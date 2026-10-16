//go:build ignore

// Actual production Go summary regeneration HTTP, permission, model, queue and physical-index oracle.
package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	repo "github.com/Tencent/WeKnora/internal/application/repository"
	pgindex "github.com/Tencent/WeKnora/internal/application/repository/retriever/postgres"
	sr "github.com/Tencent/WeKnora/internal/application/repository/retriever/sqlite"
	svc "github.com/Tencent/WeKnora/internal/application/service"
	retrieve "github.com/Tencent/WeKnora/internal/application/service/retriever"
	"github.com/Tencent/WeKnora/internal/config"
	"github.com/Tencent/WeKnora/internal/handler"
	"github.com/Tencent/WeKnora/internal/logger"
	"github.com/Tencent/WeKnora/internal/middleware"
	"github.com/Tencent/WeKnora/internal/models/embedding"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"github.com/Tencent/WeKnora/internal/utils"
	"github.com/alicebob/miniredis/v2"
	sqlitevec "github.com/asg017/sqlite-vec-go-bindings/cgo"
	"github.com/gin-gonic/gin"
	"github.com/hibiken/asynq"
	"github.com/panjf2000/ants/v2"
	"gorm.io/driver/postgres"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
	gl "gorm.io/gorm/logger"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}

type capture struct {
	client *asynq.Client
	fail   bool
	rows   []any
}

func (c *capture) Enqueue(t *asynq.Task, opts ...asynq.Option) (*asynq.TaskInfo, error) {
	if c.fail {
		return nil, errors.New("controlled enqueue failure")
	}
	info, e := c.client.Enqueue(t, opts...)
	if e != nil {
		return nil, e
	}
	var p any
	must(json.Unmarshal(t.Payload(), &p))
	c.rows = append(c.rows, map[string]any{"type": t.Type(), "payload": p, "queue": info.Queue, "max_retry": info.MaxRetry, "timeout": info.Timeout / time.Millisecond})
	return info, nil
}

type scene struct{ name, operation, variant, target string }

func main() {
	time.Local = time.UTC
	logger.SetOutput(os.Stderr)
	gin.SetMode(gin.TestMode)
	sqlitevec.Auto()
	if os.Getenv("WEKNORA_SUMMARY_PUBLIC_PARITY_PG_DSN") != "" {
		must(os.Setenv("RETRIEVE_DRIVER", "postgres"))
	} else {
		must(os.Setenv("RETRIEVE_DRIVER", "sqlite"))
	}
	must(os.Setenv("SSRF_WHITELIST", "127.0.0.1"))
	must(os.Setenv("SYSTEM_AES_KEY", ""))
	utils.ResetSSRFWhitelistForTest()
	out := []any{}
	for _, variant := range []string{"ok", "missing-kb", "deleted-kb", "creator-viewer", "creator-contributor", "noncreator-contributor", "noncreator-viewer", "noncreator-admin", "shared-editor", "shared-viewer", "shared-revoked", "api-ingest", "api-retrieve", "api-full", "api-wrong-kb", "api-full-wrong-kb", "unknown-role", "moving", "foreign-document", "deleted-document", "wrong-kb-doc", "summary-no-model", "queue-error", "summary-pending", "summary-processing", "summary-failed", "parse-cancelled", "parse-deleting", "initial-plain", "initial-empty-status", "initial-profile", "initial-fenced", "initial-gist-only", "initial-json-empty", "initial-json-mistyped", "initial-json-null", "initial-chat-empty", "initial-chat-error", "initial-model-missing", "initial-no-model", "initial-no-text", "initial-short", "initial-disabled", "initial-existing", "initial-duplicates", "initial-chunk-error", "initial-index-error", "initial-embedding-error", "initial-revision-changed", "initial-metadata-changed", "initial-cancel-during-chat", "initial-custom-metadata", "initial-parse-cancelled", "initial-parse-deleting", "initial-edited", "initial-overlap"} {
		out = append(out, run(scene{"summary-" + variant, "summary", variant, ""}))
	}
	b, e := json.Marshal(out)
	must(e)
	must(os.WriteFile(os.Args[1], append(b, '\n'), 0600))
	fmt.Println("exported", len(out), "actual Go summary HTTP scenes")
}
func run(s scene) any {
	dir, e := os.MkdirTemp("", "weknora-go-update-")
	must(e)
	defer os.RemoveAll(dir)
	var dialect gorm.Dialector = sqlite.Open(filepath.Join(dir, "case.db"))
	pgDSN := os.Getenv("WEKNORA_SUMMARY_PUBLIC_PARITY_PG_DSN")
	if pgDSN != "" {
		u, e := url.Parse(pgDSN)
		must(e)
		if u.Hostname() != "127.0.0.1" || u.Path != "/weknora_summary_public_parity" {
			panic("Owned loopback folder parity database required")
		}
		dialect = postgres.Open(pgDSN)
	}
	db, e := gorm.Open(dialect, &gorm.Config{Logger: gl.Default.LogMode(gl.Silent)})
	must(e)
	sql, e := db.DB()
	must(e)
	defer sql.Close()
	sql.SetMaxOpenConns(1)
	if pgDSN == "" {
		for _, m := range []string{"000000_init", "000002_knowledge_folder_path", "000003_knowledge_base_auto_tag_config", "000004_memory", "000007_system_admin_and_settings", "000008_processing_spans_and_pending_subtasks", "000010_knowledge_multi_tags", "000011_principal_model", "000021_knowledge_profiles", "000031_model_catalog_config", "000033_chunk_source_locators"} {
			b, e := os.ReadFile("migrations/sqlite/" + m + ".up.sql")
			must(e)
			must(summaryExec(db, string(b)).Error)
		}
	} else {
		must(summaryExec(db, "DROP TRIGGER IF EXISTS fail_chunk ON chunks").Error)
		must(summaryExec(db, "DROP TRIGGER IF EXISTS fail_index ON embeddings").Error)
		must(summaryExec(db, "TRUNCATE tenants,knowledge_bases,knowledges,chunks,models,embeddings,organizations,organization_tenant_members,kb_shares,audit_logs RESTART IDENTITY CASCADE").Error)
		must(summaryExec(db, "ALTER SEQUENCE chunks_seq_id_seq RESTART WITH 100000000").Error)
	}
	must(summaryExec(db, "INSERT INTO tenants(id,name,business) VALUES(1,'Owner','test'),(2,'Caller','test')").Error)
	must(summaryExec(db, "INSERT INTO knowledge_bases(id,tenant_id,creator_id,name,type,embedding_model_id,summary_model_id) VALUES('kb',1,'caller','KB','document','',''),('other-kb',1,'other','Other','document','','')").Error)
	stamp := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)

	paths := []string{"", "A", "A/spec", "A/spec/deep", "B", "a%_/sub", `C\windows`, "中文/说明", "é", "é", "Z", "../A./spec ", "A/./spec", "aX/sub"}
	stamp = time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	for i, path := range paths {
		must(summaryExec(db, "INSERT INTO knowledges(id,tenant_id,knowledge_base_id,type,title,source,parse_status,folder_path,created_at,updated_at) VALUES(?,1,'kb','file','Document','manual','completed',?,?,?)", fmt.Sprintf("d%d", i), path, stamp, stamp).Error)
	}
	must(summaryExec(db, "INSERT INTO knowledges(id,tenant_id,knowledge_base_id,type,title,source,parse_status,folder_path,created_at,updated_at) VALUES('foreign',2,'kb','file','Foreign','manual','completed','A',?,?),('other',1,'other-kb','file','Other','manual','completed','A',?,?),('deleting',1,'kb','file','Deleting','manual','deleting','A',?,?),('deleted',1,'kb','file','Deleted','manual','completed','A',?,?)", stamp, stamp, stamp, stamp, stamp, stamp, stamp, stamp).Error)
	must(summaryExec(db, "UPDATE knowledges SET deleted_at=CURRENT_TIMESTAMP WHERE id='deleted'").Error)

	must(summaryExec(db, "UPDATE knowledges SET title='Old title',description='Old description',summary_status='completed',custom_metadata=? WHERE id='d1'", `{"old":"value"}`).Error)
	must(summaryExec(db, "UPDATE knowledge_bases SET summary_model_id='summary' WHERE id='kb'").Error)
	switch s.variant {
	case "missing-kb":
		must(summaryExec(db, "DELETE FROM knowledge_bases WHERE id='kb'").Error)
	case "deleted-kb":
		must(summaryExec(db, "UPDATE knowledge_bases SET deleted_at=CURRENT_TIMESTAMP WHERE id='kb'").Error)
	case "foreign-document":
		must(summaryExec(db, "UPDATE knowledges SET tenant_id=2 WHERE id='d1'").Error)
	case "deleted-document":
		must(summaryExec(db, "UPDATE knowledges SET deleted_at=CURRENT_TIMESTAMP WHERE id='d1'").Error)
	case "wrong-kb-doc":
		must(summaryExec(db, "UPDATE knowledges SET knowledge_base_id='other-kb' WHERE id='d1'").Error)
	case "summary-no-model":
		must(summaryExec(db, "UPDATE knowledge_bases SET summary_model_id='' WHERE id='kb'").Error)
	case "summary-pending":
		must(summaryExec(db, "UPDATE knowledges SET summary_status='pending' WHERE id='d1'").Error)
	case "summary-processing":
		must(summaryExec(db, "UPDATE knowledges SET summary_status='processing' WHERE id='d1'").Error)
	case "summary-failed":
		must(summaryExec(db, "UPDATE knowledges SET summary_status='failed' WHERE id='d1'").Error)
	case "parse-cancelled", "parse-deleting":
		must(summaryExec(db, "UPDATE knowledges SET parse_status=? WHERE id='d1'", strings.TrimPrefix(s.variant, "parse-")).Error)
	case "moving":
		must(summaryExec(db, "UPDATE knowledges SET metadata=? WHERE id='d1'", []byte(`{"_knowledge_transfer":{"operation":"move","phase":"moving"}}`)).Error)
	}
	initial := strings.HasPrefix(s.variant, "initial-")
	name := strings.TrimPrefix(s.variant, "initial-")
	if initial {
		must(summaryExec(db, "UPDATE knowledges SET summary_status='none' WHERE id='d1'").Error)
	}
	if name == "empty-status" {
		must(summaryExec(db, "UPDATE knowledges SET summary_status='' WHERE id='d1'").Error)
	}
	if initial && name == "no-model" {
		must(summaryExec(db, "UPDATE knowledge_bases SET summary_model_id='' WHERE id='kb'").Error)
	}
	if name == "model-missing" {
		must(summaryExec(db, "UPDATE knowledge_bases SET summary_model_id='missing' WHERE id='kb'").Error)
	}
	if initial && strings.HasPrefix(name, "parse-") {
		must(summaryExec(db, "UPDATE knowledges SET parse_status=? WHERE id='d1'", strings.TrimPrefix(name, "parse-")).Error)
	}
	content := "A production document explains onboarding, access controls and delivery checkpoints."
	if name == "short" {
		content = "短😀"
	}
	must(summaryExec(db, "INSERT INTO chunks(id,tenant_id,knowledge_id,knowledge_base_id,content,chunk_index,start_at,end_at,is_enabled,chunk_type,status) VALUES('physical',1,'d1','kb',?,0,0,?,true,'text',0)", content, len([]rune(content))).Error)
	if name == "no-text" {
		must(summaryExec(db, "UPDATE chunks SET deleted_at=CURRENT_TIMESTAMP WHERE id='physical'").Error)
	}
	if name == "disabled" {
		must(summaryExec(db, "UPDATE chunks SET is_enabled=false WHERE id='physical'").Error)
	}
	if name == "existing" || name == "duplicates" {
		must(summaryExec(db, "INSERT INTO chunks(id,tenant_id,knowledge_id,knowledge_base_id,content,source_content,chunk_index,start_at,end_at,is_enabled,chunk_type,status,parent_chunk_id) VALUES('summary-existing',1,'d1','kb','Old summary','Old original',2,0,0,false,'summary',0,'physical')").Error)
	}
	if name == "duplicates" {
		must(summaryExec(db, "INSERT INTO chunks(id,tenant_id,knowledge_id,knowledge_base_id,content,chunk_index,start_at,end_at,is_enabled,chunk_type,status,parent_chunk_id) VALUES('summary-duplicate',1,'d1','kb','Old duplicate',3,0,0,true,'summary',0,'physical')").Error)
	}
	if name == "edited" {
		must(summaryExec(db, "UPDATE chunks SET content_revision=1 WHERE id='physical'").Error)
		must(summaryExec(db, "INSERT INTO chunks(id,tenant_id,knowledge_id,knowledge_base_id,content,chunk_index,start_at,end_at,is_enabled,chunk_type,status) VALUES('physical-2',1,'d1','kb','Edited replacement 😀 delivered last.',1,0,20,true,'text',0)").Error)
	}
	if name == "overlap" {
		must(summaryExec(db, "UPDATE chunks SET content='Alpha beta gamma delta epsilon ',end_at=31 WHERE id='physical'").Error)
		must(summaryExec(db, "INSERT INTO chunks(id,tenant_id,knowledge_id,knowledge_base_id,content,chunk_index,start_at,end_at,is_enabled,chunk_type,status) VALUES('physical-2',1,'d1','kb','gamma delta epsilon zeta eta final.',1,11,50,true,'text',0)").Error)
	}
	if name == "custom-metadata" {
		must(summaryExec(db, "UPDATE knowledges SET custom_metadata=? WHERE id='d1'", `{"z":null,"a":" approved ","bool":true}`).Error)
	}
	requests := []any{}
	var mu sync.Mutex
	reply := "  A short objective summary 中文😀.  "
	switch name {
	case "profile", "fenced":
		reply = `{"summary":" Profile summary 中文 ","gist":" A headline ","topics":[" Kubernetes ","kubernetes.","Delivery"],"doc_type":" policy ","typical_question":" How to onboard? "}`
		if name == "fenced" {
			reply = "```json\n" + reply + "\n```"
		}
	case "gist-only":
		reply = `{"gist":" Headline only ","topics":["topic"]}`
	case "json-empty":
		reply = `{"topics":["topic"]}`
	case "json-mistyped":
		reply = `{"summary":12}`
	case "json-null":
		reply = "null"
	case "chat-empty":
		reply = " \n\t"
	}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		must(json.NewDecoder(r.Body).Decode(&body))
		mu.Lock()
		requests = append(requests, map[string]any{"path": r.URL.Path, "body": body})
		mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		if r.URL.Path == "/v1/chat/completions" {
			if name == "chat-error" {
				w.WriteHeader(503)
				fmt.Fprint(w, `{"error":{"message":"synthetic provider unavailable"}}`)
				return
			}
			if name == "revision-changed" {
				must(summaryExec(db, "UPDATE chunks SET content_revision=1 WHERE id='physical'").Error)
			}
			if name == "metadata-changed" {
				must(summaryExec(db, `UPDATE knowledges SET custom_metadata='{"new":"metadata"}' WHERE id='d1'`).Error)
			}
			if name == "cancel-during-chat" {
				must(summaryExec(db, "UPDATE knowledges SET parse_status='cancelled' WHERE id='d1'").Error)
			}
			must(json.NewEncoder(w).Encode(map[string]any{"choices": []any{map[string]any{"message": map[string]any{"role": "assistant", "content": reply}, "finish_reason": "stop"}}}))
			return
		}
		if name == "embedding-error" {
			w.WriteHeader(400)
			fmt.Fprint(w, `{"error":{"message":"synthetic embedding unavailable"}}`)
			return
		}
		rows := []any{}
		for i := range body["input"].([]any) {
			rows = append(rows, map[string]any{"index": i, "embedding": []float32{1, 0, 0}})
		}
		must(json.NewEncoder(w).Encode(map[string]any{"data": rows}))
	}))
	defer server.Close()
	mr := repo.NewModelRepository(db)
	for _, m := range []*types.Model{{ID: "summary", TenantID: 1, Name: "unknown", Type: types.ModelTypeKnowledgeQA, Source: types.ModelSourceRemote, Status: types.ModelStatusActive, Parameters: types.ModelParameters{BaseURL: server.URL + "/v1", APIKey: "synthetic-key", Provider: "generic"}}, {ID: "embedding", TenantID: 1, Name: "unknown", Type: types.ModelTypeEmbedding, Source: types.ModelSourceRemote, Status: types.ModelStatusActive, Parameters: types.ModelParameters{BaseURL: server.URL + "/v1", APIKey: "synthetic-key", Provider: "generic", EmbeddingParameters: types.EmbeddingParameters{Dimension: 3}}}} {
		must(mr.Create(context.Background(), m))
	}
	pool, e := ants.NewPool(2)
	must(e)
	defer pool.Release()
	models := svc.NewModelService(mr, nil, nil, nil, embedding.NewBatchEmbedder(pool), nil)
	registry := retrieve.NewRetrieveEngineRegistry(nil, nil)
	var index interfaces.RetrieveEngineRepository
	engineType := types.SQLiteRetrieverEngineType
	if pgDSN != "" {
		index = pgindex.NewPostgresRetrieveEngineRepository(db)
		engineType = types.PostgresRetrieverEngineType
	} else {
		index = sr.NewSQLiteRetrieveEngineRepository(db)
	}
	must(registry.Register(retrieve.NewKVHybridRetrieveEngine(index, engineType)))
	must(summaryExec(db, "UPDATE knowledge_bases SET embedding_model_id='embedding' WHERE id='kb'").Error)
	if name == "chunk-error" {
		must(summaryExec(db, "CREATE TRIGGER fail_chunk BEFORE INSERT ON chunks BEGIN SELECT RAISE(FAIL,'synthetic chunk failure');END").Error)
	}
	if name == "index-error" {
		must(summaryExec(db, "CREATE TRIGGER fail_index BEFORE INSERT ON lite_embeddings BEGIN SELECT RAISE(FAIL,'synthetic index failure');END").Error)
	}
	redis, e := miniredis.Run()
	must(e)
	defer redis.Close()
	client := asynq.NewClient(asynq.RedisClientOpt{Addr: redis.Addr()})
	defer client.Close()
	captured := &capture{client: client, rows: []any{}, fail: s.variant == "queue-error"}
	audit := svc.NewAuditLogService(repo.NewAuditLogRepository(db))
	shares := svc.NewKBShareService(repo.NewKBShareRepository(db), repo.NewOrganizationRepository(db), nil, nil, nil, audit)
	agents := svc.NewAgentShareService(repo.NewAgentShareRepository(db), repo.NewTenantDisabledSharedAgentRepository(db), repo.NewOrganizationRepository(db), repo.NewCustomAgentRepository(db), nil, nil, nil)
	br, kr, cr := repo.NewKnowledgeBaseRepository(db), repo.NewKnowledgeRepository(db), repo.NewChunkRepository(db)
	kbs := svc.NewKnowledgeBaseService(br, kr, cr, nil, shares, nil, nil, nil, repo.NewTenantRepository(db), nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil)
	kg, e := svc.NewKnowledgeService(&config.Config{Conversation: &config.ConversationConfig{GenerateSummaryPrompt: "PROFILE {{language}}", Summary: &config.SummaryConfig{MaxInputChars: 8192, MaxCompletionTokens: 1024}}}, kr, nil, kbs, repo.NewTenantRepository(db), nil, nil, cr, nil, nil, nil, nil, nil, models, captured, nil, nil, registry, nil, nil, shares, nil, nil, nil, nil, nil, audit)
	must(e)
	enabled := true
	cfg := &config.Config{Tenant: &config.TenantConfig{EnableRBAC: &enabled}}
	h := handler.NewKnowledgeHandler(cfg, kg, kbs, shares, agents, nil, nil, nil, captured, nil, nil)
	role, user, tenant := types.TenantRoleAdmin, "caller", uint64(1)
	if strings.HasSuffix(s.variant, "viewer") {
		role = types.TenantRoleViewer
	}
	if strings.HasSuffix(s.variant, "contributor") {
		role = types.TenantRoleContributor
	}
	if strings.HasPrefix(s.variant, "noncreator-") {
		user = "intruder"
	}
	if s.variant == "unknown-role" {
		role = types.TenantRole("unknown")
		user = "intruder"
	}
	if strings.HasPrefix(s.variant, "shared-") {
		tenant = 2
		permission := "editor"
		if s.variant == "shared-viewer" {
			permission = "viewer"
		}
		must(summaryExec(db, "INSERT INTO organizations(id,name,owner_tenant_id,owner_id) VALUES('org','Org',1,'caller')").Error)
		must(summaryExec(db, "INSERT INTO organization_tenant_members(id,organization_id,tenant_id,role) VALUES('source','org',1,'admin'),('member','org',2,'editor')").Error)
		must(summaryExec(db, "INSERT INTO kb_shares(id,knowledge_base_id,organization_id,source_tenant_id,permission,shared_by_user_id) VALUES('share','kb','org',1,?,'caller')", permission).Error)
		if s.variant == "shared-revoked" {
			must(summaryExec(db, "UPDATE kb_shares SET deleted_at=CURRENT_TIMESTAMP").Error)
		}
	}
	machine := strings.HasPrefix(s.variant, "api-")
	scope := types.TenantAPIKeyScope{Capabilities: types.StringArray{"ingest"}, KnowledgeBaseIDs: []string{"kb"}}
	if s.variant == "api-retrieve" {
		scope.Capabilities = types.StringArray{"retrieve"}
	}
	if strings.HasPrefix(s.variant, "api-full") {
		scope.FullAccess = true
	}
	if strings.HasSuffix(s.variant, "wrong-kb") {
		scope.KnowledgeBaseIDs = []string{"other-kb"}
	}
	router := gin.New()
	router.Use(middleware.ErrorHandler(), middleware.AuditServiceProvider(audit))
	router.Use(func(c *gin.Context) {
		ctx := context.WithValue(c.Request.Context(), types.TenantIDContextKey, tenant)
		ctx = context.WithValue(ctx, types.UserIDContextKey, user)
		ctx = context.WithValue(ctx, types.TenantInfoContextKey, &types.Tenant{ID: tenant, Name: "Owner", Business: "test", Status: "active"})
		ctx = context.WithValue(ctx, types.LanguageContextKey, "en-US")
		ctx = context.WithValue(ctx, types.TenantRoleContextKey, role)
		if machine {
			ctx = types.WithTenantAPIKeyScope(ctx, scope)
		}
		c.Request = c.Request.WithContext(ctx)
		c.Set(types.TenantIDContextKey.String(), tenant)
		c.Set(types.UserIDContextKey.String(), user)
		c.Next()
	})
	gate := middleware.NewAPIKeyRouteAuthorizer()
	writePolicy := middleware.APIKeyRoutePolicy{RequireFullAccess: true}.WithCapability(types.APIKeyCapabilityIngest)

	gate.Register("POST", "/api/v1/knowledge/:id/regenerate-summary", writePolicy)
	own := middleware.RequireOwnershipOrRole(types.TenantRoleAdmin, h.KBCreatorLookupFromKnowledgeID, cfg)
	write := middleware.RequireKBAccess(middleware.KBIDFromKnowledgeIDParam("id", kg), types.OrgRoleEditor, kbs, shares, agents, cfg)
	router.POST("/api/v1/knowledge/:id/regenerate-summary", gate.Middleware(), own, write, h.RegenerateKnowledgeSummary)
	method, target, payload := "POST", "/api/v1/knowledge/d1/regenerate-summary", "deliberately ignored invalid JSON body"
	r := httptest.NewRequest(method, target, strings.NewReader(payload))
	r.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	router.ServeHTTP(w, r)
	var body any
	dec := json.NewDecoder(bytes.NewReader(w.Body.Bytes()))
	dec.UseNumber()
	must(dec.Decode(&body))

	var rows []map[string]any
	table := "knowledges"
	if s.variant == "count-table-missing" {
		table = "hidden_knowledges"
	}
	must(db.Table(table).Select("id,tenant_id,knowledge_base_id,title,description,summary_status,custom_metadata,parse_status,CASE WHEN deleted_at IS NOT NULL THEN 1 ELSE 0 END AS deleted,CASE WHEN updated_at = ? THEN 0 ELSE 1 END AS updated", stamp).Order("id").Find(&rows).Error)
	var audits []map[string]any
	must(db.Table("audit_logs").Select("tenant_id,actor_user_id,actor_role,action,request_path,request_method,outcome,details").Order("id").Find(&audits).Error)
	for _, entry := range audits {
		raw := entry["details"]
		var decoded any
		switch v := raw.(type) {
		case []byte:
			must(json.Unmarshal(v, &decoded))
		case string:
			must(json.Unmarshal([]byte(v), &decoded))
		default:
			decoded = raw
		}
		entry["details"] = decoded
	}
	if pgDSN != "" && s.variant == "count-table-missing" {
		must(summaryExec(db, "ALTER TABLE hidden_knowledges RENAME TO knowledges").Error)
	}
	summaries := []map[string]any{}
	var chunkRows []types.Chunk
	must(db.Where("knowledge_id='d1' AND chunk_type='summary'").Order("id").Find(&chunkRows).Error)
	ids := map[string]string{}
	for _, c := range chunkRows {
		ids[c.ID] = c.ID
		if !strings.HasPrefix(c.ID, "summary-") {
			ids[c.ID] = "new-summary"
		}
		summaries = append(summaries, map[string]any{"id": ids[c.ID], "content": c.Content, "source_content": c.SourceContent, "index": c.ChunkIndex, "parent": c.ParentChunkID, "enabled": c.IsEnabled, "status": c.Status, "seq_id": c.SeqID})
	}
	sort.Slice(summaries, func(i, j int) bool { return summaries[i]["id"].(string) < summaries[j]["id"].(string) })
	indexes := []map[string]any{}
	indexTable := "lite_embeddings"
	if pgDSN != "" {
		indexTable = "embeddings"
	}
	must(db.Raw("SELECT source_id,source_type,chunk_id,knowledge_id,knowledge_base_id,content,dimension,is_enabled FROM " + indexTable + " ORDER BY source_id").Scan(&indexes).Error)
	for _, entry := range indexes {
		entry["source_id"] = ids[entry["source_id"].(string)]
		entry["chunk_id"] = ids[entry["chunk_id"].(string)]
	}
	sort.Slice(indexes, func(i, j int) bool { return indexes[i]["source_id"].(string) < indexes[j]["source_id"].(string) })
	return map[string]any{"name": s.name, "operation": s.operation, "variant": s.variant, "method": method, "target": target, "request_body": payload, "status": w.Code, "body": body, "documents": rows, "tasks": captured.rows, "audits": audits, "caller_tenant": tenant, "user": user, "role": role, "effective_role": types.TenantRoleFromContext(context.WithValue(context.Background(), types.TenantRoleContextKey, role)), "machine": machine, "scope": scope, "reply": reply, "requests": requests, "summaries": summaries, "indexes": indexes}
}

// Dialect adaptation is restricted to the runner-owned fixture database.
func summaryExec(db *gorm.DB, sql string, args ...any) *gorm.DB {
	if db.Dialector.Name() != "postgres" {
		return db.Exec(sql, args...)
	}
	for name, table := range map[string]string{"fail_chunk": "chunks", "fail_index": "embeddings"} {
		if strings.HasPrefix(sql, "CREATE TRIGGER "+name+" ") {
			message := "synthetic chunk failure"
			if name == "fail_index" {
				message = "synthetic index failure"
			}
			result := db.Exec("CREATE OR REPLACE FUNCTION " + name + "_fn() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION '" + message + "'; END $$")
			if result.Error != nil {
				return result
			}
			return db.Exec("CREATE TRIGGER " + name + " BEFORE INSERT ON " + table + " FOR EACH ROW EXECUTE FUNCTION " + name + "_fn()")
		}
	}
	if strings.Contains(sql, "metadata=") {
		for i, arg := range args {
			if bytes, ok := arg.([]byte); ok {
				args[i] = string(bytes)
			}
		}
	}
	return db.Exec(sql, args...)
}
