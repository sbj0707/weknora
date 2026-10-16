//go:build ignore

// Production ProcessKnowledgeMove: whole-batch SQL preflight, independent commits, Redis progress and audit.
package service

import (
 "context"
 "encoding/json"
 "errors"
 "net"
 "os"
 "strings"
 "testing"
 "time"

 "github.com/alicebob/miniredis/v2"
 "github.com/redis/go-redis/v9"
 "github.com/Tencent/WeKnora/internal/types"
 "github.com/Tencent/WeKnora/internal/types/interfaces"
 "github.com/hibiken/asynq"
 "github.com/stretchr/testify/require"
)

type javaWorkerTenant struct { interfaces.TenantRepository; value *types.Tenant; fail bool }
func (r javaWorkerTenant) GetTenantByID(context.Context,uint64)(*types.Tenant,error){if r.fail{return nil,errors.New("controlled tenant failure")};return r.value,nil}
type javaWorkerGraph struct { interfaces.RetrieveGraphRepository; events *[]any; fail string }
func (g javaWorkerGraph) DelGraph(_ context.Context,ns []types.NameSpace)error{for _,n:=range ns{*g.events=append(*g.events,map[string]any{"kind":"delete_graph","source":n.KnowledgeBase,"id":n.Knowledge});if g.fail=="all"||g.fail==n.Knowledge{return errors.New("controlled graph failure")}};return nil}
type javaWorkerAudit struct { interfaces.AuditLogService; events *[]any }
func(a javaWorkerAudit)Log(_ context.Context,e *types.AuditLog)error{var details any;_ = json.Unmarshal(e.Details,&details);*a.events=append(*a.events,map[string]any{"kind":"audit","tenant":e.TenantID,"actor":e.ActorUserID,"role":e.ActorRole,"action":e.Action,"target_type":e.TargetType,"target_id":e.TargetID,"scope_id":e.ScopeID,"outcome":e.Outcome,"details":details});return nil}
type javaWorkerRedisHook struct { events *[]any }
func (h javaWorkerRedisHook)DialHook(next redis.DialHook)redis.DialHook{return func(ctx context.Context,network,addr string)(net.Conn,error){return next(ctx,network,addr)}}
func (h javaWorkerRedisHook)ProcessPipelineHook(next redis.ProcessPipelineHook)redis.ProcessPipelineHook{return next}
func (h javaWorkerRedisHook)ProcessHook(next redis.ProcessHook)redis.ProcessHook{return func(ctx context.Context,cmd redis.Cmder)error{if cmd.Name()=="set"{args:=cmd.Args();var p map[string]any;raw:=args[2];switch v:=raw.(type){case []byte:_=json.Unmarshal(v,&p);case string:_=json.Unmarshal([]byte(v),&p)};if p!=nil{p["created_at"]=0;p["updated_at"]=0;*h.events=append(*h.events,map[string]any{"kind":"progress","value":p})}};return next(ctx,cmd)}}

func TestJavaKnowledgeMoveWorkerExport(t *testing.T){
 out:=[]any{}
 names:=[]string{"reuse","reparse","duplicates","missing-document","second-missing","second-processing","second-wrong-parent","second-wrong-tag","empty-ids","blank-id","padded-id","invalid-mode","pair-type","pair-model","same-kb","foreign-source","foreign-target","missing-source","missing-target","missing-tenant","wrong-tenant","tenant-failure","invalid-json","array-payload","null-payload","negative-owner","zero-owner","missing-task","missing-source-id","missing-target-id","wrong-string","wrong-slice","wrong-initiator","partial-first","partial-final","all-fail-first","all-fail-final","first-success-retry","second-success-retry","progress-failure","user-initiator","api-initiator","preflight-first","preflight-final","engine-preflight-failure","done-retry"}
 for _,name:=range names{t.Run(name,func(t *testing.T){
 f:=javaMoveFixture(t);if os.Getenv("WEKNORA_MOVE_PLAN_PG_DSN")==""{require.NoError(t,f.db.AutoMigrate(&types.Tenant{}))}
 for _,table:=range []string{"chunks","knowledges","knowledge_tags","knowledge_tag_relations","tenants"}{require.NoError(t,f.db.Exec("DELETE FROM "+table).Error)}
 source,target:=f.kbs.values["kb"],f.kbs.values["other"];source.Type="document";target.Type="document"
 mode:="reuse_vectors";if name=="reparse"{mode="reparse"}
 seed:=[]string{
 `INSERT INTO tenants(id,name,business,storage_used) VALUES(7,'owner','test',9)`,
 `INSERT INTO knowledges(id,tenant_id,knowledge_base_id,type,parse_status,metadata,file_path,file_name,embedding_model_id,storage_size,custom_metadata,title,source,description,enable_status,error_message,updated_at) VALUES('doc',7,'kb','manual','completed','{"content":"# Original body","format":"markdown","version":2}', 'original.txt','original.txt','',5,'{}','original','','source summary','enabled','','2026-01-01 00:00:00+00:00'),('second',7,'kb','manual','completed','{"content":"# Second body"}','second.txt','second.txt','',3,'{}','second','','second summary','enabled','','2026-01-01 00:00:00+00:00')`,
 `INSERT INTO chunks(id,tenant_id,knowledge_id,knowledge_base_id,chunk_type,parent_chunk_id,tag_id,content,chunk_index,start_at,end_at) VALUES('chunk',7,'doc','kb','text','','','original',0,0,8),('second-chunk',7,'second','kb','text','','','second',0,0,6)`,
 }
 payload:=map[string]any{"tenant_id":7,"task_id":"transfer-task","knowledge_ids":[]string{"doc","second"},"source_kb_id":"kb","target_kb_id":"other","mode":mode}
 retry,maxRetry:=0,3
 switch name{
 case "duplicates":payload["knowledge_ids"]=[]string{"doc","doc","second"}
 case "missing-document":seed=append(seed,`DELETE FROM knowledges WHERE id='doc'`)
 case "second-missing":seed=append(seed,`DELETE FROM knowledges WHERE id='second'`)
 case "second-processing","preflight-first","preflight-final":seed=append(seed,`UPDATE knowledges SET parse_status='processing' WHERE id='second'`)
 case "second-wrong-parent":seed=append(seed,`UPDATE chunks SET parent_chunk_id='chunk' WHERE knowledge_id='second'`)
 case "second-wrong-tag":seed=append(seed,`UPDATE chunks SET tag_id='missing' WHERE knowledge_id='second'`)
 case "empty-ids":payload["knowledge_ids"]=[]string{}
 case "blank-id":payload["knowledge_ids"]=[]string{"doc","\u3000"}
 case "padded-id":payload["knowledge_ids"]=[]string{" doc "}
 case "invalid-mode":payload["mode"]="other"
 case "pair-type":target.Type="faq"
 case "pair-model":target.EmbeddingModelID="different"
 case "same-kb":payload["target_kb_id"]="kb"
 case "foreign-source":source.TenantID=8
 case "foreign-target":target.TenantID=8
 case "missing-source":delete(f.kbs.values,"kb")
 case "missing-target":delete(f.kbs.values,"other")
 case "zero-owner":payload["tenant_id"]=0
 case "negative-owner":payload["tenant_id"]=-1
 case "missing-task":payload["task_id"]=""
 case "missing-source-id":payload["source_kb_id"]=""
 case "missing-target-id":payload["target_kb_id"]=""
 case "wrong-string":payload["mode"]=4
 case "wrong-slice":payload["knowledge_ids"]=map[string]any{}
 case "wrong-initiator":payload["initiator"]=false
 case "user-initiator":payload["initiator"]=types.TaskInitiator{UserID:"user",Role:types.TenantRoleContributor}
 case "api-initiator":payload["initiator"]=types.TaskInitiator{APIKeyID:19,APIKeyName:"robot"}
 case "engine-preflight-failure":seed=append(seed,`UPDATE knowledges SET embedding_model_id='embedding'`);source.EmbeddingModelID="embedding";target.EmbeddingModelID="embedding"
 }
 if strings.HasSuffix(name,"-final"){retry=3};if strings.HasSuffix(name,"-retry"){retry=1}
 if name=="done-retry"{seed=append(seed,`UPDATE knowledges SET knowledge_base_id='other',metadata='{"_knowledge_transfer":{"task_id":"transfer-task","operation":"move","source_kb":"kb","target_kb":"other","source_id":"doc","mode":"reuse_vectors","phase":"done"}}' WHERE id='doc'`,`UPDATE chunks SET knowledge_base_id='other' WHERE knowledge_id='doc'`)}
 for _,q:=range seed{require.NoError(t,f.db.Exec(q).Error)}
 events:=[]any{};mini:=miniredis.RunT(t);client:=redis.NewClient(&redis.Options{Addr:mini.Addr(),MaxRetries:-1,DialTimeout:time.Second,ReadTimeout:time.Second,WriteTimeout:time.Second});t.Cleanup(func(){_ = client.Close()});client.AddHook(javaWorkerRedisHook{&events})
 tenant:=&types.Tenant{ID:7,RetrieverEngines:types.RetrieverEngines{Engines:[]types.RetrieverEngineParams{{RetrieverType:types.VectorRetrieverType,RetrieverEngineType:types.PostgresRetrieverEngineType}}}}
 if name=="missing-tenant"{tenant=nil};if name=="wrong-tenant"{tenant=&types.Tenant{ID:8}}
 f.svc.tenantRepo=javaWorkerTenant{value:tenant,fail:name=="tenant-failure"};f.svc.redisClient=client
 fail:="";if strings.HasPrefix(name,"partial-"){fail="doc"};if strings.HasPrefix(name,"all-fail-"){fail="all"}
 f.svc.graphEngine=javaWorkerGraph{events:&events,fail:fail};f.svc.audit=javaWorkerAudit{events:&events}
 calls:=0;f.svc.retrieveEngine=javaMovePlanRegistry{engine:javaMovePlanEngine{calls:&calls,fail:name=="engine-preflight-failure"}};f.svc.modelService=parentChildModelService{embedder:parentChildEmbedder{}}
 f.svc.task=javaMoveExecutionQueue{events:&events,db:f.db}
 raw,err:=json.Marshal(payload);require.NoError(t,err);if name=="invalid-json"{raw=[]byte(`{"task_id":`)};if name=="array-payload"{raw=[]byte(`[]`)};if name=="null-payload"{raw=[]byte(`null`)}
 if name=="progress-failure"{mini.SetError("ERR controlled Redis failure")}
 ctx,cancel:=asynq.JavaMoveFixtureContext(context.Background(),retry,maxRetry);defer cancel()
 err=f.svc.ProcessKnowledgeMove(ctx,asynq.NewTask(types.TypeKnowledgeMove,raw))
 var progress any;value,readErr:=mini.Get("knowledge_move_progress:transfer-task");if readErr==nil{var p map[string]any;require.NoError(t,json.Unmarshal([]byte(value),&p));p["created_at"]=0;p["updated_at"]=0;progress=p}
 documents,chunks:=[]map[string]any{},[]map[string]any{}
 require.NoError(t,f.db.Raw(`SELECT id,tenant_id,knowledge_base_id,parse_status,CAST(metadata AS TEXT) AS metadata,storage_size,enable_status,description,COALESCE(error_message,'') AS error_message FROM knowledges ORDER BY id`).Scan(&documents).Error)
 require.NoError(t,f.db.Raw(`SELECT id,knowledge_id,knowledge_base_id,tag_id,deleted_at IS NOT NULL AS deleted FROM chunks ORDER BY id`).Scan(&chunks).Error)
 out=append(out,map[string]any{"name":name,"raw":string(raw),"source":source,"target":target,"tenant":tenant,"retry":retry,"max_retry":maxRetry,"seed":seed,"error":javaMoveError(err),"skip_retry":errors.Is(err,asynq.SkipRetry),"progress":progress,"events":events,"documents":documents,"chunks":chunks,"engine_checks":calls,"ttl_seconds":int64(mini.TTL("knowledge_move_progress:transfer-task")/time.Second)})
 })}
 if t.Failed(){return};raw,err:=json.MarshalIndent(out,"","  ");require.NoError(t,err);require.NoError(t,os.WriteFile(os.Getenv("WEKNORA_MOVE_WORKER_ORACLE"),raw,0600))
}

func TestJavaKnowledgeMoveProgressExport(t *testing.T){
 mini:=miniredis.RunT(t);client:=redis.NewClient(&redis.Options{Addr:mini.Addr(),MaxRetries:-1});defer client.Close();svc:=&knowledgeService{redisClient:client};ctx:=context.Background();rows:=[]any{}
 values:=[]string{`null`,`{}`,`[]`,`true`,`1`,`"x"`,`{"task_id":4}`,`{"total":1.5}`,`{"total":9223372036854775808}`,`{"created_at":-1}`,`{"total":3,"TOTAL":null}`,`{"total":1,"total":4}`,`{"total":"bad","total":4}`,`{"Status":"completed","message":"<>&\u2028\u2029😀"}`,`{"unknown":{"x":1},"progress":100}`,`{"error":false,"task_id":2}`,`{} true`,`{"task_id":`,""}
 for _,raw:=range values{require.NoError(t,mini.Set("knowledge_move_progress:task",raw));value,err:=svc.GetKnowledgeMoveProgress(ctx,"task");rows=append(rows,map[string]any{"raw":raw,"value":value,"error":javaMoveError(err)})}
 key:="knowledge_move_progress:task";mini.Del(key);initial:=&types.KnowledgeMoveProgress{TaskID:"task",SourceKBID:"kb",TargetKBID:"other",Status:types.KBCloneStatusPending,Total:2,Message:"Task <>&\u2028\u2029😀",CreatedAt:41,UpdatedAt:42}
 require.NoError(t,svc.SaveKnowledgeMoveProgress(ctx,initial));first,err:=mini.Get(key);require.NoError(t,err)
 completed:=*initial;completed.Status=types.KBCloneStatusCompleted;completed.Progress=100;completed.Processed=2;completed.UpdatedAt=44;require.NoError(t,svc.saveKnowledgeMoveProgress(ctx,&completed));second,err:=mini.Get(key);require.NoError(t,err)
 mini.FastForward(3*time.Hour);require.NoError(t,svc.SaveKnowledgeMoveProgress(ctx,initial));third,err:=mini.Get(key);require.NoError(t,err)
 output:=map[string]any{"decode":rows,"initial":initial,"completed":completed,"initial_bytes":first,"completed_bytes":second,"after_late_initial_bytes":third,"after_late_initial_ttl_seconds":int64(mini.TTL(key)/time.Second)}
 mini.Del(key);_,err=svc.GetKnowledgeMoveProgress(ctx,"task");output["missing_error"]=javaMoveError(err)
 raw,err:=json.MarshalIndent(output,"","  ");require.NoError(t,err);require.NoError(t,os.WriteFile(os.Getenv("WEKNORA_MOVE_PROGRESS_ORACLE"),raw,0600))
}

func TestJavaKnowledgeMoveNamedQueueExport(t *testing.T){mini:=miniredis.RunT(t);rows,err:=asynq.JavaMoveFixtureNamedQueue(mini.Addr());require.NoError(t,err);raw,err:=json.MarshalIndent(rows,"","  ");require.NoError(t,err);require.NoError(t,os.WriteFile(os.Getenv("WEKNORA_MOVE_NAMED_ORACLE"),raw,0600))}
