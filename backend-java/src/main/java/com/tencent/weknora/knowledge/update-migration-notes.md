# 文档部分更新与元数据摘要刷新

本切片实现 `PUT /api/v1/knowledge/:id`，开关 `weknora.knowledge.processing.enabled=true`。保持 in_progress；未扩充 bridge 白名单，也未实现公开 regenerate-summary 接口。完整 Go 后端替换仍在进行。

## Go 行为与 Java 接线

API ingest/full 检查在资源查询/绑定前，full 仍受 KB allow-list 约束。自己的 KB 要求 creator 或 Admin；creator Viewer 可以更新。共享 KB 要求 Editor，原 actor 和执行 owner 分开；拒绝审计使用 `/api/v1/knowledge/:id`。handler 检查文档的真实 tenant/KB，服务在保存前检查未完成移动和实际所属 KB。

title/description 是可选 pointer string；遗漏/null 不修改。空 title 保留已有标题，纯空白标题允许；空 description 清空说明并设 summary_status=none。JSON 绑定保留首个值、大小写兼容、重复字段/null 和首个 typed error 的 Go 行为；不增加原 Go 没有的标题 HTML 清理。

custom_metadata 是 RawMessage：遗漏保留，显式 null 与空对象不同。限20字段、64字节键、fmt.Sprint 后1000字节值，允许 string/float64/bool/null。验证数字使用 Go float64 语义，字段顺序、1/1.0、+0/-0、超过53位整数按 float64 比较；已有错误的数值映射保留 Go 的部分解码行为。SQL 保存输入原始 JSON，返回值使用 RawValue 保留数字精度和下溢值，而非经过 double 重写。Go 的 %q 控制字符错误信息也已对照。

保存编辑后，元数据语义变化且 summary_status 非空/非 none 时调用独立 `KnowledgeSummaryRefresh`。先写 pending，再接收任务；payload 含 owner/KB/document/language/refresh=true，最新 attempt 非0时加入；queue=summary、MaxRetry3（原生4次总尝试）、timeout30min，无默认去重键。无模型/入队失败将摘要设 failed，但保留字段更新并返回200。未改变正文 chunks/源文件/配额，也不立即重建正文索引。刷新 worker 重用已有摘要 chunk ID，重新发布摘要索引；不会消耗新的 postprocess pending counter。

## 实际验证

- 实际 Go handler、middleware、service、GORM、miniredis/Asynq 导出112个 SQLite 场景。最初79个结果保留且新导出除合法动态 updated_at 外相同。输入/输出/SQL/审计/任务完整对比；数字精度用 BigDecimal/BigInteger 保留，只有合法动态 RFC3339 时间和 Go encoding/json 对非成对 surrogate 的解码被规范化。
- 最新115项/2类实际 exit0、独立 XML failure/error/skip 全0：`/tmp/weknora-java-update-pipeline-third.log`。112差分和3生产 Spring（JWT/API key/Hikari1、实际上传、实际summary模型/vec/FTS、排队失败持久化）。既有585 content-read 和38 WriteAuth 在前一738项运行中全部通过；该运行唯一新 RawMessage surrogate 失败已保留。
- 全115 Go PG migrations、owned loopback DB、Hikari1，另导出真实 Go PG112并严格对照：`/tmp/weknora-java-postgres-update-third.log`、artifact `weknora-postgres-update-bvn5khmc`，实际exit0、独立XML全0。此处 GIN/tsvector 保留仅是 sentinel；生产 ParadeDB/halfvec 证据来自下一项。
- 全 PG 生产链实际exit0：`/tmp/weknora-java-postgres-update-pipeline-third.log`、artifact `weknora-postgres-processing-silefky_`，10上传/44任务（42成功2取消）/22Embedding/19Chat。own creator Viewer 和 shared Contributor 更新实际 JSONB，经 durable queue/真实模型/halfvec 刷新，保留正文 SQL/索引/源字节/配额/计数及摘要chunk ID。
- 隔离1075个非md文件冻结候选 jar；clean package -DskipTests 实际exit0，**不是完整 clean verify**。152772323bytes，SHA256 `7708363c7d9c4eef2448637cb307cbb5d46d9f27a1889ddbdd1df2fb26300a30`。同jar single/go 各实际exit0（2上传/8任务/6Embedding/4Chat）；provider barrier 证明 pending 后执行真实刷新，payload/attempt/language/options 与源vec/FTS/字节/配额保留逐项断言。`/tmp/weknora-java-update-candidate-source-proof.json` 记录源码/包哈希，验收后独立核实不变。

## 失败记录和范围

初次79差分发现 SQL NULL file_size 被 Java 投影成空字符串；Go 为数值0，已修正 `KnowledgeRepository` 数值空值投影。初次生产测试固定内容命中真实去重，且保留查询误包含摘要索引；只改 fixture 独立内容和正文/摘要谓词。扩展 fixture 误种 SQL NULL custom_metadata 违反 NOT NULL，已改为真实可存储 JSON string 异常类型。新 PG44流程前两轮中间 task-count 漏加实际新增2个刷新，保留失败后修正计数。原失败日志和原 Go 预期未删除/重写以掩盖结果。

本批在稳定6511项不可变 jar 之外。完整共享 clean verify、公共摘要再生成的无摘要同步路径、Langfuse、所有外部/绑定引擎、撤销/重定位与并发写/delete边界、进程重启/Lite同步/Redis积压切换仍待验收。多个非法 map 字段的 Go 首错误顺序是未排序的 map 遍历，未宣称确定顺序一致。Java 使用专属列更新避免覆盖处理计数；此处未宣称与 Go 整行 Save 的所有并发交错完全相同。
