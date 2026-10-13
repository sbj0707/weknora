//go:build ignore

// Production Go incremental extractor/progress/assembler oracles. Uses no external endpoint.
package main
import (
 "context"
 "encoding/json"
 "os"
 "strings"
 "github.com/Tencent/WeKnora/internal/logger"
 "github.com/Tencent/WeKnora/internal/models/api"
 "github.com/Tencent/WeKnora/internal/types"
)
func main(){
 logger.SetOutput(os.Stderr)
 var extracts, progress, assemblies []any
 for _,raw:=range []string{
  `{"thought":"hello 中文😀 world"}`,`{"other":"x","thought":"a\nb\t\\c\"d\/e\bf\r\fg"}`,`{"thought":"\u4e2d\u6587\uD83D\uDE00"}`,`{"thought":"\uZZZZ\x\q"}`,`{"thought":"","other":"ignored"}`,`{"thought":42,"thought":"later"}`,`{"thought" ::: "permissive"}`,`{"thought":"partial\u4e`, `{"thought":"trailing\\`, `{"nested":{"thought":"first"},"thought":"second"}`,`{"content":"miss"}`,`{"thought":"escape \\"end"}`,`{"thought":"\u１２３４"}`,
 } {for _,size:=range []int{1,2,5,9999}{runes:=[]rune(raw);fragments:=[]string{};outputs:=[]string{};dones:=[]bool{};x:=api.NewJSONFieldExtractor("thought");for len(runes)>0{n:=min(size,len(runes));chunk:=string(runes[:n]);runes=runes[n:];fragments=append(fragments,chunk);outputs=append(outputs,x.Feed(chunk));dones=append(dones,x.IsDone())};fragments=append(fragments,"post-close");outputs=append(outputs,x.Feed("post-close"));dones=append(dones,x.IsDone());extracts=append(extracts,map[string]any{"field":"thought","fragments":fragments,"outputs":outputs,"done":dones})}}
 for _,cfg:=range []struct{Tool string;Chunks []string}{
  {"write_sandbox_file",[]string{`{"path":"dir/中😀.txt",`, `"content":"hello\n`, `world\n`, `last"}`}},
  {"write_sandbox_file",[]string{`{"content":"\n\n",`, `"path":"notes.txt"}`}},
  {"write_sandbox_file",[]string{`{"path":"x","content":"`+strings.Repeat("line\\n",12)+`"}`}},
  {"write_sandbox_file",[]string{`{"path":"\u4e2d.txt","content":"\uD83D\uDE00\n"}`}},
  {"write_sandbox_file",[]string{`{"content":"", "path":""}`}},
  {"edit_sandbox_file",[]string{`{"path":"p", "edits":[{"old_string":"a\nb", "new_string":"中😀\n"},{"oldText":"old", "newText":"new\nnew\n"}]}`}},
  {"edit_sandbox_file",[]string{`{"path":"p", "old_string":"a\n", "new_string":"b\nc"}`}},
  {"edit_sandbox_file",[]string{`{"path":"p", "edits":[null,4,{"old_string":"","oldText":"ignored","newText":"new"}]}`}},
  {"edit_sandbox_file",[]string{`{"path":"p", "new_string":"unterminated`}},
  {"edit_sandbox_file",[]string{`{"path":"p", "new_string":"escape\`}},
  {"edit_sandbox_file",[]string{`{"path":"p", "edits": [ {"oldText":"a","newText":"b"},`}},
  {"other",[]string{`{"path":"p", "content":"x"}`}},
 } {p:=api.NewSandboxFileProgress(cfg.Tool);out:=[]any{};for _,chunk:=range cfg.Chunks{data,ok:=p.Feed(chunk);if ok{out=append(out,data)}else{out=append(out,nil)}};progress=append(progress,map[string]any{"tool":cfg.Tool,"fragments":cfg.Chunks,"outputs":out})}
 for _,deltas:=range [][]api.Delta{
  {{Reasoning:"first"},{Reasoning:"second"},{Content:"answer"},{FinishReason:"stop"}},
  {{Reasoning:"thought"},{FinishReason:"stop"}},
  {{ToolCalls:[]api.ToolCallDelta{{Index:7,ID:"seven",Name:"search"}}},{ToolCalls:[]api.ToolCallDelta{{Index:2,ID:"two",Name:"find"},{Index:7,Name:"search",Arguments:"{"}}},{ToolCalls:[]api.ToolCallDelta{{Index:7,Arguments:"}"}}},{FinishReason:"tool_calls"}},
  {{ToolCalls:[]api.ToolCallDelta{{Index:3,ID:"thought",Name:"think"}}},{ToolCalls:[]api.ToolCallDelta{{Index:3,Name:"ing",Arguments:`{"thought":"a\u4e`}}},{ToolCalls:[]api.ToolCallDelta{{Index:3,Arguments:`2d\nlast"}`}}},{Content:"final",FinishReason:"stop"}},
  {{ToolCalls:[]api.ToolCallDelta{{Index:0,ID:"write",Name:"write_sandbox_file"}}},{ToolCalls:[]api.ToolCallDelta{{Index:0,Arguments:`{"path":"p","content":"one\n`}}},{ToolCalls:[]api.ToolCallDelta{{Index:0,Arguments:`two\n"}`}}},{FinishReason:"tool_calls"}},
  {{Content:"premature"}}, {},
 } {ch:=make(chan types.StreamResponse,100);a:=api.NewStreamAssembler(context.Background(),"fixture");for _,d:=range deltas{a.Process(ch,d)};a.EndAtEOF(ch);close(ch);out:=[]types.StreamResponse{};for e:=range ch{out=append(out,e)};assemblies=append(assemblies,map[string]any{"deltas":deltas,"events":out})}
 body,err:=json.MarshalIndent(map[string]any{"extractors":extracts,"progress":progress,"assemblies":assemblies},"","  ");if err!=nil{panic(err)};if err=os.WriteFile(os.Args[1],body,0644);err!=nil{panic(err)}
}
