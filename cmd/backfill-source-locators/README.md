# 修复 anydoc PDF 页码映射

为已完成解析、缺少 `source_locators` 的 PDF Chunk 补充物理页码。保留 Chunk ID、正文、版本和检索索引；跳过摘要、已编辑片段及已有位置映射的片段。

先构建 anydoc 静态库，再从仓库根目录预览：

```bash
scripts/build-anydoc-lib.sh
go run -tags anydoc ./cmd/backfill-source-locators \
  --knowledge-id DOCUMENT_UUID \
  --source /path/to/original.pdf \
  --db-host 127.0.0.1 --db-port 5434
```

连接配置读取 `.env`、`.env.local` 和进程环境变量。`--db-host`、`--db-port` 为显式覆盖，只支持 PostgreSQL。

确认预览的 `changes` 和 `chunks_by_page` 后，增加 `--apply` 执行。执行会先验证原文件 MD5 与数据库 `file_hash` 一致、至少 90% 的未编辑正文/父片段与当前解析输出的偏移完全一致，再锁定文档与 Chunk 并复核其快照。所有更新在同一个事务中完成。

原有位置及计划写入的位置保存到 `.local-data/repairs/` 下权限为 `0600` 的 JSON 备份；修改前写入并同步到磁盘。回滚时应校验当前映射仍与该记录的 `new_source_locators` 一致，再按记录中的文档、租户、Chunk ID 恢复 `old_source_locators`。

该工具不适用于需要改写图片资源 URL 的文档，也不会为无法验证的文字分配相邻页码。解析代码更新后，重启开发后端可让后续上传使用新的页码映射。
