# 知识库复制：chunk、标签、图片与失败清理

2026-10-16。全部 Go 功能严格对齐仍未完成。本批新增可执行的 Java 复制核心和实际 Go 对照，但尚无生产 Effects 适配器、公开复制接口或 `kb:clone` handler，不能按公开功能已迁移统计。

## 本批实现

- `KnowledgeCloneChunks`：重新检查 transfer/来源与目标归属、已存储的目标文档和全部来源 chunks；所有 chunk 类型参与复制。新建 UUID，按完整映射重写 parent/pre/next，缺失引用清空。每100条单独提交，与最终文档 CAS 分离。
- `KnowledgeCloneRepository`：标签按来源 ID 缓存，复用目标同名标签，否则创建并复制名称/颜色/排序；“未分类”排序为-1；SQLite 标签和 chunk seq_id 独立生成。保留 Go 在创建时的字段清空/default 行为。
- `KnowledgeCloneObjects`：读取原始字节，通过 SaveBytes 写入目标租户 exports 路径；不调用上传布局的 CopyFile。保留图片扩展名或嗅探内容。按 URL 去重，OriginalURL 仅在等于原 URL 时改写。全部图片复制后再按旧 URL 字节长度倒序改写所有 chunk 内容。
- `image_info` 按 Go typed JSON 解码，保留 nil/null/默认字段规则、attrs 的 float64、嵌套 map 排序、HTML 转义，既对照解析后的内容，也对照原始序列化字符串。
- 先解析全部映射/复制图片，再写 chunks，随后解析来源引擎和目标 embedding 模型，最后调用索引复制。空 chunk 映射或空目标模型时跳过索引阶段。
- 写入 chunks 后发生错误：先 CAS 将原目标文档标记 failed，再删除索引、软删除 chunks、清理新图片。CAS/索引清理/chunk清理失败则保留后续资源，按 Go errors.Join 顺序保留原错误。图片清理错误只记录，流 Close 错误不覆盖复制结果。

实际 Go GORM `CreateInBatches` 在此路径会将 `is_enabled=false` 写为 true、`flags=0` 写为1，即使调用方使用 Select("*")。本批保留实际结果，没有依据注释自行修正 Go 行为。Go 没有复制 SourceContent/ContentRevision/LastEditorID/ContextHeader/关系字段，Java 同样清空；source locators 按类型投影。SQLite chunk metadata 按 types.JSON.Value 写 BLOB，source locators 按 Value 写 TEXT/NULL。

## 验证与限制

真实 Go `CloneChunk` 由 test overlay 调用，Go 生产代码未改动。文件、模型、索引边界受控，SQL 使用实际 repository。68场景涵盖全部类型/字段、目标与chunk/tag/parent归属、空模型/空chunks、标签复用/创建、图片多次引用/跨chunk/父文引用、前缀URL、类型/JSON错误、数字和重复字段、字节/扩展名、99/100/101/201条、部分提交、丢失确认、CAS冲突和清理失败。

当前 **670项/6类，失败/错误/跳过均0、实际退出0**：68个新增场景，219个clone预检，252个move计划，64个move执行，46个move worker，21个SQL索引复制。每个新场景核对持久行、seq_id、字段、SQLite物理JSON类型、目标新UUID唯一性以及文件字节和外部调用顺序。文件/索引边界受控，不能据此声称真实云文件或绑定索引集成已验收。

独立 PostgreSQL 首轮使用完整115个迁移、连接池1，63个旧场景的断言失败，容器最终停止退出0。主要问题为序列起点、测试 JSON 节点归一化和缺失目标错误文本。修正后重跑遇到 Docker blob/containerd 元数据库 input/output error，拉取镜像及已有容器 exec 同样失败。**本批 PostgreSQL 未验证通过**；未重启服务或清空 Docker 数据。之前219项PG通过只证明复制预检，不能替代本批执行验收。

最近默认冻结完整构建10464项/196类通过，属于前一索引复制快照的1047个src/pom输入。当前1059个输入有3个修改、12个新增，未重跑当前默认完整构建，不能将不同快照的测试累加成当前全量证明。[完整证据](clone-chunks-checkpoint-2026-10-16.json)。

## 后续仍需完成

1. `cloneKnowledge` 的目标文档创建、源文件深复制、持久 transfer state、成功/失败 CAS 和配额；KB 目标创建与恢复。
2. 生产 Effects 适配器、全部文件存储与来源绑定索引引擎，真实文件和物理索引集成。
3. FAQ diff/执行、复制进度、公开 admission 和 `kb:clone` worker；取消/租约/崩溃恢复/并发/切换、审计和 tracing。
4. PostgreSQL 重跑；存储损坏数据的 SQL JSON scanner 错误、source locator 全部序列化/数值边界及更多并发故障组合仍需扩展覆盖。

总体仍为121/482原生入口、15/22种部分handler、0 verified。不能关闭Go后完整切换Java。

```sh
python3 backend-java/scripts/export-clone-chunks-oracle.py /tmp/go-clone-chunks.json
python3 backend-java/scripts/postgres-clone-chunks-parity.py
```
