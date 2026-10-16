# Go / Java 功能一致性与迁移验收

当前结论以[最新复核](comparison-latest.md)为准：2026-10-16，121/482 条接口有 Java 入口，15/22 种任务有部分 handler，功能仍不一致。下文为历史批次证据，不能作为当前代码全量通过的证明。

## 历史记录

本批图片更新证据（2026-10-16）：新增原生 `PUT /api/v1/knowledge/image/:id/:chunk_id`。实际 Go HTTP SQLite92/PostgreSQL92 场景通过，PG 应用全部115个迁移、Hikari连接池1；对照原调用方权限、内外层JSON绑定、父子片段归属、OCR/描述新增与更新、逐阶段部分提交、索引调用和链式MD5。专项412项/5类通过。冻结完整 clean verify **9,160项/180类，failures/errors/skips全0、actual exit0**；同一jar的single/go原文+ZIP+批量读取/标签+图库+图片更新真实HTTP流程均actual0，各2上传/4成功任务/6embedding/0chat，独立核对6条物理vec/FTS、4个图片子片段、源文件、配额、任务、审计和零版本记录。当前116/482条Go接口有Java入口（120个方法/路径组合），文档30/32；清单115 in_progress/366 not_started/1 health implemented/0 verified，任务仍13种部分迁移、9种缺失。图片接口仍in_progress，绑定外部向量库/完整组合引擎、全部存储数据与并发/生命周期/切换仍待对齐。没有部署或重启开发实例。详见[本批checkpoint](image-update-checkpoint-2026-10-16.json)、[当前逐模块对比](comparison-latest.md)和[逐接口快照](native-route-audit-2026-10-16-image-update.json)。

## 此前对比与历史增量记录

核对与迁移更新日期：2026-10-16。**功能不一致，Java 不能完整独立替代 Go。** 最新完整对比见[当前对比](comparison-latest.md)，逐条见[接口快照](native-route-audit-2026-10-16-gallery.json)和[routes.json](routes.json)，执行目标仍是[全部 Go 功能严格对齐](strict-alignment-progress.md)。

## 最新迁移增量

此前图库证据（2026-10-16）：新增原生图库配置和图片列表 GET 两个接口，实际Go HTTP SQLite152场景、完整115个迁移的PostgreSQL150场景通过（2个非法偏好JSON种子不可持久化，明确排除）。保留系统策略/原用户偏好、Viewer/retrieve/KB scope、SQL投影去重、属性规则、排序分页和只读副作用；共享数字分页绑定已按Gin 1.12先去空白。专项952项/5类通过；最终冻结 clean verify **9,064项/178类，failures/errors/skips全0、actual exit0**。同一jar的single/go原文+ZIP+批量读取/标签+图库真实HTTP流程均actual0，各2上传/4成功任务/2embedding；独立复核vec/FTS、源文件、配额、任务、审计、图库投影/策略清理及状态还原。当前115/482条Go接口有Java入口（119个方法/路径组合），文档29/32；清单114 in_progress/367 not_started/1 health implemented/0 verified，任务13种部分迁移、9种缺失。图库两个接口仍in_progress，完整存储数据/并发/生命周期/切换及其余模块仍缺。详见[本批checkpoint](gallery-checkpoint-2026-10-16.json)和[当前对比](comparison-latest.md)。没有部署或重启开发实例；下方保留历史证据，不同jar的验收模式不能累加。

此前批量标签证据（2026-10-16）：新增原生 `PUT /api/v1/knowledge/tags`，实际Go HTTP SQLite96场景、完整115个迁移的PostgreSQL95场景通过（1个非法JSON种子不可持久化，未计通过或skip）。保留Contributor/ingest/KB allow-list/共享Editor/后续所有者校验、重复map/null绑定、全量预校验和按文档ID逐个事务提交；中途失败保留此前提交。专项309项/3类通过；最终冻结 clean verify **8,908项/176类，failures/errors/skips全0、actual exit0**。同一jar的single/go原文+ZIP+批量读取/搜索+批量标签真实HTTP流程均actual0，各2上传/4成功任务/2embedding；独立复核vec/FTS、源文件、配额、任务、审计及临时标签/关系清理。当前113/482条Go接口有Java入口（117个方法/路径组合），文档27/32；清单112 in_progress/369 not_started/1 health implemented/0 verified，任务13种部分迁移、9种缺失。该接口仍in_progress，完整存储数据解码、并发权限变化、故障/取消/重启/切换及其余模块仍缺。详见[本批checkpoint](batch-tags-checkpoint-2026-10-16.json)和[当前对比](comparison-latest.md)。没有部署或重启开发实例；以下为历史证据，不同jar的验收模式不能累加。

此前读取8,810项/175类及其两种进程布局属于历史jar证据；不能合并不同jar的模式。详细剩余差异以[当前对比](comparison-latest.md)为准。下方保留历史复核和运行观察。

## 此前请求复核（历史）

**结论仍为不一致，不能将当前 Java 当作 Go 的完整替代。** 新扫描确认 Go 482 条路由、480/480 注册位置、0 未解析；Java 110 个显式方法/路径组合对应其中 106 条，376 条没有显式入口。Go 清单 2,482 个文件的哈希均与工作区一致。源码入口扫描不验证条件 Bean 是否启用，也不证明行为对齐。

最新保存的重解析测试报告共 168 项，1 failure、0 errors、0 skipped：HTTP 差分 100 项和 Spring 链 9 项通过，直接批量重解析 worker 差分 59 项中 1 项失败。失败是畸形 trace 参数的 Go JSON 错误字段路径：Go 返回 `KnowledgeListReparsePayload.TracingContext.lf_trace_id`，Java 返回 `KnowledgeListReparsePayload.lf_trace_id`。该批次未通过，不能计为完全对齐；报告位置和 SHA-256 已保存在本次复核 JSON。本次只读取报告，没有重跑测试，该报告也不能替代当前源码冻结后的全量验收。

任务源码仍为 13 种 handler 对应 Go 的 22 种任务，其中批量重解析尚未验收完成。没有 handler 的 9 种是 `datasource:sync`、`faq:import`、`kb:clone`、`kb:delete`、`knowledge:move`、`memory:extract`、`temporary_document:process`、`wiki:finalize`、`wiki:ingest`。迁移清单保持 103 条进行中、378 条未开始、1 条 health 已实现、0 条 verified，未因代码入口或部分测试通过而升级。

另已核对 bridge 白名单：开启 bridge 时，仅白名单内请求可由 Java 处理，其他请求仍转发给 Go；关闭 bridge 不会补齐缺失业务。文档发布失败时的事务结果、配额计算、绑定外部索引写入、完整存储引擎、Lite/Redis 队列和并发取消等差异仍需解决。下文保留各历史批次的证据和范围，历史全量通过记录不覆盖后续改动。

## 此前结论与逐模块矩阵（历史）

**尚未完成全部迁移，Java 目前不能独立替换 Go 主服务。** 当前显式 HTTP 路由共 482 条：103 条 `in_progress`、378 条 `not_started`、1 条 health `implemented`、0 条 `verified`。迁移中的接口也可能只有部分输入、存储或副作用通过验收，数量不能当作业务完成比例。

当前独立扫描显式 Spring 方法/路径共 **110 个组合**，对应 **106 条 Go 路由（含 health）**，另外 **376 条**没有对应的 Java 显式接口。其中 **2 条重解析入口仅有草稿代码，尚未验收，清单仍保留 `not_started`**；因此378条清单待迁移项不等于378条完全没有代码。110与106的差额来自Go通用KV路由在Java中按固定key实现；通配报错处理器和Go bridge不计入原生覆盖。路径对应只证明存在代码，不证明条件Bean已注册或业务行为一致。完整方法、路径和源码位置见[本次逐接口核对](native-route-audit-2026-10-16-reparse-draft.json)；[摘要入口稳定快照](native-route-audit-2026-10-16-summary-public.json)和[原始核对快照](native-route-audit-2026-10-16.json)保留。

| 模块 | 有显式Java入口 / Go接口数 | 当前判定 |
|---|---:|---|
| 认证 | 16 / 17 | 主要链部分迁移；auto-setup尚缺 |
| 租户管理 | 20 / 26 | 部分迁移，仍有管理接口缺口 |
| 知识库 | 8 / 13 | 删除、复制及移动目标/复制进度等5接口尚缺 |
| 文档 | 20 / 32 | 含2条未验收重解析草稿；其余12条没有显式入口 |
| chunk | 10 / 10 | 接口已存在，完整存储/并发/切换语义仍未全部验收 |
| 模型 | 10 / 12 | copy/debug尚缺；API key入口及部分模型能力仍受限 |
| 向量存储管理 | 8 / 8 | 接口已存在，支持的引擎和连接管理范围仍受限 |
| 会话 / 聊天 | 0 / 40 | 原生公开主链未迁移 |
| Wiki / FAQ / Memory | 0 / 51 | 原生公开管理模块未迁移 |

任务层面，Go有22种业务任务。Java已有12种部分迁移并取得有界专项证据，另有 `knowledge:list_reparse` 1种草稿，仍有9种没有原生handler源码。Lite同步执行器没有完成对齐，SQL队列不消费Go的Redis/Asynq存量任务；13个handler文件不能视作13种任务已经验收。

公开摘要稳定批次已有56个SQLite实际Go场景、56个PostgreSQL实际Go场景及7个Spring生产链测试通过，专项组合257项全0。上一轮对比时，工作区已比该批次新增6个构建输入（5个Java重解析文件、1个Go参考场景导出器），修改2个权限类；当时1,160个输入的哈希保存在[历史输入快照](comparison-current-input-hashes-2026-10-16.json)。后续继续修正并增加验收，1,154个冻结输入及该1,160输入快照均不能证明当前源码已经全量通过。该次2,481个Go清单文件哈希全部一致；新增URL oracle测试后，当前清单刷新为2,482个文件（1,275实现、1,207测试），哈希全部相符，AST仍为482路由、480/480注册位置、0未解析。

后续重解析增量已修正JSON `null` metadata、NULL写回、任务字段、拒绝审计和错误文本等差异；SQLite组合125项/5类（100实际Go差分、7完整Spring链、18回归）及独立PostgreSQL100项实际Go差分均actual exit0、failures/errors/skips=0。最终Go oracle包含真实计数器故障、Asynq inspector和Wiki pending repository。详见[该批checkpoint](reparse-admission-checkpoint-2026-10-16.json)和[范围说明](../src/main/java/com/tencent/weknora/knowledge/reparse-migration-notes.md)。

再后续URL/file_url worker已接入安全下载、reader转换、临时资源及元数据/标题更新；265项/12类专项和回归actual exit0、全部0，包括31个实际Go下载/文件名观察、13个Go worker场景、5个原生保护，以及2条新增Spring真实HTTP/模型/物理索引流程。[最新checkpoint](remote-source-checkpoint-2026-10-16.json)与[限定范围](../src/main/java/com/tencent/weknora/knowledge/remote-source-migration-notes.md)保留报告和剩余差异。直接Go批worker完整差分、公开URL导入、全部并发/配置/存储/网络语义、冻结完整构建及同jar处理链仍未完成，因此两个接口与该任务的清单状态暂不升级。上面的输入快照和当前对比JSON属于这些增量之前的记录，不能覆盖后续源码；新Go oracle测试也已刷新到源码清单。

**最近一次完整构建：163类/7,914项，0 failures/errors/skips，actual exit0；属于重解析草稿之前的冻结源码。** 本次重新读取XML和退出码记录确认该结果，未对当前新增代码重跑全量测试。该批使用独立ES8容器补齐 `weknora.es.url` 后，对同一份冻结源码重新执行clean verify；原先139项失败的两个公开向量存储IT也全部通过。首轮缺失ES测试配置的失败报告保留，没有改动业务代码或放宽断言。

摘要稳定jar SHA256为 `4b4d3e39ba3304243198bae9d8a9a970ffbd7166daaeb05ab24e5819e8bc85bd`；公开摘要入口的single/go两种SQL worker布局均actual exit0，每种2上传/8任务/8embedding/6chat，覆盖同步首次生成、异步刷新、共享权限、入队失败及真实vec/FTS/源文件/配额保留。其冻结源码和jar证据保留；当前工作区已继续变化，该jar不包含重解析草稿。本jar只有这2种新增流程的进程验收，不能合并旧jar的6种或40种。报告、原始失败与限制见 [VALIDATION.md](../VALIDATION.md)和[摘要公开入口说明](../src/main/java/com/tencent/weknora/knowledge/summary-public-migration-notes.md)。

摘要入口之前保存的完整构建为 **7,851 tests /161 classes，0 failures/errors/skips**，显式包含公开向量存储 Go 差分 IT 133 项与 Spring IT 6 项，不能用默认 Maven 测试选择直接等同该范围。该批次的 **1,133 个构建输入哈希**在开始本增量前与当时工作区全部相符；本批源码新增后不能继续声称旧jar覆盖当前改动。旧jar SHA256 为 `c7b5e454c6d6fcb421e16ed6332383a5640f3c1444f0db0e5535b94c1d17d792`，其 **6 种同步进程配置**均有 actual exit0 记录；这些配置的 tasks=false，不是 worker 验收。

上一批 **7,712 tests /159 classes、40 种同 jar 流程/配置**属于另一个 jar，其中38种覆盖既有处理与同步chunk问题重新生成，另2种验证绑定ES8公开检索。不能与最新6种合称同jar46种，也不能据此前的40种声称最新jar已完成相同的全部worker验收。原始runner失败与修正重跑记录均保留。

新增同步chunk问题重新生成81 SQLite/81 PostgreSQL实际Go场景和9 Spring流程；绑定ES8元数据、凭据、registry、真实索引及公开Hybrid组合300项、52实际Go/PostgreSQL对照通过。PG公开联合链为10上传48任务（46成功/2取消）、38embedding/27chat HTTP。当前Go全套测试包含ES构造context取消修复，actual exit0。

公开向量存储管理8接口已有原生实现，新增133个实际Go对照及PostgreSQL对照；支持范围和限制见矩阵。完整RAG、会话/消息/SSE、Agent、其余任务、其余向量引擎和绑定后台文档写入仍有缺口；Go bridge的业务仍由Go执行。

逐项路由、权限、handler、文件哈希和任务注册见 [routes.json](routes.json)、[source-files.json](source-files.json)、[tasks.json](tasks.json)。状态来自人工维护的 [status.json](status.json)，运行 `python3 backend-java/migration/generate.py` 可刷新清单并保留状态。

## 当前运行实例与源码能力的区别

本次请求时 `http://127.0.0.1:8081/health` 返回200，Java 使用隔离数据库，bridge关闭，文档 processing 和 SQL worker 开启。根据该进程启动参数及项目默认配置，**`weknora.knowledge.upload.enabled` 未开启，Neo4j 也关闭**：上传控制器的条件注册不会因为 processing=true 自动启用，源码中已有上传/图谱代码不能证明这个实例已能使用它们。没有修改或重启该实例。

`127.0.0.1:8080/health` 当前未连通，因此这轮结论来自源码、已有差分记录和保存的构建证据，不能表述为刚刚完成双服务在线全量验收。8081仍运行先前7,851项构建的jar，不包含后续公开摘要入口；该旧jar的6种进程配置也不能替代当前源码或开发实例自身的全功能验收。

## 逐项功能矩阵

| 功能 | Java 原生实现与验证范围 | 尚未对齐的范围 |
|---|---|---|
| 健康检查 | 基础 `/health` 响应 | 尚未标为完整 verified |
| 认证/JWT/OIDC | 登录、注册、刷新、注销、OIDC 和主要权限链；实际 Go/HTTP 对照 | 部分身份防护更严格，platform/external principal、完整策略边界 |
| 租户、成员、邀请、API key | 主要 tenant 范围管理链及共享 KB 授权 | 平台/外部身份和部分管理接口 |
| 知识库 | 列表、详情、置顶；部分创建/更新事务 | 删除、复制、移动、共享管理与完整配置任务副作用；bridge 模式部分写接口仍交 Go |
| 文档/chunk 基础读取 | 文档列表、详情、chunk 列表/by-id；真实 Go HTTP 输出；chunk 编辑/回退196、问题增删130与重新生成81对照 | 文档模块仍有8条没有显式入口，包括图库、图片、批量读取、移动与进度查询、搜索及批量标签；批量ZIP、URL导入、下载/预览和重解析已有有界验收，仍为in_progress；详见当前逐接口快照 |
| chunk 删除与修订列表 | 单个/批量软删除、完整修订 DTO；117 SQLite 实际 Go 对照、3 完整认证和物理 vec/FTS/源文件保留验证、117 PG 对照通过 | 编辑/回退196 SQLite+6 Spring+196 PG已通过，问题增删130 SQLite/PG已纳入7538冻结构建及同jar两拓扑；重新生成81对照已纳入7712冻结构建/同jar两拓扑；更广并发/存储和bridge切换仍待完成 |
| 文件夹目录树/重命名/归类 | 151 SQLite 与 151 PG 实际 Go HTTP/SQL/拒绝审计对照、3 完整认证/物理向量/源文件保留；PG 全生产 42-task 流水线通过 | 已纳入7538共享冻结构建及同jar single/go；并发迁移边界和bridge切换尚待完成 |
| 文档标题/描述/自定义元数据更新 | 112 SQLite + 112 PG 实际 Go HTTP/SQL/Asynq差分、3生产认证/刷新；PG44任务处理链与同候选包single/go刷新通过 | 并发迁移/全部store/切换待完成；原始JSON精度保留，队列失败不回滚字段。公开摘要入口增量见下一行 |
| 公开摘要重新生成 | 已有摘要异步刷新、首次摘要同步生成；56 SQLite与56 PG实际Go对照、7 Spring认证/模型/物理索引链、7914项冻结构建及同jar两种worker布局通过 | 全部绑定引擎、模型协议、Redis/Lite、并发与切换仍待验收；保持in_progress |
| 手动文档公开写入与 `manual:process` | 98接口+45 worker实际差分、98 PG公开对照；严格非恒定向量/同内容多source；生产Spring/PG与7538冻结构建、同jar两拓扑通过 | 完整解析器、远端/多云资源、全部绑定引擎、重启与切换仍待完成 |
| 图片 `image:multimodal` | 66实际Go/Java处理场景；manual/image组合301项、PG76项/3类及真实生产worker；7538冻结构建与同jar两拓扑通过 | Ollama/legacy inline图片、普通文件图片生产者、绑定云/索引存储及全部并发窗口 |
| 索引状态与 chunk 完整详情 | 实际 SQLite FTS/vec0 向量、历史问题/Wiki、构建状态；160 个 Go HTTP、964 个状态样本等 | Neo4j 的完整搜索/删除生命周期、外部绑定存储、完整 PostgreSQL 详情差分和生产 backlog/stall 探测 |
| 构建记录 spans/stages | 两个 HTTP 别名、原生 tracker 与文档/postprocess/summary 接线；130 个 Go HTTP、9 个 Go SQL 对照，真实原生上传 | 原生 housekeeping、其余增强子任务、完整 PostgreSQL trace 差分、外部 Langfuse |
| 文件上传处理 | 显式开关下，本地存储、simple/HTTP/gRPC reader、分块、模型HTTP、实际索引与持久化任务；后续URL/file_url worker有44个实际Go观察场景及2条Spring生产链 | URL公开导入已有有界验收；临时文档、对象存储、全部解析器/格式、网络与失败副作用契约、完整PG URL处理仍待完成 |
| 分块、预览、原文定位 | legacy/adaptive、父子分块、TXT/MD/CSV/Office 定位与 formatter 的 Go 样本 | Office 定位不是内容转换；大文件/全部模型输入和完整公开流程 |
| 表格摘要 `datatable:summary` | 实际 DuckDB schema/sample、两次 Chat、table_summary/table_column、索引和清理；37 个 Go 全处理器回放及池/取消/顺序回归 | 任意解析器、外部存储、完整任务迁移/切换，Go/Java 的来源和租约安全差异 |
| 文档摘要 `summary:generation` | 实际 Chat prompt/options、文本重建、profile、summary chunk、索引、刷新、重试/fallback/counter/trace；公开刷新入口已有上述增量 | 任意配置模板、完整异常/取消与基础设施差分 |
| 问题生成 `question:generation` | 114 个实际 Go worker 样本；原生20 chunk窗口；公开问题增删130 SQLite/130 PG，同步重新生成81 SQLite/81 PG及9 Spring已通过 | 7712冻结构建/同jar已通过；其他索引引擎、队列重启和切换仍待完成 |
| 取消文档解析 | 显式原生 processing 模式下，54 Go SQL 场景 + 8 Spring HTTP 测试；保留已有 chunk/index，取消 SQL 队列与关闭开放 span；PG HTTP 取消通过 | Go 全 router/middleware 差分、Redis/Asynq 取消、完整并发竞态和切换；bridge 中该入口仍交 Go |
| 图谱 `chunk:extract` | 42 个真实 Go worker＋18 分发、521 个数字属性对照；SearchNode/DelGraph 属性与跨文档边界、重解析历史清理、实际 Neo4j/APOC、PG 联合链和两拓扑 jar 通过 | 公开 RAG 图谱检索编排、全部删除/移动/housekeeping 生命周期与并发窗口 |
| 自动标签 `knowledge:auto_tag` | 52 真实 Go SQL/HTTP 服务＋8 真实 Post 分发＋6 Spring 链；候选/置信度/采样、追加去重、归属/版本/租约校验；两拓扑 jar 通过 | 完整标签公开 CRUD/关系变更、同步执行器与全部并发窗口 |
| 知识库描述 `kb:profile` | 52 Go 服务＋23 Go 公开 HTTP＋13 分发＋10 Spring＋2 配置，共100项；聚合/hash/缓存/失败保留、owner 防护、Post/Summary/启用触发、同步共享生成、API key gate，两拓扑 jar/PG 通过 | 全部历史脏数据、时间/配置组合和并发窗口，SQL 固定窗口去重与 Redis 投递差异，完整队列切换 |
| 索引删除 `index:delete` | 41项专项；27真实Go worker/SQLite vec/FTS＋9生产者、原生租约/快照和3Spring；PG38任务链；已纳入5798-test稳定jar及两拓扑物理清理 | 绑定/其他索引引擎、完整删除/移动/重启/切换；公开Tag删除后续专项见下一行 |
| Tag 公开 CRUD / 文档列表删除 | 后续167项专项：36真实Go worker+88公开Tag HTTP/SQL/Asynq/business audit，8真实认证链；PG标签88场景和40任务处理链通过；文档删除候选包两拓扑通过 | 完整公开删除API、Wiki/云/绑定清理、异常并发/重启与桥接切换；已纳入6511-test稳定jar/两拓扑验收 |
| 公开文档删除 / batch-delete / clear | 后续123实际Go HTTP/SQL/Asynq +46worker状态边界；378专项含3真实认证/物理清理链通过；PG123已通过，扩展PG42任务生产链通过 | Wiki/云/绑定清理、异常并发重启与切换；已纳入6511构建/同jar两拓扑验收 |
| 图谱查询 | 55实际Go SQL存储/模型HTTP/Neo4j严格差分，647专项；16真实并行共享图谱Go场景及全Go测试通过，生产Bean基础已打包 | 公开会话/SSE/完整RAG编排、PostgreSQL查询内容回填与全范围检索 |
| Wiki | 历史索引与详情读取 | 完整生成、刷新、管理、检索和清理链 |
| SQLite FTS/vec0 | 原生读写、float32 向量字节及实际查询对照 | 不代表公开检索/RAG 已完成；大型/并发/重启和各平台验收 |
| PostgreSQL halfvec/ParadeDB | 原生索引读写、标签/启用/移动/复制、关键词/向量查询；实际 Go 仓库 16 组操作与 2,368 次查询严格对照 | 公开检索编排、完整上传处理差分、大规模 HNSW 计划、其余向量引擎 |
| 绑定外部索引存储 | owner-scoped vector_stores元数据/严格密钥解码、singleflight registry、ES8真实映射/写入/查询/删除；300项组合、52公开Go/Java/PG对照通过 | 绑定ES8公开检索已接通，但文档后台遇到非空 `vector_store_id` 仍明确拒绝；不能从适配器能写入推断文档worker已接通绑定写入 |
| 公开向量存储管理 | 列表、类型、详情、创建、更新、删除、未保存/已保存连接测试，共8接口；133实际Go HTTP/SQL对照、6 Spring IT与PG133对照；纳入7851历史构建及该jar6种同步配置，当前7914构建仍包含这139项IT | 原生仅支持ES8、SQLite、PostgreSQL范围；ES7运行时及Qdrant/Milvus/Weaviate/Doris/Tencent/OpenSearch等其余引擎未接通，未支持的连接测试/创建明确拒绝；独立PG连接管理仍有功能门禁。详见[公开管理说明](../src/main/java/com/tencent/weknora/vectorstore/public-api-migration-notes.md) |
| 混合检索、重排、知识问答 | 后续原生GET/POST hybrid-search；143实际Go HTTP/SQL/model、143 PG、288精确浮点rawbits、6认证链与3真实SQL deadline/borrow通过；最多4组并发和18次真实PG阻塞/取消严格对照通过；已纳入6511-test稳定jar/同步公开HTTP进程验收 | Servlet断连传播、knowledge-search/chat、会话/SSE/完整RAG、其他索引引擎与切换 |
| 模型 | 部分远程 CRUD/catalog 和多个供应商内部客户端 | copy/debug两条Go接口没有对应原生实现，通配报错处理器不计迁移；模型API key入口仍明确拒绝，完整公开推理/下载/协议边界未对齐 |
| 异步任务 | SQL durable queue，租约/重试/取消基础；13种业务handler部分迁移，修复SQLite并发writer升级冲突并实测 | Go共22种业务任务；批量重解析已有有界验收，9种没有handler；不消费Redis/Asynq存量任务，完整Lite同步执行器、公开task API、排空/重启/取消与切换未对齐 |
| 会话、消息、SSE、附件、反馈 | bridge 可转发 HTTP/SSE | 原生业务主链和停机/断线/重连/继续生成 |
| Agent、MCP、沙盒、IM、Memory、FAQ、Wiki 管理、组织、数据源、embed | 个别基础组件 | 对应完整 Java 原生公开模块 |
| 文件路由、签名 URL、多云存储 | 本地资源目录/绑定/签名基础 | 完整资源访问授权、文件路由、多云存储与后台清理 |
| 数据库迁移、部署和运维 | 已初始化 Go schema + 显式 Java SQL task schema；隔离实库测试 | 空库业务迁移自动化、完整 Docker/压测/升级/监控/任务切流与回滚 |

## 严格对齐仍需解决的行为差异

以下是当前实现的差异记录，不是本目标允许的例外。不能因为Java行为更保守或事务性更强就自行视为对齐；必须按Go基准补齐并取得对应验收证据。

### 文档发布事务与计费

Go 文档处理包含先清理旧数据、创建 chunks，再嵌入/写索引的多个 checkpoint。Java 将 chunks、索引、状态和 postprocess 入队放在同一事务中，嵌入失败时不会留下相同的部分发布结果。Java 在提交后才记录 `chunks_written/vectors_written`，不能提前声称构建成功。

Go 当前按新索引大小增加 `storage_used`；Java 文档发布按新旧差额调整，避免重试重复计费。该差异必须单独验收，不能计为完全一致。摘要与表格任务的索引也不能仅凭文档计费核对就声称全量配额兼容。

代码与边界：[DocumentProcessingRepository](../src/main/java/com/tencent/weknora/knowledge/DocumentProcessingRepository.java)、[文档迁移说明](../src/main/java/com/tencent/weknora/knowledge/migration-notes.md)。

### 表格任务与文档任务的执行顺序

原 Go 与 Java 文档清理均可能删除先完成的表格摘要。本轮修正两端的表格文档 worker：只删除自己替换的 chunk/index，保留并行生成的 `table_summary/table_column`。Go 重解析已有入队前同步清理旧资源，仍清理旧 attempt；Java重解析公开接口仅有草稿，必须补齐并验收这个边界。

实际 SQLite/vec0/FTS 测试覆盖表格先完成、嵌入期间完成与单连接上的表格任务后接文档发布。表格任务自身仍有 Go 原有多 checkpoint/cleanup 行为，不能由这一处竞态修复推断其他并发窗口全部一致。

### 原生库加载顺序

在当前 macOS arm64 的 DuckDB JDBC 1.5.2 与 SQLite/vec0 组合中，运行中才加载 DuckDB 可导致 SQLite constrained write 报 `SQLITE_NOMEM`，最小复现也曾 native crash。Java 入口现在在 Spring、连接池和 worker 启动前加载并关闭空 DuckDB，vec0 加载处保留初始化保障。已有失败日志保留，当前 macOS arm64 最终 jar 的八种流程已通过；不能只凭单元测试通过认定其他平台或运行方式已经解决。

### 摘要取消与任务 accounting

真实 Go 摘要 worker 的“Chat 期间取消”样本曾暴露继续发布结果的缺口；现已修复 Go 在模型调用后的取消检查，重新采集真实 Go 基准，44 个样本全部严格回放通过。Java 对业务取消确认过时工作，失租约、任务取消和中断仍走失败路径。修复后专项 63 测试通过，本修复已纳入 5,049 项完整 clean verify 和最终 jar 的摘要/取消两拓扑验收。Java summary handoff 把 finalizing/counter/入队原子提交，使用 SQL receipt 避免重复终端投递二次减 counter；Go 的入队与计数 checkpoint 并不完全相同。

专属样本、字段范围与限制：[summary-generation-migration-notes.md](../src/main/java/com/tencent/weknora/knowledge/summary-generation-migration-notes.md)。

### 存储与队列边界

PostgreSQL 索引适配器已用实际 halfvec/BM25 验证，不能再将其描述为“只有读取代码”。但实际存储适配器对照不证明全部公开检索、文档处理、绑定存储或 RAG 已完成。详见 [PostgreSQL 说明](../src/main/java/com/tencent/weknora/retrieval/postgres-migration-notes.md)。

Java 使用 `java_task_jobs` 等 SQL 表，不消费 Go Redis/Asynq 存量任务。12种部分迁移的业务handler不能当成22种任务或同步执行器全部迁移；Go的22种任务分别在Asynq与Lite同步执行器注册，共44项注册记录，不是44种业务任务。Java的 `topology=go` 是SQL worker池布局，不表示运行Go处理器或消费Asynq。

未迁移的10种任务为 `temporary_document:process`、`faq:import`、`kb:clone`、`knowledge:move`、`knowledge:list_reparse`、`kb:delete`、`datasource:sync`、`wiki:ingest`、`wiki:finalize`、`memory:extract`。原生task schema必须显式部署；不能在生产连接上用测试方式创建或清空数据。

### 权限、响应和桥接

共享 KB 写入必须在 owner tenant 下存储和构建，同时保留原 actor 的审计身份。API key 的 scope/KB allow-list 与 Viewer/Editor 边界必须按请求验证；不能只按角色名推断权限。

部分原生错误采用安全诊断文本，与 Go 原始依赖错误文字有差异。Bridge 不支持 WebSocket；SSE 基础转发不等于原生会话和完整取消/重连语义。

## 路由归属

| 设置或请求 | 实际处理者 |
|---|---|
| bridge 开启、native-routes 关闭 | health 在 Java，其余 HTTP 由 Go |
| bridge 开启、native-routes 开启 | `ownsNative` 精确白名单在 Java，其余仍由 Go |
| bridge 关闭 | 只有已注册 Java Controller 能处理；缺失功能不会自动补齐 |
| 部分 KB/model 写入、文件上传或 `agent_id` 列表（bridge 模式） | 仍遵守白名单，不能从 Java 源码存在推断已切换 |

`JAVA_NATIVE_ROUTES_ENABLED=true` 不是“全部切到 Java”。`X-WeKnora-Implementation: go-compatibility-bridge` 可识别转发响应，缺少该头也不能代替完整验收。

## 验证记录与通过标准

完整构建、专项实库与打包结果记录在 [VALIDATION.md](../VALIDATION.md)。历史各批次不可相加当作当前全量测试数，也不代表 482 条路由都已对齐。`PostgresIndexGoParityIT` 与 `PostgresProcessingPipelineIT` 为显式实库 IT，默认单元测试不会自动运行或假装跳过即通过。

```sh
# 实际 Go / Java PostgreSQL 仓库差分；创建并最终停止自己的一次性容器
python3 backend-java/scripts/postgres-index-parity.py
# 完整 Go PostgreSQL schema 上的原生上传/摘要/trace 集成验收
python3 backend-java/scripts/postgres-processing-smoke.py
# 实际打包 worker；JAR 和 vec0 路径按本机替换
python3 backend-java/scripts/processing_smoke.py /path/backend.jar /path/vec0 --topology single --office
python3 backend-java/scripts/processing_smoke.py /path/backend.jar /path/vec0 --topology go --summary
```

每个模块完成原生实现后，必须核对 HTTP/null/空集合/时间、授权拒绝路径、SQL/索引/文件副作用、外部请求、失败与重试、取消/来源更新/失租约、重复交付、重启、任务归属和运行配置。所有必需路径通过后才标记 `verified` 并扩大归属。

优先顺序：完成构建增强任务和公开文档写入 → 公开检索/RAG → 会话/消息/SSE → Agent/工具/其余模块 → 各存储/部署/任务排空/切换。迁移过程中继续保留 Go 回退和明确的未迁移错误。

2026-10-15 PostgreSQL 增量验收：隔离新容器应用全部 115 个 Go migrations，Hikari pool 1、bridge=false；6 个成功上传包含 own/shared 的普通、摘要、摘要+问题流程，另 2 个上传通过 HTTP 在真实摘要 Chat 调用期间取消。24 jobs（22 succeeded、2 canceled）、14 embedding HTTP、8 Chat HTTP，实际 halfvec、JSONB profile/question metadata、counter、Wiki ingest/retract 和原始 caller audit 全部通过。日志 `/tmp/weknora-java-postgres-enrichment-final.log`。这属于原生实库集成，仍非全流程 Go PostgreSQL 差分。取消保留 postprocess 分发时已完成的根 span，只关闭仍开放的下游 span，符合 Go 设计。


## 2026-10-15 摘要、问题与取消解析稳定批次

完整 clean verify：109 classes / 5,049 tests / 0 failures / 0 errors / 0 skipped，日志 `/tmp/weknora-java-full-enrichment.log`，jar `/tmp/weknora-java-full-enrichment-build/backend-java-0.1.0-SNAPSHOT.jar`。该 jar 的 Office、摘要、摘要＋问题与摘要 HTTP 期间取消均在 single/go 拓扑通过（八次）；Hikari1、owner/shared、真实模型 HTTP、SQLite vec0/FTS、问题索引、摘要/问题 counter receipt 和审计均实际核对。受影响 Go repository/service/handler 测试通过 `/tmp/weknora-go-full-enrichment-final.log`。图谱和自动标签后续开发不能引用本 jar 作为验证。

Docker 镜像构建在获取 Maven/Temurin 基础镜像 metadata 时被 registry-1.docker.io:443 connection refused 阻断，未进入编译；日志 `/tmp/weknora-java-enrichment-docker-build.log`。没有通过 Linux 镜像验收或生产切流。

历史6,511项构建批次的22个最终同jar进程验收通过；首轮简介go拓扑首次Hikari连接获取在2秒内超时，原失败保留，相同jar/参数单独复跑通过，根因未确认。该结果不将全部482路由标记verified，也未切换生产服务。
