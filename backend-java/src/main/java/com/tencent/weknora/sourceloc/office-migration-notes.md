# Office 原文结构与 Excel 格式迁移边界

`OfficeStructure` 对照生产 Go `internal/sourceloc/office.go` 提取原文结构并使用 `SourceAlignment` 对齐转换后的 Markdown。DOCX/DOCM 的段落、透明内容控件、表格/嵌套表格行与已完成部分 XML；PPTX/PPTM 的 presentation 关系顺序、slide 序号缺口、ZIP 相对/绝对 part 与 notes；EPUB 的 OPF/spine、section/title 和顺序 HTML 块已纳入真实 Go 差分。ZIP 不落盘，不访问外部关系，首个重复 member、Zip64 与前导数据保持 Go 行为。DOC/PPT/EPUB 保留 Go 的 64MiB member 和 200000 unit cap；PPT/EPUB 在完整 slide/section 后检查，可单 item 超过 cap。

XLSX/XLSM 原生读取 worksheet 顺序、shared/inline/rich strings、转义、公式缓存与稀疏行列。Excelize v2.11.0 的通用 `format`、nfp tokenizer/sections、数字占位/缩放/货币/percent/fraction/scientific/DBNum、alignment/text 分类及语言日期已移植；不是按少量内置 ID 猜测。默认 CultureUnknown 的未识别 built-in ID 按真实 Go 返回原值；自定义格式与 namespace-qualified formatCode16 覆盖、重复 ID 首项优先、坏 style XML 的读取失败通过实际 Rows.Columns 对照。1900假闰日、date1904特例、Go UTC64bit calendar wrap/duration saturation 与极端日期保留。Go 416 LCID、2 named aliases 的12个月×3种缩写、weekday、AM/PM、日文/民国年号固定数据可复现导出，运行时使用 native Java，没有 Go 子进程、业务桥接或系统 Locale 猜测。

本阶段 actual Go：4520 tokenizer cases（上游520 + seed321的4000语法样本）；59982 `format` 输出（所有 builtin/currency/localized pattern/LCID + 上游4293案例的两种datebase + typed/nonfinite/systemdate/Culture）；32真实OOXML workbooks 共87439 source units，覆盖4597 custom patterns、全部上游raw值、shared/inline/bool/date/error/formula、ID0–399、首duplicate、formatCode16、坏styles、strict/qualified namespace、空styles/非法ID以及64bitID。默认没有提前替换 Go 对普通非标准格式的原值行为。三个 large fixtures 为 gzip，仅减小测试文件体积；production locale table 为固定 JSON。`generate-excel-format-oracles.py` 在临时目录复制锁定库，使用私有 test helper 导出；临时 -modfile 与 overlay 不改生产 Go 或 modcache。独立重生成三个 gzip oracle及locale表已逐项相等。

Go `Rows.Columns` 对 `NaN` 配合 `# ?/?` 的 continued-fraction 会不终止，已在独立 Go 进程以3秒deadline确认并清理进程；Java对此已证实不可完成的能力明确 `UnsupportedCapability`，有限 fraction 超过4096迭代步为Java显式容量限制（并非已证明Go不终止），同样明确失败，不能发布猜测值。非有限类型转int存在Go平台语义，本次oracle来自Go1.26.2 darwin/arm64；Linux/amd64该角落的日期输出仍需独立实际Go验证，不将跨架构非有限转换宣告完成。

XLSX member仍限64MiB，而Go Excelize默认总解压16GiB、可将更大worksheet/shared strings解压到临时文件；这部分流式实现尚待迁移，超限明确失败。完整非规范 EPUB HTML tokenizer、XML多根碎片、非法PPT多notes relationship顺序及非格式XML样式字段的完整Go类型校验仍需扩大差分。Office内容到Markdown的AnyDoc/native/registered-parser绑定仍未迁移；本模块是来源定位，不是内容转换器。公开上传route的Go bridge ownership没有改变，不宣告全Office或全后端完成。

NativeDocumentSources优先已有parser blocks；original bytes/Markdown空则跳过。普通损坏ZIP/XML读取失败按Go attachStructureBlocks降级无定位并继续；未迁移/不终止能力明确失败。绑定原file hash并Verified RemapBlocks适配CRLF/改写；不制造原文位置。旧pending自定义0.0000 fixture现实际生成3.1400、persist source locator并完成durable postprocess；损坏文件与transaction fencing回归保留。

原Office基础235 ZIP/units/alignment样本（自定义样本由pending转Go对齐成功）、4边界完整SHA256、20 actual production attachment样本；原6个Office JUnit方法更新，并新增4个Excel差分方法。当前仅/tmp standalone共24方法全绿：Excel4+Office4+OfficeProcessing2+DocumentProcessing7+SourceLocation7，含realSQLite/vec0与任务事务回归。尚未占main/Maven窗口；正式clean结果由主迁移agent验收后更新。

```sh
python3 backend-java/scripts/generate-office-oracles.py
python3 backend-java/scripts/generate-excel-format-oracles.py
```

许可：Excelize BSD3已打包 `META-INF/licenses/EXCELIZE-LICENSE.txt`；nfp BSD3为 `NFP-LICENSE.txt`（Ri Xu版权），Go UTC calendar使用已随source模块打包的 `GO-HTML-UNICODE-LICENSE.txt`。生成locale表同属Excelize许可。
