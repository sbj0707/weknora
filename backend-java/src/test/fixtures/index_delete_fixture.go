//go:build ignore

// Production index-delete worker oracle: actual SQLite metadata/vec/FTS and ordered scoped batches.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	repo "github.com/Tencent/WeKnora/internal/application/repository"
	sqliteindex "github.com/Tencent/WeKnora/internal/application/repository/retriever/sqlite"
	svc "github.com/Tencent/WeKnora/internal/application/service"
	"github.com/Tencent/WeKnora/internal/application/service/retriever"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	secutils "github.com/Tencent/WeKnora/internal/utils"
	vec "github.com/asg017/sqlite-vec-go-bindings/cgo"
	"github.com/hibiken/asynq"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
	gl "gorm.io/gorm/logger"
	"os"
	"path/filepath"
	"time"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}

type capturedEngine struct {
	interfaces.RetrieveEngineService
	calls []any
	name  string
}

func (c *capturedEngine) DeleteByChunkIDList(ctx context.Context, ids []string, dim int, kind string) error {
	c.calls = append(c.calls, map[string]any{"ids": ids, "dimension": dim, "kb_type": kind})
	if c.name == "delete-error" || c.name == "second-batch-error" && len(c.calls) == 2 {
		return errors.New("controlled index cleanup failure")
	}
	return c.RetrieveEngineService.DeleteByChunkIDList(ctx, ids, dim, kind)
}
func main() {
	must(os.Setenv("SSRF_WHITELIST", "127.0.0.1"))
	secutils.ResetSSRFWhitelistForTest()
	vec.Auto()
	out := []any{}
	for _, name := range []string{"normal", "empty-ids", "null-ids", "duplicates", "many", "second-batch-error", "delete-error", "no-engines", "unsupported-engine", "model-missing", "model-foreign", "model-type", "payload-null", "payload-array", "payload-fraction", "payload-negative-owner", "payload-chunk-number", "payload-model-number", "payload-engines-string", "wrong-kb", "foreign-chunk", "same-owner-other-kb", "mixed-scope", "missing-chunk", "soft-deleted-chunk", "soft-deleted-kb", "changed-kb-owner"} {
		folder, e := os.MkdirTemp("", "weknora-go-index-delete-")
		must(e)
		db, e := gorm.Open(sqlite.Open(filepath.Join(folder, "test.db")), &gorm.Config{Logger: gl.Default.LogMode(gl.Silent)})
		must(e)
		sql, e := db.DB()
		must(e)
		sql.SetMaxOpenConns(1)
		for _, m := range []string{"000000_init", "000003_knowledge_base_auto_tag_config", "000007_system_admin_and_settings", "000008_processing_spans_and_pending_subtasks", "000011_principal_model", "000021_knowledge_profiles", "000031_model_catalog_config", "000033_chunk_source_locators"} {
			b, e := os.ReadFile("migrations/sqlite/" + m + ".up.sql")
			must(e)
			must(db.Exec(string(b)).Error)
		}
		must(db.Exec("INSERT INTO tenants(id,name,business) VALUES(1,'Owner','isolated'),(2,'Peer','isolated')").Error)
		must(db.Exec("INSERT INTO knowledge_bases(id,tenant_id,name,type,embedding_model_id,summary_model_id) VALUES('kb',1,'KB','document','embed',''),('peer-kb',2,'Peer','document','embed',''),('other-kb',1,'Other','document','embed','')").Error)
		must(db.Exec("INSERT INTO models(id,tenant_id,name,type,source,parameters,status) VALUES('embed',1,'unknown','Embedding','remote',?,'active')", []byte(`{"provider":"generic","base_url":"http://127.0.0.1:1/v1","embedding_parameters":{"dimension":2}}`)).Error)
		if name == "model-missing" {
			must(db.Exec("DELETE FROM models").Error)
		}
		if name == "model-foreign" {
			must(db.Exec("UPDATE models SET tenant_id=2").Error)
		}
		if name == "model-type" {
			must(db.Exec("UPDATE models SET type='KnowledgeQA'").Error)
		}
		index := sqliteindex.NewSQLiteRetrieveEngineRepository(db)
		chunkIDs := []string{"chunk"}
		if name == "many" || name == "second-batch-error" {
			chunkIDs = []string{}
			for i := 0; i < 205; i++ {
				chunkIDs = append(chunkIDs, fmt.Sprintf("chunk-%03d", i))
			}
		}
		infos := []*types.IndexInfo{}
		vectors := map[string][]float32{}
		for _, id := range append(append([]string{}, chunkIDs...), "peer") {
			kb, doc, owner := "kb", "doc", 1
			if id == "peer" {
				kb, doc, owner = "peer-kb", "peer-doc", 2
			}
			must(db.Exec("INSERT INTO chunks(id,tenant_id,knowledge_base_id,knowledge_id,chunk_index,start_at,end_at,content,chunk_type) VALUES(?,?,?,?,0,0,0,'body','text')", id, owner, kb, doc).Error)
			for _, source := range []string{id, id + "-question"} {
				infos = append(infos, &types.IndexInfo{SourceID: source, SourceType: 0, ChunkID: id, KnowledgeID: doc, KnowledgeBaseID: kb, Content: "actual index", IsEnabled: true})
				vectors[source] = []float32{1, 0}
			}
		}
		must(index.BatchSave(context.Background(), infos, map[string]any{"embedding": vectors}))
		switch name {
		case "same-owner-other-kb":
			must(db.Exec("UPDATE chunks SET knowledge_base_id='other-kb' WHERE id='chunk'").Error)
		case "soft-deleted-chunk":
			must(db.Exec("UPDATE chunks SET deleted_at=CURRENT_TIMESTAMP WHERE id='chunk'").Error)
		case "soft-deleted-kb":
			must(db.Exec("UPDATE chunks SET deleted_at=CURRENT_TIMESTAMP WHERE id='chunk'").Error)
			must(db.Exec("UPDATE knowledge_bases SET deleted_at=CURRENT_TIMESTAMP WHERE id='kb'").Error)
		case "changed-kb-owner":
			must(db.Exec("UPDATE knowledge_bases SET tenant_id=2 WHERE id='kb'").Error)
		}
		engine := &capturedEngine{RetrieveEngineService: retriever.NewKVHybridRetrieveEngine(index, types.SQLiteRetrieverEngineType), name: name, calls: []any{}}
		registry := retriever.NewRetrieveEngineRegistry(nil, nil)
		must(registry.Register(engine))
		payload := types.IndexDeletePayload{TenantID: 1, KnowledgeBaseID: "kb", EmbeddingModelID: "embed", KBType: "document", ChunkIDs: chunkIDs, EffectiveEngines: []types.RetrieverEngineParams{{RetrieverEngineType: types.SQLiteRetrieverEngineType, RetrieverType: types.VectorRetrieverType}, {RetrieverEngineType: types.SQLiteRetrieverEngineType, RetrieverType: types.KeywordsRetrieverType}}}
		if name == "empty-ids" {
			payload.ChunkIDs = []string{}
		}
		if name == "null-ids" {
			payload.ChunkIDs = nil
		}
		if name == "duplicates" {
			payload.ChunkIDs = []string{"chunk", "chunk"}
		}
		if name == "no-engines" {
			payload.EffectiveEngines = nil
		}
		if name == "unsupported-engine" {
			payload.EffectiveEngines[0].RetrieverEngineType = "unsupported"
		}
		if name == "wrong-kb" {
			payload.KnowledgeBaseID = "peer-kb"
		}
		if name == "mixed-scope" {
			payload.ChunkIDs = []string{"peer", "chunk", "absent", "chunk"}
		}
		if name == "missing-chunk" {
			payload.ChunkIDs = []string{"absent"}
		}
		if name == "foreign-chunk" {
			payload.ChunkIDs = []string{"peer"}
		}
		raw, e := json.Marshal(payload)
		must(e)
		switch name {
		case "payload-null":
			raw = []byte("null")
		case "payload-array":
			raw = []byte("[]")
		case "payload-fraction":
			raw = []byte(`{"tenant_id":1.0}`)
		case "payload-negative-owner":
			raw = []byte(`{"tenant_id":-1}`)
		case "payload-chunk-number":
			raw = []byte(`{"tenant_id":1,"chunk_ids":[2]}`)
		case "payload-model-number":
			raw = []byte(`{"tenant_id":1,"embedding_model_id":2}`)
		case "payload-engines-string":
			raw = []byte(`{"tenant_id":1,"effective_engines":"sqlite"}`)
		}
		models := svc.NewModelService(repo.NewModelRepository(db), repo.NewKnowledgeBaseRepository(db), nil, nil, nil, nil)
		tags, e := svc.NewKnowledgeTagService(nil, repo.NewKnowledgeTagRepository(db), repo.NewKnowledgeRepository(db), repo.NewChunkRepository(db), registry, nil, models, nil, nil, nil, nil)
		must(e)
		err := tags.ProcessIndexDelete(context.WithValue(context.Background(), types.TenantIDContextKey, uint64(1)), asynq.NewTask(types.TypeIndexDelete, raw))
		message := ""
		if err != nil {
			message = err.Error()
		}
		var remaining []map[string]any
		must(db.Table("lite_embeddings").Select("source_id,chunk_id,knowledge_id,knowledge_base_id").Order("source_id").Find(&remaining).Error)
		var counts struct {
			Metadata int64
			Vectors  int64
			Keywords int64
		}
		db.Table("lite_embeddings").Count(&counts.Metadata)
		db.Table("vec_embeddings_2").Count(&counts.Vectors)
		db.Table("lite_embeddings_fts").Count(&counts.Keywords)
		out = append(out, map[string]any{"name": name, "payload": json.RawMessage(raw), "calls": engine.calls, "error": message, "remaining": remaining, "counts": counts})
		sql.Close()
		os.RemoveAll(folder)
	}
	b, e := json.Marshal(out)
	must(e)
	must(os.WriteFile(os.Args[1], append(b, '\n'), 0600))
	fmt.Println("exported", len(out), "actual Go index cleanup scenes", time.Now().Format(time.RFC3339))
}
