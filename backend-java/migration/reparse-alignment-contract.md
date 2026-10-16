# 文档重解析的 Go 行为基准

2026-10-16 源码核对；目标仍包括单个重解析、批量重解析及其完整处理链。本文件不表示这些接口或任务已经迁移。两个公开接口和 `knowledge:list_reparse` 仍为 `not_started`。

## 授权与请求绑定

- 单个接口为 `POST /api/v1/knowledge/:id/reparse`。沿用真实 router 的 API-key ingest、creator/Admin 和共享 Editor 边界，按原 actor 审计，使用 KB owner tenant 执行业务。先授权，再绑定可选请求体；仅当 ContentLength 非0时解析 `{process_config: ...}`，保留 Go 的 null、空体、类型错误、大小写字段及首个JSON值语义。
- 批量接口为 `POST /api/v1/knowledge/batch-reparse`。先绑定必填 `kb_id` 和 `ids`，ID按Go空白规则修剪、丢空、保序去重，限制200项；再执行KB权限、creator/Admin及共享Editor校验。入队前必须验证所有文档存在、属于该KB、没有未完成的move。任何一项不通过都不提交批任务。
- Go入口：[single handler](../../internal/handler/knowledge.go)、[router](../../internal/router/routes_knowledge.go)、[batch scope](../../internal/application/service/knowledge_reparse_scope.go)。以代码和真实HTTP对照确定每个拒绝响应，不用角色名称推测权限。

## 单个重解析的 checkpoint

1. `loadKnowledgeWrite` 校验当前绑定和移动状态。新的process overrides先针对文档类型校验并持久化；nil沿用原值。手动文档无文件类型，网页按html，其他文档优先存储file_type、再取filename后缀。
2. 手动文档先读取metadata.content，暂不清理；其他文档同步执行 `cleanupKnowledgeResources`。清理包含实际索引、各类型chunks及提取图片、图谱和storage_used扣减。该清理会聚合错误并继续尝试后续动作，因此失败可能已留下部分清理结果；不能改成全事务回滚，也不能把“失败不创建attempt”误解为“失败无任何副作用”。
3. 只有此前步骤成功，且旧状态为pending/processing/finalizing时，取消旧队列任务；随后打开新attempt。打开attempt失败为best effort，worker可补开。即使Wiki当前关闭，也清除旧的pending ingest；保留现有Wiki页面，后续ingest替换其来源。
4. 重置parse_status=pending、enable_status=disabled、description为空、processed_at=NULL、error_message为空、embedding_model_id为当前KB模型，单独将pending_subtasks_count归0。不能额外重置summary_status或丢失其他metadata。
5. 手动文档入队 `manual:process`，原始content、need_cleanup=true；普通文件优先file_path，file_url其次，网页url最后，均入队 `document:process`。普通文件的file_type取filename原始后缀；file_url保留存储file_type；网页payload不伪造file字段。保留language、attempt及已有trace字段。
6. 入队失败将文档写为failed及 `Failed to enqueue processing task`，返回500 `Failed to submit processing task`；此前提交的清理/attempt/状态不会回滚。无可解析来源同样在重置之后写failed，再返回400 `Knowledge has no parseable content`。
7. 成功入队后记录 `knowledge.reparse_started` 活动。文件及file_url的csv/xlsx/xls还要按Go规则提交表格摘要任务。

实际代码：[reparse service](../../internal/application/service/knowledge_process.go)、[cleanup](../../internal/application/service/knowledge_delete.go)、[manual producer](../../internal/application/service/knowledge_create.go)。

## 队列和批任务

`documentProcessTaskOptions` 已经统一包含MaxRetry(3)，所以file_url调用处没有额外写MaxRetry并不表示它使用Asynq默认重试次数。文件、file_url和网页均为default队列、3次重试和部署配置的document timeout；manual为default、3次重试、30分钟。Java需按“总尝试次数=4”映射，不能只观察调用处的显式附加参数。[实际工厂](../../internal/application/service/knowledge_task_options.go)。

批任务使用物理队列名 `low`（QueueMaintenance）、3次重试、1小时，payload保留KB、owner tenant、保序去重后的IDs、process_config、原始initiator和trace。worker须在任何破坏性提交之前重新校验整批绑定及moving状态；旧payload仅在KB归属无歧义时重建KB。加载owner tenant之后逐项调用完整重解析。某一项失败仍提交其余项；存在部分失败时返回带完整错误列表的SkipRetry，不能让整个wrapper重试并重复清理已成功项。

## 处理链前置缺口与验收

当前Java `DocumentProcessingHandler` 明确拒绝url/file_url；不能只新增入队接口而把随后必然失败的任务算作迁移。需补网页docreader请求与标题提取，以及file_url安全下载、60秒超时、最多10次重定向、10MB双重大小限制、filename解析和资源持久化，再接入现有真实分块/模型/索引/postprocess。所有存储、解析器、索引引擎和配额/失败checkpoint仍属于全量目标，不以本地成功样例豁免。

单个及批量需采集真实Go HTTP/SQL/Asynq差分，覆盖所有来源类型、共享/API授权、配置错误、清理中失败、队列失败、旧任务取消和attempt顺序、Wiki pending、全批预检及部分提交。随后在SQLite与完整Go PostgreSQL schema上核对实际索引、文件和配额，并使用同一个通过完整构建的jar运行single/go两种SQL worker布局；这两种布局都不等于接管Go Redis/Asynq队列。

Go已有重解析相关基线于本日实际exit0：`go test -tags sqlite_fts5 ./internal/application/service -run 'Test(ReparseKnowledge|ReplaceKnowledgeFile|KnowledgeListReparse)' -count=1`，日志 `/tmp/weknora-go-reparse-baseline-20261016.log`。该基线不是Java验收，也不是全HTTP/实库差分。
