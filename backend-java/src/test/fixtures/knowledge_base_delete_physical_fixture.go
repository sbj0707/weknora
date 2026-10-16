//go:build ignore

// Real ProcessKBDelete, SQL indexes (including vec/FTS), resource claims and local files.
package main

import (
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"strings"

	repo "github.com/Tencent/WeKnora/internal/application/repository"
	pg "github.com/Tencent/WeKnora/internal/application/repository/retriever/postgres"
	lite "github.com/Tencent/WeKnora/internal/application/repository/retriever/sqlite"
	svc "github.com/Tencent/WeKnora/internal/application/service"
	"github.com/Tencent/WeKnora/internal/application/service/file"
	"github.com/Tencent/WeKnora/internal/application/service/retriever"
	"github.com/Tencent/WeKnora/internal/logger"
	"github.com/Tencent/WeKnora/internal/models/embedding"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	sqlitevec "github.com/asg017/sqlite-vec-go-bindings/cgo"
	"github.com/hibiken/asynq"
	"gorm.io/driver/postgres"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
	gl "gorm.io/gorm/logger"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}

type embedder struct{ embedding.Embedder }

func (embedder) GetDimensions() int { return 2 }

type models struct{ interfaces.ModelService }

func (models) GetEmbeddingModel(_ context.Context, id string) (embedding.Embedder, error) {
	if id == "" {
		return nil, errors.New("empty embedding model")
	}
	return embedder{}, nil
}

type registry struct {
	interfaces.RetrieveEngineRegistry
	engine interfaces.RetrieveEngineService
}

func (r registry) GetRetrieveEngineService(types.RetrieverEngineType) (interfaces.RetrieveEngineService, error) {
	return r.engine, nil
}

func main() {
	logger.SetOutput(os.Stderr)
	sqlitevec.Auto()
	rows := []any{}
	for _, name := range []string{"normal", "duplicate-engines", "empty-engines", "empty-model", "deleted-document", "shared-image", "claim-error", "missing-file", "index-metadata-error", "documents-error-retry", "scoped-original", "dummy-provider"} {
		rows = append(rows, run(name))
	}
	data, err := json.MarshalIndent(rows, "", "  ")
	must(err)
	must(os.WriteFile(os.Args[1], append(data, '\n'), 0600))
	fmt.Println("exported", len(rows), "actual Go physical KB delete cases")
}

func run(name string) any {
	dir, err := os.MkdirTemp("", "weknora-go-kb-delete-physical-")
	must(err)
	defer os.RemoveAll(dir)
	dsn := os.Getenv("WEKNORA_KB_DELETE_PG_DSN")
	var dialect gorm.Dialector = sqlite.Open(filepath.Join(dir, "db.sqlite"))
	if dsn != "" {
		u, err := url.Parse(dsn)
		must(err)
		if u.Hostname() != "127.0.0.1" || u.Path != "/weknora_kb_delete_parity" {
			panic("owned loopback KB deletion DB required")
		}
		dialect = postgres.Open(dsn)
	}
	db, err := gorm.Open(dialect, &gorm.Config{Logger: gl.Default.LogMode(gl.Silent)})
	must(err)
	connection, err := db.DB()
	must(err)
	defer connection.Close()
	connection.SetMaxOpenConns(1)
	engineType, indexTable := types.SQLiteRetrieverEngineType, "lite_embeddings"
	if dsn == "" {
		for _, migration := range []string{"000000_init", "000003_knowledge_base_auto_tag_config", "000004_memory", "000006_task_queue_and_dead_letters", "000007_system_admin_and_settings", "000008_processing_spans_and_pending_subtasks", "000010_knowledge_multi_tags", "000011_principal_model", "000021_knowledge_profiles", "000031_model_catalog_config", "000033_chunk_source_locators"} {
			data, err := os.ReadFile("migrations/sqlite/" + migration + ".up.sql")
			must(err)
			must(db.Exec(string(data)).Error)
		}
	} else {
		engineType, indexTable = types.PostgresRetrieverEngineType, "embeddings"
		must(db.Exec("DROP TRIGGER IF EXISTS kb_physical_fault ON embeddings").Error)
		must(db.Exec("DROP TRIGGER IF EXISTS kb_physical_fault ON knowledges").Error)
		must(db.Exec("DROP TRIGGER IF EXISTS kb_physical_fault ON resource_bindings").Error)
		must(db.Exec("TRUNCATE tenants,knowledge_bases,knowledges,chunks,chunk_images,wiki_pages,wiki_folders,wiki_page_revisions,wiki_page_issues,task_pending_ops,resources,resource_bindings,embeddings RESTART IDENTITY CASCADE").Error)
	}
	seeds := []string{}
	seed := func(statement string) { must(db.Exec(statement).Error); seeds = append(seeds, statement) }
	seed("INSERT INTO tenants(id,name,business,storage_used,storage_engine_config) VALUES(1,'Owner','test',500,'{}'),(2,'Peer','test',700,'{}')")
	seed("INSERT INTO knowledge_bases(id,tenant_id,name,type,embedding_model_id,summary_model_id,deleted_at) VALUES('kb',1,'Deleted','document','embed','',CURRENT_TIMESTAMP),('other-kb',2,'Other','document','embed','',NULL)")
	const sourceRef = "resource://AAAAAAAAAAAAAAAAAAAAAA"
	const exclusiveRef = "resource://CCCCCCCCCCCCCCCCCCCCCC"
	const sharedRef = "resource://BBBBBBBBBBBBBBBBBBBBBB"
	for i, row := range []struct {
		id, kb string
		owner  int
	}{{"a", "kb", 1}, {"b", "kb", 1}, {"other", "other-kb", 2}} {
		path := sourceRef
		if row.id != "a" {
			path = "local://" + fmt.Sprint(row.owner) + "/" + row.id + ".txt"
		}
		seed(fmt.Sprintf("INSERT INTO knowledges(id,tenant_id,knowledge_base_id,type,source,title,parse_status,embedding_model_id,file_path,storage_size,created_at) VALUES('%s',%d,'%s','file','manual','Document','completed','embed','%s',100,'2026-01-%02d 00:00:00+00:00')", row.id, row.owner, row.kb, path, i+1))
		images, _ := json.Marshal([]map[string]string{{"url": exclusiveRef}, {"url": sharedRef}, {"url": "local://1/legacy.png"}, {"url": exclusiveRef}})
		seed(fmt.Sprintf("INSERT INTO chunks(id,tenant_id,knowledge_base_id,knowledge_id,chunk_index,start_at,end_at,content,image_info) VALUES('%s-chunk',%d,'%s','%s',0,0,4,'body','%s')", row.id, row.owner, row.kb, row.id, images))
		for _, statement := range []string{
			fmt.Sprintf("INSERT INTO wiki_pages(id,tenant_id,knowledge_base_id,slug) VALUES('%s',%d,'%s','%s')", row.id, row.owner, row.kb, row.id),
			fmt.Sprintf("INSERT INTO wiki_folders(id,tenant_id,knowledge_base_id,name) VALUES('%s',%d,'%s','%s')", row.id, row.owner, row.kb, row.id),
			fmt.Sprintf("INSERT INTO wiki_page_revisions(id,tenant_id,knowledge_base_id,page_id,slug,version) VALUES('%s',%d,'%s','%s','%s',1)", row.id, row.owner, row.kb, row.id, row.id),
			fmt.Sprintf("INSERT INTO wiki_page_issues(id,tenant_id,knowledge_base_id,slug,issue_type,description,reported_by) VALUES('%s',%d,'%s','%s','test','test','test')", row.id, row.owner, row.kb, row.id),
			fmt.Sprintf("INSERT INTO task_pending_ops(tenant_id,task_type,scope,scope_id,op) VALUES(%d,'wiki:ingest','knowledge_base','%s','test')", row.owner, row.kb),
		} {
			seed(statement)
		}
	}
	paths := []string{"1/a.txt", "1/b.txt", "1/exclusive.png", "1/shared.png", "1/legacy.png", "2/other.txt"}
	for _, path := range paths {
		target := filepath.Join(dir, "files", path)
		must(os.MkdirAll(filepath.Dir(target), 0700))
		must(os.WriteFile(target, []byte(path), 0600))
	}
	for _, row := range []struct{ id, handle, path string }{{"source", "AAAAAAAAAAAAAAAAAAAAAA", "1/a.txt"}, {"exclusive", "CCCCCCCCCCCCCCCCCCCCCC", "1/exclusive.png"}, {"shared", "BBBBBBBBBBBBBBBBBBBBBB", "1/shared.png"}} {
		physical := "local://" + row.path
		seed(fmt.Sprintf("INSERT INTO resources(id,handle,tenant_id,provider,physical_path,location_hash) VALUES('%s','%s',1,'local','%s','%x')", row.id, row.handle, physical, sha256.Sum256([]byte(physical))))
		for _, owner := range []string{"a", "b"} {
			seed(fmt.Sprintf("INSERT INTO resource_bindings(id,resource_id,tenant_id,owner_type,owner_id,relation) VALUES('%s-%s','%s',1,'knowledge','%s','attachment')", row.id, owner, row.id, owner))
		}
	}
	if name == "shared-image" {
		seed("INSERT INTO resource_bindings(id,resource_id,tenant_id,owner_type,owner_id,relation) VALUES('message','shared',1,'message','message','attachment')")
	}
	if name == "deleted-document" {
		seed("UPDATE knowledges SET deleted_at=CURRENT_TIMESTAMP WHERE id='a'")
	}
	if name == "empty-model" {
		seed("UPDATE knowledges SET embedding_model_id='' WHERE tenant_id=1")
	}
	if name == "scoped-original" {
		seed("UPDATE knowledges SET file_path='storage://previous-backend/local://1/b.txt' WHERE id='b'")
	}
	if name == "missing-file" {
		must(os.Remove(filepath.Join(dir, "files", "1/a.txt")))
	}
	var index interfaces.RetrieveEngineRepository
	if dsn == "" {
		index = lite.NewSQLiteRetrieveEngineRepository(db)
	} else {
		index = pg.NewPostgresRetrieveEngineRepository(db)
	}
	entries := []*types.IndexInfo{}
	vectors := map[string][]float32{}
	for _, id := range []string{"a", "b", "other"} {
		kb := "kb"
		if id == "other" {
			kb = "other-kb"
		}
		entries = append(entries, &types.IndexInfo{SourceID: id + "-chunk", SourceType: types.ChunkSourceType, ChunkID: id + "-chunk", KnowledgeID: id, KnowledgeBaseID: kb, Content: "body " + id, IsEnabled: true})
		vectors[id+"-chunk"] = []float32{1, 0}
	}
	entries = append(entries, &types.IndexInfo{SourceID: "a-question", SourceType: types.ChunkSourceType, ChunkID: "a-chunk", KnowledgeID: "a", KnowledgeBaseID: "kb", Content: "question a", IsEnabled: true})
	vectors["a-question"] = []float32{0, 1}
	must(index.BatchSave(context.Background(), entries, map[string]any{"embedding": vectors}))
	mutations := []string{}
	if name == "index-metadata-error" || name == "claim-error" || name == "documents-error-retry" {
		table, operation := indexTable, "DELETE"
		if name == "claim-error" {
			table = "resource_bindings"
		}
		if name == "documents-error-retry" {
			table, operation = "knowledges", "UPDATE"
		}
		if dsn == "" {
			mutations = append(mutations, fmt.Sprintf("CREATE TRIGGER kb_physical_fault BEFORE %s ON %s BEGIN SELECT RAISE(ABORT,'controlled physical failure'); END", operation, table))
		} else {
			mutations = append(mutations, "CREATE OR REPLACE FUNCTION kb_physical_fault_fn() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'controlled physical failure' USING ERRCODE='P0001'; END $$", fmt.Sprintf("CREATE TRIGGER kb_physical_fault BEFORE %s ON %s FOR EACH ROW EXECUTE FUNCTION kb_physical_fault_fn()", operation, table))
		}
		for _, statement := range mutations {
			must(db.Exec(statement).Error)
		}
	}
	provider := "local"
	var physical interfaces.FileService = file.NewLocalFileService(filepath.Join(dir, "files"), "")
	if name == "dummy-provider" {
		provider = "dummy"
		physical = file.NewDummyFileService()
	}
	catalog := svc.NewResourceCatalog(repo.NewResourceRepository(db))
	files := file.NewResourceCatalogFileService(physical, catalog)
	service := svc.NewKnowledgeBaseService(repo.NewKnowledgeBaseRepository(db), repo.NewKnowledgeRepository(db), repo.NewChunkRepository(db), nil, nil, models{}, registry{engine: retriever.NewKVHybridRetrieveEngine(index, engineType)}, nil, repo.NewTenantRepository(db), files, nil, nil, nil, nil, repo.NewTaskPendingOpsRepository(db), nil, nil, nil, nil, catalog, repo.NewWikiPageRepository(db))
	engines := []types.RetrieverEngineParams{{RetrieverEngineType: engineType, RetrieverType: types.VectorRetrieverType}}
	if name == "empty-engines" {
		engines = nil
	}
	if name == "duplicate-engines" {
		engines = append(engines, types.RetrieverEngineParams{RetrieverEngineType: engineType, RetrieverType: types.KeywordsRetrieverType})
	}
	payload := types.KBDeletePayload{TenantID: 1, KnowledgeBaseID: "kb", DataSourceIDs: []string{"source"}, EffectiveEngines: engines}
	raw, err := json.Marshal(payload)
	must(err)
	attempts := 1
	if name == "documents-error-retry" {
		attempts = 2
	}
	results := []string{}
	for i := 0; i < attempts; i++ {
		err := service.ProcessKBDelete(context.Background(), asynq.NewTask(types.TypeKBDelete, raw))
		verdict := "success"
		if err != nil {
			verdict = "retry"
			if errors.Is(err, asynq.SkipRetry) {
				verdict = "skip_retry"
			}
		}
		results = append(results, verdict)
	}
	state := map[string]any{}
	for _, table := range []string{"knowledges", "chunks", "wiki_pages", "wiki_folders", "wiki_page_issues"} {
		rows := []map[string]any{}
		must(db.Raw("SELECT id,tenant_id,knowledge_base_id,CASE WHEN deleted_at IS NULL THEN 0 ELSE 1 END AS deleted FROM " + table + " ORDER BY id").Scan(&rows).Error)
		state[table] = rows
	}
	queries := map[string]string{"tenants": "SELECT id,storage_used FROM tenants ORDER BY id", "wiki_page_revisions": "SELECT id,tenant_id,knowledge_base_id FROM wiki_page_revisions ORDER BY id", "task_pending_ops": "SELECT tenant_id,scope,scope_id,op FROM task_pending_ops ORDER BY id", "resources": "SELECT id,state,CASE WHEN deleted_at IS NULL THEN 0 ELSE 1 END AS deleted FROM resources ORDER BY id", "resource_bindings": "SELECT resource_id,owner_type,owner_id FROM resource_bindings ORDER BY resource_id,owner_type,owner_id", "indexes": "SELECT id,source_id,knowledge_id,knowledge_base_id,dimension FROM " + indexTable + " ORDER BY id"}
	if dsn == "" {
		queries["vectors"] = "SELECT rowid AS id,hex(embedding) AS embedding FROM vec_embeddings_2 ORDER BY rowid"
		queries["keyword_hits"] = "SELECT rowid AS id FROM lite_embeddings_fts WHERE lite_embeddings_fts MATCH 'body OR question' ORDER BY rowid"
	} else {
		queries["vectors"] = "SELECT id,embedding::text AS embedding FROM embeddings ORDER BY id"
	}
	for key, query := range queries {
		rows := []map[string]any{}
		must(db.Raw(query).Scan(&rows).Error)
		state[key] = rows
	}
	remaining := map[string]bool{}
	for _, path := range paths {
		_, err := os.Stat(filepath.Join(dir, "files", path))
		remaining[path] = err == nil
	}
	return map[string]any{"name": name, "seed_statements": seeds, "mutations": mutations, "payload": payload, "entries": entries, "vectors": vectors, "initial_files": paths, "missing_file": name == "missing-file", "provider": provider, "results": results, "state": state, "files": remaining, "engine": strings.ToLower(string(engineType))}
}
