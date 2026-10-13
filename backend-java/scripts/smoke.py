#!/usr/bin/env python3
"""Run a packaged Java backend against a fresh, isolated Go-schema SQLite database."""
import argparse
import base64
import gzip
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import secrets
import sqlite3
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from smoke_kb_read import run as check_shared_knowledge_base_reads
from smoke_system_parser import run as check_system_parsers
from smoke_parser_config import run as check_parser_config


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path)
    args = parser.parse_args()
    jar = args.jar.resolve()
    if not jar.is_file():
        parser.error("executable jar does not exist")
    root = Path(__file__).resolve().parents[2]
    folder = Path(tempfile.mkdtemp(prefix="weknora-java-smoke-"))
    database = folder / "test.db"
    secret = secrets.token_urlsafe(32)
    key = "sk-isolated-" + secrets.token_urlsafe(20)
    tokens = {}
    with sqlite3.connect(database) as sql:
        for name in ("000000_init", "000003_knowledge_base_auto_tag_config",
                     "000005_messages_attachments_and_invitation_fields", "000007_system_admin_and_settings",
                     "000011_principal_model", "000021_knowledge_profiles"):
            sql.executescript((root / "migrations/sqlite" / (name + ".up.sql")).read_text())
        sql.execute("INSERT INTO tenants(id,name,business) VALUES(1,'Smoke','isolated'),(3,'Orphan','isolated')")
        for user, tenant, role in (("owner", 1, "owner"), ("viewer", 1, "viewer"), ("orphan", 3, None),
                                  ("invitee", None, None), ("decliner", None, None)):
            sql.execute("INSERT INTO users(id,username,email,password_hash,tenant_id) VALUES(?,?,?,'unused',?)",
                        (user, user, user + "@test.invalid", tenant))
            if role:
                sql.execute("INSERT INTO tenant_members(user_id,tenant_id,role,status) VALUES(?,?,?,'active')",
                            (user, tenant, role))
            encode = lambda value: base64.urlsafe_b64encode(json.dumps(value, separators=(",", ":")).encode()).rstrip(b"=")
            payload = encode({"alg": "HS256", "typ": "JWT"}) + b"." + encode({"user_id": user, "tenant_id": tenant or 0,
                           "type": "access", "exp": int(time.time()) + 3600})
            token = (payload + b"." + base64.urlsafe_b64encode(hmac.new(secret.encode(), payload, hashlib.sha256).digest()).rstrip(b"=")).decode()
            tokens[user] = token
            sql.execute("INSERT INTO auth_tokens(id,user_id,token,token_type,expires_at,is_revoked) VALUES(?,?,?,'access_token','2099-01-01 00:00:00',false)",
                        (str(uuid.uuid4()), user, token))
        sql.execute("INSERT INTO tenant_api_keys(tenant_id,name,key_hash,api_key,full_access) VALUES(1,'Smoke',?,?,true)",
                    (hashlib.sha256(key.encode()).hexdigest(), key))
    environment = os.environ.copy()
    environment.update(JAVA_DB_URL="jdbc:sqlite:" + str(database), JWT_SECRET=secret,
                       JAVA_DB_POOL_SIZE="1", JAVA_GO_BRIDGE_ENABLED="false", WEKNORA_TASKS_ENABLED="false",
                       DOCREADER_TRANSPORT="http", DOCREADER_ADDR="",
                       SSRF_WHITELIST="", SSRF_WHITELIST_EXTRA="127.0.0.1",
                       SSRF_DNS_WHITELIST_ONLY="true", WEKNORA_TENANT_ENABLE_RBAC="true")
    log = folder / "server.log"
    checks = []
    with log.open("wb") as output:
        process = subprocess.Popen(["java", "-jar", str(jar), "--server.address=127.0.0.1", "--server.port=0",
                                    "--spring.datasource.driver-class-name=org.sqlite.JDBC", "--debug=false",
                                    "--logging.level.root=WARN",
                                    "--logging.level.org.springframework.boot.web.embedded.tomcat.TomcatWebServer=INFO"],
                                   env=environment, stdout=output, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 45
            while True:
                match = re.search(r"Tomcat started on port (\d+)", log.read_text(errors="replace"))
                if match:
                    base = "http://127.0.0.1:" + match.group(1)
                    break
                if process.poll() is not None or time.monotonic() > deadline:
                    raise AssertionError("isolated Java startup failed; see " + str(log))
                time.sleep(0.1)

            def request(method, path, status, body=None, user="owner", api_key=False):
                headers = {}
                if user:
                    headers["Authorization"] = "Bearer " + tokens[user]
                if api_key:
                    headers["X-API-Key"] = key
                if body is not None:
                    headers["Content-Type"] = "application/json"
                    if not isinstance(body, bytes):
                        body = json.dumps(body).encode()
                req = urllib.request.Request(base + path, data=body, headers=headers, method=method)
                try:
                    with urllib.request.urlopen(req, timeout=10) as response:
                        code, data = response.status, response.read()
                except urllib.error.HTTPError as error:
                    code, data = error.code, error.read()
                assert code == status, (method, path, "expected", status, "actual", code)
                checks.append((method, path, code))
                return json.loads(data) if data else None

            assert request("GET", "/health", 200, user=None) == {"status": "ok"}
            request("GET", "/api/v1/knowledge-bases", 401, user=None)
            assert request("GET", "/api/v1/knowledge-bases", 200)["data"] == []
            created = request("POST", "/api/v1/knowledge-bases", 201,
                              {"id": "smoke-kb", "name": "Smoke", "tenant_id": 3,
                               "storage_config": {"secret_key": "isolated-storage-secret"}})["data"]
            assert created["tenant_id"] == 1 and created["storage_config"]["secret_key"] == ""
            request("PUT", "/api/v1/knowledge-bases/smoke-kb", 200, {"name": "Changed"})
            assert request("GET", "/api/v1/knowledge-bases/smoke-kb", 200)["data"]["name"] == "Changed"
            request("POST", "/api/v1/chunker/preview", 401, {"text": "hi"}, user=None)
            fixtures = json.loads(gzip.decompress((root / "backend-java/src/test/resources/go-chunker-preview.json.gz").read_bytes()))
            for index in (0, 1, 2, 7, 13, 17, 23, 24, 29, 34, 36, 40, 59, 64, 67, 73, 78, 79, 85, 86, 87, 88, 89):
                case = fixtures[index]
                actual = request("POST", "/api/v1/chunker/preview", case["status"],
                                 base64.b64decode(case["body_base64"]), user="viewer")
                assert actual == case["response"], ("Go HTTP response differs", case["name"])
            request("GET", "/api/v1/chunker/preview", 405)
            request("POST", "/api/v1/chunker/preview", 200, {"text": "hi"}, user=None, api_key=True)
            # Every invitation route runs against the packaged server and real Go tables.
            invitation = request("POST", "/api/v1/tenants/1/invitations", 201,
                                 {"email": "invitee@test.invalid", "role": "viewer"})["data"]
            assert request("GET", "/api/v1/me/invitations/pending-count", 200, user="invitee")["data"]["pending_count"] == 1
            assert request("GET", "/api/v1/me/invitations", 200, user="invitee")["data"]["total"] == 1
            accepted = request("POST", "/api/v1/me/invitations/" + str(invitation["id"]) + "/accept",
                               200, user="invitee")["data"]["membership"]
            assert accepted["tenant_id"] == 1 and accepted["role"] == "viewer"
            assert request("GET", "/api/v1/me/invitations/pending-count", 200, user="invitee")["data"]["pending_count"] == 0
            declined = request("POST", "/api/v1/tenants/1/invitations", 201,
                               {"email": "decliner@test.invalid", "role": "contributor"})["data"]
            request("POST", "/api/v1/me/invitations/" + str(declined["id"]) + "/decline", 200, user="decliner")
            link = request("POST", "/api/v1/tenants/1/invite-links", 201, {"role": "contributor"})["data"]
            assert "token" not in link
            link_token = urllib.parse.parse_qs(urllib.parse.urlsplit(link["invite_url"]).query)["token"][0]
            assert len(link_token) == 43
            assert request("POST", "/api/v1/auth/invitations/lookup", 200, {"token": link_token},
                           user=None)["data"]["tenant_id"] == 1
            request("POST", "/api/v1/me/invitations/accept-by-token", 200, {"token": link_token}, user="orphan")
            request("POST", "/api/v1/me/invitations/accept-by-token", 200, {"token": link_token}, user="orphan")
            owner_list = request("GET", "/api/v1/tenants/1/invitations", 200)["data"]["invitations"]
            assert any(row["id"] == link["id"] and row["accepted_count"] == 1 for row in owner_list)
            viewer_list = request("GET", "/api/v1/tenants/1/invitations", 200, user="viewer")["data"]["invitations"]
            assert all(not row.get("token") and not row.get("invite_url") for row in viewer_list)
            request("GET", "/api/v1/me/invitations", 403, user=None, api_key=True)
            request("GET", "/api/v1/me/invitations", 401, user=None)
            invited_registration = request("POST", "/api/v1/auth/register-by-invite", 201,
                                           {"token": link_token, "email": "NEW@TEST.INVALID",
                                            "username": " Smoke New ", "password": "Strong123!"}, user=None)
            assert invited_registration["user"]["email"] == "new@test.invalid"
            assert invited_registration["user"]["username"] == "Smoke New"
            assert invited_registration["active_tenant"]["id"] == 1
            tokens["registered"] = invited_registration["token"]
            assert request("GET", "/api/v1/auth/me", 200, user="registered")["data"]["user"]["email"] == "new@test.invalid"
            request("DELETE", "/api/v1/tenants/1/invitations/" + str(link["id"]), 200)
            request("POST", "/api/v1/auth/invitations/lookup", 410, {"token": link_token}, user=None)
            check_shared_knowledge_base_reads(database, request, key)
            check_system_parsers(database, request, key)
            check_parser_config(database, request, key)
            request("POST", "/api/v1/tenants/1/leave", 403, user=None, api_key=True)
            request("POST", "/api/v1/tenants/1/leave", 409)
            request("POST", "/api/v1/tenants/1/leave", 200, user="viewer")
            request("GET", "/api/v1/tenants", 401, user="viewer")
            request("GET", "/api/v1/tenants", 200, user="orphan")
            with sqlite3.connect(database) as sql:
                assert sql.execute("SELECT role FROM tenant_members WHERE user_id='orphan' AND tenant_id=3").fetchone() == ("owner",)
                assert sql.execute("SELECT tenant_id FROM users WHERE id='invitee'").fetchone() == (1,)
                assert sql.execute("SELECT status FROM tenant_invitations WHERE id=?", (invitation["id"],)).fetchone() == ("accepted",)
                assert sql.execute("SELECT status FROM tenant_invitations WHERE id=?", (declined["id"],)).fetchone() == ("declined",)
                assert sql.execute("SELECT status,accepted_count FROM tenant_invitations WHERE id=?", (link["id"],)).fetchone() == ("revoked", 2)
                assert sql.execute("SELECT tenant_id FROM users WHERE email='new@test.invalid'").fetchone() == (1,)
                assert sql.execute("SELECT is_revoked FROM auth_tokens WHERE user_id='viewer'").fetchone() == (1,)
                assert sql.execute("SELECT count(*) FROM chunks").fetchone() == (0,)
                assert sql.execute("SELECT count(*) FROM knowledges WHERE id NOT LIKE 'read-%'").fetchone() == (0,)
                row = sql.execute("SELECT name,tenant_id,cos_config FROM knowledge_bases WHERE id='smoke-kb'").fetchone()
                assert row[:2] == ("Changed", 1)
                assert json.loads(row[2])["secret_key"] == "isolated-storage-secret"
            print(json.dumps({"http_checks": len(checks), "status": "passed", "jar": str(jar), "log": str(log)}, ensure_ascii=False))
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)


if __name__ == "__main__":
    main()
