# PostgreSQL / ParadeDB 原生适配器

2026-10-14：`PostgresIndexRepository` 接入生产 document/table-summary/document-summary 的 `IndexWriter` 选择器，使用现有 Go `embeddings` schema；不在构造时运行数据库迁移。绑定外部 vector store 仍明确拒绝。

已实现批量 last-source-wins/upsert、halfvec 写入、全文与向量检索、source/chunk/document 删除、禁用/标签、移动、复制、存储大小估算。复制保留生成问题的 source 后缀，使用 500 行 keyset 分页和冲突忽略。写入加入调用者持有的 fenced transaction；公开业务层授权仍由调用者负责。

## 实库差分证据

可复现命令（Docker 和 Maven/Go 必须可用）：

```sh
python3 backend-java/scripts/postgres-index-parity.py
```

脚本创建自己的随机名称容器、随机 loopback TCP 端口、合成凭据和 `weknora_parity` 数据库，应用实际 `000002`/`000007` embedding migrations，执行生产 Go repository，再以 Java `PostgresIndexGoParityIT` 逐步重放，最后停止该容器。Go generator 与 Java IT 均拒绝重置其它数据库名。测试输出和日志保留在临时 artifact 目录。

本轮独立全流程通过：`paradedb/paradedb:v0.22.6-pg17`，**16 个变更场景、2,368 次检索**；实际 Hikari 最大连接数 1，完整 metadata/halfvec 文本、返回字段、null、数组顺序、分数与 SQLSTATE 比较。没有丢弃同分结果或忽略 ID/排序。内容含 Unicode、NUL 清理、不同维度、禁用行、AND 范围过滤、无向量/halfvec 溢出失败、重复 source/upsert、问题 source 复制、重复复制和移动后删除。

- ParadeDB 分数为 float4，JDBC 用 `getFloat` 后拓宽，与 Go pgx 数值一致。
- SELECT/ORDER BY 复用一个向量 SQL 参数，避免不同参数破坏表达式匹配和同分排序；使用可内联的单行 derived table。
- 向量搜索保留 Go 的候选扩展、阈值、HNSW `SET LOCAL` 和旧 GUC 回退逻辑。
- 任意数字比较仅采用既有 fixture helper 的 double ULP 容差；其它字段/顺序严格比较。数据库时间戳未被当成固定时钟 oracle，本测试不比较 created/updated 时间。

最终成功日志 `/tmp/weknora-java-postgres-isolated-replay.log`，独立 artifact `/var/folders/pf/6ssfy5pj7fj9w1jyg4kpbmcr0000gn/T/weknora-postgres-index-parity-emaqijnt`。此前失败日志用于定位 float4 拓宽和重复向量参数，不替代最后完整成功运行。

## 完整 Go schema 上的原生流程验收

`scripts/postgres-processing-smoke.py` 创建自己的全新 ParadeDB 容器，应用全部 **115** 个 Go up migrations 与显式 Java task schema，再执行 `PostgresProcessingPipelineIT`。Hikari1、bridge=false：4 次 owner/共享 multipart 上传、10 个持久化任务、6 次 Embedding HTTP、2 次 Chat HTTP全部通过，核对 halfvec、summary/profile JSONB、完成状态/counter、原 actor 审计、spans/stages/index-status HTTP。最终日志 `/tmp/weknora-java-postgres-final-processing.log`，artifact 路径在日志中，容器已自动停止。

这证明受控原生成功流程可以连接完整 PostgreSQL schema；不声称整个文档处理器的 Go/Java 差分已经通过，亦不包括表格任务、失败/取消/断连/重启和所有公开写入口。索引详情 HTTP 的完整 PostgreSQL oracle 仍待补齐。

## 未完成验收

仍为 `in_progress`。公开 hybrid-search/RAG/FAQ/编辑等调用链未接线；全部格式/增强任务的 PostgreSQL 上传、索引详情 HTTP 的完整差分、1024/798/3584 维大数据 HNSW 实际计划/召回、老版本 pgvector GUC 回退、故障/超时/断连、分页超 500 行和随机复杂 source 的复制、独立外部绑定库、schema 初始化和切流未完成。

Go adapter 的 SQL 会在同分且候选裁剪边界没有稳定 ID 排序时允许数据库决定顺序；本批小数据 fixture 逐项严格通过，不能据此保证所有大数据同分边界都确定。内部 SPI 空 kind 可执行两个检索器；Go repository 的单次 `Retrieve` 要求指定 kind，该扩展不是公开接口兼容声明。
