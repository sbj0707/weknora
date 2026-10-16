# 普通知识库复制执行核心

2026-10-16。新增 `KnowledgeCloneExecution` 和同步 `KnowledgeReferencedDelete`，连接已有只读复制计划与分阶段文档复制核心。全量迁移尚未完成：这些类仍没有生产 Effects、公开复制接口、进度接口或 `kb:clone` worker 接线。

## 本批行为

- 先对两端全部文档、chunk、parent 和 tag 做只读预检，再发布初始进度；任何预检失败都不进入删除或复制。
- 同步删除计划中的多余目标，再串行复制来源；每个阶段独立提交，不用一个事务回滚整个知识库。
- 删除前两次读取当前文档，锁定已获准的 tenant/KB 绑定。已经删除或迁出租户的数据按实际 Go 查询结果处理；未完成 move 被拒绝。
- 每篇目标文档分别 CAS 为 deleting。第二篇 CAS 失败时，第一篇已经提交；不会撤销其状态。
- 索引和图谱删除并发；Wiki 清理位于 chunk 删除之前。等待所有清理分支，再处理标签关系和文档软删除。只有文档行成功删除之后才清理源文件与提取图片。
- 标签关系、文件清理、图片释放、配额更新、Profile 和审计使用 Go 对应的 best effort 边界。配额按删除时重新读取的 storage_size 扣减，并截断到零。
- 删除进度按原计划的移除数量计数，包括执行时已经消失的目标。文档复制成功后才推进一个单位；完整成功后的再次执行报告 0/0。
- 不替 Go 自动修复失败状态。删除中途失败可能留下 deleting，下一次计划会拒绝忙碌目标；第二篇复制失败则保留第一篇已完成结果，并按新的实际计划重试。

## 实际 Go 对照

`export-clone-execution-oracle.py` 通过 test overlay 调用实际 Go `executeKnowledgeClone`、`deleteReferencedKnowledge`、`DeleteKnowledgeList`、`cloneKnowledge` 和 chunk 复制，使用实际 GORM 文档/chunk/标签/租户仓库。每个场景有独立 SQLite 数据库；文件、模型、索引和图谱边界受控，SQL 错误由真实 trigger 产生。

46 个场景各执行两次，比较每次错误、进度、全部选定文档/chunk/标签/关系/租户状态及受控边界事件。包括空集合、已完成 transfer/hash 匹配、预检失败、预检后归属/状态/计费变化、删除各阶段失败、第二篇创建/索引/最终 CAS 失败、源文件复制、图片去重与非法字段、空模型组、配额下限等。

并发比较仅把独立的 delete_graph 事件移到同一清理阶段末尾，保留索引调用之间的顺序，以及文件、复制和进度之间的阶段边界；不移除字段或忽略数据库差异。测试先前错误地只识别 dst-* 为删除目标，导致重试时生成的目标 ID 无法归一化；现按图谱删除的真实 ID 集合识别。

本批 Go 导出和 Java 对照均实际退出 0。相关回归、构建输入哈希及失败历史见 [checkpoint](clone-execution-checkpoint-2026-10-16.json)。这不是生产复制链或当前全部测试的通过证明。

## 仍需完成

- 生产文件/资源目录/索引/图谱适配器及其组合，目标 KB 配置创建/恢复，FAQ diff 执行，公开 admission/进度和 worker。
- 完整 Go SQL Scan 行为及错误优先级、KB/owner/storage 解析边界、所有持久化字段和并发配置变化。当前删除配额只更新 storage_used/updated_at；Go `AdjustStorageUsed` 还会将完整 typed Tenant 保存，其他字段的重编码与 SQL 副作用尚未对齐。
- Wiki 清理、dequeue、资源引用释放、审计和 Profile 的真实运行效果。本 fixture 的 Wiki 关闭，任务/审计为空，图片引用释放允许；不能据此验收这些效果。
- 多引擎组与多个并发清理分支同时失败、取消、租约丢失、崩溃/重启恢复和服务切换。
- PostgreSQL 复制执行。本批只支持 SQLite；导出器会在继承 PostgreSQL fixture DSN 时提前拒绝，避免误用外部数据库。此前 PostgreSQL chunk 复制的失败及 Docker I/O 阻断记录保留，未计为通过。

本批没有增加公开路由或任务 handler。仍为 121/482 个原生入口、15/22 种部分 handler、0 verified。未部署、重启服务或切换前端。

复现：

```sh
python3 backend-java/scripts/export-clone-execution-oracle.py /tmp/go-clone-execution.json
mvn -f backend-java/pom.xml \
  -Dweknora.clone.execution.fixture=/tmp/go-clone-execution.json \
  -Dweknora.sqlite.vec.extension=/path/to/vec0.dylib \
  -Dtest=KnowledgeCloneExecutionGoParityTest,KnowledgeCloneDocumentGoParityTest,KnowledgeCloneChunksGoParityTest,KnowledgeClonePlanGoParityTest,KnowledgeMovePlanGoParityTest,KnowledgeMoveExecutionGoParityTest,KnowledgeMoveWorkerGoParityTest,KnowledgeIndexCopyGoParityTest test
```
