# 批量重解析 worker 对齐

目标仍为全部 Go 功能的 Java 原生迁移和严格对齐。本文件只记录批量重解析增量，不能用它证明完整替换已经完成。

## 实现

`knowledge:list_reparse` 从 SQL 队列保存的原始 payload 解码，保留 Go 的 JSON 类型检查、重复字段合并、大小写字段匹配、`null` 与首个类型错误行为。畸形 JSON 由 worker 判定重试，不能在领取任务时提前丢失原始错误。嵌入的 `TracingContext` 字段保持扁平 JSON，但类型错误中保留 Go 的嵌入结构路径。API key 审计 ID 支持完整 `uint64`，越界、负数和指数形式按 Go 拒绝；导出器使用 `UseNumber`，避免比较基准先丢失整数精度。

执行前核对完整文档选择、owner、知识库、软删除及移动状态。原始 initiator 用于恢复审计身份，不能变成 API key 授权身份，也不重新查询当前 key 名称来覆盖入队快照。审计包含任务 ID、触发者和 pending 状态；背景语言取部署配置。文件清理、计费、任务取消和下游提交保留 Go 的 checkpoint；一个文档失败后继续其他文档，批量部分失败不重试整个 wrapper。

原生任务的取消和失租约检查传到清理及提交边界；该保护不证明 Go/Java 所有取消和并发窗口行为一致。

## 差分证据

真实 Go Asynq server 执行生产 `ProcessKnowledgeListReparse`，使用 miniredis、生产 inspector 和 pending repository，导出 67 个场景。Java 比较完整错误与重试标志、所有文档 SQL checkpoint、配额、chunk、实际物理索引、span、旧任务可执行性、Wiki pending 清理、下游 payload/顺序/队列参数和审计。

SQLite 专项及相关回归 289 项/13 类通过；独立 PostgreSQL 应用全部 115 个 Go migrations、Hikari pool 1、实际 halfvec/FTS，另外 67 个 Go/PostgreSQL worker 场景通过。两次命令均 actual exit 0、failures/errors/skips 全 0。PostgreSQL 可通过 `python3 backend-java/scripts/postgres-reparse-parity.py --worker` 重跑，默认不加参数仍验证公开 HTTP。

原首轮59项/51 failures/3 errors、后续168项的trace错误路径失败，以及新增unsigned ID场景的67项/2失败运行均保留。没有放宽响应、SQL、物理索引或审计断言。

新冻结完整 `clean verify` 为8,139项/168类，failures/errors/skips全0、actual exit0，显式包含两个公开向量存储IT的139项；使用runner自建ES，结束后只停止该容器。固定jar SHA-256为 `682db43c68bdd13bd80a92caa2e970ed5bcab7b55284cc85d0dec48892d49cf3`。同jar的 `processing_smoke.py --reparse --topology single|go` 均actual exit0，每种2上传、11个成功任务、5次embedding HTTP、0次chat；创建者Viewer先执行自己的单文档重解析，共享Editor再批量重解析两个文档。核对新chunk和实际vec/FTS、attempt 3/2、原始文件字节、配额保留及owner/原actor/批任务ID审计。所有1,280个已冻结输入在文档/清单更新之前与工作区哈希相符。两个接口和批任务仅推进为in_progress，未增加verified或切换运行实例。

证据见 `backend-java/migration/reparse-worker-checkpoint-2026-10-16.json`；历史7,914项及其摘要jar记录仍保留，不能合并两个jar的流程数量。

## 尚未完成

启用 Langfuse 的 worker tracing/middleware、全部解析器/对象存储/绑定索引、所有 schema/数据/配置、并发取消/来源变更/重启和桥接切换仍需验收。这里的 trace 样本验证关闭 Langfuse 时的解码及行为，不能证明启用后的 trace 传播。SQL worker 不消费 Go 的 Redis/Asynq 存量任务，Lite 同步执行器也未完成；已有文档发布事务和计费差异仍然存在。
