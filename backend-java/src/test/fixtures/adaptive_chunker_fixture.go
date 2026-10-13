//go:build ignore

package main

import (
 "encoding/json"
 "os"
 "strings"
 "fmt"
 "github.com/Tencent/WeKnora/internal/infrastructure/chunker"
 "github.com/Tencent/WeKnora/internal/logger"
)
type fixture struct {
 Name string `json:"name"`
 Text string `json:"text"`
 Config chunker.SplitterConfig `json:"config"`
 ChildConfig chunker.SplitterConfig `json:"child_config"`
 Chunks []chunker.Chunk `json:"chunks"`
 Diagnostics *chunker.Diagnostics `json:"diagnostics"`
 Embeddings []string `json:"embeddings"`
 ParentChild chunker.ParentChildResult `json:"parent_child"`
 ParentDiagnostics *chunker.Diagnostics `json:"parent_diagnostics"`
 Profile *chunker.DocProfile `json:"profile"`
 Language string `json:"language"`
}
func main() {
 logger.SetOutput(os.Stderr)
 texts:=[]string{
 "", "  \u00a0\n", "plain short",strings.Repeat("An ordinary line of prose.\n",35),
 "# Top\n\n## First\nHello😀中文\n\n## Second\nGoodbye\n\n## Third\nFinal\n",
 "# Top\n## A\n"+strings.Repeat("A useful detail line.\n",20)+"### Deep\n"+strings.Repeat("Detailed deep paragraph.\n",20)+"## B\n"+strings.Repeat("Another sentence.\n",20)+"## C\n尾巴😀",
 "## One\nshort\n## Two\nshort\n## Three\nshort\n## Four\nshort\n## Five\nshort\n",
 "preamble\n# Top\ntext\n### Jump\nmore\n## Back\nmore\n# New\nend",
 "# Heading\n```\n# Fake\n## False\n```\n## Real\n"+strings.Repeat("line\n",40)+"## Ending\nend",
 "## Heading   ###\ntext\n## \u00a0Title \u00a0\nbody\n## Ending\nend",
 "INTRODUCTION\n"+strings.Repeat("intro line.\n",30)+"METHODS\n"+strings.Repeat("method line.\n",30)+"RESULTS\n"+strings.Repeat("results line.\n",30),
 "1. Introduction\n"+strings.Repeat("intro line.\n",30)+"2.3 Methods\n"+strings.Repeat("method line.\n",30)+"IV. Results\n"+strings.Repeat("results line.\n",30),
 "Kapitel 1. Anfang\n"+strings.Repeat("DER TEXT IST NICHT GROSS.\n",30)+"Abschnitt II. Details\n"+strings.Repeat("mit der Lösung\n",30),
 "Chapter 1. Introduction\n"+strings.Repeat("A long sentence to read.\n",30)+"Section II. Details\n"+strings.Repeat("more detail\n",30),
 "第一章 开始\n"+strings.Repeat("中文内容😀。\n",50)+"第 2 节 结束\n"+strings.Repeat("最后一段！\n",30),
 "one\f"+strings.Repeat("body\n",50)+"\f"+strings.Repeat("another\n",50),
 strings.Repeat("body body\n\n\n---\n",20),
 "| A | B |\n| --- | --- |\n"+strings.Repeat("| 1. heading | value |\n",30)+"\nCHAPTER END\nend",
 "```\n"+strings.Repeat("fake CHAPTER\n\n\nPage 2 of 3\n",20)+"```\nbody",
 "abc 123 😀 !!!", "日本語カタカナテスト", "한국어 텍스트", "Der Text und die Lösung ist wichtig", "A mixed 中文 test", 
 strings.Repeat("# Header\n",40)+strings.Repeat("content\n",70),
 }
 strategies:=[]string{"","auto","heading","heuristic","legacy","recursive","unknown"}
 var output []fixture
 for i,text:=range texts {for j,strategy:=range strategies {
  cfg:=chunker.SplitterConfig{ChunkSize:200,ChunkOverlap:35,Separators:[]string{"\n\n","\n","。",". "},Strategy:strategy}
  if j==0 {cfg.ChunkSize=0; cfg.ChunkOverlap=0;cfg.Separators=nil}
  if j==2 {cfg.ChunkSize=75;cfg.TokenLimit=35;cfg.Languages=[]string{"zh"}}
  if j==3 {cfg.Languages=[]string{"en"};cfg.ChunkOverlap=1000}
  if j==6 {cfg.Languages=[]string{"unknown"};cfg.TokenLimit=80}
  child:=chunker.SplitterConfig{ChunkSize:90,ChunkOverlap:15,Separators:cfg.Separators,Strategy:strategy,TokenLimit:25,Languages:cfg.Languages}
  chunks,diag:=chunker.SplitWithDiagnostics(text,cfg)
  parentChild,parentDiag:=chunker.SplitParentChildWithDiagnostics(text,cfg,child)
  var embeddings []string
  for _,c:=range chunks {embeddings=append(embeddings,c.EmbeddingContent())}
  output=append(output,fixture{fmt.Sprintf("text-%d-strategy-%d",i,j),text,cfg,child,chunks,diag,embeddings,parentChild,parentDiag,chunker.ProfileDocument(text),chunker.DetectLanguage(text)})
 }}
 enc:=json.NewEncoder(os.Stdout);enc.SetIndent("","  ");if err:=enc.Encode(output);err!=nil {panic(err)}
}
