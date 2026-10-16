# 迁移验证记录

2026-10-16 当前复核：**121/482 条 Go 接口有 Java 原生入口，361 条缺失；15/22 种任务有部分 handler，7 种缺失，0 verified，功能仍不一致。** 本批补齐分阶段文档复制核心，与已有 chunk/图片复制及真实 SQL CAS/配额接合；实际 Go75 场景及相关回归 **745项/7类全部通过、实际退出0**。1063个src/pom输入构建前后哈希一致。生产适配器、公开复制/进度和 kb:clone 尚未接通，PostgreSQL 执行未验收。最近默认完整10464项通过只覆盖旧索引复制1047输入快照，当前3个修改、16个新增，本批未重跑完整构建。未部署或重启服务。 见 [文档复制范围](migration/clone-document-notes.md) 和 [本批证据](migration/clone-document-checkpoint-2026-10-16.json)。

## 历史批次（以下结果仅对应当时快照）

前一配置复制批次：2026-10-16 当前复核：**120/482 条 Go 接口有 Java 原生入口，362 条缺失；14/22 种部分任务 handler，0 条 verified。** 新增原生知识库配置复制；206 项相关回归、独立 PostgreSQL 55 项对照全部通过，同一候选 jar 的 duplicate HTTP 在 single/go 两种 Java SQL worker 拓扑均实际退出 0。前一 r6 全量 10,012 项通过属于历史快照，本批未重新跑当前完整测试集。清单 119 in_progress/362 not_started/1 health implemented，无入口状态漂移；全量迁移未完成。详见 [最新对比](migration/comparison-latest.md) 和 [本批证据](migration/knowledge-base-duplicate-checkpoint-2026-10-16.json)。

2026-10-16 Elasticsearch 8 索引移动增量：已补原生 update-by-query、完整更新判定、默认重试、Go typed JSON/错误解码及取消/超时传播、不跟随重定向和首次产品标识校验。实际 Go HTTP252场景＋2个上下文场景、真实ES22个移动场景通过，核对66次完整物理快照和396次检索；同引擎物理ID、内容和向量保留。997项专项通过；当前975个src/pom输入冻结完整 clean verify **9,768项/187类，failures/errors/skips全0、actual exit0**，包含SQL移动、ES移动/检索及两个公开向量存储IT。公开移动/进度入口、绑定和 knowledge:move worker 尚未接通；仍116/482条入口、13种部分任务handler、0 verified，完整迁移未完成。没有部署、重启开发实例或切换前端。 见[本批checkpoint](migration/elasticsearch-index-move-checkpoint-2026-10-16.json)。以下保留历史批次证据，不将旧jar验收累加到本批。

此前SQL索引移动证据（2026-10-16）：SQLite 按源KB/文档原位迁移索引归属并清空标签，保留物理ID、向量和FTS；PostgreSQL移动时间改用Go对应的应用时间。实际Go SQLite13/PostgreSQL13对照及336项专项全部通过；PG使用完整115个迁移、Hikari1。覆盖完整选定元数据、源/目标/其他KB检索集合、回滚和重试。当时967个src/pom输入已记录，本批未另跑完整构建或打包验收；此前执行批次9,476项只证明其冻结快照。公开移动/进度入口、worker及其余运行时效果未接通，接口仍116/482、任务13种部分迁移、0 verified。见[SQL批次checkpoint](migration/index-move-checkpoint-2026-10-16.json)及[范围说明](src/main/java/com/tencent/weknora/retrieval/index-move-migration-notes.md)。没有部署、重启开发实例或切换前端。

此前移动执行证据（2026-10-16）：新增逐文档保留向量/重解析状态机、独立 Chunk/标签/归属提交、清理与配额事务顺序、确定性下游任务 ID/7天保留参数、重读确认及最终失败恢复。实际 Go SQLite64/PostgreSQL64 对照和316项专项全部通过；PG使用全部115个迁移、Hikari1。含并发解析不覆盖和6个Wiki边界场景。原生外部效果适配器、公开移动/进度入口和 knowledge:move worker 尚未接通，接口仍116/482、任务13种部分迁移，未新增 verified。该批962个 src/pom 输入冻结完整构建已确认9,476项/182类、failures/errors/skips全0、actual exit0；不包含后续SQL索引移动修改。见[当前 checkpoint](migration/move-execution-checkpoint-2026-10-16.json)和[范围说明](src/main/java/com/tencent/weknora/knowledge/move-migration-notes.md)。没有部署、重启开发实例或切换前端。

此前移动基础层证据（2026-10-16）：新增原生资源对兼容检查、完整持久化 transfer metadata、整批只读预检和独立 CAS/配额事务。实际 Go 对照 SQLite252/PostgreSQL252 均通过，PG 使用全部115个迁移及 Hikari1；专项444项/3类全部通过。保留 SQLite 旧时间格式导致的 Go CAS 冲突、数据库时间回读和并发非移动字段不覆盖。**公开移动/进度接口、Redis进度和 knowledge:move worker 尚未接通，路由和任务覆盖数未增加**。该批957个 src/pom 输入被冻结；完整 clean verify 已确认9,412项/181类、failures/errors/skips全0、actual exit0。此快照不包含后续移动执行逻辑，不能作为后续源码的全量证明。见[本批 checkpoint](migration/move-plan-checkpoint-2026-10-16.json)和[范围说明](src/main/java/com/tencent/weknora/knowledge/move-migration-notes.md)。没有部署、重启开发实例或切换前端。以下保留历史批次证据。

本批图片更新证据（2026-10-16）：新增原生 `PUT /api/v1/knowledge/image/:id/:chunk_id`。实际 Go HTTP SQLite92/PostgreSQL92 场景通过，PG 应用全部115个迁移、Hikari连接池1；对照原调用方权限、内外层JSON绑定、父子片段归属、OCR/描述新增与更新、逐阶段部分提交、索引调用和链式MD5。专项412项/5类通过。冻结完整 clean verify **9,160项/180类，failures/errors/skips全0、actual exit0**；同一jar的single/go原文+ZIP+批量读取/标签+图库+图片更新真实HTTP流程均actual0，各2上传/4成功任务/6embedding/0chat，独立核对6条物理vec/FTS、4个图片子片段、源文件、配额、任务、审计和零版本记录。当前116/482条Go接口有Java入口（120个方法/路径组合），文档30/32；清单115 in_progress/366 not_started/1 health implemented/0 verified，任务仍13种部分迁移、9种缺失。图片接口仍in_progress，绑定外部向量库/完整组合引擎、全部存储数据与并发/生命周期/切换仍待对齐。没有部署或重启开发实例。详见[本批checkpoint](migration/image-update-checkpoint-2026-10-16.json)、[实现范围](src/main/java/com/tencent/weknora/knowledge/image-update-migration-notes.md)和[当前对比](migration/comparison-latest.md)。以下为历史证据，不同jar的验收模式不能累加。

此前图库证据（2026-10-16）：新增原生图库配置和图片列表 GET 两个接口，实际Go HTTP SQLite152场景、完整115个迁移的PostgreSQL150场景通过（2个非法偏好JSON种子不可持久化，明确排除）。保留系统策略/原用户偏好、Viewer/retrieve/KB scope、SQL投影去重、属性规则、排序分页和只读副作用；共享数字分页绑定已按Gin 1.12先去空白。专项952项/5类通过；最终冻结 clean verify **9,064项/178类，failures/errors/skips全0、actual exit0**。同一jar的single/go原文+ZIP+批量读取/标签+图库真实HTTP流程均actual0，各2上传/4成功任务/2embedding；独立复核vec/FTS、源文件、配额、任务、审计、图库投影/策略清理及状态还原。当前115/482条Go接口有Java入口（119个方法/路径组合），文档29/32；清单114 in_progress/367 not_started/1 health implemented/0 verified，任务13种部分迁移、9种缺失。图库两个接口仍in_progress，完整存储数据/并发/生命周期/切换及其余模块仍缺。详见[本批checkpoint](migration/gallery-checkpoint-2026-10-16.json)和[当前对比](migration/comparison-latest.md)。没有部署或重启开发实例；下方保留历史证据，不同jar的验收模式不能累加。

此前批量标签证据（2026-10-16）：新增原生 `PUT /api/v1/knowledge/tags`，实际Go HTTP SQLite96场景、完整115个迁移的PostgreSQL95场景通过（1个非法JSON种子不可持久化，未计通过或skip）。保留Contributor/ingest/KB allow-list/共享Editor/后续所有者校验、重复map/null绑定、全量预校验和按文档ID逐个事务提交；中途失败保留此前提交。专项309项/3类通过；最终冻结 clean verify **8,908项/176类，failures/errors/skips全0、actual exit0**。同一jar的single/go原文+ZIP+批量读取/搜索+批量标签真实HTTP流程均actual0，各2上传/4成功任务/2embedding；独立复核vec/FTS、源文件、配额、任务、审计及临时标签/关系清理。当前113/482条Go接口有Java入口（117个方法/路径组合），文档27/32；清单112 in_progress/369 not_started/1 health implemented/0 verified，任务13种部分迁移、9种缺失。该接口仍in_progress，完整存储数据解码、并发权限变化、故障/取消/重启/切换及其余模块仍缺。详见[本批checkpoint](migration/batch-tags-checkpoint-2026-10-16.json)和[当前对比](migration/comparison-latest.md)。没有部署或重启开发实例；以下为历史证据，不同jar的验收模式不能累加。

此前文档读取证据（2026-10-16）：新增原生文档批量读取/搜索，实际 Go HTTP SQLite203场景、PostgreSQL在无统计信息/显式ANALYZE两种受控状态下各203场景通过，完整数组/DTO/错误/审计和无写入副作用逐项比较。最终冻结 clean verify **8,810项/175类，failures/errors/skips全0、actual exit0**；同一jar的single/go原文+ZIP+批量读取/搜索真实HTTP流程均actual0，各2上传/4成功任务/2embedding，独立复核物理vec/FTS、源文件、配额、任务和审计。首轮旧404路由断言失败及中止退出143记录保留；新快照完整重跑通过。两个新接口仍为in_progress：缺Go配置的Redis/Wiki stall_state探测；batch无ORDER BY，跨不同统计信息/计划不能保证稳定顺序。当前 **112/482条Go接口有Java入口（116个方法/路径组合）**，文档26/32；清单111 in_progress/370 not_started/1 health implemented/0 verified，任务13种部分迁移、9种缺失。详见[本批checkpoint](migration/collection-read-checkpoint-2026-10-16.json)和[当前对比](migration/comparison-latest.md)。完整迁移尚未完成，没有部署或重启开发实例。以下保留历史证据，不同jar的验收模式不能累加。

此前批量 ZIP 证据（2026-10-16）：原生批量原文 ZIP 下载完成 SQLite127/PG125 实际 Go HTTP 对照（另2个NUL文本种子不可在PG持久化）及20个实际Go archive对照，含200个不同文档、真实512MiB/512MiB+1流和完整成功ZIP摘要；相关429项通过。最终冻结 clean verify **8,605项/174类，全0、actual exit0**；同一jar的single/go文件+批量ZIP HTTP流程均actual0，各2上传/4成功任务/2embedding，独立复核vec/FTS、源文件、配额、任务和审计。四并发名额、失败清理/复用及POST条件已验证；全量暴露的旧HTTP接收队列溢出已有真实socket反例和64MiB慢消费者回归，并修正反压。Servlet断连传播与Go context取消仍有差距，接口保持in_progress；清单109 in_progress/372 not_started/1 health implemented/0 verified，110/482条Go接口有Java入口。详见[本批checkpoint](migration/batch-download-checkpoint-2026-10-16.json)和[当前对比](migration/comparison-latest.md)。完整迁移仍在执行，没有部署或重启开发实例。以下为历史证据，不同jar的验收模式不能累加。

此前原文批次证据（2026-10-16）：原文下载/预览新增 SQLite 209 个、PostgreSQL 207 个实际 Go 对照（另 2 个损坏 JSON 场景不能写入 jsonb），SQLite/Spring/真实 Tomcat 227 项通过。最终冻结完整 clean verify **8,451 项/171 类，failures/errors/skips 全 0、actual exit 0**；同一 jar 的 single/go 两种文件 HTTP 流程均 actual 0，各 2 上传/4 成功任务/2 embedding，并独立复核 vec/FTS、源文件、配额、任务和审计。已修正清洗后 ID 的 API key 范围及真实 Tomcat 的响应头删除差异，早期失败记录保留。清单为 108 in_progress/373 not_started/1 health implemented/0 verified；109/482 条 Go 接口有 Java 显式入口。完整迁移尚未完成。详见 [原文 checkpoint](migration/file-response-checkpoint-2026-10-16.json)和 [当前功能对比](migration/comparison-latest.md)。没有部署或重启开发实例。以下批次为历史证据，不能累加不同 jar 的验收模式。

此前 URL 批次证据（2026-10-16）：公开 URL/file_url 导入 SQLite/PostgreSQL 各94实际Go场景、107项SQLite/Spring、冻结完整 clean verify **8,237项/169类，failures/errors/skips全0、actual exit0**。同一jar的single/go两种URL流程均actual0，每种2上传/2导入/8成功任务/4embedding/1reader/0chat，独立核对物理vec/FTS、下载原文、配额和原调用方审计，并覆盖入队失败201/failed。全范围迁移尚未完成；新URL路由仅标in_progress，清单106in_progress/375not_started/1health/0verified。详见[URL checkpoint](migration/url-import-checkpoint-2026-10-16.json)和[当前功能对比](migration/comparison-latest.md)。没有部署或重启开发实例。以下旧批次数据保留为历史，不覆盖新改动，也不能将不同jar的模式数相加。

最近通过的完整冻结构建：2026-10-16，7,914 tests /163 classes，failures/errors/skips均0、actual exit0；同jar通过摘要公开入口single/go两种SQL worker流程。初次对比时，该批之后的重解析草稿仅编译通过（当时6个新增构建输入、2个权限类修改）；[该次对比记录](migration/comparison-review-2026-10-16-current.json)保留了当时源码、历史冻结jar与运行实例的区别。随后新增专项如下，下方也保留各批历史失败和修正证据；7,712项/40种流程属于更早jar。

随后重解析增量已完成有界专项：SQLite125项/5类（实际Go差分100、完整Spring链7、上传/guard回归18）和独立PostgreSQL实际Go差分100项/1类，均actual exit0、0 failures/errors/skips。最终Go oracle验证真实计数器故障、Asynq旧任务取消与Wiki pending；[重解析checkpoint](migration/reparse-admission-checkpoint-2026-10-16.json)保留当时报告、15个相关输入哈希和原失败记录。

再后续 URL/file_url worker 增量：265项/12类组合 actual exit0，failures/errors/skips全0，包括31个实际Go下载/文件名观察、13个实际Go worker场景、5个原生下载保护、9个Spring重解析流程（新增2个URL/file_url真实HTTP/模型/物理索引流程）和既有处理/权限回归。Go两个oracle重新执行actual exit0，导出语料逐字节一致；[本增量checkpoint](migration/remote-source-checkpoint-2026-10-16.json)保存19个范围内输入哈希、全部12份XML和日志哈希及原失败。[详细范围与未完成项](src/main/java/com/tencent/weknora/knowledge/remote-source-migration-notes.md)。URL公开导入、全部存储/解析器/网络异常/并发和PG URL处理仍待完成；未执行新的冻结全量构建或同jar验收，不能与历史7,914项相加当作当前源码全量结果。

验证日期：2026-10-12（Asia/Shanghai）。环境：Java 21.0.11、Maven 3.9.14、Spring Boot 3.5.16。

`mvn -f backend-java/pom.xml verify` 成功，24 tests、0 failures、0 errors：

| 测试 | 数量 | 证据范围 |
|---|---:|---|
| SecurityCompatibilityTest | 9 | Go bcrypt/JWT fixture；签名/expiry/session撤销、成员/租户边界、API key撤销与allow-list/capability、refresh单次消费 |
| KnowledgeBaseIntegrationTest | 5 | Spring完整HTTP链+实际SQLite Go init/000007；计数/脱敏、cross-tenant拒绝、creator筛选、个人pin排序、SQLite bcrypt登录/refresh/logout |
| GoKnowledgeBaseSerializationTest | 1 | document/FAQ/wiki三组由当前Go类型生成的JSON金样本，null/默认值/omission/typed字段/secret/capabilities |
| GoCompatibilityBridgeTest | 4 | 实际HTTP上游，原始body/binary、认证/租户/query/状态、SSE首事件在EOF之前flush、WebSocket未实现501、fixed origin、丢弃伪造forwarded IP |
| ModelProjectionTest | 1 | 67组实际Go ModelParameters.Scan与HTTP DTO金样本，角色/内置模型/secret/AES/decode/configured状态 |
| ModelIntegrationTest | 4 | 真实SQLite schema+Spring HTTP模型隔离/内置可见性/角色脱敏/参数与状态/未支持remote chat拒绝/API key拒绝 |

生成 executable jar：`target/backend-java-0.1.0-SNAPSHOT.jar`。
使用临时测试JWT secret与隔离SQLite配置启动独立Java进程，在随机HTTP端口验证：

- `GET /health` → 200，`{"status":"ok"}`。
- 未携带认证 `GET /api/v1/knowledge-bases` → 401。
- 随后对本阶段启动进程发送SIGTERM并退出；未部署、未提交、未push。

清单生成：`python3 backend-java/migration/generate.py`，482 routes、480/480注册位置、0未解析。状态保持1 implemented/9 in_progress/472 not_started，未声称全量verified。

未运行：真实Go服务与Java全接口对照、PostgreSQL/各向量库/对象存储/真实模型集成、Asynq/docreader/Agent/MCP/SSE完整生命周期/WebSocket、Docker镜像构建、性能/生产切流。现有Go服务及用户修改均保留。

Go KB fixtures重生成（仓库根；需先审计源代码变更）：

```sh
go run ./backend-java/src/test/fixtures/kb_fixture.go > backend-java/src/test/resources/go-kb-default.json
cp backend-java/src/test/resources/go-kb-default.json backend-java/src/main/resources/kb-default.json
go run ./backend-java/src/test/fixtures/kb_fixture.go schema > backend-java/src/main/resources/kb-schema.json
go run ./backend-java/src/test/fixtures/kb_fixture.go cases > backend-java/src/test/resources/go-kb-cases.json
```

Model fixture生成器与范围说明见 `src/main/java/com/tencent/weknora/model/migration-notes.md`。


## 2026-10-13 第二阶段完整验证

- `SecurityCompatibilityTest`：19 项通过（包含实际 Go DTO/JWT/preferences 金样本、三种 HMAC、旧无 exp、DB/ENV/settings、单事务 switch 与密码校验优先级）；另 6 个真实 SQLite HTTP/config/register 测试及 2 个 Go AES/seven-provider tests 已通过。
- `ModelProjectionTest,ModelIntegrationTest,RuntimeCatalogTest,CatalogOverlaySecurityTest`：10 项通过，含 131 Go DTO/Scan、740 Go runtime.Resolve、82 实际 Go providers/resolve handler 金样本；catalog last-good overlay 与错误隔离。
- `KnowledgeBaseLifecycleIntegrationTest,GoKnowledgeBaseSerializationTest,KnowledgeBaseIntegrationTest`：19 项通过（12 真实 SQLite 生命周期 + 2 序列化测试、共 8 Go payload/row 金样本 + 5 原有 HTTP 链）。真实 schema 为 Go init/000003/000007/000021；覆盖存储 default/legacy alias/状态/归属、vector 2200/2201、权限、immutable bindings、Go config partial-update/FAQ defaults、secret persistence/redaction、Unicode limits、profile 501 的事务回滚、审计失败仍保持业务 commit。
- docreader：25 常规测试及 2 项实际 Go gRPC 服务明文/mTLS/bearer 互操作通过；fixture 含 138 个有效/无效 source locator 场景。具体命令与证据见 `src/main/java/com/tencent/weknora/docreader/migration-notes.md`。`*IT` 互操作需独立测试服务，不自动计入常规 verify。
- `TaskRepositoryTest,TaskWorkerTest`：17 项通过（11 真实 SQLite SQL transaction + 6 真实 worker thread）。证明 Java 独立 queue 的幂等/tenant/lease/fencing/retry/DLQ/cancel/timeout/shutdown/最终数据库副作用原子提交；没有连接 Redis、没有消费 Go Asynq、没有声称业务任务已迁移。

最后执行 `mvn -q verify` 已成功（exit 0），425 tests、0 failures、0 errors、0 skipped。可执行 jar 为 61,077,288 bytes（约 61 MB）。

| 本次完整运行 | 测试数 |
|---|---:|
| Auth/security/API key | 34 |
| Models reads/catalog/remote mutations/SSRF | 18 |
| KB read/lifecycle/Go JSON | 20 |
| Go transport bridge | 5 |
| docreader gRPC regular | 25 |
| simple-format actual Go oracle + behavior | 73 |
| legacy chunker actual Go oracle | 233 |
| Java SQL queue repository + real worker | 17 |
| 合计 | 425 |

本轮整合曾发现 worker test 错误假定同毫秒提交的随机 ID 严格 FIFO，以及新 API key fixture 漏填 Go schema NOT NULL 字段；两者已修复，再次完整 verify 全通过。Queue 以 available_at/created_at/id 排序，测试现检查恰好一个 running 和一个 pending 的并发约束。

jar 在全新临时 SQLite 上运行（Go init/000003/000007/000021），验证实际 HTTP：health=200、anonymous KB=401、authenticated empty list=200、KB create=201、update=200、detail=200；tenant_id 强制当前租户与 secret redaction 均检查。随后 SIGTERM exit143，未保留运行服务。

持久化 fixture/代码与日志记录摘要见 `migration/status.json`；本轮临时日志 `/tmp/weknora-java-second-verify.log`。独立进程日志 `/var/folders/pf/6ssfy5pj7fj9w1jyg4kpbmcr0000gn/T/weknora-java-smoke-vd9tmtd9/server.log`。

另 175 adaptive chunker actual-Go cases 在此 full verify 后单独通过，连同 legacy233专项共408；尚未计入425。2 个实际GoGrpc明文/mTLS互操作是独立 IT，不计425。新 source changes 后需要下一次 full verify 再更新全套数量，不能把重复专项相加当完整测试数。

KB explicit SQLite/external vector engine 构造尚未迁移，已返回2201以避免跳过FTS/vector/image index修复；default PostgreSQL连接无初始化副作用 preflight 可用。画像更新 false→true 任务仍501，delete/copy/上传/索引/共享未完成。API key管理4 routes原生且7 HTTP/Go GORM黄金测试计入34；PostgreSQL仅方言绑定实现、未真实测试。所有路由继续 `in_progress`，未宣称全后端替换完成。

## 后续专项（完整 verify 待整合）

- 原生成员4条：`TenantMemberHttpIntegrationTest` 10项全部通过，最后Owner并发保护、machine capability、移除后会话撤销、mutation/role-denied审计；未验证实际PG，邀请/孤儿恢复未接线。
- chunker最新 legacy254 + adaptive175 =429项全通过（21新生产Go宽表stress）；使用RE2/J替代递归回溯regex，修复16k管道符表格行真实JVM StackOverflow。日志 `/tmp/weknora-java-adaptive-chunker-test.log`。
- HTML70 + 原生preview35 =105项全通过；preview含34实际Go handler输出和1鉴权/跨tenant/HTTP method边界，断言不写知识/分块表。日志 `/tmp/weknora-java-preview-test.log`。Syntax error wording与罕见HTML converter行为仍在完善，路由保持in_progress。
- storage67已准备、Maven尚未执行；不能把待跑测试记为通过。当前最近一次完整verify仍是上面的425，而不是把各次专项简单相加称全量验证。

## 第三次完整验证：829项全部通过

2026-10-13，`mvn -q verify` 返回0，34个Surefire测试类共829项，failures/errors/skipped全部0。日志 `/tmp/weknora-java-third-verify.log`；jar `target/backend-java-0.1.0-SNAPSHOT.jar` 为61,892,247字节。排除默认verify不执行的独立GoDocReaderInteropIT，未把旧报告或重复专项相加。

| 模块 | 本次测试数 | 结果 |
|---|---:|---|
| auth/security + member + tenant | 35+10+10 | 通过 |
| models + native client | 18+14 | 通过 |
| KB | 20 | 通过 |
| bridge | 5 | 通过 |
| docreader + simple format + HTML | 25+73+70 | 通过 |
| legacy + adaptive + preview | 254+175+35 | 通过 |
| Java SQL tasks | 17 | 通过 |
| storage physical/registry/resolver | 68 | 通过 |
| 合计 | 829 | 0失败/错误/跳过 |

storage首次oracle混用了不同TTL的Go派生token复用行，generator现按TTL清空grants隔离期望expiry；另加一项跨TTL同秒token保持原expiry的生产行为回归。修正后的storage68+bridge5专项73全部通过。新包进程级HTTPsmoke待更新；前述425包smoke仍是历史证据。无真实PG、外部云存储、Docker、生产或全482路由验收。

## JSON预览兼容性专项

2026-10-13，`GoJsonFirstValueCompatibilityTest,ChunkerPreviewIntegrationTest,GoHtmlTableCompatibilityTest` 专项162项全部通过（1+91+70，0失败/错误/跳过，`/tmp/weknora-java-preview-syntax-test.log`）。其中scanner单项包含7039个真实Go Decoder字节输入，HTTP预览90组实际Go handler响应使用base64请求体保留非法UTF-8；新增精确语法错误/首值尾随数据/Unicode字段fold/空值和重复键/最大嵌套深度。仍没有声明全接口兼容。

后续整合构建发现VSCode后台Java编译器可能污染`target/classes`（已删除package的残留class与protobuf class缺失），未把失败构建记为通过。`pom.xml`新增`weknora.build.directory`可覆盖输出目录，默认保持target；验证使用独立`/tmp`目录，不终止用户编辑器。下一次完整clean verify待所有新增模块稳定后执行。

## 第四次完整验证：976项与37次进程HTTP检查

2026-10-13，`mvn -q -Dweknora.build.directory=/tmp/weknora-java-verify-build clean verify` 返回0；42个Surefire类共976项，0 failures/errors/skipped。日志 `/tmp/weknora-java-fourth-isolated-verify-retest.log`，报告 `/tmp/weknora-java-verify-build/surefire-reports`。jar为61,950,704字节，包含Go MIME版权许可证。

| 模块 | 本次测试数 |
|---|---:|
| Auth/security/API key/member/orphan/leave/invitation codec | 120 |
| Tenant APIs | 10 |
| Models/catalog/CRUD/native client/Responses/stream helpers | 38 |
| KB | 20 |
| Bridge | 5 |
| docreader/simple formats/HTML/parser configuration | 172 |
| legacy/adaptive/preview/JSON scanner | 521 |
| Java SQL queue/worker | 17 |
| Storage/resource registry/Go MIME | 73 |
| 合计 | 976 |

整合首跑975项发现一次真实鉴权回归：新增孤儿恢复把tenant查询放在header切换权限之前，非成员访问不存在的目标空间从Go的403变为401。按真实Go middleware恢复权限→目标存在性→home恢复顺序，并新增三个precedence边界（无权header403、可访问但缺失header400、无header缺失401）；未修改preview测试迁就实现。修复后196项专项及本次976全套通过。

执行 `python3 backend-java/scripts/smoke.py /tmp/weknora-java-verify-build/backend-java-0.1.0-SNAPSHOT.jar` 返回0；新随机端口Java进程在临时SQLite上通过37次HTTP请求，包括23组实际Go preview状态/完整body、health、匿名401、KB创建/更新/详情、强制租户和secret脱敏、API key preview/leave边界、last-owner409、viewer退出与令牌撤销、孤儿home Owner恢复。数据库额外检查preview未生成knowledges/chunks、KBsecret原值持久化、viewertoken撤销与orphan成员。日志 `/tmp/weknora-java-fourth-smoke.log`；服务日志 `/var/folders/pf/6ssfy5pj7fj9w1jyg4kpbmcr0000gn/T/weknora-java-smoke-4y7ocpw2/server.log`。测试进程已SIGTERM停止；未部署。

ParserConfiguration的320规则/默认选择与106 tenantoverride真实Go样本均纳入4方法；scanner的7039字节样本纳入1方法；MIME的1117注册与24参数样本纳入4方法。不得把样本数量额外累加为测试方法或已完成路由。Go fixture生成器均保留在测试目录。

清单重新生成：482路由，480/480注册位置，0未解析；1 implemented/42 in_progress/439 not_started。只有逐项兼容证明才可标记verified，桥接不是原生迁移。上传配置、邀请完整状态机/HTTP、模型图片拒绝重试等下一阶段staging仍在/tmp，未纳入本次976。完整上传→解析→分块→embedding→索引、检索/RAG/会话/Agent/MCP、云存储/向量适配器、全量任务/Go路由及真实PG/外部服务/Docker/生产切流仍待完成。


## 第五次完整验证：1,272项与56项实际HTTP全部通过

2026-10-13，`mvn -q -Dweknora.build.directory=/tmp/weknora-java-verify-build clean verify` 返回0，52个Surefire类共1,272项，failures/errors/skipped均0。日志 `/tmp/weknora-java-fifth-isolated-verify.log`。可执行jar `/tmp/weknora-java-verify-build/backend-java-0.1.0-SNAPSHOT.jar` 为62,083,116字节，Go MIME第三方许可已确认打包在 `META-INF/licenses/GO-MIME-LICENSE.txt`。新Java源码179个，物理路径与声明package均一致，Git不再因根目录二进制忽略规则漏掉包目录。

| 模块 | 本次测试数 |
|---|---:|
| security/auth/member/orphan/leave/invitation/registration/binder/limiter | 395 |
| tenant | 10 |
| models/catalog/mutations/native clients | 42 |
| KB | 20 |
| bridge | 5 |
| docreader/gRPC/HTTP/simple/HTML/parser config | 178 |
| chunker/adaptive/preview/JSON scanner | 521 |
| SQL task backend | 17 |
| storage | 73 |
| knowledge process config/upload service | 11 |
| 合计 | 1,272 |

新增正式整合证据包括：邀请状态机15个Go状态/审计对照、6项实际MVC邀请测试、10项邀请注册SQLite/MVC测试、225个实际Go请求绑定/validation响应、19项公共限流/真实Gin proxy IP检查；模型图片重试/session/header32案例52请求、Anthropic558构造+82实际HTTP/SSE；文档HTTP6项含136真实Go请求/响应/type/syntax/base64/重复slice/负零与配置/DNS/取消/期限；knowledge process72、overrides212、names29、上传81生命周期及5真实SQLite/文件/资源/SQL任务场景。各fixture在JUnit方法内循环与动态测试的数量已区分，完整总数严格来自本次52个XML报告。

运行 `python3 backend-java/scripts/smoke.py /tmp/weknora-java-verify-build/backend-java-0.1.0-SNAPSHOT.jar` 成功，56项实际HTTP检查通过：KB读写/tenant强制/secret脱敏、23组Go preview响应与零知识/分块写入、API key权限、邀请全部10路由、邀请注册与新token认证、Viewer不暴露invite URL、匿名/API key inbox拒绝、接受/拒绝/撤销、tenantless home、share重复加入不重复计数、工作区退出/会话撤销/最后Owner保护/孤儿Owner恢复。初次脚本错误读取非Go DTO字段 `token`，修为从响应 `invite_url` 解析后重跑通过；没有调整服务实现或Go期望来让脚本通过。

成功日志 `/tmp/weknora-java-fifth-smoke-retest.log`；新独立Java进程日志 `/var/folders/pf/6ssfy5pj7fj9w1jyg4kpbmcr0000gn/T/weknora-java-smoke-bir_bkrr/server.log`。测试后进程已停止。

迁移清单仍为482路由，53 in_progress、428 not_started、1 health implemented，没有新增verified路线。上传服务仍未连接HTTP/生产Enqueuer/worker/Embedding/index；SQL任务queue/retry/timeout契约尚需完整生产adapter；公开推理/RAG、其它协议、OIDC/全域拒绝审计、完整parser providers、真实PG/云存储/向量库、Docker/压测/生产切流尚未完成。17个邀请注册完整Go业务oracle已在/tmp验证，尚未正式落盘或计入此次1,272。


## 第六次完整验证：1,534项、71项实际HTTP及生产处理链

2026-10-13，独立输出目录 `/tmp/weknora-java-verify-build/` 的 `mvn -q -f backend-java/pom.xml -Dweknora.build.directory=/tmp/weknora-java-verify-build -Dweknora.sqlite.vec.extension=/tmp/weknora-sqlite-vec-native/vec0.dylib clean verify` 返回0。67个测试类、1,534项、failures/errors/skipped全部0，未把重复专项或历史IT累加。日志 `/tmp/weknora-java-sixth-isolated-verify.log`。

| 本次完整运行包 | 测试数 |
|---|---:|
| security | 620 |
| tenant | 10 |
| model + model.client | 45 |
| knowledge（KB及上传/处理） | 42 |
| storage | 73 |
| docreader | 178 |
| chunker | 521 |
| task | 29 |
| sourceloc | 7 |
| retrieval | 4 |
| bridge | 5 |
| 合计 | 1,534 |

本次实际整合：邀请注册17组Go业务、18组guard/audit、mapped IP规则与KB write38组Go/8MVC；KB read62+69实际GoHTTP+2configured builtin共133新增检查与63field typed Agentconfig；Ollama78场景/355实际HTTP+float32原始位；SQLite11mutations/704vec0FTS查询/8输入清理；sourceloc range/point/HTML quote/CSV/rewrite对照；生产task options、294实际Go settings/全部22task type队列权重和六独立pool；上传76实际GoHTTP绑定+24实际GoProcessDocument/postprocess场景与11新JUnit方法。fixture循环、参数化动态测试和JUnit方法数量按真实报告统计。

新jar为62,304,130字节，包含Go MIME/HTML/Unicode和sqlite-vec许可证，以及native Ollama、read authorization、worker pool、document processing类。234个Java源码文件声明包与路径一致，全部未被Git忽略；Go fixture均不进入正常Go包构建。

新jar在全新真实Go-schema SQLite启动独立进程，`scripts/smoke.py` 通过71个真实HTTP请求，含原56项和15项shared KB读取：owner计数/处理状态、私有vector元数据隐藏、COS/VLM脱敏、组织共享、implicit/explicit Agent、source selector/不存在/撤销/disabled UI、full-access Owner与scoped-key Viewer、capability/allowlist精确错误体。日志 `/tmp/weknora-java-sixth-smoke.log`。首次扩展smoke暴露fixture缺少embedding/summary非NULL字段，以及scoped key Viewer错误期待Editor，按实际Go auth上下文修正测试数据后全绿；没有扩大授权。

`processing_smoke.py` 对同一jar分别在 `--topology single` 与 `--topology go` 完成实际production Spring beans链路：multipart HTTP → StorageResolver/local resource → SQL durable task → reader/chunker/source hash → ModelClientFactory真实HTTP → JDBC vec0/FTS → durable postprocess → completed。每次2上传、4succeeded jobs、2actualEmbedding HTTP，Hikari pool1、owner和foreign Contributor真实JWT/shared Editor grant、所有写入owner租户、audit原actor与task ID、资源绑定、quota等价检查均通过。日志 `/tmp/weknora-java-sixth-processing-single-smoke.log` 和 `/tmp/weknora-java-sixth-processing-go-smoke.log`。metadata连接关闭后才初始化索引，修复pool1二次取连接阻塞。主HTTP单测的foreign tenant role也修正为实际合法Contributor，随后4项targeted测试通过（`/tmp/weknora-java-shared-upload-role-correction.log`）；生产实现和已验证jar未改变。所有独立进程与modelstub均已finally停止。

清单54 in_progress、427 not_started、1 health implemented，共482路由/480注册位置/0未解析，没有完整verified路由。上传仅显式开关下的基础处理，Go bridge上传归属及全能力上线仍保留；summary/question/graph/wiki/autoTag/profile/CSVsummary/Office/ASR/image/cloud/bound stores/PG未迁完。Java SQL queue不消费Redis/Asynq，六池配额是启动快照且每池显式上限64，runtime/taskAPI仍待完成。完整SDK malformed/duplicate binder、公开推理/RAG/会话/Agent/MCP、完整剩余路由、真实PG/Docker/压测/生产切流仍未完成。后续/tmp staging的Office/read链/SDK测试不得算进此次1,534。


### 第六阶段SDK补充后的最新全量验证：1,536项

在上述1,534之后，新增Ollama typed response binder6个changed/new main文件、2个JUnit方法；1,498个locked SDK实际Go解码案例、236个生产GoHTTP案例/987请求全部对照（236包含旧78，不能加成314个净新增）。保留Go重复字段/大小写/null/ordinary-vs-fatal first-error、自定义Message/OrderedMap reset、unused timestamp/base64类型校验，以及工具参数Float64原始位和格式。未把Ollama等同所有模型SDK。

`/tmp/weknora-java-sixth-sdk-isolated-verify.log` 的完整独立 `clean verify` exit0：69 classes、1,536 tests、failures/errors/skipped全部0。model+model.client由45增为47，其他包数量保持上表。此运行也包括此前共享上传测试的合法Contributor角色修正；不把专项重复累加。最新jar62,324,914字节，239个Java路径/包声明正确且无Git忽略，OllamaTypedJson与Go Unicode表/许可证均已实际检查打包。

最新jar71实际HTTP通过：`/tmp/weknora-java-sixth-sdk-smoke.log`。单池与六池production处理均通过：`/tmp/weknora-java-sixth-sdk-processing-single-ready-smoke.log`、`/tmp/weknora-java-sixth-sdk-processing-go-ready-smoke.log`，每次仍2上传/4任务/2实际Embedding HTTP、真实vec/FTS、Hikari1和共享owner/actor审计。服务和模型stub已停止。

同时启动三个JVM的首次单池测试在首个上传前认证返回503（日志 `/tmp/weknora-java-sixth-sdk-processing-single-smoke.log`），随后单池独立重跑成功。加入只读 `GET /auth/me` 就绪探测后的并行单池/六池也通过，单池曾需要1次就绪重试、六池0次；观察符合lazy数据库初始化尚未就绪的推断，但没有定位全部底层启动延迟来源，不能计生产readiness/容量验收。没有重试上传POST，没有因此重复写入。此改动只调整测试启动等待，不放宽认证、不修改业务实现。

公开知识/分块读取、Office source结构、hybrid/RRF/rerank仍是后续agent隔离staging，未纳入1,536。482路由的54/427/1状态不变，全Go主后端替换仍未完成。


## 第七次完整验证：2,122项及96项实际HTTP

知识列表、知识详情、分块列表、分块by-id正式落盘，保留共享owner执行租户、组织/explicit或implicit Agent只读授权及API Key retrieve/KB范围。Go静态batch/search不会被知识详情动态路径接管；桥接选择器仅接入四条已实现GET，相邻写入或子路径仍保留原归属。

`/tmp/weknora-java-content-read-full-verify.log` 对应完整独立 `clean verify` exit0：70 classes、2,122 tests，failures/errors/skipped均0。同一次报告的分组为security1205、tenant10、knowledge42、model36+model.client11、docreader178、chunker521、storage73、task29、sourceloc7、retrieval4、bridge6；总数没有相加历史专项。新增585个security方法为584个实际Go HTTP全DTO案例与1项静态路由保留检查，新增bridge方法验证4GET及相邻方法/子路径归属。

新jar62,356,621字节，244个main/test Java源码路径与声明包名吻合、均未被Git忽略。真实独立进程+生产Go SQLite schema的 `scripts/smoke.py` 通过96项HTTP（原71+新增25，日志 `/tmp/weknora-java-content-read-smoke.log`）：原生四条读取、owner过滤、分页、大小上限、标签、关键词通配符转义、空文件夹/递归、deleting过滤、profile与source_locators typed JSON、内部source/header隐藏、disabled/status分块、API capability/KB scope、组织撤销与Agent回退。测试补种的chunks/tags/Agent记录清理后，原零分块与共享撤销断言仍通过。

同一jar的single和go六池生产处理回归均通过：`/tmp/weknora-java-content-read-processing-single.log`、`/tmp/weknora-java-content-read-processing-go.log`。每次2上传、4个持久化成功任务、2次真实loopback Embedding HTTP、Hikari1、真实vec0/FTS、postprocess完成、共享owner写入与原actor审计；本次两者readiness重试均0。服务和stub已停止。这是隔离功能验证，未替代生产压力/readiness验收。

清单482路由：58 in_progress、423 not_started、1 health implemented。读取SQL扫描错误体code/details已对照，但2项驱动/列索引错误文字不是字节级相等；其他stored JSON/time查询边界、真实PG仍待审计，因此新读取保持in_progress。Office结构定位、混合检索/Jieba/rerank、解析引擎registry/probes在/tmp独立验证，未计入此次正式结果；完整Go主后端、公开RAG/会话/Agent/MCP、所有存储/模型、Docker/生产切流仍未完成。

## 解析引擎、读取边界、Office 与 hybrid 基础的完整验证

2026-10-13，隔离目录一次 `clean verify` exit 0，81个测试类、
**3,079 tests / 0 failures / 0 errors / 0 skipped**。
日志 `/tmp/weknora-java-parser-office-full-verify.log`，
可执行jar `/tmp/weknora-java-verify-build/backend-java-0.1.0-SNAPSHOT.jar`，
62,468,126字节。旧2,122与各专项为历史证据，不相加计数。

```sh
mvn -q -f backend-java/pom.xml \
  -Dweknora.build.directory=/tmp/weknora-java-verify-build \
  -Dweknora.sqlite.vec.extension=/tmp/weknora-sqlite-vec-native/vec0.dylib \
  -Dweknora.jieba.library=/tmp/weknora-jieba-native/libweknora_jieba.dylib \
  -Dweknora.jieba.dict=/tmp/weknora-jieba-native/dict clean verify
```

| 同一次完整运行的包 | 测试数 |
|---|---:|
| security | 1,847 |
| tenant | 10 |
| knowledge | 344 |
| model + model.client | 37 + 12 |
| docreader | 180 |
| chunker | 521 |
| storage | 73 |
| sourceloc | 11 |
| task | 29 |
| retrieval | 7 |
| system | 1 |
| bridge | 7 |
| 合计 | 3,079 |

新增范围与证据：

- 日期解析300项真实Go对照（75输入×4时区），含逗号/超长小数、单小时、
  ±24:60时区、DST；640项真实Go内容读取HTTP对照使用生产SQLite schema，
  保留大整数、TEXT JSON标量并拒绝尾随JSON。
- 三条原生解析引擎管理接口：108 registry、408真实HTTP探测、
  121 Go SystemHandler HTTP响应、18真实HTTP重定向trace；
  capitalized DTO、null/[]、重复引擎、secret/rules合并和不落库check均对照。
  四个新JUnit方法循环这些案例，另有真实Spring鉴权/角色/API key测试。
  初次65项专项有1项redirect失败，暴露HEAD/请求体headers差异；修复后
  clean17类/65项全绿，并进入本次全量验证。
- Office ZIP原文定位：234真实Go提取/对齐案例、18生产
  attachStructureBlocks输出与4个完整输出hash；
  6个新JUnit方法含SQLite production processing成功、降级与回滚。
  曾修正一项旧测试对empty original错误预期；Office standalone35项随后clean全绿。
  DOCX/DOCM/PPTX/PPTM/EPUB与部分XLSX显示格式已接线；完整自定义/本地化
  Excel格式、64MiB/archive-member与Go容量差异、转换/ASR仍未完成。
- Hybrid/Rerank基础：428真实Go算法与12真实Go SQLite→模型HTTP→fusion/rerank
  场景（20实际HTTP）；另80 typed metadata场景修正ImageAttrs.Source为实际结构体。
  JNI使用锁定gojieba v1.4.7的C++ cppjieba，部署Java不执行Go业务代码。
  公开hybrid/RAG、完整授权后SQL hydration、FAQ/image流程仍未接线；
  agents另外的/tmp staged案例没有计入本次正式通过数。

打包后独立进程：

- `smoke.py` exit0，**133项实际主后端HTTP检查**：旧96+11读取边界+26解析管理。
  后者另断言10次loopback reader/probe HTTP（8次discovery+2次MinerU），
  未混入133。完整JWT/Viewer/Admin-before-binding、scoped机器key、check不存DB、
  shared reader重连与remote null/[]/duplicate均验证；日志
  `/tmp/weknora-java-parser-office-smoke.log`。
- 首次和诊断运行因脚本误把未知成员role的Java提前认证拒绝当作Go route
  guard拒绝而失败，记录分别保留为
  `/tmp/weknora-java-parser-office-smoke-initial-failure.log` 和
  `/tmp/weknora-java-parser-office-smoke-diagnostic-failure.log`。
  修正脚本如实断言已知差异，未修改生产代码：Java unknown role先typed403且
  无role-denial审计，Go guard403并审计。正式检查2条POST审计模板与去重。
  本次133通过不代表未知role与Go字节一致。
- `processing_smoke.py --topology single` 和 `--topology go` 均exit0，
  每种2uploads/4持久化jobs/2次Embedding HTTP；真实本地文件、vec0/FTS、
  postprocess完成、共享owner租户/原actor审计，Hikari pool1。
  只读数据库就绪重试分别1次/0次，没有重试上传POST。日志
  `/tmp/weknora-java-parser-office-processing-single.log` 与
  `/tmp/weknora-java-parser-office-processing-go.log`。
- 主Java进程及所有测试HTTP服务器已停止。274个Java源码package路径均正确，
  没有Git忽略；独立jar包含Go MIME/HTML Unicode、Excelize、cppjieba与limonp
  license notices。所有Go fixture为build-ignore，`go list ./backend-java/...`
  exit0/no packages，不污染Go主构建。

`migration/generate.py` exit0：482路由、480/480注册位置、0未解析，
61 in_progress / 420 not_started / 1 implemented（health），没有verified路由。
真实PG/云存储/外部模型/全部OCR与转换、OIDC、公开RAG/SSE/Agent/MCP/其余Go
业务、Docker和生产切流尚未完成。本次新增公开接口与基础组件仍保留in_progress。


## 解析配置 KV 与模型 BLOB 的完整验证

2026-10-13，同一次隔离 `clean verify` exit0：**3,084 tests / 83 classes / 0 failures / 0 errors / 0 skipped**，日志 `/tmp/weknora-java-parser-kv-full-verify.log`。jar `/tmp/weknora-java-parser-kv-full-build/backend-java-0.1.0-SNAPSHOT.jar` 为62,481,288字节。包统计security1847、tenant14、knowledge344、model38+model.client12、docreader180、chunker521、storage73、sourceloc11、task29、retrieval7、system1、bridge7。此前3,079是历史验证，没有相加重复专项。

- 原生GET/PUT `/api/v1/tenants/kv/parser-engine-config`：1,092个真实Go handler/service/生产SQLite场景，完整HTTP响应、持久化JSON和时间变化对照；另3方法覆盖真实AuthFilter、JWT积分配置Admin、settings capability、角色拒绝在绑定之前、audit去重、foreign isolation、写失败回滚和live role recheck。修正shared typed omitempty对pointer false的处理，完整回归确认其他消费者未失败。Go fixture来源由实际Go类型反射，重生成语义相等日志 `/tmp/weknora-parser-config-kv-root-replay.log`。
- 模型factory修复SQLite BLOB参数，6个实际Go repo.Create→tenant model service→HTTP场景和6次模型请求在1方法中对照；malformed/encryptedsecret仍失败且不发HTTP。Root重生成语义相等 `/tmp/weknora-model-blob-root-replay.log`。专项11项exit0 `/tmp/weknora-java-model-blob-checkpoint.log`。不以SQLite验证推断PostgreSQL已验证。
- `smoke.py`新jarexit0，**146个实际主后端HTTP**（133+13个KV），保留额外10次System probe HTTP独立计数；配置权限、三密钥脱敏、BLOB真实保存、false、rule保留/清空、请求错误/SSRF回滚、scopedkey与foreign isolation均验证。日志 `/tmp/weknora-java-parser-kv-smoke.log`。
- single/go两种生产上传链均exit0，每种2uploads/4succeeded durable jobs/2actualEmbeddingHTTP，Hikari1、真实vec0/FTS、sharedowner/原actor审计。此次模型参数明确按Go BLOB存储且SQL typeof检查为blob；readiness GET retries均0，无上传POST重试。日志 `/tmp/weknora-java-parser-kv-processing-single.log` 与 `/tmp/weknora-java-parser-kv-processing-go.log`。三个Java进程与HTTP stub均停止。
- 279 Java source包/路径吻合、无Git忽略，jar的Go MIME/HTML Unicode/Excelize/cppjieba/limonp/sqlitevec许可证实际存在。Go fixtures仍build-ignore，正常Go包扫描exit0/no packages。

Go settings写入还通过非nil StorageEngineConfig触发旧tenant与alltenant读取扫描；Java未复现该依赖，不以未变更bucket为由宣称全service一致。原始JSON字节、数据库driver fault文字、真实PostgreSQL及其他KV键也未完成。Generic GET/PUT KV两条路由已在旧61项in_progress内，本次没有虚增路由数。独立Excel custom formatter、全hybrid/OIDC staging未混入此次正式结果。完整482路由迁移目标保留，未宣布全后端完成。


## Excel 格式正式整合后的完整验证

2026-10-13，完整隔离 `clean verify` exit0：**3,088 tests / 84 classes / 0 failures / 0 errors / 0 skipped**。日志 `/tmp/weknora-java-excel-full-verify.log`，jar `/tmp/weknora-java-excel-full-build/backend-java-0.1.0-SNAPSHOT.jar` 为62,574,215字节。同次包统计sourceloc由11增到15；其余包与前述3,084运行相同。不把真实Go样本数量当JUnit方法数，不把历史运行相加。283个Java源码package路径正确且没有Git忽略；实际jar含NFP、Excelize、Go MIME/Unicode、cppjieba、limonp、sqlitevec许可证及生产Excel locale表。

正式26个artifact逐文件SHA256审查后合入：6个生产源码、Go goldens/generators、3个新/更新测试与模块说明。通用Excel/nfp格式解析与UTC日历移植，覆盖4,520 parser、59,982格式结果、32真实OOXML/87,439定位单元；custom/localized/currency/era/systemdate、1900/1904、typed cells、styles/namespace/qualified root/signed64ID均按实际Go对照。旧Office对照现235案例/20attach输出，真实SQLite custom XLSX处理与postprocess成功；NaN fraction明确失败并不发布索引或postprocess。

首次24项专项因root命令漏传必需vec0参数导致8个处理链初始化失败，日志 `/tmp/weknora-java-excel-checkpoint.log` 保留；补齐参数后的同5类24方法exit0，日志 `/tmp/weknora-java-excel-checkpoint-fixed.log`，未修改生产代码或断言来掩盖缺少依赖。Root独立重运行Go generator后，四个fixtures与locale表逐项语义相等，日志 `/tmp/weknora-excel-root-replay.log`。Go NaN + `# ?/?`有独立3秒timeout证据；Java有限分数4096步容量明确是Java限制。Root另观察Inf/+Inf/-Inf同输入各3秒timeout并kill/reap，不虚增正式fixture计数；darwin/arm64非有限整数转换不推断Linux/amd64等价。

新jar `smoke.py` **146个实际主后端HTTP检查通过**，日志 `/tmp/weknora-java-excel-smoke.log`；额外10次System reader/probe HTTP仍单独计数。当前jar默认single/go处理链均通过：每种2uploads/4jobs/2actualEmbeddingHTTP，Go BLOB模型参数、Hikari1、vec0/FTS、sharedowner/原actor审计，readiness GET retries均0。日志 `/tmp/weknora-java-excel-processing-single.log` 和 `/tmp/weknora-java-excel-processing-go.log`。

额外 `processing_smoke.py --topology go --office` **没有通过完整上传验收**，失败日志 `/tmp/weknora-java-excel-processing-go-office.log`：3文档处理+3postprocess成功，但单独的 `datatable:summary`进入dead，原因`No Java handler registered for this task type`。脚本保留严格全任务成功要求并已修正表格应有7个任务的计数，未忽略dead任务来变绿。对该真实SQLite独立检查确认custom XLSX的3.1400文本、sheet/row/quote/sourcehash与Go完全一致、1个embedding索引、owner tenant1/foreign Contributor审计；证据 `/tmp/weknora-java-excel-packaged-source-inspection.log`。这证明实际jar formatter/table资源可用和source处理成功，不证明表格摘要或完整Office上传已完成。受控HTTP reader只提供Go fixture解析文本，未计作原生Office内容转换。所有本轮Java与stub进程已停止。

清单仍482路由/480注册位置/0未解析，61 in_progress、420 not_started、1 health implemented，没有verified。四个已含实际native接口的module此前误留not_started，现按正式证据修正为in_progress。图片资源链、OIDC与完整hybrid仍在agent/tmp staging；表格摘要DuckDB→模型→两类chunk/index/cleanup handler为接下来必须迁移的生产缺口。现有Go及用户前端修改保留，完整Go后端替换目标没有缩小。

## OIDC 与共享 Agent BLOB 正式整合

2026-10-13，完整隔离 `clean verify` exit0：**3,405 tests / 89 classes / 0 failures / 0 errors / 0 skipped**，日志 `/tmp/weknora-java-oidc-full-verify.log`；可执行jar `/tmp/weknora-java-oidc-full-build/backend-java-0.1.0-SNAPSHOT.jar` 为62,628,026字节。来自同次完整报告：security2163、tenant14、knowledge344、model38+client12、docreader180、chunker521、storage73、sourceloc15、task29、retrieval7、system1、bridge8。298个Java主/测试源码package路径正确、无Git忽略；实际jar含OIDC controller及旧许可证/Excel locale资源。正常Go包扫描exit0/no packages，4个新增Go generators均build-ignore。

OIDC 7个生产类、4个精确公开GET AuthFilter/bridge接线、5个JUnit类315项正式合入。Root实际重放原Go state94、config.LoadConfig52、生产SQLite handler149（151次handler/232次providerHTTP）、transport18（42次providerHTTP）。配置重生成与原样本语义一致；state/HTTP按新Go当前clock生成，正式完整Java运行验证新样本，未做跨时原始JSON相等声明。Spring lifecycle/精确方法边界、实际PGobject文本adapter两项包含315内；后者不是PostgreSQL数据库集成。认证/shared KB/bridge等相关33类专项先通过2191项，日志 `/tmp/weknora-java-oidc-checkpoint.log`。重放工具与限制见 `migration/oidc-notes.md`。

共享Agent BLOB最小修复：`AuthRepository.string`用Go UTF-8规则解码JDBC byte[]，保留String/null/其他值路径。1个新方法包含32个实际Go生产GORM Agent读取grant/denial案例；独立旧代码RED（Go200/Java403）保留 `/tmp/weknora-agent-blob-root-red.log`，修复GREEN保留 `/tmp/weknora-agent-blob-root-green.log`，完整3,405包含该方法。原有packaged shared-read smoke也改按Go BLOB seed并检查SQL typeof=blob，未只用TEXT掩盖bug。

当前jar进程检查全部exit0：

- `smoke.py`146次实际后端HTTP，日志 `/tmp/weknora-java-oidc-smoke.log`；另10次System reader/probe HTTP保持独立计数。
- `oidc_smoke.py`26次实际Java后端HTTP、8次真实loopback providerHTTP、1次Go bridge归属探测，日志 `/tmp/weknora-java-oidc-packaged-smoke.log`。真实Go schema SQLite、随机测试凭据、disabled config、所有公开GET、非公开POST、start redirect/cookie、现有账号与新personal workspace、storage snapshot/Owner/preferences、access/refresh落库与HMAC、后续auth/me、无cookie普通回放拒绝、手工捕获有效state/cookie的Go同等回放，以及启用bridge时GET原生/POST留Go均通过。真实Go对照149场景仍使用MockMVC和实际provider网络，此26项才是jar进程检查，二者未混称。
- single/go两种基础上传链均2uploads/4succeeded durable jobs/2actualEmbeddingHTTP，Go BLOB模型参数、Hikari1、vec0/FTS、sharedowner/原actor审计，readiness retries均0，未重试上传。日志 `/tmp/weknora-java-oidc-processing-single.log`、`/tmp/weknora-java-oidc-processing-go.log`。本轮6个Java进程及所有HTTP stub均已停止。

本轮实际后端HTTP合计172（146+26），provider/bridge/parser HTTP分别记，不累加为后端请求。清单生成exit0 `/tmp/weknora-java-oidc-final-inventory.log`：482路由/480注册位置/0未解析，65 in_progress、416 not_started、1 health implemented，无verified。OIDC四路由按有实现但尚有runtime限制记in_progress，全认证/全后端未宣布完成。

Servlet客户端断开到CallContext/JDBC取消、proxy、真实PG、任意RSA/driver异常逐字兼容、全guard/未知角色拒绝审计/shared tenant storage validation仍待完成。表格摘要仍缺正式handler；root已实际验证匹配Go core版本的DuckDB JDBC和10个CSV/多sheet XLSX数据加载/schema/sample/cleanup案例，仅/tmp stage、不计入JUnit/完整上传通过。完整表格任务需两次Chat、两类chunks、索引与清理，并重跑严格Office smoke。图片、完整hybrid/RAG、云存储及其他全部Go逻辑继续迁移，scope保持全主后端。


## 2026-10-13 表格基础与文档并发修复完整里程碑

完整隔离 clean verify exit0，3,444 tests / 90 classes / 0 failures / 0 errors / 0 skipped：`/tmp/weknora-java-datatable-foundation-full-verify.log`。相对上次3,405增加38个表格动态测试与1个文档并发回归；不能把专项52再次相加。301 Java sources（207 main、94 test）package路径正确、无Git忽略。jar `/tmp/weknora-java-datatable-foundation-full-build/backend-java-0.1.0-SNAPSHOT.jar` 为146,449,729字节，包含DuckDB JDBC 1.5.2.0、DuckDbTableSession、DataTableSummaryText、原始prompt和两份上游pinned MIT notice。原有JNI、sqlite-vec与Go/Excelize/NFP notices保留。

实际jar：旧146 backend HTTP通过 `/tmp/weknora-java-datatable-foundation-smoke.log`；OIDC26 backend/8 provider/1 bridge探测通过 `/tmp/weknora-java-datatable-foundation-oidc-smoke.log`。单池和Go六池基础上传各2 uploads/4成功jobs/2次Embedding HTTP，真实Go schema SQLite、BLOB模型、vec0/FTS、Hikari1、共享owner/原actor审计和postprocess均通过，readiness重试0，分别见 `/tmp/weknora-java-datatable-foundation-processing-{single,go}.log`。这些没有使用 --office，不能宣称表格summary已消费。

`DataTableCompatibilityTest`正式10个Go DuckDB加载/schema/sample/cleanup与28个生产Go summary handler prompt/options/原始输出通过。Portable generator独立重放10+37场景，`/tmp/weknora-datatable-generator-final-check.log`；修正软删除IDs投影与Go cleanup-index-error留下metadata但向量已删除后，再以更新golden跑正式38个动态测试，`/tmp/weknora-java-datatable-updated-oracle-test.log` exit0。原始Go map去重引入embedding batch随机顺序，必须验证source/content→captured input的向量关联，不能忽略错误关联。

隔离/tmp full summary repository/handler与standalone probe随后37个Go生产场景通过完整chunks字段/links/status、知识状态、soft delete、index metadata/向量存在与float32关联、完整Chat请求/embedding input集合、HTTP数量及业务错误。`/tmp/weknora-datatable-full-stage-deep-flow-fixed.log`。此前深比较发现table ChunkStatusStored实际是1（document原有创建默认0），已仅在stage修正；失败日志 `/tmp/weknora-datatable-full-stage-deep-flow.log` 保留。这个37是standalone scope，不计3,444，不是Spring生产接线：model missing的原有大小写与file-missing安全诊断差异仍明确单列；生产任务model SPI、typed tenant/scalar异常、moving知识校验、引擎完整选择、共享DuckDB生命周期、SQL deadline/cancel、Hikari1的表格并发和严格Office7 jobs仍待完成。

清单重生成保持482 routes /480sites /0 unresolved，65 in_progress、416 not_started、1health implemented，未标记任何route verified。旧严格Office失败证据继续保留；当前完整Go主后端迁移没有完成。

## 2026-10-14：索引、构建记录、表格与文档摘要的原生稳定批次

本批完整 `clean verify` **103 类、4,845 项、failures/errors/skipped 全部 0**，exit 0；日志 `/tmp/weknora-java-full-migration-final.log`，JUnit XML 与可执行 jar 在 `/tmp/weknora-java-full-migration-final-build/`。该结果属于摘要稳定 checkpoint；随后 agent 独立新增的问题生成代码不算已经由此 jar 验证。之前 4,843 项的全量运行有 1 项 error（跨测试残留 summary_model_id），已修测试隔离后重新 clean verify；未把失败运行或重复专项累加。

- 索引读取真实 Go 对照：160 HTTP + 964 状态样本（另 1 fixture 防护）+10 MVC+2 deadline。构建记录：130 Go HTTP、9 Go SQL、原生 tracker 与文档/后处理接线。原生 summary 最终专项 63 项：44 真实 Go worker 样本（43 严格相等 + 1 明示取消安全差异）、6 Spring、11 trace、2 显式 schema/无构造器 DDL。summary seq_id 严格覆盖 SQLite 外租户/软删最大值，PG 仍走 schema sequence。
- PostgreSQL 实际生产 Go/Java repository 差分：16 组变更、2,368 次查询，半精度向量/完整字段/排序/分数/SQLSTATE；Hikari1、一次性 ParadeDB。`scripts/postgres-index-parity.py` 可复现，日志 `/tmp/weknora-java-postgres-isolated-replay.log`。
- PostgreSQL 原生流程：全 115 个 Go up migrations + 显式 Java task schema、bridge=false、Hikari1；4 次 owner/共享上传、10 成功任务、6 Embedding HTTP、2 Chat HTTP，真实 halfvec、summary/profile JSONB、counter、审计原 actor、spans/stages/index-status HTTP。显式 receipt schema 后再次通过，`scripts/postgres-processing-smoke.py`，日志 `/tmp/weknora-java-postgres-final-processing.log`。这是 Java 原生成功流程验收，不等于整个 Go 文档处理器的实库差分。
- 最终 jar 的 Office：single 与 go 六池各 3 上传、7 成功任务、4 Embedding HTTP、2 Chat HTTP、1 受控 HTTP reader；严格核对格式/定位、table summary/column 的内容、parent/prev/next、status 与实际索引。日志 `/tmp/weknora-java-final-office-{single,go}.log`。
- 最终 jar 的文档摘要：single 与 go 六池各 2 owner/共享上传、6 成功任务、4 Embedding HTTP、2 Chat HTTP；真实 description/profile、summary chunk/index、终端 counter 和 summary span。日志 `/tmp/weknora-java-final-summary-{single,go}.log`。每次新库、bridge=false、Hikari1；没有绕过 dead task 或用历史数据充当新构建。
- 表格先完成被初次文档清理删除的问题在 Go 与 Java 两端修正。Go 重解析原有入队前同步清理仍删除旧 attempt。Go 的实际 repository 清理/重解析回归以及受影响 repository/service/handler 测试通过，日志 `/tmp/weknora-go-full-migration-check.log`。
- 复现过当前 macOS arm64 下 DuckDB JNI 运行中加载导致 SQLite NOMEM/native crash；入口现在在 Spring/池/worker 前初始化 DuckDB，vec0 路径保留保障。上述最终 jar 的两种 Office 拓扑都正常完成，不声称所有平台或加载路径已验证。
- Docker build **未通过**：Docker Hub 的 Maven/Temurin metadata HEAD 超时，尚未到代码编译。日志 `/tmp/weknora-java-docker-build.log`。没有改镜像源或把宿主机 jar 运行当 Docker 验收。

迁移台账仍为 482 路由、69 in_progress、412 not_started、1 health implemented、0 verified。4/22 Asynq 业务类型原生接线但未 verified；全部同步 handler 与 Redis 存量消费/排空切换未完成。其余问题/graph/Wiki/auto-tag/profile、公开文档写入、检索/RAG、会话/SSE/Agent/扩展模块与完整运维仍待迁移。更新后的逐项边界见 `migration/go-java-feature-comparison.md`。

所有 smoke 自己启动的服务器均已结束，临时数据库/日志保留供复核；实库脚本只清理自己创建的容器，未重启或切换项目现有服务、修改生产数据。

### 2026-10-15 — 摘要模型调用期间取消的严格对齐

真实 Go oracle 暴露的取消后继续发布结果已修复：`summarySourceChanged` 在模型调用后识别 cancelled/deleting，保留原有摘要和索引。重新执行真实 Go fixture generator，44 个样本全部严格比较 HTTP 和 SQL，未排除取消样本。Java 对业务取消 ACK，但保留失租约、任务取消和中断失败。四类专项 63 tests、0 failures/errors/skips，日志 `/tmp/weknora-java-summary-cancel-alignment.log`；Go 专项也通过 `/tmp/weknora-go-summary-cancel-test.log`。原始不安全基准保留 `/tmp/weknora-go-summary-generation-before-cancel-fix.json`。上述旧完整构建和打包验收早于本修复，本条仅记录专项验证。

### 2026-10-15 — 原生取消入口与 PostgreSQL 增强流程

取消解析 54 个真实 Go service 场景严格对照 SQL/tracker/audit/pending-op 副作用；8 项真实 Spring HTTP/Hikari1 集成覆盖共享权限、API ingest/KB scope、模型调用期间取消、保留索引、幂等和 507 个问题任务跨页取消。62 tests、0 failures/errors/skips，`/tmp/weknora-java-cancel-parse-final-test.log`。早期两个 HTTP 测试误期望已修正：scoped API key 对共享 KB 仍受 Go viewer cap；API key 存在 tenant 用户时保留实际用户作为 audit actor，原生行为未放宽。

新的隔离 PG runner 应用全部 115 个 Go migrations，Hikari1、bridge=false。6 次成功上传包含普通、摘要、摘要+问题 × own/shared，2 次上传在实际模型 Chat 调用期间 HTTP 取消，共 24 jobs/22 succeeded/2 canceled、14 embedding HTTP、8 Chat HTTP；真实 halfvec、JSONB profile/questions、counter、Wiki ingest 删除/retract 保留、原始 actor audit 通过。日志 `/tmp/weknora-java-postgres-enrichment-final.log`，artifact `/var/folders/pf/6ssfy5pj7fj9w1jyg4kpbmcr0000gn/T/weknora-postgres-processing-lkdmhf1y`。首轮测试误期望已经 done 的 parse root 改为 cancelled；Go 源码明确 postprocess 分发即结束 root，cancel 仅关闭开放子 span，已按此修正测试并重跑通过，未修改该业务语义。隔离容器已停止。此结果属于 Java 实库集成，非全 Go 流水线差分。

当前路由台账 482 条：70 in_progress、411 not_started、1 health implemented、0 verified。5/22 Asynq 业务类型具备原生 handler 接线，所有同步执行器和原有 Redis 队列切换仍待迁移。新 full clean verify 与最终 jar smoke 尚未完成，不能使用旧 4,845 测试的 jar 证明本增量。


## 2026-10-15 最新稳定全量与八次打包验收

同一次隔离 `clean verify` exit0：**5,049 tests / 109 classes / 0 failures / 0 errors / 0 skipped**；日志 `/tmp/weknora-java-full-enrichment.log`，报告和最终 jar 在 `/tmp/weknora-java-full-enrichment-build/`。包含最终摘要取消修复、114 个真实 Go 问题 worker、17 个真实 Go Post 分发样本、9 个 Spring 问题链、2 个 Schema、62 个取消解析测试；专项/历史数据没有重复相加。取消 HTTP 测试的模型服务器现在由自己的 BeforeEach/AfterEach 管理，避免依赖已关闭的其他测试 fixture。

该同一 jar 的 `scripts/processing_smoke.py` 在 `--topology single` / `--topology go` 上各跑四种流程，均 exit0：

- Office：每次 3 上传、7 成功任务、4 embed / 2 chat / 1 reader。
- 摘要：每次 2 上传、6 成功任务、4 embed / 2 chat。
- 摘要＋问题：每次 2 上传、8 成功任务、6 embed / 4 chat；每片段 3 个问题及真实问题索引。
- 取消：每次 2 上传、6 任务（4 成功、2 摘要取消）、2 embed / 2 chat；模型 HTTP 被阻塞后调用真实 cancel-parse，保留原片段/索引，summary none、counter0、无摘要片段。Go Post 在分发时已经完成根 span，因此保留 done 根，仅关闭开放子 span。

日志为 `/tmp/weknora-java-enrichment-{office,summary,question,cancel}-{single,go}.log`，各日志提供独立数据库与服务器日志目录。全部 bridge false / Hikari1；涵盖 owner/shared 与原 actor 审计，测试管理的服务器均 finally 停止。

Go 受影响 repository/service/handler 测试通过 `/tmp/weknora-go-full-enrichment-final.log`。真实 PostgreSQL 8 上传/24 任务专项另见上一节，不能混入 Surefire 数量。

Docker 构建基础镜像 metadata HEAD 遭 DockerHub registry:443 connection refused，未进入编译，`/tmp/weknora-java-enrichment-docker-build.log`；Linux 镜像未验证。台账仍 70 in_progress / 411 not_started / 1 implemented / 0 verified（482 路由），5/22 Asynq 任务接线；全同步模式、Redis 存量队列切换及其余 Go 功能未完成。后续 graph/auto-tag 新代码不属于本稳定 checkpoint。


## 2026-10-15 图谱与自动标签稳定构建及十二次打包验收

同一次隔离 `clean verify` exit0：**5,657 tests / 116 classes / 0 failures / 0 errors / 0 skipped**。日志 `/tmp/weknora-java-graph-auto-tag-final.log`，报告和 immutable jar `/tmp/weknora-java-graph-auto-tag-final-build/`。包含自动标签 52 Go service SQL/HTTP＋8 Go Post 分发＋6 Spring pipeline，以及图谱 18 Go 分发、521 production Go 数字解析、2 schema、1 关闭图谱的启动验证。真实 Neo4j 图谱 worker 41 与 Spring 联合链 8 为显式 IT，已独立通过，不混入默认 Surefire 数量。

同一 jar 的 Office、摘要、摘要＋问题、取消、自动标签、图谱共六种流程，single/go 两配置共 **12 次**均 exit0。前四种计数与前批一致；自动标签各 2 上传/10 成功任务/6 embedding/6 chat，精确验证现有标签关系追加、无独立计数槽；图谱各 2 上传/10 成功任务/6 embedding/6 chat，真实 APOC 每文档 2 节点/1 关系、5 物理索引、共享权限/owner/原 actor、公共 counts/details/spans/stages、counter0。全部 bridge=false、Hikari1，子服务器 finally 结束；仅清理自有随机 Neo4j 文档 namespace。

日志 `/tmp/weknora-java-final-{office,summary,question,cancel,auto-tag}-{single,go}.log` 和 `/tmp/weknora-java-graph-live-{single,go}.log`。前一次 full `/tmp/weknora-java-graph-auto-tag-full.log` 5136 项有 1 个失败：编译的旧 disabled-startup 测试误访问需鉴权的 `/actuator/health`；修为生产 `/health`/ok 后，另合入实际 Go 验证的 numeric formatter 并 fresh clean verify 全绿，保留失败记录。

独立 PostgreSQL 自动标签增强流水线：全部 115 Go migrations，10 上传/34 jobs/32 succeeded/2 canceled、20 embedding/14 chat、真实 halfvec/JSONB/profile/问题/tag relation/审计/counter/HTTP 取消通过；`/tmp/weknora-java-postgres-auto-tag-final.log`，artifact `/var/folders/pf/6ssfy5pj7fj9w1jyg4kpbmcr0000gn/T/weknora-postgres-processing-wu6gxk7z`。先前轮次仅测试 SQL 对 TEXT payload 误用 JSON operator，改为 payload::jsonb 后重跑通过，未改业务查询。

图谱 PostgreSQL＋Neo4j 联合专项独立记录 `/tmp/weknora-java-postgres-graph-test.log`，不是默认全量测试或该 jar packaged 数据。后续 kb:profile、图谱搜索/清理与 prompt 新增代码不属于上述 immutable checkpoint。Linux Docker 构建和完整生产切换尚未通过；482 路由仍 70 in_progress/411 not_started/1 health implemented/0 verified，7/22 原生任务稳定验收，所有同步模式和 Redis 存量队列切换待迁移。


## 2026-10-15 知识库简介与图谱边界稳定批次

新的隔离 `clean verify` **真实 exit0**：121 classes、5,757 tests，failures/errors/skipped 全为0。日志 `/tmp/weknora-java-profile-graph-verified.log`；immutable jar `/tmp/weknora-java-profile-graph-verified-build/backend-java-0.1.0-SNAPSHOT.jar`，152,473,581 bytes。根独立汇总同一报告，未将显式 Neo4j/PostgreSQL IT 或历史专项相加。

知识库简介100项/5类：52真实Go SQL/模型HTTP、23真实Go公开handler/权限/语言HTTP、13分发（其中12真实Asynq）、10实际Spring/Hikari1/任务/真实APIkey链、2Go配置模板。包括自动触发、30秒延迟、配置启用后的after-commit任务、强制同步共享生成、owner变化/取消/失租约拒写与错误体。Go模型调用期间owner改变仍写入peer KB的缺口已修正，原unsafe回放保留。Go默认配置模板与inline fallback不同，已按实际LoadConfig结果接入简介模板。

图谱本批专项628项/11类，含42真实Go worker（自定义prompt百分号）、18真实分发、521数字属性、10生产Spring链、SearchNode/DelGraph/属性8、schema2/关闭启动1及相关问题测试；显式实库IT单独记录。实际Neo4j/APOC证明文档label冲突的精确kg隔离、确定性关系顺序、缺失属性、跨文档关系DETACH删除、200/2000结果边界及重解析历史graph清理。Go旧unsafe结果保留，修正后再采集严格基准。

**同一新jar共14次 packaged验收均实际exit0**，single/go × Office、摘要、摘要＋问题、模型HTTP期间取消、自动标签、Neo4j图谱、简介＋摘要＋问题＋标签组合。每次新库、bridge=false、Hikari1，实际own/shared multipart、原caller审计、模型HTTP、physical vec/FTS、counter/span/receipt和生产worker。简介组合每次2uploads/11jobs/6embedding/9chat（含1自动简介与2同步强制own/shared生成）；同步生成不新增任务。简介首次脚本错误地断言任务完成后的available_at仍等于入队时间＋30秒；settle按设计刷新该字段。改为捕获pending时精确30秒并核对first attempt不早于30秒，未修改任务生产行为，复跑通过。原失败日志`/tmp/weknora-java-profile-package-kb-profile-{single,go}.log`保留，成功日志`...-retest.log`。

其余成功日志：`/tmp/weknora-java-profile-package-{question,auto-tag,cancel,office}-{single,go}.log`、`/tmp/weknora-java-profile-package-summary-{single,go}-retest.log`、`/tmp/weknora-java-profile-graph-package-{single,go}.log`。首次完整构建仅1error，测试list读取了其他测试刻意损坏的task JSON；测试改为明确选本fixture tenant/type/pending ID，再完整clean verify通过；原日志`/tmp/weknora-java-profile-graph-final-verify.log`保留。

PostgreSQL增强流水线实际exit0：全115Go migrations/显式Java task schema/Hikari1/bridge=false，10uploads/35jobs（33succeeded/2canceled）/20embedding/17chat，真实halfvec/JSONB、tags/profile、自动＋同步简介、共享配置启用、取消和Wiki清理；日志`/tmp/weknora-java-postgres-profile-graph-final.log`，artifact`/var/folders/pf/6ssfy5pj7fj9w1jyg4kpbmcr0000gn/T/weknora-postgres-processing-523htxze`。图谱PG＋Neo4j另1实库IT零失败错误跳过，5uploads/19jobs/9embedding/9chat；日志`/tmp/weknora-java-postgres-graph-search-cleanup-test.log`。受影响Go repository/service/handler suite以`-tags sqlite_fts5`实际exit0，日志`/tmp/weknora-go-profile-graph-fts5-final.log`；缺少FTS5构建tag的前次失败保留，不计成功。

482路由：71 in_progress、410 not_started、1health implemented、0verified；8/22异步业务类型原生验收。完整公开RAG/会话/SSE/Agent、其他任务与同步执行器、Redis backlog排空、所有存储、Linux/Docker/压测/生产切换仍未完成。下一批Graph RAG与index:delete不属于此immutable jar。


## 2026-10-15 index:delete 下一批专项与候选包

新增默认SQLite/PG清理worker与admitted producer helper；public tag CRUD/删除入口仍未迁移。正式41tests/3classes，失败/错误/跳过全0、实际exit0，日志`/tmp/weknora-java-index-delete-final-test.log`。包含27真实Go production worker/SQLite metadata+vec0+FTS严格差分、1租约/取消、3生产Spring/Hikari1共享上传→模型→索引→清理、9真实GoFAQ/tag删除→Asynq生产者信封及1原生owner/snapshot/freshID。Go worker曾跨owner/KB删除索引，现用包含软删除记录的owner/KB绑定限制；真实原unsafe BLOB oracle保留`/tmp/weknora-go-index-delete-original-blob.json`。Java普通embedding factory仍保持类型校验，cleanup-only入口按Go允许旧model type，但执行不发送embeddingHTTP。

先前31-test `clean package`候选成功，`/tmp/weknora-java-index-delete-candidate-build/backend-java-0.1.0-SNAPSHOT.jar`152,516,038bytes；同一candidate的single/go cleanup packaged验收均实际exit0，每次2owner/shared uploads、7succeeded jobs、2embedding、0chat；wrongKB任务无删除，随后软删除chunk清理全部physical metadata/vec/FTS，storage/counter保持，原actor审计存在。日志`/tmp/weknora-java-index-delete-package-{single,go}.log`。该candidate只有31专项，没有声称全量verify，亦尚未包含新producer helper。

更新的PG原生流水线实际exit0：115Go migrations/Hikari1/bridgefalse，10uploads/38jobs（36succeeded/2canceled）/20embedding/17chat。新增3cleanup jobs分别拒绝wrongKB并清理soft-deleted own/shared chunk的actualhalfvec；无额外modelHTTP或storage/counter变化。独立XML1test/zero failure-error-skip，日志`/tmp/weknora-java-postgres-index-delete-pipeline.log`、artifact`weknora-postgres-processing-8kwowizb`。此前5757-test稳定jar不包含这批；下一wholecleanverify待GraphQuery联合批次收口。

## 索引删除与图谱查询联合稳定构建（2026-10-15）

新的独立 `clean verify` 实际 exit0：**124 classes / 5,798 tests / zero failures/errors/skips**，`/tmp/weknora-java-index-rag-verified.log`；XML 由根 agent 独立汇总。不可变 jar `/tmp/weknora-java-index-rag-verified-build/backend-java-0.1.0-SNAPSHOT.jar`，152,526,858 bytes，SHA256 `89108a0433cd30df3cae6cde1650e29cb89652cf35ad2355fde5a3b10ac5be96`。新增41个IndexDelete测试属于本次完整报告；显式Neo4j/PG IT与历史专项未相加。

同一 jar **16 次包运行全部实际 exit0**：question、auto-tag、summary、cancel、office、kb-profile、index-delete各single/go，加真实Graph single/go。根14项进程返回码清单 `/tmp/weknora-java-index-rag-package-manifest.json`；对应 `/tmp/weknora-java-index-rag-package-{mode}-{single,go}.log`。Graph `/tmp/weknora-java-index-rag-graph-package-{single,go}.log`。全部隔离临时DB/Hikari1/bridge=false、真实共享与owner/原actor；索引删除每轮2上传/7成功任务/2Embedding/0Chat，包括3个cleanup job。索引删除包测试的队列行是隔离SQL fixture；完整公开Tag生产者属于后续批次。

GraphQuery显式专项实际exit0，5类647项0failure/error/skip，`/tmp/weknora-java-graph-query-final-test.log`。55真实Go查询严格比较modelHTTP、真实SQL BLOB storage class、完整hydrated DTO及Neo4j；其余包含7生产Bean链、63既有读取授权、1disabledstartup、521Go数字formatter。公共会话/SSE与PG查询回填未被这些内部证据验收。Go并行主链旧构造器漏接share service已修，16真实Go parallel正负案例、affected/full suite均actualexit0，最终 `/tmp/weknora-go-full-graph-query-parallel-final.log`。

下一批Tag四路由已实现基础原生CRUD，76真实Go HTTP/SQL/Asynq样本逐项通过；联合149/3类实际exit0 `/tmp/weknora-java-tag-second-test.log`。首轮仅compile失败（跨包访问package-private数字helper），随后改Tag本地helper；保留 `/tmp/weknora-java-tag-first-test.log`。生产AuthFilter/JWT/API key/公开删除到worker物理链另行补验。文档型标签的knowledge-list删除仍明确未迁移，Tag不在上述5798-test jar中。

Tag补验 `/tmp/weknora-java-tag-pipeline-test.log` **actual exit0，154 tests/4 classes/zero failure/error/skip**（76+10+63既有差分/回归，加5实际认证/Hikari1生产链）。实际JWT/API key的权限、scope、own creator Viewer/noncreator/shared Viewer/full key/revocation，以及HTTP Tag CRUD→owner low task→worker物理清理通过。清理案例的FAQ chunks来自实际已索引文档加隔离SQL夹具，不包含FAQ导入迁移；document标签知识列表删除仍明确未迁移。详见 `knowledge/tag-migration-notes.md`。

Tag PostgreSQL 严格对照通过：115实际Go migrations，真实Go76公开handler/SQL/Asynq样本全部导出，Native76/1 IT/Hikari1实际exit0，零fail/error/skip。`/tmp/weknora-java-postgres-tag-second.log`、artifact `weknora-postgres-tag-9nflqnk2`；本次代码不在5798稳定jar。首轮15 DTO差异由种子无offset时间文字在JDBC连接session timezone下解释造成，只修fixture typed UTC Timestamp，未修改生产时间转换；原始 `qsre7jw7` 日志保留。


## 2026-10-15 文档列表删除及公开标签审计对齐（后续批次）

`/tmp/weknora-java-knowledge-delete-audit-final.log` actual exit0：**167 tests / 6 classes / 0 failures / 0 errors / 0 skipped**。含36实际Go清理worker及资源目录场景、1原生租约fence、88公开Tag HTTP/SQL/Asynq/business audit场景、8真实JWT/API key/Spring/Hikari1流水线、28索引删除及6存储回归。共享图片仍有引用则保留，最后引用释放才删字节；存储读取兼容Go持久化BLOB配置。

PostgreSQL公开Tag两份严格对照各actual exit0：76+12场景，全115Go migrations、Hikari1，完整业务审计、响应、SQL与入队契约。日志 `/tmp/weknora-java-postgres-tag-{public,documents}-audit-second.log`；报告目录 `weknora-postgres-tag-1kxvoaw2` / `weknora-postgres-tag-le7sxtb9`，XML独立核实76/12测试零失败错误跳过。前轮fixture把PGobject转成对象导致audit.details为null，修为CAST(details AS TEXT)，生产SQL未改。实际Go另揭示同步Tag审计保留synthetic actor、异步TaskInitiator去掉synthetic user/role但保留key ID/name；两处现分别对齐。API key有home user时仍使用原Go最早用户行为，新增Spring场景特意构造无home user fallback。

PostgreSQL真实处理链 `/tmp/weknora-java-postgres-document-delete.log` actual exit0：10上传/40任务（38成功2取消）/20Embedding/17Chat，公开own/shared文档Tag删除移除物理halfvec/源文件、调整quota并保留initiator审计。runner运行时已加载旧打印值38，编译IT明确断言40和新增2删除任务；保留原日志，不把console旧值当失败或重复重跑。

早期164-test候选包SHA256 dd6e39ef3d0e43581c01c62ee6556ca1b3705946f717fd49b8320a233bbc4e4c 两拓扑文档删除均actual exit0，各2上传/6任务/2Embedding/0Chat；日志 `/tmp/weknora-java-document-delete-candidate-{single,go}.log`。该包早于2资源oracle与最终audit/fallback变更，不是新的完整构建证明。后续原生第10个handler knowledge:list_delete、Tag和进行中的Hybrid尚未纳入5,798-test immutable checkpoint。完整公开删除API、Wiki/云/绑定引擎清理、并发重启和全Go替换继续进行。


## 2026-10-15 公开 Hybrid / Tag / 文档清理统一稳定构建

Fresh clean verify actual exit0：**6,371 tests / 132 classes / 0 failures / 0 errors / 0 skipped**，XML独立核实。日志 `/tmp/weknora-java-public-search-delete-verified.log`；immutable jar `/tmp/weknora-java-public-search-delete-verified-build/backend-java-0.1.0-SNAPSHOT.jar`，152634621字节，SHA256 `0c4f3f8a84ee6a7b014898ef54a6c3d98597a3f8260f00797de23566095fd00a`。673个冻结main/test/pom文件贯穿打包验收hash未变化。

同jar **20次隔离进程验收全部actual exit0**：18次任务流水线（single/go×Office、摘要、问题、取消、自动标签、图谱、知识库简介、索引删除、公开文档标签删除）；另2次同步Hybrid HTTP验收（tasks=false，不计作worker执行）。Root16返回码manifest `/tmp/weknora-java-public-search-delete-package-manifest.json`，Graph2日志 `/tmp/weknora-java-public-search-delete-graph-package-{single,go}.log`；Hybrid2 `/tmp/weknora-java-public-search-delete-hybrid-package-{single,go}.log`各11HTTP/5model/4physicalentries。文档删除各2上传/6成功任务/2Embedding/0Chat，Graph各2上传/10任务/6Embedding/6Chat，真实Neo4j/APOC。均临时自有SQL/文件目录、Hikari1、bridge=false，共享授权与原actor审计实际核验。

143实际Go HTTP/SQL/model/vec/FTS Hybrid场景、143 PostgreSQL（全部115migrations）、288精确FMA rawbits、6认证链和2真实SQL deadline/cancel已有证据。影响Go及全项目 `go test -tags sqlite_fts5 ./...` actualexit0 `/tmp/weknora-go-full-public-hybrid-final.log`。保留原unsafe绑定、context/多owner无序及隐式FMA的before oracle；通过真实Go精确修复重导，未数组归一化或浮点epsilon。当前多store/owner查询顺序执行与Go并发调度仍是已确认缺口，下一切片将继续；当前checkpoint不宣告整个Hybrid完整等价。

台账482路由/77in_progress/404not_started/1health implemented/0verified；10/22原生Asynq handler基础。完整公开删除、Wiki/云/绑定资源清理、其余12任务、会话/chat/SSE/RAG/Agent、同步/Redis存量切换与整个Go后端迁移仍未完成。


## 2026-10-15 公开文档删除与移动状态边界（下一稳定批次）

实际 Go 导出123公开 single/batch/clear HTTP/SQL/Asynq场景及46 worker场景，Native专项378/9类全部actual exit0、失败/错误/跳过0，日志 `/tmp/weknora-java-public-delete-scope.log`。包含3实际Spring JWT/API key/Hikari1生产链，真正删除源文件、physical vec/FTS、quota、审核原actor/任务ID；移动判断要求operation=move且phase=moving，并对齐raw metadata的大小写、重复键、null和typed错误。API fullAccess仍遵循KB allow-list，allow-list lookup裁Go空白，SQL保留原始KB ID。

PostgreSQL123严格对照actual exit0，XML独立核实123/0失败错误跳过；全115 Go migrations/Hikari1，日志 `/tmp/weknora-java-postgres-public-delete-scope-second.log`，artifact `weknora-postgres-delete-fs66j_v8`。前轮真实NUL ID触发SQLSTATE22021，Go500而Native503，修复只作用于batch路径的真实22021，不复制任意驱动错误。失败日志 `/tmp/weknora-java-postgres-public-delete-scope.log` 与artifact `swkpvri1` 保留。

扩展PG生产处理链actual exit0，独立XML1/0失败错误跳过；日志 `/tmp/weknora-java-postgres-public-delete-pipeline.log`，artifact `weknora-postgres-processing-w0ije67t`，10上传/42任务（40成功2取消）/20Embedding/17Chat。新增公开single/shared batch→worker验证实际源字节、quota、tombstone、任务与审计identity；这两个文档的physical indexes已被前面的index-delete阶段清理，因此不把这两任务另外宣称halfvec删除证据。此前公开document-tag删除已独立证明halfvec清理。PG clear新增为空清理；非空clear的物理清理由SQLite Spring证明。

本批尚不属于前面的6,371-test不可变jar，下一完整构建及同jar打包验收待Hybrid并发切片稳定后执行。当前482路由/80in_progress/401not_started/1health/0verified。完整Wiki/云/绑定存储、异常并发/重启、剩余任务/路由、会话/SSE/Agent与切换继续迁移。


## 2026-10-15 公开删除与 Hybrid 并发完整构建及同jar验收

同一冻结源码的 fresh `clean verify` **actual exit0**：135 classes / 6,511 tests / 0 failures / 0 errors / 0 skipped，独立XML核实；日志 `/tmp/weknora-java-public-delete-concurrency-verified-second.log`。jar `/tmp/weknora-java-public-delete-concurrency-verified-build/backend-java-0.1.0-SNAPSHOT.jar`，152658608 bytes，SHA256 `e30e96ea33105cf4492aafe5aa3bc42c919fdc80d7e6579958999b168af83cdd`。686个main/test/pom非md文件hash在构建后核实不变。22个最终同jar进程验收全部actual exit0：20任务流水线（single/go×既有9模式加公开文档删除，共10模式）和2同步Hybrid HTTP（tasks=false）。最终manifest `/tmp/weknora-java-public-delete-concurrency-package-final-manifest.json` 保留首轮root17pass/1fail及复跑记录。公开删除各2上传/6任务/2Embedding/0Chat，包含actual源文件/vecFTS/quota/原actor/taskID及空clear无额外任务；非空clear另由Spring证明。Graph2与Hybrid2日志 `/tmp/weknora-java-public-delete-concurrency-{graph,hybrid}-package-{single,go}.log`。

首跑命令漏传明确的sqlite-vec/Jieba参数，引起native fixture初始化失败；仅终止自有误配置Maven/Surefire（actual143），原日志 `/tmp/weknora-java-public-delete-concurrency-verified.log` 及partial reports `/tmp/weknora-java-public-delete-concurrency-missing-native-paths-build`保留。主代码/测试断言未改；补齐以下真实本地库参数后同冻结源clean verify通过：`weknora.sqlite.vec.extension=/tmp/weknora-sqlite-vec-native/vec0.dylib`，`weknora.jieba.library=/tmp/weknora-jieba-native/libweknora_jieba.dylib`，`weknora.jieba.dict=/tmp/weknora-jieba-native/dict`。

Hybrid新slice专项509/8类actual0：143HTTP、288rawbits、6生产Spring、3fanout、3真实deadline/borrow、2基础方法、1metadata方法、63既有ReadAuth回归。PG143最新普通对照actual0，全115migrations/Hikari1，日志 `/tmp/weknora-java-postgres-hybrid-concurrent-current143.log`，artifact `weknora-postgres-hybrid-nxy72p03`，根独立XML143/全0。真实Go blocked6场景与Native Hikari4/2/1的18次原始完整响应/provider/SQL对照actual0，日志 `/tmp/weknora-java-postgres-hybrid-blocked-final6.log`，artifact `weknora-postgres-hybrid-u7xl_v3r`，根独立XML三个6测试/全0。验证实际4组并发、first-failure兄弟取消、pool borrow预算及取消后相同backend PID可复用；single组caller deadline/cancel保留Go500，multi组retrieval失败400/code2201。Servlet断连传播、bound/external engines与完整会话/RAG/SSE仍未被这些证据验收。

本轮root首批18模式中kb-profile/go在首次获取数据库连接时Hikari total0/2s超时，actual1，原 `/tmp/weknora-java-public-delete-concurrency-package-kb-profile-go.log` 与自有server log保留。相同jar/参数单独复跑actual0，日志 `/tmp/weknora-java-public-delete-concurrency-package-kb-profile-go-second.log`，2uploads/11jobs/6Embedding/9Chat；根因未确认，未修改断言/runner/业务代码，也未宣称已修复启动问题。最终686源码文件hash及jarhash再次独立核实不变。482路由仍80in_progress/401not_started/1health/0verified，全Go迁移与生产切换未完成。下一chunks删除/修订117实际Go样本仍在隔离stage，不属于本jar/新迁移路由。

2026-10-15 后续 chunk lifecycle（三路由，位于 6511 稳定 jar 之外）：117 实际 Go SQLite handler/service/middleware/SQL oracle 全通过，正式导出与 staged 实测一致；3 production Spring JWT/API/Hikari1 对实际上传后 chunk 删除及修订列表验证物理 sqlite-vec、FTS、源文件字节、quota 和任务数保留，全通过。PostgreSQL 115 Go migrations + Hikari1 实际 Go/Java 117 场景全通过，actual exit 0，独立 XML117/0failure/error/skip；/tmp/weknora-java-postgres-chunk-third.log，artifact hzpdt9qh。第一次 PG114通过/3 diagnostic差异失败 qoopgz7k 和第二次 runtime依赖scope编译失败 uzw3u7hg 均保留；生产改为 typed PSQLException server severity/message/SQLSTATE，PG驱动compile scope且版本不变。PG物理GIN/tsvector仅是 retention sentinel，不代表全部ParadeDB/图谱生产索引已验收。首次fresh402专项396通过/6Spring error因测试编译早于agent的ManualConfiguration落盘，agent接线完整后复跑中；尚未宣称fresh402/full/package通过。清单现83in_progress/398not_started/1health/0verified。

Chunk 后续 fresh clean 专项402项/8类实际exit0，failure/error/skip为0，独立XML合计已核对；/tmp/weknora-java-chunk-lifecycle-focused-second.log，build /tmp/weknora-java-chunk-lifecycle-focused-build。包含117chunk+3chunkSpring、123公开文档删除+47worker+3Spring、38write+63read+8role回归。首次并发接线中396pass/6Springstartup error保留，未放松断言。使用固定702源码快照另打chunk候选包，不替代6511稳定jar/全量验收。

Chunk fixed702源码+实际proto候选包（skipTests，仅候选，不替代full）：actual package0，jar152691156bytes SHA2564e3597cdab5dcd0ba7d1842f86249b2f88ae2ccefdd225ff7f43debc586f1ead；single/go --chunk-lifecycle各actual0，2上传/4任务/2embedding/0chat/0cleanupjob，HTTP修订和单个/共享批量软删除保留实际vec/FTS/源文件/quota。证明 /tmp/weknora-java-chunk-candidate-source-proof.json；初次isolated copy漏../docreader/proto编译失败已保留、补入未改动实际proto源码后重新打包。当前共享batch仍待Manual worker完成后的fresh full verify。

Chunk lifecycle production PostgreSQL replay: `/tmp/weknora-java-postgres-chunk-pipeline.log`, actual exit 0; independent XML one test / zero failures/errors/skips at `weknora-postgres-processing-z8y09pz3`. Full 115 Go migrations, Hikari one, actual production halfvec and ParadeDB BM25 query results retained by public own creator-Viewer single/shared-Editor bulk tombstones and revision reads; subsequent scoped index cleanup passed. 10 uploads / 42 jobs / 40 succeeded / 2 canceled / 20 embedding HTTP / 17 chat HTTP. This is additional live-store evidence for the chunk slice, outside the immutable 6,511-test checkpoint.

Folder API slice: fresh clean 263 tests / 5 classes, zero failures/errors/skips and actual exit 0 (`/tmp/weknora-java-folder-focused-fourth.log`); 151 actual Go SQLite folder HTTP/SQL/denial-audit cases plus 3 production auth/source/physical-vector tests. Separately exported PG151 on all 115 Go migrations/Hikari one passes (`weknora-postgres-folder-yo0e4toz`), and full production PG 10-upload/42-task pipeline now covers own/shared tree, creator-Viewer rename, batch refile, production BM25/halfvec/source/quota/queue retention (`weknora-postgres-processing-m1b5up13`). Immutable 1,060-file candidate jar SHA256 `3e3e4b4f1c34f520744b0802db129e2e335396ddd4d9a613194ac62e254ff133` passes folder single/go, 2 uploads/4 jobs/2 embedding each, actual exit 0. This skipTests candidate is not a fresh shared full verification. Full failures, source proof and boundaries: `src/main/java/com/tencent/weknora/knowledge/folder-migration-notes.md`.

Manual incremental independent root XML check: `/tmp/weknora-java-manual-first143-final-build/surefire-reports`, two classes / 143 tests (98 public + 45 worker), zero failures/errors/skips; agent reported actual exit 0. Separate live PG public98 (`weknora-postgres-manual-oqvx3w9c`) independently read XML98/0 and actual launcher success. Manual routes/task remain in_progress; image/VLM/resources, production worker chains and full fresh build remain pending. Earlier 137-test build was reused by the agent; its overwritten XML is not counted here.


Document PUT / metadata refresh incremental slice, outside immutable6511: actual Go SQLite112 exported exit0 `/tmp/weknora-go-update-expanded112-third.log`; original79 unchanged except validated dynamic clock. Latest focused115/2 actualexit0 and independent XML all zero `/tmp/weknora-java-update-pipeline-third.log` (112 Go differential +3 production Spring JWT/API/Hikari1 upload/refresh/queue failure). Existing585 content reads and38 WriteAuth independently passed in the preceding aggregate738 run, which failed only the new raw unpaired-surrogate case; that failure is retained and the latest wire RawMessage fix preserves precision including 1e-400. PG112 latest independently exported actualGo/native/all115migrations/Hikari1 actual0, `/tmp/weknora-java-postgres-update-third.log`, artifact bvn5khmc. Full production PG replay actual0 and independent XML1/all0, `/tmp/weknora-java-postgres-update-pipeline-third.log`, artifact silefky_, now10 uploads/44 jobs/42 succeeded/2 canceled/22 embeddings/19 chats; two added native own creator-Viewer/shared Contributor metadata PUT -> real durable refresh -> actual model/halfvec, source chunks/index rows/file bytes/quota/pending counters retained. Earlier intermediate job-count assertion failures are retained; the production behavior and expected Go outputs were not changed.

Frozen1075-file candidate package actual0 (skipTests, not fullverify), `/tmp/weknora-java-update-candidate-package.log`; jar152772323 bytes SHA2567708363c7d9c4eef2448637cb307cbb5d46d9f27a1889ddbdd1df2fb26300a30. Source/jar hashes independently identical after both single/go `--update-knowledge` actualexit0; each2 uploads/8 durable jobs/6 embeddings/4 chats/readiness0. Real provider barrier establishes pending before execution; metadata/owner/attempt/language/options match, original summary chunk ID is reused and source vectors/FTS/source bytes/quota remain. Proof `/tmp/weknora-java-update-candidate-source-proof.json`. No shared fresh fullverify, public summary-regenerate, multi-store relocation/racing writes/Redis cutover completion is claimed. Current inventory89 in_progress/392 not_started/1 health/0 verified of482.

Manual additional independent root XML:204/4 (98public+45worker+57images+4 real raw-SQL queue/claim tests), `/tmp/weknora-java-manual-images-worker-second-build`; Spring7 `/tmp/weknora-java-manual-production-third-build`; live PG worker45 `/var/folders/pf/6ssfy5pj7fj9w1jyg4kpbmcr0000gn/T/weknora-postgres-manual-i1g8t66y/build/surefire-reports`; all zero failure/error/skip and agent-reported actualexit0. Default pool5 concurrency, image:multimodal/VLM and shared fullverify/immutable new-process acceptance remain pending.

2026-10-15 Chunk edit/revert增量：实际Go196安全导出actual0，原184规范化后无语义变化；保留unsafe196复现证明Go旧full-row Save覆盖后续编辑/metadata/tenant，Go和Java修正provider前后绑定与条件专属状态更新。SQLite差分196+完整Spring6 actual0，独立XML202/2全0，/tmp/weknora-java-chunk-edit-binding-first.log；请求的WriteAuth类名未存在而未执行，不计入202，正在独立正确类复跑。PG全115Go migrations/Hikari1 actual0，独立XML196/1全0，/tmp/weknora-java-postgres-chunk-edit-binding-first.log，artifactgfy1vn9h；真实halfvec每source/type原始float32位/metadata/完整HTTP/SQL/不可变revision/实际model与Asynq严格对照，GIN sentinel仅保留证据。全Go -tags sqlite_fts5 ./... actual0 /tmp/weknora-go-chunk-image-full-first.log（后续Image改动不混入）。Chunk编辑两路由继续in_progress；清单91in_progress/390not_started/1health/0verified。新Java完整cleanverify和同jar两拓扑验收仍待完成；详见knowledge/chunk-edit-migration-notes.md。

Chunk edit write auth补测：正确类KnowledgeBaseWriteAuthorizationCompatibilityTest独立actual0，XML38/1failure/error/skip均0，/tmp/weknora-java-chunk-edit-write-auth-first.log；不与先前202的原始报告混合。Image agent67/3（52worker+11Spring+4rawqueue）、PG52 artifact_1qejdg8及rawqueue新增7/2 XML也已独立核实全0，但后续Image producer/取消接线仍在进行，未作为新的共享全量checkpoint。

Chunk immutable candidate同jar two topologies实际exit0：single/go各2 uploads/10 durable jobs/12 embedding/6 chat/Hikari1/readiness0，版本0→1→2、own creator Viewer/shared Contributor、真实FTS MATCH→rowid元数据/vec、summary ID重用、file bytes/quota retained、stale409/no-op无任务或RPC均严格断言。1114非md文件初冻结遗漏canonical proto导致首package编译失败，补未改动8 proto相关文件后1122文件package actual0（skipTests，未称full）；jar152851186 bytes SHA2564fcd10c6e992cee68bb19ef175d056e9da3c7e1fcd3349d0b78cc12f8e3c3383。首两模式runner从contentless FTS直接读取source_id得NULL失败，修正为实际MATCH rowid JOIN物理metadata，未改业务或jar；第二轮两模式actual0 /tmp/weknora-java-chunk-edit-candidate-{single,go}-second.log。Source/jar独立核实验收后hash不变，proof /tmp/weknora-java-chunk-edit-candidate-source-proof.json。此candidate在后续public PG实际上传发现SQL NULL metadata写JSONB错误之前，修复保留NULL的当前workspace与Image最新producer尚不属于该jar；pending新checkpoint。
# 2026-10-15 新鲜问题索引与全量失败追踪

公共问题 PUT/DELETE 实际 Go SQLite130 导出 actual0；Java question130+production Spring5+原 edit196+Spring6 新鲜同次337/4，独立 XML failures/errors/skips 全0，actual0：`/tmp/weknora-java-chunk-question-safe.log`。PG115迁移/Hikari1 actual Go+Java130 actual0、XML130全0：`/tmp/weknora-java-postgres-chunk-question-safe-second.log`，artifact7wxebxpe；有效 upsert HTTP200/真实 embedding1。双方原来 JSONB 格式字符串比较假冲突的错误 oracle保留在o6_yi4_y，生产Go+Java改用JSON语义比较后重新导出。详见 knowledge/chunk-question-migration-notes.md。

实际PG公共上传/编辑/恢复/summary刷新整链第五次运行actual0，XML1全0，`/tmp/weknora-java-postgres-chunk-edit-pipeline-fifth.log`，artifactc8yx0ll3：10uploads/48jobs/46success+2cancel/30embedding/23chat。新增用例验证真实BM25 MATCH/halfvec与源文件quota/revisions/actor；暴露并修复PG NULL metadata误绑定空JSON字符串。

先前冻结chunk候选full实际exit1：151classes/7376tests/7failures/0errors/0skips，`/tmp/weknora-java-chunk-edit-candidate-full-first.log`。6项因为冻结输入遗漏config（prompt和builtin agent），下一版补完整config；另外1项TaskTopology已用新鲜隔离测试实际复现SQLITE_BUSY，原因settle事务读后升级写锁冲突。生产SQLite claim/settle/commitResult在读前取得writer reservation，PG仍用原有row locks；修复专项TaskRepository11+TaskWorker6+Topology5共22/3全0、actual0，`/tmp/weknora-java-task-topology-fixed.log`。失败诊断保留`/tmp/weknora-java-task-topology-diagnostic.log`，未用重跑掩盖。

新1194文件完整源/config/migrations/proto候选在`/tmp/weknora-java-alignment-question-image-layout`，proof `/tmp/weknora-java-alignment-question-image-proof.json`，全量日志`/tmp/weknora-java-alignment-question-image-full.log`；**尚未记录通过**。该候选包含新问题PUT/DELETE、PG NULL/JSONB修复、SQLite任务并发修复及image66稳定代码；前述旧jar不可证明这些后续改动。


## 2026-10-15 冻结问题增删/图片候选与同步重新生成增量

冻结源码 `/tmp/weknora-java-alignment-question-image-layout` 共1194文件（含canonical config/migrations/docreader/proto）。完整构建 `/tmp/weknora-java-alignment-question-image-full.log` actual exit0；独立读取154份Surefire XML合计7538 tests、0 failures/errors/skips。jar为152868176 bytes，SHA256 `ecf1ba248fb20bd19195720502b9d9ede78d185aa90d6f8e6c8a304abc5b4a9a`；源码与jar哈希证明 `/tmp/weknora-java-alignment-question-image-proof.json`，运行后重新核对未变化。

同jar最终36种不同模式全部actual exit0：`/tmp/weknora-java-alignment-question-image-package-final-manifest.json`。包含原始34次attempt和两次cancel runner失败，原因是新增refresh release误满足原cancel等待；修正runner后同jar两拓扑通过，保留严格finalizing/取消/物理索引断言，生产代码未改。另新增公开问题增删两拓扑实际UUID/hashed alias/FTS/vec验证，各2上传4任务6embedding0chat。完整结果包含SQLite任务并发修复：先取得writer锁再读租约，避免真实SQLITE_BUSY快照升级冲突；专项22项及任务拓扑额外3次重复运行通过，不把重复数加进7538。

后续同步问题重新生成尚不在该jar内。实际Go81场景、Java新鲜组合421项/5类（81+9+130+196+5）actual exit0、独立XML全0，日志 `/tmp/weknora-java-chunk-regenerate-safe81-first.log`。另WriteAuthorization38项actual0。PostgreSQL实际Go/Java81场景 actual exit0，XML81/0/0/0，`/tmp/weknora-java-postgres-chunk-regenerate-safe81-first.log`，artifact `weknora-postgres-chunk-edit-c5_ychhc`。保留原不安全Go66输出和修正后81输出。公开PG总流程正在重新验证，新冻结完整构建和同jar重新生成两拓扑待进行。

新增公开PG总流程 fourth actual exit0：`/tmp/weknora-java-postgres-chunk-question-regenerate-pipeline-fourth.log`，10上传、48任务、46成功/2取消、38 embedding HTTP、27 chat HTTP；own creator-viewer/shared contributor均真实执行问题新增/修改/删除与两次重新生成。核对hashed source、BM25查询、halfvec距离、新旧UUID不相交、正文修订/历史/源文件/配额/任务数保留。首次testCompile漏传token和随后首次空集合AssertJ测试错误均保留；修正测试参数与空集合调用，生产断言未放宽。第三次已经actual0/XML1全0但runner输出仍旧30/23，第四次修正输出为与测试实际断言一致的38/27再运行通过。

## 2026-10-15 同步重新生成/ES8 新冻结完整里程碑

新冻结 `/tmp/weknora-java-regenerate-vector-layout` 包含1313个项目输入文件（额外复制10721个docreader虚拟环境依赖文件，不计项目源码）。完整clean verify `/tmp/weknora-java-regenerate-vector-full.log` actual exit0；独立核对159份XML：7712 tests、0 failures/errors/skips。包含同步chunk问题重新生成、绑定ES8原生公开检索与元数据/registry/context等新增代码；真实ES独立IT另列，不混入默认test数量。

固定jar `/tmp/weknora-java-regenerate-vector-build/backend-java-0.1.0-SNAPSHOT.jar`，152904047 bytes，SHA256 `bbb2f24feab41cf278a8751ad9a917edaf10e2102e3ce2aec4ed997388c1bde0`。证明 `/tmp/weknora-java-regenerate-vector-proof.json`；验收后重新核查jar及全部12034冻结输入哈希均未变化。

同jar38种流程全部首次actual exit0，`/tmp/weknora-java-regenerate-vector-package-batch.log`，模式为Office、summary、question、cancel、auto-tag、kb-profile、index-delete、document-delete、public-delete、chunk-lifecycle、folders、update-knowledge、chunk-edit、chunk-questions、chunk-regenerate、graph、hybrid、manual、image，各single/go。新增chunk-regenerate每种2上传6任务8embedding6chat，真实UUID/hashed alias新旧替换、FTS/vec、源文件/配额/版本/历史/无新增刷新任务均严格检查。

同jar另2种绑定ES8公开进程配置通过，各14公开HTTP/8模型HTTP/4物理ES条目：实际Go DTO、own/shared JWT/API scope、ES物理删除后绑定空索引不回退、显式解绑恢复default、缺失绑定2200。这里tasks=false，仅同步HTTP验收。首次single runner忘记在第二Python SQLite连接load vec0，原失败exit1保留，修正runner每connection加载后同jar single/go actual0，未改生产代码/fixture或断言。ES原始及修正runner/log哈希见 `/tmp/weknora-java-regenerate-vector-es-package-manifest.json`。

统一40种最终不同模式/配置 `/tmp/weknora-java-regenerate-vector-package-final-manifest.json`，不把失败重跑累加。root独立复算ES日志/runner哈希、源码/jar哈希、默认XML和40项实际退出结果。新ES300 tests/10类专项actual0；当前Go修复后PG52再跑actual0、root独立XML52/0/0/0，`/tmp/weknora-java-vector-hybrid-postgres-final.log`，artifact `weknora-postgres-vector-hybrid-c_789r98`。当前Go全套 `/tmp/weknora-go-vector-store-full-final.log` actual0由授权agent读取进程退出，root检查日志；包含本轮ES构造context修复。

该里程碑不代表482条路由全部迁移、所有22种Go业务任务或Lite/Redis语义对齐。公开store CRUD、其余外部引擎、绑定后台发布、完整RAG/会话/SSE/Agent及切换仍待完成。

## Java开发试用实例与向量存储管理后续验收

公开vector store8接口更新为in_progress，102路由in_progress/379 not_started/1 health implemented/0 verified。后续完整验收7851项/161类（显式包含public IT133+Spring IT6），root独立XML全0、1133输入文件哈希与当前工作区完全相同。固定jar SHA256 c7b5e454c6d6fcb421e16ed6332383a5640f3c1444f0db0e5535b94c1d17d792；完整范围、6种同jar进程配置及其他引擎限制见vectorstore/public-api-migration-notes.md。此6种配置不能和上一jar的40种混称同jar46种。

使用该jar启动开发试用实例127.0.0.1:8081，原开发PostgreSQL只读pg_dump后恢复到独立owned容器，原文文件复制到隔离目录；仅在副本应用Java任务schema，桥接关闭、native processing和SQL worker开启、Neo4j关闭，独立JWT要求重新登录。已验证health200、auth/config200、未认证KB401。运行信息位于 /var/folders/pf/6ssfy5pj7fj9w1jyg4kpbmcr0000gn/T/weknora-java-dev-4xwr06c8/metadata.json。未切换现有前端代理。

## 2026-10-16 当前源码与功能覆盖复核

刷新Go清单后独立扫描Spring显式method/path，107个Java组合对应103条Go路由（含health），379条无对应显式接口，与102 in_progress/379 not_started/1 implemented/0 verified一致。通配未迁移错误处理器和Go bridge不计为原生实现，条件注册也不保证当前实例已启用。Go共22种业务任务、Asynq/Lite合计44项注册，Java12种部分迁移，另10种及完整Lite执行语义未对齐。

本次没有重新运行全套测试。独立重读最新保存的161份Surefire XML合计7851项，failures/errors/skips均0；重新计算1133个构建输入与jar哈希，全部与冻结证明相符；最新manifest6个actual exit0及6份日志哈希均一致。该构建显式选择public IT133和Spring IT6，不等同默认Maven选择。上一7712/40属于另一jar，不能合并证据。

运行观察：8081 health200，8080 health未连通，未完成新一轮在线Go/Java全接口A/B。核对当前Java进程启动参数与默认配置发现上传开关 `weknora.knowledge.upload.enabled` 未开启、Neo4j关闭；源码上传/图谱能力不能视为当前试用实例已启用。本次仅核对与更新文档，没有重启或更改运行配置。逐接口结果见[migration/native-route-audit-2026-10-16.json](migration/native-route-audit-2026-10-16.json)，功能差异见[功能对齐矩阵](migration/go-java-feature-comparison.md)。

## 2026-10-16 公开摘要增量与最新功能复核

公开摘要入口新增后，482条Go路由中103条in_progress、378条not_started、1条health implemented、0条verified。重新扫描108个Spring显式method/path，匹配104条Go路由（含health），与台账无差异；通配错误处理与bridge不计为原生覆盖。重新计算2,481个Go清单文件哈希、当前冻结1,154个非Markdown构建输入，差异均为0。逐接口快照见[migration/native-route-audit-2026-10-16-summary-public.json](migration/native-route-audit-2026-10-16-summary-public.json)。

公开摘要新鲜专项实际exit0，独立读取XML为257项/5类、0 failures/errors/skips：公开Go差分56、Spring链7、原summary worker44、文档更新112、写授权38。报告 `/tmp/weknora-java-summary-public-20261016-build/surefire-reports`，日志 `/tmp/weknora-java-summary-public-20261016-focused-first.log`。实际Go/PostgreSQL56项独立通过（115个Go migrations、Hikari1、真实halfvec），第四轮日志 `/tmp/weknora-java-summary-public-pg-20261016-fourth.log`，报告 `/var/folders/pf/6ssfy5pj7fj9w1jyg4kpbmcr0000gn/T/weknora-postgres-summary-public-g71p3dmg/build/surefire-reports`。此前3次fixture隔离/JSON绑定/sequence初始化失败均保留；详细边界见[摘要入口说明](src/main/java/com/tencent/weknora/knowledge/summary-public-migration-notes.md)。

**首轮完整构建未通过，不能作为发布候选：actual exit1，163类/7,914项、139 failures、0 errors、0 skipped，其余7,775项通过。** 日志 `/tmp/weknora-java-summary-public-20261016-full.log`，报告 `/tmp/weknora-java-summary-public-20261016-full-build/surefire-reports`。构建来自 `/tmp/weknora-java-summary-public-20261016-layout` 的冻结输入，证明 `/tmp/weknora-java-summary-public-20261016-source-proof.json`。命令显式选择默认测试模式以及 `VectorStorePublicGoParityIT`、`VectorStorePublicPipelineHttpIT`，却漏传这两个IT必需的 `-Dweknora.es.url=...`：前者133项在非空断言处失败，后者6项因空地址无法通过安全校验。该失败及最初功能复核保留；随后配置隔离ES完成的重跑见下一节。

本次功能复核重新读取上述失败XML与专项通过XML，没有修改业务实现、削弱测试、启动ES、重跑全套或重启8081。新runner `--regenerate-summary` 的single/go固定jar进程验收尚未运行通过。上一个7,851项通过构建与其6种进程验收仍是历史证据，旧jar不包含本次公开摘要入口。8081 health仍返回200，运行的是该旧jar；8080 health未连通，本轮没有完成双服务在线全量A/B。当前范围和差异见[功能对齐矩阵](migration/go-java-feature-comparison.md)，机器可读复核见[migration/comparison-review-2026-10-16.json](migration/comparison-review-2026-10-16.json)。全量迁移目标保持进行中。

## 2026-10-16 公开摘要增量完整重跑及固定jar验收

从相同1,154个冻结输入执行新一轮clean verify，actual exit0；root独立读取163份XML，合计7,914 tests、0 failures/errors/skips。命令补齐 `weknora.es.url` 并显式选择 `Test*,*Test,*Tests,*TestCase,VectorStorePublicGoParityIT,VectorStorePublicPipelineHttpIT`，原先139项向量存储失败也全部通过。使用自己创建的 `elasticsearch:8.14.0` 容器、随机loopback端口60330，没有连接9200开发ES；结束后只停止该容器，actual stop0。无业务源码或断言改动。日志 `/tmp/weknora-java-summary-public-20261016-full-second.log`，报告 `/tmp/weknora-java-summary-public-20261016-full-second-build/surefire-reports`，命令及实际退出记录 `/tmp/weknora-java-summary-public-20261016-full-second-manifest.json`。首轮139fail的日志/XML未覆盖。

固定jar `/tmp/weknora-java-summary-public-20261016-full-second-build/backend-java-0.1.0-SNAPSHOT.jar`，152,949,857 bytes，SHA256 `4b4d3e39ba3304243198bae9d8a9a970ffbd7166daaeb05ab24e5819e8bc85bd`。同jar的 `--regenerate-summary` single/go两种SQL worker布局均首次actual exit0，每种2上传/8任务全部succeeded/8embedding HTTP/6chat HTTP。真实own creator-Viewer与shared Editor执行公开同步生成、异步刷新及queue故障；校验owner、当前attempt、语言、summary ID复用、入队参数、HTTP400业务错误和failed状态。故障注入后保留已存在索引，移除触发器；源文件、非摘要chunk/索引与配额保留。原始日志 `/tmp/weknora-java-summary-public-20261016-package-{single,go}-first.log`。

根进程独立读取两个真实SQLite数据库：各8任务全部成功、2文档parse completed/pending counter0、故障注入后的summary failed、4条vec0原始float32向量均为 `[1,0,0]`、2条摘要实际FTS MATCH命中，拒绝入队触发器已移除。manifest `/tmp/weknora-java-summary-public-20261016-package-manifest.json`；源码1,154输入、runner和jar哈希在验收后重新计算一致。可评审证据副本见[migration/summary-public-checkpoint-2026-10-16.json](migration/summary-public-checkpoint-2026-10-16.json)。本jar只有这2种新增流程的同包进程验收，不和旧jar的6种/40种相加。

Go重解析相关基线实际exit0：`go test -tags sqlite_fts5 ./internal/application/service -run 'Test(ReparseKnowledge|ReplaceKnowledgeFile|KnowledgeListReparse)' -count=1`，日志 `/tmp/weknora-go-reparse-baseline-20261016.log`；[重解析行为基准](migration/reparse-alignment-contract.md)记录源码核对结果和完整后续验收要求。文档两个重解析接口及批任务仍未迁移，482路由状态仍103 in_progress/378 not_started/1 health implemented/0 verified。没有重启旧8081实例、切换前端或接管Go队列；全量严格对齐目标继续进行。

## 2026-10-16 重解析worker完整构建与同jar验收

直接Go Asynq worker最终67个场景由生产ProcessKnowledgeListReparse导出，SQLite差分及相关回归289项/13类全部0、actual exit0；日志 `/tmp/weknora-java-reparse-worker-20261016-final.log`。独立自有PostgreSQL容器应用全部115个Go migrations及Java task schema，Hikari1、真实halfvec/FTS，另外67个Go/PostgreSQL worker对照全部0、actual exit0；日志 `/tmp/weknora-postgres-reparse-worker-20261016-final.log`。原59项/51 failures/3 errors、168项/1 failure和unsigned边界67项/2 failures报告保留。修正原始payload解码、嵌入trace字段错误路径、完整uint64、initiator审计快照、任务活动字段、部署语言和失败checkpoint；导出器改用UseNumber保留真实整数，没有削弱断言。

新冻结布局 `/tmp/weknora-java-reparse-worker-20261016-layout` 的完整clean verify **8,139项/168类、0 failures/errors/skipped、actual exit0**。包含显式公开向量存储IT的133+6项；自建ES8.14随机loopback端口61847，结束后仅停止该容器，actual stop0。日志 `/tmp/weknora-java-reparse-worker-20261016-full.log`，命令及退出码 `/tmp/weknora-java-reparse-worker-20261016-full-manifest.json`。全部1,280个冻结输入在文档/清单更新之前与工作区哈希一致。

固定jar `/tmp/weknora-java-reparse-worker-20261016-full-build/backend-java-0.1.0-SNAPSHOT.jar`，152,991,984 bytes，SHA256 `682db43c68bdd13bd80a92caa2e970ed5bcab7b55284cc85d0dec48892d49cf3`。同jar `processing_smoke.py --reparse --topology single|go` 均actual exit0，每种2上传/11个成功任务/5embedding HTTP/0chat HTTP。创建者Viewer单文档重建后，共享Editor批量重建两个文档；验证attempt 3/2、新chunk和实际vec/FTS、原始源文件字节/配额、owner tenant/原actor/批任务ID审计。根独立核对两个真实数据库：每个document:process 5成功、postprocess 5成功、batch wrapper 1成功，2条真实vec0向量、2条FTS命中，2文档completed/counter0，原actor审计owner Viewer 1条/shared Contributor 2条。原日志 `/tmp/weknora-java-reparse-worker-20261016-package-{single,go}-first.log`。这2种流程不能与旧jar的摘要/其他流程相加称作同包验收。

可评审证据：[worker checkpoint](migration/reparse-worker-checkpoint-2026-10-16.json)、[详细范围](src/main/java/com/tencent/weknora/knowledge/reparse-worker-migration-notes.md)。两个接口和批任务仅推进为in_progress：482条路由为105进行中、376未开始、1 health已实现、0 verified。启用Langfuse、完整解析器/存储/并发、Go事务和配额语义、Redis/Asynq存量/Lite、全部其他业务与部署切换仍待完成。没有重启现有开发实例或切换前端，完整迁移目标保持进行中。
