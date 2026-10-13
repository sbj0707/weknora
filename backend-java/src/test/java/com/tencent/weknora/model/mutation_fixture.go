//go:build ignore

// Generate native-write parity fixtures with the actual Go HTTP handlers and typed DTO/storage encoding.
package main

import (
 "context"
 "encoding/json"
 "net/http/httptest"
 "os"
 "strings"
 "time"
 "github.com/gin-gonic/gin"
 "github.com/Tencent/WeKnora/internal/handler"
 "github.com/Tencent/WeKnora/internal/types"
 "github.com/Tencent/WeKnora/internal/types/interfaces"
)
type svc struct {interfaces.ModelService; model *types.Model}
func(s *svc)GetModelByID(context.Context,string)(*types.Model,error){return s.model,nil}
func(s *svc)CreateModel(_ context.Context,m *types.Model)error{m.ID="fixture-model";m.Status=types.ModelStatusActive;m.CreatedAt=time.Date(2026,10,1,0,0,0,0,time.UTC);m.UpdatedAt=m.CreatedAt;s.model=m;return nil}
func(s *svc)UpdateModel(_ context.Context,m *types.Model)error{s.model=m;return nil}
func main(){
 gin.SetMode(gin.TestMode);cases:=[]any{}
 old:=`{"base_url":"","provider":"volcengine","api_key":"old-api","app_secret":"old-app","app_id":"old-id","interface_type":"old-interface","parameter_size":"7B","extra_config":{"secret_key":"old-secret","region":"old-region"},"custom_headers":{"old":"header"},"spec":{"reasoning":true}}`
 capture:=func(op,body string){
  s:=&svc{};m:=&types.Model{ID:"fixture-model",TenantID:1,Name:"old-name",DisplayName:"old display",Type:types.ModelTypeRerank,Source:types.ModelSourceRemote,Status:types.ModelStatusActive,CreatedAt:time.Date(2026,10,1,0,0,0,0,time.UTC),UpdatedAt:time.Date(2026,10,1,0,0,0,0,time.UTC)};json.Unmarshal([]byte(old),&m.Parameters);s.model=m
  w:=httptest.NewRecorder();c,_:=gin.CreateTestContext(w);req:=httptest.NewRequest("POST","/api/v1/models",strings.NewReader(body));req.Header.Set("Content-Type","application/json");ctx:=context.WithValue(req.Context(),types.TenantIDContextKey,uint64(1));ctx=context.WithValue(ctx,types.TenantRoleContextKey,types.TenantRoleAdmin);c.Request=req.WithContext(ctx);c.Set(types.TenantIDContextKey.String(),uint64(1));c.Params=gin.Params{{Key:"id",Value:"fixture-model"}}
  h:=handler.NewModelHandler(s);if op=="create"{h.CreateModel(c)}else{h.UpdateModel(c)}
  var response any;var storage any;entry:=map[string]any{"operation":op,"body":body,"old_parameters":json.RawMessage(old)}
  if len(c.Errors)>0 {entry["error"]=c.Errors.Last().Error()}else{if err:=json.Unmarshal(w.Body.Bytes(),&response);err!=nil{panic(err)};entry["response"]=response;value,err:=s.model.Parameters.Value();if err!=nil{panic(err)};json.Unmarshal(value.([]byte),&storage);entry["storage"]=storage}
  cases=append(cases,entry)
 }
 for _,body:=range []string{
  `{"name":"gpt-5\nname","display_name":"shown\tname","description":"text\u0001\nend","type":"KnowledgeQA","source":"remote","parameters":{"api_key":"new-key","provider":"openai","unknown":"ignored","spec":{"reasoning":false,"thinking_levels":{"off":null,"high":"high"},"compat":{"supports_reasoning_effort":true}}}}`,
  `{"name":"embedding","type":"Embedding","source":"remote","parameters":{}}`,
  `{"name":"custom","type":"ASR","source":"remote","parameters":{"provider":"openai","extra_config":{"language":null},"embedding_parameters":{"dimension":3072},"spec":{"input":["text",null]}}}`,
  `{"name":"custom","type":"other-type","source":"remote"}`,
  `{"name":"model","type":"KnowledgeQA","source":"remote","parameters":{"spec":{"thinking_levels":{"bogus":null}}}}`,
  `{"name":"model","type":"KnowledgeQA","source":"remote","parameters":{"extra_config":{"api":"bogus"}}}`,
 }{capture("create",body)}
 for _,body:=range []string{
  `{}`,
  `{"name":"new\nname","display_name":"new\tshown","description":"new\ntext","type":"Rerank","source":"remote","parameters":{"api_key":"ignored-new","app_secret":"ignored-new","provider":"volcengine","extra_config":{"region":"new-region","secret_key":"••••"}}}`,
  `{"type":"Rerank","source":"remote","parameters":{"provider":"volcengine","extra_config":{}}}`,
  `{"type":"Rerank","source":"remote","parameters":{"provider":"openai","extra_config":{"secret_key":"****","region":"new"}}}`,
  `{"type":"Rerank","source":"remote","parameters":{"provider":"openai"}}`,
  `{"type":"Rerank","source":"remote","parameters":{"provider":"openai","extra_config":{"secret_key":"typed-new"},"app_id":"new-id","interface_type":"new-interface","parameter_size":"99B"}}`,
  `{"display_name":null,"parameters":{"extra_config":null}}`,
  `{"parameters":{"spec":{"api":"wrong"}}}`,
 }{capture("update",body)}
 data,err:=json.MarshalIndent(cases,"","  ");if err!=nil{panic(err)};if err=os.WriteFile(os.Args[1],data,0644);err!=nil{panic(err)}
}
