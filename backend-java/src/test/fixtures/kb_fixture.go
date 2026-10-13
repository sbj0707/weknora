//go:build ignore

// Run from repository root to refresh contracts after auditing Go changes.
package main
import (
 "encoding/json"
 "fmt"
 "os"
 "reflect"
 "strings"
 "github.com/Tencent/WeKnora/internal/types"
)
func schema(original reflect.Type) any {
 t:=original;pointer:=false;for t.Kind()==reflect.Pointer {pointer=true;t=t.Elem()}
 zero,_:=json.Marshal(reflect.Zero(original).Interface())
 out:=map[string]any{"pointer":pointer,"zero":json.RawMessage(zero),"kind":t.Kind().String()}
 if t.PkgPath()=="time"||t.PkgPath()=="gorm.io/gorm" {out["kind"]="timestamp";return out}
 if t.Kind()==reflect.Slice||t.Kind()==reflect.Array {out["items"]=schema(t.Elem());return out}
 if t.Kind()!=reflect.Struct {return out}
 underlyingZero,_:=json.Marshal(reflect.Zero(t).Interface());out["object_zero"]=json.RawMessage(underlyingZero)
 fields:=map[string]any{}
 for i:=0;i<t.NumField();i++ {f:=t.Field(i);if f.PkgPath!="" {continue};tag:=strings.Split(f.Tag.Get("json"),",");name:=tag[0];if name==""||name=="-" {continue};child:=schema(f.Type).(map[string]any);for _,option:=range tag[1:] {if option=="omitempty" {child["omit_empty"]=true}};fields[name]=child}
 out["fields"]=fields;return out
}
func main() {
 kb:=types.KnowledgeBase{}
 kb.EnsureDefaults()
 data,err:=json.Marshal(&kb);if err!=nil {panic(err)}
 if len(os.Args)==1 {fmt.Println(string(data));return}
 if os.Args[1]=="schema" {data,err=json.Marshal(schema(reflect.TypeOf(kb)));if err!=nil {panic(err)};fmt.Println(string(data));return}
 inputs:=[]string{
  `{"id":"faq-nil","type":"faq"}`,
  `{"type":"document","auto_tag_config":{"enabled":true,"max_tags":0,"skip_if_tagged":false},"profile_config":{"enabled":true,"custom_instructions":" keep  "}}`,
  `{"type":"document","auto_tag_config":{"enabled":true,"max_tags":100},"storage_provider_config":{},"cos_config":{"provider":"s3","secret_id":"legacy","secret_key":"legacy-secret","bucket_name":"legacy"}}`,
  `{"type":"document","chunking_config":{"enable_multimodal":false,"separators":[],"strategy":"semantic","unknown":"stripped"},"storage_backend_id":"","vector_store_id":""}`,
  `{"type":"wiki","auto_tag_config":{"enabled":true},"profile_config":{"enabled":true},"question_generation_config":{"enabled":false,"question_count":0},"wiki_config":{}}`,
  `{}`,
  `{"id":"faq-golden","type":"faq","faq_config":{},"indexing_strategy":{"vector_enabled":false,"keyword_enabled":true,"wiki_enabled":false,"graph_enabled":false}}`,
  `{"id":"wiki-golden","type":"wiki","storage_config":{"secret_id":"hidden-id","secret_key":"hidden-key","region":"cn"},"vlm_config":{"api_key":"hidden-api-key","enabled":true,"model_id":"vision"},"extract_config":{"enabled":true},"indexing_strategy":{"vector_enabled":false,"keyword_enabled":false,"wiki_enabled":true,"graph_enabled":true},"chunking_config":{"chunk_size":512,"split_markers":["unknown-go-field"],"separators":["\n\n"]}}`,
 }
 cases:=[]map[string]any{}
 for _,input:=range inputs {kb=types.KnowledgeBase{};if err=json.Unmarshal([]byte(input),&kb);err!=nil {panic(err)};kb.EnsureDefaults();var raw map[string]any;if err=json.Unmarshal([]byte(input),&raw);err!=nil {panic(err)};db:=map[string]any{};for key,value:=range raw {if key=="storage_config" {key="cos_config"};if _,ok:=value.(map[string]any);ok {bytes,_:=json.Marshal(value);db[key]=string(bytes)}else{db[key]=value}};indexing,_:=json.Marshal(kb.IndexingStrategy);db["indexing_strategy"]=string(indexing);expected,_:=json.Marshal(&kb);cases=append(cases,map[string]any{"input":json.RawMessage(input),"db":db,"go":json.RawMessage(expected)})}
 data,err=json.Marshal(cases);if err!=nil {panic(err)};fmt.Println(string(data))
}
