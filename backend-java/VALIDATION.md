# 迁移验证记录

最新完整里程碑：2026-10-13 表格基础与文档并发修复，3,444 tests / 90 classes，172 次实际后端 HTTP 检查通过。下方按时间保留历史运行，最新完整记录见文末。

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
