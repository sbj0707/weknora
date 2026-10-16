# 下一项知识库删除：完整迁移边界

2026-10-16：公开 DELETE、kb:delete、SQL 队列、SQLite/PostgreSQL 物理索引和本地资源清理已接通并有限定验证，见 [公开实现](knowledge-base-delete-http-notes.md)。以下保留完整验收边界；Langfuse、原生 datasource cron scheduler、全部外部引擎/云存储/图谱及并发重启切换仍未完成。

## 公开接口

Go handler 通过 validateAndGetKnowledgeBase 校验，来源必须属于原调用租户且具有 OrgRoleAdmin；共享来源不得删除。保留路由角色及 API-key manage_kbs/full-access 及 KB 范围控制，成功 200 返回 success/message。任何业务副作用之前须完成真实租户/KB/权限解析。

## 入队前状态与副作用

1. 软删除前读取 KB 并快照 VectorStoreID；软删除 KB，独立记录 kb.deleted 审计。
2. 使用脱离客户端取消的有界 context 清理该 KB 的 durable pending operations，再取消范围内队列任务；不能只取消当前文档任务。
3. best-effort 删除组织 shares；逐个删除数据源、移除 cron 调度、取消同步日志并收集 IDs；再次按数据源取消任务。
4. 入队 kb:delete，保留 tenant、KB、数据源 IDs、effective engines、vector-store 快照和 tracing。maintenance（实际名 low）队列、3 retries、2 小时 timeout。入队失败仍返回成功，KB 已删除；不得改成事务回滚或由请求同步清空全部资源。

## Worker

先列出该租户该 KB 的全部文档并执行带文档 IDs 的任务清理。按入队时绑定快照解析检索引擎：forbidden/not-found 对应 SkipRetry，暂时不可用和取消必须重试，不能无条件报告成功。按 embedding model/type 分组删除索引；Go 对模型、索引删除失败的 best-effort 顺序也要保留。

在删除 chunks 之前收集图片 URL，随后删除 chunks、原文文件、抽取图片并扣减文档 storage_size，用 resource catalog 的归属限制资源删除；删除图谱 namespace，再删文档记录。必须保留其他知识库和租户的数据、索引、资源及配额。

**所有返回路径（包括取消、普通失败、SkipRetry）都要执行 Wiki 清理及最终任务清理**，各自使用有界 detached context。Wiki pages/folders/issues 软删除，revisions 硬删除；Wiki 清理失败会把 nil/SkipRetry 提升为可重试错误。最终 scrub 关闭重型清理期间下游任务再次入队的竞态。

## 验收要求

不能只加接口或复用单文档删除 worker：需实际 Go admission 与 worker oracle，比较权限/错误、软删除与部分失败阶段、任务 payload/options、队列和 pending operations、shares/数据源/同步日志、绑定引擎错误分类、Wiki 每种返回路径、原文/图片/图谱/配额及幂等重试。SQLite 与全迁移 PostgreSQL 独立对照；同 jar 的真实认证→删除→worker→物理 vec/FTS/文件和 Wiki 状态验收，并覆盖完整取消/租约/重启恢复。

源代码：internal/handler/knowledgebase.go DeleteKnowledgeBase；internal/application/service/knowledgebase.go DeleteKnowledgeBase、ProcessKBDelete、cleanupWikiForKnowledgeBase、cleanupTasksForKnowledgeBase、deleteDataSourcesForKnowledgeBase；路由及 API-key 策略见 internal/router/routes_knowledge.go RegisterKnowledgeBaseRoutes 与 internal/router/rbac.go。
