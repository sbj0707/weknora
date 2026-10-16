//go:build ignore

// Export the production move-target handler, middleware, services and repositories.
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
	"github.com/gin-gonic/gin"
	"gorm.io/driver/postgres"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
	gl "gorm.io/gorm/logger"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

func must(e error) {
	if e != nil {
		panic(e)
	}
}
func quote(s string) string { return "'" + strings.ReplaceAll(s, "'", "''") + "'" }
func main() {
	time.Local = time.UTC
	logger.SetOutput(os.Stderr)
	gin.SetMode(gin.TestMode)
	out := []any{}
	for _, variant := range []string{"owner", "blob-config", "legacy-provider", "viewer", "unknown-role", "no-user", "api-retrieve", "api-full", "api-ingest", "api-wrong-kb", "api-full-wrong-kb", "shared-viewer", "shared-editor", "shared-revoked", "missing-kb", "deleted-kb", "foreign-source", "temporary-source", "faq-source", "empty", "pins", "pins-nanoseconds", "pins-other-user", "pins-other-tenant", "pins-missing", "documents-missing", "chunks-missing", "users-missing", "stores-missing", "kb-table-missing", "agent-query"} {
		out = append(out, run(variant))
	}
	b, e := json.MarshalIndent(out, "", "  ")
	must(e)
	must(os.WriteFile(os.Args[1], append(b, '\n'), 0600))
	fmt.Println("exported", len(out), "actual Go move-target HTTP cases")
}
func run(variant string) any {
	dir, e := os.MkdirTemp("", "weknora-go-move-targets-")
	must(e)
	defer os.RemoveAll(dir)
	dsn := os.Getenv("WEKNORA_MOVE_TARGETS_PG_DSN")
	var dialect gorm.Dialector = sqlite.Open(filepath.Join(dir, "case.db"))
	if dsn != "" {
		u, e := url.Parse(dsn)
		must(e)
		if u.Hostname() != "127.0.0.1" || u.Path != "/weknora_move_targets_parity" {
			panic("Owned loopback move-target database required")
		}
		dialect = postgres.Open(dsn)
	}
	db, e := gorm.Open(dialect, &gorm.Config{Logger: gl.Default.LogMode(gl.Silent)})
	must(e)
	sql, e := db.DB()
	must(e)
	defer sql.Close()
	sql.SetMaxOpenConns(1)
	tables := []string{"knowledge_bases", "knowledges", "chunks", "user_kb_pins", "users", "vector_stores"}
	if dsn == "" {
		for _, m := range []string{"000000_init", "000002_knowledge_folder_path", "000003_knowledge_base_auto_tag_config", "000004_memory", "000007_system_admin_and_settings", "000008_processing_spans_and_pending_subtasks", "000010_knowledge_multi_tags", "000011_principal_model", "000021_knowledge_profiles", "000031_model_catalog_config", "000032_chunk_images", "000033_chunk_source_locators"} {
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
		must(db.Exec("TRUNCATE tenants,knowledge_bases,knowledges,chunks,user_kb_pins,users,vector_stores,organizations,organization_tenant_members,kb_shares,audit_logs RESTART IDENTITY CASCADE").Error)
	}
	seeds := []string{}
	seed := func(q string) { must(db.Exec(q).Error); seeds = append(seeds, q) }
	seed("INSERT INTO tenants(id,name,business) VALUES(1,'Owner','test'),(2,'Other','test')")
	seed("INSERT INTO users(id,username,email,password_hash,tenant_id,is_active) VALUES('caller','Caller','caller@example.test','unused',1,true),('other','Other','other@example.test','unused',1,true)")
	for i, id := range []string{"source", "older", "newer", "different-model", "different-type", "temporary", "deleted", "foreign", "bound-store", "cloud-storage", "wiki"} {
		tenant := 1
		kind, model := "document", "embedding"
		temporary := false
		if id == "different-model" {
			model = "other"
		}
		if id == "different-type" {
			kind = "faq"
		}
		if id == "temporary" {
			temporary = true
		}
		if id == "foreign" {
			tenant = 2
		}
		seed(fmt.Sprintf("INSERT INTO knowledge_bases(id,tenant_id,creator_id,name,type,embedding_model_id,summary_model_id,is_temporary,created_at,updated_at,cos_config,vlm_config) VALUES(%s,%d,'other',%s,%s,%s,'summary',%t,'2026-01-%02d 00:00:00+00:00','2026-01-01 00:00:00+00:00','{\"secret_id\":\"hidden-id\",\"secret_key\":\"hidden-key\"}','{\"api_key\":\"hidden-vlm\"}')", quote(id), tenant, quote(id), quote(kind), quote(model), temporary, i+1))
	}
	seed("UPDATE knowledge_bases SET deleted_at='2026-02-01 00:00:00+00:00' WHERE id='deleted'")
	seed("UPDATE knowledge_bases SET vector_store_id='unresolved-store',storage_backend_id='different-backend' WHERE id='bound-store'")
	seed("UPDATE knowledge_bases SET storage_provider_config='{\"provider\":\"cos\"}' WHERE id='cloud-storage'")
	seed("UPDATE knowledge_bases SET indexing_strategy='{\"vector_enabled\":true,\"keyword_enabled\":true,\"wiki_enabled\":true,\"graph_enabled\":false}' WHERE id='wiki'")
	for i, status := range []string{"completed", "pending", "processing", "failed", "deleting", "completed"} {
		seed(fmt.Sprintf("INSERT INTO knowledges(id,tenant_id,knowledge_base_id,type,title,source,parse_status) VALUES('d%d',1,'newer','file','Title','manual',%s)", i, quote(status)))
	}
	seed("UPDATE knowledges SET deleted_at='2026-02-01 00:00:00+00:00' WHERE id='d5'")
	seed("INSERT INTO knowledges(id,tenant_id,knowledge_base_id,type,title,source,parse_status) VALUES('foreign-doc',2,'newer','file','Title','manual','pending')")
	seed("INSERT INTO chunks(id,tenant_id,knowledge_id,knowledge_base_id,content,chunk_index,start_at,end_at) VALUES('chunk',1,'d0','different-type','FAQ',0,0,3),('deleted-chunk',1,'d0','different-type','Deleted',1,0,7),('foreign-chunk',2,'d0','different-type','Foreign',2,0,7)")
	seed("UPDATE chunks SET deleted_at='2026-02-01 00:00:00+00:00' WHERE id='deleted-chunk'")
	if variant == "blob-config" {
		configs := map[string]string{
			"chunking_config":         `{"chunk_size":321,"chunk_overlap":12,"separators":["\n"]}`,
			"indexing_strategy":       `{"vector_enabled":true,"keyword_enabled":true,"wiki_enabled":true,"graph_enabled":true}`,
			"extract_config":          `{"enabled":true,"text":"Extract","tags":["tag"]}`,
			"cos_config":              `{"provider":"cos","secret_id":"hidden-id","secret_key":"hidden-key","bucket_name":"bucket"}`,
			"storage_provider_config": `{"provider":"cos"}`,
			"vlm_config":              `{"api_key":"hidden-vlm","enabled":true,"model_id":"vision"}`,
		}
		fields := []string{}
		for field := range configs {
			fields = append(fields, field)
		}
		sort.Strings(fields)
		for _, field := range fields {
			literal := quote(configs[field])
			if dsn == "" {
				literal = "CAST(" + literal + " AS BLOB)"
			}
			seed("UPDATE knowledge_bases SET " + field + "=" + literal)
		}
	}
	if variant == "legacy-provider" {
		literal := quote(`{"provider":"cos","secret_id":"hidden-id","secret_key":"hidden-key"}`)
		if dsn == "" {
			literal = "CAST(" + literal + " AS BLOB)"
		}
		seed("UPDATE knowledge_bases SET cos_config=" + literal + ",storage_provider_config=NULL")
	}
	tenant := uint64(1)
	user, role := "caller", "owner"
	if variant == "viewer" {
		role = "viewer"
	}
	if variant == "unknown-role" {
		role = "unknown"
	}
	if variant == "no-user" {
		user = ""
	}
	if strings.HasPrefix(variant, "shared-") {
		tenant = 2
		seed("INSERT INTO organizations(id,name,owner_tenant_id,owner_id) VALUES('org','Org',1,'caller')")
		seed("INSERT INTO organization_tenant_members(id,organization_id,tenant_id,role) VALUES('src','org',1,'admin'),('dst','org',2,'editor')")
		permission := "viewer"
		if variant == "shared-editor" {
			permission = "editor"
		}
		seed("INSERT INTO kb_shares(id,knowledge_base_id,organization_id,source_tenant_id,permission,shared_by_user_id) VALUES('share','source','org',1," + quote(permission) + ",'caller')")
		if variant == "shared-revoked" {
			seed("UPDATE kb_shares SET deleted_at='2026-02-01 00:00:00+00:00'")
		}
	}
	switch variant {
	case "missing-kb":
		seed("DELETE FROM knowledge_bases WHERE id='source'")
	case "deleted-kb":
		seed("UPDATE knowledge_bases SET deleted_at='2026-02-01 00:00:00+00:00' WHERE id='source'")
	case "foreign-source":
		tenant = 2
	case "temporary-source":
		seed("UPDATE knowledge_bases SET is_temporary=true WHERE id='source'")
	case "faq-source":
		seed("UPDATE knowledge_bases SET type='faq' WHERE id='source'")
	case "empty":
		seed("UPDATE knowledge_bases SET embedding_model_id='none' WHERE id='source'")
	case "pins", "pins-nanoseconds", "pins-other-user", "pins-other-tenant":
		pinUser := "caller"
		pinTenant := 1
		if variant == "pins-other-user" {
			pinUser = "other"
		}
		if variant == "pins-other-tenant" {
			pinTenant = 2
		}
		a, b := "2026-03-01 00:00:00+00:00", "2026-03-02 00:00:00+00:00"
		// PostgreSQL timestamps have microsecond precision; use a separate explicit nanos case on SQLite.
		if variant == "pins-nanoseconds" {
			a = "2026-03-01 00:00:00.123456+00:00"
			b = "2026-03-01 00:00:00.123455+00:00"
			if dsn == "" {
				a = "2026-03-01 00:00:00.123456789+00:00"
				b = "2026-03-01 00:00:00.123456788+00:00"
			}
		}
		seed(fmt.Sprintf("INSERT INTO user_kb_pins(tenant_id,user_id,kb_id,pinned_at) VALUES(%d,%s,'older',%s),(%d,%s,'newer',%s)", pinTenant, quote(pinUser), quote(a), pinTenant, quote(pinUser), quote(b)))
	case "pins-missing":
		seed("ALTER TABLE user_kb_pins RENAME TO hidden_user_kb_pins")
	case "documents-missing":
		seed("ALTER TABLE knowledges RENAME TO hidden_knowledges")
	case "chunks-missing":
		seed("ALTER TABLE chunks RENAME TO hidden_chunks")
	case "users-missing":
		seed("ALTER TABLE users RENAME TO hidden_users")
	case "stores-missing":
		seed("ALTER TABLE vector_stores RENAME TO hidden_vector_stores")
	case "kb-table-missing":
		seed("ALTER TABLE knowledge_bases RENAME TO hidden_knowledge_bases")
	}
	machine := strings.HasPrefix(variant, "api-")
	scope := types.TenantAPIKeyScope{Capabilities: types.StringArray{"retrieve"}, KnowledgeBaseIDs: []string{"source"}}
	if variant == "api-ingest" {
		scope.Capabilities = types.StringArray{"ingest"}
	}
	if strings.Contains(variant, "full") {
		scope.FullAccess = true
	}
	if strings.HasSuffix(variant, "wrong-kb") {
		scope.KnowledgeBaseIDs = []string{"newer"}
	}
	audit := svc.NewAuditLogService(repo.NewAuditLogRepository(db))
	shares := svc.NewKBShareService(repo.NewKBShareRepository(db), repo.NewOrganizationRepository(db), nil, nil, nil, audit)
	agents := svc.NewAgentShareService(repo.NewAgentShareRepository(db), repo.NewTenantDisabledSharedAgentRepository(db), repo.NewOrganizationRepository(db), repo.NewCustomAgentRepository(db), nil, nil, nil)
	kbs := svc.NewKnowledgeBaseService(repo.NewKnowledgeBaseRepository(db), repo.NewKnowledgeRepository(db), repo.NewChunkRepository(db), nil, shares, nil, nil, nil, repo.NewTenantRepository(db), nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil)
	enabled := true
	cfg := &config.Config{Tenant: &config.TenantConfig{EnableRBAC: &enabled}}
	h := handler.NewKnowledgeBaseHandler(cfg, kbs, nil, shares, agents, nil, nil, nil, nil, nil, nil)
	router := gin.New()
	router.Use(middleware.ErrorHandler(), middleware.AuditServiceProvider(audit))
	router.Use(func(c *gin.Context) {
		ctx := context.WithValue(c.Request.Context(), types.TenantIDContextKey, tenant)
		ctx = context.WithValue(ctx, types.UserIDContextKey, user)
		ctx = context.WithValue(ctx, types.TenantRoleContextKey, types.TenantRole(role))
		if machine {
			ctx = types.WithTenantAPIKeyScope(ctx, scope)
		}
		c.Request = c.Request.WithContext(ctx)
		c.Set(types.TenantIDContextKey.String(), tenant)
		c.Set(types.UserIDContextKey.String(), user)
		c.Next()
	})
	gate := middleware.NewAPIKeyRouteAuthorizer()
	path := "/api/v1/knowledge-bases/:id/move-targets"
	gate.Register("GET", path, middleware.APIKeyRoutePolicy{RequireFullAccess: true}.WithCapability(types.APIKeyCapabilityRetrieve))
	router.GET(path, gate.Middleware(), middleware.RequireRole(types.TenantRoleViewer, cfg), middleware.RequireKBAccess(middleware.KBIDFromParam("id"), types.OrgRoleViewer, kbs, shares, agents, cfg), h.ListMoveTargets)
	target := "/api/v1/knowledge-bases/source/move-targets"
	if variant == "agent-query" {
		target += "?agent_id=ignored&agent_source_tenant_id=bad"
	}
	w := httptest.NewRecorder()
	router.ServeHTTP(w, httptest.NewRequest("GET", target, nil))
	var body any
	dec := json.NewDecoder(bytes.NewReader(w.Body.Bytes()))
	dec.UseNumber()
	must(dec.Decode(&body))
	var audits []map[string]any
	must(db.Raw("SELECT tenant_id,actor_user_id,actor_role,action,request_path,request_method,outcome,details FROM audit_logs ORDER BY id").Scan(&audits).Error)
	for _, a := range audits {
		switch v := a["details"].(type) {
		case string:
			var obj any
			must(json.Unmarshal([]byte(v), &obj))
			a["details"] = obj
		case []byte:
			var obj any
			must(json.Unmarshal(v, &obj))
			a["details"] = obj
		}
	}
	return map[string]any{"name": variant, "variant": variant, "target": target, "seed_statements": seeds, "body": body, "status": w.Code, "caller_tenant": tenant, "user": user, "effective_role": types.TenantRoleFromContext(context.WithValue(context.Background(), types.TenantRoleContextKey, types.TenantRole(role))), "machine": machine, "scope": scope, "audits": audits}
}
