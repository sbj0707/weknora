//go:build ignore

// Generates response contracts by invoking the actual Go model catalog handlers.
package main

import (
 "context"
 "encoding/json"
 "fmt"
 "net/http/httptest"
 "net/url"
 "os"
 "strings"
 "github.com/gin-gonic/gin"
 "github.com/Tencent/WeKnora/internal/handler"
 rt "github.com/Tencent/WeKnora/internal/models/runtime"
 "github.com/Tencent/WeKnora/internal/types"
)
func main() {
 gin.SetMode(gin.TestMode)
 cases:=[]any{}
 capture:=func(path,method,body,role string) {
  w:=httptest.NewRecorder();c,_:=gin.CreateTestContext(w);req:=httptest.NewRequest(method,path,strings.NewReader(body));req.Header.Set("Content-Type","application/json");c.Request=req.WithContext(context.WithValue(req.Context(),types.TenantRoleContextKey,types.TenantRole(role)))
  h:=&handler.ModelHandler{};if strings.Contains(path,"/providers"){h.ListModelProviders(c)}else{h.ResolveModelCatalog(c)}
  var result any;if err:=json.Unmarshal(w.Body.Bytes(),&result);err!=nil{panic(fmt.Sprintf("%s %s %s errors=%v body=%s",method,path,body,c.Errors,w.Body.String()))}
  cases=append(cases,map[string]any{"path":path,"method":method,"body":body,"role":role,"status":w.Code,"response":result})
 }
 for _,role:=range []string{"viewer","admin"} {
  for _,kind:=range []string{"","chat","vllm","embedding","rerank","asr"}{capture("/api/v1/models/providers?model_type="+kind,"GET","",role)}
  for _,p:=range rt.List(){capture("/api/v1/models/catalog/resolve?"+url.Values{"provider":{p.ID},"model":{"unknown-model"}}.Encode(),"GET","",role)}
  for _,values:=range []url.Values{
   {"provider":{"azure_openai"},"model":{"deployment/name: +"},"api_version":{"2024-10+ space"},"base_url":{"https://azure.invalid"}},
   {"provider":{"aliyun"},"model":{"qwen3-vl-embedding"},"model_type":{"embedding"}},
   {"provider":{"volcengine"},"model":{"doubao-embedding-vision-250615"},"model_type":{"embedding"}},
   {"provider":{"openai"},"model":{"gpt-5"},"base_url":{"https://relay.invalid/anthropic/v1"}},
   {"provider":{"generic"},"model":{"private"},"model_type":{"invalid"},"api_key":{"ignored-secret"},"secret_key":{"ignored-secret"}},
  }{capture("/api/v1/models/catalog/resolve?"+values.Encode(),"GET","",role)}
  for _,body:=range []string{
   `{"provider":"openai","model":"gpt-5","spec":{"api":"anthropic-messages","reasoning":false,"thinking_levels":{"off":null,"max":"max"},"compat":{"thinking_mode":"adaptive"}}}`,
   `{"provider":"gemini","model":"unknown","base_url":"https://relay.invalid/openai/v1","spec":{"input":["image"],"context_window":9000,"max_output_tokens":1000}}`,
   `{"provider":null,"model":"custom","spec":{"reasoning":null,"input":null,"context_window":0,"unknown_field":true}}`,
  }{capture("/api/v1/models/catalog/resolve","POST",body,role)}
 }
 data,err:=json.Marshal(cases);if err!=nil{panic(err)};if len(os.Args)>1{if err=os.WriteFile(os.Args[1],data,0644);err!=nil{panic(err)}}else{fmt.Println(string(data))}
}
