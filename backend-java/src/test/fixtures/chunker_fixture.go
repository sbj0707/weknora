//go:build ignore

package main

import (
 "encoding/json"
 "fmt"
 "math/rand"
 "os"
 "strings"

 "github.com/Tencent/WeKnora/internal/infrastructure/chunker"
)

type fixture struct {
 Name string `json:"name"`
 Text string `json:"text"`
 Config chunker.SplitterConfig `json:"config"`
 ChildConfig chunker.SplitterConfig `json:"child_config"`
 Chunks []chunker.Chunk `json:"chunks"`
 Embeddings []string `json:"embeddings"`
 ParentChild chunker.ParentChildResult `json:"parent_child"`
 Images []chunker.ImageRef `json:"images"`
}
func main() {
 texts := []string{
  "", " \t\u00a0\n", "Hello world. This is a test.", "中文[![图😀](https://img.test/a_(b).png)](https://target.test/a_(b)) tail ![empty]() ![other](url) end", "你好😀世界これはテストです。下一句！第三句？结束。",
  strings.Repeat("alpha beta. gamma delta! epsilon zeta?\n", 15),
  "lead paragraph\n\n"+strings.Repeat("A relatively short line.\n", 35),
  "一段中文。\r\n\r\n第二段中文！\r\n第三段中文。\n\nemoji😀end.",
  "before $$x\n+y\n=z$$ after\n\n![图](https://example.com/a.png)\n[link](https://example.com/a)\n`inline. code`\n",
  "lead\n```java\nString value=\"x.y\";\n# not a heading\n```\ntrailing paragraph\n",
  "| A | B |\n| --- | --- |\n"+strings.Repeat("| 中文😀 | long value |\n", 15),
  "||\n| --- | --- |\n| col one | col two |\n"+strings.Repeat("| value one | value two |\n", 12),
  "| A | B |\n| --- | --- |\n| 1 | 2 |\n\n| C | D | E |\n| --- | --- | --- |\n"+strings.Repeat("| 3 | 4 | 5 |\n", 12),
  "| A | B |\n| --- | --- |\n| 1 | 2 |\n\n"+strings.Repeat("| 3 | 4 |\n", 12)+"plain text\n",
  "| A | B |\n| --- | --- |\n| 1 | 2 |\n"+strings.Repeat("| 3 | 4 | 5 |\n", 12),
  "[stray OCR bracket\n"+strings.Repeat("ordinary line\n", 30)+"](target)",
  strings.Repeat("😀", 16020),
  "```\n"+strings.Repeat("中文😀 line\n", 1800)+"```\nend",
  "$$"+strings.Repeat("math text ", 1650)+"$$",
  strings.Repeat("|",16000)+"\n",
  strings.Repeat("|",16000),
  "| A | B |\n| --- | --- |\n"+strings.Repeat("| x | y |",1600)+"\n",
 }
 configs := []chunker.SplitterConfig{
  {ChunkSize:100, Separators:[]string{". "}},
  {ChunkSize:30, ChunkOverlap:5, Separators:[]string{"\n\n","\n","。"}},
  {ChunkSize:65, ChunkOverlap:25, Separators:[]string{"\n\n","\n",". ","。"," "}},
  {ChunkSize:90, ChunkOverlap:1000, Separators:[]string{"\n","。"}},
  {ChunkSize:0, ChunkOverlap:-1},
  {ChunkSize:8, ChunkOverlap:4, Separators:[]string{"", "😀", "。"}},
  {ChunkSize:7500, ChunkOverlap:0, Separators:[]string{"\n"}},
 }
 var output []fixture
 child:=chunker.SplitterConfig{ChunkSize:35,ChunkOverlap:8,Separators:[]string{"\n\n","\n","。",". "}}
 add:=func(name,text string,cfg chunker.SplitterConfig){
  chunks:=chunker.SplitText(text,cfg)
  var embeddings []string
  for _,c:=range chunks {embeddings=append(embeddings,c.EmbeddingContent())}
  output=append(output,fixture{name,text,cfg,child,chunks,embeddings,chunker.SplitTextParentChild(text,cfg,child),chunker.ExtractImageRefs(text)})
 }
 for i,text:=range texts { for j,cfg:=range configs { add(fmt.Sprintf("case-%d-config-%d",i,j),text,cfg) } }
 random:=rand.New(rand.NewSource(42))
 parts:=[]string{"word", "中文😀", "\n", "\n\n", ". ", "。", "\r\n", "`x`", "![图](a.png)", "$$x+y$$", " ", "\u00a0"}
 for i:=0;i<100;i++ {
  var text strings.Builder
  for j:=0;j<30+random.Intn(80);j++ {text.WriteString(parts[random.Intn(len(parts))])}
  add(fmt.Sprintf("random-%d",i),text.String(),chunker.SplitterConfig{ChunkSize:10+random.Intn(100),ChunkOverlap:random.Intn(40),Separators:[]string{"\n\n","\n","。",". "," "}})
 }
 encoder:=json.NewEncoder(os.Stdout)
 encoder.SetIndent("", "  ")
 if err:=encoder.Encode(output);err!=nil {panic(err)}
}
