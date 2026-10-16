//go:build ignore

// Actual Go public delete admission, SQL services, middleware and Redis-backed Asynq producer.
package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	repo "github.com/Tencent/WeKnora/internal/application/repository"
	svc "github.com/Tencent/WeKnora/internal/application/service"
	"github.com/Tencent/WeKnora/internal/config"
	handler "github.com/Tencent/WeKnora/internal/handler"
	"github.com/Tencent/WeKnora/internal/logger"
	"github.com/Tencent/WeKnora/internal/middleware"
	"github.com/Tencent/WeKnora/internal/types"
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

type scene struct{ name, operation, target, body string }

func main() {
	time.Local = time.UTC
	logger.SetOutput(os.Stderr)
	gin.SetMode(gin.TestMode)
	cases := []scene{}
	for _, op := range []string{"single", "batch", "clear"} {
		for _, variant := range []string{"ok", "moving", "missing-kb", "deleted-kb", "foreign-document", "deleted-document", "queue-error", "creator-viewer", "creator-contributor", "noncreator-contributor", "noncreator-admin", "shared-editor", "shared-viewer", "shared-revoked", "api-ingest", "api-retrieve", "api-full", "api-wrong-kb", "api-audit", "api-space-kb", "api-full-wrong-kb", "temporary", "moving-copy", "moving-phase-only", "moving-casefold", "moving-null-operation", "moving-duplicate-null", "moving-invalid-operation", "moving-invalid-metadata", "moving-root-null", "moving-root-array", "moving-outer-casefold"} {
			target := "/api/v1/knowledge/doc"
			body := ""
			if op == "batch" {
				target = "/api/v1/knowledge/batch-delete"
				body = `{"kb_id":"kb","ids":["doc","second"]}`
			}
			if op == "clear" {
				target = "/api/v1/knowledge-bases/kb/knowledge"
			}
			cases = append(cases, scene{op + "-" + variant, op, target, body})
		}
	}
	for _, v := range []struct{ name, body string }{
		{"empty", ""}, {"null", "null"}, {"object", "{}"}, {"array", "[]"}, {"incomplete", "{"}, {"unknown", `{"unused":true}`},
		{"empty-ids", `{"kb_id":"kb","ids":[]}`}, {"null-ids", `{"kb_id":"kb","ids":null}`}, {"blank-ids", `{"kb_id":"kb","ids":[" ","",null]}`},
		{"dedupe", `{"kb_id":"kb","ids":[" second ","doc","second",""," doc "]}`},
		{"wrong-kb", `{"kb_id":"kb","ids":["other"]}`}, {"missing-id", `{"kb_id":"kb","ids":["doc","absent"]}`},
		{"string-ids", `{"kb_id":"kb","ids":"doc"}`}, {"number-id", `{"kb_id":"kb","ids":[4]}`}, {"number-kb", `{"kb_id":4,"ids":["doc"]}`},
		{"fold", `{"KB_ID":"kb","IDS":["doc"]}`}, {"trailing", `{"kb_id":"kb","ids":["doc"]}garbage`},
		{"duplicate", `{"kb_id":"kb","ids":["second"],"ids":["doc"]}`}, {"duplicate-null", `{"kb_id":"kb","ids":["doc"],"ids":null}`},
		{"control-kb", `{"kb_id":"k\u0000b","ids":["doc"]}`}, {"space-kb", `{"kb_id":" kb ","ids":["doc"]}`}, {"duplicate-null-kb", `{"kb_id":"kb","kb_id":null,"ids":["doc"]}`},
		{"first-type-error", `{"kb_id":4,"kb_id":"kb","ids":["doc"]}`}, {"unicode-trim", `{"kb_id":"kb","ids":["\u3000doc\u00a0"]}`},
	} {
		cases = append(cases, scene{"batch-bind-" + v.name, "batch", "/api/v1/knowledge/batch-delete", v.body})
	}
	cases = append(cases, scene{"single-absent", "single", "/api/v1/knowledge/absent", ""}, scene{"clear-empty", "clear", "/api/v1/knowledge-bases/empty/knowledge", ""})
	ids := []string{}
	for i := 0; i < 201; i++ {
		ids = append(ids, fmt.Sprintf("entry-%d", i))
	}
	b, e := json.Marshal(map[string]any{"kb_id": "kb", "ids": ids})
	must(e)
	cases = append(cases, scene{"batch-bind-limit", "batch", "/api/v1/knowledge/batch-delete", string(b)})
	out := []any{}
	for _, s := range cases {
		out = append(out, run(s))
	}
	b, e = json.Marshal(out)
	must(e)
	must(os.WriteFile(os.Args[1], append(b, '\n'), 0600))
	fmt.Println("exported", len(out), "actual Go delete admission scenes")
}
func run(s scene) any {
	dir, e := os.MkdirTemp("", "weknora-go-public-delete-")
	must(e)
	defer os.RemoveAll(dir)
	var dialect gorm.Dialector = sqlite.Open(filepath.Join(dir, "case.db"))
	pgDSN := os.Getenv("WEKNORA_DELETE_PARITY_PG_DSN")
	if pgDSN != "" {
		u, e := url.Parse(pgDSN)
		must(e)
		if u.Hostname() != "127.0.0.1" || u.Path != "/weknora_delete_parity" {
			panic("Owned delete parity database required")
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
		for _, m := range []string{"000000_init", "000003_knowledge_base_auto_tag_config", "000004_memory", "000007_system_admin_and_settings", "000008_processing_spans_and_pending_subtasks", "000010_knowledge_multi_tags", "000011_principal_model", "000021_knowledge_profiles", "000031_model_catalog_config", "000033_chunk_source_locators"} {
			b, e := os.ReadFile("migrations/sqlite/" + m + ".up.sql")
			must(e)
			must(db.Exec(string(b)).Error)
		}
	} else {
		must(db.Exec("TRUNCATE tenants,knowledge_bases,knowledges,tenant_api_keys,chunks,organizations,organization_tenant_members,kb_shares,audit_logs RESTART IDENTITY CASCADE").Error)
	}
	must(db.Exec("INSERT INTO tenants(id,name,business) VALUES(1,'Owner','test'),(2,'Caller','test')").Error)
	must(db.Exec("INSERT INTO knowledge_bases(id,tenant_id,creator_id,name,type,embedding_model_id,summary_model_id) VALUES('kb',1,'caller','KB','document','',''),('other-kb',1,'other','Other','document','',''),('empty',1,'caller','Empty','document','','')").Error)
	for i, id := range []string{"doc", "second", "other"} {
		kb := "kb"
		if id == "other" {
			kb = "other-kb"
		}
		stamp := time.Date(2026, 1, 1, 0, i, 0, 0, time.UTC)
		must(db.Exec("INSERT INTO knowledges(id,tenant_id,knowledge_base_id,type,title,source,parse_status,created_at,updated_at) VALUES(?,1,?,'file','Document','manual','completed',?,?)", id, kb, stamp, stamp).Error)
	}
	if strings.HasSuffix(s.name, "-moving") {
		must(db.Exec(`UPDATE knowledges SET metadata=? WHERE id='doc'`, `{"_knowledge_transfer":{"operation":"move","phase":"moving"}}`).Error)
	}
	if strings.HasSuffix(s.name, "-moving-copy") {
		must(db.Exec("UPDATE knowledges SET metadata=? WHERE id='doc'", []byte(`{"_knowledge_transfer":{"operation":"copy","phase":"moving"}}`)).Error)
	}
	if strings.HasSuffix(s.name, "-moving-phase-only") {
		must(db.Exec("UPDATE knowledges SET metadata=? WHERE id='doc'", []byte(`{"_knowledge_transfer":{"phase":"moving"}}`)).Error)
	}
	if strings.HasSuffix(s.name, "-moving-casefold") {
		must(db.Exec("UPDATE knowledges SET metadata=? WHERE id='doc'", []byte(`{"_knowledge_transfer":{"OPERATION":"move","PHASE":"moving"}}`)).Error)
	}
	if strings.HasSuffix(s.name, "-moving-null-operation") {
		must(db.Exec("UPDATE knowledges SET metadata=? WHERE id='doc'", []byte(`{"_knowledge_transfer":{"operation":null,"phase":"moving"}}`)).Error)
	}
	if strings.HasSuffix(s.name, "-moving-duplicate-null") {
		must(db.Exec("UPDATE knowledges SET metadata=? WHERE id='doc'", []byte(`{"_knowledge_transfer":{"operation":"move","operation":null,"phase":"moving"}}`)).Error)
	}
	if strings.HasSuffix(s.name, "-moving-invalid-operation") {
		must(db.Exec("UPDATE knowledges SET metadata=? WHERE id='doc'", []byte(`{"_knowledge_transfer":{"operation":4,"operation":"move","phase":"moving"}}`)).Error)
	}
	if strings.HasSuffix(s.name, "-moving-invalid-metadata") {
		must(db.Exec("UPDATE knowledges SET metadata=? WHERE id='doc'", []byte(`{"_knowledge_transfer":4}`)).Error)
	}
	if strings.HasSuffix(s.name, "-moving-root-null") {
		must(db.Exec("UPDATE knowledges SET metadata=? WHERE id='doc'", []byte(`null`)).Error)
	}
	if strings.HasSuffix(s.name, "-moving-root-array") {
		must(db.Exec("UPDATE knowledges SET metadata=? WHERE id='doc'", []byte(`[]`)).Error)
	}
	if strings.HasSuffix(s.name, "-moving-outer-casefold") {
		must(db.Exec("UPDATE knowledges SET metadata=? WHERE id='doc'", []byte(`{"_KNOWLEDGE_TRANSFER":{"operation":"move","phase":"moving"}}`)).Error)
	}
	if strings.HasSuffix(s.name, "-missing-kb") {
		must(db.Exec("DELETE FROM knowledge_bases WHERE id='kb'").Error)
	}
	if strings.HasSuffix(s.name, "-deleted-kb") {
		must(db.Exec("UPDATE knowledge_bases SET deleted_at=CURRENT_TIMESTAMP WHERE id='kb'").Error)
	}
	if strings.HasSuffix(s.name, "-foreign-document") {
		must(db.Exec("UPDATE knowledges SET tenant_id=2 WHERE id='doc'").Error)
	}
	if strings.HasSuffix(s.name, "-deleted-document") {
		must(db.Exec("UPDATE knowledges SET deleted_at=CURRENT_TIMESTAMP WHERE id='doc'").Error)
	}
	if strings.HasSuffix(s.name, "-temporary") {
		must(db.Exec("UPDATE knowledge_bases SET is_temporary=true WHERE id='kb'").Error)
	}
	audit := svc.NewAuditLogService(repo.NewAuditLogRepository(db))
	shares := svc.NewKBShareService(repo.NewKBShareRepository(db), repo.NewOrganizationRepository(db), nil, nil, nil, audit)
	agents := svc.NewAgentShareService(repo.NewAgentShareRepository(db), repo.NewTenantDisabledSharedAgentRepository(db), repo.NewOrganizationRepository(db), repo.NewCustomAgentRepository(db), nil, nil, nil)
	br, kr, cr := repo.NewKnowledgeBaseRepository(db), repo.NewKnowledgeRepository(db), repo.NewChunkRepository(db)
	kbs := svc.NewKnowledgeBaseService(br, kr, cr, nil, shares, nil, nil, nil, repo.NewTenantRepository(db), nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil)
	kg, e := svc.NewKnowledgeService(&config.Config{}, kr, nil, kbs, nil, nil, nil, cr, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, shares, nil, nil, nil, nil, nil, audit)
	must(e)
	redis, e := miniredis.Run()
	must(e)
	defer redis.Close()
	client := asynq.NewClient(asynq.RedisClientOpt{Addr: redis.Addr()})
	defer client.Close()
	captured := &capture{client: client, rows: []any{}, fail: strings.HasSuffix(s.name, "-queue-error")}
	enabled := true
	cfg := &config.Config{Tenant: &config.TenantConfig{EnableRBAC: &enabled}}
	h := handler.NewKnowledgeHandler(cfg, kg, kbs, shares, agents, nil, nil, nil, captured, nil, nil)
	role, user, tenant := types.TenantRoleAdmin, "caller", uint64(1)
	if strings.HasSuffix(s.name, "-creator-viewer") {
		role = types.TenantRoleViewer
	}
	if strings.HasSuffix(s.name, "-creator-contributor") {
		role = types.TenantRoleContributor
	}
	if strings.Contains(s.name, "-noncreator-") {
		user = "intruder"
		if strings.HasSuffix(s.name, "-contributor") {
			role = types.TenantRoleContributor
		}
	}
	if strings.Contains(s.name, "-shared-") {
		tenant = 2
		permission := "editor"
		if strings.HasSuffix(s.name, "-viewer") {
			permission = "viewer"
		}
		must(db.Exec("INSERT INTO organizations(id,name,owner_tenant_id,owner_id) VALUES('org','Org',1,'caller')").Error)
		must(db.Exec("INSERT INTO organization_tenant_members(id,organization_id,tenant_id,role) VALUES('source','org',1,'admin'),('member','org',2,'editor')").Error)
		must(db.Exec("INSERT INTO kb_shares(id,knowledge_base_id,organization_id,source_tenant_id,permission,shared_by_user_id) VALUES('share','kb','org',1,?,'caller')", permission).Error)
		if strings.HasSuffix(s.name, "-revoked") {
			must(db.Exec("UPDATE kb_shares SET deleted_at=CURRENT_TIMESTAMP").Error)
		}
	}
	machine := strings.Contains(s.name, "-api-")
	scope := types.TenantAPIKeyScope{Capabilities: types.StringArray{"ingest"}, KnowledgeBaseIDs: []string{"kb"}}
	if strings.HasSuffix(s.name, "-retrieve") {
		scope.Capabilities = types.StringArray{"retrieve"}
	}
	if strings.Contains(s.name, "-api-full") {
		scope.FullAccess = true
	}
	if strings.HasSuffix(s.name, "-wrong-kb") {
		scope.KnowledgeBaseIDs = []string{"other-kb"}
	}
	if strings.HasSuffix(s.name, "-audit") {
		scope.KeyID = 77
		scope.Name = "Automation"
		user = "system-1"
	}
	router := gin.New()
	router.Use(middleware.ErrorHandler(), middleware.AuditServiceProvider(audit))
	router.Use(func(c *gin.Context) {
		ctx := context.WithValue(c.Request.Context(), types.TenantIDContextKey, tenant)
		ctx = context.WithValue(ctx, types.UserIDContextKey, user)
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
	gate.Register("DELETE", "/api/v1/knowledge/:id", middleware.APIKeyRoutePolicy{RequireFullAccess: true}.WithCapability(types.APIKeyCapabilityIngest))
	gate.Register("POST", "/api/v1/knowledge/batch-delete", middleware.APIKeyRoutePolicy{RequireFullAccess: true}.WithCapability(types.APIKeyCapabilityIngest))
	gate.Register("DELETE", "/api/v1/knowledge-bases/:id/knowledge", middleware.APIKeyRoutePolicy{RequireFullAccess: true})
	router.DELETE("/api/v1/knowledge/:id", gate.Middleware(), middleware.RequireOwnershipOrRole(types.TenantRoleAdmin, h.KBCreatorLookupFromKnowledgeID, cfg), middleware.RequireKBAccess(middleware.KBIDFromKnowledgeIDParam("id", kg), types.OrgRoleEditor, kbs, shares, agents, cfg), h.DeleteKnowledge)
	router.POST("/api/v1/knowledge/batch-delete", gate.Middleware(), middleware.RequireRole(types.TenantRoleContributor, cfg), h.BatchDeleteKnowledge)
	router.DELETE("/api/v1/knowledge-bases/:id/knowledge", gate.Middleware(), middleware.RequireRole(types.TenantRoleAdmin, cfg), middleware.RequireKBAccess(middleware.KBIDFromParam("id"), types.OrgRoleEditor, kbs, shares, agents, cfg), h.ClearKnowledgeBaseContents)
	if s.name == "batch-api-space-kb" {
		s.body = `{"kb_id":" kb ","ids":["doc"]}`
	}
	method := "DELETE"
	if s.operation == "batch" {
		method = "POST"
	}
	r := httptest.NewRequest(method, s.target, strings.NewReader(s.body))
	r.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	router.ServeHTTP(w, r)
	var body any
	dec := json.NewDecoder(bytes.NewReader(w.Body.Bytes()))
	dec.UseNumber()
	must(dec.Decode(&body))
	var docs []map[string]any
	must(db.Table("knowledges").Select("id,tenant_id,knowledge_base_id,parse_status,CASE WHEN deleted_at IS NOT NULL THEN 1 ELSE 0 END AS deleted").Order("id").Find(&docs).Error)
	return map[string]any{"name": s.name, "operation": s.operation, "method": method, "target": s.target, "request": s.body, "status": w.Code, "body": body, "documents": docs, "tasks": captured.rows, "role": role, "user": user, "caller_tenant": tenant, "machine": machine, "scope": scope}
}
