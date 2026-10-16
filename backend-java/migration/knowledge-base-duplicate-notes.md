# 知识库配置复制迁移

新增原生 `POST /api/v1/knowledge-bases/:id/duplicate`，对应 Go 的**仅配置复制**。返回 201，生成新的知识库 UUID 和时间，不复制文档、chunk、索引、Wiki 内容、个人置顶或后台任务。接口保持 in_progress；全量迁移目标未完成。

## 行为

- Contributor 角色门槛；API key 需要 manage_kbs 或 full access，并受源 KB 白名单约束。共享来源即使可读/可编辑，仍因来源不属于调用租户而拒绝复制。
- 使用 Go 数据库配置解码，再经公开 JSON 序列化/请求反序列化克隆；保留 Go 默认值、凭据脱敏和 legacy provider 回填语义。不能替换成 SQL 行复制或普通 create 接口。
- 清空生成的 profile、运行时计数、删除/临时/置顶状态；creator 和审计保留原始调用身份，system-<数字> 合成用户不写 creator。API key 有实际租户用户时使用该用户，无用户才使用合成身份。
- 按调用语言生成名称和递增后缀，忽略其他租户、已删除、临时知识库的名字；名字列表查询/解码失败保留 Go 的首选名称回退。
- 保留 storage_backend_id；保留并验证 vector_store_id 的格式、归属和可用性；不套用普通 create 的所有绑定验证。
- 配置插入独立提交，审计 best-effort；审计表不可用不能回滚成功复制。插入失败保持数据库驱动对应的错误文本。

实现：[service](../src/main/java/com/tencent/weknora/knowledge/KnowledgeBaseDuplicateService.java)、[controller](../src/main/java/com/tencent/weknora/knowledge/KnowledgeBaseDuplicateController.java)。

## 验证

实际 Go SQLite/PostgreSQL 各导出 45 个 HTTP 场景；Java 每种数据库回放 45 场景并额外运行 10 个真实 JWT/API-key 认证请求，各 55 项全部通过。检查完整响应、原始 SQL 数据及 JSON 存储类型、时间和 UUID、原调用方审计、所有来源行保留及无复制任务。PostgreSQL 使用全部 115 个 Go 迁移，连接池 1。

包含 duplicate、移动目标、知识库、认证、权限和 bridge 的 206 项 / 10 类相关回归全部通过。修正了旧移动目标 Go 夹具的 TenantRole 类型，重新导出的 31 场景响应/状态与此前一致，本批回归覆盖了新的资源。

同一候选 jar 在 single/go 两种 Java SQL worker 拓扑的实际 HTTP 验收均实际退出 0：每种 2 次真实上传、4 个完成任务、2 次 embedding，随后 4 次复制。检查英文/韩文重名、共享/Viewer/API-key 拒绝、密钥清除、原来源文档、chunk、物理 vec/FTS、文件字节、配额、个人置顶、任务记录不变。go 拓扑名称不表示消费 Go Asynq。

SHA-256：`b0e28cc8d9257e9deeb3bcf7b07edae5163d87897465f3a211576fdaad9c4e0c`。src/pom 与 smoke runner 的 1015 个输入哈希已核对。此前 r6 全量 10,012 项通过属于旧快照；本批没有重新执行当前代码完整测试集。

## 边界与后续

向量库成功/失败分支差分测试使用受控 registry，不能据此声称所有真实外部引擎已支持。全部并发配置/权限变化、畸形历史数据和切换恢复仍需后续完整验收。Go 全部 482 条接口中仍缺 362 条 Java 原生入口，仍缺 8 种后台任务 handler。

早期失败、修正后的报告哈希、Go 夹具、候选输入和同 jar 验收记录见 [checkpoint](knowledge-base-duplicate-checkpoint-2026-10-16.json)。复现命令：

```sh
go run -tags sqlite_fts5 backend-java/src/test/fixtures/knowledge_duplicate_fixture.go /tmp/go-duplicate.json
python3 backend-java/scripts/postgres-duplicate-parity.py
python3 backend-java/scripts/processing_smoke.py <jar> <vec-library> --duplicate --topology single
python3 backend-java/scripts/processing_smoke.py <jar> <vec-library> --duplicate --topology go
```
