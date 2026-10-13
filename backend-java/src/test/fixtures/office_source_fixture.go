//go:build ignore

package main

import (
	"archive/zip"
	"bytes"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"github.com/Tencent/WeKnora/internal/sourceloc"
	"github.com/xuri/excelize/v2"
	"io"
	"os"
	"strings"
	"time"
	"unicode/utf16"
)

type member struct{ name, text string }

func archive(members ...member) []byte {
	var buffer bytes.Buffer
	z := zip.NewWriter(&buffer)
	for _, entry := range members {
		f, _ := z.Create(entry.name)
		f.Write([]byte(entry.text))
	}
	z.Close()
	return buffer.Bytes()
}
func main() {
	var output []any
	emit := func(name, ft string, data []byte, markdown string) {
		units, err := sourceloc.StructureUnits(ft, data)
		if strings.HasPrefix(name, "max-") {
			digest := sha256.New()
			for _, u := range units {
				b, _ := json.Marshal(u.Locator)
				var object map[string]any
				json.Unmarshal(b, &object)
				b, _ = json.Marshal(object)
				digest.Write([]byte(u.Text + "\n"))
				digest.Write(b)
				digest.Write([]byte("\n"))
			}
			first, last := any(nil), any(nil)
			if len(units) > 0 {
				first, last = units[0], units[len(units)-1]
			}
			output = append(output, map[string]any{"name": name, "file_type": ft, "data": base64.StdEncoding.EncodeToString(data), "unit_count": len(units), "digest": hex.EncodeToString(digest.Sum(nil)), "first": first, "last": last})
			return
		}
		blocks, alignErr := sourceloc.AlignStructure(ft, data, markdown)
		e := ""
		if err != nil {
			e = err.Error()
		}
		ae := ""
		if alignErr != nil {
			ae = alignErr.Error()
		}
		output = append(output, map[string]any{"name": name, "file_type": ft, "data": base64.StdEncoding.EncodeToString(data), "markdown": markdown, "units": units, "error": e, "blocks": blocks, "align_error": ae, "java_pending": strings.HasPrefix(name, "pending-")})
	}
	doc := func(body string) []byte {
		return archive(member{"word/document.xml", `<w:document xmlns:w="urn:w"><w:body>` + body + `</w:body></w:document>`})
	}
	bodies := []string{`<w:p><w:r><w:t>标题</w:t></w:r></w:p>`, `<w:p/><w:p><w:r><w:t>after blank</w:t></w:r></w:p>`, `<w:sdt><w:sdtPr><w:t>ignored</w:t></w:sdtPr><w:sdtContent><w:p><w:r><w:t>Control text</w:t></w:r></w:p></w:sdtContent></w:sdt>`, `<w:tbl><w:tr><w:tc><w:p><w:r><w:t>Header one</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>Header two</w:t></w:r></w:p></w:tc></w:tr><w:tr><w:tc><w:p><w:r><w:t>Second row</w:t></w:r></w:p></w:tc></w:tr></w:tbl>`, `<w:p><w:r><w:t>Alpha</w:t><w:tab/><w:t>Beta</w:t><w:br/><w:t>Gamma</w:t><w:cr/><w:t>Delta</w:t></w:r></w:p>`, `<w:p><w:r><w:t>Before</w:t><w:delText>deleted</w:delText><w:instrText><w:t>field instructions</w:t></w:instrText><w:t>After</w:t></w:r></w:p>`, `<w:tbl><w:tr><w:tc><w:p><w:r><w:t>Outer before</w:t></w:r></w:p><w:tbl><w:tr><w:tc><w:p><w:r><w:t>Inner</w:t></w:r></w:p></w:tc></w:tr></w:tbl><w:p><w:r><w:t>Outer after</w:t></w:r></w:p></w:tc></w:tr></w:tbl>`, `<w:p><w:r><w:t xml:space="preserve">  spaces　中文&#xA0;end  </w:t></w:r></w:p>`, `<w:p><w:r><w:t>&lt;tag&gt; &amp; &#128512; &#x1F600;</w:t></w:r></w:p>`, `<w:p><w:r><w:t>first completed</w:t></w:r></w:p><w:p><w:r><w:t>unclosed`, `<w:p><w:r><w:t>first completed</w:t></w:r></w:p><w:p><w:r><w:t>&unknown;</w:t></w:r></w:p>`, `<w:p><w:r><w:t>before unexpected close</w:t></wrong>`, `<w:p><w:r><w:t>duplicate paragraph</w:t></w:r></w:p><w:p><w:r><w:t>duplicate paragraph</w:t></w:r></w:p>`, `<w:p><w:r><w:t>第一段文本</w:t></w:r></w:p><w:p><w:r><w:t>第二段文本</w:t></w:r></w:p>`}
	for i, b := range bodies {
		for _, ft := range []string{"docx", " .DOCM "} {
			data := doc(b)
			u, _ := sourceloc.StructureUnits(ft, data)
			var md []string
			for _, unit := range u {
				md = append(md, unit.Text)
			}
			emit(fmt.Sprintf("doc-%d-%s", i, ft), ft, data, "# "+strings.Join(md, "\r\n\r\n"))
		}
	}
	emit("doc-first-duplicate", "docx", archive(member{"word/document.xml", `<document><body><p><t>First duplicate</t></p></body></document>`}, member{"word/document.xml", `<document><body><p><t>Second duplicate</t></p></body></document>`}), "First duplicate")
	emit("doc-no-body", "docx", archive(member{"word/document.xml", `<document><p><t>ignored outside</t></p></document>`}), "ignored outside")
	emit("doc-missing-part", "docx", archive(member{"wrong/path", "x"}), "missing")
	emit("doc-invalid-zip", "docx", []byte("not zip"), "none")
	const relNS = `http://schemas.openxmlformats.org/officeDocument/2006/relationships`
	for i, slide := range []string{`<sld><p><t>Slide first</t></p><p><t>Second</t><br/><t>line</t></p></sld>`, `<sld><p><t>First &amp; second</t></p><p/><p><t>尾段😀</t></p></sld>`, `<sld><p><t>Completed slide</t></p><p><t>invalid`, `<sld><p><t>Title</t></p><p><t>&unknown;</t></p></sld>`} {
		for _, ft := range []string{"pptx", "pptm"} {
			files := []member{{"ppt/presentation.xml", `<presentation xmlns:r="` + relNS + `"><sldIdLst><sldId r:id="missing"/><sldId r:id="r1"/><sldId r:id="r2"/></sldIdLst></presentation>`}, {"ppt/_rels/presentation.xml.rels", `<Relationships><Relationship Id="r1" Type="slide" Target="/ppt/slides/slide2.xml"/><Relationship Id="r2" Type="slide" Target="slides/slide1.xml"/></Relationships>`}, {"ppt/slides/slide2.xml", slide}, {"ppt/slides/slide1.xml", `<sld><p><t>Final slide</t></p></sld>`}, {"ppt/slides/_rels/slide2.xml.rels", `<Relationships><Relationship Id="n1" Type="x/notesSlide" Target="../notesSlides/note.xml"/></Relationships>`}, {"ppt/notesSlides/note.xml", `<notes><p><t>Speaker notes</t></p><p><t>第二备注</t></p></notes>`}}
			data := archive(files...)
			u, _ := sourceloc.StructureUnits(ft, data)
			var md []string
			for _, unit := range u {
				md = append(md, unit.Text)
			}
			emit(fmt.Sprintf("ppt-%d-%s", i, ft), ft, data, strings.Join(md, "\n\n"))
		}
	}
	emit("ppt-invalid-pres", "pptx", archive(member{"ppt/presentation.xml", `<presentation`}), "")
	emit("ppt-wrong-rid-ns", "pptx", archive(member{"ppt/presentation.xml", `<presentation xmlns:r="wrong"><sldIdLst><sldId r:id="r1"/></sldIdLst></presentation>`}, member{"ppt/_rels/presentation.xml.rels", `<Relationships><Relationship Id="r1" Target="slides/a.xml"/></Relationships>`}, member{"ppt/slides/a.xml", `<p><t>must not appear</t></p>`}), "must not appear")
	htmls := []string{`<html><head><title>ignored</title></head><body><h1>Chapter title</h1><p>Before<b>bold</b>after</p><p>A &amp; B &nbsp; C</p></body></html>`, `<h2>第二章</h2><section><p>正文</p><script>let x='<p>hidden</p>'</script><style>p{color:red}</style><p>尾文</p></section>`, `<div>before<br/>after</div><ul><li>one<li>two</ul><pre>line\nline</pre>`, `<h1>Title<span>inline</span></h1><p>body &notit; &copy &#0;</p>`, `<textarea>A <b>raw</b> &amp; text</textarea><p>next paragraph</p>`, `<html><body><p>Unclosed body`, `<h1/>text following heading<p>paragraph</p>`, `<p>A&#13;B\r\nC\x00D</p><!-- hidden comment --><p title='>'>Visible</p>`}
	for i, h := range htmls {
		data := archive(member{"META-INF/container.xml", `<container><rootfiles><rootfile full-path="OPS/book.opf"/><rootfile full-path="unused"/></rootfiles></container>`}, member{"OPS/book.opf", `<package><manifest><item id="a" href="text/ch.xhtml#anchor"/><item id="b" href="/OPS/end.xhtml"/></manifest><spine><itemref idref="missing"/><itemref idref="a"/><itemref idref="b"/></spine></package>`}, member{"OPS/text/ch.xhtml", h}, member{"OPS/end.xhtml", `<h2>End title</h2><p>Final section</p>`})
		u, _ := sourceloc.StructureUnits("epub", data)
		var md []string
		for _, unit := range u {
			md = append(md, unit.Text)
		}
		emit(fmt.Sprintf("epub-%d", i), "epub", data, strings.Join(md, "\n\n"))
	}
	emit("epub-no-rootfile", "epub", archive(member{"META-INF/container.xml", `<container/>`}), "")
	emit("epub-bad-container", "epub", archive(member{"META-INF/container.xml", `<container>`}), "")
	emit("unsupported", "pdf", []byte("invalid"), "none")
	for _, id := range []int{0, 1, 2, 3, 4, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49} {
		f := excelize.NewFile()
		style, _ := f.NewStyle(&excelize.Style{NumFmt: id})
		f.SetCellValue("Sheet1", "A1", "Label")
		f.SetCellValue("Sheet1", "C1", 12345.6789)
		f.SetCellStyle("Sheet1", "C1", "C1", style)
		if id >= 41 && id <= 44 {
			v, _ := f.GetCellValue("Sheet1", "C1")
			fmt.Fprintf(os.Stderr, "format%d=%q\n", id, v)
		}
		f.SetCellValue("Sheet1", "A3", "Row three")
		f.SetCellValue("Sheet1", "B3", true)
		f.SetCellValue("Sheet1", "C4", time.Date(2025, 2, 3, 4, 5, 6, 0, time.UTC))
		f.NewSheet("汇总")
		f.SetCellValue("汇总", "B2", "合计")
		buf, _ := f.WriteToBuffer()
		u, _ := sourceloc.StructureUnits("xlsx", buf.Bytes())
		var md []string
		for _, unit := range u {
			md = append(md, unit.Text)
		}
		emit(fmt.Sprintf("xlsx-format-%d", id), "xlsx", buf.Bytes(), strings.Join(md, "\n\n"))
		f.Close()
	}

	for _, date1904 := range []bool{false, true} {
		for _, id := range []int{0, 1, 2, 3, 4, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49} {
			f := excelize.NewFile()
			f.SetWorkbookProps(&excelize.WorkbookPropsOptions{Date1904: &date1904})
			style, _ := f.NewStyle(&excelize.Style{NumFmt: id})
			values := []float64{0, 0.1, 0.5, 1, 2.5, 2.675, 59, 59.5, 60, 60.5, 61, 61.5, 62, 12345.6789}
			if id < 14 || id >= 37 && id <= 44 || id >= 48 {
				values = append(values, -1, -2.5, -12345.6789, 1e-8, 1e15, 123456789123456789)
			}
			for i, value := range values {
				cell := fmt.Sprintf("A%d", i+1)
				f.SetCellValue("Sheet1", cell, value)
				f.SetCellStyle("Sheet1", cell, cell, style)
			}
			buf, _ := f.WriteToBuffer()
			u, _ := sourceloc.StructureUnits("xlsx", buf.Bytes())
			var md []string
			for _, unit := range u {
				md = append(md, unit.Text)
			}
			emit(fmt.Sprintf("xlsx-broad-%d-%v", id, date1904), "xlsx", buf.Bytes(), strings.Join(md, "\n\n"))
			f.Close()
		}
	}

	fraw := excelize.NewFile()
	rawbuf, _ := fraw.WriteToBuffer()
	fraw.Close()
	rewrite := func(data []byte, changes map[string]string) []byte {
		z, _ := zip.NewReader(bytes.NewReader(data), int64(len(data)))
		var members []member
		seen := map[string]bool{}
		for _, entry := range z.File {
			r, _ := entry.Open()
			b, _ := io.ReadAll(r)
			r.Close()
			if value, ok := changes[entry.Name]; ok {
				b = []byte(value)
				seen[entry.Name] = true
			}
			members = append(members, member{entry.Name, string(b)})
		}
		for name, value := range changes {
			if !seen[name] {
				members = append(members, member{name, value})
			}
		}
		return archive(members...)
	}
	rawSheets := []string{`<row r="3"><c r="A3" t="inlineStr"><is><t> Plain </t><r><t>rich</t></r><rPh><t>ignored phonetic</t></rPh><r><t> end</t></r></is></c><c r="C3"><f>SUM(A1)</f><v>42</v></c></row><row r="6"><c r="B6"><f>empty cached</f></c><c r="C6" t="inlineStr"><is><t>next</t></is></c></row>`, `<row r="2"><c t="inlineStr"><is><t>first</t></is></c><c t="inlineStr"><is><t>second</t></is></c><c r="D2" t="str"><v>cached string</v></c></row>`, `<row r="1"><c r="A1" t="s"><v>0</v></c><c r="C1" t="s"><v>1</v></c><c r="D1" t="s"><v>-1</v></c><c r="E1" t="s"><v>999</v></c></row>`, `<row r="1"><c r="A1" t="inlineStr"><is><t>_x0008__x005F_x0008__xD83D__xDE00_</t></is></c><c r="B1" t="b"><v>2</v></c><c r="C1" t="e"><v>#DIV/0!</v></c></row>`, `<row r="2"><c r="A2"><v>first complete</v></c></row><row r="4"><c r="A4"><v>unclosed`, `<row r="2"><c r="A2"><v>first complete</v></c></row><row r="4"><c r="invalid"><v>stops sheet</v></c></row>`, `<row r="8"><c r="D8"><v>8</v></c><c r="B8"><v>2</v></c><c r="A8"><v>1</v></c></row>`, `<row r="1"><c r="A1" t="str"><v>_x0008_</v></c><c r="C1"><v>0x1p2</v></c></row>`}
	for i, sheet := range rawSheets {
		changes := map[string]string{"xl/worksheets/sheet1.xml": `<worksheet><sheetData>` + sheet + `</sheetData></worksheet>`, "xl/sharedStrings.xml": `<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><si><t>Plain</t><r><t>rich</t></r><rPh><t>phonetic</t></rPh></si><si><r><t>二</t></r><r><t>号</t></r></si></sst>`}
		data := rewrite(rawbuf.Bytes(), changes)
		u, _ := sourceloc.StructureUnits("xlsx", data)
		var md []string
		for _, unit := range u {
			md = append(md, unit.Text)
		}
		emit(fmt.Sprintf("xlsx-raw-%d", i), "xlsx", data, strings.Join(md, "\n\n"))
	}
	for i, value := range []string{"2025-02-03T04:05:06Z", "20250203T040506Z", "20250203T040506.789", "2025-02-03 04:05:06Z", "2025-02-03T04:05:06.789Z", "invalid-date", "1899-12-30T00:00:00Z", "1900-02-28T12:00:00Z"} {
		for _, id := range []int{0, 14, 22} {
			fs := excelize.NewFile()
			style, _ := fs.NewStyle(&excelize.Style{NumFmt: id})
			buffer, _ := fs.WriteToBuffer()
			fs.Close()
			data := rewrite(buffer.Bytes(), map[string]string{"xl/worksheets/sheet1.xml": fmt.Sprintf(`<worksheet><sheetData><row r="1"><c r="A1" t="d" s="%d"><v>%s</v></c></row></sheetData></worksheet>`, style, value)})
			u, _ := sourceloc.StructureUnits("xlsx", data)
			var md []string
			for _, unit := range u {
				md = append(md, unit.Text)
			}
			emit(fmt.Sprintf("xlsx-iso-%d-%d", i, id), "xlsx", data, strings.Join(md, "\n"))
		}
	}
	for i, h := range []string{`A<!bogus comment> B<!--x--!>C<p>D</p>`, `A<![CDATA[raw <p>]]>B<p>C</p>`, `<p>A</p><script/>raw<p>must hide</p>`, `<p>A</p><textarea/>raw<b>tag</b>&amp;</textarea><p>B</p>`, `<p>A</p><title/>raw<b>tag</b></title><p>B</p>`, `<xmp>A<b>raw</b>&amp;</xmp><p>B</p>`, `<plaintext>A<p>raw</p>&amp;`, `<iframe>A<p>raw</p></iframe><p>B</p>`, `<h1 title="x>y">Title</h1><p>A<!-- unfinished`, `A<!-- > <p>hidden</p> -->B`, `<h1>First<h2>Second</h2>third</h1>`, `<p>A\r\nB\rC\x00D</p>`, `<p>&NotEqualTilde; &notit; &amp;amp; &#x110000; &#xD800; &#128;</p>`} {
		h = strings.ReplaceAll(h, `\r`, "\r")
		h = strings.ReplaceAll(h, `\n`, "\n")
		h = strings.ReplaceAll(h, `\x00`, "\x00")
		data := archive(member{"META-INF/container.xml", `<container><rootfiles><rootfile full-path="book.opf"/></rootfiles></container>`}, member{"book.opf", `<package><manifest><item id="x" href="a.xhtml"/></manifest><spine><itemref idref="x"/></spine></package>`}, member{"a.xhtml", h})
		u, _ := sourceloc.StructureUnits("epub", data)
		var md []string
		for _, unit := range u {
			md = append(md, unit.Text)
		}
		emit(fmt.Sprintf("epub-token-%d", i), "epub", data, strings.Join(md, "\n\n"))
	}
	good := doc(`<p><t>ZIP valid text</t></p>`)
	prefix := append([]byte("MZ self extracting prefix"), good...)
	emit("zip-preamble", "docx", prefix, "ZIP valid text")
	corrupt := append([]byte(nil), good...)
	for i := 0; i+46 < len(corrupt); i++ {
		if binary.LittleEndian.Uint32(corrupt[i:]) == 0x02014b50 {
			binary.LittleEndian.PutUint32(corrupt[i+16:], 0)
			break
		}
	}
	emit("zip-crc-error", "docx", corrupt, "ZIP valid text")

	for i, xml := range []string{`<?xml version="1.0" encoding="UTF-8"?><document><body><p><t>UTF8 text</t></p></body></document>`, `<!DOCTYPE document [<!ENTITY x "secret">]><document><body><p><t>before</t></p><p><t>&x;</t></p></body></document>`, `<!DOCTYPE document SYSTEM "file:///tmp/never-read-office-secret"><document><body><p><t>ordinary text</t></p></body></document>`, `<x:document><x:body><x:p><x:t>undeclared prefix text</x:t></x:p></x:body></x:document>`, `<?xml version="1.0" encoding="ISO-8859-1"?><document><body><p><t>ASCII nonUTF declaration</t></p></body></document>`} {
		emit(fmt.Sprintf("doc-xml-directive-%d", i), "docx", archive(member{"word/document.xml", xml}), "before UTF8 text ordinary text undeclared prefix text")
	}
	xml16 := `<?xml version="1.0" encoding="UTF-16"?><document><body><p><t>UTF16 text</t></p></body></document>`
	binary16 := []byte{0xff, 0xfe}
	for _, v := range utf16.Encode([]rune(xml16)) {
		binary16 = binary.LittleEndian.AppendUint16(binary16, v)
	}
	emit("doc-utf16", "docx", archive(member{"word/document.xml", string(binary16)}), "UTF16 text")
	badsst := rewrite(rawbuf.Bytes(), map[string]string{"xl/worksheets/sheet1.xml": `<worksheet><sheetData><row r="1"><c r="A1"><v>no shared cells but invalid SST</v></c></row></sheetData></worksheet>`, "xl/sharedStrings.xml": `<sst><si><t>wrong namespace</t></si></sst>`})
	emit("xlsx-invalid-sst-ns", "xlsx", badsst, "no shared cells but invalid SST")
	for _, id := range []int{1, 2, 11, 14, 22, 37, 41, 42, 43, 44, 49} {
		fs := excelize.NewFile()
		style, _ := fs.NewStyle(&excelize.Style{NumFmt: id})
		fs.SetCellValue("Sheet1", "A1", "before")
		fs.SetCellValue("Sheet1", "B1", -2.675)
		fs.SetCellStyle("Sheet1", "B1", "B1", style)
		fs.SetCellValue("Sheet1", "C1", "after")
		buffer, _ := fs.WriteToBuffer()
		fs.Close()
		u, _ := sourceloc.StructureUnits("xlsx", buffer.Bytes())
		var md []string
		for _, unit := range u {
			md = append(md, unit.Text)
		}
		emit(fmt.Sprintf("xlsx-style-negative-%d", id), "xlsx", buffer.Bytes(), strings.Join(md, "\n"))
	}
	for _, variant := range []string{"large", "method", "encrypted"} {
		data := append([]byte(nil), good...)
		for i := 0; i+46 < len(data); i++ {
			if binary.LittleEndian.Uint32(data[i:]) == 0x02014b50 {
				switch variant {
				case "large":
					binary.LittleEndian.PutUint32(data[i+24:], 64<<20+1)
				case "method":
					binary.LittleEndian.PutUint16(data[i+10:], 99)
				case "encrypted":
					binary.LittleEndian.PutUint16(data[i+8:], binary.LittleEndian.Uint16(data[i+8:])|1)
				}
				break
			}
		}
		emit("zip-"+variant, "docx", data, "ZIP valid text")
	}

	repeated := strings.Repeat(`<p><t>Boundary text</t></p>`, 200003)
	emit("max-docx", "docx", doc(repeated), "")
	emit("max-pptx", "pptx", archive(member{"ppt/presentation.xml", `<presentation xmlns:r="` + relNS + `"><sldIdLst><sldId r:id="r1"/></sldIdLst></presentation>`}, member{"ppt/_rels/presentation.xml.rels", `<Relationships><Relationship Id="r1" Target="slides/a.xml"/></Relationships>`}, member{"ppt/slides/a.xml", `<sld>` + repeated + `</sld>`}), "")
	emit("max-epub", "epub", archive(member{"META-INF/container.xml", `<container><rootfiles><rootfile full-path="book.opf"/></rootfiles></container>`}, member{"book.opf", `<package><manifest><item id="x" href="a.xhtml"/></manifest><spine><itemref idref="x"/></spine></package>`}, member{"a.xhtml", strings.Repeat(`<p>Boundary text</p>`, 200003)}), "")
	var sheetmax strings.Builder
	sheetmax.WriteString(`<worksheet><sheetData>`)
	for i := 1; i <= 200003; i++ {
		fmt.Fprintf(&sheetmax, `<row r="%d"><c r="A%d" t="inlineStr"><is><t>Boundary text</t></is></c></row>`, i, i)
	}
	sheetmax.WriteString(`</sheetData></worksheet>`)
	emit("max-xlsx", "xlsx", rewrite(rawbuf.Bytes(), map[string]string{"xl/worksheets/sheet1.xml": sheetmax.String()}), "")

	dirpos := bytes.Index(good, []byte{0x50, 0x4b, 0x01, 0x02})
	endpos := bytes.LastIndex(good, []byte{0x50, 0x4b, 0x05, 0x06})
	central := append([]byte(nil), good[dirpos:endpos]...)
	oldSize := binary.LittleEndian.Uint32(central[24:])
	oldComp := binary.LittleEndian.Uint32(central[20:])
	oldOffset := binary.LittleEndian.Uint32(central[42:])
	extra := binary.LittleEndian.AppendUint16(nil, 1)
	extra = binary.LittleEndian.AppendUint16(extra, 24)
	extra = binary.LittleEndian.AppendUint64(extra, uint64(oldSize))
	extra = binary.LittleEndian.AppendUint64(extra, uint64(oldComp))
	extra = binary.LittleEndian.AppendUint64(extra, uint64(oldOffset))
	binary.LittleEndian.PutUint16(central[30:], uint16(len(extra)))
	binary.LittleEndian.PutUint32(central[20:], 0xffffffff)
	binary.LittleEndian.PutUint32(central[24:], 0xffffffff)
	binary.LittleEndian.PutUint32(central[42:], 0xffffffff)
	central = append(central, extra...)
	zip64 := append(append([]byte(nil), good[:dirpos]...), central...)
	position64 := len(zip64)
	zip64 = binary.LittleEndian.AppendUint32(zip64, 0x06064b50)
	zip64 = binary.LittleEndian.AppendUint64(zip64, 44)
	zip64 = binary.LittleEndian.AppendUint16(zip64, 45)
	zip64 = binary.LittleEndian.AppendUint16(zip64, 45)
	zip64 = binary.LittleEndian.AppendUint32(zip64, 0)
	zip64 = binary.LittleEndian.AppendUint32(zip64, 0)
	zip64 = binary.LittleEndian.AppendUint64(zip64, 1)
	zip64 = binary.LittleEndian.AppendUint64(zip64, 1)
	zip64 = binary.LittleEndian.AppendUint64(zip64, uint64(len(central)))
	zip64 = binary.LittleEndian.AppendUint64(zip64, uint64(dirpos))
	zip64 = binary.LittleEndian.AppendUint32(zip64, 0x07064b50)
	zip64 = binary.LittleEndian.AppendUint32(zip64, 0)
	zip64 = binary.LittleEndian.AppendUint64(zip64, uint64(position64))
	zip64 = binary.LittleEndian.AppendUint32(zip64, 1)
	end64 := append([]byte(nil), good[endpos:]...)
	binary.LittleEndian.PutUint16(end64[8:], 65535)
	binary.LittleEndian.PutUint16(end64[10:], 65535)
	binary.LittleEndian.PutUint32(end64[12:], 0xffffffff)
	binary.LittleEndian.PutUint32(end64[16:], 0xffffffff)
	zip64 = append(zip64, end64...)
	emit("zip64-valid", "docx", zip64, "ZIP valid text")

	custombook := excelize.NewFile()
	customfmt := "0.0000"
	customstyle, _ := custombook.NewStyle(&excelize.Style{CustomNumFmt: &customfmt})
	custombook.SetCellValue("Sheet1", "A1", 3.14)
	custombook.SetCellStyle("Sheet1", "A1", "A1", customstyle)
	custombuffer, _ := custombook.WriteToBuffer()
	custombook.Close()
	emit("xlsx-custom-format", "xlsx", custombuffer.Bytes(), "3.1400")

	macro := excelize.NewFile()
	macro.SetCellValue("Sheet1", "B2", "宏 workbook 原始文本")
	macroBuffer, _ := macro.WriteToBuffer()
	macro.Close()
	emit("xlsm-alias", " .XLSM ", macroBuffer.Bytes(), "宏 workbook 原始文本")

	emit("xlsx-float-edges", "xlsx", rewrite(rawbuf.Bytes(), map[string]string{"xl/worksheets/sheet1.xml": `<worksheet><sheetData><row r="1"><c r="A1"><v>1.234567890123456e-5</v></c><c r="C1"><v>1.234567890123456e-4</v></c></row><row r="2"><c r="A2"><v>-0</v></c><c r="C2"><v>NaN</v></c><c r="D2"><v>Infinity</v></c><c r="E2"><v>-Inf</v></c></row></sheetData></worksheet>`}), "0.00001234567890123456 0.0001234567890123456")

	for _, id := range []int{0, 2, 11, 14, 37, 41, 42, 44, 49} {
		book := excelize.NewFile()
		style, _ := book.NewStyle(&excelize.Style{NumFmt: id})
		buffer, _ := book.WriteToBuffer()
		book.Close()
		sheet := fmt.Sprintf(`<worksheet><sheetData><row r="1"><c r="A1" t="inlineStr" s="%d"><is><t>123.456</t></is></c><c r="B1" t="s" s="%d"><v>0</v></c><c r="C1" t="b" s="%d"><v>2</v></c><c r="D1" t="str" s="%d"><v>123.456</v></c></row></sheetData></worksheet>`, style, style, style, style)
		data := rewrite(buffer.Bytes(), map[string]string{"xl/worksheets/sheet1.xml": sheet, "xl/sharedStrings.xml": `<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><si><t>123.456</t></si></sst>`})
		u, _ := sourceloc.StructureUnits("xlsx", data)
		var md []string
		for _, unit := range u {
			md = append(md, unit.Text)
		}
		emit(fmt.Sprintf("xlsx-styled-strings-%d", id), "xlsx", data, strings.Join(md, "\n"))
	}

	for _, id := range []int{14, 22, 46} {
		book := excelize.NewFile()
		style, _ := book.NewStyle(&excelize.Style{NumFmt: id})
		for i, value := range []float64{100000, 2958465.5, 2958466, 1e7} {
			cell := fmt.Sprintf("A%d", i+1)
			book.SetCellValue("Sheet1", cell, value)
			book.SetCellStyle("Sheet1", cell, cell, style)
		}
		buffer, _ := book.WriteToBuffer()
		book.Close()
		u, _ := sourceloc.StructureUnits("xlsx", buffer.Bytes())
		var md []string
		for _, unit := range u {
			md = append(md, unit.Text)
		}
		emit(fmt.Sprintf("xlsx-date-bounds-%d", id), "xlsx", buffer.Bytes(), strings.Join(md, "\n"))
	}

	for i, h := range []string{`<p>First İ visible</p><script>İ hidden</SCRIPT><p>Final visible</p>`, `<div>A<p　id="wide-space">B</p><p>C</p></div>`} {
		data := archive(member{"META-INF/container.xml", `<container><rootfiles><rootfile full-path="book.opf"/></rootfiles></container>`}, member{"book.opf", `<package><manifest><item id="x" href="a.xhtml"/></manifest><spine><itemref idref="x"/></spine></package>`}, member{"a.xhtml", h})
		u, _ := sourceloc.StructureUnits("epub", data)
		var md []string
		for _, unit := range u {
			md = append(md, unit.Text)
		}
		emit(fmt.Sprintf("epub-unicode-token-%d", i), "epub", data, strings.Join(md, "\n\n"))
	}
	json.NewEncoder(os.Stdout).Encode(output)
}
