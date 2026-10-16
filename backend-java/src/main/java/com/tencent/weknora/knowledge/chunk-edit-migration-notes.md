# Chunk 编辑、回滚与索引重建

原生实现 `PUT /api/v1/chunks/:knowledge_id/:id` 和 `POST /api/v1/chunks/:knowledge_id/:id/revert`，由 `weknora.knowledge.processing.enabled=true` 启用。两条路由保持 `in_progress`，bridge 白名单未扩展。完整 Go 功能迁移仍在进行。

## 行为与数据

使用实际文档的知识库授权；API key 必须具有 ingest 且符合 KB allow-list。自己的 KB creator Viewer 可以编辑，共享 Editor 以 source tenant 执行，保留原 actor。授权与 chunk/document 归属检查先于 JSON 绑定。未完成移动、错误 KB、父子跨文档/租户均拒绝。

保留 Go 首个 JSON 值、pointer null/遗漏、重复字段、大小写、类型错误与 int64 绑定。只编辑 text；内容 Go Trim 后不得为空，最大200000字节。expected_revision 冲突返回409；正常 no-op 不建新版本，failed no-op 重建索引但不增加版本。

内容与启用状态被接受后，以同一事务保存新 revision 和不可变旧版本快照。首次编辑保存 source_content；正文变化清除 source_locators。Unicode 父段使用原始坐标叠加，重叠时保留最新覆盖并拼入未包含的冲突正文。编辑不能新增原文没有的图片 URL；既有 OCR/caption 子段按 Markdown/HTML 和 image_info 的 URL 启停，回滚可恢复。

接受版本的事务先提交，随后执行图片/父段更新、摘要任务接收和同步索引重建。普通索引故障保留编辑并返回200、index_status=failed；模型重试沿用 Go 5次及退避。保留原有问题元数据及其 source ID，重复问题 source ID 取首次，长 ID 使用相同 hash。正文/问题使用实际模型 HTTP，校验 float32 和响应 index 位置，再写物理向量及全文索引。

摘要刷新复用 `KnowledgeSummaryRefresh` 与实际生产 worker，沿用 source owner、attempt、language、queue/retry/timeout。无模型或入队失败保留编辑。原文文件、资源、配额和解析状态不因 chunk 编辑重建。

## 并发修正

真实 Go provider 回调复现了旧请求在模型执行后整行 Save，覆盖后续正文、问题 metadata 或 tenant 的缺陷。原始196项复现输出保留于 `/tmp/weknora-go-chunk-edit-concurrency-unsafe196-first.json`。Go 和 Java 均加入模型前后来源校验，并使用带 revision/content/enabled/metadata 及活动文档/KB owner 条件的专属 index_status 更新，避免完成时整行覆盖。

新增12项 edit/revert 并发场景：后续编辑、禁用、问题更新和 document/KB/chunk owner 变更全部返回409，保留新数据；只发一次真实模型请求，不发布旧响应。原184项除已说明的动态字段和 Go map 输入顺序外完全相同。该证据覆盖 provider 窗口；不等于已证明所有外部 store 的跨事务原子性、父段/图片写入的所有交错、授权撤销或请求断开。

## 实测证据

- Go SQLite196实际 handler/service/GORM/model/Asynq 导出 actual exit0：`/tmp/weknora-go-chunk-edit-concurrency-safe196-first.log`；正式 fixture 为 `src/test/resources/go-chunk-edit-http.json`。
- Java196差分和6完整 Spring 测试 actual exit0，独立 XML202/2类、failure/error/skip均0：`/tmp/weknora-java-chunk-edit-binding-first.log`。Spring 经真实 JWT/API key、Hikari1、上传、共享编辑、回滚、摘要刷新与 failed no-op 修复；源文件/配额和物理索引逐项断言。
- PostgreSQL 使用自有临时库、全115个 Go migrations、Hikari1，另跑实际 Go 与 Java196对照 actual exit0、独立 XML196全0：`/tmp/weknora-java-postgres-chunk-edit-binding-first.log`，artifact `weknora-postgres-chunk-edit-gfy1vn9h`。断言真实 halfvec float32 位、每个 source/type 的物理 metadata、完整 HTTP/SQL/版本及实际模型/Asynq；GIN sentinel 仅证明独立表保留，不代表公开 BM25 检索验收。
- 全 Go 回归 `go test -tags sqlite_fts5 ./...` actual exit0：`/tmp/weknora-go-chunk-image-full-first.log`。这是运行时源码的证据；之后仍需对新增代码重新验收。

差分只规范化合法动态时间/版本 UUID、SQL bool/JSON 表示与每批模型输入的无序 multiset。Go Composite map 的顺序由独立重跑证实不固定；模型选项、调用次数、输入身份及每个 source/type 的实际向量位保持严格比较。

首次124差分中的 SQLite 每连接 extension 漏加载、普通 Go not-found 的 PUT500/POST400、failed no-op 时间戳，176扩展中的 parent NULL 变空串和无效 BLOB 策略，以及并行编译暂态失败，均保留原日志后修正。SQLite TEXT indexing_strategy 触发 Go Scanner legacy 默认、BLOB/PG JSONB 才严格解析的现有行为在此切片局部兼容，不更改全局配置语义。

后续已纳入7538项/154类冻结候选的完整clean verify与同jar36种最终模式，其中chunk-edit single/go均actual0。公开PG完整上传后edit/revert/BM25/halfvec生产链已通过，后续含问题增删/重新生成扩展为10上传48任务38embedding27chat；日志 `/tmp/weknora-java-postgres-chunk-question-regenerate-pipeline-fourth.log`。PG NULL metadata和JSONB语义绑定已分别修正，最初失败仍保留。

当前仍待所有绑定/外部store，以及取消/撤销/迁移/父子写入的更广泛并发验证。7538候选之后的增量需用新的冻结构建验收，不能据专项宣称全部482条Go路由已迁移。
