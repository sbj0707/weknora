# 文档复制执行核心

2026-10-16。全量 Go 功能迁移仍未完成。本批实现 `KnowledgeCloneDocument`，接入既有 chunk/图片复制核心和真实 SQL 创建、transfer CAS；尚无生产 Effects、公开复制入口、复制进度或 `kb:clone` 接线。

## 按实际 Go 保留的行为

- 校验源文档绑定、完成状态及所有 chunk/parent/tag 后，创建目标 UUID 和 cloning 标记；失败预检不复制文件或写数据。
- 独立复制源文件，随后创建目标文档。源文件复制和 chunk 图片复制各自拥有新对象，即使 URL 相同也不共用缓存。Go 当前对 PDF 源文件也使用 `copyOwnedObject`，扩展名可能回落为 `.png`，Java 保留这一实际行为。
- 只复制 Go 选定字段；使用目标 embedding model；不复制文档标签关系、pending subtasks、summary 状态、FAQ 导入结果、处理时间和旧错误。channel 空值为 web，summary 为 none，空 custom metadata 为 `{}`。
- Profile 保留顺序、重复 topic、空白及超出正常 topic 上限的数据，不调用 Normalize；SQL JSON null profile 与缺失 profile 按 Go 实际 Scan/Clone 区分。自定义 metadata 保留内部 JSON 数字和空白，仅去外围空白；Profile/metadata/custom metadata 在 SQLite 使用 Go 对应的 BLOB。
- 创建后重新读取持久化时间，只将 UpdatedAt 回填内存，随后调用 chunk/索引复制；最终 CAS 提交 done/completed/enabled、源 storage_size，并在同一事务调整租户用量、下限截断为零。
- 创建前失败且确认目标不存在时，清理已复制源文件；写入/读取结果不确定时保留。目标已存在时，只有绑定和任务标记匹配、且未 completed 才尝试写 failed。失败记账本身失败不替换原始错误。
- chunk 清理失败时保留阶段结果，外层文档失败记账使用最新行。最终提交失败不会自动删除已经复制的 chunks/索引；并发 completed 或另一任务标记按 Go 保留。

本批也修正文档不存在的错误码 1001 → Go 1003，以及共享 transfer checkpoint 将新 metadata 写为 SQLite TEXT 的差异。PostgreSQL 仍使用 JSONB 文本参数。

## 实际验证

`export-clone-document-oracle.py` 用 test overlay 执行真实 Go 私有 `cloneKnowledge`、GORM 创建和 transfer 仓库、Chunk 仓库。文件服务、模型与索引引擎边界受控，记录调用顺序和文件字节；SQL 故障使用真实 SQLite trigger。Java 使用实际迁移 schema、SQL 创建、CAS 和 chunk 复制执行。

75 个场景包括常规/URL/manual、无文件/模型/chunk、选定字段与默认值、profile/null/缺失、custom JSON 字面值、非法 metadata、预检失败、文件 read/buffer/save/close、创建前失败/SQL 失败/ack 不确定、重新读取失败、目标绑定变化、其他任务/空/非法/缺失标记、chunk 和索引失败及各级清理失败、并发完成/改时间/改归属/删除、缺失租户、零/负 storage_size、配额 SQL 失败和失败记账再次失败。

对照包含原始 profile/custom JSON/image_info、metadata 物理存储类型、文档字段、chunks、标签、文档标签关系、租户用量和完整受控边界事件；仅 UUID 和时间存在性/相等性按规则归一化，不通过删掉差异字段使测试通过。

最终实际 Go 导出 75 场景退出 0；Java 相关回归 **745 项 / 7 类，失败、错误、跳过均 0，实际退出 0**。1063 个 src/pom 输入在构建前后哈希一致。详细命令、日志、哈希、失败历史和快照见 [checkpoint](clone-document-checkpoint-2026-10-16.json)。最近默认全量 10464 项通过仅属于旧索引复制快照；本批未重跑完整构建。

## 未完成范围

普通复制删除与执行编排随后已有有界实现和对照，见 [普通执行范围](clone-execution-notes.md)。仍需接入生产文件/索引适配器、普通复制生产链路、目标知识库创建/恢复、FAQ diff、公开 admission/进度、worker、审计和 Profile。源/目标配置并发变化、完整数据库扫描错误、全部存储/索引引擎、取消/租约/重启恢复和切换也待验收。

本批未执行 PostgreSQL 对照；当前 fixture 只支持 SQLite。此前 chunk 复制 PG 首轮失败，修正后因 Docker/containerd I/O 故障未完成通过验收。前批 PG219 只证明复制预检，不能替代执行验证。

未新增公开路由和任务 handler，仍为 121/482 个原生入口、15/22 种部分 handler、0 verified。未部署或重启开发服务，未切换前端。

复现 SQLite：

```sh
python3 backend-java/scripts/export-clone-document-oracle.py /tmp/go-clone-document.json
mvn -f backend-java/pom.xml \
  -Dweknora.clone.document.fixture=/tmp/go-clone-document.json \
  -Dweknora.sqlite.vec.extension=/path/to/vec0.dylib \
  -Dtest=KnowledgeCloneDocumentGoParityTest,KnowledgeCloneChunksGoParityTest,KnowledgeClonePlanGoParityTest,KnowledgeMovePlanGoParityTest,KnowledgeMoveExecutionGoParityTest,KnowledgeMoveWorkerGoParityTest,KnowledgeIndexCopyGoParityTest test
```
