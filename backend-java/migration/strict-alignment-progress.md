# 全部 Go 功能严格对齐进度

2026-10-16 当前复核：**121/482 条 Go 接口有 Java 原生入口，361 条缺失；15/22 种任务有部分 handler，7 种缺失，0 verified，功能仍不一致。** 本批新增普通复制执行编排和同步目标清理，连接已有文档/chunk复制核心；实际 Go46 场景各执行两次，相关回归 **791项/8类全部通过、实际退出0**。1068个src/pom输入构建前后哈希一致。生产适配器、目标创建、FAQ、公开复制/进度和 kb:clone 尚未完成，PostgreSQL 执行未验收。最近默认完整10464项通过只覆盖旧索引复制1047输入快照，当前3个修改、21个新增，本批未重跑完整构建。未部署或重启服务。见 [最新对比](comparison-latest.md)、[普通复制范围](clone-execution-notes.md) 和 [本批证据](clone-execution-checkpoint-2026-10-16.json)。

## 历史批次（以下结果仅对应当时快照）

前一配置复制批次：2026-10-16 当前复核：**120/482 条 Go 接口有 Java 原生入口，362 条缺失；14/22 种部分任务 handler，0 条 verified。** 新增原生知识库配置复制；206 项相关回归、独立 PostgreSQL 55 项对照全部通过，同一候选 jar 的 duplicate HTTP 在 single/go 两种 Java SQL worker 拓扑均实际退出 0。前一 r6 全量 10,012 项通过属于历史快照，本批未重新跑当前完整测试集。清单 119 in_progress/362 not_started/1 health implemented，无入口状态漂移；全量迁移未完成。详见 [最新对比](comparison-latest.md) 和 [本批证据](knowledge-base-duplicate-checkpoint-2026-10-16.json)。

2026-10-16 Elasticsearch 8 索引移动增量：已补原生 update-by-query、完整更新判定、默认重试、Go typed JSON/错误解码及取消/超时传播、不跟随重定向和首次产品标识校验。实际 Go HTTP252场景＋2个上下文场景、真实ES22个移动场景通过，核对66次完整物理快照和396次检索；同引擎物理ID、内容和向量保留。997项专项通过；当前975个src/pom输入冻结完整 clean verify **9,768项/187类，failures/errors/skips全0、actual exit0**，包含SQL移动、ES移动/检索及两个公开向量存储IT。公开移动/进度入口、绑定和 knowledge:move worker 尚未接通；仍116/482条入口、13种部分任务handler、0 verified，完整迁移未完成。没有部署、重启开发实例或切换前端。 见[本批checkpoint](elasticsearch-index-move-checkpoint-2026-10-16.json)。以下保留历史批次证据，不将旧jar验收累加到本批。

此前SQL索引移动证据（2026-10-16）：SQLite 按源KB/文档原位迁移索引归属并清空标签，保留物理ID、向量和FTS；PostgreSQL移动时间改用Go对应的应用时间。实际Go SQLite13/PostgreSQL13对照及336项专项全部通过；PG使用完整115个迁移、Hikari1。覆盖完整选定元数据、源/目标/其他KB检索集合、回滚和重试。当时967个src/pom输入已记录，本批未另跑完整构建或打包验收；此前执行批次9,476项只证明其冻结快照。公开移动/进度入口、worker及其余运行时效果未接通，接口仍116/482、任务13种部分迁移、0 verified。见[SQL批次checkpoint](index-move-checkpoint-2026-10-16.json)及[范围说明](../src/main/java/com/tencent/weknora/retrieval/index-move-migration-notes.md)。没有部署、重启开发实例或切换前端。

此前移动执行证据（2026-10-16）：新增逐文档保留向量/重解析状态机、独立 Chunk/标签/归属提交、清理与配额事务顺序、确定性下游任务 ID/7天保留参数、重读确认及最终失败恢复。实际 Go SQLite64/PostgreSQL64 对照和316项专项全部通过；PG使用全部115个迁移、Hikari1。含并发解析不覆盖和6个Wiki边界场景。原生外部效果适配器、公开移动/进度入口和 knowledge:move worker 尚未接通，接口仍116/482、任务13种部分迁移，未新增 verified。该批962个 src/pom 输入冻结完整构建已确认9,476项/182类、failures/errors/skips全0、actual exit0；不包含后续SQL索引移动修改。见[当前 checkpoint](move-execution-checkpoint-2026-10-16.json)和[范围说明](../src/main/java/com/tencent/weknora/knowledge/move-migration-notes.md)。没有部署、重启开发实例或切换前端。

此前移动基础层证据（2026-10-16）：新增原生资源对兼容检查、完整持久化 transfer metadata、整批只读预检和独立 CAS/配额事务。实际 Go 对照 SQLite252/PostgreSQL252 均通过，PG 使用全部115个迁移及 Hikari1；专项444项/3类全部通过。保留 SQLite 旧时间格式导致的 Go CAS 冲突、数据库时间回读和并发非移动字段不覆盖。**公开移动/进度接口、Redis进度和 knowledge:move worker 尚未接通，路由和任务覆盖数未增加**。该批957个 src/pom 输入被冻结；完整 clean verify 已确认9,412项/181类、failures/errors/skips全0、actual exit0。此快照不包含后续移动执行逻辑，不能作为后续源码的全量证明。见[本批 checkpoint](move-plan-checkpoint-2026-10-16.json)和[范围说明](../src/main/java/com/tencent/weknora/knowledge/move-migration-notes.md)。没有部署、重启开发实例或切换前端。以下保留历史批次证据。

目标保持为：完成全部 Go 功能的 Java 原生迁移并对齐。不能将同名路由、桥接 Go、健康检查成功或某批测试通过替代该目标。

验收基准为当前 Go 生产行为，包括 HTTP/DTO/错误、身份和权限、SQL/索引/文件/配额副作用、任务和外部调用、失败/重试/取消/重复交付/重启。存在有意差异也必须明确解决或由用户改变目标，不能自行当作完成。

## 当前状态

2026-10-16 最近一次新增HTTP入口：图片信息更新，原生入口与有界验收记录如下。482条Go接口清单为115条 `in_progress`、366条 `not_started`、1条health `implemented`、0条 `verified`；Java120个显式方法/路径组合对应116条Go接口，文档30/32。见[本批checkpoint](image-update-checkpoint-2026-10-16.json)、[逐接口快照](native-route-audit-2026-10-16-image-update.json)和[status.json](status.json)。全部Go功能迁移目标仍在执行。

| 原目标范围 | 状态和待完成要求 |
|---|---|
| 登录、权限、租户 | 主要链部分迁移；补身份、管理及完整策略边界 |
| 知识库、文档、chunk | 重解析/批量重解析已有有界差分与固定jar验收，仍为进行中；文档另2条及KB另5条没有显式入口 |
| 文档构建 | 已有处理链部分迁移；补全解析器/格式、云存储、绑定写入，并解决文档发布事务和配额与Go的差异 |
| 检索、向量存储 | 混合检索/8个管理接口部分迁移；补全部引擎、公开knowledge-search及完整问答链 |
| 知识图谱 | 抽取/查询部分迁移；补公开问答集成、全部删除/移动生命周期及配置运行验收 |
| 后台任务 | 22种中13种部分迁移、9种没有handler；补完整Lite、Redis/Asynq、任务API、存量排空/接管 |
| 会话、消息、SSE、Agent | 补原生主链、生成停止/继续/重连、附件/反馈及全部对应接口 |
| MCP、FAQ、Wiki、Memory、组织、数据源等 | 按逐条接口和任务清单补完整模块，不能用内部基础组件替代公开业务 |
| 文件、部署、运维 | 补文件授权/签名、多云、空库初始化/升级、容器/压测/监控及切换/回滚验收 |

## 当前增量证据

本批图片更新证据（2026-10-16）：新增原生 `PUT /api/v1/knowledge/image/:id/:chunk_id`。实际 Go HTTP SQLite92/PostgreSQL92 场景通过，PG 应用全部115个迁移、Hikari连接池1；对照原调用方权限、内外层JSON绑定、父子片段归属、OCR/描述新增与更新、逐阶段部分提交、索引调用和链式MD5。专项412项/5类通过。冻结完整 clean verify **9,160项/180类，failures/errors/skips全0、actual exit0**；同一jar的single/go原文+ZIP+批量读取/标签+图库+图片更新真实HTTP流程均actual0，各2上传/4成功任务/6embedding/0chat，独立核对6条物理vec/FTS、4个图片子片段、源文件、配额、任务、审计和零版本记录。当前116/482条Go接口有Java入口（120个方法/路径组合），文档30/32；清单115 in_progress/366 not_started/1 health implemented/0 verified，任务仍13种部分迁移、9种缺失。图片接口仍in_progress，绑定外部向量库/完整组合引擎、全部存储数据与并发/生命周期/切换仍待对齐。没有部署或重启开发实例。见[本批checkpoint](image-update-checkpoint-2026-10-16.json)及[当前对比](comparison-latest.md)。以下为历史证据。

此前图库证据（2026-10-16）：新增原生图库配置和图片列表 GET 两个接口，实际Go HTTP SQLite152场景、完整115个迁移的PostgreSQL150场景通过（2个非法偏好JSON种子不可持久化，明确排除）。保留系统策略/原用户偏好、Viewer/retrieve/KB scope、SQL投影去重、属性规则、排序分页和只读副作用；共享数字分页绑定已按Gin 1.12先去空白。专项952项/5类通过；最终冻结 clean verify **9,064项/178类，failures/errors/skips全0、actual exit0**。同一jar的single/go原文+ZIP+批量读取/标签+图库真实HTTP流程均actual0，各2上传/4成功任务/2embedding；独立复核vec/FTS、源文件、配额、任务、审计、图库投影/策略清理及状态还原。当前115/482条Go接口有Java入口（119个方法/路径组合），文档29/32；清单114 in_progress/367 not_started/1 health implemented/0 verified，任务13种部分迁移、9种缺失。图库两个接口仍in_progress，完整存储数据/并发/生命周期/切换及其余模块仍缺。详见[本批checkpoint](gallery-checkpoint-2026-10-16.json)和[当前对比](comparison-latest.md)。没有部署或重启开发实例；下方保留历史证据，不同jar的验收模式不能累加。

此前批量标签证据（2026-10-16）：新增原生 `PUT /api/v1/knowledge/tags`，实际Go HTTP SQLite96场景、完整115个迁移的PostgreSQL95场景通过（1个非法JSON种子不可持久化，未计通过或skip）。保留Contributor/ingest/KB allow-list/共享Editor/后续所有者校验、重复map/null绑定、全量预校验和按文档ID逐个事务提交；中途失败保留此前提交。专项309项/3类通过；最终冻结 clean verify **8,908项/176类，failures/errors/skips全0、actual exit0**。同一jar的single/go原文+ZIP+批量读取/搜索+批量标签真实HTTP流程均actual0，各2上传/4成功任务/2embedding；独立复核vec/FTS、源文件、配额、任务、审计及临时标签/关系清理。当前113/482条Go接口有Java入口（117个方法/路径组合），文档27/32；清单112 in_progress/369 not_started/1 health implemented/0 verified，任务13种部分迁移、9种缺失。该接口仍in_progress，完整存储数据解码、并发权限变化、故障/取消/重启/切换及其余模块仍缺。详见[本批checkpoint](batch-tags-checkpoint-2026-10-16.json)和[当前对比](comparison-latest.md)。没有部署或重启开发实例；以下为历史证据，不同jar的验收模式不能累加。

本批最新证据（2026-10-16）：新增原生文档批量读取/搜索，实际 Go HTTP SQLite203场景、PostgreSQL在无统计信息/显式ANALYZE两种受控状态下各203场景通过，完整数组/DTO/错误/审计和无写入副作用逐项比较。最终冻结 clean verify **8,810项/175类，failures/errors/skips全0、actual exit0**；同一jar的single/go原文+ZIP+批量读取/搜索真实HTTP流程均actual0，各2上传/4成功任务/2embedding，独立复核物理vec/FTS、源文件、配额、任务和审计。首轮旧404路由断言失败及中止退出143记录保留；新快照完整重跑通过。两个新接口仍为in_progress：缺Go配置的Redis/Wiki stall_state探测；batch无ORDER BY，跨不同统计信息/计划不能保证稳定顺序。当前 **112/482条Go接口有Java入口（116个方法/路径组合）**，文档26/32；清单111 in_progress/370 not_started/1 health implemented/0 verified，任务13种部分迁移、9种缺失。详见[本批checkpoint](collection-read-checkpoint-2026-10-16.json)和[当前对比](comparison-latest.md)。完整迁移尚未完成，没有部署或重启开发实例。以下保留历史证据，不同jar的验收模式不能累加。

批量ZIP实际Go SQLite127/PG125 HTTP对照和20个archive对照、429项相关测试通过；含200个不同文档、真实512MiB/512MiB+1、完整成功ZIP字节摘要、四并发名额和失败清理/复用。PG另2个NUL文本种子不可持久化。最终冻结 **8,605项/174类全0、actual exit0**，同一jar single/go文件+批量ZIP HTTP流程各2上传/4成功任务/2embedding均actual0，独立核对vec/FTS/源文件/配额/任务/审计。Servlet断连传播尚不等同Go请求context取消，完整存储、故障和竞态仍缺；该路由保持in_progress，没有新增verified。未部署或重启开发实例。以下为此前批次记录，不同jar的模式不能累加。

原文 SQLite209/PG207 实际 Go 对照（2 个损坏 JSON 场景不能持久化为 jsonb）和227项 SQLite/Spring/真实 Tomcat 测试通过。最终冻结完整 clean verify **8,451项/171类，全0、actual exit0**；同一 jar 的 single/go 文件 HTTP 流程各2上传/4成功任务/2embedding，均 actual exit0，并独立核对物理 vec/FTS、源文件、配额、任务和审计。修正了清洗后ID的跨KB/API-key范围及真实Tomcat响应头删除行为，旧失败记录保留；没有新增 verified，也未部署或重启开发实例。见 [本批 checkpoint](file-response-checkpoint-2026-10-16.json)。以下均为历史记录，不能合并不同jar的验收模式。

URL 公开导入 SQLite/PG 各 94 个实际 Go 场景、SQLite/Spring 107 项通过；冻结完整 clean verify 8,237 项/169 类全 0、actual exit0。固定 jar 的 single/go 两种 URL 流程各 2 上传/2 导入/8 成功任务/4 embedding/1 reader/0 chat，真实 vec/FTS/原文/配额/审计和入队失败均已验证。详细证据见 [URL checkpoint](url-import-checkpoint-2026-10-16.json)。接口仍为 in_progress，0 新增 verified；没有部署或重启开发实例。下方均为此前批次记录。

最新批worker SQLite/PG各67个真实Go场景、289项相关回归均通过。新的冻结完整clean verify为8,139项/168类，全部0、actual exit0；固定jar的single/go重解析流程每种2上传/11任务/5embedding HTTP均actual exit0，核对实际vec/FTS、源文件、配额、attempt、共享owner与原actor审计。启用Langfuse、全部配置/存储/并发/重启/切换及其他模块仍缺；0新增verified，没有重启开发实例。以下为此前批次记录。

[公开摘要生成说明](../src/main/java/com/tencent/weknora/knowledge/summary-public-migration-notes.md)记录了56个SQLite真实Go场景、56个PostgreSQL真实Go场景及7个Spring生产链测试。完整构建/固定jar验收以[VALIDATION.md](../VALIDATION.md)为准；不同jar或不同历史批次的数量不能相加宣称同一份源码已全部验收。

最近完整构建7,914项/163类、0 failures/errors/skips，actual exit0；使用隔离ES与完整配置，对同份冻结源码重跑后通过。首轮缺少ES地址的139项失败保留。同一稳定jar的摘要入口single/go两种SQL worker布局均actual exit0，每种2上传/8任务/8embedding/6chat；物理索引、源文件、配额和任务状态已复核。该结果属于重解析草稿之前的1,154输入快照；当前新增6输入并修改2个权限类，不能声称旧全量覆盖当前源码。未重启开发实例。

重解析后续增量已通过SQLite组合125项/5类（100实际Go差分、7完整Spring链、18上传/guard回归）以及另一次完整115迁移的PostgreSQL实际Go差分100项/1类，均failures/errors/skips=0、actual exit0。最终Go oracle含真实计数器SQL故障、Asynq旧任务取消与Wiki pending repository，早期失败报告保留。证据见[重解析checkpoint](reparse-admission-checkpoint-2026-10-16.json)及[范围说明](../src/main/java/com/tencent/weknora/knowledge/reparse-migration-notes.md)。

后续URL/file_url worker已接通，见下一节；直接Go批worker的完整差分、部署语言/trace/initiator及并发窗口、全部解析器/存储、冻结完整构建和同jar流程尚未完成。两个接口和该任务暂不升级清单状态；不能只将任务入队或本次专项通过视为完整处理链已迁移。原目标仍是全部Go功能严格对齐。

## URL / file_url worker 增量

2026-10-16：新增逐跳SSRF/DNS固定下载、10MB边界、文件名/类型解析、原owner临时资源保存、URL reader选择、条件标题更新和相关SQL checkpoint。实际Go31个下载/文件名观察与13个worker场景通过Java差分；5个原生下载保护、2条新增Spring JWT/shared/Hikari1/HTTP/模型/物理索引流程通过。最终组合265项/12类，failures/errors/skips全0、actual exit0；原手动/文档/图片fanout/重解析/上传/权限/span回归包含在该批。

证据：[最新checkpoint](remote-source-checkpoint-2026-10-16.json)、[详细范围](../src/main/java/com/tencent/weknora/knowledge/remote-source-migration-notes.md)。原失败和修正记录保留；没有重启或部署新jar。公开URL导入、所有网络/存储/解析器/失败checkpoint、PG URL处理及直接批worker差分仍待完成；Java较强来源/租约保护与Go并发语义的差异也未被该专项消除。尚无包含本批的冻结全量构建/同jar验收，清单状态继续保持，完整目标未完成。
