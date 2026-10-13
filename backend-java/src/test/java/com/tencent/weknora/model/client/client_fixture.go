//go:build ignore

// Real Go clients against loopback HTTP; never reaches a production model.
package main
import (
 "context"
 "crypto/x509"
 "encoding/base64"
 "encoding/json"
 "fmt"
 "net/http"
 "net/http/httptest"
 "os"
 "strings"
 "sync"
 "github.com/Tencent/WeKnora/internal/models/api"
 "github.com/Tencent/WeKnora/internal/models/api/openaicompletions"
 "github.com/Tencent/WeKnora/internal/models/chat"
 "github.com/Tencent/WeKnora/internal/models/embedding"
 "github.com/Tencent/WeKnora/internal/models/rerank"
 rt "github.com/Tencent/WeKnora/internal/models/runtime"
 "github.com/Tencent/WeKnora/internal/types"
 "github.com/Tencent/WeKnora/internal/utils"
)
func main(){
 os.Setenv("SSRF_WHITELIST","127.0.0.1");utils.ResetSSRFWhitelistForTest();var mu sync.Mutex;var recorded []any;reply:="";mode:=""
 server:=httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){var body map[string]any;if err:=json.NewDecoder(r.Body).Decode(&body);err!=nil{panic(err)};headers:=map[string]string{};for _,key:=range []string{"Authorization","api-key","X-Test","Accept","session_id","x-client-request-id","x-session-affinity"}{if value:=r.Header.Get(key);value!=""{headers[strings.ToLower(key)]=value}};mu.Lock();recorded=append(recorded,map[string]any{"path":r.URL.Path,"query":r.URL.RawQuery,"headers":headers,"body":body});mu.Unlock();w.Header().Set("Content-Type","application/json")
  if mode=="embedding"{input:=body["input"].([]any);var data []any;for i:=len(input)-1;i>=0;i--{data=append(data,map[string]any{"index":i,"embedding":[]float32{float32(len(input[i].(string))),0.12345678}})};json.NewEncoder(w).Encode(map[string]any{"data":data});return}
  if mode=="rerank"{docs:=body["documents"].([]any);var results []any;for i:=len(docs)-1;i>=0;i--{results=append(results,map[string]any{"index":i,"relevance_score":float64(len(docs[i].(string)))/10,"document":map[string]any{"text":docs[i]}})};json.NewEncoder(w).Encode(map[string]any{"results":results});return}
  if mode=="stream"{w.Header().Set("Content-Type","text/event-stream")};fmt.Fprint(w,reply)
 }));defer server.Close();var fixtures []any
 capture:=func(kind,provider,name,suffix,parameters,options,messages,payload string){
  mode=kind;recorded=nil;reply=payload;var params types.ModelParameters;if err:=json.Unmarshal([]byte(parameters),&params);err!=nil{panic(err)};params.BaseURL=server.URL+suffix;params.APIKey="synthetic-api-key";params.Provider=provider;params.CustomHeaders=map[string]string{"X-Test":"trace","Authorization":"must-not-override","Content-Type":"must-not-override"};if kind=="chat"||kind=="stream"{if params.ExtraConfig==nil{params.ExtraConfig=map[string]string{}};params.ExtraConfig["api"]="openai-completions"}
  typ:=types.ModelTypeEmbedding;if kind=="rerank"{typ=types.ModelTypeRerank};if kind=="chat"||kind=="stream"{typ=types.ModelTypeKnowledgeQA};m:=&types.Model{ID:"client-fixture",TenantID:1,Name:name,Source:types.ModelSourceRemote,Type:typ,Parameters:params}
  var opts *api.Options;if options!=""&&options!="null"{opts=&api.Options{};if err:=json.Unmarshal([]byte(options),opts);err!=nil{panic(err)};var extra map[string]json.RawMessage;json.Unmarshal([]byte(options),&extra);if v:=extra["prompt_cache_key"];len(v)>0{json.Unmarshal(v,&opts.PromptCacheKey)};if v:=extra["cache_retention"];len(v)>0{json.Unmarshal(v,&opts.CacheRetention)}}
  var msgs []api.Message;if messages!=""{if err:=json.Unmarshal([]byte(messages),&msgs);err!=nil{panic(err)}};var result any;var err error;ctx:=context.Background()
  switch kind{case "embedding":var client embedding.Embedder;client,err=embedding.NewEmbedder(embedding.ConfigFromModel(m,"",""),nil,nil);if err==nil{result,err=client.BatchEmbed(types.WithEmbedQuery(ctx),[]string{"a","three","中😀"})}
  case "rerank":var client rerank.Reranker;client,err=rerank.NewReranker(rerank.ConfigFromModel(m,"",""));if err==nil{result,err=client.Rerank(ctx,"query",[]string{"a","three","cc"})}
  default:var client chat.Chat;client,err=chat.NewRemoteChat(chat.ConfigFromModel(m,"",""));if err==nil{if kind=="chat"{result,err=client.Chat(ctx,msgs,opts)}else{var chunks <-chan types.StreamResponse;chunks,err=client.ChatStream(ctx,msgs,opts);if err==nil{out:=[]types.StreamResponse{};for chunk:=range chunks{out=append(out,chunk)};result=out}}}}
  if err!=nil{panic(fmt.Sprintf("%s %s %s: %v",kind,provider,name,err))};params.BaseURL="";cfgRaw,_:=json.Marshal(params);var config map[string]any;json.Unmarshal(cfgRaw,&config);config["base_suffix"]=suffix
  fixture:=map[string]any{"kind":kind,"provider":provider,"name":name,"type":typ,"parameters":config,"requests":recorded,"result":result,"payload":payload};if options!=""{fixture["options"]=json.RawMessage(options)};if messages!=""{fixture["messages"]=json.RawMessage(messages)};fixtures=append(fixtures,fixture)
 }
 for _,c:=range [][4]string{
  {"generic","unknown","/v1",`{}`},{"openai","text-embedding-3-small","/v1",`{"embedding_parameters":{"dimension":256,"supports_dimension_override":true}}`},
  {"jina","jina-embeddings-v3","/v1",`{"embedding_parameters":{"dimension":256,"supports_dimension_override":true}}`},
  {"nvidia","nvidia/nv-embedqa-e5-v5","/v1",`{}`},{"gpustack","embedding","/v1",`{"embedding_parameters":{"truncate_prompt_tokens":256}}`},
  {"zhipu","embedding-3","/api/paas/v4",`{"embedding_parameters":{"dimension":256,"supports_dimension_override":false}}`},
  {"aliyun","text-embedding-v4","/compatible-mode/v1",`{"embedding_parameters":{"dimension":256,"supports_dimension_override":true}}`},
  {"volcengine","doubao-embedding-text-240715","/api/v3/embeddings",`{}`},
  {"azure_openai","deployment name","",`{}`},{"azure_openai","deployment/name","",`{"extra_config":{"api_version":"2024-10-21"}}`},
 }{capture("embedding",c[0],c[1],c[2],c[3],"","","")}
 for _,c:=range [][4]string{{"generic","unknown","/v1",`{}`},{"jina","jina-reranker-v3","/v1",`{}`},{"zhipu","rerank","/api/paas/v4/rerank",`{}`},{"qianfan","bce-reranker-base","/v2",`{}`},{"gpustack","bge-reranker-v2-m3","/v1",`{"extra_config":{"truncate_prompt_tokens":"256","score_scale":"logit"}}`}}{capture("rerank",c[0],c[1],c[2],c[3],"","","")}
 msgs:=`[{"role":"system","content":"instruction <|im_start|>"},{"role":"user","content":"question [INST]"}]`
 opts:=`{"temperature":0.7,"top_p":0.8,"seed":42,"max_tokens":10,"max_completion_tokens":20,"thinking":true,"reasoning_effort":"high","thinking_budget_tokens":1200,"tools":[{"type":"function","function":{"name":"search","description":"Search","parameters":{"type":"object"}}}],"tool_choice":"required","parallel_tool_calls":true,"format":{},"prompt_cache_key":"cache-session","cache_retention":"long"}`
 chatReply:=`{"choices":[{"message":{"content":"<think>inline thought</think> final answer","reasoning_content":"dedicated thought","tool_calls":[{"id":"call1","function":{"name":"search","arguments":"{}"},"extra_content":{"signature":"sig"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":10,"completion_tokens":3,"prompt_tokens_details":{"cached_tokens":5}}}`
 for _,c:=range [][3]string{{"generic","model",`{}`},{"openai","gpt-5",`{}`},{"deepseek","deepseek-reasoner",`{}`},{"moonshot","kimi-k2.5",`{}`},{"aliyun","qwen3-235b-a22b",`{}`},{"openrouter","anthropic/claude-sonnet-4",`{}`},{"gemini","gemini-2.5-pro",`{}`},{"generic","model",`{"spec":{"compat":{"max_tokens_field":"","fixed_temperature":1,"supports_temperature":true,"extra_body":{"max_tokens":5,"max_completion_tokens":7,"enable_search":true}}}}`}}{capture("chat",c[0],c[1],"/v1",c[2],opts,msgs,chatReply)}
 for _,payload:=range []string{
  "event: message\r\ndata:{\"choices\":[{\"delta\":{\"reasoning_content\":\"thought\"}}]}\r\n\r\ndata: {\"choices\":[{\"delta\":{\"content\":\"answer\"},\"finish_reason\":\"stop\"}]}\n\ndata: {\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2,\"cached_tokens\":4}}\n\ndata:[DONE]  \n",
  "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n",
  "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":7,\"id\":\"c7\",\"function\":{\"name\":\"search\",\"arguments\":\"{\"}}]}}]}\ndata: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":7,\"function\":{\"name\":\"search\",\"arguments\":\"} \"}}]},\"finish_reason\":\"tool_calls\"}]}\ndata: [DONE]\n",
  "data: {\"choices\":[{\"delta\":{\"reasoning_details\":[{\"type\":\"reasoning.text\",\"text\":\"opaque\"}],\"content\":\"answer\"},\"finish_reason\":\"stop\"}]}\ndata: [DONE]\n",
 }{capture("stream","generic","model","/v1",`{}`,`{"max_tokens":10}`,msgs,payload)}
 // Golden body builders across every catalog ChatCompletions provider and thinking level.
 var bodies []any
 for _,v:=range rt.List(){for _,level:=range []string{"","off","auto","minimal","high","max","hgih"}{resolved,err:=rt.Resolve(rt.Ref{Provider:v.ID,Model:"unknown",BaseURL:"https://relay.invalid/v1",ModelType:types.ModelTypeKnowledgeQA,Extra:map[string]string{"api":"openai-completions"}});if err!=nil{continue};var messages []api.Message;json.Unmarshal([]byte(msgs),&messages);o:=&api.Options{};json.Unmarshal([]byte(opts),o);o.ReasoningEffort=api.ReasoningEffort(level);o.PromptCacheKey="cache-session";o.CacheRetention=api.CacheRetentionLong;c:=openaicompletions.New(openaicompletions.Config{Endpoint:api.Endpoint{Model:resolved.RemoteModel},Settings:resolved.OpenAICompletions,ThinkingLevels:resolved.ThinkingLevels,Reasoning:resolved.Spec.Reasoning});body,err:=c.BuildRequestBody(messages,o,true);if err!=nil{panic(err)};var oo map[string]any;json.Unmarshal([]byte(opts),&oo);oo["reasoning_effort"]=level;bodies=append(bodies,map[string]any{"provider":v.ID,"level":level,"messages":json.RawMessage(msgs),"options":oo,"body":body})}}
 var placement []any
 for _,raw:=range []string{`[]`,`[{"embedding":[1]},{"embedding":[2]}]`,`[{"index":1,"embedding":[2]},{"index":0,"embedding":[1]}]`,`[{"index":2,"embedding":[1]}]`,`[{"index":-1,"embedding":[1]}]`,`[{"index":0,"embedding":[1]},{"index":0,"embedding":[2]}]`,`[{"index":0,"embedding":[]},{"index":1,"embedding":[2]}]`,`[{"index":0,"embedding":[null]},{"index":1,"embedding":[2]}]`,`[{"index":null,"embedding":[1]},{"embedding":[2]}]`}{var rows []struct{Index *int `json:"index"`;Embedding []float32 `json:"embedding"`};json.Unmarshal([]byte(raw),&rows);result,err:=api.PlaceEmbeddings(2,len(rows),func(i int)(int,[]float32){index:=i;if rows[i].Index!=nil{index=*rows[i].Index};return index,rows[i].Embedding});fixture:=map[string]any{"data":json.RawMessage(raw),"want":2};if err!=nil{fixture["error"]=err.Error()}else{fixture["result"]=result};placement=append(placement,fixture)}
 var batches []any
 for _,fixed:=range []int{0,2,5}{for _,limits:=range []api.BatchLimits{{},{MaxItems:2},{MaxItemRunes:2},{MaxTotalRunes:5},{MaxItems:2,MaxTotalRunes:5}}{items:=[]string{"中😀","a","bbb",""};result,err:=api.SplitBatches(items,fixed,limits);fixture:=map[string]any{"items":items,"fixed":fixed,"max_items":limits.MaxItems,"max_item_runes":limits.MaxItemRunes,"max_total_runes":limits.MaxTotalRunes};if err!=nil{fixture["error"]=err.Error()}else{fixture["result"]=result};batches=append(batches,fixture)}}
 tlsServer:=httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){fmt.Fprint(w,`{}`)}));certificate:=tlsServer.TLS.Certificates[0];privateDER,err:=x509.MarshalPKCS8PrivateKey(certificate.PrivateKey);if err!=nil{panic(err)};tlsMaterial:=map[string]any{"certificate_der":base64.StdEncoding.EncodeToString(certificate.Certificate[0]),"private_key_pkcs8":base64.StdEncoding.EncodeToString(privateDER)};tlsServer.Close()
 data,err:=json.MarshalIndent(map[string]any{"wire":fixtures,"chat_bodies":bodies,"placement":placement,"batches":batches,"tls_material":tlsMaterial},"","  ");if err!=nil{panic(err)};if err=os.WriteFile(os.Args[1],data,0644);err!=nil{panic(err)}
}
