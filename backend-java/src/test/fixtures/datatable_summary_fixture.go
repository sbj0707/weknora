//go:build ignore
package main
import (
 "context"
 "database/sql"
 "encoding/base64"
 "encoding/binary"
 "encoding/json"
 "fmt"
 "math"
 "net/http"
 "net/http/httptest"
 "os"
 "path/filepath"
 "sort"
 "strings"
 "sync"
 repo "github.com/Tencent/WeKnora/internal/application/repository"
 sr "github.com/Tencent/WeKnora/internal/application/repository/retriever/sqlite"
 svc "github.com/Tencent/WeKnora/internal/application/service"
 filesvc "github.com/Tencent/WeKnora/internal/application/service/file"
 retrieve "github.com/Tencent/WeKnora/internal/application/service/retriever"
 "github.com/Tencent/WeKnora/internal/logger"
 "github.com/Tencent/WeKnora/internal/models/embedding"
 "github.com/panjf2000/ants/v2"
 "github.com/Tencent/WeKnora/internal/types"
 "github.com/Tencent/WeKnora/internal/utils"
 sqlitevec "github.com/asg017/sqlite-vec-go-bindings/cgo"
 _ "github.com/duckdb/duckdb-go/v2"
 "github.com/hibiken/asynq"
 "gorm.io/driver/sqlite"
 "gorm.io/gorm"
 gl "gorm.io/gorm/logger"
)
func must(e error){if e!=nil{panic(e)}}
func main(){
 logger.SetOutput(os.Stderr);sqlitevec.Auto()
 must(os.Setenv("RETRIEVE_DRIVER","sqlite"));must(os.Setenv("SSRF_WHITELIST","127.0.0.1"));must(os.Setenv("SYSTEM_AES_KEY",""));utils.ResetSSRFWhitelistForTest()
 loadPath:="backend-java/src/test/resources/go-datatable-loader.json";if len(os.Args)>1{loadPath=os.Args[1]};var load []map[string]any;data,err:=os.ReadFile(loadPath);must(err);must(json.Unmarshal(data,&load))
 names:=[]string{"success","custom","override","table-empty","column-empty","chat-table-error","chat-column-error","embedding-error","create-error","index-error","update-error","cleanup-delete-error","cleanup-index-error","knowledge-changed-cleanup","deleted","foreign","kb-foreign","kb-missing","tenant-missing","summary-model-missing","embedding-model-missing","unsupported","file-missing"}
 for _,state:=range []string{"pending","processing","failed","cancelled","deleting"}{names=append(names,"state-"+state)}
 for i:=1;i<len(load);i++{names=append(names,fmt.Sprintf("load-%d",i))}
 out:=[]any{}
 for _,name:=range names{
  n:=0;if strings.HasPrefix(name,"load-"){fmt.Sscanf(name,"load-%d",&n)};input:=load[n]
  dir,er:=os.MkdirTemp("","weknora-summary-go-");must(er);file:=filepath.Join(dir,"source."+input["file_type"].(string));bytes,er:=base64.StdEncoding.DecodeString(input["input_base64"].(string));must(er);must(os.WriteFile(file,bytes,0600))
  db,er:=gorm.Open(sqlite.Open(filepath.Join(dir,"db.sqlite")),&gorm.Config{Logger:gl.Default.LogMode(gl.Silent)});must(er);sqldb,er:=db.DB();must(er);sqldb.SetMaxOpenConns(1)
  for _,m:=range []string{"000000_init","000002_knowledge_folder_path","000003_knowledge_base_auto_tag_config","000004_memory","000007_system_admin_and_settings","000008_processing_spans_and_pending_subtasks","000010_knowledge_multi_tags","000011_principal_model","000021_knowledge_profiles","000031_model_catalog_config","000033_chunk_source_locators"}{data,er=os.ReadFile("migrations/sqlite/"+m+".up.sql");must(er);must(db.Exec(string(data)).Error)}
  tenants:=repo.NewTenantRepository(db);tenant:=&types.Tenant{ID:1,Name:"owner",Business:"test",Status:"active"};must(tenants.CreateTenant(context.Background(),tenant))
  kr:=repo.NewKnowledgeRepository(db);cr:=repo.NewChunkRepository(db);br:=repo.NewKnowledgeBaseRepository(db)
  kb:=&types.KnowledgeBase{ID:"kb",TenantID:1,Name:"KB",Type:"document",EmbeddingModelID:"embedding",SummaryModelID:"summary"};if name=="custom"||name=="override"{kb.ChunkingConfig.TableMetadataInstructions="  KB business <>& 中文  "};must(br.CreateKnowledgeBase(context.Background(),kb))
  state:="completed";if strings.HasPrefix(name,"state-"){state=strings.TrimPrefix(name,"state-")}
  k:=&types.Knowledge{ID:"knowledge",TenantID:1,KnowledgeBaseID:"kb",Type:"file",FileType:input["file_type"].(string),FileName:"source."+input["file_type"].(string),FilePath:file,FileHash:"hash",ParseStatus:state,EnableStatus:"enabled",SummaryStatus:"none",ErrorMessage:"old error",StorageSize:11}
  if name=="override"{must(k.SetProcessOverrides(&types.KnowledgeProcessOverrides{ChunkingConfig:&types.ChunkingConfig{TableMetadataInstructions:" Override instruction \n test "}}))};must(kr.CreateKnowledge(context.Background(),k));must(cr.CreateChunks(context.Background(),[]*types.Chunk{{ID:"old",TenantID:1,KnowledgeBaseID:"kb",KnowledgeID:k.ID,Content:"old body",ChunkType:types.ChunkTypeText,Status:2,IsEnabled:true}}))
  requests:=[]map[string]any{};var mu sync.Mutex;chatN:=0
  server:=httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){
   var body map[string]any;must(json.NewDecoder(r.Body).Decode(&body));mu.Lock();requests=append(requests,map[string]any{"path":r.URL.Path,"body":body,"authorization":r.Header.Get("Authorization")});mu.Unlock();w.Header().Set("Content-Type","application/json")
   if r.URL.Path=="/v1/chat/completions"{
    chatN++;if (name=="chat-table-error"&&chatN==1)||(name=="chat-column-error"&&chatN==2){w.WriteHeader(503);fmt.Fprint(w,`{"error":{"message":"synthetic provider unavailable"}}`);return}
    content:="  Table metadata 中文 <>&\n ";if chatN==2{content="\n Column metadata 中文 \t"};if(name=="table-empty"&&chatN==1)||(name=="column-empty"&&chatN==2){content=" \n\t\u00a0"}
    if name=="knowledge-changed-cleanup"&&chatN==2{must(db.Exec("UPDATE knowledges SET updated_at='2030-01-01 00:00:00' WHERE id='knowledge'").Error)}
    must(json.NewEncoder(w).Encode(map[string]any{"choices":[]any{map[string]any{"message":map[string]any{"role":"assistant","content":content},"finish_reason":"stop"}}}));return
   }
   if name=="embedding-error"{w.WriteHeader(400);fmt.Fprint(w,`{"error":{"message":"synthetic embedding unavailable"}}`);return}
   values:=body["input"].([]any);rows:=[]any{};for i,v:=range values{rows=append(rows,map[string]any{"index":i,"embedding":[]float32{float32(len([]rune(v.(string)))),float32(i),.12345678}})};must(json.NewEncoder(w).Encode(map[string]any{"data":rows}))
  }))
  mr:=repo.NewModelRepository(db);for _,m:=range []*types.Model{{ID:"summary",TenantID:1,Name:"unknown",Type:types.ModelTypeKnowledgeQA,Source:types.ModelSourceRemote,Status:types.ModelStatusActive,Parameters:types.ModelParameters{BaseURL:server.URL+"/v1",APIKey:"synthetic-key",Provider:"generic"}},{ID:"embedding",TenantID:1,Name:"unknown",Type:types.ModelTypeEmbedding,Source:types.ModelSourceRemote,Status:types.ModelStatusActive,Parameters:types.ModelParameters{BaseURL:server.URL+"/v1",APIKey:"synthetic-key",Provider:"generic",EmbeddingParameters:types.EmbeddingParameters{Dimension:3}}}}{must(mr.Create(context.Background(),m))}
  pool,er:=ants.NewPool(2);must(er);models:=svc.NewModelService(mr,br,nil,nil,embedding.NewBatchEmbedder(pool),nil);local:=filesvc.NewLocalFileService(dir,"");registry:=retrieve.NewRetrieveEngineRegistry(nil,nil);index:=sr.NewSQLiteRetrieveEngineRepository(db);must(registry.Register(retrieve.NewKVHybridRetrieveEngine(index,types.SQLiteRetrieverEngineType)))
  ts:=svc.NewTenantService(tenants,nil);chunks:=svc.NewChunkService(cr,kr,br,models,registry,nil,nil,nil);bases:=svc.NewKnowledgeBaseService(br,kr,cr,nil,nil,models,registry,nil,tenants,local,nil,nil,nil,nil,nil,nil,nil,nil,nil,nil,nil)
  knowledge,er:=svc.NewKnowledgeService(nil,kr,nil,bases,tenants,ts,chunks,cr,nil,nil,local,nil,nil,models,nil,nil,nil,registry,nil,nil,nil,nil,nil,nil,nil,nil,nil);must(er)
  duck,er:=sql.Open("duckdb",":memory:");must(er);for _,ext:=range []string{"spatial","excel"}{_,er=duck.Exec("LOAD "+ext);must(er)}
  payload:=svc.DataTableSummaryPayload{TenantID:1,KnowledgeID:"knowledge",SummaryModel:"summary",EmbeddingModel:"embedding"}
  switch name{
   case "deleted":must(db.Delete(&types.Knowledge{},"id='knowledge'").Error)
   case "foreign":payload.TenantID=2
   case "kb-foreign":must(db.Exec("UPDATE knowledge_bases SET tenant_id=2 WHERE id='kb'").Error)
   case "kb-missing":must(db.Delete(&types.KnowledgeBase{},"id='kb'").Error)
   case "tenant-missing":must(db.Delete(&types.Tenant{},"id=1").Error)
   case "summary-model-missing":payload.SummaryModel="missing"
   case "embedding-model-missing":payload.EmbeddingModel="missing"
   case "unsupported":must(db.Exec("UPDATE knowledges SET file_type=' TXT ' WHERE id='knowledge'").Error)
   case "file-missing":must(os.Remove(file))
  }
  if name=="create-error"{must(db.Exec("CREATE TRIGGER fail_chunks BEFORE INSERT ON chunks BEGIN SELECT RAISE(FAIL,'synthetic create failure');END").Error)}
  if name=="index-error"||name=="cleanup-delete-error"||name=="knowledge-changed-cleanup"{must(db.Exec("CREATE TRIGGER fail_index BEFORE INSERT ON lite_embeddings BEGIN SELECT RAISE(FAIL,'synthetic index failure');END").Error)}
  if name=="update-error"||name=="cleanup-index-error"{must(db.Exec("CREATE TRIGGER fail_status BEFORE UPDATE OF status ON chunks BEGIN SELECT RAISE(FAIL,'synthetic status failure');END").Error)}
  if name=="cleanup-delete-error"{must(db.Exec("CREATE TRIGGER fail_delete BEFORE UPDATE OF deleted_at ON chunks BEGIN SELECT RAISE(FAIL,'synthetic delete failure');END").Error)}
  if name=="cleanup-index-error"{must(db.Exec("CREATE TRIGGER fail_index_delete BEFORE DELETE ON lite_embeddings BEGIN SELECT RAISE(FAIL,'synthetic index delete failure');END").Error)}
  handler:=svc.NewDataTableSummaryService(models,bases,knowledge,local,chunks,ts,registry,nil,duck,nil);raw,er:=json.Marshal(payload);must(er);err=handler.Handle(context.Background(),asynq.NewTask(types.TypeDataTableSummary,raw));message:="";if err!=nil{message=err.Error()}
  stored:=types.Knowledge{};must(db.Unscoped().Where("id='knowledge'").First(&stored).Error);cc:=[]types.Chunk{};must(db.Where("knowledge_id='knowledge'").Find(&cc).Error);sort.Slice(cc,func(i,j int)bool{return cc[i].ChunkType<cc[j].ChunkType});allChunks:=[]types.Chunk{};must(db.Unscoped().Where("knowledge_id=?","knowledge").Find(&allChunks).Error);ids:=map[string]string{"":"","old":"old"};for _,c:=range allChunks{if c.ID!="old"{ids[c.ID]=string(c.ChunkType)}};normalized:=[]any{};for _,c:=range cc{normalized=append(normalized,map[string]any{"id":ids[c.ID],"tenant":c.TenantID,"kb":c.KnowledgeBaseID,"knowledge":c.KnowledgeID,"content":c.Content,"type":c.ChunkType,"index":c.ChunkIndex,"parent":ids[c.ParentChunkID],"previous":ids[c.PreChunkID],"next":ids[c.NextChunkID],"start":c.StartAt,"end":c.EndAt,"status":c.Status,"enabled":c.IsEnabled,"context_header":c.ContextHeader})}
  emb:=[]map[string]any{};must(db.Raw("SELECT source_id,source_type,chunk_id,knowledge_id,knowledge_base_id,content,dimension,is_enabled FROM lite_embeddings ORDER BY source_id").Scan(&emb).Error);for _,e:=range emb{sourceID:=e["source_id"].(string);e["source_id"]=ids[e["source_id"].(string)];e["chunk_id"]=ids[e["chunk_id"].(string)];var bytes []byte;er=sqldb.QueryRow(fmt.Sprintf("SELECT v.embedding FROM vec_embeddings_%v v JOIN lite_embeddings l ON l.id=v.rowid WHERE l.source_id=?",e["dimension"]),sourceID).Scan(&bytes);if er==nil{v:=[]float32{};for i:=0;i<len(bytes);i+=4{v=append(v,math.Float32frombits(binary.LittleEndian.Uint32(bytes[i:i+4])))};e["vector"]=v}else{e["vector_error"]=er.Error()}}
  sort.Slice(emb,func(i,j int)bool{return emb[i]["source_id"].(string)<emb[j]["source_id"].(string)})
  var deletedChunks int64;must(db.Unscoped().Model(&types.Chunk{}).Where("knowledge_id=? AND deleted_at IS NOT NULL","knowledge").Count(&deletedChunks).Error);var tableCount int;must(duck.QueryRow("SELECT count(*) FROM duckdb_tables() WHERE table_name='k_knowledge'").Scan(&tableCount));out=append(out,map[string]any{"name":name,"load_case":n,"payload":payload,"requests":requests,"error":message,"chunks":normalized,"indexes":emb,"knowledge":map[string]any{"parse_status":stored.ParseStatus,"error":stored.ErrorMessage,"storage_size":stored.StorageSize,"enable_status":stored.EnableStatus,"summary_status":stored.SummaryStatus},"tables_after_cleanup":tableCount,"soft_deleted_chunks":deletedChunks})
  server.Close();pool.Release();must(duck.Close());must(sqldb.Close());must(os.RemoveAll(dir))
 }
 must(json.NewEncoder(os.Stdout).Encode(out))
}
func findID(m map[string]string,normalized string)string{for id,n:=range m{if n==normalized{return id}};return ""}
