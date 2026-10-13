# Go → Java 迁移清单

本清单基于当前工作区真实代码生成，包含尚未提交的用户修改。Go 是行为基准；Java 桥接不算迁移完成。

运行：`python3 backend-java/migration/generate.py`（需要 Go 和 Python 3，无项目依赖下载）。

- 实际显式 HTTP 路由：**482**，注册语法位置 **480/480**，动态未解析 **0**。
- Swagger：**376** 个操作，覆盖实际路由 **371** 个；差异详见 coverage-report.json。
- Go：**1263** 个实现文件、**1190** 个测试文件、**7859** 个测试/基准/Fuzz 函数（internal/cmd/client）。
- 任务注册：Asynq **22**、同步执行器 **22**。

## 可追踪文件

| 文件 | 内容 |
|---|---|
| routes.json / routes.csv | 每个方法、真实路径、middleware、RBAC/API-key 权限、handler 定义、条件、Swagger/前端引用、迁移状态 |
| router-ast.json | Go AST 解析结果、所有未解析/未访问注册点以及 noAuthAPI |
| source-files.json / source-files.csv | 全部 Go 文件、内容哈希、测试函数、数据库/任务调用位置 |
| modules.json | 路由模块与所有后端层；数据库、异步任务、外部依赖审计状态 |
| tasks.json | Asynq/同步任务类型、handler、注册点、验证要求 |
| supporting-artifacts.json | 数据库迁移、docreader protobuf、配置/部署文件 |
| coverage-report.json | 路由覆盖、Swagger 和前端差异、静态解析限制 |
| status.json | 人工维护的状态覆盖；重生成保留该文件 |

## 状态与验收

状态：`not_started → in_progress → implemented → verified`；另有 `bridge_only`、`blocked`。状态覆盖键：routes 使用 `METHOD /真实路径`，modules 使用下表模块名，files 使用仓库相对路径，tasks 使用 `asynq:TypeDocumentProcess` / `sync:TypeDocumentProcess`。

`implemented` 仅表示 Java 原生实现已存在；`verified` 必须有 Go/Java 对照证据，覆盖 JSON/null/空集合/时间/ID、错误响应、租户和资源权限、事务/数据库状态、索引/文件副作用、任务重试/取消/死信、SSE 顺序/断线/继续生成。禁止在生产环境双写做验证。数据库调用的文本证据和文件名推测不能代替完整依赖审计。

## 推荐实施顺序

1. 固定当前 Go 行为快照，测试环境运行 Go；完成 Java 配置、健康检查、数据库连接。
2. 认证/JWT/API Key、租户/RBAC/跨租户及资源权限，先验证拒绝路径。
3. 知识库完整 CRUD 链路、模型/存储配置；每个接口连同 Service/Repository 实现。
4. 文档上传/解析（含 Go Anydoc）/分块/Embedding/索引及异步任务生命周期。
5. 检索/重排/问答/消息/SSE 和取消/重连，固定模型输出做对照。
6. Agent/工具/MCP/沙盒/数据源/IM/embed/wiki/memory 及全部存储和模型适配器。
7. Docker/监控/优雅停机、任务排空/归属、分模块切流和回滚；全部 verified 才移除 Go。

## 路由模块

| 模块 | 路由数 | 状态 |
|---|---:|---|
| NewRouter | 8 | in_progress |
| RegisterAuthRoutes | 17 | in_progress |
| RegisterChatRoutes | 3 | not_started |
| RegisterChunkRoutes | 10 | in_progress |
| RegisterChunkerDebugRoutes | 1 | in_progress |
| RegisterCustomAgentRoutes | 9 | not_started |
| RegisterDataSourceRoutes | 17 | not_started |
| RegisterEmbedChannelRoutes | 9 | not_started |
| RegisterEmbedPublicRoutes | 20 | not_started |
| RegisterEvaluationRoutes | 2 | not_started |
| RegisterFAQRoutes | 13 | not_started |
| RegisterIMChannelRoutes | 8 | not_started |
| RegisterIMRoutes | 2 | not_started |
| RegisterImageAttrRoutes | 1 | not_started |
| RegisterInitializationRoutes | 17 | not_started |
| RegisterKnowledgeBaseActivityRoutes | 1 | not_started |
| RegisterKnowledgeBaseRoutes | 13 | in_progress |
| RegisterKnowledgeRoutes | 32 | in_progress |
| RegisterKnowledgeTagRoutes | 4 | not_started |
| RegisterMCPEndpointRoutes | 7 | not_started |
| RegisterMCPServerRoutes | 3 | not_started |
| RegisterMCPServiceRoutes | 22 | not_started |
| RegisterMemoryRoutes | 16 | not_started |
| RegisterMessageRoutes | 4 | not_started |
| RegisterModelRoutes | 12 | in_progress |
| RegisterMyEnvVarRoutes | 5 | not_started |
| RegisterMyInvitationRoutes | 5 | in_progress |
| RegisterOrganizationRoutes | 35 | not_started |
| RegisterSandboxConfigRoutes | 21 | not_started |
| RegisterSandboxDesktopRoutes | 1 | not_started |
| RegisterSandboxTerminalRoutes | 1 | not_started |
| RegisterSessionRoutes | 37 | not_started |
| RegisterSkillRoutes | 7 | not_started |
| RegisterStorageBackendRoutes | 9 | not_started |
| RegisterSystemAdminRoutes | 21 | not_started |
| RegisterSystemRoutes | 9 | in_progress |
| RegisterTenantRoutes | 26 | in_progress |
| RegisterUserFavoriteRoutes | 3 | not_started |
| RegisterVectorStoreRoutes | 8 | not_started |
| RegisterWeKnoraCloudRoutes | 2 | not_started |
| RegisterWebSearchProviderRoutes | 10 | not_started |
| RegisterWebSearchRoutes | 1 | not_started |
| RegisterWikiPageRoutes | 22 | not_started |
| serveFilesWithResources | 1 | not_started |
| serveKBScopedFiles | 1 | not_started |
| serveMessageScopedFiles | 1 | not_started |
| servePresignedFiles | 2 | not_started |
| servePresignedPreview | 1 | not_started |
| serveResourceGrants | 2 | not_started |

## 静态分析边界

- All possible conditional registrations are included; nil dependencies/build edition/config change runtime routes.
- Conditional Use middleware is explicitly annotated; branches are not evaluated.
- All 480 registration sites is a snapshot count, not a future invariant; generation fails on unvisited/unresolved sites.
- Static SPA middleware and framework redirects/CORS OPTIONS are middleware behavior, not explicit API routes.
- Swagger matching normalizes parameter names and trailing slash; router path remains authoritative.
- Frontend extraction is string/regex evidence, not a TypeScript AST; dynamic URL composition and external call sites require audit.
- Database/task evidence is lexical; module service/repository candidates are filename heuristics. Transaction and transitive side effects remain pending.
- No status is inferred from a Java bridge or matching route name; verified requires differential contract tests.
