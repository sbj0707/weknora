//go:build ignore

// Executes the production processing-span handlers/services/repositories and exports each
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
	time.Local = time.UTC
	vec.Auto()
	log.SetOutput(io.Discard)
	gin.SetMode(gin.TestMode)
	must(types.LoadBuiltinAgentsConfig("config"))
	must(os.Setenv("RETRIEVE_DRIVER", "sqlite"))
	modes := []string{"normal", "tracker-done", "tracker-failure", "tracker-reentry", "tracker-supersede", "tracker-abort", "legacy-empty", "legacy-completed", "legacy-failed", "legacy-cancelled", "legacy-pending", "legacy-processing", "legacy-finalizing", "legacy-unknown", "rootless", "orphan", "order", "multiple-root", "stage-reentry", "running-stage", "running-subspan", "running-orphan", "stall-failure", "multiple-failures", "failure-no-finished", "knowledge-recovery", "knowledge-restart", "knowledge-restart-unicode-space", "history-before-failure", "missing-after-docreader", "missing-after-embedding", "missing-after-multimodal", "missing-after-postprocess", "metadata-null", "metadata-empty", "metadata-json-null", "metadata-blob", "metadata-array", "input-invalid", "output-invalid", "error-detail-secret", "times-null", "attempt-one", "attempt-two", "attempt-missing", "attempt-zero", "attempt-negative", "attempt-whitespace", "attempt-invalid", "attempt-overflow", "attempt-large", "attempt-duplicate", "table-failure", "document-missing", "document-owner-mismatch", "kb-missing", "kb-table-failure", "api-key-retrieve", "api-key-ingest", "api-key-full", "api-key-scope", "shared-viewer", "shared-revoked", "foreign-denied", "agent-share"}
	var corpus []any
	for _, endpoint := range []string{"spans", "stages"} {
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
	for i, name := range []string{"knowledge_processing", "docreader", "chunking", "embedding", "multimodal", "postprocess"} {
		kind, parent := "stage", "root"
		if i == 0 {
			kind = "root"
			parent = ""
		}
		id := fmt.Sprintf("s%d", i)
		if i == 0 {
			id = "root"
		}
		execute(db, "INSERT INTO knowledge_processing_spans(knowledge_id,attempt,span_id,parent_span_id,name,kind,status,input,output,metadata,started_at,finished_at,duration_ms,created_at,updated_at) VALUES('doc',2,?,?,?,?, 'done',?,?,?, ?,?,5000,?,?)", id, parent, name, kind, []byte(`{"source":"file"}`), []byte(`{"count":1}`), []byte(`{"langfuse_trace_id":"trace"}`), fixed, fixed, fixed, fixed)
	}
	execute(db, "INSERT INTO knowledge_processing_spans(knowledge_id,attempt,span_id,parent_span_id,name,kind,status,created_at,updated_at) VALUES('doc',1,'old-root','','knowledge_processing','root','done',?,?)", fixed, fixed)

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
	case "tracker-done", "tracker-failure", "tracker-reentry", "tracker-supersede", "tracker-abort":
		tracker := service.NewSpanTracker(repository.NewKnowledgeSpanRepository(db), db)
		ctx := context.Background()
		_, attempt, err := tracker.OpenAttempt(ctx, "doc", "actual-trace")
		must(err)
		stage := tracker.BeginStage(ctx, "doc", attempt, types.StageEmbedding, types.JSONMap{"model_id": "embedding", "vector_enabled": true, "keyword_enabled": true})
		sub := tracker.BeginSubSpan(ctx, stage, "embedding.batch[0]", types.SpanKindSubSpan, types.JSONMap{"count": 2})
		switch mode {
		case "tracker-failure":
			tracker.FailSpan(ctx, stage, "MODEL_UNREACHABLE", "native failure", fmt.Errorf("RAW_PRIVATE_STACK"))
		case "tracker-reentry":
			tracker.EndSpan(ctx, stage, types.JSONMap{"vectors_written": 2})
			tracker.BeginStage(ctx, "doc", attempt, types.StageEmbedding, types.JSONMap{"retry": true})
		case "tracker-supersede":
			tracker.BeginSubSpan(ctx, stage, "embedding.batch[0]", types.SpanKindSubSpan, nil)
		case "tracker-abort":
			tracker.AbortAttempt(ctx, "doc", attempt, "USER_CANCELLED", "cancelled", "user cancelled")
		default:
			tracker.EndSpan(ctx, sub, types.JSONMap{"written": 2})
			tracker.EndSpan(ctx, stage, types.JSONMap{"vectors_written": 2})
			tracker.FinalizeAttempt(ctx, "doc", attempt, types.SpanStatusDone, types.JSONMap{"chunks": 2}, "", "")
		}
	case "legacy-empty", "legacy-completed", "legacy-failed", "legacy-cancelled", "legacy-pending", "legacy-processing", "legacy-finalizing", "legacy-unknown":
		execute(db, "DELETE FROM knowledge_processing_spans")
		status := strings.TrimPrefix(mode, "legacy-")
		if status == "empty" {
			status = ""
		}
		execute(db, "UPDATE knowledges SET parse_status=?", status)
	case "rootless":
		execute(db, "DELETE FROM knowledge_processing_spans WHERE span_id='root'")
	case "orphan":
		execute(db, "UPDATE knowledge_processing_spans SET parent_span_id='missing' WHERE name='embedding'")
	case "order":
		execute(db, "INSERT INTO knowledge_processing_spans(knowledge_id,attempt,span_id,parent_span_id,name,kind,status,created_at,updated_at) VALUES('doc',2,'late-z','s4','multimodal.image[9]','generation','done',?,?),('doc',2,'late-a','s4','multimodal.image[1]','generation','done',?,?)", fixed, fixed, fixed, fixed)
	case "multiple-root":
		execute(db, "INSERT INTO knowledge_processing_spans(knowledge_id,attempt,span_id,name,kind,status,created_at,updated_at) VALUES('doc',2,'second-root','other_root','root','done',?,?)", fixed, fixed)
	case "stage-reentry":
		execute(db, "INSERT INTO knowledge_processing_spans(knowledge_id,attempt,span_id,parent_span_id,name,kind,status,created_at,updated_at) VALUES('doc',2,'retry','root','embedding','stage','running',?,?)", fixed, fixed)
	case "running-stage":
		execute(db, "UPDATE knowledge_processing_spans SET status='running',finished_at=NULL WHERE name IN ('chunking','embedding')")
	case "running-subspan", "running-orphan":
		parent := "s5"
		if mode == "running-orphan" {
			parent = "missing"
		}
		execute(db, "INSERT INTO knowledge_processing_spans(knowledge_id,attempt,span_id,parent_span_id,name,kind,status,created_at,updated_at) VALUES('doc',2,'sub',?,'postprocess.wiki','subspan','running',?,?)", parent, fixed, fixed)
		execute(db, "UPDATE knowledges SET parse_status='finalizing'")
	case "stall-failure", "multiple-failures", "failure-no-finished":
		code := "MODEL_FAILED"
		if mode == "stall-failure" {
			code = "TASK_STALLED"
		}
		execute(db, "UPDATE knowledge_processing_spans SET status='failed',error_code=?,error_message='First failure' WHERE name='multimodal'", code)
		if mode == "failure-no-finished" {
			execute(db, "UPDATE knowledge_processing_spans SET finished_at=NULL WHERE name='multimodal'")
		} else {
			execute(db, "UPDATE knowledge_processing_spans SET status='failed',error_code='LATER_FAILED',error_message='Later failure' WHERE name='postprocess'")
		}
	case "knowledge-recovery", "knowledge-restart", "knowledge-restart-unicode-space", "history-before-failure":
		message := "recovery failed"
		if strings.HasPrefix(mode, "knowledge-restart") {
			message = "Task interrupted due to application restart"
		}
		if mode == "knowledge-restart-unicode-space" {
			message = "\u00a0TASK INTERRUPTED DUE TO APPLICATION RESTART\u3000"
		}
		execute(db, "UPDATE knowledges SET parse_status='failed',error_message=?", message)
	case "missing-after-docreader", "missing-after-embedding", "missing-after-multimodal", "missing-after-postprocess":
		stage := strings.TrimPrefix(mode, "missing-after-")
		execute(db, "DELETE FROM knowledge_processing_spans WHERE attempt=2 AND name<>? AND kind<>'root'", stage)
		execute(db, "UPDATE knowledge_processing_spans SET status='failed',error_code='ACTUAL_FAILURE' WHERE name=?", stage)
		execute(db, "UPDATE knowledges SET parse_status='failed'")
	case "metadata-null":
		execute(db, "UPDATE knowledge_processing_spans SET metadata=NULL")
	case "metadata-empty":
		execute(db, "UPDATE knowledge_processing_spans SET metadata='' ")
	case "metadata-json-null":
		execute(db, "UPDATE knowledge_processing_spans SET metadata='null'")
	case "metadata-blob":
		execute(db, "UPDATE knowledge_processing_spans SET metadata=?", []byte(`{"custom":{"nested":true}}`))
	case "metadata-array":
		execute(db, "UPDATE knowledge_processing_spans SET metadata='[]'")
	case "input-invalid":
		execute(db, "UPDATE knowledge_processing_spans SET input='4'")
	case "output-invalid":
		execute(db, "UPDATE knowledge_processing_spans SET output='{' ")
	case "error-detail-secret":
		execute(db, "UPDATE knowledge_processing_spans SET error_detail='NEVER_PUBLIC_RAW_STACK_SECRET' ")
	case "times-null":
		execute(db, "UPDATE knowledge_processing_spans SET started_at=NULL,finished_at=NULL,duration_ms=NULL,error_code=NULL,error_message=NULL,parent_span_id=NULL,input=NULL,output=NULL,metadata=NULL")
	case "table-failure":
		execute(db, "DROP TABLE knowledge_processing_spans")
	case "document-missing":
		execute(db, "DELETE FROM knowledges WHERE id='doc'")
	case "document-owner-mismatch":
		execute(db, "UPDATE knowledges SET tenant_id=8 WHERE id='doc'")
	case "kb-missing":
		execute(db, "DELETE FROM knowledge_bases WHERE id='kb'")
	case "kb-table-failure":
		execute(db, "DROP TABLE knowledge_bases")
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
	template, path := "/api/v1/knowledge/:id/"+endpoint, "/api/v1/knowledge/doc/"+endpoint
	handle := h.GetKnowledgeSpans
	queries := map[string]string{"attempt-one": "1", "history-before-failure": "1", "attempt-two": "2", "attempt-missing": "3", "attempt-zero": "0", "attempt-negative": "-1", "attempt-whitespace": "%C2%A0%2B2%E3%80%80", "attempt-invalid": "1x", "attempt-overflow": "9223372036854775808", "attempt-large": "2147483648", "attempt-duplicate": "1&attempt=2"}
	if value, ok := queries[mode]; ok {
		path += "?attempt=" + value
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
	started := time.Now()
	r.ServeHTTP(w, httptest.NewRequest("GET", path, nil))
	after := snapshot(sqldb)
	var body any
	must(json.Unmarshal(w.Body.Bytes(), &body))
	clock := started.Format(time.RFC3339Nano)
	var syntheticClock func(any)
	syntheticClock = func(value any) {
		object, ok := value.(map[string]any)
		if !ok {
			return
		}
		if id, ok := object["span_id"].(string); ok && id == "" {
			if stamp, ok := object["created_at"].(string); ok {
				clock = stamp
			}
		}
		if children, ok := object["children"].([]any); ok {
			for _, child := range children {
				syntheticClock(child)
			}
		}
	}
	if object, ok := body.(map[string]any); ok {
		if data, ok := object["data"].(map[string]any); ok {
			syntheticClock(data["trace"])
		}
	}
	return map[string]any{"name": endpoint + "/" + mode, "endpoint": endpoint, "mode": mode, "path": path, "clock": clock, "machine": machine, "full_access": scope.FullAccess, "capabilities": scope.Capabilities, "kb_scope": scope.KnowledgeBaseIDs, "seed": before, "after": after, "status": w.Code, "body": body}

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
