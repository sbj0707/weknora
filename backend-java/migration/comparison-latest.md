# Go / Java 当前功能对比

2026-10-16 重新核对当前工作区。**功能不一致，Java 尚不能完整独立替换 Go。** 本次重新执行 Go AST / Java Controller / TaskHandler 扫描，并检查实际业务分支与测试报告；没有部署、重启开发服务或切换前端。

本次复核更新（2026-10-16 22:34，当前 HEAD `e1b378d`）：重新运行入口扫描，仍为121/482、缺361、15/22种部分任务实现，无清单漂移。当前1074个src/pom输入；相比791项通过时新增6个、修改5个，**791项不能作为当前源码全部通过的证明**。FAQ复制已新增内部执行草稿，但未接Controller/worker；最近现有专项54项均断言失败、退出1，本次只检查报告，未重跑测试。首个差异为SQLite布尔表示true/1；仅做诊断性统一后51个首次尝试快照一致，余下3个首差异为索引条目顺序，不能宣称正式通过或全部重试行为一致。以下保留历史专项证据并注明其快照范围。

## 接口与任务覆盖

| 核对项 | 结果 |
|---|---|
| Go HTTP 接口 | 482 条；480/480 个注册位置已遍历，无未解析项 |
| 有对应 Java 原生入口 | 121 条，入口覆盖率 25.1% |
| 缺少 Java 原生入口 | 361 条 |
| Java 显式方法/路径组合 | 125 个；通用 KV 路由按固定 key 拆分，因此不等于 125 条 Go 接口 |
| 后台任务 | Go 22 种，Java 15 种有部分 handler，7 种无 handler |
| 迁移清单 | 361 not_started、120 in_progress、1 health implemented、0 verified；与入口扫描无状态漂移 |

“有入口”只表示存在对应 Controller 代码，不证明配置启用后完整注册，更不证明权限、输入输出、全部存储引擎、副作用与失败恢复已经对齐。**25.1% 不能当作功能迁移完成率。** 清单 verified 为 0 也不表示没有专项测试通过，而是没有业务接口完成全部范围的严格验收。

## 主要模块

| 模块 | Java 原生入口 / Go 接口 | 当前缺口或边界 |
|---|---:|---|
| 认证 | 16 / 17 | 缺 auto-setup；平台 API key、外部 API principal 未迁移 |
| 租户 | 20 / 26 | 创建/删除等仍缺；API key 权限和通用 KV 未全覆盖 |
| 知识库 | 11 / 13 | 缺内容复制、复制进度；配置 duplicate 不等于内容复制 |
| 文档 | 32 / 32 | 入口齐；音频/图片构建、外部向量库写入、事务和计费仍有差异 |
| chunk | 10 / 10 | 入口齐；全部副作用、引擎组合与生命周期尚未完成验收 |
| 文档标签 | 4 / 4 | 入口齐；不能据此判定完整行为一致 |
| 模型 | 10 / 12 | 缺 copy/debug；API key、协议、代理和模型供应商能力未全覆盖 |
| 向量存储管理 | 8 / 8 | 管理入口齐；实际引擎、连接管理与文档写入能力不齐 |
| 会话、聊天、消息 | 0 / 44 | Java 原生公开主链缺失；内部模型客户端不等于公开聊天功能 |
| Wiki、FAQ、Memory | 0 / 51 | Java 原生公开模块缺失；已有内部组件不算完整模块 |
| 自定义 Agent | 0 / 9 | Java 原生公开模块缺失 |
| 数据源 | 0 / 17 | Java 原生公开模块缺失 |
| 组织管理 | 0 / 35 | Java 原生公开模块缺失 |
| MCP | 0 / 32 | Java 原生公开模块缺失 |
| 沙盒配置、桌面、终端 | 0 / 23 | Java 原生公开模块缺失 |
| embed | 0 / 29 | Java 原生公开模块缺失 |

其他缺口包括 IM、系统管理、初始化、存储后端、WebSearch、文件授权路由等。完整模块清单、121 条匹配和 361 条缺失接口都保存在 [机器可读对比](comparison-latest.json)。

缺少的 7 种后台 handler：`datasource:sync`、`faq:import`、`kb:clone`、`memory:extract`、`temporary_document:process`、`wiki:finalize`、`wiki:ingest`。已有 15 种只是部分实现，不能据此推断重试、取消、幂等、死信、租约和接管全部一致。

## 已有入口中的实际差异

| 范围 | 当前源码表现及影响 |
|---|---|
| 文档失败后的状态 | Java 将 chunks、SQL 索引、状态、后处理入队放进同一事务；Go 分阶段发布，中途失败的可见结果不同。[Java](../src/main/java/com/tencent/weknora/knowledge/DocumentProcessingRepository.java)、[Go](../../internal/application/service/knowledge_process.go) |
| 存储计费 | Java 按 `storageBytes - oldSize` 更新用量；Go 文档处理累加新用量，重试/重解析语义不同。参见上述两个文件。 |
| 音频、图片构建 | 普通文档 worker 明确拒绝本地音频 ASR、本地图片文档及解析结果中的音频/图片增强；也拒绝特定 inline content 任务。这与已有独立图片编辑/多模态 handler 是不同路径。[Java](../src/main/java/com/tencent/weknora/knowledge/DocumentProcessingHandler.java)、[Go ASR](../../internal/application/service/knowledge_process.go) |
| 绑定外部向量库 | 普通文档处理对非空 `vector_store_id` 报未迁移；绑定检索引擎工厂只创建 Elasticsearch 8，其他引擎不齐。[索引选择](../src/main/java/com/tencent/weknora/knowledge/KnowledgeProcessingConfiguration.java)、[工厂](../src/main/java/com/tencent/weknora/vectorstore/VectorStoreConfiguration.java) |
| 云文件存储 | Java `StorageResolver` 仅支持 local；Go 有 MinIO/COS/TOS/S3 等实现。使用现有云存储配置不能直接切换。[Java](../src/main/java/com/tencent/weknora/storage/StorageResolver.java)、[Go](../../internal/container/container.go) |
| 认证模式 | 平台 API key 和 external API principal 模式被明确拒绝；部分模型/租户 API key 路径仍受未迁移限制。[认证](../src/main/java/com/tencent/weknora/security/AuthFilter.java) |
| Wiki 与文档生命周期 | 文档删除、移动后的 Wiki 源清理/目标 ingest 仍有未迁移分支；相关 Wiki handler 也缺失。[移动](../src/main/java/com/tencent/weknora/knowledge/KnowledgeMoveConfiguration.java)、[删除](../src/main/java/com/tencent/weknora/knowledge/KnowledgeListDeleteRepository.java) |
| 队列接管 | Java 使用 SQL `java_task_jobs`，不能直接消费 Go Redis/Asynq 存量任务；`topology=go` 仍是 Java SQL worker 池布局。[任务配置](../src/main/java/com/tencent/weknora/task/TaskConfiguration.java)、[任务仓库](../src/main/java/com/tencent/weknora/task/TaskRepository.java) |
| Go bridge | 转发成功的业务继续由 Go 执行，不能算 Java 原生迁移；关闭 bridge 不会补齐缺失业务，且 bridge 不支持 WebSocket。[桥接](../src/main/java/com/tencent/weknora/bridge/GoCompatibilityBridge.java) |

摘要、问题生成、图谱抽取等代码中的部分 “not migrated” 是缺少可选组件时的保护分支；本次没有把所有同名报错简单累计为缺失功能。图谱已有部分抽取/查询实现，但完整问答集成和全部生命周期仍未对齐。

## 最新验证及其边界

| 验证范围 | 已核对结果 | 能证明什么 |
|---|---|---|
| 上批相关回归（历史快照） | 791 项 / 8 类，失败/错误/跳过均 0，实际退出 0 | 新增46个普通复制执行场景，各两次尝试；加文档75、chunk/图片68、复制预检219、移动计划/执行/worker和SQL索引复制回归；1068个构建输入前后哈希稳定 |
| 前批独立 PostgreSQL 复制预检对照 | 219 项 / 1 类，全部通过、实际退出 0 | 全部 115 个 Go 迁移、连接池 1，真实 Go oracle；自建容器最终停止退出 0 |
| 最近完成的默认完整构建 | 10,464 项 / 196 类，失败/错误/跳过均 0、实际退出 0 | 仅对应索引复制批次冻结的 1047 个 src/pom 输入；不含随后新增的复制计划代码 |
| 当前代码的完整构建 | 本次未重跑 | 当前1074个src/pom输入；与冻结全量快照的逐文件差异见机器可读报告，不能称当前全量通过 |
| FAQ复制草稿最新现有专项 | 54项 / 1类，54断言失败、0错误/跳过，实际退出1 | 尚未正式通过；包含布尔类型表示和索引条目顺序差异，未接生产入口/任务 |
| 本批 PostgreSQL chunk复制执行 | 未验收通过 | 首轮63个旧场景断言失败；修正后重跑因Docker镜像/containerd I/O故障未执行，不能用前批219项预检替代 |
| 历史完整构建失败 | 10,443 项 / 195 类，1 项失败、实际退出 1 | Elasticsearch TCP 复用计数预期 2、实际 3，原因尚未确认；后续通过不等于原失败已定位修复 |

复制预检已经验证只读行为、两端全部 chunk/parent/tag 预检、UTF-8 ID 排序、transfer metadata 优先匹配、hash 一对一匹配、状态拒绝等。但 `KnowledgeClonePlanner` 尚无生产 worker 接线，**公开内容复制、复制进度和 `kb:clone` 仍缺失**。文件/图片/chunk及文档复制核心已有有界 SQLite 对照；FAQ diff 执行已有未验收草稿；生产接线、全部索引引擎及完整失败恢复仍待迁移。[复制范围](knowledge-base-clone-audit.md)、[本批 checkpoint](clone-plan-checkpoint-2026-10-16.json)。

前批 SQL 索引复制 SQLite/PG 各 21 场景及既有 PG 16 个变更/2368 次检索回归见 [索引复制 checkpoint](index-copy-checkpoint-2026-10-16.json)。公开知识库删除的 HTTP、真实认证、独立 PG 和同一 jar 的 single/go 布局验证见 [删除 checkpoint](knowledge-base-delete-http-checkpoint-2026-10-16.json)。不同快照、jar 和专项结果不相加当作当前完整验收；默认 Surefire 也不包含另行指定的外部 IT。

本批另新增 `KnowledgeCloneChunks/Repository/Objects`，实现全部类型chunk、标签、图片深复制及失败清理核心。68个实际Go场景既核对image_info原始字符串，也核对SQLite metadata BLOB/locator TEXT类型。尚无生产Effects/公开复制/worker接线。随后文档执行核心见下，FAQ及其他运行时效果仍待完成。[实现范围](clone-chunks-notes.md)、[证据](clone-chunks-checkpoint-2026-10-16.json)。

本批另补 `KnowledgeCloneDocument` 和真实 SQL 文档创建，覆盖源文件独立复制、字段/default/Profile/JSON、最终 CAS/配额及外层失败记账。实际 Go75 场景与相关回归共745项通过；当前1063输入在构建前后哈希一致。修正 NotFound 1003、自定义JSON外围空白和 SQLite transfer metadata BLOB。仍无生产适配器、公开复制/进度或 `kb:clone` 接线；本批未执行 PostgreSQL 对照。[范围](clone-document-notes.md)、[证据](clone-document-checkpoint-2026-10-16.json)。

本批新增 `KnowledgeCloneExecution` 和同步 `KnowledgeReferencedDelete`，实现整批预检后先删目标、再串行复制及阶段进度。46个实际Go场景各两次尝试，与相关回归共791项/8类通过，1068输入哈希稳定。删除失败可能留下deleting并在重试时拒绝，第二篇复制失败则保留此前成功结果。仍无生产适配器/公开复制/worker接线；FAQ、PG、完整SQL Scan、Wiki/资源引用等效果及租户完整typed Save副作用待对齐。[执行范围](clone-execution-notes.md)、[证据](clone-execution-checkpoint-2026-10-16.json)。

## 使用结论

可以继续在隔离开发环境验证 Java 已实现的功能；**目前不具备关闭 Go、由 Java 承担全部现有功能的条件。** 完整替换仍须补齐 361 个缺失入口、7 种任务及已有功能的行为差异，完成对应引擎/存储、并发恢复和切换验收。

可复现入口扫描：

```sh
python3 backend-java/scripts/compare-go-java.py --output /tmp/weknora-go-java-comparison.json
```
