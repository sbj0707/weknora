//go:build ignore

// Actual Go knowledge-list cleanup worker, scoped SQL/vec/FTS/local bytes and admitted effects.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	repo "github.com/Tencent/WeKnora/internal/application/repository"
	graphrepo "github.com/Tencent/WeKnora/internal/application/repository/retriever/neo4j"
	sqliteindex "github.com/Tencent/WeKnora/internal/application/repository/retriever/sqlite"
	svc "github.com/Tencent/WeKnora/internal/application/service"
	filesvc "github.com/Tencent/WeKnora/internal/application/service/file"
	"github.com/Tencent/WeKnora/internal/application/service/retriever"
	"github.com/Tencent/WeKnora/internal/config"
	"github.com/Tencent/WeKnora/internal/logger"
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
	"strings"
	"time"
)

func must(e error) {
	if e != nil {
		panic(e)
	}
}

type engine struct {
	interfaces.RetrieveEngineService
	calls []any
	fail  bool
}

func (e *engine) DeleteByKnowledgeIDList(ctx context.Context, ids []string, dim int, kind string) error {
	e.calls = append(e.calls, map[string]any{"ids": ids, "dimension": dim, "type": kind})
	if e.fail {
		return errors.New("controlled vector deletion error")
	}
	return e.RetrieveEngineService.DeleteByKnowledgeIDList(ctx, ids, dim, kind)
}

type graph struct {
	interfaces.RetrieveGraphRepository
	calls []any
	fail  bool
}

func (g *graph) DelGraph(ctx context.Context, namespaces []types.NameSpace) error {
	g.calls = append(g.calls, namespaces)
	if g.fail {
		return errors.New("controlled graph deletion error")
	}
	return g.RetrieveGraphRepository.DelGraph(ctx, namespaces)
}

type file struct {
	interfaces.FileService
	calls []string
	fail  bool
}

func (f *file) DeleteFile(ctx context.Context, path string) error {
	f.calls = append(f.calls, path)
	if f.fail {
		return errors.New("controlled file deletion error")
	}
	return f.FileService.DeleteFile(ctx, path)
}
func main() {
	logger.SetOutput(os.Stderr)
	vec.Auto()
	must(os.Setenv("SSRF_WHITELIST", "127.0.0.1"))
	secutils.ResetSSRFWhitelistForTest()
	out := []any{}
	scenes := []string{"normal", "empty", "null-ids", "duplicate-ids", "missing-document", "foreign-document", "same-owner-other-kb", "mixed-foreign", "legacy-single", "legacy-ambiguous", "blank-id", "space-id", "zero-owner", "foreign-owner", "changed-kb-owner", "deleted-kb", "deleted-document", "moving", "model-missing", "model-foreign", "model-type", "empty-document-model", "no-engines", "vector-error", "graph-error", "file-error", "storage-underflow", "initiator", "payload-null", "payload-array", "payload-fraction", "payload-negative-owner", "payload-number-id", "payload-model-unknown", "resource-shared-image", "resource-last-image", "moving-copy", "moving-phase-only", "moving-casefold", "moving-null-operation", "moving-duplicate-null", "moving-invalid-operation", "moving-invalid-metadata", "moving-root-null", "moving-root-array", "moving-outer-casefold"}
	for _, name := range scenes {
		folder, e := os.MkdirTemp("", "weknora-go-knowledge-delete-")
		must(e)
		db, e := gorm.Open(sqlite.Open(filepath.Join(folder, "db.sqlite")), &gorm.Config{Logger: gl.Default.LogMode(gl.Silent)})
		must(e)
		sql, e := db.DB()
		must(e)
		sql.SetMaxOpenConns(1)
		for _, m := range []string{"000000_init", "000003_knowledge_base_auto_tag_config", "000004_memory", "000007_system_admin_and_settings", "000008_processing_spans_and_pending_subtasks", "000010_knowledge_multi_tags", "000011_principal_model", "000021_knowledge_profiles", "000031_model_catalog_config", "000033_chunk_source_locators"} {
			b, e := os.ReadFile("migrations/sqlite/" + m + ".up.sql")
			must(e)
			must(db.Exec(string(b)).Error)
		}
		must(os.Setenv("RETRIEVE_DRIVER", "sqlite"))
		if name == "no-engines" {
			must(os.Setenv("RETRIEVE_DRIVER", ""))
		}
		must(db.Exec("INSERT INTO tenants(id,name,business,storage_used) VALUES(1,'Owner','isolated',500),(2,'Peer','isolated',700)").Error)
		must(db.Exec("INSERT INTO knowledge_bases(id,tenant_id,name,type,embedding_model_id,summary_model_id) VALUES('kb',1,'KB','document','embed',''),('peer-kb',2,'Peer','document','embed',''),('other-kb',1,'Other','document','embed','')").Error)
		must(db.Exec("INSERT INTO models(id,tenant_id,name,type,source,parameters,status) VALUES('embed',1,'unknown','Embedding','remote',?,'active')", []byte(`{"provider":"generic","base_url":"http://127.0.0.1:1/v1","embedding_parameters":{"dimension":2}}`)).Error)
		root := filepath.Join(folder, "files")
		paths := map[string]string{"doc": "1/doc/file.txt", "other": "1/other/file.txt", "peer": "2/peer/file.txt", "image": "1/image.png"}
		for _, path := range paths {
			must(os.MkdirAll(filepath.Dir(filepath.Join(root, path)), 0700))
			must(os.WriteFile(filepath.Join(root, path), []byte("isolated bytes"), 0600))
		}
		index := sqliteindex.NewSQLiteRetrieveEngineRepository(db)
		infos := []*types.IndexInfo{}
		vectors := map[string][]float32{}
		for _, d := range []struct {
			id, kb string
			owner  int
		}{{"doc", "kb", 1}, {"other", "other-kb", 1}, {"peer", "peer-kb", 2}} {
			must(db.Exec("INSERT INTO knowledges(id,tenant_id,knowledge_base_id,type,source,title,parse_status,enable_status,embedding_model_id,file_path,storage_size,created_at,updated_at) VALUES(?,?,?,'file','manual',?,'completed','enabled','embed',?,100,?,?)", d.id, d.owner, d.kb, d.id, "local://"+paths[d.id], time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC), time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)).Error)
			image := ""
			if d.id == "doc" {
				image = `[{"url":"local://1/image.png"},{"url":"local://1/image.png"}]`
			}
			must(db.Exec("INSERT INTO chunks(id,tenant_id,knowledge_base_id,knowledge_id,chunk_index,start_at,end_at,content,chunk_type,image_info) VALUES(?,?,?,?,0,0,0,'body','text',?)", d.id+"-chunk", d.owner, d.kb, d.id, image).Error)
			for _, source := range []string{d.id + "-chunk", d.id + "-question"} {
				infos = append(infos, &types.IndexInfo{SourceID: source, SourceType: 0, ChunkID: d.id + "-chunk", KnowledgeID: d.id, KnowledgeBaseID: d.kb, Content: "actual index", IsEnabled: true})
				vectors[source] = []float32{1, 0}
			}
		}
		must(index.BatchSave(context.Background(), infos, map[string]any{"embedding": vectors}))
		must(db.Exec("INSERT INTO knowledge_tags(id,seq_id,tenant_id,knowledge_base_id,name) VALUES('tag',1,1,'kb','Tag')").Error)
		must(db.Exec("INSERT INTO knowledge_tag_relations(knowledge_id,tag_id) VALUES('doc','tag')").Error)
		switch name {
		case "changed-kb-owner":
			must(db.Exec("UPDATE knowledge_bases SET tenant_id=2 WHERE id='kb'").Error)
		case "deleted-kb":
			must(db.Exec("UPDATE knowledge_bases SET deleted_at=CURRENT_TIMESTAMP WHERE id='kb'").Error)
		case "deleted-document":
			must(db.Exec("UPDATE knowledges SET deleted_at=CURRENT_TIMESTAMP WHERE id='doc'").Error)
		case "moving":
			must(db.Exec("UPDATE knowledges SET metadata=? WHERE id='doc'", []byte(`{"_knowledge_transfer":{"operation":"move","phase":"moving"}}`)).Error)
		case "moving-copy":
			must(db.Exec("UPDATE knowledges SET metadata=? WHERE id='doc'", []byte(`{"_knowledge_transfer":{"operation":"copy","phase":"moving"}}`)).Error)
		case "moving-phase-only":
			must(db.Exec("UPDATE knowledges SET metadata=? WHERE id='doc'", []byte(`{"_knowledge_transfer":{"phase":"moving"}}`)).Error)
		case "moving-casefold":
			must(db.Exec("UPDATE knowledges SET metadata=? WHERE id='doc'", []byte(`{"_knowledge_transfer":{"OPERATION":"move","PHASE":"moving"}}`)).Error)
		case "moving-null-operation":
			must(db.Exec("UPDATE knowledges SET metadata=? WHERE id='doc'", []byte(`{"_knowledge_transfer":{"operation":null,"phase":"moving"}}`)).Error)
		case "moving-duplicate-null":
			must(db.Exec("UPDATE knowledges SET metadata=? WHERE id='doc'", []byte(`{"_knowledge_transfer":{"operation":"move","operation":null,"phase":"moving"}}`)).Error)
		case "moving-invalid-operation":
			must(db.Exec("UPDATE knowledges SET metadata=? WHERE id='doc'", []byte(`{"_knowledge_transfer":{"operation":4,"operation":"move","phase":"moving"}}`)).Error)
		case "moving-invalid-metadata":
			must(db.Exec("UPDATE knowledges SET metadata=? WHERE id='doc'", []byte(`{"_knowledge_transfer":4}`)).Error)
		case "moving-root-null":
			must(db.Exec("UPDATE knowledges SET metadata=? WHERE id='doc'", []byte(`null`)).Error)
		case "moving-root-array":
			must(db.Exec("UPDATE knowledges SET metadata=? WHERE id='doc'", []byte(`[]`)).Error)
		case "moving-outer-casefold":
			must(db.Exec("UPDATE knowledges SET metadata=? WHERE id='doc'", []byte(`{"_KNOWLEDGE_TRANSFER":{"operation":"move","phase":"moving"}}`)).Error)
		case "model-missing":
			must(db.Exec("DELETE FROM models").Error)
		case "model-foreign":
			must(db.Exec("UPDATE models SET tenant_id=2").Error)
		case "model-type":
			must(db.Exec("UPDATE models SET type='KnowledgeQA'").Error)
		case "empty-document-model":
			must(db.Exec("UPDATE knowledges SET embedding_model_id='' WHERE id='doc'").Error)
		case "storage-underflow":
			must(db.Exec("UPDATE tenants SET storage_used=10 WHERE id=1").Error)
		}
		kbr, kr, cr, tr, tenant := repo.NewKnowledgeBaseRepository(db), repo.NewKnowledgeRepository(db), repo.NewChunkRepository(db), repo.NewKnowledgeTagRepository(db), repo.NewTenantRepository(db)
		kbs := svc.NewKnowledgeBaseService(kbr, kr, cr, nil, nil, nil, nil, nil, tenant, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil)
		models := svc.NewModelService(repo.NewModelRepository(db), kbr, nil, nil, nil, nil)
		eng := &engine{RetrieveEngineService: retriever.NewKVHybridRetrieveEngine(index, types.SQLiteRetrieverEngineType), calls: []any{}, fail: name == "vector-error"}
		registry := retriever.NewRetrieveEngineRegistry(nil, nil)
		must(registry.Register(eng))
		g := &graph{RetrieveGraphRepository: graphrepo.NewNeo4jRepository(nil), calls: []any{}, fail: name == "graph-error"}
		f := &file{FileService: filesvc.NewLocalFileService(root, ""), calls: []string{}, fail: name == "file-error"}
		var catalog interfaces.ResourceCatalog
		imageReference := ""
		if strings.HasPrefix(name, "resource-") {
			catalog = svc.NewResourceCatalog(repo.NewResourceRepository(db))
			imageReference, e = catalog.Register(context.Background(), 1, "local://1/image.png", interfaces.ResourceRegistration{Kind: "image"})
			must(e)
			must(catalog.Bind(context.Background(), imageReference, "knowledge", "doc", "image"))
			if name == "resource-shared-image" {
				must(catalog.Bind(context.Background(), imageReference, "knowledge", "other", "image"))
			}
			data, e := json.Marshal([]map[string]string{{"url": imageReference}, {"url": imageReference}})
			must(e)
			must(db.Exec("UPDATE chunks SET image_info=? WHERE id='doc-chunk'", string(data)).Error)
			f.FileService = filesvc.NewResourceCatalogFileService(f.FileService, catalog)
		}
		audit := svc.NewAuditLogService(repo.NewAuditLogRepository(db))
		service, e := svc.NewKnowledgeService(&config.Config{}, kr, nil, kbs, tenant, nil, nil, cr, tr, nil, f, nil, catalog, models, nil, nil, g, registry, nil, nil, nil, nil, nil, nil, nil, nil, audit)
		must(e)
		payload := types.KnowledgeListDeletePayload{TenantID: 1, KnowledgeBaseID: "kb", KnowledgeIDs: []string{"doc"}}
		switch name {
		case "empty":
			payload.KnowledgeIDs = []string{}
		case "null-ids":
			payload.KnowledgeIDs = nil
		case "duplicate-ids":
			payload.KnowledgeIDs = []string{"doc", "doc"}
		case "missing-document":
			payload.KnowledgeIDs = []string{"absent"}
		case "foreign-document":
			payload.KnowledgeIDs = []string{"peer"}
		case "same-owner-other-kb":
			payload.KnowledgeIDs = []string{"other"}
		case "mixed-foreign":
			payload.KnowledgeIDs = []string{"peer", "doc"}
		case "legacy-single":
			payload.KnowledgeBaseID = ""
		case "legacy-ambiguous":
			payload.KnowledgeBaseID = ""
			payload.KnowledgeIDs = []string{"doc", "other"}
		case "blank-id":
			payload.KnowledgeIDs = []string{""}
		case "space-id":
			payload.KnowledgeIDs = []string{" "}
		case "zero-owner":
			payload.TenantID = 0
		case "foreign-owner":
			payload.TenantID = 2
		case "initiator":
			payload.Initiator = types.TaskInitiator{UserID: "caller", Role: types.TenantRoleContributor, APIKeyID: 7, APIKeyName: "Operator"}
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
		case "payload-number-id":
			raw = []byte(`{"tenant_id":1,"knowledge_ids":[1]}`)
		case "payload-model-unknown":
			raw = []byte(`{"tenant_id":1,"knowledge_base_id":"kb","knowledge_ids":["doc"],"embedding_model_id":"ignored"}`)
		}
		err := service.ProcessKnowledgeListDelete(context.Background(), asynq.NewTask(types.TypeKnowledgeListDelete, raw))
		message := ""
		if err != nil {
			message = err.Error()
		}
		var documents, chunks, storage, relations, audits []map[string]any
		must(db.Table("knowledges").Select("id,tenant_id,knowledge_base_id,parse_status,storage_size,deleted_at IS NOT NULL AS deleted").Order("id").Find(&documents).Error)
		must(db.Table("chunks").Select("id,tenant_id,knowledge_base_id,deleted_at IS NOT NULL AS deleted").Order("id").Find(&chunks).Error)
		must(db.Table("tenants").Select("id,storage_used").Order("id").Find(&storage).Error)
		must(db.Table("knowledge_tag_relations").Select("knowledge_id,tag_id").Order("knowledge_id").Find(&relations).Error)
		must(db.Table("audit_logs").Select("tenant_id,actor_user_id,actor_role,action,target_type,target_id,scope_type,scope_id,outcome,details").Order("id").Find(&audits).Error)
		var surviving []map[string]any
		must(db.Table("lite_embeddings").Select("knowledge_id,chunk_id,source_id").Order("source_id").Find(&surviving).Error)
		counts := map[string]int64{}
		for _, table := range []string{"lite_embeddings", "vec_embeddings_2", "lite_embeddings_fts"} {
			var count int64
			must(db.Raw("SELECT COUNT(*) FROM " + table).Scan(&count).Error)
			counts[table] = count
		}
		existing := map[string]bool{}
		for key, path := range paths {
			_, e := os.Stat(filepath.Join(root, path))
			existing[key] = e == nil
		}
		var resources, bindings []map[string]any
		must(db.Table("resources").Select("handle,state").Order("handle").Find(&resources).Error)
		must(db.Table("resource_bindings").Select("tenant_id,owner_type,owner_id,relation").Order("owner_id").Find(&bindings).Error)
		out = append(out, map[string]any{"name": name, "payload": json.RawMessage(raw), "error": message, "skip_retry": errors.Is(err, asynq.SkipRetry), "documents": documents, "chunks": chunks, "storage": storage, "relations": relations, "audits": audits, "surviving_indexes": surviving, "physical": counts, "index_calls": eng.calls, "graph_calls": g.calls, "file_calls": f.calls, "files": existing, "image_reference": imageReference, "resources": resources, "resource_bindings": bindings})
		sql.Close()
		os.RemoveAll(folder)
	}
	bytes, e := json.Marshal(out)
	must(e)
	must(os.WriteFile(os.Args[1], append(bytes, '\n'), 0600))
	fmt.Println("exported", len(out), "actual Go knowledge-list cleanup SQL/index/local-file scenes", strings.Join(scenes, ","))
}
