//go:build ignore

// Actual production Go chunk deletion/revisions and downstream SQL state.
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
	"strings"
	"time"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}

type scene struct{ name, operation, variant, target string }

func main() {
	time.Local = time.UTC
	logger.SetOutput(os.Stderr)
	gin.SetMode(gin.TestMode)
	out := []any{}
	variants := []string{"ok", "moving", "moving-copy", "moving-phase-only", "moving-casefold", "moving-null-operation", "moving-duplicate-null", "moving-invalid-operation", "moving-invalid-metadata", "moving-root-null", "moving-root-array", "moving-outer-casefold", "missing-kb", "deleted-kb", "foreign-document", "deleted-document", "foreign-chunk", "deleted-chunk", "wrong-document", "wrong-chunk-kb", "creator-viewer", "creator-contributor", "noncreator-contributor", "noncreator-viewer", "noncreator-admin", "shared-editor", "shared-viewer", "shared-revoked", "api-ingest", "api-retrieve", "api-full", "api-wrong-kb", "api-full-wrong-kb", "unknown-role", "empty-revisions", "revision-table-missing", "write-error"}
	for _, op := range []string{"single", "all", "revisions"} {
		for _, variant := range variants {
			target := "/api/v1/chunks/doc/chunk"
			if op == "all" {
				target = "/api/v1/chunks/doc"
			}
			if op == "revisions" {
				target += "/revisions"
			}
			out = append(out, run(scene{op + "-" + variant, op, variant, target}))
		}
	}
	for _, s := range []scene{{"single-absent-chunk", "single", "", "/api/v1/chunks/doc/absent"}, {"single-absent-document", "single", "", "/api/v1/chunks/absent/chunk"}, {"all-absent-document", "all", "", "/api/v1/chunks/absent"}, {"revisions-absent-chunk", "revisions", "", "/api/v1/chunks/doc/absent/revisions"}, {"single-space-document", "single", "", "/api/v1/chunks/%20doc%20/chunk"}, {"single-space-chunk", "single", "", "/api/v1/chunks/doc/%20chunk%20"}} {
		out = append(out, run(s))
	}
	b, e := json.Marshal(out)
	must(e)
	must(os.WriteFile(os.Args[1], append(b, '\n'), 0600))
	fmt.Println("exported", len(out), "actual Go chunk mutation/read scenes")
}
func run(s scene) any {
	dir, e := os.MkdirTemp("", "weknora-go-chunk-delete-")
	must(e)
	defer os.RemoveAll(dir)
	var dialect gorm.Dialector = sqlite.Open(filepath.Join(dir, "case.db"))
	pgDSN := os.Getenv("WEKNORA_CHUNK_PARITY_PG_DSN")
	if pgDSN != "" {
		u, e := url.Parse(pgDSN)
		must(e)
		if u.Hostname() != "127.0.0.1" || u.Path != "/weknora_chunk_parity" {
			panic("Owned loopback chunk parity database required")
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
		b, e := os.ReadFile("migrations/versioned/000078_chunk_editing_and_custom_metadata.up.sql")
		must(e)
		must(db.Exec(string(b)).Error)
		must(db.Exec("DROP TRIGGER IF EXISTS block_chunk_delete ON chunks").Error)
		must(db.Exec("DROP TABLE IF EXISTS chunk_delete_fts").Error)
		must(db.Exec("TRUNCATE tenants,knowledge_bases,knowledges,chunks,chunk_revisions,organizations,organization_tenant_members,kb_shares,audit_logs RESTART IDENTITY CASCADE").Error)
	}
	must(db.Exec("INSERT INTO tenants(id,name,business) VALUES(1,'Owner','test'),(2,'Caller','test')").Error)
	must(db.Exec("INSERT INTO knowledge_bases(id,tenant_id,creator_id,name,type,embedding_model_id,summary_model_id) VALUES('kb',1,'caller','KB','document','',''),('other-kb',1,'other','Other','document','','')").Error)
	stamp := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	for _, id := range []string{"doc", "other"} {
		must(db.Exec("INSERT INTO knowledges(id,tenant_id,knowledge_base_id,type,title,source,parse_status,created_at,updated_at) VALUES(?,1,'kb','file','Document','manual','completed',?,?)", id, stamp, stamp).Error)
	}
	for i, id := range []string{"chunk", "child", "other", "foreign", "wrong-kb"} {
		tenant := 1
		document, kb, parent, kind := "doc", "kb", "", "text"
		if id == "foreign" {
			tenant = 2
		}
		if id == "other" {
			document = "other"
		}
		if id == "wrong-kb" {
			kb = "other-kb"
		}
		if id == "child" {
			parent = "chunk"
			kind = "image_caption"
		}
		must(db.Exec("INSERT INTO chunks(id,tenant_id,knowledge_id,knowledge_base_id,content,chunk_index,start_at,end_at,is_enabled,chunk_type,parent_chunk_id,status,content_revision,created_at,updated_at) VALUES(?,?,?,?,?,?,0,20,true,?,?,0,3,?,?)", id, tenant, document, kb, "Chunk content "+id, i, kind, parent, stamp, stamp).Error)
	}
	for i := 1; i <= 2; i++ {
		must(db.Exec("INSERT INTO chunk_revisions(id,tenant_id,knowledge_base_id,knowledge_id,chunk_id,revision,content,is_enabled,editor_id,edit_source,edited_at,created_at) VALUES(?,1,'kb','doc','chunk',?,?,?, 'caller','manual',?,?)", fmt.Sprintf("revision-%d", i), i, fmt.Sprintf("Original %d 中文", i), i == 2, stamp, stamp).Error)
	}
	// A real FTS store is retained by these direct soft-delete APIs.
	if pgDSN == "" {
		must(db.Exec("CREATE VIRTUAL TABLE chunk_delete_fts USING fts5(chunk_id UNINDEXED,content)").Error)
	} else {
		must(db.Exec("CREATE TABLE chunk_delete_fts(chunk_id TEXT,content TEXT,search TSVECTOR GENERATED ALWAYS AS (to_tsvector('simple',content)) STORED)").Error)
		must(db.Exec("CREATE INDEX chunk_delete_fts_gin ON chunk_delete_fts USING GIN(search)").Error)
	}
	must(db.Exec("INSERT INTO chunk_delete_fts(chunk_id,content) VALUES('chunk','Original physical content'),('child','Image content')").Error)
	metadata := map[string]string{
		"moving": `{"_knowledge_transfer":{"operation":"move","phase":"moving"}}`, "moving-copy": `{"_knowledge_transfer":{"operation":"copy","phase":"moving"}}`, "moving-phase-only": `{"_knowledge_transfer":{"phase":"moving"}}`, "moving-casefold": `{"_knowledge_transfer":{"OPERATION":"move","PHASE":"moving"}}`, "moving-null-operation": `{"_knowledge_transfer":{"operation":null,"phase":"moving"}}`, "moving-duplicate-null": `{"_knowledge_transfer":{"operation":"move","operation":null,"phase":"moving"}}`, "moving-invalid-operation": `{"_knowledge_transfer":{"operation":4,"operation":"move","phase":"moving"}}`, "moving-invalid-metadata": `{"_knowledge_transfer":4}`, "moving-root-null": `null`, "moving-root-array": `[]`, "moving-outer-casefold": `{"_KNOWLEDGE_TRANSFER":{"operation":"move","phase":"moving"}}`}
	if raw, ok := metadata[s.variant]; ok {
		must(db.Exec("UPDATE knowledges SET metadata=? WHERE id='doc'", []byte(raw)).Error)
	}
	switch s.variant {
	case "missing-kb":
		must(db.Exec("DELETE FROM knowledge_bases WHERE id='kb'").Error)
	case "deleted-kb":
		must(db.Exec("UPDATE knowledge_bases SET deleted_at=CURRENT_TIMESTAMP WHERE id='kb'").Error)
	case "foreign-document":
		must(db.Exec("UPDATE knowledges SET tenant_id=2 WHERE id='doc'").Error)
	case "deleted-document":
		must(db.Exec("UPDATE knowledges SET deleted_at=CURRENT_TIMESTAMP WHERE id='doc'").Error)
	case "foreign-chunk":
		must(db.Exec("UPDATE chunks SET tenant_id=2 WHERE id='chunk'").Error)
	case "deleted-chunk":
		must(db.Exec("UPDATE chunks SET deleted_at=CURRENT_TIMESTAMP WHERE id='chunk'").Error)
	case "wrong-document":
		must(db.Exec("UPDATE chunks SET knowledge_id='other' WHERE id='chunk'").Error)
	case "wrong-chunk-kb":
		must(db.Exec("UPDATE chunks SET knowledge_base_id='other-kb' WHERE id='chunk'").Error)
	case "empty-revisions":
		must(db.Exec("DELETE FROM chunk_revisions").Error)
	case "revision-table-missing":
		must(db.Exec("DROP TABLE chunk_revisions").Error)
	case "write-error":
		if pgDSN == "" {
			must(db.Exec("CREATE TRIGGER block_chunk_delete BEFORE UPDATE OF deleted_at ON chunks BEGIN SELECT RAISE(ABORT,'controlled chunk write failure'); END").Error)
		} else {
			must(db.Exec("CREATE OR REPLACE FUNCTION block_chunk_delete() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'controlled chunk write failure'; END $$").Error)
			must(db.Exec("CREATE TRIGGER block_chunk_delete BEFORE UPDATE OF deleted_at ON chunks FOR EACH ROW EXECUTE FUNCTION block_chunk_delete()").Error)
		}
	}
	audit := svc.NewAuditLogService(repo.NewAuditLogRepository(db))
	shares := svc.NewKBShareService(repo.NewKBShareRepository(db), repo.NewOrganizationRepository(db), nil, nil, nil, audit)
	agents := svc.NewAgentShareService(repo.NewAgentShareRepository(db), repo.NewTenantDisabledSharedAgentRepository(db), repo.NewOrganizationRepository(db), repo.NewCustomAgentRepository(db), nil, nil, nil)
	br, kr, cr := repo.NewKnowledgeBaseRepository(db), repo.NewKnowledgeRepository(db), repo.NewChunkRepository(db)
	kbs := svc.NewKnowledgeBaseService(br, kr, cr, nil, shares, nil, nil, nil, repo.NewTenantRepository(db), nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil)
	kg, e := svc.NewKnowledgeService(&config.Config{}, kr, nil, kbs, nil, nil, nil, cr, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, shares, nil, nil, nil, nil, nil, audit)
	must(e)
	chunks := svc.NewChunkService(cr, kr, br, nil, nil, nil, nil, nil)
	h := handler.NewChunkHandler(chunks, kg)
	enabled := true
	cfg := &config.Config{Tenant: &config.TenantConfig{EnableRBAC: &enabled}}
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
		must(db.Exec("INSERT INTO organizations(id,name,owner_tenant_id,owner_id) VALUES('org','Org',1,'caller')").Error)
		must(db.Exec("INSERT INTO organization_tenant_members(id,organization_id,tenant_id,role) VALUES('source','org',1,'admin'),('member','org',2,'editor')").Error)
		must(db.Exec("INSERT INTO kb_shares(id,knowledge_base_id,organization_id,source_tenant_id,permission,shared_by_user_id) VALUES('share','kb','org',1,?,'caller')", permission).Error)
		if s.variant == "shared-revoked" {
			must(db.Exec("UPDATE kb_shares SET deleted_at=CURRENT_TIMESTAMP").Error)
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
	readPolicy := middleware.APIKeyRoutePolicy{RequireFullAccess: true}.WithCapability(types.APIKeyCapabilityRetrieve)
	gate.Register("DELETE", "/api/v1/chunks/:knowledge_id/:id", writePolicy)
	gate.Register("DELETE", "/api/v1/chunks/:knowledge_id", writePolicy)
	gate.Register("GET", "/api/v1/chunks/:knowledge_id/:id/revisions", readPolicy)
	own := middleware.RequireOwnershipOrRole(types.TenantRoleAdmin, h.KBCreatorLookupFromKnowledgeIDParam, cfg)
	write := middleware.RequireKBAccess(middleware.KBIDFromKnowledgeIDParam("knowledge_id", kg), types.OrgRoleEditor, kbs, shares, agents, cfg)
	read := middleware.RequireKBAccess(middleware.KBIDFromKnowledgeIDParam("knowledge_id", kg), types.OrgRoleViewer, kbs, shares, agents, cfg)
	router.DELETE("/api/v1/chunks/:knowledge_id/:id", gate.Middleware(), own, write, h.DeleteChunk)
	router.DELETE("/api/v1/chunks/:knowledge_id", gate.Middleware(), own, write, h.DeleteChunksByKnowledgeID)
	router.GET("/api/v1/chunks/:knowledge_id/:id/revisions", gate.Middleware(), middleware.RequireRole(types.TenantRoleViewer, cfg), read, h.ListChunkRevisions)
	method := "DELETE"
	if s.operation == "revisions" {
		method = "GET"
	}
	r := httptest.NewRequest(method, s.target, nil)
	w := httptest.NewRecorder()
	router.ServeHTTP(w, r)
	var body any
	dec := json.NewDecoder(bytes.NewReader(w.Body.Bytes()))
	dec.UseNumber()
	must(dec.Decode(&body))
	var rows, fts []map[string]any
	must(db.Table("chunks").Select("id,tenant_id,knowledge_base_id,knowledge_id,content,status,content_revision,CASE WHEN deleted_at IS NOT NULL THEN 1 ELSE 0 END AS deleted").Order("id").Find(&rows).Error)
	must(db.Table("chunk_delete_fts").Select("chunk_id,content").Order("chunk_id").Find(&fts).Error)
	return map[string]any{"name": s.name, "operation": s.operation, "variant": s.variant, "method": method, "target": s.target, "status": w.Code, "body": body, "chunks": rows, "physical_fts": fts, "caller_tenant": tenant, "user": user, "role": role, "effective_role": types.TenantRoleFromContext(context.WithValue(context.Background(), types.TenantRoleContextKey, role)), "machine": machine, "scope": scope}
}
