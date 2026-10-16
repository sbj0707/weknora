# WeKnora Java 后端迁移

2026-10-16 当前复核：**121/482 条 Go 接口有 Java 原生入口，361 条缺失；15/22 种任务有部分 handler，7 种缺失，0 verified，功能仍不一致。** 本批新增普通复制执行编排和同步目标清理，连接已有文档/chunk复制核心；实际 Go46 场景各执行两次，相关回归 **791项/8类全部通过、实际退出0**。1068个src/pom输入构建前后哈希一致。生产适配器、目标创建、FAQ、公开复制/进度和 kb:clone 尚未完成，PostgreSQL 执行未验收。最近默认完整10464项通过只覆盖旧索引复制1047输入快照，当前3个修改、21个新增，本批未重跑完整构建。未部署或重启服务。见 [最新对比](migration/comparison-latest.md)、[普通复制范围](migration/clone-execution-notes.md) 和 [本批证据](migration/clone-execution-checkpoint-2026-10-16.json)。

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

这是正在实施的 Go → Java 迁移工程，**尚未完成整个主后端替换**。现有 Go、前端和数据库迁移均保留。实际接口清单与逐项状态见 [migration/README.md](migration/README.md)；当前扫描得到 482 个路由，不能把启动成功或 Go 桥接当作全部迁移完成。

最新批量重解析增量已有67个SQLite和67个PostgreSQL真实Go worker对照、289项相关回归通过。冻结源码完整构建 **8,139项/168类，failures/errors/skips全0、actual exit0**；同jar单文档及共享批量重解析在single/go两种SQL worker布局通过，每种2上传/11任务/5embedding HTTP，实际vec/FTS、源文件、配额、attempt及原始actor审计已核对。见[本批checkpoint](migration/reparse-worker-checkpoint-2026-10-16.json)和[worker说明](src/main/java/com/tencent/weknora/knowledge/reparse-worker-migration-notes.md)。URL/file_url worker增量也包含在该冻结构建中；公开URL导入、启用Langfuse、完整存储/并发/队列接管及其他Go模块仍待完成。迁移清单为105条进行中、376条未开始、1条health已实现、0条verified。

## 当前可以运行的 Java 原生能力

- `GET /health`：与 Go 一样返回 `{"status":"ok"}`。
- 认证：Go bcrypt 密码验证、HS256/384/512 JWT 与历史无 `exp` claim、数据库 access token 撤销检查、登录、refresh 单次轮换、logout；新增 validate、偏好修改、切换工作区、密码修改，以及运行时配置和注册事务链。
- OIDC：四个公开GET、Go YAML/环境配置、state/cookie、discovery/code exchange/userinfo/RSA-JWKS、自定义字段映射、本地账号/workspace/membership/preferences与access/refresh token链。315项专项和实际jar新旧账号登录检查通过；详见 [OIDC范围](migration/oidc-notes.md)。
- 租户权限：current/detail/metadata、cross-super all/search、retrieval/memory配置9个具体接口已有原生实现，10项专项通过；其他KV keys保持Go。active user/tenant/member 校验、`X-Tenant-ID` 切换校验、角色等级、tenant API key 的 SHA-256/hash、有效期/撤销、能力及 KB allow-list。新增 Owner 管理 API key 的 list/create/update/revoke，完整 scope/KB/expiry/AES 校验和异步 last-used；成员管理、lastOwner 并发约束、会话撤销、孤儿 home 恢复及退出已有实现。新增邀请 list/create/revoke/share-link/inbox/count/accept/decline/token lookup 与邀请注册，共11路由；Go 请求校验对照与两个公开邀请接口共享30/60s限流已验证，已补原生role-guard拒绝审计、60s去重和审计count失败后仍尝试insert；PG对照及剩余路由guard仍待完成。
- 知识库：本租户 list和owner/shared/Agent授权detail、`creator=mine|others`、文档/FAQ/处理计数、secret 脱敏、Go 类型 JSON 字段与默认值、个人 pin 和列表排序；新增 create/update 的租户权限、存储绑定、配置校验、事务和 best-effort 审计。已按真实Go修正更新授权：Viewer creator可更新，非creator需Admin；共享Editor按原KB owner写入/审计，API key/capability与lookup503保留。尚未实现的任务副作用会在写入前拒绝。
- 解析引擎管理：原生 GET `/api/v1/system/parser-engines`、POST `/parser-engines/check` 和 POST `/docreader/reconnect`（后两条同属 `/api/v1/system`）。保留 Go 引擎元数据、未保存配置探测、secret 合并、共享 reader 重连，以及 Viewer/Admin 和 API key 能力校验。新增租户KV配置GET/PUT、密钥保留/脱敏、显式false与Go请求绑定对照；共享tenant service存储校验依赖仍待完成。Office 转换与完整 cloud parser 仍待迁移。
- PostgreSQL JDBC 与 SQLite JDBC 连接、事务、现有表读取；自动建表/改表关闭。SQLite 时间写入兼容 Go DATETIME 文本。
- 临时 Go HTTP 桥接：认证头/租户头/query/body/状态透传、原始上传与文件字节、SSE 逐块 flush。**业务仍在 Go 中执行。**
- 文档索引查看：原生 `GET /api/v1/knowledge/:id/index-status` 和 `GET /api/v1/knowledge/:id/chunks/:chunk_id/details`，复用共享 KB/Agent/API key 授权，读取实际 SQLite FTS/vec0、问题与 Wiki 引用。PostgreSQL 读取已有代码但仍待真实库验证；图谱和未适配外部存储显示未知。详见 [索引详情范围](src/main/java/com/tencent/weknora/knowledge/index-read-migration-notes.md)，两路由保持 `in_progress`。

模型 list/detail 已实现本租户/内置与 remote chat 范围、Go DTO 脱敏、AES-GCM 凭据配置状态和 runtime catalog；providers 与 catalog resolve GET/POST 已实现部署配置及 console DB overlay、last-good 同步。专项覆盖 131 个 Go DTO、740 个 runtime resolve 和 82 个实际 Go handler 金样本。远程模型创建/更新、凭据修改/清除及完整引用保护删除已通过 18 项专项；本地模型下载、probe/推理/copy/cloud仍未迁移；Go bridge 暂不接管 models 路由。详见 [模型范围说明](src/main/java/com/tencent/weknora/model/migration-notes.md)。

## 编译、验证与启动

需要 Java 21 和 Maven 3.6.3+。采用 Spring Boot 3.5.16，官方兼容范围包含 Java 21：[系统要求](https://docs.spring.io/spring-boot/3.5/system-requirements.html)。

```sh
# 从仓库根运行
# SQLite 差分用实际 vec0；开发构建需 Go 锁定模块缓存和 C compiler
weknora_vec_library="$(backend-java/scripts/build-sqlite-vec.sh /tmp/weknora-sqlite-vec-native)"
backend-java/scripts/build-jieba.sh /tmp/weknora-jieba-native
# macOS uses .dylib below; Linux uses libweknora_jieba.so
mvn -f backend-java/pom.xml -Dweknora.build.directory=/tmp/weknora-java-oidc-full-build \
  "-Dweknora.sqlite.vec.extension=$weknora_vec_library" \
  -Dweknora.jieba.library=/tmp/weknora-jieba-native/libweknora_jieba.dylib \
  -Dweknora.jieba.dict=/tmp/weknora-jieba-native/dict clean verify
python3 backend-java/migration/generate.py
```

验证命令使用独立输出目录，避免编辑器后台 Java 编译产生旧类或删除 Maven 正在读取的类。`weknora.build.directory` 默认仍为 `backend-java/target`，Docker/常规构建可不覆盖；独立验证的 jar 和报告位于该参数指定目录。

服务默认监听 **8081**，Go 可以继续监听 8080。先将数据库恢复为隔离副本，再设置 Java 连接参数。`JWT_SECRET` 必须与需要兼容的 Go 测试服务一致，不使用内置默认密钥。

```sh
export JAVA_DB_URL='jdbc:postgresql://127.0.0.1:5432/weknora_java_test'
export JAVA_DB_USERNAME='weknora'
# JAVA_DB_PASSWORD 和 JWT_SECRET 从已有测试环境配置注入
mvn -f backend-java/pom.xml spring-boot:run
curl http://127.0.0.1:8081/health
```

SQLite 副本配置示例：

```sh
export JAVA_DB_URL='jdbc:sqlite:/absolute/path/to/weknora-test-copy.db'
export SPRING_DATASOURCE_DRIVER_CLASS_NAME='org.sqlite.JDBC'
export JAVA_DB_POOL_SIZE=1
```

前端开发服务可以临时指向 Java 入口，保留原有前端文件：

```sh
cd frontend
VITE_DEV_PROXY_TARGET=http://127.0.0.1:8081 npm run dev
```

所有运行使用现有 schema，Java 不启动 Flyway/GORM migrations。空数据库只适合健康检查；认证和业务接口需要 Go 已初始化的数据库。

当前全量迁移尚未完成：482条Go HTTP路由的清单仍为103条in_progress、378条not_started、1条health implemented、0条verified。2026-10-16最新扫描确认106条Go路径有Java显式入口，其中2条是尚未验收的重解析草稿；376条没有显式入口。路径存在不代表功能对齐。公开摘要稳定批次的257项专项和56项PostgreSQL对照通过；**最近完整冻结构建7,914项/163类、失败/错误/跳过均0**，显式包含公开向量存储IT133+6项。首轮漏传ES地址造成139项失败；补齐独立ES配置后，同份源码完整重跑通过，原失败保留。当前已比该1,154输入快照新增6输入并修改2个权限类，旧全量结果不覆盖这些改动；本次当前源码编译通过，未重跑完整测试。详见[当前核对记录](migration/comparison-review-2026-10-16-current.json)。

摘要稳定jar SHA256为 `4b4d3e39ba3304243198bae9d8a9a970ffbd7166daaeb05ab24e5819e8bc85bd`。同jar的摘要公开入口single/go两种SQL worker布局通过，每种2上传/8任务/8embedding/6chat，实际检查同步/异步生成、共享授权、入队失败和vec/FTS/源文件/配额保留。冻结源码与jar证据保留，但该jar不包含当前重解析草稿。这是本jar的2种流程验收，不能替代所有历史流程、完整任务或全路由切换验收。

上一个通过的完整构建为 **7,851项/161类、失败/错误/跳过均0**，其jar的6种同步配置均actual exit0、tasks=false；该旧jar不覆盖后续摘要入口，1,133个旧输入哈希不能用于证明当前全部改动。再上一批7,712项/40种流程属于另一个jar，不能相加称同jar46种。原生SQL业务任务已有12种部分迁移、1种批量重解析草稿，Go的其余9种任务没有handler；Lite/Redis消费和完整切换尚未完成。源码、jar哈希、原始失败与证据见 [VALIDATION.md](VALIDATION.md)。

同步chunk重新生成另有81 SQLite/81 PostgreSQL实际Go对照、9 Spring流程；绑定ES8另有300项组合、52 PostgreSQL公开对照，以及真实ES写入/检索/删除专项。公开PG联合流程通过10上传48任务（46成功/2取消）、38embedding/27chat HTTP。公开向量存储8个管理接口已有原生实现和133实际Go对照，但其余引擎、绑定后台文档写入仍待迁移；完整RAG、会话/Agent、其余任务与生产切换也未完成。详见 [功能对齐矩阵](migration/go-java-feature-comparison.md)。

当前8081开发试用实例仍运行先前7,851项构建的jar，未自动重启到本次摘要入口改动。health已返回200，但按启动参数及默认配置，上传开关 `weknora.knowledge.upload.enabled` 未开启，Neo4j关闭；processing和SQL worker开启不能替代这两个功能开关。该实例使用隔离数据库、bridge关闭，未切换现有前端代理。
