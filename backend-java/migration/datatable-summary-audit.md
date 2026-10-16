# 当前表格摘要原生进展（2026-10-14）

生产 `DataTableSummaryHandler/Repository` 已注册并接入模型、DuckDB、实际索引与 SQL worker。40 个正式 JUnit 测试通过：37 个真实 Go 全处理器回放、单连接清理、取消与表格后接文档发布回归。先完成的表格摘要不再被同一次文档构建清理；Go/Java 两端已修复，Go 重解析前置清理仍删除旧 attempt。DuckDB JNI 提前加载已进入 Java main，最终 jar 单池与六池的 Office 7-job 验收均通过，每次 3 上传/7 成功任务/4 Embedding HTTP/2 Chat HTTP/1 受控 reader，真实 table chunks/index/link/source locator 均保留；日志 `/tmp/weknora-java-final-office-{single,go}.log`。受控 reader 不等于原生 Office 内容转换。以下为历史审计，不能把历史“没有 handler”当作当前状态，也不能把未完成运行提前记成通过。

# 表格摘要生产缺口

2026-10-13，Excel里程碑 Java 构建为 3,088 tests / 84 classes，全绿；这不证明表格上传完整完成。

`scripts/processing_smoke.py --topology go --office` 对实际 jar、生产 Go schema SQLite、共享 foreign Contributor、Go BLOB 模型参数、真实 HTTP reader/Embedding 和 vec0/FTS 执行三个上传。三个 document jobs 和三个 postprocess jobs 成功，但第七个 `datatable:summary` 因没有注册 Java handler 进入 dead。失败日志 `/tmp/weknora-java-excel-processing-go-office.log` 保留。独立数据库检查确认自定义 XLSX 的 `3.1400`、sheet/row/quote/source_hash、Embedding 行、owner tenant 与原 actor 审计均正确，见 `/tmp/weknora-java-excel-packaged-source-inspection.log`。这个结果只能证明已迁移的 source/格式处理。

完整迁移须保留以下实际 Go 链路，不能通过忽略 summary job、改名任务或拼接简化摘要宣告成功：

1. `internal/application/service/extract.go:131` 的任务 payload、summary 队列、MaxRetry(3)、30 分钟 timeout 和 tracing。表格任务独立于普通文档 summary 开关；旧记录 file type 为空时按 file name 回退。
2. `DataTableSummaryService.Handle` 的 tenant/knowledge 查询、已删除或任务租户下不存在知识的跳过、错误分类、资源准备、scope 校验及初始故障副作用。
3. `prepareResources` 的 KB、tenant、summary/embedding 模型、存储实例与完整检索引擎选择。使用实际生产 resolver，不能以 fake repository/provider 的更宽能力代替。
4. `processTableData` 的 DuckDB 会话 `table_summary_<knowledge ID>`、表格加载、schema、`SELECT * FROM "dataset" LIMIT 10`、sample JSON 和 cleanup。`internal/agent/tools/data_analysis.go` 使用模型表名 `dataset`，物理知识 ID 表名不得暴露到摘要 prompt。
5. 两次 Chat：temperature 0.3、thinking false，table description max tokens 512、column description 2048；完整原 prompt、process override 自定义指令、有效输出校验与 Go错误顺序。原文结构提取不等于完整表格加载/类型推断。
6. `buildChunks` 的两种 chunk `table_summary` 与 `table_column`、父子/前后关系、Stored→Indexed 状态、完整字段。索引后更新和失败时知识标记/条件清理/删除索引须按 Go 真实副作用对照；同时保留 Java lease/source fencing 的明确差异说明。
7. 实际 Go handler/service、生产 SQLite/存储、受控 Chat/Embedding HTTP 和真实 DuckDB 对照；再执行当前 strict Office packaged smoke。它要求七个任务全部成功，任何 dead job 都会失败。

现有 Go 测试入口：`extract_data_table_summary_test.go`（prompt/sample/output）、`extract_data_table_orphan_test.go`（缺失/删除/scope/context/fault）、实际 `DataAnalysisTool.LoadFromKnowledge` 与 `Cleanup`。这些是后续 fixture 来源，尚没有据此声称 Java 表格摘要已实现。PostgreSQL和外部存储也仍需完整验证。


2026-10-13 后续进展：新OIDC/Agent BLOB完整里程碑为3,405 tests / 89 classes，仍未注册datatable handler。Root真实Go DuckDB probe与Java JDBC 1.5.2.0均返回 `v1.5.2 / 8a5851971f`，spatial/excel可加载；`/tmp/weknora-datatable-loader-fixture.go`调用实际Go DataAnalysisTool、生产local FileService、schema/query/Cleanup，私有sample helper通过Go overlay调用原service函数。10个CSV/XLSX案例与 `/tmp/weknora-DuckDbTableSession.java` 的真实Java JDBC stage完全相等：leading zero、空值`<nil>`、BOM/Unicode/HTML escape、多行、重复header、多个异构sheet UNION BY NAME、sheet单引号/custom format、物理表清理与source保留。证据 `/tmp/weknora-datatable-loader-java.log`。这些仅/tmp，未加入main/POM/正式JUnit；不计入3,405，不宣称完整summary handler。下一步还须原prompt与两次Chat、chunk/index生命周期、scope/lease/source/失败清理和严格Office进程验收。


2026-10-13 表格基础与文档并发修复（完整 handler 尚未接线）：

- `DuckDbTableSession`、`DataTableSummaryText` 已正式进入 Java main；POM 固定 JDBC 1.5.2.0，两份 pinned MIT notice 已打包为资源。原始 Go 的两个 prompt 为 `datatable-prompts.json`。未注册 `datatable:summary` handler，因此不能以此宣称第七个 Office job 成功。
- `DataTableCompatibilityTest` 的 10 个真实 DuckDB/schema/sample/cleanup 与 28 个真实 Go handler Chat 请求/prompt/options/原始输出动态测试通过。CSV/Excel extensions 是实际 DuckDB `LOAD spatial/excel`，需要本机匹配版本的扩展缓存；尚未实现生产 extension 初始化/故障处理、SQL 执行期间的取消或 task deadline。
- `scripts/generate-datatable-oracles.py` 可从 repository root 重放原始 Go loader 与生产 `DataTableSummaryService` 37 场景，使用实际生产 SQLite schema/repositories/services/local storage/Chat HTTP/Embedding HTTP/vec0/FTS。Loader 的 immutable knowledge lookup 是 stub；37 handler 场景使用 production lookup。Go overlay 仅暴露原 service 私有 sample helper，不改业务逻辑。
- 37 场景涵盖 scope/orphan、KB/tenant/models、CSV/多 sheet Excel、原始 prompt/业务 instructions、两种 chunk/link/status、Chat/Embedding/SQL 失败、知识变更 CAS、软删除失败与索引清理失败。它们目前是完整 handler 的验证依据，JUnit 仅迁移了其中 Chat 文本部分；尚未用 Java 对照完整分块/索引副作用。
- 修复 document 事务提交删除整个 knowledge 所有 chunks/index 的竞态：在远程 embedding 前捕获既有 chunk IDs 与 index source IDs，成功提交仅替换这些行。新增测试以两个发布时间安排验证真实 chunks/vec0/FTS，包括无 chunk 的旧 orphan index；既有 source/lease fencing、事务回滚和 Office source tests 保留。Native document 仍采用原有原子提交/回滚，与 Go 先清理旧数据再索引的失败行为不同，不能把该测试声称为两种 handler 的全链路并发测试。
- Go 的 CompositeRetrieveEngine 去重后以 map 顺序生成 embedding input，顺序可随重放改变；比较须验证每个向量与其实际 captured input/content 关联，再规范化批内顺序，不能将随机顺序本身当 Java 排序规则。Excel ZIP timestamp 与 file-missing 的随机 temp path 也须单列。
- Go chunk cleanup 是软删除。实际 cleanup-index-error 会留下 metadata，但 vec0 向量已删；原 fixture 对 soft-deleted IDs 的投影曾漏掉此状态，generator 已修正为使用 unscoped IDs，并显式记录 `vector_error`。完整 Java handler 需要验证这类部分清理，不能默认扩大为单一全局事务。

专项日志：`/tmp/weknora-java-datatable-boundary-fixed-test.log`（10 tests）；`/tmp/weknora-java-datatable-loader-test.log`（52 tests，包括 38 新表格动态场景、8 document、2 Office、4 SQLite index，零失败/错误/跳过）。完整 verify 运行中，不能预先更新已完成数量。
