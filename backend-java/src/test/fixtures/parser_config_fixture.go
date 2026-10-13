//go:build ignore
package main
import (
 "encoding/json"
 "fmt"
 "os"
 "reflect"
 "strings"
 "github.com/Tencent/WeKnora/internal/types"
 _ "github.com/Tencent/WeKnora/internal/infrastructure/docparser"
)
type routingCase struct {
 Name string `json:"name"`; Rules []types.ParserEngineRule `json:"rules"`; FileType string `json:"file_type"`; Prefer string `json:"prefer"`
 Default string `json:"default"`; Knowledge string `json:"knowledge"`; Chat string `json:"chat"`; Rule *types.ParserEngineRule `json:"rule"`
}
type overrideCase struct{Name string `json:"name"`;Config json.RawMessage `json:"config"`;Overrides map[string]string `json:"overrides"`}
func main(){
 var output struct{ Routing []routingCase `json:"routing"`; Overrides []overrideCase `json:"overrides"`}
 t,f:=true,false
 lists:=[][]types.ParserEngineRule{
 nil,{},{{FileTypes:[]string{"i","οσ","k"},Engine:"unicode-lower"}},{{FileTypes:[]string{"pdf",".DOCX"},Engine:"mineru"}},
 {{FileTypes:[]string{"XLSX"},Engine:" builtin ",XLSXFirstRowAsHeader:&t}},
 {{FileTypes:[]string{"xlsx",".xls"},Engine:"",XLSXFirstRowAsHeader:&f}},
 {{FileTypes:[]string{"pdf",""},Engine:"\u00a0 builtin \u3000"},{FileTypes:[]string{"pdf","pptx"},Engine:"markitdown"}},
 {{FileTypes:[]string{"txt","pdf"},Engine:""},{FileTypes:[]string{"txt","pdf"},Engine:"simple"}},
 {{FileTypes:[]string{" .PPTX\u00a0","...pdf",""},Engine:" custom "}},
 {{FileTypes:[]string{"json", "xlsx"},Engine:"mineru_cloud",XLSXFirstRowAsHeader:&t}},
 }
 fileTypes:=[]string{"", "pdf", ".PDF", " .PPTX\u3000", "ppt", "xlsx", ".XLS", " .docx ", "JSON", ".TXT", "url", "...pdf", "\u0085\u00a0", ".İ", "ΟΣ", "K"}
 for _,preferred:=range []string{"","anydoc"}{
  if preferred==""{types.SetPreferParserEngine(nil)}else{types.SetPreferParserEngine(func(ft string)string{if ft=="docx"||ft=="pptx"||ft=="xlsx"{return "anydoc"};return ""})}
  for i,rules:=range lists{kb:=types.ChunkingConfig{ParserEngineRules:rules};chat:=types.ParserEngineConfig{ChatParserEngineRules:rules};for j,ft:=range fileTypes{output.Routing=append(output.Routing,routingCase{fmt.Sprintf("route-%s-%d-%d",preferred,i,j),rules,ft,preferred,types.DefaultParserEngine(ft),kb.ResolveParserEngine(ft),chat.ResolveChatParserEngine(ft),kb.ResolveParserEngineRule(ft)})}}
 }
 types.SetPreferParserEngine(nil)
 add:=func(name string,config interface{}){raw,_:=json.Marshal(config);var parsed *types.ParserEngineConfig;if err:=json.Unmarshal(raw,&parsed);err!=nil{panic(err)};output.Overrides=append(output.Overrides,overrideCase{name,raw,parsed.ToOverridesMap()})}
 add("nil",nil);add("empty",map[string]interface{}{});add("unknown",map[string]interface{}{"unknown":"ignored"})
 typ:=reflect.TypeOf(types.ParserEngineConfig{});all:=map[string]interface{}{}
 for i:=0;i<typ.NumField();i++{field:=typ.Field(i);key:=strings.Split(field.Tag.Get("json"),",")[0];if key=="chat_parser_engine_rules"{continue};if field.Type.Kind()==reflect.String{for j,value:=range []string{""," value \u00a0","AUTO"}{add(fmt.Sprintf("%s-%d",key,j),map[string]interface{}{key:value})};all[key]=" value "}else{for j,value:=range []interface{}{nil,true,false}{add(fmt.Sprintf("%s-%d",key,j),map[string]interface{}{key:value})};all[key]=false}}
 add("all",all)
 for _,method:=range []string{"auto"," oCr "," TXT\u3000","bad",""," "}{for _,legacy:=range []interface{}{nil,true,false}{add(fmt.Sprintf("method-%s-%v",method,legacy),map[string]interface{}{"mineru_parse_method":method,"mineru_enable_ocr":legacy})}}
 enc:=json.NewEncoder(os.Stdout);enc.SetIndent("","  ");if err:=enc.Encode(output);err!=nil{panic(err)}
}
