# 公开知识库删除：实现与验证

2026-10-16：新增原生 `DELETE /api/v1/knowledge-bases/:id`，生产配置启用 `weknora.knowledge.processing.enabled=true` 后接通 `kb:delete`。桥接 native-routes 模式归 Java 处理。当前接口仍为 in_progress，全 Go 迁移目标尚未完成。

## 行为

API key 要求 manage_kbs/full-access，继续检查 KB allow-list。先按原 Go 顺序执行创建者/管理员与 KB write grant，再在 handler 校验原调用方的 tenant 和 admin permission：同租户创建者 Viewer 可删除，共享 Editor/Admin 都不能删除源 KB。拒绝角色审计保留 Go 的未知角色 viewer 回退。

入队前重新读 KB、快照 vector_store_id 与租户 effective engines。保留 Go RetrieverEngines 的 SQLite BLOB/TEXT 区别、旧数组格式、typed 字段默认值和重复数组语义；默认引擎按 RETRIEVE_DRIVER 的顺序、映射和去重生成。

KB 仅更新 deleted_at，独立提交。审计 kb.deleted、脱离父线程中断且最多 5 秒的 pending-first 队列清理、shares 软删除依次执行。数据源按 created_at DESC 查找；每个源先记录 ID，再软删除，成功后移除可用的 schedule hook 并取消 pending/running 同步日志。失败源仍进入第二次队列取消和任务 payload，不回滚此前阶段。

最后投递 tenant/KB/data-source/effective-engine/vector-store 快照，队列 low、3 retries（SQL max_attempts=4）、2 小时 timeout。入队失败仍返回 200 `Knowledge base deleted successfully`；重复调用返回 404。请求线程不直接执行重型资源清理。后台层见 [运行时实现](knowledge-base-delete-worker-notes.md)。

## 验证

- 实际 Go SQLite/PostgreSQL 各导出 50 个公开 HTTP 场景。对照完整响应、审计、SQL 部分提交、payload 和真实 Asynq 入队参数；覆盖 scope/role/share、重复删除、绑定快照、引擎配置和各阶段故障。
- 318 项 / 8 类相关回归通过；独立 PostgreSQL 公开接口 60 项 / 1 类、后台清理 62 项 / 2 类全部通过，均观察到实际退出 0。PG 应用全部 115 个 Go 迁移、Hikari 连接池 1，拥有的容器已清理。
- 同一候选 jar 的 single/go 两种 Java SQL worker 布局均通过真实认证 HTTP：各 2 上传、1 KB 删除任务、2 embedding、0 chat。独立 SQL/文件核对物理 vec/FTS、原文、独占/共享图片与 message claim、四类 Wiki、配额、shares、数据源/同步日志、pending 和队列取消、审计，以及另一个租户的文档/向量/文件/Wiki/配额保留。
- 1042 个 src/pom、1374 个编译输入已冻结且与当前源码无漂移；默认完整 clean verify 已结束，10,443 项 / 195 类中 1 项失败，0 错误/跳过，实际退出 1。失败为 Elasticsearch HTTP 连接复用断言，原因待排查。前一批 10,382 项属于较早快照。

原始产物、报告和哈希见 [checkpoint](knowledge-base-delete-http-checkpoint-2026-10-16.json)。复现：

```sh
python3 backend-java/scripts/postgres-kb-delete-http-parity.py
python3 backend-java/scripts/postgres-kb-delete-worker-parity.py
python3 backend-java/scripts/processing_smoke.py /path/to/candidate.jar /path/to/vec0.dylib --kb-delete --topology single
python3 backend-java/scripts/processing_smoke.py /path/to/candidate.jar /path/to/vec0.dylib --kb-delete --topology go
```

## 剩余范围

Langfuse/OTel 的 request/worker trace 传播尚未接通。数据源 cron removal 提供可注入 hook，但 Java 原生 datasource scheduler 尚未迁移，当前无生产 schedule bean；不能将这个 hook 视为真实调度系统验收。

全部云存储、索引引擎和真实 Neo4j 生命周期仍未全部对齐；仍需完整并发入队、lease loss、崩溃/重启恢复及切换验证。Java SQL 队列不能直接消费 Go Asynq backlog。公开知识库内容复制/进度与其余 361 个接口、7 种任务 handler 仍未完成。没有部署、重启开发实例或切换前端。
