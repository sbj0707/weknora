# 知识库内容复制迁移范围

2026-10-16。目标仍为全部 Go 功能迁移和严格对齐。本文列出内容复制的实际 Go 契约及剩余接线，不能把配置 duplicate、索引复制原语或入口数量当成完整复制功能。

## 已完成的索引复制原语

Java `IndexCopier`、`SQLiteIndexRepository.copyIndices` 和 `PostgresIndexRepository.copyIndices` 直接复制已存在的物理向量，不调用 embedding。SQLite 与 PostgreSQL 的语义分别匹配 Go，未把两者强行统一。

| 行为 | Go SQLite / Java SQLite | Go PostgreSQL / Java PostgreSQL |
|---|---|---|
| 选择来源 | 只按映射的 chunk ID；source KB、维度和类型参数不参与过滤 | source KB + 映射的文档 ID，按物理 id 游标分页 |
| 批次 | 每批 500 个 chunk ID，查询结果按 id 排序 | 每批 500 条物理索引记录 |
| 问题 source ID | 普通行改为目标 chunk；问题保留后缀；其他形状生成新 UUID | 同左 |
| 文档映射缺失 | 写入空文档 ID | 跳过 |
| 标签 | 保留源标签 | 清空 |
| NULL enabled | Go 指针/default:true，复制为 true | Go bool 零值，复制为 false |
| 元数据失败 | 跳过该条，继续复制 | 当前整批失败，返回错误；此前成功批次保留 |
| FTS / 向量 | 元数据后独立写入，写失败不撤回元数据 | 向量在当前批次 INSERT 内复制 |
| 重试 / 冲突 | 冲突行跳过；非标准 source ID 再生成 UUID | ON CONFLICT DO NOTHING，保留序列消耗 |

同时修正原有 PostgreSQL `copy(Connection,...)` 的逐行 INSERT：现为每页一个 INSERT，避免一批中途失败时保留同批前几条。该方法仍加入调用方事务；供未来 clone worker 使用的 `copyIndices` 则独立提交各页。

真实 Go oracle 在 SQLite、独立 PostgreSQL 各导出 21 个场景，包含重复执行的两次状态、完整选定元数据、原始向量、来源/目标/其他 KB 的向量与关键词检索集合及 SQL 错误。PostgreSQL 使用全部 115 个迁移、连接池 1。覆盖问题行、禁用状态、异常 source ID、NULL、跨范围数据、空映射、冲突、元数据/FTS/向量故障、501 行与后批失败。

相关回归 65 项 / 4 类通过；PostgreSQL 新对照 21 项通过；既有 PostgreSQL 16 个变更 / 2368 次检索对照通过。证据见 `index-copy-checkpoint-2026-10-16.json`。这不代表公开复制或 `kb:clone` 已实现。

## 已验证的只读预检基础

新增 `KnowledgeClonePlanner` 和 clone 兼容性检查：普通文档计划保留两端全部 chunk/parent/tag 先预检、UTF-8 ID 排序、transfer metadata 优先匹配、hash 一对一匹配及状态规则；FAQ 预检只检查容器和 FAQ chunk/关系。真实 Go 导出129个兼容性、71个普通文档计划、19个FAQ预检场景。当前相关回归602项/5类、独立完整115迁移的PostgreSQL219项/1类均通过，实际退出0；所有预检均核对无写入副作用。

这只完成下面第5项中的计划计算和第9项中的预检，不包含目标删除、文档复制、FAQ diff执行或生产worker接线。新增helper尚无生产调用方。最近冻结完整构建10464项通过属于前一索引复制快照，不含本次预检修改。详见 [预检checkpoint](clone-plan-checkpoint-2026-10-16.json)。

## 新增chunk复制执行核心

已新增chunk/标签/图片深复制、引用改写、100条独立提交和CAS失败清理核心。68个实际Go场景及相关回归共670项通过；当时尚无生产Effects、文档创建和公开worker接线；后续文档核心见下。本批PG重跑因Docker I/O故障未完成，不能计通过。字段default/清空、序列、metadata物理类型和详细剩余项见 [执行范围](clone-chunks-notes.md) 与 [checkpoint](clone-chunks-checkpoint-2026-10-16.json)。

## 新增文档复制执行核心

新增分阶段文档创建、源文件独立复制、Profile/JSON/默认字段、最终 CAS/配额及外层失败记账。75个实际Go SQLite场景及相关745项/7类通过，1063个构建输入哈希前后稳定。仍无生产Effects/公开worker接线；下列第6项仅有核心及有界对照，完整生命周期和PG尚未验收。详见 [文档范围](clone-document-notes.md) 与 [checkpoint](clone-document-checkpoint-2026-10-16.json)。

## 新增普通复制编排与同步目标清理

新增整批只读预检后同步删除多余目标、再逐文档复制及阶段进度。实际Go46场景各两次尝试，相关791项/8类通过；1068构建输入哈希稳定。覆盖预检后数据变化、各级部分提交、删除失败重试与第二篇复制失败重试。仍缺生产Effects、完整typed SQL读取/租户Save、Wiki/资源引用效果、FAQ、公开复制/进度、worker和PG执行验收。下列第5项已有有界执行核心，尚未完成生产链路。[范围](clone-execution-notes.md)、[checkpoint](clone-execution-checkpoint-2026-10-16.json)。

## 仍需迁移的 Go 契约

1. 公开 admission：`POST /api/v1/knowledge-bases/copy`。JSON 绑定、management key/Contributor、KB allow-list、本租户来源读取和既有目标的 creator/Admin 置换权限。新目标只在 payload 中保留 UUID，不提前创建空 KB；入队失败返回 500。任务业务 ID、low、3 retries、2 小时和原 actor/tracing 必须保留。
2. 目标创建：实际 `knowledgeBaseService.CopyKnowledgeBase` 复制的是指定配置子集，和 `DuplicateKnowledgeBase` 不同。需要持久目标预留、创建/恢复、默认值、creator 的 synthetic-user 规则及并发竞争验收。
3. 进度：`GET /api/v1/knowledge-bases/copy/progress/:task_id` 先验证任务租户。Redis `kb_clone_progress:` 保存 24 小时；初始值 SET NX 不覆盖已运行任务；类型解码、未找到和故障结果须按 Go 对照。
4. `kb:clone` worker：Go typed JSON、原始 payload、tenant/source/task 校验及 SkipRetry，legacy 空 target 的 UUID v5（OID namespace、`kb-clone:{tenant}:{source}:{task}`），重新验证 transfer binding、tenant 和兼容性。
5. 普通文档计划（只读计算及有界普通执行已验证，生产删除/复制执行待接线）：完整读取两端所有文档、所有 chunk 类型、parent/tag 关系，先整批只读预检，再按 id 排序。优先匹配本任务的完成 transfer state，再按非空 file hash 一对一匹配；拒绝忙碌目标和未完成来源。先删除多余目标，再逐文档复制，阶段独立提交。
6. 文档复制：文件及其引用、配额、metadata、所有字段、标签映射、持久 transfer state、重试及 CAS。逐阶段故障后的可见状态必须和 Go 一致。
7. Chunk 复制：所有字段、source locators、metadata/hash/flags/index status，parent/pre/next ID 重映射，100 条批量保存。图片按 URL 缓存深复制到 exports 路径；OriginalURL 条件改写，最终按旧 URL 长度倒序替换全部内容。失败清理目标状态、索引、chunks 和新文件的顺序必须对照。
8. 索引接线：使用 source 绑定的引擎和目标 embedding 模型维度，不重新 embedding。SQL 原语已完成；ES7/8、Qdrant、Milvus、Weaviate、Doris、TencentVectorDB、OpenSearch、混合引擎等仍待接线和独立验证。
9. FAQ（只读预检已验证，其余待迁移）：独立预检容器/FAQ chunk 类型和标签关系，再计算 hash diff；未索引的匹配项要删除后重建。批量删除、索引、deep-copy 图片、状态、tags、progress 和重试不能复用普通文档流程替代。
10. 生命周期：pending/processing/completed/failed、最终重试判定、开始/结束/失败审计、Profile best effort、tracing、取消、租约丢失、崩溃/重启恢复及切换验收。Java SQL queue 仍不能消费 Go Redis/Asynq 存量任务。

当前未增加公开路由或任务 handler。整体仍为 121/482 个原生入口、15/22 个部分 handler、0 verified；完整迁移未完成。

## 权威代码

- `internal/handler/knowledgebase.go`：`CopyKnowledgeBase`、`GetKBCloneProgress`。
- `internal/application/access/kb_transfer.go`：scope、CloneDestination、兼容性。
- `internal/application/service/knowledgebase.go`：目标配置创建与恢复。
- `internal/application/service/knowledge_transfer.go`：transfer metadata、整批预检和增量计划。
- `internal/application/service/knowledge_clone_move.go`：worker、Chunk/图片、FAQ、进度。
- `internal/application/repository/retriever/sqlite/repository.go`、`postgres/repository.go`：本批物理复制基准。

此前默认完整测试的 10,443 项中有 1 项 Elasticsearch TCP 复用断言失败，原始失败保留。隔离诊断 20 轮 / 480 场景通过，尚未确定原失败原因，未放宽断言。新增索引复制后的冻结完整测试另行记录，不能把历史测试或不同 jar 累加成当前完整验收。
