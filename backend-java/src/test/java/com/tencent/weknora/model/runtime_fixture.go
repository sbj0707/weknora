//go:build ignore

// Production metadata and contract generator; Java never executes Go at runtime.
package main

import (
 "bytes"
 "compress/gzip"
 "encoding/base64"
 "encoding/json"
 "fmt"
 "os"
 "reflect"
 "strings"
 "github.com/Tencent/WeKnora/internal/models"
 "github.com/Tencent/WeKnora/internal/models/api"
 rt "github.com/Tencent/WeKnora/internal/models/runtime"
 "github.com/Tencent/WeKnora/internal/types"
)

var compatTypes=map[string]any{"openai_completions":api.OpenAICompletionsCompat{},"openai_responses":api.OpenAIResponsesCompat{},"anthropic_messages":api.AnthropicMessagesCompat{},"google_generative_ai":api.GoogleGenerativeAICompat{},"rerank":api.RerankCompat{},"embeddings":api.EmbeddingsCompat{},"transcriptions":api.TranscriptionsCompat{}}
func schema(t reflect.Type) any {
 nullable:=false
 for t.Kind()==reflect.Pointer {nullable=true;t=t.Elem()}
 out:=map[string]any{"kind":t.Kind().String(),"pointer":nullable}
 switch t.Kind() {
 case reflect.Struct:
  fields:=map[string]any{}
  for i:=0;i<t.NumField();i++ {f:=t.Field(i);if f.PkgPath!=""||f.Type.Kind()==reflect.Func {continue};tag:=strings.Split(f.Tag.Get("json"),",");name:=tag[0];if name=="-"{continue};if name==""{name=f.Name};s:=schema(f.Type).(map[string]any);s["field"]=f.Name;s["omit"]=len(tag)>1&&tag[1]=="omitempty";fields[name]=s}
  out["fields"]=fields
 case reflect.Map,reflect.Slice,reflect.Array:out["items"]=schema(t.Elem())
 }
 return out
}
func settings(value any,compat any) map[string]any {
 v:=reflect.ValueOf(value);t:=reflect.TypeOf(compat);out:=map[string]any{}
 for i:=0;i<t.NumField();i++ {f:=t.Field(i);name:=strings.Split(f.Tag.Get("json"),",")[0];if name==""{continue};out[name]=v.FieldByName(f.Name).Interface()}
 // Fields existing only on the resolved setting are included as well.
 for _,name:=range []string{"API","TruncatePromptTokens"} {f:=v.FieldByName(name);if f.IsValid() {key:="api";if name=="TruncatePromptTokens"{key="truncate_prompt_tokens"};out[key]=f.Interface()}}
 return out
}
func data() map[string]any {
 providers:=[]any{}
 for _,p:=range rt.New().List() {v:=reflect.ValueOf(*p.Definition);d:=map[string]any{};t:=v.Type();for i:=0;i<v.NumField();i++{if t.Field(i).Type.Kind()!=reflect.Func{d[t.Field(i).Name]=v.Field(i).Interface()}};d["PreferAPIHook"]=p.PreferAPI!=nil;d["EndpointHook"]=p.Endpoint!=nil;providers=append(providers,map[string]any{"definition":d,"models":p.Models()})}
 schemas:=map[string]any{}
 for key,value:=range compatTypes {schemas[key]=schema(reflect.TypeOf(value))}
 defaults:=map[string]any{"openai_completions":settings(api.DefaultOpenAICompletions(),api.OpenAICompletionsCompat{}),"openai_responses":settings(api.DefaultOpenAIResponses(),api.OpenAIResponsesCompat{}),"anthropic_messages":settings(api.DefaultAnthropicMessages(),api.AnthropicMessagesCompat{}),"google_generative_ai":settings(api.DefaultGoogleGenerativeAI(),api.GoogleGenerativeAICompat{}),"rerank":settings(api.DefaultRerank(),api.RerankCompat{}),"embeddings":settings(api.DefaultEmbeddings(),api.EmbeddingsCompat{}),"transcriptions":settings(api.DefaultTranscriptions(),api.TranscriptionsCompat{})}
 return map[string]any{"providers":providers,"schemas":schemas,"defaults":defaults,"model_schema":schema(reflect.TypeOf(models.ModelSpec{})),"patch_schema":schema(reflect.TypeOf(rt.ModelSpecPatch{})),"overlay_schema":schema(reflect.TypeOf(rt.OverlayFile{}))}
}
func resolved(r *rt.Resolved) any {
 return map[string]any{"provider":r.Vendor.ID,"api":r.API,"base_url":r.BaseURL,"remote_model":r.RemoteModel,"cataloged":r.Cataloged,"model":r.Spec,"capabilities":r.Capabilities(),"settings":map[string]any{"openai_completions":settings(r.OpenAICompletions,api.OpenAICompletionsCompat{}),"openai_responses":settings(r.OpenAIResponses,api.OpenAIResponsesCompat{}),"anthropic_messages":settings(r.AnthropicMessages,api.AnthropicMessagesCompat{}),"google_generative_ai":settings(r.GoogleGenerativeAI,api.GoogleGenerativeAICompat{}),"rerank":settings(r.Rerank,api.RerankCompat{}),"embeddings":settings(r.Embeddings,api.EmbeddingsCompat{}),"transcriptions":settings(r.Transcriptions,api.TranscriptionsCompat{})},"embedding_api":r.EmbeddingAPI,"rerank_api":r.RerankAPI,"transcription_api":r.TranscriptionAPI}
}
func fixtures() any {
 cases:=[]any{}
 runtime:=rt.New()
 add:=func(ref rt.Ref,deployment,console string){candidate:=runtime;if deployment!="" {candidate=rt.New();if err:=candidate.Reload([]byte(deployment),"");err!=nil {panic(err)}};if console!="" {next,err:=candidate.WithOverlay([]byte(console),"");if err!=nil{panic(err)};candidate=next};r,err:=candidate.Resolve(ref);input:=map[string]any{"provider":ref.Provider,"model":ref.Model,"base_url":ref.BaseURL,"model_type":ref.ModelType,"extra":ref.Extra,"spec":ref.Override,"truncate_prompt_tokens":ref.TruncatePromptTokens};c:=map[string]any{"input":input,"deployment":deployment,"console":console};if err!=nil {c["error"]=err.Error()}else{c["result"]=resolved(r)};cases=append(cases,c)}
 for _,p:=range runtime.List() {
  for _,m:=range p.Models() {name:=m.ID;if name==""{name=strings.ReplaceAll(m.Match,"*","snapshot")};add(rt.Ref{Provider:p.ID,Model:name,ModelType:m.Type},"","");for _,alias:=range m.Aliases{add(rt.Ref{Provider:p.ID,Model:alias,ModelType:m.Type},"","")}}
  for _,kind:=range []types.ModelType{types.ModelTypeKnowledgeQA,types.ModelTypeVLLM,types.ModelTypeEmbedding,types.ModelTypeRerank,types.ModelTypeASR} {add(rt.Ref{Provider:p.ID,Model:"unknown-model",ModelType:kind},"","")}
  for _,url:=range []string{"https://relay.invalid/v1","https://API.OPENAI.COM:443/v1","https://relay.invalid/anthropic/v1","https://relay.invalid/openai/v1"} {add(rt.Ref{Provider:p.ID,Model:"unknown-model",BaseURL:url},"","")}
  for _,control:=range []string{"none","enable_thinking","thinking_type","custom"} {add(rt.Ref{Provider:p.ID,Model:"unknown-model",Extra:map[string]string{"thinking_control":control}},"","")}
  yes:=true
  add(rt.Ref{Provider:p.ID,Model:"unknown-model",Override:&types.ModelSpecOverride{API:"openai-completions",Reasoning:&yes,Input:[]string{"text","image"},ContextWindow:12345,MaxOutputTokens:678,ThinkingLevels:map[string]*string{"off":nil,"max":api.StringPtr("max")},Compat:map[string]any{"thinking_format":"chat-template-kwargs","supports_reasoning_effort":true,"extra_body":map[string]any{"sample":true}}}},"","")
 }
 for _,ref:=range []rt.Ref{{Model:"GPT-5-FUTURE",BaseURL:"https://api.openai.com/v1"},{Provider:"custom-unknown",Model:"custom"},{Provider:"generic",Model:"custom",Extra:map[string]string{"api":"bad-api"}},{Provider:"generic",Model:"custom",Override:&types.ModelSpecOverride{API:"openai-completions",Compat:map[string]any{"typo":true}}}} {add(ref,"","")}
 deployment:=`{"providers":{"openai":{"base_url":"https://gateway.invalid/v1","compat":{"thinking_format":"enable-thinking","extra_body":{"a":1}},"models":[{"id":"gpt-5","context_window":70000}]},"lab":{"api":"anthropic-messages","base_url":"https://lab.invalid","thinking_levels":{"minimal":null},"models":[{"id":"lab-reasoner","reasoning":true,"context_window":16000}]}}}`
 console:=`{"providers":{"openai":{"models":[{"id":"gpt-5","max_output_tokens":1234}],"model_overrides":{"gpt-5":{"thinking_levels":{"max":"max"},"compat":{"extra_body":{"b":2}}}}},"lab":{"models":[{"id":"lab-reasoner","reasoning":false}]}}}`
 for _,name:=range []string{"gpt-5","gpt-5.9","unknown"} {add(rt.Ref{Provider:"openai",Model:name},deployment,console)}
 add(rt.Ref{Provider:"lab",Model:"lab-reasoner"},deployment,console)
 for _,overlay:=range []string{
  `{"providers":{"lab":{"models":[{"id":"custom","cost":{},"reasoning":false,"input":null,"compat":null}]}}}`,
  `{"providers":{"openai":{"models":[{"id":"gpt-5","reasoning":null,"aliases":["custom-alias"],"thinking_levels":{"max":null},"compat":{"extra_body":{"sample":{"a":1}}}}],"model_overrides":{"gpt-5":{"aliases":["second-alias"],"thinking_levels":{"xhigh":"xhigh"},"compat":{"extra_body":{"sample":{"b":2}}}}}}}}`,
  `{"providers":{"lab":{"api":"google-generative-ai","compat":{"thinking_budgets":{"low":1234},"extra_generation_config":{"sample":true}},"models":[{"id":"custom","reasoning":true,"thinking_levels":{"xhigh":"xhigh"}}]}}}`,
 } {provider:="lab";model:="custom";if strings.Contains(overlay,`"openai"`){provider="openai";model="second-alias"};add(rt.Ref{Provider:provider,Model:model},overlay,"")}
 return cases
}
func main() {
 mode:=os.Args[1]
 if mode=="fixtures" {out,err:=json.Marshal(fixtures());if err!=nil{panic(err)};fmt.Println(string(out));return}
 raw,err:=json.Marshal(data());if err!=nil{panic(err)}
 var buf bytes.Buffer;gz:=gzip.NewWriter(&buf);gz.Write(raw);gz.Close();encoded:=base64.StdEncoding.EncodeToString(buf.Bytes())
 fmt.Println("package com.tencent.weknora.model;\n// Generated from Go built-in definitions/catalog by runtime_fixture.go. No Go runtime dependency.\nfinal class BuiltinCatalogData {\n static String encoded() { return String.join(\"\", new String[]{")
 for i:=0;i<len(encoded);i+=12000 {end:=i+12000;if end>len(encoded){end=len(encoded)};fmt.Printf("\"%s\",\n",encoded[i:end])}
 fmt.Println("}); }\n}")
}
