//go:build ignore

// Actual production Go reparse HTTP/worker admission, cleanup checkpoints, physical indexes, SQL and Asynq oracle.
package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	repo "github.com/Tencent/WeKnora/internal/application/repository"
	ng "github.com/Tencent/WeKnora/internal/application/repository/retriever/neo4j"
	pgindex "github.com/Tencent/WeKnora/internal/application/repository/retriever/postgres"
	sr "github.com/Tencent/WeKnora/internal/application/repository/retriever/sqlite"
	svc "github.com/Tencent/WeKnora/internal/application/service"
	retrieve "github.com/Tencent/WeKnora/internal/application/service/retriever"
	"github.com/Tencent/WeKnora/internal/config"
	"github.com/Tencent/WeKnora/internal/handler"
	"github.com/Tencent/WeKnora/internal/logger"
	"github.com/Tencent/WeKnora/internal/middleware"
	"github.com/Tencent/WeKnora/internal/models/embedding"
	"github.com/Tencent/WeKnora/internal/router"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"github.com/Tencent/WeKnora/internal/utils"
	"github.com/alicebob/miniredis/v2"
	sqlitevec "github.com/asg017/sqlite-vec-go-bindings/cgo"
	"github.com/gin-gonic/gin"
	"github.com/hibiken/asynq"
	"github.com/panjf2000/ants/v2"
	redislib "github.com/redis/go-redis/v9"
	"gorm.io/driver/postgres"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
	gl "gorm.io/gorm/logger"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
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

type scene struct{ name, operation, variant, target, body string }

var seeds []any
var recording bool
var workerLanguage string

func main() {
	time.Local = time.UTC
	logger.SetOutput(os.Stderr)
	gin.SetMode(gin.TestMode)
	sqlitevec.Auto()
	if os.Getenv("WEKNORA_REPARSE_PARITY_PG_DSN") != "" {
		must(os.Setenv("RETRIEVE_DRIVER", "postgres"))
	} else {
		must(os.Setenv("RETRIEVE_DRIVER", "sqlite"))
	}
	must(os.Setenv("SSRF_WHITELIST", "127.0.0.1"))
	must(os.Setenv("SYSTEM_AES_KEY", ""))
	utils.ResetSSRFWhitelistForTest()
	out := []any{}
	if len(os.Args) > 2 && os.Args[2] == "worker" {
		for _, variant := range []string{"ok", "missing-kb", "deleted-kb", "foreign-document", "deleted-document", "wrong-kb-doc", "moving", "file", "file-table", "file-url", "url", "missing-source", "manual-null-metadata", "manual-missing-metadata", "manual-bad-content", "file-cleanup-failure", "file-queue-error", "manual-counter-failure", "tenant-missing"} {
			out = append(out, run(scene{"worker-" + variant, "worker", variant, "", `{"tenant_id":1,"knowledge_base_id":"kb","knowledge_ids":["d1","d2"]}`}))
		}
		for n, raw := range []string{"", `null`, `{}`, `[]`, `{`, `{} trailing`,
			`{"tenant_id":1}`, `{"tenant_id":1,"knowledge_ids":[]}`, `{"tenant_id":1,"knowledge_ids":["d2","d1","d2"]}`,
			`{"TENANT_ID":1,"KNOWLEDGE_BASE_ID":"kb","KNOWLEDGE_IDS":["d1"]}`, `{"tenant_id":1,"knowledge_ids":[" d1 "]}`, `{"tenant_id":1,"knowledge_ids":[null]}`, `{"tenant_id":1,"knowledge_ids":[" "]}`, `{"tenant_id":1,"knowledge_ids":["absent"]}`,
			`{"tenant_id":"1","knowledge_ids":["d1"]}`, `{"tenant_id":1.0,"knowledge_ids":["d1"]}`, `{"tenant_id":-1}`, `{"tenant_id":1,"knowledge_ids":"d1"}`, `{"tenant_id":1,"knowledge_ids":[1]}`, `{"tenant_id":1,"knowledge_base_id":4}`, `{"tenant_id":1,"initiator":[]}`,
			`{"tenant_id":1,"knowledge_ids":[],"process_config":{"chunking_config":{"chunk_size":-2}}}`, `{"tenant_id":1,"knowledge_ids":["d1","d2"],"process_config":{"chunking_config":{"chunk_size":-2}}}`, `{"tenant_id":1,"knowledge_ids":["d1"],"process_config":{"summary_enabled":false}}`,
			`{"tenant_id":1,"knowledge_ids":["d1"],"process_config":1}`, `{"tenant_id":1,"knowledge_ids":["d1"],"process_config":{"summary_enabled":"wrong"}}`,
			`{"tenant_id":1,"knowledge_ids":["d1"],"initiator":{"user_id":"original","role":"admin"}}`,
			`{"tenant_id":1,"knowledge_ids":["d1"],"initiator":{"user_id":"original","role":"invalid"}}`,
			`{"tenant_id":1,"knowledge_ids":["d1"],"initiator":{"user_id":"system-1","role":"owner","api_key_id":71,"api_key_name":"Name at enqueue"}}`,
			`{"tenant_id":1,"knowledge_ids":["d1"],"initiator":{"user_id":"original","role":"viewer","api_key_id":71,"api_key_name":"Name at enqueue"}}`,
			`{"tenant_id":1,"knowledge_ids":["d1"],"initiator":{"api_key_name":"Display identity only"}}`,
			`{"tenant_id":1,"knowledge_ids":["d1"],"initiator":{"api_key_id":-1}}`,
			`{"tenant_id":1,"knowledge_ids":["d1"],"lf_trace_id":4}`, `{"tenant_id":1,"knowledge_ids":["d1"],"lf_trace_id":"legacy-trace","lf_traceparent":"00-11111111111111111111111111111111-2222222222222222-01","lf_user_id":"trace-user","lf_session_id":"trace-session"}`,
			`{"tenant_id":1,"knowledge_ids":["d1"],"initiator":{"user_id":"first","role":"viewer"},"INITIATOR":{"role":"admin"}}`,
			`{"tenant_id":1,"knowledge_ids":["d1"],"knowledge_ids":null}`, `{"tenant_id":1,"knowledge_ids":["d1"],"process_config":{"summary_enabled":false},"process_config":null}`,
			`{"tenant_id":1,"knowledge_ids":["d1"],"lf_parent_obs_id":true}`,
			`{"tenant_id":1,"knowledge_ids":["d1"],"lf_traceparent":[]}`,
			`{"tenant_id":1,"knowledge_ids":["d1"],"lf_user_id":{}}`,
			`{"tenant_id":1,"knowledge_ids":["d1"],"LF_SESSION_ID":1}`,
			`{"tenant_id":1,"knowledge_ids":["d1"],"initiator":{"user_id":"original","role":"contributor","api_key_id":9223372036854775808,"api_key_name":"Unsigned audit identity"}}`,
			`{"tenant_id":1,"knowledge_ids":["d1"],"initiator":{"user_id":"original","role":"contributor","api_key_id":18446744073709551615}}`,
			`{"tenant_id":1,"knowledge_ids":["d1"],"initiator":{"api_key_id":18446744073709551616}}`,
			`{"tenant_id":1,"knowledge_ids":["d1"],"initiator":{"api_key_id":1e1}}`,
		} {
			out = append(out, run(scene{fmt.Sprintf("worker-payload-%d", n), "worker", "binding", "", raw}))
		}
		for _, language := range []string{"", "en-US", "  ko-KR  "} {
			workerLanguage = language
			out = append(out, run(scene{"worker-language-" + language, "worker", "file", "", `{"tenant_id":1,"knowledge_base_id":"kb","knowledge_ids":["d1","d2"]}`}))
		}
		workerLanguage = ""
		b, e := json.Marshal(out)
		must(e)
		must(os.WriteFile(os.Args[1], append(b, '\n'), 0600))
		fmt.Println("exported", len(out), "actual Go Asynq reparse worker scenes")
		return
	}
	for _, operation := range []string{"single", "batch"} {
		for _, variant := range []string{"ok", "missing-kb", "deleted-kb", "creator-viewer", "creator-contributor", "noncreator-contributor", "noncreator-viewer", "noncreator-admin", "shared-editor", "shared-viewer", "shared-revoked", "api-ingest", "api-retrieve", "api-full", "api-wrong-kb", "api-full-wrong-kb", "moving", "foreign-document", "deleted-document", "wrong-kb-doc", "queue-error"} {
			out = append(out, run(scene{operation + "-" + variant, operation, variant, "", ""}))
		}
	}
	for _, variant := range []string{"file", "file-finalizing", "file-cancelled", "file-draft", "file-table", "file-upper", "file-no-extension", "file-no-model", "file-missing-model", "file-cleanup-failure", "file-queue-error", "file-overrides", "file-override-image-reject", "url", "file-url", "file-url-table", "missing-source", "manual-null-metadata", "manual-missing-metadata", "manual-bad-content", "manual-empty-content", "manual-overrides", "manual-stored-overrides", "manual-pending", "manual-processing", "manual-finalizing", "manual-failed", "manual-cancelled", "manual-deleting", "manual-counter-failure"} {
		out = append(out, run(scene{"single-" + variant, "single", variant, "", ""}))
	}
	for _, body := range []string{"", "null", "{}", "[]", "{", `{"process_config":null}`, `{"process_config":{}}`, `{"process_config":1}`, `{"PROCESS_CONFIG":{"summary_enabled":false}}`, `{"process_config":{"summary_enabled":true},"process_config":null}`, `{"process_config":{"summary_enabled":"wrong"}}`, `{}trailing`} {
		out = append(out, run(scene{fmt.Sprintf("single-body-%d", len(out)), "single", "binding", "", body}))
	}
	for _, body := range []string{"", "null", "{}", "[]", "{", `{"kb_id":"kb","ids":[]}`, `{"kb_id":"kb","ids":[" ",null,""]}`, `{"kb_id":"kb","ids":[" d2 ","d1","d2",""," d1 "]}`, `{"kb_id":"kb","ids":["d1","absent"]}`, `{"kb_id":"kb","ids":["d1","other"]}`, `{"KB_ID":"kb","IDS":["d1"]}`, `{"kb_id":"kb","ids":["d1"],"process_config":{"summary_enabled":false}}`, `{"kb_id":"kb","ids":["d1"],"process_config":1}`, `{"kb_id":"kb","ids":[4]}`, `{"kb_id":"kb","ids":["d1"]}trailing`, `{"kb_id":" kb ","ids":["d1"]}`} {
		out = append(out, run(scene{fmt.Sprintf("batch-body-%d", len(out)), "batch", "binding", "", body}))
	}
	b, e := json.Marshal(out)
	must(e)
	must(os.WriteFile(os.Args[1], append(b, '\n'), 0600))
	fmt.Println("exported", len(out), "actual Go reparse scenes")
}
func run(s scene) any {
	recording = false
	seeds = []any{}
	dir, e := os.MkdirTemp("", "weknora-go-update-")
	must(e)
	defer os.RemoveAll(dir)
	var dialect gorm.Dialector = sqlite.Open(filepath.Join(dir, "case.db"))
	pgDSN := os.Getenv("WEKNORA_REPARSE_PARITY_PG_DSN")
	if pgDSN != "" {
		u, e := url.Parse(pgDSN)
		must(e)
		if u.Hostname() != "127.0.0.1" || u.Path != "/weknora_reparse_parity" {
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
		for _, m := range []string{"000000_init", "000002_knowledge_folder_path", "000003_knowledge_base_auto_tag_config", "000004_memory", "000006_task_queue_and_dead_letters", "000007_system_admin_and_settings", "000008_processing_spans_and_pending_subtasks", "000010_knowledge_multi_tags", "000011_principal_model", "000021_knowledge_profiles", "000031_model_catalog_config", "000033_chunk_source_locators"} {
			b, e := os.ReadFile("migrations/sqlite/" + m + ".up.sql")
			must(e)
			must(summaryExec(db, string(b)).Error)
		}
	} else {
		must(summaryExec(db, "DROP TRIGGER IF EXISTS fail_chunk ON chunks").Error)
		must(summaryExec(db, "DROP TRIGGER IF EXISTS fail_index ON embeddings").Error)
		must(summaryExec(db, "DROP TRIGGER IF EXISTS fail_counter ON knowledges").Error)
		must(summaryExec(db, "TRUNCATE tenants,knowledge_bases,knowledges,chunks,models,embeddings,organizations,organization_tenant_members,kb_shares,audit_logs,knowledge_processing_spans,task_pending_ops RESTART IDENTITY CASCADE").Error)
		must(summaryExec(db, "ALTER SEQUENCE chunks_seq_id_seq RESTART WITH 100000000").Error)
	}
	recording = true
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
	must(summaryExec(db, `UPDATE knowledges SET type='manual',file_name='notes.md',file_type='manual',metadata=?,description='Old description',summary_status='completed',enable_status='enabled',embedding_model_id='embedding',storage_size=13,pending_subtasks_count=4,error_message='old failure',processed_at=? WHERE id IN ('d1','d2')`, `{"content":"# Reparse body 中文","format":"markdown","status":"publish","version":7}`, stamp).Error)
	must(summaryExec(db, "UPDATE tenants SET storage_used=100 WHERE id=1").Error)
	must(summaryExec(db, `INSERT INTO task_pending_ops(tenant_id,task_type,scope,scope_id,op,dedup_key) VALUES
    (1,'wiki:ingest','knowledge_base','kb','ingest','d1'),
    (1,'wiki:ingest','knowledge_base','kb','retract','d1'),
    (1,'wiki:ingest','knowledge_base','kb','ingest','d2'),
    (1,'wiki:ingest','knowledge_base','other-kb','ingest','d1'),
    (1,'other:task','knowledge_base','kb','ingest','d1'),
    (2,'wiki:ingest','knowledge_base','kb','ingest','d1')`).Error)
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
	case "moving":
		must(summaryExec(db, `UPDATE knowledges SET metadata='{"_knowledge_transfer":{"operation":"move","phase":"moving"}}' WHERE id='d1'`).Error)
	}
	if strings.HasPrefix(s.variant, "file") {
		typ, name, path, source := "md", "notes.md", "local://original/notes.md", "upload"
		kind := "file"
		if strings.HasPrefix(s.variant, "file-url") {
			kind = "file_url"
			path = ""
			source = "https://example.test/files/notes.md"
		}
		if strings.HasSuffix(s.variant, "table") {
			typ = "csv"
			name = "data.csv"
		}
		if s.variant == "file-upper" {
			name = "NOTES.MD"
		}
		if s.variant == "file-no-extension" {
			name = "notes"
		}
		must(summaryExec(db, "UPDATE knowledges SET type=?,source=?,file_path=?,file_name=?,file_type=? WHERE id='d1'", kind, source, path, name, typ).Error)
	}
	if s.variant == "url" {
		must(summaryExec(db, "UPDATE knowledges SET type='url',source='https://example.test/page',file_type='html' WHERE id='d1'").Error)
	}
	if s.variant == "missing-source" {
		must(summaryExec(db, "UPDATE knowledges SET type='file',source='' WHERE id='d1'").Error)
	}
	if s.variant == "file-no-model" {
		must(summaryExec(db, "UPDATE knowledges SET embedding_model_id='' WHERE id='d1'").Error)
	}
	if s.variant == "file-missing-model" {
		must(summaryExec(db, "UPDATE knowledges SET embedding_model_id='absent' WHERE id='d1'").Error)
	}
	if s.variant == "manual-null-metadata" {
		must(summaryExec(db, "UPDATE knowledges SET metadata='null' WHERE id='d1'").Error)
	}
	if s.variant == "manual-missing-metadata" {
		must(summaryExec(db, "UPDATE knowledges SET metadata=NULL WHERE id='d1'").Error)
	}
	if s.variant == "manual-bad-content" {
		must(summaryExec(db, `UPDATE knowledges SET metadata='{"content":12}' WHERE id='d1'`).Error)
	}
	if s.variant == "manual-empty-content" {
		must(summaryExec(db, "UPDATE knowledges SET metadata='{}' WHERE id='d1'").Error)
	}
	if s.variant == "manual-stored-overrides" {
		must(summaryExec(db, `UPDATE knowledges SET metadata='{"content":"stored","process_overrides":{"summary_enabled":false}}' WHERE id='d1'`).Error)
	}
	for _, status := range []string{"pending", "processing", "finalizing", "failed", "cancelled", "deleting", "draft"} {
		if strings.HasSuffix(s.variant, "-"+status) {
			must(summaryExec(db, "UPDATE knowledges SET parse_status=? WHERE id='d1'", status).Error)
		}
	}
	if s.variant == "file-override-image-reject" {
		must(summaryExec(db, "UPDATE knowledges SET file_name='image.png',file_type='png' WHERE id='d1'").Error)
	}
	must(summaryExec(db, "INSERT INTO chunks(id,tenant_id,knowledge_id,knowledge_base_id,content,chunk_index,start_at,end_at,is_enabled,chunk_type,status) VALUES('physical',1,'d1','kb','Old indexed body',0,0,16,true,'text',0),('other-physical',1,'d2','kb','Other indexed body',0,0,18,true,'text',0)").Error)
	name := s.variant
	requests := []any{}
	var mu sync.Mutex
	reply := "unused"
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		must(json.NewDecoder(r.Body).Decode(&body))
		mu.Lock()
		requests = append(requests, map[string]any{"path": r.URL.Path, "body": body})
		mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
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
	must(index.BatchSave(context.Background(), []*types.IndexInfo{{SourceID: "physical", ChunkID: "physical", KnowledgeID: "d1", KnowledgeBaseID: "kb", Content: "Old indexed body", IsEnabled: true}, {SourceID: "other-physical", ChunkID: "other-physical", KnowledgeID: "d2", KnowledgeBaseID: "kb", Content: "Other indexed body", IsEnabled: true}}, map[string]any{"embedding": map[string][]float32{"physical": {1, 0, 0}, "other-physical": {0, 1, 0}}}))
	must(summaryExec(db, "UPDATE knowledge_bases SET embedding_model_id='embedding' WHERE id='kb'").Error)
	if name == "file-cleanup-failure" {
		must(summaryExec(db, "CREATE TRIGGER fail_chunk BEFORE UPDATE ON chunks BEGIN SELECT RAISE(FAIL,'synthetic chunk failure');END").Error)
	}
	if name == "manual-counter-failure" {
		must(summaryExec(db, "CREATE TRIGGER fail_counter BEFORE UPDATE OF pending_subtasks_count ON knowledges BEGIN SELECT RAISE(FAIL,'synthetic counter failure');END").Error)
	}
	if s.operation == "worker" && s.variant == "tenant-missing" {
		must(summaryExec(db, "DELETE FROM tenants WHERE id=1").Error)
	}
	if name == "unused-index-error" {
		must(summaryExec(db, "CREATE TRIGGER fail_index BEFORE INSERT ON lite_embeddings BEGIN SELECT RAISE(FAIL,'synthetic index failure');END").Error)
	}
	redis, e := miniredis.Run()
	must(e)
	defer redis.Close()
	client := asynq.NewClient(asynq.RedisClientOpt{Addr: redis.Addr()})
	defer client.Close()
	redisClient := redislib.NewClient(&redislib.Options{Addr: redis.Addr()})
	defer redisClient.Close()
	inspector := router.NewAsynqInspector(redisClient)
	defer inspector.Close()
	for _, id := range []string{"d1", "d2"} {
		payload, err := json.Marshal(map[string]any{"tenant_id": 1, "knowledge_id": id, "knowledge_base_id": "kb"})
		must(err)
		_, err = client.Enqueue(asynq.NewTask(types.TypeSummaryGeneration, payload), asynq.Queue("summary"), asynq.TaskID("old-"+id))
		must(err)
	}
	captured := &capture{client: client, rows: []any{}, fail: strings.Contains(s.variant, "queue-error")}
	audit := svc.NewAuditLogService(repo.NewAuditLogRepository(db))
	shares := svc.NewKBShareService(repo.NewKBShareRepository(db), repo.NewOrganizationRepository(db), nil, nil, nil, audit)
	agents := svc.NewAgentShareService(repo.NewAgentShareRepository(db), repo.NewTenantDisabledSharedAgentRepository(db), repo.NewOrganizationRepository(db), repo.NewCustomAgentRepository(db), nil, nil, nil)
	br, kr, cr := repo.NewKnowledgeBaseRepository(db), repo.NewKnowledgeRepository(db), repo.NewChunkRepository(db)
	kbs := svc.NewKnowledgeBaseService(br, kr, cr, nil, shares, nil, nil, nil, repo.NewTenantRepository(db), nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil)
	tracker := svc.NewSpanTracker(repo.NewKnowledgeSpanRepository(db), db)
	chunks := svc.NewChunkService(cr, kr, br, models, registry, nil, captured, tracker)
	kg, e := svc.NewKnowledgeService(&config.Config{Conversation: &config.ConversationConfig{}}, kr, nil, kbs, repo.NewTenantRepository(db), nil, chunks, cr, nil, nil, nil, nil, nil, models, captured, router.NewAsynqTaskInspector(inspector, redisClient), ng.NewNeo4jRepository(nil), registry, nil, nil, shares, nil, nil, nil, repo.NewTaskPendingOpsRepository(db), tracker, audit)
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

	gate.Register("POST", "/api/v1/knowledge/:id/reparse", writePolicy)
	gate.Register("POST", "/api/v1/knowledge/batch-reparse", writePolicy)
	own := middleware.RequireOwnershipOrRole(types.TenantRoleAdmin, h.KBCreatorLookupFromKnowledgeID, cfg)
	write := middleware.RequireKBAccess(middleware.KBIDFromKnowledgeIDParam("id", kg), types.OrgRoleEditor, kbs, shares, agents, cfg)
	router.POST("/api/v1/knowledge/:id/reparse", gate.Middleware(), own, write, h.ReparseKnowledge)
	router.POST("/api/v1/knowledge/batch-reparse", gate.Middleware(), middleware.RequireRole(types.TenantRoleContributor, cfg), h.BatchReparseKnowledge)
	method, target, payload := "POST", "/api/v1/knowledge/d1/reparse", ""
	if s.operation == "batch" {
		target = "/api/v1/knowledge/batch-reparse"
		payload = `{"kb_id":"kb","ids":["d1","d2"]}`
	}
	if s.variant == "binding" {
		payload = s.body
	}
	if strings.HasSuffix(s.variant, "overrides") || s.variant == "file-override-image-reject" {
		payload = `{"process_config":{"summary_enabled":false}}`
	}
	recording = false
	r := httptest.NewRequest(method, target, strings.NewReader(payload))
	r.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	var body any
	if s.operation == "worker" {
		payload = s.body
		must(os.Setenv("WEKNORA_LANGUAGE", workerLanguage))
		finished := make(chan error, 1)
		server := asynq.NewServer(asynq.RedisClientOpt{Addr: redis.Addr()}, asynq.Config{Concurrency: 1, Queues: map[string]int{"low": 1}, TaskCheckInterval: 10 * time.Millisecond, ShutdownTimeout: time.Second})
		mux := asynq.NewServeMux()
		mux.HandleFunc(types.TypeKnowledgeListReparse, func(ctx context.Context, task *asynq.Task) error {
			err := kg.ProcessKnowledgeListReparse(types.WithBackgroundTask(ctx), task)
			finished <- err
			return err
		})
		must(server.Start(mux))
		_, err := client.Enqueue(asynq.NewTask(types.TypeKnowledgeListReparse, []byte(payload)), asynq.Queue("low"), asynq.TaskID("batch-job"), asynq.MaxRetry(0))
		must(err)
		var failure error
		select {
		case failure = <-finished:
		case <-time.After(15 * time.Second):
			server.Shutdown()
			panic("Go worker did not finish")
		}
		server.Shutdown()
		message := ""
		if failure != nil {
			message = failure.Error()
		}
		body = map[string]any{"error": message, "skip_retry": errors.Is(failure, asynq.SkipRetry)}
	} else {
		router.ServeHTTP(w, r)
		dec := json.NewDecoder(bytes.NewReader(w.Body.Bytes()))
		dec.UseNumber()
		must(dec.Decode(&body))
	}

	var rows, tenants, indexRows, audits, chunkRows, spans []map[string]any
	must(db.Table("knowledges").Select("id,tenant_id,knowledge_base_id,type,title,source,file_path,file_name,file_type,description,summary_status,metadata,parse_status,enable_status,embedding_model_id,storage_size,pending_subtasks_count,error_message,CASE WHEN processed_at IS NULL THEN 1 ELSE 0 END AS processed_null,CASE WHEN deleted_at IS NULL THEN 0 ELSE 1 END AS deleted").Order("id").Find(&rows).Error)
	must(db.Table("tenants").Select("id,storage_used").Order("id").Find(&tenants).Error)
	table := "lite_embeddings"
	if pgDSN != "" {
		table = "embeddings"
	}
	must(db.Table(table).Select("source_id,source_type,chunk_id,knowledge_id,knowledge_base_id,content,dimension,is_enabled").Order("source_id").Find(&indexRows).Error)
	must(db.Table("chunks").Unscoped().Select("id,tenant_id,knowledge_id,content,CASE WHEN deleted_at IS NULL THEN 0 ELSE 1 END AS deleted").Order("id").Find(&chunkRows).Error)
	must(db.Table("knowledge_processing_spans").Select("knowledge_id,attempt,name,kind,status").Order("knowledge_id,attempt,kind,name").Find(&spans).Error)
	must(db.Table("audit_logs").Select("tenant_id,actor_user_id,actor_role,action,request_path,request_method,outcome,details").Order("id").Find(&audits).Error)
	for _, entry := range audits {
		var decoded any
		raw := entry["details"]
		switch v := raw.(type) {
		case []byte:
			decoder := json.NewDecoder(bytes.NewReader(v))
			decoder.UseNumber()
			must(decoder.Decode(&decoded))
		case string:
			decoder := json.NewDecoder(strings.NewReader(v))
			decoder.UseNumber()
			must(decoder.Decode(&decoded))
		default:
			decoded = raw
		}
		entry["details"] = decoded
	}
	var pending []map[string]any
	must(db.Table("task_pending_ops").Select("tenant_id,task_type,scope,scope_id,op,dedup_key").Order("id").Find(&pending).Error)
	oldQueue := map[string]bool{}
	for _, id := range []string{"d1", "d2"} {
		_, err := inspector.GetTaskInfo("summary", "old-"+id)
		if err != nil && !errors.Is(err, asynq.ErrTaskNotFound) {
			must(err)
		}
		oldQueue[id] = err == nil
	}
	result := map[string]any{"name": s.name, "operation": s.operation, "variant": s.variant, "method": method, "target": target, "request_body": payload, "status": w.Code, "body": body, "seed_sql": seeds, "documents": rows, "tenants": tenants, "chunks": chunkRows, "indexes": indexRows, "spans": spans, "pending_ops": pending, "old_queue": oldQueue, "tasks": captured.rows, "audits": audits, "caller_tenant": tenant, "user": user, "role": role, "machine": machine, "scope": scope, "requests": requests, "reply": reply}
	if s.operation == "worker" {
		result["worker_language"] = workerLanguage
	}
	return result
}

// Dialect adaptation is restricted to the runner-owned fixture database.
func summaryExec(db *gorm.DB, sql string, args ...any) *gorm.DB {
	if recording {
		seeds = append(seeds, map[string]any{"sql": sql, "args": args})
	}
	if db.Dialector.Name() != "postgres" {
		return db.Exec(sql, args...)
	}
	for name, table := range map[string]string{"fail_chunk": "chunks", "fail_index": "embeddings", "fail_counter": "knowledges"} {
		if strings.HasPrefix(sql, "CREATE TRIGGER "+name+" ") {
			message := "synthetic chunk failure"
			if name == "fail_index" {
				message = "synthetic index failure"
			}
			if name == "fail_counter" {
				message = "synthetic counter failure"
			}
			result := db.Exec("CREATE OR REPLACE FUNCTION " + name + "_fn() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION '" + message + "'; END $$")
			if result.Error != nil {
				return result
			}
			event := "INSERT"
			if name == "fail_chunk" {
				event = "UPDATE"
			}
			if name == "fail_counter" {
				event = "UPDATE OF pending_subtasks_count"
			}
			return db.Exec("CREATE TRIGGER " + name + " BEFORE " + event + " ON " + table + " FOR EACH ROW EXECUTE FUNCTION " + name + "_fn()")
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
