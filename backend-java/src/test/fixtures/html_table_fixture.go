//go:build ignore
package main
import (
 "encoding/json"
 "fmt"
 "os"
 "strings"
 "github.com/Tencent/WeKnora/internal/infrastructure/docparser"
 "github.com/Tencent/WeKnora/internal/logger"
)
type sample struct { Name string `json:"name"`; Input string `json:"input"`; Output string `json:"output"`; Twice string `json:"twice"` }
func main() {
 logger.SetOutput(os.Stderr)
 inputs:=[]string{
 "plain\n| a | b |\n| --- | --- |", "<table", "<table></table>",
 `<table><tr><td style="text-align:center;">指标</td><td>数值</td></tr><tr><td>营收</td><td>10亿</td></tr></table>`,
 `<table><thead><tr><th>A</th><th>B</th></tr></thead><tbody><tr><td>1</td><td>2</td></tr></tbody></table>`,
 `<table><tr><td>data</td></tr><tr><th>Header</th></tr><tr><td>more</td></tr></table>`,
 `<table><thead><tr><td>Header</td></tr><tr><td>Second</td></tr></thead><tr><td>Body</td></tr></table>`,
 `<table><tr><td>A</td></tr><tr><td>x</td><td>y</td><td>z</td></tr><tr><td>short</td></tr></table>`,
 `<table><tr></tr><tr><td>value</td></tr><tr></tr></table>`,
 `<table><tr><td></td><td> </td></tr><tr><td>  </td><td></td></tr></table>`,
 `<table><tr><td colspan="2" style="color:red" class=x width=20>A</td></tr><tr><td>B</td></tr></table>`,
 `<table><tr><td rowspan=3 align=left valign=top bgcolor=red>A</td></tr></table>`,
 `<table><tr><td colspan="1" rowspan = 0>A</td></tr></table>`,
 `<table role="presentation"><tr><td>A</td><td>B</td></tr></table>`,
 `<table><caption>Title <b>bold</b></caption><tr><th>A</th></tr><tr><td>B</td></tr></table>`,
 `<table><tr><th align=left>A</th><th align=center>B</th><th align=right>C</th></tr><tr><td>1</td><td>2</td><td>3</td></tr></table>`,
 `<TABLE><TR><TD>A</TD><TD>B</TD></TR></TABLE>`,
 `<table><tr><td>A<td>B<tr><td>C<td>D</table>`,
 `<table><tr><td><table><tr><td>nested</td></tr></table></td></tr></table>`,
 "```html\n<table><tr><td>A</td></tr></table>\n```",
 "~~~html\n<table><tr><td>A</td></tr></table>\n~~~",
 "before\n\n\n<table><tr><td>A</td></tr></table>\n\n\nafter",
 `<table><tr><td>A</td></tr></table><table><tr><td>B</td></tr></table>`,
 }
 cells:=[]string{"中文😀", "a &amp; b &lt; c &gt; d &nbsp; &#x1f600;", "a\n  b\t  c", "a<br/>b", "<ul><li>a</li><li>b</li></ul>",
 "<b>bold</b> <i>italic</i>","<strong>bold</strong><em>italic</em>","<b> before </b>after", "<b><b>same</b></b>", "<b>one</b><b>two</b>",
 "<span style=\"font-weight:bold\">fake</span>","<span style=\"font-style:italic\">fake</span>","<code>x ` y</code>","<code> x  y </code>","<code> </code>",
 "<p>single</p>","<p>one</p><p>two</p>","<div>single</div>","<div>one</div><div>two</div>","<h2>heading</h2>", "<blockquote>quote</blockquote>",
 "<a href=\"https://example.com/x_(y)\" title=\"a &quot;quote&quot;\">link</a>","<a href=\"\">empty</a>","<a href=\"url\"></a>","<a href=\"url\"> surrounding </a>",
 "<img src=\"local://images/a.png\" alt=\"中文😀\"/>","<img src=\"x.png\" alt=\"a[b]\" title=\"a &quot;quote&quot;\"/>","<img alt=\"no src\">",
 "![cover](local://a.png)","[link](url)","a|b", "*bold*", "_italic_", "a_b", "1. list", "# heading", "---", "`code`", "\\escaped", "a **b** c", "$value", "<del>removed</del>", "<span>normal</span>",
 "<script>alert(1)</script>text<!--comment--><style>x</style>", "<pre><code>x\ny</code></pre>", "<a href=\"url\"><b>strong link</b></a>",
 }
 for _,cell:=range cells {inputs=append(inputs,`<table><tr><td>`+cell+`</td><td>X</td></tr><tr><td>tail</td><td>value</td></tr></table>`)}
 inputs=append(inputs,`<table><tr><td>`+strings.Repeat("long😀",150)+`</td></tr></table>`)
 var samples []sample
 for i,input:=range inputs {output:=docparser.NormalizeHTMLTables(input);samples=append(samples,sample{fmt.Sprintf("html-%d",i),input,output,docparser.NormalizeHTMLTables(output)})}
 enc:=json.NewEncoder(os.Stdout);enc.SetIndent("","  ");if err:=enc.Encode(samples);err!=nil {panic(err)}
}
