//go:build ignore

// Go chat fallback/cache contract with an isolated loopback provider, no production models.
package main
import (
 "context"
 "encoding/json"
 "fmt"
 "net/http"
 "net/http/httptest"
 "os"
 "strings"
 "github.com/Tencent/WeKnora/internal/logger"
 "github.com/Tencent/WeKnora/internal/models/api"
 "github.com/Tencent/WeKnora/internal/models/api/openaicompletions"
 "github.com/Tencent/WeKnora/internal/models/api/openairesponses"
 "github.com/Tencent/WeKnora/internal/types"
 "github.com/Tencent/WeKnora/internal/utils"
)
func main(){os.Setenv("SSRF_WHITELIST","127.0.0.1");utils.ResetSSRFWhitelistForTest();logger.SetOutput(os.Stderr);var fixtures []any
 for _,protocol:=range []string{"openai-completions","openai-responses"}{for _,stream:=range []bool{false,true}{for _,scenario:=range []struct{Status int;Body,Session,Key,Retention string}{
  {400,"image input rejected","context-session","",""},
  {403,"multimodal not supported","context-session","explicit-session","long"},
  {503,"unsupported vision model","context-session","","none"},
  {500,"image exceeds max 4000 px","context-session","",""},
  {401,"unauthorized","context-session","",""},
  {400,"invalid temperature","context-session","",""},
  {400,"image rejected","","",""},
  {400,"image rejected",strings.Repeat("中😀",40),"",""},
 }{
  requests:=[]any{};attempt:=0
  server:=httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){var body any;json.NewDecoder(r.Body).Decode(&body);headers:=map[string]string{};for _,k:=range []string{"session_id","x-client-request-id","x-session-affinity"}{if v:=r.Header.Get(k);v!=""{headers[k]=v}};requests=append(requests,map[string]any{"body":body,"headers":headers});attempt++;if attempt==1{w.WriteHeader(scenario.Status);fmt.Fprint(w,scenario.Body);return};if stream{w.Header().Set("Content-Type","text/event-stream");if protocol=="openai-completions"{fmt.Fprint(w,"data: {\"choices\":[{\"delta\":{\"content\":\"answer\"},\"finish_reason\":\"stop\"}]}\ndata: [DONE]\n")}else{fmt.Fprint(w,"data: {\"type\":\"response.output_text.delta\",\"delta\":\"answer\"}\ndata: {\"type\":\"response.completed\"}\n")}}else if protocol=="openai-completions"{fmt.Fprint(w,`{"choices":[{"message":{"content":"answer"},"finish_reason":"stop"}]}`)}else{fmt.Fprint(w,`{"output":[{"type":"message","content":[{"type":"output_text","text":"answer"}]}]}`)}}))
  endpoint:=api.Endpoint{URL:server.URL,Model:"wire-model",Client:server.Client()};var rawMessages=`[{"role":"user","content":"fallback plain","images":["https://images.invalid/old.png"],"multi_content":[{"type":"text","text":"keep text"},{"type":"image_url","image_url":{"url":"https://images.invalid/image.png","detail":"low"}}]},{"role":"user","content":"image-only fallback","multi_content":[{"type":"image_url","image_url":{"url":"data:image/png;base64,AAAA"}}]}]`;var messages []api.Message;json.Unmarshal([]byte(rawMessages),&messages)
  opts:=&api.Options{PromptCacheKey:scenario.Key,CacheRetention:api.CacheRetention(scenario.Retention)};ctx:=context.Background();if scenario.Session!=""{ctx=types.WithSessionID(ctx,scenario.Session)}
  var result any;var err error;var events <-chan types.StreamResponse
  if protocol=="openai-completions"{client:=openaicompletions.New(openaicompletions.Config{Endpoint:endpoint,Settings:api.OpenAICompletionsSettings{SupportsMultiContent:true,PromptCacheKey:true}});if stream{events,err=client.ChatStream(ctx,messages,opts)}else{result,err=client.Chat(ctx,messages,opts)}}else{client:=openairesponses.New(openairesponses.Config{Endpoint:endpoint,Settings:api.OpenAIResponsesSettings{PromptCacheKey:true,SupportsLongCacheRetention:true}});if stream{events,err=client.ChatStream(ctx,messages,opts)}else{result,err=client.Chat(ctx,messages,opts)}}
  expectedAttempts:=1;if api.IsMultimodalNotSupportedError(&api.HTTPError{StatusCode:scenario.Status,Body:scenario.Body}){expectedAttempts=2};if len(requests)!=expectedAttempts{panic(fmt.Sprintf("oracle failed to reach provider: %s requests=%d expected=%d error=%v",protocol,len(requests),expectedAttempts,err))};if stream&&err==nil{out:=[]types.StreamResponse{};for e:=range events{out=append(out,e)};result=out};server.Close();f:=map[string]any{"protocol":protocol,"stream":stream,"status":scenario.Status,"body":scenario.Body,"session":scenario.Session,"messages":json.RawMessage(rawMessages),"options":map[string]any{"prompt_cache_key":scenario.Key,"cache_retention":scenario.Retention},"requests":requests};if err!=nil{f["error"]=err.Error()}else{f["result"]=result};fixtures=append(fixtures,f)
 }}}
 data,err:=json.MarshalIndent(fixtures,"","  ");if err!=nil{panic(err)};if err=os.WriteFile(os.Args[1],data,0644);err!=nil{panic(err)}
}
