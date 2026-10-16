//go:build ignore

// Actual public Go KB deletion: real middleware, repositories, staged side effects and Asynq options.
package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	repo "github.com/Tencent/WeKnora/internal/application/repository"
	svc "github.com/Tencent/WeKnora/internal/application/service"
	"github.com/Tencent/WeKnora/internal/config"
	"github.com/Tencent/WeKnora/internal/handler"
	"github.com/Tencent/WeKnora/internal/logger"
	"github.com/Tencent/WeKnora/internal/middleware"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"github.com/alicebob/miniredis/v2"
	"github.com/gin-gonic/gin"
	"github.com/hibiken/asynq"
	"gorm.io/driver/postgres"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
	gl "gorm.io/gorm/logger"
	"net/http/httptest"
	"net/url"
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
func quote(s string) string { return "'" + strings.ReplaceAll(s, "'", "''") + "'" }

type capture struct {
	client *asynq.Client
	rows   []any
	fail   bool
}

func (c *capture) Enqueue(t *asynq.Task, opts ...asynq.Option) (*asynq.TaskInfo, error) {
	if c.fail {
		return nil, fmt.Errorf("controlled enqueue failure")
	}
	info, e := c.client.Enqueue(t, opts...)
	if e != nil {
		return nil, e
	}
	var payload any
	must(json.Unmarshal(t.Payload(), &payload))
	c.rows = append(c.rows, map[string]any{"type": t.Type(), "payload": payload, "queue": info.Queue, "max_retry": info.MaxRetry, "timeout": int64(info.Timeout / time.Millisecond), "scheduled": info.State == asynq.TaskStateScheduled})
	return info, nil
}

type inspector struct {
	interfaces.TaskInspector
	db    *gorm.DB
	calls []any
	fail  bool
}

func (i *inspector) CancelTasksForKnowledgeBase(ctx context.Context, kb string, docs, sources []string) (int, int, error) {
	var n int64
	must(i.db.WithContext(ctx).Table("task_pending_ops").Where("scope = ? AND scope_id = ?", "knowledge_base", kb).Count(&n).Error)
	i.calls = append(i.calls, map[string]any{"kb": kb, "documents": append([]string{}, docs...), "sources": append([]string{}, sources...), "pending": n, "detached": ctx.Err() == nil, "bounded": func() bool { _, ok := ctx.Deadline(); return ok }()})
	if i.fail {
		return 0, 0, fmt.Errorf("controlled queue scan failure")
	}
	return 0, 0, nil
}
func main() {
	time.Local = time.UTC
	logger.SetOutput(os.Stderr)
	gin.SetMode(gin.TestMode)
	out := []any{}
	for _, v := range []string{
		"owner", "admin", "contributor-creator", "viewer-creator", "contributor", "viewer", "unknown-role", "no-user", "api-management", "api-no-user", "api-full", "api-retrieve", "api-ingest", "api-wrong-kb", "api-full-wrong-kb", "shared-editor", "shared-admin", "shared-viewer", "shared-revoked", "foreign", "missing", "deleted", "temporary", "faq", "wiki", "empty-vector", "bound-vector", "blob-engines", "text-engines", "legacy-engines", "empty-engines", "unknown-engine-fields", "partial-engine", "null-engine", "duplicate-engine-fields", "null-engine-array", "default-drivers", "enqueue-failure", "delete-failure", "share-failure", "source-failure", "source-list-failure", "log-failure", "pending-failure", "queue-failure", "audit-missing", "kb-table-missing", "repeat", "empty-name", "agent-query",
	} {
		out = append(out, run(v))
	}
	raw, e := json.MarshalIndent(out, "", "  ")
	must(e)
	must(os.WriteFile(os.Args[1], append(raw, '\n'), 0600))
	fmt.Println("exported", len(out), "actual Go public KB delete cases")
}
func run(v string) any {
	dir, e := os.MkdirTemp("", "weknora-kb-delete-http-")
	must(e)
	defer os.RemoveAll(dir)
	dsn := os.Getenv("WEKNORA_KB_DELETE_HTTP_PG_DSN")
	var dialect gorm.Dialector = sqlite.Open(filepath.Join(dir, "case.db"))
	if dsn != "" {
		u, e := url.Parse(dsn)
		must(e)
		if u.Hostname() != "127.0.0.1" || u.Path != "/weknora_kb_delete_http_parity" {
			panic("Owned loopback KB delete HTTP database required")
		}
		dialect = postgres.Open(dsn)
	}
	db, e := gorm.Open(dialect, &gorm.Config{Logger: gl.Default.LogMode(gl.Silent)})
	must(e)
	sql, e := db.DB()
	must(e)
	defer sql.Close()
	sql.SetMaxOpenConns(1)
	tables := []string{"knowledge_bases", "data_sources", "audit_logs"}
	if dsn == "" {
		for _, m := range []string{"000000_init", "000006_task_queue_and_dead_letters", "000011_principal_model"} {
			b, e := os.ReadFile("migrations/sqlite/" + m + ".up.sql")
			must(e)
			must(db.Exec(string(b)).Error)
		}
	} else {
		for _, t := range tables {
			var n int64
			must(db.Raw("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public' AND table_name=?", "hidden_"+t).Scan(&n).Error)
			if n > 0 {
				must(db.Exec("ALTER TABLE hidden_" + t + " RENAME TO " + t).Error)
			}
		}
		for _, t := range []string{"knowledge_bases", "kb_shares", "data_sources", "sync_logs", "task_pending_ops"} {
			must(db.Exec("DROP TRIGGER IF EXISTS reject_delete ON " + t).Error)
		}
		must(db.Exec("TRUNCATE tenants,knowledge_bases,knowledges,chunks,users,kb_shares,organizations,organization_tenant_members,data_sources,sync_logs,task_pending_ops,audit_logs RESTART IDENTITY CASCADE").Error)
	}
	seeds := []string{}
	seed := func(q string) { must(db.Exec(q).Error); seeds = append(seeds, q) }
	seed("INSERT INTO tenants(id,name,business) VALUES(1,'Owner','test'),(2,'Peer','test')")
	seed("INSERT INTO users(id,username,email,password_hash,tenant_id,is_active) VALUES('caller','Caller','caller@example.test','unused',1,true),('other','Other','other@example.test','unused',1,true)")
	seed("INSERT INTO knowledge_bases(id,tenant_id,creator_id,name,type,embedding_model_id,summary_model_id,created_at,updated_at) VALUES('kb',1,'other','KB','document','','','2026-01-01 00:00:00+00:00','2026-01-01 00:00:00+00:00'),('peer',2,'other','Peer','document','','','2026-01-01 00:00:00+00:00','2026-01-01 00:00:00+00:00')")
	seed("INSERT INTO knowledges(id,tenant_id,knowledge_base_id,type,title,source,parse_status,storage_size) VALUES('doc',1,'kb','file','Document','manual','completed',123)")
	seed("INSERT INTO chunks(id,tenant_id,knowledge_id,knowledge_base_id,content,chunk_index,start_at,end_at) VALUES('chunk',1,'doc','kb','Unchanged',0,0,9)")
	seed("INSERT INTO organizations(id,name,owner_tenant_id,owner_id) VALUES('org','Org',1,'caller')")
	seed("INSERT INTO organization_tenant_members(id,organization_id,tenant_id,role) VALUES('source','org',1,'admin'),('member','org',2,'editor')")
	seed("INSERT INTO kb_shares(id,knowledge_base_id,organization_id,source_tenant_id,permission,shared_by_user_id,created_at,updated_at) VALUES('share','kb','org',1,'editor','caller','2026-01-01 00:00:00+00:00','2026-01-01 00:00:00+00:00'),('peer-share','peer','org',2,'viewer','other','2026-01-01 00:00:00+00:00','2026-01-01 00:00:00+00:00')")
	for n, id := range []string{"s1", "s2", "old-source", "peer-source"} {
		base := "kb"
		if id == "peer-source" {
			base = "peer"
		}
		seed(fmt.Sprintf("INSERT INTO data_sources(id,tenant_id,knowledge_base_id,name,type,created_at,updated_at) VALUES(%s,1,%s,'Source','notion','2026-01-%02d 00:00:00+00:00','2026-01-01 00:00:00+00:00')", quote(id), quote(base), n+1))
		for j, status := range []string{"pending", "running", "success", "partial", "failed", "canceled"} {
			seed(fmt.Sprintf("INSERT INTO sync_logs(id,data_source_id,tenant_id,status,error_message,created_at,updated_at) VALUES(%s,%s,1,%s,'Original','2026-01-01 00:00:00+00:00','2026-01-01 00:00:00+00:00')", quote(fmt.Sprintf("%s-%d", id, j)), quote(id), quote(status)))
		}
	}
	seed("UPDATE data_sources SET deleted_at='2026-02-01 00:00:00+00:00' WHERE id='old-source'")
	seed("INSERT INTO task_pending_ops(tenant_id,scope,scope_id,task_type,op,payload) VALUES(1,'knowledge_base','kb','wiki:ingest','ingest','{}'),(2,'knowledge_base','peer','wiki:ingest','ingest','{}'),(1,'document','kb','wiki:ingest','ingest','{}')")
	role, user, tenant := "owner", "caller", uint64(1)
	switch v {
	case "admin":
		role = "admin"
	case "contributor", "contributor-creator":
		role = "contributor"
	case "viewer", "viewer-creator":
		role = "viewer"
	case "unknown-role":
		role = "revoked"
	case "no-user":
		role = "viewer"
		user = ""
	}
	if strings.HasSuffix(v, "-creator") {
		seed("UPDATE knowledge_bases SET creator_id='caller' WHERE id='kb'")
	}
	if strings.HasPrefix(v, "shared-") {
		tenant = 2
		role = "contributor"
		if v == "shared-admin" {
			seed("UPDATE organization_tenant_members SET role='admin' WHERE id='member'")
			seed("UPDATE kb_shares SET permission='admin' WHERE id='share'")
		}
		if v == "shared-viewer" {
			seed("UPDATE kb_shares SET permission='viewer' WHERE id='share'")
		}
		if v == "shared-revoked" {
			seed("UPDATE kb_shares SET deleted_at='2026-02-01 00:00:00+00:00' WHERE id='share'")
		}
	}
	switch v {
	case "api-no-user":
		seed("DELETE FROM users")
	case "foreign":
		seed("UPDATE knowledge_bases SET tenant_id=2 WHERE id='kb'")
	case "missing":
		seed("DELETE FROM knowledge_bases WHERE id='kb'")
	case "deleted":
		seed("UPDATE knowledge_bases SET deleted_at='2026-02-01 00:00:00+00:00' WHERE id='kb'")
	case "temporary":
		seed("UPDATE knowledge_bases SET is_temporary=true WHERE id='kb'")
	case "faq", "wiki":
		seed("UPDATE knowledge_bases SET type=" + quote(v) + " WHERE id='kb'")
	case "empty-name":
		seed("UPDATE knowledge_bases SET name='' WHERE id='kb'")
	case "empty-vector":
		seed("UPDATE knowledge_bases SET vector_store_id='' WHERE id='kb'")
	case "bound-vector":
		seed("UPDATE knowledge_bases SET vector_store_id='snapshot-store' WHERE id='kb'")
	}
	engines := `{"engines":[{"retriever_engine_type":"milvus","retriever_type":"vector"},{"retriever_engine_type":"opensearch","retriever_type":"keywords"}]}`
	driver := "sqlite"
	if dsn != "" {
		driver = "postgres"
	}
	if v == "default-drivers" {
		driver = " elasticsearch_v7,elasticsearch_v8,milvus,qdrant,sqlite,postgres,unknown,sqlite "
	}
	os.Setenv("RETRIEVE_DRIVER", driver)
	if v == "blob-engines" || v == "text-engines" || v == "legacy-engines" || v == "empty-engines" || v == "unknown-engine-fields" || v == "partial-engine" || v == "null-engine" || v == "duplicate-engine-fields" || v == "null-engine-array" {
		if v == "legacy-engines" {
			engines = `[{"retriever_engine_type":"doris","retriever_type":"keywords"}]`
		}
		if v == "empty-engines" {
			engines = `{"engines":[]}`
		}
		switch v {
		case "unknown-engine-fields":
			engines = `{"engines":[{"retriever_engine_type":"sqlite","retriever_type":"vector","future":"ignored"}]}`
		case "partial-engine":
			engines = `{"engines":[{"retriever_engine_type":"milvus"}]}`
		case "null-engine":
			engines = `{"engines":[null]}`
		case "duplicate-engine-fields":
			engines = `{"engines":[{"retriever_engine_type":"milvus","retriever_type":"vector"},{"retriever_type":"keywords"}],"engines":[null,{"retriever_engine_type":"sqlite"}]}`
		case "null-engine-array":
			engines = `{"engines":null}`
		}
		literal := quote(engines)
		if dsn == "" && v != "text-engines" {
			literal = "CAST(" + literal + " AS BLOB)"
		}
		seed("UPDATE tenants SET retriever_engines=" + literal + " WHERE id=1")
	}
	failTable := ""
	switch v {
	case "delete-failure":
		failTable = "knowledge_bases"
	case "share-failure":
		failTable = "kb_shares"
	case "source-failure":
		failTable = "data_sources"
	case "log-failure":
		failTable = "sync_logs"
	case "pending-failure":
		failTable = "task_pending_ops"
	}
	if failTable != "" {
		event := "UPDATE"
		condition := ""
		if failTable == "task_pending_ops" {
			event = "DELETE"
		}
		if v == "source-failure" {
			condition = " WHEN (OLD.id='s2')"
		}
		if dsn == "" {
			seed("CREATE TRIGGER reject_delete BEFORE " + event + " ON " + failTable + condition + " BEGIN SELECT RAISE(ABORT,'controlled deletion failure'); END")
		} else {
			seed("CREATE OR REPLACE FUNCTION reject_kb_delete_write() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'controlled deletion failure'; END $$")
			seed("CREATE TRIGGER reject_delete BEFORE " + event + " ON " + failTable + " FOR EACH ROW" + condition + " EXECUTE FUNCTION reject_kb_delete_write()")
		}
	}
	switch v {
	case "audit-missing":
		seed("ALTER TABLE audit_logs RENAME TO hidden_audit_logs")
	case "source-list-failure":
		seed("ALTER TABLE data_sources RENAME TO hidden_data_sources")
	case "kb-table-missing":
		seed("ALTER TABLE knowledge_bases RENAME TO hidden_knowledge_bases")
	}
	machine := strings.HasPrefix(v, "api-")
	scope := types.TenantAPIKeyScope{Capabilities: types.StringArray{"manage_kbs"}, KnowledgeBaseIDs: []string{"kb"}}
	if machine {
		scope.KeyID = 17
		scope.Name = "Fixture key"
		identity, e := repo.NewUserRepository(db).GetUserByTenantID(context.Background(), tenant)
		if e != nil || identity == nil {
			user = fmt.Sprintf("system-%d", tenant)
		} else {
			user = identity.ID
		}
		role = "viewer"
	}
	if v == "api-full" || v == "api-full-wrong-kb" {
		scope.FullAccess = true
		role = "owner"
	}
	if v == "api-retrieve" {
		scope.Capabilities = types.StringArray{"retrieve"}
	}
	if v == "api-ingest" {
		scope.Capabilities = types.StringArray{"ingest"}
	}
	if strings.HasSuffix(v, "wrong-kb") {
		scope.KnowledgeBaseIDs = []string{"peer"}
	}
	redis, e := miniredis.Run()
	must(e)
	defer redis.Close()
	client := asynq.NewClient(asynq.RedisClientOpt{Addr: redis.Addr()})
	defer client.Close()
	enqueuer := &capture{client: client, rows: []any{}, fail: v == "enqueue-failure"}
	inspect := &inspector{db: db, calls: []any{}, fail: v == "queue-failure"}
	audit := svc.NewAuditLogService(repo.NewAuditLogRepository(db))
	shares := svc.NewKBShareService(repo.NewKBShareRepository(db), repo.NewOrganizationRepository(db), nil, nil, nil, audit)
	agents := svc.NewAgentShareService(repo.NewAgentShareRepository(db), repo.NewTenantDisabledSharedAgentRepository(db), repo.NewOrganizationRepository(db), repo.NewCustomAgentRepository(db), nil, nil, nil)
	kbs := svc.NewKnowledgeBaseService(repo.NewKnowledgeBaseRepository(db), repo.NewKnowledgeRepository(db), repo.NewChunkRepository(db), repo.NewKBShareRepository(db), shares, nil, nil, nil, repo.NewTenantRepository(db), nil, nil, nil, enqueuer, inspect, repo.NewTaskPendingOpsRepository(db), repo.NewDataSourceRepository(db), repo.NewSyncLogRepository(db), nil, audit, nil, nil)
	enabled := true
	cfg := &config.Config{Tenant: &config.TenantConfig{EnableRBAC: &enabled}}
	h := handler.NewKnowledgeBaseHandler(cfg, kbs, nil, shares, agents, nil, nil, nil, nil, nil, nil)
	router := gin.New()
	router.Use(middleware.ErrorHandler(), middleware.AuditServiceProvider(audit))
	router.Use(func(c *gin.Context) {
		ctx := context.WithValue(c.Request.Context(), types.TenantIDContextKey, tenant)
		ctx = context.WithValue(ctx, types.UserIDContextKey, user)
		ctx = context.WithValue(ctx, types.TenantRoleContextKey, types.TenantRole(role))
		var info types.Tenant
		must(db.Where("id = ?", tenant).First(&info).Error)
		ctx = context.WithValue(ctx, types.TenantInfoContextKey, &info)
		if machine {
			ctx = types.WithTenantAPIKeyScope(ctx, scope)
		}
		c.Request = c.Request.WithContext(ctx)
		c.Set(types.TenantIDContextKey.String(), tenant)
		c.Set(types.UserIDContextKey.String(), user)
		c.Next()
	})
	path := "/api/v1/knowledge-bases/:id"
	gate := middleware.NewAPIKeyRouteAuthorizer()
	gate.Register("DELETE", path, middleware.APIKeyRoutePolicy{RequireFullAccess: true}.WithCapability(types.APIKeyCapabilityManageKnowledgeBases))
	router.DELETE(path, gate.Middleware(), middleware.RequireOwnershipOrRole(types.TenantRoleAdmin, h.KBCreatorLookup, cfg), middleware.RequireKBAccess(middleware.KBIDFromParam("id"), types.OrgRoleEditor, kbs, shares, agents, cfg), h.DeleteKnowledgeBase)
	target := "/api/v1/knowledge-bases/kb"
	if v == "agent-query" {
		target += "?agent_id=ignored&agent_source_tenant_id=invalid"
	}
	statuses := []int{}
	var body any
	for n := 0; n < 1+func() int {
		if v == "repeat" {
			return 1
		}
		return 0
	}(); n++ {
		w := httptest.NewRecorder()
		router.ServeHTTP(w, httptest.NewRequest("DELETE", target, nil))
		statuses = append(statuses, w.Code)
		dec := json.NewDecoder(bytes.NewReader(w.Body.Bytes()))
		dec.UseNumber()
		must(dec.Decode(&body))
	}
	state := map[string]any{}
	queries := map[string]string{
		"bases":     "SELECT id,tenant_id,CASE WHEN deleted_at IS NULL THEN 0 ELSE 1 END AS deleted,CASE WHEN updated_at='2026-01-01 00:00:00+00:00' THEN 0 ELSE 1 END AS updated FROM knowledge_bases ORDER BY id",
		"shares":    "SELECT id,CASE WHEN deleted_at IS NULL THEN 0 ELSE 1 END AS deleted FROM kb_shares ORDER BY id",
		"sources":   "SELECT id,CASE WHEN deleted_at IS NULL THEN 0 ELSE 1 END AS deleted,CASE WHEN updated_at='2026-01-01 00:00:00+00:00' THEN 0 ELSE 1 END AS updated FROM data_sources ORDER BY id",
		"logs":      "SELECT id,status,error_message,CASE WHEN finished_at IS NULL THEN 0 ELSE 1 END AS finished,CASE WHEN updated_at='2026-01-01 00:00:00+00:00' THEN 0 ELSE 1 END AS updated FROM sync_logs ORDER BY id",
		"pending":   "SELECT scope,scope_id,task_type FROM task_pending_ops ORDER BY scope,scope_id",
		"documents": "SELECT id,parse_status,storage_size,CASE WHEN deleted_at IS NULL THEN 0 ELSE 1 END AS deleted FROM knowledges ORDER BY id",
		"chunks":    "SELECT id,content,CASE WHEN deleted_at IS NULL THEN 0 ELSE 1 END AS deleted FROM chunks ORDER BY id",
		"tenants":   "SELECT id,storage_used FROM tenants ORDER BY id",
	}
	for key, q := range queries {
		if v == "kb-table-missing" && key == "bases" {
			q = strings.Replace(q, "FROM knowledge_bases", "FROM hidden_knowledge_bases", 1)
		}
		if v == "source-list-failure" && key == "sources" {
			q = strings.Replace(q, "FROM data_sources", "FROM hidden_data_sources", 1)
		}
		var rows []map[string]any
		must(db.Raw(q).Scan(&rows).Error)
		state[key] = rows
	}
	at := "audit_logs"
	if v == "audit-missing" {
		at = "hidden_audit_logs"
	}
	var audits []map[string]any
	must(db.Raw("SELECT tenant_id,actor_user_id,actor_role,action,scope_type,scope_id,target_type,target_id,request_path,request_method,outcome,details FROM " + at + " ORDER BY id").Scan(&audits).Error)
	for _, a := range audits {
		var x any
		switch value := a["details"].(type) {
		case string:
			must(json.Unmarshal([]byte(value), &x))
		case []byte:
			must(json.Unmarshal(value, &x))
		}
		a["details"] = x
	}
	return map[string]any{"name": v, "seed_statements": seeds, "caller_tenant": tenant, "user": user, "effective_role": role, "machine": machine, "scope": scope, "target": target, "statuses": statuses, "body": body, "state": state, "audits": audits, "tasks": enqueuer.rows, "scrubs": inspect.calls, "driver": driver}
}
