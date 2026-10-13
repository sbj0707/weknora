# 原文位置基础迁移

已迁移 Go `sourceloc/assign.go`、`text.go`、`RemapBlocks` 及 `Align` 验证算法：原文 rune 范围、重叠查询、页面/slide/sheet/text 相邻折叠、partial 标记、引用清理和合并；TXT/MD 原文段落与 BOM viewer 坐标；CRLF、Markdown/URL/HTML 改写按完整文本重对齐。重复文本数量和数字边界必须匹配，未验证的改变/删除内容不能制造位置。没有使用已 deprecated 的比例插值 Remapper。

Go `office.go` 的 CSV source units 已通过共享原生 GoCsv 解析器实现（LazyQuotes、TrimLeadingSpace、FieldsPerRecord=-1），与原先推断列宽的 CSV 文档转换行为分开，最多 200,000 个非空 unit。DOCX/DOCM、PPTX/PPTM、EPUB 的原生 ZIP 结构提取以及 XLSX/XLSM 通用 Excelize/nfp 显示格式提取已接入知识处理；范围、实际 Go oracle、实际 Excel 格式语义以及更大 ZIP member/非规范 Office 的剩余边界见 [office-migration-notes.md](office-migration-notes.md)，不能宣告全部 Office 完成。ASR 时间段生成仍待迁移；source hash/知识来源持久化由处理业务层负责。

实际 Go oracle：235 个 index/range、1880 个 point、12 原文及12 BOM映射、4464 个完整 HTML entity/引用清理样本、12 merge、39 rewrite、88 Align、30 CSV units/Align。7 个 JUnit 方法包含这些样本；不是把每个样本计为一项测试。2026-10-13 isolated clean docreader+sourceloc+task+knowledge 共240项/17类全绿，日志 `/tmp/weknora-java-source-location-checkpoint.log`。

Runtime HTML entity 和 Unicode Letter/Digit/ToLower 表由本地 Go 1.26.2 标准库生成，许可随 jar 打包。引用 display 300 rune cap 与存储的完整 overlap quote 分开，存储不会300截断。内部 offsets 可用 UTF-16 字符串实现搜索，但所有外部和原文位置都为 Unicode code point。

从仓库根重生成：

```sh
go run backend-java/src/test/fixtures/source_location_tables.go backend-java/src/main/resources
go run backend-java/src/test/fixtures/source_location_fixture.go backend-java/src/main/resources/go-html-entities.json backend-java/src/test/java/com/tencent/weknora/sourceloc/source-location-golden.json
```

无 source blocks 时 locators 返回 null，写入旧 Go `source_locators` 时保留 SQL NULL。当前模块不表示知识处理/文件公开接口已经完整迁移。
