# WeKnora Java 后端迁移

这是正在实施的 Go → Java 迁移工程，**尚未完成整个主后端替换**。现有 Go、前端和数据库迁移均保留。实际接口清单与逐项状态见 [migration/README.md](migration/README.md)；当前扫描得到 482 个路由，不能把启动成功或 Go 桥接当作全部迁移完成。

## 当前可以运行的 Java 原生能力

- `GET /health`：与 Go 一样返回 `{"status":"ok"}`。
- 认证：Go bcrypt 密码验证、HS256/384/512 JWT 与历史无 `exp` claim、数据库 access token 撤销检查、登录、refresh 单次轮换、logout；新增 validate、偏好修改、切换工作区、密码修改，以及运行时配置和注册事务链。
- OIDC：四个公开GET、Go YAML/环境配置、state/cookie、discovery/code exchange/userinfo/RSA-JWKS、自定义字段映射、本地账号/workspace/membership/preferences与access/refresh token链。315项专项和实际jar新旧账号登录检查通过；详见 [OIDC范围](migration/oidc-notes.md)。
- 租户权限：current/detail/metadata、cross-super all/search、retrieval/memory配置9个具体接口已有原生实现，10项专项通过；其他KV keys保持Go。active user/tenant/member 校验、`X-Tenant-ID` 切换校验、角色等级、tenant API key 的 SHA-256/hash、有效期/撤销、能力及 KB allow-list。新增 Owner 管理 API key 的 list/create/update/revoke，完整 scope/KB/expiry/AES 校验和异步 last-used；成员管理、lastOwner 并发约束、会话撤销、孤儿 home 恢复及退出已有实现。新增邀请 list/create/revoke/share-link/inbox/count/accept/decline/token lookup 与邀请注册，共11路由；Go 请求校验对照与两个公开邀请接口共享30/60s限流已验证，已补原生role-guard拒绝审计、60s去重和审计count失败后仍尝试insert；PG对照及剩余路由guard仍待完成。
- 知识库：本租户 list和owner/shared/Agent授权detail、`creator=mine|others`、文档/FAQ/处理计数、secret 脱敏、Go 类型 JSON 字段与默认值、个人 pin 和列表排序；新增 create/update 的租户权限、存储绑定、配置校验、事务和 best-effort 审计。已按真实Go修正更新授权：Viewer creator可更新，非creator需Admin；共享Editor按原KB owner写入/审计，API key/capability与lookup503保留。尚未实现的任务副作用会在写入前拒绝。
- 解析引擎管理：原生 GET `/api/v1/system/parser-engines`、POST `/parser-engines/check` 和 POST `/docreader/reconnect`（后两条同属 `/api/v1/system`）。保留 Go 引擎元数据、未保存配置探测、secret 合并、共享 reader 重连，以及 Viewer/Admin 和 API key 能力校验。新增租户KV配置GET/PUT、密钥保留/脱敏、显式false与Go请求绑定对照；共享tenant service存储校验依赖仍待完成。Office 转换与完整 cloud parser 仍待迁移。
- PostgreSQL JDBC 与 SQLite JDBC 连接、事务、现有表读取；自动建表/改表关闭。SQLite 时间写入兼容 Go DATETIME 文本。
- 临时 Go HTTP 桥接：认证头/租户头/query/body/状态透传、原始上传与文件字节、SSE 逐块 flush。**业务仍在 Go 中执行。**

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

当前最新独立 `clean verify` 完整通过 **3,444 项测试**（90个测试类，失败/错误/跳过均0），可执行jar为146,449,729字节，位于 `/tmp/weknora-java-datatable-foundation-full-build/`。结果来自同一次完整运行。新jar通过 **172项实际后端HTTP检查**（原有146项加OIDC26项）；8次OIDC provider、1次Go bridge归属探测和10次解析探测HTTP单独记录。单池、六池基础上传链各2上传、4个持久化任务、2次Embedding HTTP，Hikari单连接、Go BLOB模型参数、真实vec0/FTS、共享owner/原actor审计、最终postprocess完成均通过，readiness重试0。301个Java主/测试源码路径正确且未被Git忽略。新增DuckDB表格加载、原始摘要prompt和文档并发替换回归已计入；完整表格summary handler仍在隔离stage，尚未注册生产worker，严格Office全任务验收仍待完成。证据和限制见 [VALIDATION.md](VALIDATION.md) 与 [表格任务审计](migration/datatable-summary-audit.md)。482路由为65项in_progress、416项not_started、1项health implemented，完整Go替换尚未完成。

```sh
python3 backend-java/scripts/smoke.py /tmp/weknora-java-oidc-full-build/backend-java-0.1.0-SNAPSHOT.jar
python3 backend-java/scripts/oidc_smoke.py /tmp/weknora-java-oidc-full-build/backend-java-0.1.0-SNAPSHOT.jar
python3 backend-java/scripts/processing_smoke.py /tmp/weknora-java-oidc-full-build/backend-java-0.1.0-SNAPSHOT.jar "$weknora_vec_library" --topology go
# 同一基础处理链的默认单池回归：--topology single
```

处理smoke仅使用临时数据库、文件目录、随机端口和测试凭据；三个开关均显式启用，bridge关闭，增强处理禁用。smoke先通过只读认证请求确认数据库就绪，再发送一次上传；并行启动时曾观察到一次短暂503，未通过重试上传掩盖。真实PostgreSQL/云存储/外部模型、Docker、完整增强任务与生产切流未验证。

## 逐步替换时的 Go 桥接

桥接默认关闭。启用后默认只有健康检查由 Java 处理，其余 HTTP 请求交给 Go，保持未迁移业务原有认证与行为：

```sh
export JAVA_GO_BRIDGE_ENABLED=true
export JAVA_GO_BRIDGE_URL='http://127.0.0.1:8080'
```

`JAVA_NATIVE_ROUTES_ENABLED=true` 将当前 login/me/refresh/logout/validate/preferences/switch-tenant/change-password/config/register、tenant API key 管理 4 条、成员管理 4 条及精确 `POST /api/v1/tenants/:id/leave`、上述租户9个具体接口、邀请10条与精确 `POST /api/v1/auth/register-by-invite`、精确 `POST /api/v1/chunker/preview`、四个知识/分块读取 GET、三个解析引擎管理接口及精确GET/PUT parser-engine-config KV、OIDC四个精确公开GET、KB list/detail/pin 小范围链路切到 Java；其他路径仍进 Go。KB create/update 仍默认归 Go，以保留未迁移的引擎初始化/异步副作用。含 `agent_id` 的 KB 列表留在 Go。此开关只用于隔离验证，尚未通过全量兼容验收。

桥接以固定 HTTP(S) origin 转发，禁重定向/自动重试；每次只有一个业务请求，不做 Go/Java 双写。响应头 `X-WeKnora-Implementation: go-compatibility-bridge` 表明实际实现归属。

WebSocket/终端/桌面连接未实现桥接，返回 501；这些路径应暂时由前置代理直接转 Go。Java bridge 会替换未经验证的 forwarded/client-IP 头，以 servlet 连接源为基准；经前置代理时需要后续明确可信代理与客户端 IP 策略，当前不能声称公共 embed/IP 限流完全等价。Java servlet 线程按流占用，容量和连接中断需压测；本地 SSE 测试覆盖事件字节与首事件及时 flush，尚未证明所有停止/重连/任务取消行为。

## 已知原生迁移差异

- 始终要求 active user/tenant/member；孤立 home tenant 自动修复 Owner 已有原生事务实现及 12 项专项验证；仅有效 JWT 用户自己的有效 home tenant、没有任何活跃成员时恢复，并锁租户行防并发抢占。RBAC 开关与跨租户超级用户需按 Java 配置显式启用，并同时校验用户属性，避免只凭属性绕过权限。历史重复 token 行只要有撤销行即拒绝；这些防护比 Go 原始校验严格，尚未宣称所有身份路径字节级一致。
- API key 原生只支持 tenant scope/tenant principal。platform、direct/signed external principal、hash 缺失回填尚未迁移；last_used_at 已按 Go 异步一分钟限频写入。API key 路由默认拒绝，只放行显式 KB 能力；restricted key 可创建 KB，但新 KB 不自动加入旧 allow-list，保持 Go 行为。
- `/auth/me` 已实现角色安全的配置、实际 capabilities、偏好默认值与 runtime DB→ENV→YAML 配置优先级。新增 validate/preferences/switch/change-password；config/register 的完整个人租户、环境存储快照、Owner 与用户事务已通过专项验证。邀请 HTTP10条与邀请注册已原生落盘；225组Go请求绑定/校验、15组实际Gin代理IP规则、共享限流和真实SQLite状态/失败副作用通过专项。17组实际Go邀请注册业务、18组拒绝审计与guard对照已落盘并通过专项。OIDC四个公开GET和provider/本地身份链已原生接线，Servlet断开取消、代理与PG仍待验证；全域拒绝审计、租户/系统管理仍未完成；成员管理、工作区退出和孤儿恢复已有原生实现。内部邀请 DTO/TTL/URL codec 的57个实际Go对照已整合，全部路线仍保留in_progress。Tenant API key Owner 管理已有原生实现。Go List/PUT 会返回解密后的 api_key，POST 的 api_key 在启用 AES 时为密文并另返回 token；Java 已按真实 Go GORM fixtures 保持这一响应语义。
- KB create/update 已有原生实现及 12 项真实 SQLite 生命周期测试。绑定展示按 Go DB/env 元数据解析，不是连接探测。仅 default-connection PostgreSQL 绑定可以完成无副作用 preflight；原生SQLite索引SPI已移植FTS/vector初始化和image index修复，但KB生命周期尚未连接该SPI、外部向量引擎构建仍未移植，因此绑定preflight仍显式返回2201。现有绑定读取仍可展示其 Go 元数据；不能计完整索引迁移。画像 false→true 所需 Asynq 任务明确返回 501 并回滚；临时 KB 无该任务副作用。共享KB读取授权（含Agent）、更新写授权已有原生对照；共享向量绑定隐藏具体ID/name/engine并使用owner计数。复制、删除文件/索引清理、完整上传与搜索仍待迁移。
- Java 登录/KB 的全部错误消息、配置响应、分页/排序边界还需逐个 Go 运行结果对照，清单保留 `in_progress`。数据库/外部故障使用 typed 503；桥接保留 Go 原始响应。

## 后续必须完成的业务链路

1. 完成所有认证模式、租户/RBAC/共享/API key 管理与安全 DTO 行为对照。
2. 完成 KB CRUD 的 tenant 默认存储与具体实例验证、vector store ownership、事务、审计和清理任务；不能只删数据库行而遗留索引/文件。
3. 模型提供商和 runtime catalog、已有加密密钥格式、存储/向量适配器、资源 URL/预签名与权限。
4. 上传 → docreader/Anydoc → 分块 → Embedding → 索引 → 状态、任务的取消/重试/幂等/死信。
5. 检索 → rerank → RAG → 会话/消息 → SSE 的事件、停止/继续生成与落库；用固定模型响应做编排对照。
6. Agent 工具/MCP/沙盒/数据源同步/IM/embed/wiki/memory 和全部实际路由。
7. Docker/监控/压测、任务归属与排空、可回滚切流；全量 verified 后才能移除 Go。

### Asynq 切换约束

现有 Redis/Asynq 任务由 Go Worker 消费。Java Redis client 不兼容 Asynq 协议，当前没有让 Java 消费这些任务。迁移阶段相关请求交给 Go，由 Go 创建/消费任务；任务检查/重试/取消同样留在 Go。后续要么增加明确的内部 enqueue/inspect/cancel Go adapter，要么实现新的 Java task backend，并在停止新任务→排空/迁移 pending→切换消费者后移除 Go。任何 bridge/adapter 都标记 `bridge_only`，不能标记 Java 已实现。

### 文档解析切换约束

Python docreader 可以保留，但不覆盖 `internal/infrastructure/docparser/anydoc_reader.go` 的进程内 Go 解析。需要迁移为 Java parser 或独立 Go 解析 adapter。Java remote reader 根据 `DOCREADER_TRANSPORT` 选择原生 HTTP/JSON 或 gRPC；HTTP 已通过136个实际Go请求/响应对照与6项配置/DNS/取消安全专项，包含图片与source locator。Java gRPC client 已从 canonical `docreader/proto/docreader.proto` 生成，TLS/mTLS/token/deadline、取消、消息上限、stream-first/UNIMPLEMENTED-only unary fallback 和 ListEngines 已移植。25 常规测试和 2 项实际 Go gRPC 明文/mTLS 互操作已通过；这不代表上传/解析/分块/索引整条链路完成。详见 [解析适配范围](src/main/java/com/tencent/weknora/docreader/migration-notes.md)。

### 原生分块基础

`chunker/` 的 legacy254+adaptive175 共429个实际 Go oracle 已计入完整3,405验证；21个超宽表格边界促使使用 RE2/J 修复 Java regex 实际 StackOverflow。Unicode rune offsets、原文边界、context header、embedding text、保护区与图片引用位置均独立保留。HTML normalizer70项、原生 preview91项与 scanner1项专项通过（scanner 内含7039个实际Go字节输入），preview保持Go64k字符限制、配置默认、stats截断及鉴权且不写知识/分块表；基础上传链另有显式开关，增强处理仍待完成。这些组件已全部计入3,405完整verify；详见 [分块范围](src/main/java/com/tencent/weknora/chunker/migration-notes.md)。

### 文件存储基础

`storage/` 已落盘原生 local provider、具体 backend scope、Go HMAC预签名、旧 resources/bindings/grants 表、tenant-bound resource FileStore 和 KB/default/legacy 解析。73项测试已通过并纳入完整3,405验证，包含原68项路径/签名/资源grant/文件系统/SQLite/安全行为以及新增Unix MIME注册表：1117个真实Go注册、24个参数案例和10个实际资源持久化检查；基础TXT上传已显式接线，桥接仍归Go；cloud providers 明确拒绝执行，不能将 local 基础计为对象存储全迁移。

### SQLite 索引与原文位置

`retrieval/SQLiteIndexRepository` 已通过原生 JDBC vec0/FTS5 对照，复用 Go 的 metadata、FTS 和维度向量表，保存 float32 原始字节并加入调用方事务。11次真实Go写操作、704次真实检索和8组模型输入清理已验证；旧24项model clients与新增4项index测试的专项28项通过。开发验证需上述显式vec库参数，Java运行需operator提供匹配平台v0.1.6库；扩展不自动下载。Postgres halfvec/ParadeDB 和公开RAG接线仍待完成。详见 [索引范围](src/main/java/com/tencent/weknora/retrieval/migration-notes.md)。

`sourceloc/` 已实现原文rune引用、段落/BOM、相邻折叠、partial和完整引用、验证式rewrite/remap/alignment以及CSV行结构。7个测试方法内含235index、1880point、4464HTML quote、39rewrite、88Align、30CSV结构和12merge；相关240项专项clean通过。TXT/MD/CSV已连接处理链并绑定知识source hash；Office ZIP 原文定位已接入处理链（DOCX/DOCM/PPTX/PPTM/EPUB 与 XLSX 自定义/本地化数字展示格式；4,520 个解析与59,982个格式Go样本已对照）。实际jar中的custom XLSX原文定位也已对照，但单独的datatable:summary缺少handler，额外完整XLSX上传smoke失败；Office内容转换、表格摘要、ASR及其他处理仍待完成；[原文位置范围](src/main/java/com/tencent/weknora/sourceloc/migration-notes.md)。

### Java 任务后端

`task/` 是独立 SQL durable queue，默认 worker 关闭，**不会消费 Redis/Asynq**。显式 schema 在 `migration/sql/java-tasks.sql`，只在隔离副本验证后按任务归属切换；不会自动建表。29 项真实 SQLite/worker 测试覆盖 tenant-scoped 幂等、业务事务 enqueue、lease 续租与 attempt fencing、取消、重试预算、死信与人工重试、最终 SQL 副作用与 completion 原子提交、超时和停机。

生产 `KnowledgeFileTaskEnqueuer` 已保留 Go upload 的 queue、MaxRetry 和 timeout：MaxRetry(3) 为初次加三次重试，共四次执行；每次 enqueue 生成新 ID，并加入调用方 SQL 事务。Worker 使用持久化的每任务 deadline、队列成员过滤及 Asynq 加权随机顺序；重试延迟按锁定 Asynq 版本的 n⁴+15+jitter 公式计算。旧版 Java queue 的隔离副本需显式执行 `migration/sql/java-tasks-options-upgrade.sql`；新建副本只执行完整 schema，两者不一起运行。默认仍为single单池；显式 `weknora.tasks.topology=go` 启用六个独立pool和DB→ENV→默认值启动配额（8/2/12/4/6/8）。294个实际Go配置、队列registry/权重和真实SQLite饱和时的解析/后处理保留容量已对照。Java每池显式上限64；runtime配置API/Redis任务仍待完成。详见 [任务范围](src/main/java/com/tencent/weknora/task/migration-notes.md)。

基础document:process与knowledge:post_process已原生接线；profile及其他增强handler和旧task API仍待迁移，不能把queue基础设施计为22个任务完成。delivery 是 at-least-once，业务 handler 必须按 task ID 做幂等；外部存储/索引副作用还需自己的幂等和补偿。接线后才可在隔离环境启用 `WEKNORA_TASKS_ENABLED=true` 或 `weknora.tasks.enabled=true`，按任务类型停止 Go 新建→排空旧任务→单一消费者切换，保留回滚路径。

## Docker 构建

构建上下文使用仓库根，以生成 canonical proto：

```sh
docker build -f backend-java/Dockerfile .
```

镜像尚未实际构建和部署。

## 继续执行

逐路由读取 `migration/routes.json` 的 handler、guards、API-key policy、Swagger/前端证据；从 handler 沿 service/repository 审计事务和外部副作用。修改 `migration/status.json` 后重生成，生成器保留人工状态；保留旧 Go 实现与用户改动。完整 Go→Java 运行对照通过之前，不将 `in_progress` 改为 `verified`。

### 上传与模型的新基础组件

原生 `KnowledgeProcessConfig`、输入 overrides 验证和命名规则通过72/212/29组实际Go对照。`KnowledgeRepository` 和 `KnowledgeFileUpload` 的6项测试包含81个实际Go上传生命周期、5个真实SQLite/文件/资源/SQL任务场景，保持原始存储名与展示名分离、MD5去重scope、quota、tag best-effort、入队失败保留failed记录、数据库事务失败清理文件，以及文档/表格摘要任务的queue/retry/timeout契约。另有11项正式处理/HTTP测试，含24个实际Go处理结果、76个真实Go multipart绑定/大小/JSON响应以及真正的multipart→本地资源→durable worker→Embedding HTTP→SQLite vec0/FTS→postprocess链，保留source hash/owner租户/audit与源文件或lease改变时整事务回滚。三开关 `weknora.tasks.enabled=true`、`weknora.knowledge.processing.enabled=true`、`weknora.knowledge.upload.enabled=true` 默认关闭，桥接上传归属仍Go；只有禁用增强处理的受控基础链可用，不能计完整上传路由。

模型客户端新增单次图片拒绝重试、session/cache affinity与UTF-8请求头（32个Go案例、52次请求），以及原生Anthropic Messages HTTP/SSE（558个Go构造、82个实际HTTP/SSE场景、thinking签名逐块回放及raw工具参数）。原生Ollama chat/NDJSON、embedding和availability/pull也已落盘，78个Go场景/355个实际HTTP与3项新增测试通过；Float32原始位精确对照。signed auth明确拒绝；Ollama typed SDK binder已按1,498个实际Go解码场景和236个实际HTTP场景/987请求验证，包含原78场景，不累加重复数量。其他厂商SDK binder仍需对照。公开推理/RAG编排、其它模型协议、本地管理/代理/storage-image仍待迁移。源文件路径与声明包名已一致，`backend-java/.gitignore`覆盖根目录二进制同名规则，新增Java源码可以被Git纳入。

知识与分块原生读取已接入四条GET：知识库知识列表、知识详情、分块列表和分块by-id。584个实际Go HTTP完整DTO案例通过，含共享owner范围、组织/Agent权限、撤销、API Key retrieve范围、标签/文件夹/分页、profile/source_locators类型解码和隐藏内部字段。公开写入/下载/search/batch/分块detail仍按现有归属处理。SQL驱动错误文本及其他时间/存储JSON边界仍待审计，四条读取路由保持in_progress，未宣告完整知识模块替换。
