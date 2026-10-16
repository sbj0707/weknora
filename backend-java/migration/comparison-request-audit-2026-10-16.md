# Go / Java 当前功能一致性复核

复核时间：2026-10-16T20:42:39.467339+08:00。**功能不一致，Java 尚不能完整独立替换 Go。**

本次重新执行 Go AST、Java Controller 和 TaskHandler 源码扫描，并核对关键业务分支。没有启动或重启服务、部署、切换前端，也没有使用 Computer Use。

## 覆盖情况

| 核对项 | 当前结果 |
|---|---|
| Go 接口 | 482 条；480/480 个注册位置已遍历，无未解析项 |
| 有 Java 原生入口 | 121 条，25.1% |
| 缺少 Java 原生入口 | 361 条 |
| 后台任务 | 22 种，15 种有部分 handler，7 种缺失 |
| 严格验收清单 | 361 未开始、120 进行中、1 health 已实现、0 verified |

**25.1% 是入口覆盖率，不是功能完成率。** 匹配路径不证明条件配置下实际注册、权限、全部输入输出、存储引擎、副作用和故障恢复一致。

## 主要模块

| 模块 | Java 入口 / Go 接口 | 判定 |
|---|---:|---|
| 认证 | 16 / 17 | 部分迁移，缺 auto-setup 及部分认证模式 |
| 租户 | 20 / 26 | 部分管理接口缺失 |
| 知识库 | 11 / 13 | 缺内容复制与复制进度 |
| 文档 | 32 / 32 | 入口齐，构建和生命周期仍有差异 |
| chunk | 10 / 10 | 入口齐，完整行为尚未验收 |
| 文档标签 | 4 / 4 | 入口齐，完整行为尚未验收 |
| 模型 | 10 / 12 | 缺 copy/debug 等能力 |
| 向量存储管理 | 8 / 8 | 入口齐，实际引擎支持不齐 |
| 聊天/会话/消息 | 0 / 44 | 原生公开主链缺失 |
| Wiki/FAQ/Memory | 0 / 51 | 原生公开模块缺失 |
| Agent | 0 / 9 | 原生公开模块缺失 |
| 数据源 | 0 / 17 | 原生公开模块缺失 |
| 组织管理 | 0 / 35 | 原生公开模块缺失 |
| MCP | 0 / 32 | 原生公开模块缺失 |
| 沙盒/桌面/终端 | 0 / 23 | 原生公开模块缺失 |
| embed | 0 / 29 | 原生公开模块缺失 |

其他模块及全部 361 条缺失入口见[本次机器快照](comparison-request-audit-2026-10-16.json)。

## 已有入口的行为缺口

- 普通文档构建明确拒绝 ASR/图片；不能用独立多模态 handler 代替此路径。[源码](../../backend-java/src/main/java/com/tencent/weknora/knowledge/DocumentProcessingHandler.java#L219)
- 普通文档构建拒绝绑定外部向量库。[源码](../../backend-java/src/main/java/com/tencent/weknora/knowledge/KnowledgeProcessingConfiguration.java#L149)
- Java 单事务发布及差额计费，与 Go 分阶段发布/累加计费不同。[源码](../../backend-java/src/main/java/com/tencent/weknora/knowledge/DocumentProcessingRepository.java#L233)
- 原生文件存储仅 local。[源码](../../backend-java/src/main/java/com/tencent/weknora/storage/StorageResolver.java#L32)
- 平台 API key 和 external API principal 未迁移。[源码](../../backend-java/src/main/java/com/tencent/weknora/security/AuthFilter.java#L115)
- 绑定检索引擎工厂仅创建 Elasticsearch。[源码](../../backend-java/src/main/java/com/tencent/weknora/vectorstore/VectorStoreConfiguration.java#L14)
- SQL java_task_jobs 不接管 Go Redis/Asynq 存量队列。[源码](../../backend-java/src/main/java/com/tencent/weknora/task/TaskRepository.java#L11)
- Wiki 文档生命周期未完整对齐。[源码](../../backend-java/src/main/java/com/tencent/weknora/knowledge/KnowledgeListDeleteRepository.java#L37)
- bridge 转发仍由 Go 执行，不能算 Java 原生功能。[源码](../../backend-java/src/main/java/com/tencent/weknora/bridge/GoCompatibilityBridge.java#L15)

缺少的后台 handler：`datasource:sync`、`faq:import`、`kb:clone`、`memory:extract`、`temporary_document:process`、`wiki:finalize`、`wiki:ingest`。已有 handler 也不等于重试、取消、幂等、死信及切换全部对齐。

## 最新复制实现和测试边界

新增 `KnowledgeCloneDocument` 文档复制 helper 仍为未接线草稿。此前 `KnowledgeClonePlanner`、chunk/图片复制核心也未接入生产复制 worker；公开复制、进度和 `kb:clone` 仍缺失。不能将 helper 文件存在算作可用功能。

本次仅执行接口静态扫描、源码核对及历史报告哈希校验，没有重跑业务测试。已核对此前 670 项/6 类专项、10,464 项/196 类完整构建的报告与记录哈希一致，且当时均无失败、错误或跳过；它们对应此前源码快照。当前有 1060 个 src/pom 输入，相对上一份 1059 输入快照新增 `KnowledgeCloneDocument.java`，修改 `KnowledgeCloneRepository.java`，因此不能宣称当前源码全量通过。

此前 PostgreSQL chunk 复制首轮失败，后续因 Docker/containerd I/O 故障未完成通过验收；本次没有重试或改变 Docker 环境。

**Java 可用于隔离验证已实现范围；目前不能关闭 Go 后覆盖现有全部业务。**
