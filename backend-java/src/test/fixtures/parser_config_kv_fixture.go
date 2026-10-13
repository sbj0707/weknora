//go:build ignore

// go run backend-java/src/test/fixtures/parser_config_kv_fixture.go /tmp/go-parser-config-kv.json
package main

import (
 "context"
 "encoding/json"
 "fmt"
 "os"
 "reflect"
 "sort"
 "strings"
 "net/http/httptest"
 "github.com/gin-gonic/gin"
 "github.com/Tencent/WeKnora/internal/application/repository"
 "github.com/Tencent/WeKnora/internal/application/service"
 "github.com/Tencent/WeKnora/internal/handler"
 "github.com/Tencent/WeKnora/internal/middleware"
 "github.com/Tencent/WeKnora/internal/types"
 "github.com/Tencent/WeKnora/internal/utils"
 "gorm.io/driver/sqlite"
 "gorm.io/gorm"
 glog "gorm.io/gorm/logger"
)

type Case struct {
 Name string `json:"name"`
 Method string `json:"method"`
 Body string `json:"body"`
 Existing string `json:"existing"`
 Source string `json:"source"`
 Status int `json:"status"`
 Response any `json:"response"`
 Persisted any `json:"persisted"`
 Updated bool `json:"updated"`
}

func main() {
 gin.SetMode(gin.TestMode)
 os.Setenv("SSRF_WHITELIST", "127.0.0.1,docreader")
 os.Setenv("SSRF_WHITELIST_EXTRA", "")
 os.Setenv("SSRF_DNS_WHITELIST_ONLY", "false")
 utils.ResetSSRFWhitelistForTest()
 dir,err:=os.MkdirTemp("","parser-config-kv-go-");if err!=nil{panic(err)};defer os.RemoveAll(dir)
 db,err:=gorm.Open(sqlite.Open(dir+"/test.db"),&gorm.Config{Logger:glog.Default.LogMode(glog.Silent)});if err!=nil{panic(err)}
 sql,err:=db.DB();if err!=nil{panic(err)};defer sql.Close();sql.SetMaxOpenConns(1)
 schema,err:=os.ReadFile("migrations/sqlite/000000_init.up.sql");if err!=nil{panic(err)}
 if _,err=sql.Exec(string(schema));err!=nil{panic(err)}
 svc:=service.NewTenantService(repository.NewTenantRepository(db),nil)
 h:=handler.NewTenantHandler(svc,nil,nil,nil,nil,nil,nil)
 existing:=[]struct{Raw,Source string}{
  {"","null"},
  {`{"mineru_api_key":"secret <&>","mineru_server_api_key":"server","paddleocr_vl_cloud_token":"cloud","mineru_tier":"old","chat_parser_engine_rules":[{"engine":"simple","file_types":["txt"],"xlsx_first_row_as_header":false}]}`,"blob"},
  {`{"mineru_api_key":"TEXT ignored","mineru_enable_table":false}`,"text"},
  {`{"mineru_enable_formula":false,"mineru_enable_table":false,"mineru_enable_ocr":false,"mineru_cloud_enable_formula":false,"mineru_cloud_enable_table":false,"mineru_cloud_enable_ocr":false,"odl_hybrid_fallback":false,"odl_markdown_with_html":false,"paddleocr_vl_use_seal_recognition":false,"paddleocr_vl_use_chart_recognition":false,"paddleocr_vl_cloud_use_seal_recognition":false,"paddleocr_vl_cloud_use_chart_recognition":false}`,"blob"},
 }
 fields:=map[string]bool{};reflected:=reflect.TypeOf(types.ParserEngineConfig{});for i:=0;i<reflected.NumField();i++{field:=reflected.Field(i);fields[strings.Split(field.Tag.Get("json"),",")[0]]=true}
 bodies:=[]string{`{}`,`null`,`{} {}`,``, ` `,`{`,`[]`,`true`,`1e310`,`"text"`,`{"unknown":1e310}`,`{"mineru_api_key":"***"}`,`{"mineru_server_api_key":"***","paddleocr_vl_cloud_token":""}`,`{"chat_parser_engine_rules":null}`,`{"chat_parser_engine_rules":[]}`,
  `{"chat_parser_engine_rules":[null,{}]}`,`{"chat_parser_engine_rules":[{"engine":"simple","file_types":[".txt",null],"xlsx_first_row_as_header":false}]}`,
  `{"chat_parser_engine_rules":[{"engine":"a","file_types":["txt","pdf"]}],"chat_parser_engine_rules":[{"file_types":[null]}]}`,
  `{"chat_parser_engine_rules":[{"engine":"a"},{"engine":"b"}],"chat_parser_engine_rules":[{}],"chat_parser_engine_rules":[{},{}]}`,
  `{"mineru_api_key":5,"mineru_api_key":"ok"}`,`{"mineru_api_key":"a","mineru_api_key":null}`,
  `{"mineru_enable_table":false,"mineru_enable_table":null}`,`{"mineru_server_api_Key":"folded"}`,
  `{"mineru_language":"\ud800","unknown":"\udfff"}`,`{"mineru_endpoint":"  http://127.0.0.1:9999/base  "}`,
  `{"mineru_endpoint":"file:///etc/passwd"}`,`{"mineru_endpoint":"http://169.254.169.254/"}`,
  `{"mineru_vlm_server_url":"http://169.254.169.254/"}`,`{"odl_hybrid_url":"http://169.254.169.254/"}`,`{"paddleocr_vl_endpoint":"http://169.254.169.254/"}`,
 }
 // Sort names so fixture order is independent of Go map iteration.
 keys:=make([]string,0,len(fields));for key:=range fields{keys=append(keys,key)}
 sort.Strings(keys)
 for _,key:=range keys{if key=="chat_parser_engine_rules"{continue};for _,value:=range []string{`null`,`false`,`true`,`"test"`,`""`,`0`,`{}`,`[]`}{bodies=append(bodies,fmt.Sprintf(`{"%s":%s}`,key,value))}}
 for _,value:=range []string{`0`,`true`,`"text"`,`{}`,`[]`,`null`}{for _,key:=range []string{"file_types","engine","xlsx_first_row_as_header"}{bodies=append(bodies,fmt.Sprintf(`{"chat_parser_engine_rules":[{"%s":%s}]}`,key,value))}}
 cases:=[]Case{}
 run:=func(c Case){
  if _,err=sql.Exec("DELETE FROM tenants");err!=nil{panic(err)}
  var stored any;if c.Source=="blob"{stored=[]byte(c.Existing)}else if c.Source=="text"{stored=c.Existing}
  if _,err=sql.Exec("INSERT INTO tenants(id,name,business,parser_engine_config,updated_at) VALUES(1,'Fixture','test',?,'2001-01-01 00:00:00')",stored);err!=nil{panic(err)}
  tenant,e:=repository.NewTenantRepository(db).GetTenantByID(context.Background(),1);if e!=nil{panic(e)}
  router:=gin.New();router.Use(middleware.ErrorHandler());router.Use(func(ctx *gin.Context){ctx.Set(types.TenantInfoContextKey.String(),tenant);requestCtx:=context.WithValue(ctx.Request.Context(),types.TenantRoleContextKey,types.TenantRoleOwner);requestCtx=context.WithValue(requestCtx,types.TenantInfoContextKey,tenant);ctx.Request=ctx.Request.WithContext(requestCtx)})
  router.GET("/api/v1/tenants/kv/:key",h.GetTenantKV);router.PUT("/api/v1/tenants/kv/:key",h.UpdateTenantKV)
  w:=httptest.NewRecorder();request:=httptest.NewRequest(c.Method,"/api/v1/tenants/kv/parser-engine-config",strings.NewReader(c.Body));request.Header.Set("Content-Type","application/json");router.ServeHTTP(w,request)
  c.Status=w.Code;if e=json.Unmarshal(w.Body.Bytes(),&c.Response);e!=nil{panic(e)}
  var persisted *string;var updated string;if e=sql.QueryRow("SELECT CAST(parser_engine_config AS TEXT),CAST(updated_at AS TEXT) FROM tenants WHERE id=1").Scan(&persisted,&updated);e!=nil{panic(e)}
  if persisted!=nil{if e=json.Unmarshal([]byte(*persisted),&c.Persisted);e!=nil{panic(e)}}
  c.Updated=updated!="2001-01-01 00:00:00";cases=append(cases,c)
 }
 for i,old:=range existing{run(Case{Name:fmt.Sprintf("get-%d",i),Method:"GET",Existing:old.Raw,Source:old.Source});for j,body:=range bodies{run(Case{Name:fmt.Sprintf("put-%d-%d",i,j),Method:"PUT",Body:body,Existing:old.Raw,Source:old.Source})}}
 out:=os.Stdout;if len(os.Args)>1{out,err=os.Create(os.Args[1]);if err!=nil{panic(err)};defer out.Close()};if err=json.NewEncoder(out).Encode(cases);err!=nil{panic(err)}
 fmt.Fprintf(os.Stderr,"%d actual Go TenantHandler + TenantService + GORM production-schema cases\n",len(cases))
}
