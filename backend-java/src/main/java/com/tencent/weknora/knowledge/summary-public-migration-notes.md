# 公开文档摘要重新生成

目标接口：`POST /api/v1/knowledge/:id/regenerate-summary`。状态为 `in_progress`，属于全部 Go 功能严格对齐目标中的一个增量，不代表文档生命周期或全部主服务已经迁移完成。

## 行为与权限

- 保留 Go 的两条路径：`summary_status` 为空或 `none` 时同步生成，其他状态通过 `summary:generation` 异步刷新。忽略请求体，不增加 JSON 绑定要求。
- API key 先检查 `ingest` / full access，再检查 KB allow-list；普通账号沿用 KB creator 或 Admin 和共享 Editor 授权。共享文档始终使用 owner tenant，拒绝审计保留原 caller 与真实路由模板。
- 异步刷新在入队前写 `pending`，使用 `summary` 队列、3 次重试、30 分钟超时、当前构建 attempt、语言与 `refresh=true`。入队或配置检查失败写 `failed`，公开调用返回错误。元数据编辑触发的刷新继续保持 best effort，不能因刷新失败回滚编辑。
- 同步生成只使用启用的 text chunks，保留实际 prompt/options、图片上下文、内容不足判断、profile 解析及模型失败后首片段回退。模型返回后校验内容修订、启用状态、元数据及取消，过期结果不发布。
- 只更新摘要负责的字段，不覆盖原文、解析状态、任务 counter 或配额。已有摘要 chunks 原 ID 复用，重复摘要按 Go 行为全部更新；没有摘要时新建一条。描述和 chunk 更新在索引写入前提交，后续索引失败保留相同的已提交 checkpoint。

实现：[Controller](KnowledgeSummaryRegenerationController.java)、[Service](KnowledgeSummaryRegenerationService.java)。队列生产者复用 [KnowledgeSummaryRefresh](KnowledgeSummaryRefresh.java)，任务执行复用既有 summary worker。

## 已取得的证据

2026-10-16：

- 独立运行 Go 生产 handler、middleware、service、repository、真实 Asynq/miniredis 与受控 Chat/Embedding HTTP，导出 56 个 SQLite 场景。Java 对照完整 HTTP 状态/DTO/错误、模型请求、SQL checkpoint、摘要片段、索引记录、队列参数和拒绝审计，全部通过。只归一化经过时间解析的动态更新时间和新建 UUID；没有删除业务字段来制造一致。
- 新鲜专项组合共 257 项 / 5 类、失败/错误/跳过均 0：新接口 56、完整 Spring 认证/真实索引链 7（含继承的元数据更新 3）、原 summary worker 44、元数据更新 112、写授权 38。日志 `/tmp/weknora-java-summary-public-20261016-focused-first.log`。
- 独立 PostgreSQL 容器应用全部 115 个 Go migrations，Hikari pool=1，重新从真实 Go 导出 56 场景并与 Java 原生 halfvec 路径对照，actual exit0 / XML56 全0。日志 `/tmp/weknora-java-summary-public-pg-20261016-fourth.log`，artifact `/var/folders/pf/6ssfy5pj7fj9w1jyg4kpbmcr0000gn/T/weknora-postgres-summary-public-g71p3dmg`。
- PostgreSQL 最初三次未通过：首轮 fixture 未删除前例故障触发器，第二轮测试使用 bytea 绑定 JSON 参数，第三轮两端 chunk sequence 未在每例重置。修正测试隔离/绑定/确定性初始化后通过；没有修改 Go 生产行为、忽略序列字段或放宽预期。原始日志和 artifacts 保留。

重跑 PostgreSQL：`python3 backend-java/scripts/postgres-summary-public-parity.py`。该脚本只操作自己创建的容器并在 finally 停止它，不连接开发数据库。

完整构建和同 jar 两拓扑进程验收结果继续记录于 [VALIDATION.md](../../../../../../../VALIDATION.md)。新增进程模式为 `python3 backend-java/scripts/processing_smoke.py /path/backend.jar /path/vec0 --topology single --regenerate-summary`，另用 `--topology go` 验证另一布局。

最新完整构建实际exit0：冻结1,154个输入，163类/7,914项，0 failures/errors/skips，显式包含公开向量存储IT133+6项。日志 `/tmp/weknora-java-summary-public-20261016-full-second.log`，报告 `/tmp/weknora-java-summary-public-20261016-full-second-build/surefire-reports`。首轮漏传 `weknora.es.url` 造成133项非空断言和6项空地址安全校验失败；用自己创建的ES8容器补齐配置后，同份源码重新clean verify通过，最后仅停止该容器。首轮日志 `/tmp/weknora-java-summary-public-20261016-full.log` 和XML保留，没有修改业务或断言来通过测试。

固定jar为 `/tmp/weknora-java-summary-public-20261016-full-second-build/backend-java-0.1.0-SNAPSHOT.jar`，152,949,857 bytes，SHA256 `4b4d3e39ba3304243198bae9d8a9a970ffbd7166daaeb05ab24e5819e8bc85bd`。同jar `--regenerate-summary` 的single/go两种SQL worker布局均actual exit0，每种2上传/8个成功任务/8embedding HTTP/6chat HTTP，Hikari1、bridge=false。覆盖own creator-Viewer/shared Editor、无JSON绑定要求的同步生成、summary ID复用、异步刷新及owner/attempt/语言/queue/retry/timeout、受控入队失败后failed状态和已有索引保留。真实vec原始float32字节、FTS MATCH、正文chunks/源文件/配额保留均断言。根进程另读两份真实数据库确认8任务全部成功、2个故障注入后的summary failed状态、4条物理向量及2条摘要FTS命中；故障触发器已移除。

两模式日志 `/tmp/weknora-java-summary-public-20261016-package-{single,go}-first.log`；manifest `/tmp/weknora-java-summary-public-20261016-package-manifest.json`。验收后1,154个输入、runner及jar哈希再次独立核对不变。该jar当前只有上述2种新流程的进程验收，不与旧jar的6种或40种合并，也没有自动部署到8081。

## 尚未验收的范围

其他绑定/外部向量引擎、Redis/Asynq 接管和完整 Lite 同步执行器，以及全部模型协议、并发故障窗口和生产切流仍需后续全量迁移验收。Java `topology=go` 只是 SQL worker 布局；本增量不将该接口或整个文档模块标记为 `verified`。已运行的8081开发服务仍是先前的jar，没有自动重启到本次改动。
