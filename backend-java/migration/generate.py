#!/usr/bin/env python3
"""Generate a reproducible Go→Java migration inventory from the working tree.

Requires Python 3 and Go; no Python packages or project Go dependencies.
All outputs stay in this directory. status.json is human-owned and preserved.
Static evidence is not proof of Java implementation or behavioral parity.
"""
from __future__ import annotations

import csv
import hashlib
import json
import re
import subprocess
from collections import Counter, defaultdict
from pathlib import Path

OUT = Path(__file__).resolve().parent
ROOT = OUT.parent.parent
METHODS = {"GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "CONNECT", "TRACE"}
VALID_STATUS = {"not_started", "in_progress", "implemented", "verified", "bridge_only", "blocked"}


def rel(p: Path) -> str:
    return p.relative_to(ROOT).as_posix()


def write_json(name: str, data: object) -> None:
    (OUT / name).write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n")


def csv_write(name: str, rows: list[dict], fields: list[str]) -> None:
    with (OUT / name).open("w", newline="") as f:
        writer = csv.DictWriter(f, fields, lineterminator="\n")
        writer.writeheader()
        for row in rows:
            writer.writerow({k: json.dumps(row.get(k), ensure_ascii=False) if isinstance(row.get(k), (list, dict)) else row.get(k, "") for k in fields})


def normalize(p: str) -> str:
    # Query suffix expressions can contain quotes/nested template literals,
    # so remove recognized suffixes before replacing path placeholders.
    p = re.sub(r"\$\{(?:qs|query|queryString|queryParams|searchParams|suffix|tail)\b.*$", "", p)
    p = re.sub(r"\$\{[^}]*\}", ":parameter", p)
    p = p.split("?")[0].split("#")[0].rstrip("/") or "/"
    p = re.sub(r"\{[^}]+\}|:[^/]+|\*[^/]+", ":parameter", p)
    return p


def path_matches(route_path: str, reference_path: str) -> bool:
    template = normalize(route_path)
    pattern = re.escape(template).replace(":parameter", "[^/]+")
    # Catch-all paths accept slashes; normal parameter references remain
    # comparable to Swagger's {slug} notation.
    if "/*" in route_path:
        pattern = pattern.rsplit("[^/]+", 1)[0] + ".+"
    return re.fullmatch(pattern, normalize(reference_path)) is not None


def frontend_refs() -> list[dict]:
    refs = []
    helpers = {"get": "GET", "getDown": "GET", "post": "POST", "postUpload": "POST", "put": "PUT", "del": "DELETE", "patch": "PATCH"}
    for p in sorted((ROOT / "frontend/src/api").rglob("*.ts")):
        if ".test." in p.name:
            continue
        text = p.read_text()
        # String evidence is deliberately retained when a method cannot be
        # inferred. Variable URLs/computed expressions need manual review.
        for match in re.finditer(r"([\"'`])((?:/api/v1|/files|/mcp/)[^\n]*?)\1", text):
            url = match.group(2)
            prefix = text[max(0, match.start() - 100):match.start()]
            call = re.search(r"\b(getDown|postUpload|get|post|put|del|patch)\s*(?:<[^>]*>)?\s*\(\s*$", prefix)
            method = helpers.get(call.group(1)) if call else None
            if method is None:
                after = text[match.end():match.end() + 500]
                m = re.search(r"\bmethod\s*:\s*[\"'](GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)[\"']", after)
                if m:
                    method = m.group(1)
            refs.append({"source": rel(p), "line": text.count("\n", 0, match.start()) + 1, "url_expression": url, "method": method, "canonical_path": normalize(url), "confidence": "path_and_method" if method else "path_only"})
    return refs


def source_files() -> list[dict]:
    files = []
    for base in ("internal", "cmd", "client"):
        if not (ROOT / base).exists():
            continue
        for p in sorted((ROOT / base).rglob("*.go")):
            parts = p.relative_to(ROOT).parts
            layer = "/".join(parts[:3]) if parts[:2] == ("internal", "application") else "/".join(parts[:2])
            text = p.read_text()
            test_names = re.findall(r"^func\s+((?:Test|Benchmark|Fuzz)\w+)\s*\(", text, re.M)
            evidence = []
            # Location evidence, not classification of the full call graph.
            pattern = r"\b(?:SELECT|INSERT\s+INTO|UPDATE|DELETE\s+FROM)\b|\.(?:Create|Save|Delete|Updates?|Find|First|Scan|Raw|Exec|Transaction|Begin|Commit|Rollback|Enqueue|EnqueueContext|HandleFunc|RegisterHandler)\s*\("
            for n, line in enumerate(text.splitlines(), 1):
                if re.search(pattern, line):
                    evidence.append({"line": n, "code": line.strip()[:240]})
            files.append({"source": rel(p), "layer": layer, "kind": "test" if p.name.endswith("_test.go") else "implementation", "lines": len(text.splitlines()), "sha256": hashlib.sha256(text.encode()).hexdigest(), "test_names": test_names, "database_and_task_call_evidence": evidence, "status": "not_started"})
    return files


def handler_index() -> tuple[dict, dict]:
    router = (ROOT / "internal/router/router.go").read_text()
    fields = dict(re.findall(r"^\s*(\w+Handler)\s+\*([\w.]+)", router, re.M))
    methods = defaultdict(list)
    for p in sorted((ROOT / "internal/handler").rglob("*.go")):
        if p.name.endswith("_test.go"):
            continue
        text = p.read_text()
        for m in re.finditer(r"^func\s+\(\w+\s+\*?(\w+)\)\s+(\w+)\s*\(", text, re.M):
            package = "session" if "/session/" in p.as_posix() else "handler"
            methods[(package + "." + m.group(1), m.group(2))].append({"source": rel(p), "line": text.count("\n", 0, m.start()) + 1})
    return fields, methods


def tasks() -> list[dict]:
    constants = {}
    for p in (ROOT / "internal/types").rglob("*.go"):
        if p.name.endswith("_test.go"):
            continue
        constants.update(re.findall(r'\b(Type\w+)\s*=\s*"([^"]+)"', p.read_text()))
    rows = []
    for name, pattern in (("task.go", r"mux\.HandleFunc\(types\.(\w+),\s*([^\n]+?)\)"), ("sync_task.go", r"(?:params\.\w+|executor|e)\.RegisterHandler\(types\.(\w+),\s*([^\n]+?)\)")):
        p = ROOT / "internal/router" / name
        text = p.read_text()
        for m in re.finditer(pattern, text):
            rows.append({"task_constant": m.group(1), "task_type": constants.get(m.group(1)), "handler": m.group(2), "source": rel(p), "line": text.count("\n", 0, m.start()) + 1, "mode": "asynq" if name == "task.go" else "sync", "status": "not_started", "required_validation": ["retry", "timeout", "cancel", "idempotency", "dead_letter", "scope_and_tenant", "drain_cutover"]})
    return rows


def status_for(statuses: dict, kind: str, key: str) -> str:
    value = statuses.get(kind, {}).get(key, "not_started")
    if value not in VALID_STATUS:
        raise ValueError(f"Invalid {kind} status for {key}: {value}")
    return value


def main() -> None:
    ast_data = json.loads(subprocess.check_output(["go", "run", str(OUT / "inventory_ast.go"), str(ROOT)], cwd=ROOT))
    write_json("router-ast.json", ast_data)
    if ast_data.get("unresolved") or ast_data.get("unvisited_registration_sites"):
        raise SystemExit("Router coverage incomplete; review router-ast.json before migration.")
    statuses_path = OUT / "status.json"
    if not statuses_path.exists():
        write_json("status.json", {"routes": {}, "modules": {}, "files": {}, "tasks": {}, "note": "Human-maintained overrides. verified requires differential parity tests; bridge_only is not migrated."})
    statuses = json.loads(statuses_path.read_text())
    swagger = json.loads((ROOT / "docs/swagger.json").read_text())
    documented = {}
    base = swagger.get("basePath", "")
    for path, item in swagger["paths"].items():
        for method, operation in item.items():
            if method.upper() in METHODS:
                full = base.rstrip("/") + "/" + path.lstrip("/")
                documented[(method.upper(), normalize(full))] = {"path": full, "method": method.upper(), "operation_id": operation.get("operationId"), "summary": operation.get("summary"), "security": operation.get("security", swagger.get("security")), "parameters": operation.get("parameters", []), "responses": operation.get("responses", {})}
    frontend = frontend_refs()
    fields, handlers = handler_index()
    files = source_files()
    for f in files:
        f["status"] = status_for(statuses, "files", f["source"])
    routes = ast_data["routes"]
    route_keys = {route["method"] + " " + route["path"] for route in routes}
    unknown_overrides = set(statuses.get("routes", {})) - route_keys
    if unknown_overrides:
        raise SystemExit("Status overrides do not match actual registered routes: " + ", ".join(sorted(unknown_overrides)))
    seen = set()
    by_module = defaultdict(list)
    allowlist = ast_data["no_auth_allowlist"]
    for route in routes:
        key = route["method"] + " " + route["path"]
        if key in seen:
            raise ValueError(f"Duplicate resolved route: {key}")
        seen.add(key)
        route["id"] = key
        route["status"] = status_for(statuses, "routes", key)
        canonical = normalize(route["path"])
        route["swagger_matches"] = [d for d in documented.values() if d["method"] == route["method"] and path_matches(route["path"], d["path"])]
        route["swagger"] = documented.get((route["method"], canonical)) or next(iter(route["swagger_matches"]), None)
        route["frontend_refs"] = [f for f in frontend if path_matches(route["path"], f["canonical_path"]) and f["method"] in (None, route["method"])]
        global_auth = any("middleware.Auth(" in m for m in route["middleware"])
        public_allowlisted = route["method"] in allowlist.get(route["path"], [])
        route["auth"] = "global_auth_allowlisted_public" if global_auth and public_allowlisted else "global_auth" if global_auth else "pre_global_auth_or_self_authenticated"
        route["api_key_policy"] = route["api_key_policy"] or ("default_deny_at_api_v1_gate" if any("apiKeyAuthorizer.Middleware" in m for m in route["middleware"]) else "outside_api_v1_gate_review_handler")
        route["authorization_guards"] = [m for m in route["middleware"] if re.search(r"rbacGuards\.|middleware\.(?:Require|Allow|Deny|EmbedAuth|MCPEndpointAuth|PublicAuth)", m)]
        match = re.match(r"params\.(\w+)\.(\w+)$", route["handler"])
        route["handler_definitions"] = handlers.get((fields.get(match.group(1), ""), match.group(2)), []) if match else []
        route["database_audit"] = "required: inspect handler→service→repository, transaction boundaries and index/storage side effects"
        route["required_tests"] = ["request_response_error_contract", "authorization_and_tenant_isolation", "database_and_external_side_effects"]
        if any(t in route["handler"].lower() for t in ("qa", "stream", "suggestion")):
            route["required_tests"].append("sse_order_cancel_reconnect_persistence")
        by_module[route["function"]].append(route)
    task_rows = tasks()
    for t in task_rows:
        t["status"] = status_for(statuses, "tasks", t["mode"] + ":" + t["task_constant"])
    modules = []
    for name, group in sorted(by_module.items()):
        handler_sources = sorted({d["source"] for r in group for d in r["handler_definitions"]})
        source_tokens = {Path(s).stem for s in handler_sources}
        candidate_files = [f for f in files if f["kind"] == "implementation" and f["source"].startswith("internal/application/") and any(token and token in Path(f["source"]).stem for token in source_tokens)]
        module_files = sorted(set(handler_sources) | {f["source"] for f in candidate_files})
        test_sources = [f["source"] for f in files if f["kind"] == "test" and any(token and token in Path(f["source"]).stem for token in source_tokens)]
        modules.append({"module": name, "route_count": len(group), "router_sources": sorted({r["source"] for r in group}), "handler_sources": handler_sources, "service_repository_candidates": [f["source"] for f in candidate_files], "candidate_matching": "filename tokens only; requires transitive call graph review", "database_and_task_evidence": [{"source": f["source"], "calls": f["database_and_task_call_evidence"]} for f in files if f["source"] in module_files and f["database_and_task_call_evidence"]], "tests_candidates": test_sources, "database_audit_status": "pending", "task_dependency_audit_status": "pending; consult tasks.json and Enqueue evidence", "external_dependency_audit_status": "pending", "status": status_for(statuses, "modules", name)})
    # Route-free backend layers must remain visible and independently tracked.
    for layer in sorted({f["layer"] for f in files}):
        group = [f for f in files if f["layer"] == layer]
        name = "layer:" + layer
        modules.append({"module": name, "route_count": 0, "implementation_files": [f["source"] for f in group if f["kind"] == "implementation"], "test_files": [f["source"] for f in group if f["kind"] == "test"], "database_audit_status": "pending", "task_dependency_audit_status": "pending", "external_dependency_audit_status": "pending", "status": status_for(statuses, "modules", name)})
    swagger_only = [d for d in documented.values() if not any(d["method"] == r["method"] and path_matches(r["path"], d["path"]) for r in routes)]
    frontend_only = [f for f in frontend if not any(path_matches(r["path"], f["canonical_path"]) and f["method"] in (None, r["method"]) for r in routes)]
    manifest = []
    for base_name, extensions in (("migrations", {".sql"}), ("docreader", {".proto"}), ("config", {".yaml", ".yml", ".json"}), ("docker", {".yaml", ".yml", ".sh"})):
        for p in sorted((ROOT / base_name).rglob("*")):
            if p.is_file() and p.suffix in extensions:
                manifest.append({"source": rel(p), "sha256": hashlib.sha256(p.read_bytes()).hexdigest(), "status": "not_started"})
    report = {"generation": "current working tree; input SHA-256 hashes in source-files.json", "route_count": len(routes), "registration_sites": ast_data["candidate_registration_sites"], "visited_registration_sites": ast_data["visited_registration_sites"], "unresolved_route_sites": ast_data.get("unresolved") or [], "unvisited_route_sites": ast_data["unvisited_registration_sites"], "swagger_operations": len(documented), "routes_with_swagger": sum(bool(r["swagger"]) for r in routes), "routes_without_swagger": [r["id"] for r in routes if not r["swagger"]], "swagger_without_actual_routes": swagger_only, "frontend_api_literal_refs": len(frontend), "routes_with_frontend_refs": sum(bool(r["frontend_refs"]) for r in routes), "frontend_literals_without_route_match": frontend_only, "go_implementation_files": sum(f["kind"] == "implementation" for f in files), "go_test_files": sum(f["kind"] == "test" for f in files), "go_test_functions": sum(len(f["test_names"]) for f in files), "async_task_registrations": sum(t["mode"] == "asynq" for t in task_rows), "sync_task_registrations": sum(t["mode"] == "sync" for t in task_rows), "module_count": len(modules), "status_counts": dict(Counter(r["status"] for r in routes)), "limitations": ["All possible conditional registrations are included; nil dependencies/build edition/config change runtime routes.", "Conditional Use middleware is explicitly annotated; branches are not evaluated.", "All 480 registration sites is a snapshot count, not a future invariant; generation fails on unvisited/unresolved sites.", "Static SPA middleware and framework redirects/CORS OPTIONS are middleware behavior, not explicit API routes.", "Swagger matching normalizes parameter names and trailing slash; router path remains authoritative.", "Frontend extraction is string/regex evidence, not a TypeScript AST; dynamic URL composition and external call sites require audit.", "Database/task evidence is lexical; module service/repository candidates are filename heuristics. Transaction and transitive side effects remain pending.", "No status is inferred from a Java bridge or matching route name; verified requires differential contract tests."]}
    write_json("routes.json", routes)
    csv_write("routes.csv", routes, ["id", "method", "path", "function", "source", "line", "handler", "handler_definitions", "auth", "authorization_guards", "api_key_policy", "conditions", "swagger", "frontend_refs", "database_audit", "required_tests", "status"])
    write_json("source-files.json", files)
    csv_write("source-files.csv", files, ["source", "layer", "kind", "lines", "sha256", "test_names", "status"])
    write_json("tasks.json", task_rows)
    write_json("modules.json", modules)
    write_json("supporting-artifacts.json", manifest)
    write_json("coverage-report.json", report)
    lines = ["# Go → Java 迁移清单", "", "本清单基于当前工作区真实代码生成，包含尚未提交的用户修改。Go 是行为基准；Java 桥接不算迁移完成。", "", "运行：`python3 backend-java/migration/generate.py`（需要 Go 和 Python 3，无项目依赖下载）。", "", f"- 实际显式 HTTP 路由：**{len(routes)}**，注册语法位置 **{ast_data['visited_registration_sites']}/{ast_data['candidate_registration_sites']}**，动态未解析 **0**。", f"- Swagger：**{len(documented)}** 个操作，覆盖实际路由 **{report['routes_with_swagger']}** 个；差异详见 coverage-report.json。", f"- Go：**{report['go_implementation_files']}** 个实现文件、**{report['go_test_files']}** 个测试文件、**{report['go_test_functions']}** 个测试/基准/Fuzz 函数（internal/cmd/client）。", f"- 任务注册：Asynq **{report['async_task_registrations']}**、同步执行器 **{report['sync_task_registrations']}**。", "", "## 可追踪文件", "", "| 文件 | 内容 |", "|---|---|", "| routes.json / routes.csv | 每个方法、真实路径、middleware、RBAC/API-key 权限、handler 定义、条件、Swagger/前端引用、迁移状态 |", "| router-ast.json | Go AST 解析结果、所有未解析/未访问注册点以及 noAuthAPI |", "| source-files.json / source-files.csv | 全部 Go 文件、内容哈希、测试函数、数据库/任务调用位置 |", "| modules.json | 路由模块与所有后端层；数据库、异步任务、外部依赖审计状态 |", "| tasks.json | Asynq/同步任务类型、handler、注册点、验证要求 |", "| supporting-artifacts.json | 数据库迁移、docreader protobuf、配置/部署文件 |", "| coverage-report.json | 路由覆盖、Swagger 和前端差异、静态解析限制 |", "| status.json | 人工维护的状态覆盖；重生成保留该文件 |", "", "## 状态与验收", "", "状态：`not_started → in_progress → implemented → verified`；另有 `bridge_only`、`blocked`。状态覆盖键：routes 使用 `METHOD /真实路径`，modules 使用下表模块名，files 使用仓库相对路径，tasks 使用 `asynq:TypeDocumentProcess` / `sync:TypeDocumentProcess`。", "", "`implemented` 仅表示 Java 原生实现已存在；`verified` 必须有 Go/Java 对照证据，覆盖 JSON/null/空集合/时间/ID、错误响应、租户和资源权限、事务/数据库状态、索引/文件副作用、任务重试/取消/死信、SSE 顺序/断线/继续生成。禁止在生产环境双写做验证。数据库调用的文本证据和文件名推测不能代替完整依赖审计。", "", "## 推荐实施顺序", "", "1. 固定当前 Go 行为快照，测试环境运行 Go；完成 Java 配置、健康检查、数据库连接。", "2. 认证/JWT/API Key、租户/RBAC/跨租户及资源权限，先验证拒绝路径。", "3. 知识库完整 CRUD 链路、模型/存储配置；每个接口连同 Service/Repository 实现。", "4. 文档上传/解析（含 Go Anydoc）/分块/Embedding/索引及异步任务生命周期。", "5. 检索/重排/问答/消息/SSE 和取消/重连，固定模型输出做对照。", "6. Agent/工具/MCP/沙盒/数据源/IM/embed/wiki/memory 及全部存储和模型适配器。", "7. Docker/监控/优雅停机、任务排空/归属、分模块切流和回滚；全部 verified 才移除 Go。", "", "## 路由模块", "", "| 模块 | 路由数 | 状态 |", "|---|---:|---|"]
    lines.extend(f"| {m['module']} | {m['route_count']} | {m['status']} |" for m in modules if not m["module"].startswith("layer:"))
    lines += ["", "## 静态分析边界", ""] + ["- " + s for s in report["limitations"]]
    (OUT / "README.md").write_text("\n".join(lines) + "\n")
    print(json.dumps({k: report[k] for k in ("route_count", "registration_sites", "visited_registration_sites", "swagger_operations", "routes_with_swagger", "go_implementation_files", "go_test_files", "async_task_registrations", "sync_task_registrations", "module_count", "status_counts")}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
