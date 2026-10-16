# 知识库删除后台任务：当前实现与边界

2026-10-16：已注册原生 `kb:delete` TaskHandler，接通实际 SQL 队列、索引和本地资源清理。公开 DELETE 已在后续批次接通，详见 [公开删除实现](knowledge-base-delete-http-notes.md)。当前 121/482 个原生 HTTP 入口、15/22 种部分任务 handler，尚无完整 verified 路由。

## 实现

`KnowledgeBaseDeleteConfiguration` 注册 handler、repository、resources 与 task scrubber。handler 从持久化队列读取原始 JSON，按 Go typed payload 解码，保留重复字段、null 与数组复用语义；畸形消息进入重试。SQL 队列要求 payload tenant 与队列 owner 一致，这是 Java 传输层边界。

`KnowledgeBaseDeleteProcessor` 按实际 Go 顺序执行：获取文档和清理任务，解析入队快照中的绑定或默认引擎，按模型分组删索引，在删 chunks 前收集图片，删文件/图片和扣减配额，删图谱，最后软删除文档。保留阶段独立提交、best-effort 错误、SkipRetry 和 Wiki 错误提升。清理无需 KB 仍存活。

成功、失败和取消路径均尝试四类 Wiki 清理和最终 scrub，各自使用独立的 5 秒 context。清理在独立虚拟线程执行，父线程等待完成，避免任务超时的延迟中断破坏结束清理。TaskWorker 的超时计时器独立于可能阻塞的 SQL 续租线程。

`KnowledgeBaseDeleteSql` 将 JDBC statement 绑定取消与 deadline，使用 SQLite progress handler，保留原数据源和事务连接复用。`KnowledgeBaseDeleteResources` 对 SQLite vec/FTS 和 PostgreSQL 执行真实物理删除；绑定引擎使用 VectorStoreRegistry，目前原生工厂仅覆盖 Elasticsearch。SQLite 各阶段独立提交，保留 Go 的部分失败结果。

文件使用 Go KB 删除所用的进程级默认服务，目前接通 local/dummy。本地原文删除后标记资源删除；抽取图片先释放文档 claim，保留仍被其他 owner 引用的图片。已接现有 graph adapter，但本批没有真实 Neo4j 物理删除验收，不能据此声明全部图谱行为一致。

`KnowledgeBaseTaskScope` 先完整解码所有 typed 字段再匹配，保留 clone/move 特定字段和未来类型的直接 KB 匹配，排除 kb:delete/index:delete。scrubber 先删除 durable pending operations，再扫描已知队列 pending/retry/running 并设置持久取消标记；全局 KB ID 匹配不附加租户过滤。Java 使用 SQL 队列，不能直接消费 Go Asynq backlog。

## 验证

- 相关回归 **624 项 / 11 类全部通过，Maven 实际退出 0**。
- 独立 PostgreSQL **61 项 / 2 类全部通过，实际退出 0**；全部 115 个 Go 迁移、Hikari 连接池 1，拥有的容器已清理。
- 实际 Go 导出：342 个任务范围匹配、88 个 typed payload、SQLite/PostgreSQL 各 12 个物理删除及各 40 个核心场景。物理场景比较真实索引、vec/FTS、资源 claims、文件和 SQL 状态。
- 实际 SQL 队列覆盖畸形 payload、owner 不匹配、绑定访问失败、取消、超时和过期取消恢复。过期运行任务须通过 recovery claim 收敛；没有以重新执行删除业务伪装恢复。
- 本运行时批次冻结默认完整 `clean verify` 10,382 项 / 194 类全部通过、实际退出 0。后续公开删除代码和 jar 验收见新 checkpoint；这些结果按各自快照记录，不互作全量证明。

原始报告、哈希和失败历史见 [运行时 checkpoint](knowledge-base-delete-runtime-checkpoint-2026-10-16.json)。复现：

```sh
python3 backend-java/scripts/postgres-kb-delete-worker-parity.py
python3 backend-java/scripts/compare-go-java.py --output /tmp/weknora-go-java-comparison.json
```

## 剩余完整链路

公开 DELETE 的鉴权、软删除/审计、shares/数据源/同步日志、快照和入队参数已在后续批次接通；tracing、原生 datasource cron scheduler 仍未完成。云文件服务、全部索引引擎及真实 Neo4j 行为仍未完整迁移或验证。

后续批次已完成同 jar 的公开认证→入队→物理资源验收，仍需完整并发入队、租约丢失、崩溃重启、重复执行和切换测试。Go 存量队列接管与其余 361 条接口、7 种任务 handler 仍属于全量目标。完整边界见 [删除审计](knowledge-base-delete-audit.md)。
