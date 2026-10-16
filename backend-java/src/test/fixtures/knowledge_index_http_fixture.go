//go:build ignore

// Executes the production index handlers/services/repositories and exports each
// exact database seed, HTTP response and read-side-effect snapshot.
package main

import (
	"compress/gzip"
	"context"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/Tencent/WeKnora/internal/application/repository"
	sqliteindex "github.com/Tencent/WeKnora/internal/application/repository/retriever/sqlite"
	"github.com/Tencent/WeKnora/internal/application/service"
	"github.com/Tencent/WeKnora/internal/application/service/retriever"
	"github.com/Tencent/WeKnora/internal/config"
	"github.com/Tencent/WeKnora/internal/handler"
	log "github.com/Tencent/WeKnora/internal/logger"
	"github.com/Tencent/WeKnora/internal/middleware"
	"github.com/Tencent/WeKnora/internal/types"
	vec "github.com/asg017/sqlite-vec-go-bindings/cgo"
	"github.com/gin-gonic/gin"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
)

const fixed = "2026-10-14 01:02:03+00:00"

func must(err error) {
	if err != nil {
		panic(err)
	}
}
func execute(db *gorm.DB, query string, args ...any) { must(db.Exec(query, args...).Error) }
func main() {
	if len(os.Args) != 2 {
		panic("provide explicit gzip fixture output")
	}
	vec.Auto()
	log.SetOutput(io.Discard)
	gin.SetMode(gin.TestMode)
	must(types.LoadBuiltinAgentsConfig("config"))
	must(os.Setenv("RETRIEVE_DRIVER", "sqlite"))
	modes := []string{"normal", "config-text", "wiki-text-refs", "external-text-engine", "empty", "children-empty", "children-order", "children-invalid-document", "children-invalid-kb", "children-foreign-tenant", "children-deleted", "metadata-sql-null", "metadata-empty", "metadata-json-null", "metadata-question-null", "metadata-question-empty", "metadata-casefold", "metadata-duplicate-null", "metadata-duplicate-invalid", "metadata-revision-duplicate-null", "metadata-scalar", "metadata-blob", "metadata-wrong-question-type", "question-legacy", "question-stale", "question-long-id", "question-missing-keyword", "question-missing-vector", "primary-missing", "primary-disabled", "primary-null-enabled", "chunk-processing", "chunk-failed", "parent-text", "chunk-image", "chunks-table-failure", "chunk-not-found", "chunk-other-document", "chunk-other-kb", "chunk-other-tenant", "chunk-deleted", "history-model-type", "history-counter-type", "wiki-unicode-id", "history-missing", "history-table-failure", "history-malformed-map", "history-retry", "history-attempt", "history-config-change", "model-missing", "model-inactive", "model-name-fallback", "model-builtin", "model-foreign", "tenant-missing", "external-engine", "wiki-empty", "wiki-null-refs", "wiki-invalid-refs", "wiki-table-failure", "wiki-many", "disabled-question", "disabled-vector", "graph-enabled", "invalid-overrides", "invalid-document-metadata", "document-missing", "document-owner-mismatch", "kb-missing", "kb-table-failure", "query-ignored", "api-key-retrieve", "api-key-ingest", "api-key-full", "api-key-scope", "shared-viewer", "shared-revoked", "foreign-denied", "agent-share"}
	var corpus []any
	for _, endpoint := range []string{"status", "detail"} {
		for _, mode := range modes {
			corpus = append(corpus, scene(endpoint, mode))
		}
	}
	file, err := os.Create(os.Args[1])
	must(err)
	writer := gzip.NewWriter(file)
	must(json.NewEncoder(writer).Encode(corpus))
	must(writer.Close())
	must(file.Close())
	fmt.Printf("exported %d production Go HTTP scenes\n", len(corpus))
}

func scene(endpoint, mode string) any {
	dir, err := os.MkdirTemp("", "go-index-http-")
	must(err)
	defer os.RemoveAll(dir)
	db, err := gorm.Open(sqlite.Open(filepath.Join(dir, "case.db")), &gorm.Config{Logger: logger.Default.LogMode(logger.Silent)})
	must(err)
	sqldb, err := db.DB()
	must(err)
	defer sqldb.Close()
	sqldb.SetMaxOpenConns(1)
	for _, script := range []string{"000000_init.up.sql", "000007_system_admin_and_settings.up.sql", "000008_processing_spans_and_pending_subtasks.up.sql", "000002_knowledge_folder_path.up.sql", "000021_knowledge_profiles.up.sql", "000033_chunk_source_locators.up.sql"} {
		b, e := os.ReadFile("migrations/sqlite/" + script)
		must(e)
		execute(db, string(b))
	}
	execute(db, "INSERT INTO tenants(id,name,business,retriever_engines) VALUES(7,'Caller','test','[]'),(8,'Owner','test','[]')")
	execute(db, "INSERT INTO knowledge_bases(id,name,tenant_id,creator_id,type,embedding_model_id,summary_model_id,indexing_strategy,question_generation_config) VALUES('kb','KB',7,'caller','document','','',?,?)", []byte(`{"vector_enabled":true,"keyword_enabled":true,"wiki_enabled":true}`), []byte(`{"enabled":true}`))
	execute(db, "INSERT INTO knowledges(id,tenant_id,knowledge_base_id,type,title,source,parse_status,embedding_model_id,created_at,updated_at) VALUES('doc',7,'kb','file','Document','test','completed','embedding',?,?)", fixed, fixed)
	execute(db, "INSERT INTO models(id,tenant_id,name,display_name,type,source,status,parameters,created_at,updated_at) VALUES('embedding',7,'embed','Visible embedding','Embedding','remote','active','{}',?,?),('new-model',7,'new','New model','Embedding','remote','active','{}',?,?)", fixed, fixed, fixed, fixed)
	execute(db, "INSERT INTO chunks(id,tenant_id,knowledge_base_id,knowledge_id,content,source_content,context_header,content_revision,chunk_type,index_status,metadata,chunk_index,start_at,end_at,created_at,updated_at) VALUES('chunk',7,'kb','doc','Current body','Parser body','Heading',2,'text','ready',?,0,0,12,?,?)", `{"generated_questions":[{"id":"q","question":"Why?","content_revision":2}]}`, fixed, fixed)
	execute(db, "INSERT INTO chunks(id,tenant_id,knowledge_base_id,knowledge_id,content,parent_chunk_id,chunk_type,chunk_index,start_at,end_at,created_at,updated_at) VALUES('child-z',7,'kb','doc','Last first','chunk','image_ocr',9,0,8,?,?),('child-a',7,'kb','doc','First last','chunk','image_caption',1,0,8,?,?)", fixed, fixed, fixed, fixed)
	index := sqliteindex.NewSQLiteRetrieveEngineRepository(db)
	must(index.BatchSave(context.Background(), []*types.IndexInfo{{SourceID: "chunk", SourceType: 0, ChunkID: "chunk", KnowledgeID: "doc", KnowledgeBaseID: "kb", Content: "Actual index text", IsEnabled: true}, {SourceID: "chunk-q", SourceType: 0, ChunkID: "chunk", KnowledgeID: "doc", KnowledgeBaseID: "kb", Content: "Why?", IsEnabled: false}}, map[string]any{"embedding": map[string][]float32{"chunk": {1, 0}, "chunk-q": {0, 1}}}))
	execute(db, "UPDATE lite_embeddings SET created_at=?,updated_at=?", fixed, fixed)
	execute(db, "INSERT INTO knowledge_processing_spans(knowledge_id,attempt,span_id,name,kind,status,input,output,finished_at,created_at,updated_at) VALUES('doc',2,'embedding','embedding','stage','done',?,?,?, ?,?),('doc',2,'question','postprocess.question.batch[0]','subspan','done','{}','{}',?,?,?)", `{"vector_enabled":true,"keyword_enabled":true,"model_id":"embedding"}`, `{"vectors_written":1}`, fixed, fixed, fixed, fixed, fixed, fixed)
	wiki := func(id, source, chunk string, tenant int) {
		execute(db, "INSERT INTO wiki_pages(id,tenant_id,knowledge_base_id,slug,title,content,source_refs,chunk_refs,created_at,updated_at) VALUES(?,?,'kb',?,?,?, ?,?,?,?)", id, tenant, id, "Title "+id, "Page "+id, []byte(source), []byte(chunk), fixed, fixed)
	}
	wiki("cited", `["doc|Document"]`, `["chunk"]`, 7)
	wiki("summary", `["doc"]`, `[]`, 7)
	wiki("other-chunk", `["doc"]`, `["child-a"]`, 7)
	wiki("foreign", `["doc"]`, `["chunk"]`, 8)
	machine := strings.HasPrefix(mode, "api-key-")
	scope := types.TenantAPIKeyScope{KeyID: 1, Capabilities: types.StringArray{"retrieve"}, KnowledgeBaseIDs: types.StringArray{"kb"}}
	switch mode {
	case "wiki-text-refs":
		execute(db, "UPDATE wiki_pages SET source_refs=CAST(source_refs AS TEXT),chunk_refs=CAST(chunk_refs AS TEXT)")
	case "external-text-engine":
		execute(db, "UPDATE tenants SET retriever_engines=? WHERE id=7", `[{"retriever_type":"vector","retriever_engine_type":"qdrant"},{"retriever_type":"keywords","retriever_engine_type":"qdrant"}]`)
	case "config-text":
		execute(db, "UPDATE knowledge_bases SET indexing_strategy=?,question_generation_config=?", `{"vector_enabled":true,"keyword_enabled":true,"wiki_enabled":true}`, `{"enabled":true}`)
	case "empty":
		execute(db, "DELETE FROM chunks")
		execute(db, "DELETE FROM lite_embeddings")
		execute(db, "DELETE FROM lite_embeddings_fts")
		execute(db, "DELETE FROM vec_embeddings_2")
		execute(db, "DELETE FROM wiki_pages")
		execute(db, "DELETE FROM knowledge_processing_spans")
	case "children-empty":
		execute(db, "DELETE FROM chunks WHERE parent_chunk_id='chunk'")
	case "children-invalid-document":
		execute(db, "UPDATE chunks SET knowledge_id='another' WHERE id='child-z'")
	case "children-invalid-kb":
		execute(db, "UPDATE chunks SET knowledge_base_id='another' WHERE id='child-z'")
	case "children-foreign-tenant":
		execute(db, "UPDATE chunks SET tenant_id=8 WHERE id='child-z'")
	case "children-deleted":
		execute(db, "UPDATE chunks SET deleted_at=? WHERE id='child-z'", fixed)
	case "metadata-sql-null":
		execute(db, "UPDATE chunks SET metadata=NULL WHERE id='chunk'")
	case "metadata-empty":
		execute(db, "UPDATE chunks SET metadata='' WHERE id='chunk'")
	case "metadata-json-null":
		execute(db, "UPDATE chunks SET metadata='null' WHERE id='chunk'")
	case "metadata-question-null":
		execute(db, "UPDATE chunks SET metadata=? WHERE id='chunk'", `{"generated_questions":null}`)
	case "metadata-question-empty":
		execute(db, "UPDATE chunks SET metadata=? WHERE id='chunk'", `{"generated_questions":[]}`)
	case "metadata-casefold":
		execute(db, "UPDATE chunks SET metadata=? WHERE id='chunk'", `{"GENERATED_QUESTIONS":[{"ID":"q","QUESTION":"Why?","CONTENT_REVISION":2}]}`)
	case "metadata-duplicate-null":
		execute(db, "UPDATE chunks SET metadata=? WHERE id='chunk'", `{"generated_questions":[{"id":"q","id":null,"question":"Why?","question":null,"content_revision":2}]}`)
	case "metadata-duplicate-invalid":
		execute(db, "UPDATE chunks SET metadata=? WHERE id='chunk'", `{"generated_questions":[{"id":2,"id":"q","question":"Why?","content_revision":2}]}`)
	case "metadata-revision-duplicate-null":
		execute(db, "UPDATE chunks SET metadata=? WHERE id='chunk'", `{"generated_questions_revision":2,"generated_questions":[{"id":"q","question":"Why?","content_revision":1,"content_revision":null}]}`)
	case "metadata-scalar":
		execute(db, "UPDATE chunks SET metadata='4' WHERE id='chunk'")
	case "metadata-blob":
		execute(db, "UPDATE chunks SET metadata=? WHERE id='chunk'", []byte(`{"generated_questions":[{"id":"q","question":"Why?","content_revision":2}]}`))
	case "metadata-wrong-question-type":
		execute(db, "UPDATE chunks SET metadata=? WHERE id='chunk'", `{"generated_questions":{"q":"Why?"}}`)
	case "question-legacy":
		execute(db, "UPDATE chunks SET metadata=? WHERE id='chunk'", `{"generated_questions_revision":2,"generated_questions":[{"id":"q","question":"Why?"}]}`)
	case "question-stale":
		execute(db, "UPDATE chunks SET content_revision=3 WHERE id='chunk'")
	case "question-long-id":
		execute(db, "UPDATE chunks SET metadata=? WHERE id='chunk'", `{"generated_questions":[{"id":"`+strings.Repeat("问", 40)+`","question":"Why?","content_revision":2}]}`)
	case "question-missing-keyword":
		execute(db, "DELETE FROM lite_embeddings_fts WHERE rowid=(SELECT id FROM lite_embeddings WHERE source_id='chunk-q')")
	case "question-missing-vector":
		execute(db, "DELETE FROM vec_embeddings_2 WHERE rowid=(SELECT id FROM lite_embeddings WHERE source_id='chunk-q')")
	case "primary-missing":
		execute(db, "DELETE FROM vec_embeddings_2 WHERE rowid=(SELECT id FROM lite_embeddings WHERE source_id='chunk')")
	case "primary-disabled":
		execute(db, "UPDATE lite_embeddings SET is_enabled=0 WHERE source_id='chunk'")
	case "primary-null-enabled":
		execute(db, "UPDATE lite_embeddings SET is_enabled=NULL WHERE source_id='chunk'")
	case "chunk-processing":
		execute(db, "UPDATE chunks SET index_status='processing' WHERE id='chunk'")
	case "chunk-failed":
		execute(db, "UPDATE chunks SET index_status='failed' WHERE id='chunk'")
	case "parent-text":
		execute(db, "UPDATE chunks SET chunk_type='parent_text' WHERE id='chunk'")
	case "chunk-image":
		execute(db, "UPDATE chunks SET chunk_type='image_ocr' WHERE id='chunk'")
	case "chunk-not-found":
		execute(db, "DELETE FROM chunks WHERE id='chunk'")
	case "chunk-other-document":
		execute(db, "UPDATE chunks SET knowledge_id='other' WHERE id='chunk'")
	case "chunk-other-kb":
		execute(db, "UPDATE chunks SET knowledge_base_id='other' WHERE id='chunk'")
	case "chunk-other-tenant":
		execute(db, "UPDATE chunks SET tenant_id=8 WHERE id='chunk'")
	case "chunk-deleted":
		execute(db, "UPDATE chunks SET deleted_at=? WHERE id='chunk'", fixed)
	case "history-model-type":
		execute(db, "UPDATE knowledge_processing_spans SET input=? WHERE name='embedding'", `{"model_id":123,"vector_enabled":true,"keyword_enabled":true}`)
	case "history-counter-type":
		execute(db, "UPDATE knowledge_processing_spans SET output=? WHERE name='embedding'", `{"vectors_written":"20","llm_failed":"1","pages_dropped":true}`)
	case "wiki-unicode-id":
		execute(db, "UPDATE knowledges SET id='文档'")
		execute(db, "UPDATE chunks SET knowledge_id='文档'")
		execute(db, "UPDATE lite_embeddings SET knowledge_id='文档'")
		execute(db, "UPDATE knowledge_processing_spans SET knowledge_id='文档'")
		execute(db, "UPDATE wiki_pages SET source_refs=?", []byte(`["文档|Document"]`))
	case "history-missing":
		execute(db, "DELETE FROM knowledge_processing_spans")
	case "history-table-failure":
		execute(db, "DROP TABLE knowledge_processing_spans")
	case "history-malformed-map":
		execute(db, "UPDATE knowledge_processing_spans SET input='[]' WHERE name='embedding'")
	case "history-retry":
		execute(db, "INSERT INTO knowledge_processing_spans(knowledge_id,attempt,span_id,name,kind,status,input,output,finished_at,created_at,updated_at) VALUES('doc',2,'late','embedding','stage','failed','{\"model_id\":\"new-model\"}','{}',?,?,?)", fixed, fixed, "2026-10-13 01:02:03+00:00")
	case "history-attempt":
		execute(db, "INSERT INTO knowledge_processing_spans(knowledge_id,attempt,span_id,name,kind,status,input,output,created_at,updated_at) VALUES('doc',3,'root','root','root','done','{}','{}',?,?)", fixed, fixed)
	case "history-config-change":
		execute(db, "UPDATE knowledge_processing_spans SET input='{\"vector_enabled\":false,\"keyword_enabled\":false}' WHERE name='embedding'")
	case "model-missing":
		execute(db, "DELETE FROM models WHERE id='embedding'")
	case "model-inactive":
		execute(db, "UPDATE models SET status='downloading' WHERE id='embedding'")
	case "model-name-fallback":
		execute(db, "UPDATE models SET display_name='' WHERE id='embedding'")
	case "model-builtin":
		execute(db, "UPDATE models SET tenant_id=8,is_builtin=true WHERE id='embedding'")
	case "model-foreign":
		execute(db, "UPDATE models SET tenant_id=8 WHERE id='embedding'")
	case "tenant-missing":
		execute(db, "DELETE FROM tenants WHERE id=7")
	case "external-engine":
		execute(db, "UPDATE tenants SET retriever_engines=? WHERE id=7", []byte(`[{"retriever_type":"vector","retriever_engine_type":"qdrant"},{"retriever_type":"keywords","retriever_engine_type":"qdrant"}]`))
	case "wiki-empty":
		execute(db, "DELETE FROM wiki_pages")
	case "wiki-null-refs":
		execute(db, "UPDATE wiki_pages SET aliases=NULL,in_links=NULL,out_links=NULL,chunk_refs=NULL,source_refs=NULL,page_metadata=NULL WHERE id='cited'")
	case "wiki-invalid-refs":
		execute(db, "UPDATE wiki_pages SET aliases='4' WHERE id='cited'")
	case "wiki-table-failure":
		execute(db, "DROP TABLE wiki_pages")
	case "wiki-many":
		for i := 0; i < 60; i++ {
			wiki(fmt.Sprintf("page-%03d", i), `["doc"]`, `[]`, 7)
		}
	case "disabled-question":
		execute(db, "UPDATE knowledge_bases SET question_generation_config=? WHERE id='kb'", []byte(`{"enabled":false}`))
	case "disabled-vector":
		execute(db, "UPDATE knowledge_bases SET indexing_strategy=? WHERE id='kb'", []byte(`{"vector_enabled":false,"keyword_enabled":true,"wiki_enabled":true}`))
	case "graph-enabled":
		execute(db, "UPDATE knowledge_bases SET indexing_strategy=?,extract_config=? WHERE id='kb'", []byte(`{"vector_enabled":true,"keyword_enabled":true,"graph_enabled":true}`), []byte(`{"enabled":true}`))
	case "invalid-overrides":
		execute(db, "UPDATE knowledges SET metadata='{\"process_overrides\":{\"graph_enabled\":4}}' WHERE id='doc'")
	case "invalid-document-metadata":
		execute(db, "UPDATE knowledges SET metadata='4' WHERE id='doc'")
	case "document-missing":
		execute(db, "DELETE FROM knowledges WHERE id='doc'")
	case "document-owner-mismatch":
		execute(db, "UPDATE knowledges SET tenant_id=8 WHERE id='doc'")
	case "kb-missing":
		execute(db, "DELETE FROM knowledge_bases WHERE id='kb'")
	case "kb-table-failure":
		execute(db, "DROP TABLE knowledge_bases")
	case "chunks-table-failure":
		execute(db, "DROP TABLE chunks")
	case "api-key-full":
		scope.FullAccess = true
		scope.Capabilities = nil
		scope.KnowledgeBaseIDs = nil
	case "api-key-ingest":
		scope.Capabilities = types.StringArray{"ingest"}
	case "api-key-scope":
		scope.KnowledgeBaseIDs = types.StringArray{"other"}
	}
	shared := mode == "shared-viewer" || mode == "shared-revoked" || mode == "foreign-denied" || mode == "agent-share"
	if shared {
		execute(db, "UPDATE knowledge_bases SET tenant_id=8 WHERE id='kb'")
		execute(db, "UPDATE knowledges SET tenant_id=8 WHERE id='doc'")
		execute(db, "UPDATE chunks SET tenant_id=8")
		execute(db, "UPDATE models SET tenant_id=8")
		execute(db, "UPDATE wiki_pages SET tenant_id=8")
		execute(db, "UPDATE tenants SET retriever_engines=? WHERE id=7", `[{"retriever_type":"vector","retriever_engine_type":"qdrant"}]`)
		if mode != "foreign-denied" {
			execute(db, "INSERT INTO organizations(id,name,owner_tenant_id,owner_id,invite_code) VALUES('org','Org',8,'unused','code')")
			execute(db, "INSERT INTO organization_tenant_members(id,organization_id,tenant_id,role) VALUES('source','org',8,'viewer'),('target','org',7,'viewer')")
			if mode == "agent-share" {
				execute(db, "INSERT INTO custom_agents(id,tenant_id,name,config) VALUES('agent',8,'Agent','{\"kb_selection_mode\":\"all\"}')")
				execute(db, "INSERT INTO agent_shares(id,agent_id,organization_id,source_tenant_id,permission,shared_by_user_id) VALUES('share','agent','org',8,'viewer','unused')")
			} else {
				execute(db, "INSERT INTO kb_shares(id,knowledge_base_id,organization_id,source_tenant_id,permission,shared_by_user_id) VALUES('share','kb','org',8,'viewer','unused')")
			}
			if mode == "shared-revoked" {
				execute(db, "DELETE FROM organization_tenant_members WHERE id='source'")
			}
		}
	}
	kgRepo := repository.NewKnowledgeRepository(db)
	chunkRepo := repository.NewChunkRepository(db)
	kbRepo := repository.NewKnowledgeBaseRepository(db)
	tenantRepo := repository.NewTenantRepository(db)
	models := service.NewModelService(repository.NewModelRepository(db), kbRepo, nil, nil, nil, nil)
	audit := service.NewAuditLogService(repository.NewAuditLogRepository(db))
	shares := service.NewKBShareService(repository.NewKBShareRepository(db), repository.NewOrganizationRepository(db), nil, nil, nil, audit)
	agents := service.NewAgentShareService(repository.NewAgentShareRepository(db), repository.NewTenantDisabledSharedAgentRepository(db), repository.NewOrganizationRepository(db), repository.NewCustomAgentRepository(db), nil, nil, nil)
	registry := retriever.NewRetrieveEngineRegistry(nil, nil)
	must(registry.Register(retriever.NewKVHybridRetrieveEngine(index, types.SQLiteRetrieverEngineType)))
	kbs := service.NewKnowledgeBaseService(kbRepo, kgRepo, chunkRepo, nil, shares, models, registry, nil, tenantRepo, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil)
	enabled := true
	cfg := &config.Config{Tenant: &config.TenantConfig{EnableRBAC: &enabled}}
	knowledge, err := service.NewKnowledgeService(cfg, kgRepo, nil, kbs, tenantRepo, nil, nil, chunkRepo, nil, nil, nil, nil, nil, models, nil, nil, nil, registry, nil, nil, shares, nil, repository.NewWikiPageRepository(db), nil, nil, nil, nil)
	must(err)
	h := handler.NewKnowledgeHandler(cfg, knowledge, kbs, shares, agents, nil, nil, nil, nil, repository.NewKnowledgeSpanRepository(db), nil)
	template, path := "/api/v1/knowledge/:id/index-status", "/api/v1/knowledge/doc/index-status"
	handle := h.GetKnowledgeIndexStatus
	if endpoint == "detail" {
		template = "/api/v1/knowledge/:id/chunks/:chunk_id/details"
		path = "/api/v1/knowledge/doc/chunks/chunk/details"
		handle = h.GetChunkDetail
	}
	if mode == "wiki-unicode-id" {
		path = strings.ReplaceAll(path, "/doc/", "/文档/")
	}
	if mode == "query-ignored" {
		path += "?page=x&page_size=-1"
	}
	if mode == "agent-share" {
		path += "?agent_id=agent&agent_source_tenant_id=8"
	}
	gate := middleware.NewAPIKeyRouteAuthorizer()
	gate.Register("GET", template, middleware.APIKeyRoutePolicy{RequireFullAccess: true}.WithCapability(types.APIKeyCapabilityRetrieve))
	r := gin.New()
	r.Use(middleware.ErrorHandler(), middleware.AuditServiceProvider(audit))
	r.Use(func(c *gin.Context) {
		ctx := context.WithValue(c.Request.Context(), types.UserIDContextKey, "caller")
		ctx = context.WithValue(ctx, types.TenantIDContextKey, uint64(7))
		ctx = context.WithValue(ctx, types.TenantRoleContextKey, types.TenantRoleViewer)
		if machine {
			ctx = types.WithTenantAPIKeyScope(ctx, scope)
		}
		c.Request = c.Request.WithContext(ctx)
		c.Set(types.TenantIDContextKey.String(), uint64(7))
		c.Set(types.UserIDContextKey.String(), "caller")
		c.Next()
	})
	r.GET(template, gate.Middleware(), middleware.RequireRole(types.TenantRoleViewer, cfg), middleware.RequireKBAccess(middleware.KBIDFromKnowledgeIDParam("id", knowledge), types.OrgRoleViewer, kbs, shares, agents, cfg), handle)
	before := snapshot(sqldb)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, httptest.NewRequest("GET", path, nil))
	after := snapshot(sqldb)
	var body any
	must(json.Unmarshal(w.Body.Bytes(), &body))
	return map[string]any{"name": endpoint + "/" + mode, "endpoint": endpoint, "mode": mode, "path": path, "machine": machine, "full_access": scope.FullAccess, "capabilities": scope.Capabilities, "kb_scope": scope.KnowledgeBaseIDs, "seed": before, "after": after, "status": w.Code, "body": body}
}

func snapshot(db *sql.DB) any {
	schemas := []map[string]any{}
	rows, err := db.Query("SELECT name,type,sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%' ORDER BY CASE type WHEN 'table' THEN 0 ELSE 1 END,name")
	must(err)
	var tables []string
	for rows.Next() {
		var name, kind, query string
		must(rows.Scan(&name, &kind, &query))
		if strings.HasPrefix(name, "lite_embeddings_fts_") || strings.HasPrefix(name, "vec_embeddings_2_") {
			continue
		}
		schemas = append(schemas, map[string]any{"name": name, "type": kind, "sql": query})
		if kind == "table" {
			tables = append(tables, name)
		}
	}
	must(rows.Close())
	data := map[string]any{}
	for _, table := range tables {
		query := "SELECT * FROM \"" + strings.ReplaceAll(table, "\"", "\"\"") + "\""
		if table == "vec_embeddings_2" {
			query = "SELECT rowid,embedding FROM vec_embeddings_2"
		}
		if table == "lite_embeddings_fts" {
			query = "SELECT rowid FROM lite_embeddings_fts"
		}
		results, e := db.Query(query)
		must(e)
		columns, e := results.Columns()
		must(e)
		var entries []any
		for results.Next() {
			values := make([]any, len(columns))
			pointers := make([]any, len(columns))
			for i := range values {
				pointers[i] = &values[i]
			}
			must(results.Scan(pointers...))
			entry := map[string]any{}
			for i, key := range columns {
				value := values[i]
				if b, ok := value.([]byte); ok {
					value = map[string]any{"$blob": base64.StdEncoding.EncodeToString(b)}
				}
				if stamp, ok := value.(time.Time); ok {
					value = stamp.Format(time.RFC3339Nano)
				}
				entry[key] = value
			}
			entries = append(entries, entry)
		}
		must(results.Close())
		if entries == nil {
			entries = []any{}
		}
		data[table] = entries
	}
	return map[string]any{"schema": schemas, "tables": data}
}
