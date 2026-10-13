//go:build ignore

package main
import (
 "context"
 "encoding/hex"
 "encoding/json"
 "fmt"
 "os"
 "path/filepath"
 "unicode"
 "strings"
 hybrid "github.com/Tencent/WeKnora/internal/application/service/retriever"
 "github.com/Tencent/WeKnora/internal/models/embedding"
 "github.com/Tencent/WeKnora/internal/types"
 rsqlite "github.com/Tencent/WeKnora/internal/application/repository/retriever/sqlite"
 vec "github.com/asg017/sqlite-vec-go-bindings/cgo"
 "gorm.io/gorm"
 gsqlite "gorm.io/driver/sqlite"
)
type Input struct {SourceID string `json:"source_id"`; SourceType int `json:"source_type"`; ChunkID string `json:"chunk_id"`; KnowledgeID string `json:"knowledge_id"`; KnowledgeBaseID string `json:"knowledge_base_id"`; TagID string `json:"tag_id"`; Content string `json:"content"`; Enabled bool `json:"enabled"`}
func(i Input) info()*types.IndexInfo{return &types.IndexInfo{SourceID:i.SourceID,SourceType:types.SourceType(i.SourceType),ChunkID:i.ChunkID,KnowledgeID:i.KnowledgeID,KnowledgeBaseID:i.KnowledgeBaseID,TagID:i.TagID,Content:i.Content,IsEnabled:i.Enabled}}
func main(){
 ctx:=context.Background();vec.Auto();dir,err:=os.MkdirTemp("","weknora-go-retrieval-");must(err);defer os.RemoveAll(dir)
 db,err:=gorm.Open(gsqlite.Open(filepath.Join(dir,"schema.db")),&gorm.Config{});must(err);sd,err:=db.DB();must(err);defer sd.Close();sd.SetMaxOpenConns(1)
 init,err:=os.ReadFile("migrations/sqlite/000000_init.up.sql");must(err);must(db.Exec(string(init)).Error)
 repo:=rsqlite.NewSQLiteRetrieveEngineRepository(db)
 var schema []struct{Name string `json:"name"`; SQL string `json:"sql"`};must(db.Raw("SELECT name,sql FROM sqlite_master WHERE (name='lite_embeddings' OR name='lite_embeddings_fts' OR tbl_name='lite_embeddings' AND type='index') AND sql IS NOT NULL ORDER BY type DESC,name").Scan(&schema).Error)
 queries:=[]types.RetrieveParams{}
 for _,q:=range []string{"hello","世界","中文","人民银行","中国","A股","股","2023","中文? OR 数据*","\"hello\" -missing","mixed Words","cafe","ＣＡＦＥ","a_b","東京","한글","あいう","𠀀𠀁","\u00a0hello\u0085","emoji 😀","missing","中文 hello"}{queries=append(queries,types.RetrieveParams{Query:q,TopK:10,RetrieverType:types.KeywordsRetrieverType})}
 for _,emb:=range [][]float32{{1,0},{0,1},{-1,0},{0.8,0.6},{1,0,0}}{for _,filter:=range []int{0,1,2,3,4,5,6,7}{p:=types.RetrieveParams{Embedding:emb,TopK:3,RetrieverType:types.VectorRetrieverType};switch filter{case 1:p.KnowledgeBaseIDs=[]string{"kb"};case 2:p.KnowledgeIDs=[]string{"doc"};case 3:p.TagIDs=[]string{"tag"};case 4:p.KnowledgeBaseIDs=[]string{"kb"};p.KnowledgeIDs=[]string{"doc"};p.TagIDs=[]string{"tag"};case 5:p.Threshold=.9;case 6:p.ExcludeChunkIDs=[]string{"a"};case 7:p.TopK=1};queries=append(queries,p)}}
 queries=append(queries,types.RetrieveParams{Query:"",Embedding:[]float32{},TopK:10,RetrieverType:types.VectorRetrieverType},types.RetrieveParams{Query:"中文",Embedding:[]float32{1,0},TopK:2})
 steps:=[]map[string]any{}
 capture:=func(op string, inputs []Input, embeddings map[string][]float32, ids []string){
  infos:=[]*types.IndexInfo{};for _,in:=range inputs{infos=append(infos,in.info())}
  switch op{case "save":must(repo.BatchSave(ctx,infos,map[string]any{"embedding":embeddings}));case "delete_chunk":must(repo.DeleteByChunkIDList(ctx,ids,0,""));case "delete_source":must(repo.DeleteBySourceIDList(ctx,ids,0,""));case "delete_knowledge":must(repo.DeleteByKnowledgeIDList(ctx,ids,0,""));case "disable":must(repo.BatchUpdateChunkEnabledStatus(ctx,map[string]bool{ids[0]:false}));case "tag":must(repo.BatchUpdateChunkTagID(ctx,map[string]string{ids[0]:ids[1]}))}
  var rows []map[string]any;must(db.Raw("SELECT id,source_id,source_type,chunk_id,knowledge_id,knowledge_base_id,tag_id,content,dimension,is_enabled FROM lite_embeddings ORDER BY id").Scan(&rows).Error)
  if rows==nil{rows=[]map[string]any{}}
  replies:=[]map[string]any{};for _,p:=range queries{r,e:=repo.Retrieve(ctx,p);item:=map[string]any{"params":p,"result":r};if e!=nil{item["error"]=e.Error()};replies=append(replies,item)}
  blobs:=map[string]string{};var dims []int;must(db.Model(&struct{Dimension int}{}).Table("lite_embeddings").Distinct("dimension").Where("dimension>0").Pluck("dimension",&dims).Error);for _,d:=range dims{var values []struct{Rowid int;Embedding []byte};must(db.Raw(fmt.Sprintf("SELECT rowid,embedding FROM vec_embeddings_%d ORDER BY rowid",d)).Scan(&values).Error);for _,v:=range values{blobs[fmt.Sprint(v.Rowid)]=hex.EncodeToString(v.Embedding)}}
  steps=append(steps,map[string]any{"op":op,"inputs":inputs,"embeddings":embeddings,"ids":ids,"rows":rows,"vectors":blobs,"queries":replies})
 }
 mk:=func(id,kb,doc,tag,text string,on bool)Input{return Input{id,0,id,doc,kb,tag,text,on}}
 capture("save",[]Input{mk("a","kb","doc","tag","hello 世界 中文 人民银行 A股 2023",true),mk("b","kb","doc","tag","hello 中文 数据 mixed Words",true),mk("blocked","other","other","other","hello 中文",true),mk("disabled","kb","doc","tag","hello 中文",false),mk("c","kb","another","","café ＣＡＦＥ 東京 한글 あいう 𠀀𠀁 emoji 😀",true),mk("no-vector","kb","doc","","a_b zero dimension",true)},map[string][]float32{"a":{1,0},"b":{.8,.6},"blocked":{1,0},"disabled":{1,0},"c":{-1,0}},nil)
 capture("save",[]Input{mk("a","kb","doc","tag","updated 新内容 NULL\x00clean",true),mk("new","kb","doc","","newanswer",true)},map[string][]float32{"a":{0,1},"new":{1,0,0}},nil)
 dup:=mk("dup","kb","doc","tag","first",true);last:=dup;last.Content="last duplicate";capture("save",[]Input{dup,last},map[string][]float32{"dup":{.1,.2}},nil)
 typed:=mk("a","kb","doc","","passage hello",true);typed.SourceType=1;capture("save",[]Input{typed},map[string][]float32{"a":{1,0}},nil)
 capture("save",[]Input{mk("new","kb","doc","","changed dimension",true)},map[string][]float32{"new":{.5,.5}},nil)
 capture("disable",nil,nil,[]string{"a"});capture("tag",nil,nil,[]string{"b","retag"})
 capture("delete_chunk",nil,nil,[]string{"a"});capture("delete_source",nil,nil,[]string{"dup"});capture("delete_knowledge",nil,nil,[]string{"doc"});capture("delete_knowledge",nil,nil,[]string{"another","other"})
 inputs:=[]map[string]string{};service:=hybrid.NewKVHybridRetrieveEngine(repo,types.SQLiteRetrieverEngineType);capturer:=&captureEmbedder{};for i,text:=range []string{"normal 中文 😀", "before <img src=\"data:image/png;base64,"+strings.Repeat("A",300)+"\"> after", "before ![chart](data:image/png;base64,"+strings.Repeat("A",300)+") after", "data:application/pdf;base64,"+strings.Repeat("a",205),strings.Repeat("😀中",10005),"<img中文 src=\"data:image/png;base64,AAA=\">", "DATA:IMAGE/PNG;BASE64,"+strings.Repeat("A",300),"data:image/png;base64,"+strings.Repeat("A",199)} {must(service.Index(ctx,capturer,&types.IndexInfo{Content:text,SourceID:fmt.Sprintf("payload-%d",i),ChunkID:fmt.Sprintf("payload-%d",i),IsEnabled:true},[]types.RetrieverType{types.VectorRetrieverType}));inputs=append(inputs,map[string]string{"input":text,"output":capturer.text})}
 out:=map[string]any{"embedding_inputs":inputs,"schema":schema,"steps":steps,"unicode":map[string]any{"Han":unicode.Han,"Punct":unicode.Punct,"Symbol":unicode.Symbol,"Space":unicode.White_Space},"sqlite_version":"","vec_version":""}
 must(db.Raw("SELECT sqlite_version()").Scan(&outVersion).Error);out["sqlite_version"]=outVersion;must(db.Raw("SELECT vec_version()").Scan(&outVersion).Error);out["vec_version"]=outVersion
 f,err:=os.Create(os.Args[1]);must(err);defer f.Close();enc:=json.NewEncoder(f);enc.SetIndent("","  ");must(enc.Encode(out))
}
var outVersion string
func must(err error){if err!=nil{panic(err)}}

type captureEmbedder struct{embedding.Embedder;text string}
func(e *captureEmbedder)Embed(_ context.Context,text string)([]float32,error){e.text=text;return []float32{1},nil}
