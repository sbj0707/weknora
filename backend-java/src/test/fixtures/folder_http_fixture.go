//go:build ignore

// Actual production Go folder handlers, business services, middleware and SQL state.
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

	variants := []string{"ok", "missing-kb", "deleted-kb", "creator-viewer", "creator-contributor", "noncreator-contributor", "noncreator-viewer", "noncreator-admin", "shared-editor", "shared-viewer", "shared-revoked", "api-ingest", "api-retrieve", "api-full", "api-wrong-kb", "api-full-wrong-kb", "unknown-role", "write-error"}
	for _, op := range []string{"tree", "rename", "move"} {
		for _, variant := range variants {
			out = append(out, run(scene{op + "-" + variant, op, variant, ""}))
		}
	}
	for _, variant := range []string{"empty", "normalized", "moving", "deleted-doc", "deleted-all", "empty-table", "count-table-missing"} {
		out = append(out, run(scene{"tree-" + variant, "tree", variant, ""}))
	}
	for _, variant := range []string{"root-source", "root-target", "same", "self-child", "merge", "missing-folder", "percent", "underscore", "backslash", "unicode", "long-segment", "deep", "long-path", "source-normalize", "target-normalize", "xss", "control", "moving", "deleting", "partial-failure"} {
		out = append(out, run(scene{"rename-" + variant, "rename", variant, ""}))
	}
	for _, variant := range []string{"root-target", "target-normalize", "unicode", "long-segment", "deep", "long-path", "xss", "control", "moving", "deleted-doc", "wrong-kb-doc", "foreign-doc", "absent-doc", "duplicate", "empty-ids", "over-200", "same", "write-error"} {
		out = append(out, run(scene{"move-" + variant, "move", variant, ""}))
	}
	for _, op := range []string{"rename", "move"} {
		for _, body := range []string{"", `null`, `{}`, `[]`, `true`, `1`, `"x"`, `{"from":1,"to":"B"}`, `{"from":"A","to":false}`, `{"FROM":"A","TO":"B"}`, `{"from":"A","from":null,"to":"B"}`, `{"from":"A","to":"B"}garbage`, `{"from":"A","to":"B"}{}`, `{"from":" ","to":" "}`, `{"from":"A","to":"B","unknown":3}`, `{"kb_id":1,"knowledge_ids":["d0"]}`, `{"kb_id":"kb","knowledge_ids":false}`, `{"kb_id":"kb","knowledge_ids":[1]}`, `{"kb_id":"kb","knowledge_ids":[null]}`, `{"kb_id":"kb","knowledge_ids":[]}`, `{"kb_id":"kb","knowledge_ids":["d0"],"folder_path":3}`, `{"kb_id":"kb","knowledge_ids":["d0"],"folder_path":null}`, `{"kb_id":"kb","knowledge_ids":["d0"]} garbage`, `{"kb_id":"kb","knowledge_ids":["d0"],"knowledge_ids":null}`, `{"KB_ID":"kb","KNOWLEDGE_IDS":["d0"],"FOLDER_PATH":"B"}`, `{"kb_id":" kb ","knowledge_ids":["d0"]}`} {
			out = append(out, run(scene{fmt.Sprintf("%s-body-%d", op, len(out)), op, "ok", body}))
		}
	}
	b, e := json.Marshal(out)
	must(e)
	must(os.WriteFile(os.Args[1], append(b, '\n'), 0600))
	fmt.Println("exported", len(out), "actual Go folder scenes")
}
func run(s scene) any {
	dir, e := os.MkdirTemp("", "weknora-go-folder-")
	must(e)
	defer os.RemoveAll(dir)
	var dialect gorm.Dialector = sqlite.Open(filepath.Join(dir, "case.db"))
	pgDSN := os.Getenv("WEKNORA_FOLDER_PARITY_PG_DSN")
	if pgDSN != "" {
		u, e := url.Parse(pgDSN)
		must(e)
		if u.Hostname() != "127.0.0.1" || u.Path != "/weknora_folder_parity" {
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
			must(db.Exec(string(b)).Error)
		}
	} else {
		must(db.Exec("DROP TRIGGER IF EXISTS block_folder ON knowledges").Error)
		must(db.Exec("TRUNCATE tenants,knowledge_bases,knowledges,organizations,organization_tenant_members,kb_shares,audit_logs RESTART IDENTITY CASCADE").Error)
	}
	must(db.Exec("INSERT INTO tenants(id,name,business) VALUES(1,'Owner','test'),(2,'Caller','test')").Error)
	must(db.Exec("INSERT INTO knowledge_bases(id,tenant_id,creator_id,name,type,embedding_model_id,summary_model_id) VALUES('kb',1,'caller','KB','document','',''),('other-kb',1,'other','Other','document','','')").Error)
	stamp := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)

	paths := []string{"", "A", "A/spec", "A/spec/deep", "B", "a%_/sub", `C\windows`, "中文/说明", "é", "é", "Z", "../A./spec ", "A/./spec", "aX/sub"}
	stamp = time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	for i, path := range paths {
		must(db.Exec("INSERT INTO knowledges(id,tenant_id,knowledge_base_id,type,title,source,parse_status,folder_path,created_at,updated_at) VALUES(?,1,'kb','file','Document','manual','completed',?,?,?)", fmt.Sprintf("d%d", i), path, stamp, stamp).Error)
	}
	must(db.Exec("INSERT INTO knowledges(id,tenant_id,knowledge_base_id,type,title,source,parse_status,folder_path,created_at,updated_at) VALUES('foreign',2,'kb','file','Foreign','manual','completed','A',?,?),('other',1,'other-kb','file','Other','manual','completed','A',?,?),('deleting',1,'kb','file','Deleting','manual','deleting','A',?,?),('deleted',1,'kb','file','Deleted','manual','completed','A',?,?)", stamp, stamp, stamp, stamp, stamp, stamp, stamp, stamp).Error)
	must(db.Exec("UPDATE knowledges SET deleted_at=CURRENT_TIMESTAMP WHERE id='deleted'").Error)
	metadata := `{"_knowledge_transfer":{"operation":"move","phase":"moving"}}`
	switch s.variant {
	case "missing-kb":
		must(db.Exec("DELETE FROM knowledge_bases WHERE id='kb'").Error)
	case "deleted-kb":
		must(db.Exec("UPDATE knowledge_bases SET deleted_at=CURRENT_TIMESTAMP WHERE id='kb'").Error)
	case "moving":
		must(db.Exec("UPDATE knowledges SET metadata=? WHERE id='d1'", []byte(metadata)).Error)
	case "deleted-doc":
		must(db.Exec("UPDATE knowledges SET deleted_at=CURRENT_TIMESTAMP WHERE id='d1'").Error)
	case "deleted-all":
		must(db.Exec("UPDATE knowledges SET deleted_at=CURRENT_TIMESTAMP").Error)
	case "empty-table":
		must(db.Exec("DELETE FROM knowledges").Error)
	case "empty":
		must(db.Exec("UPDATE knowledges SET folder_path='' ").Error)
	case "normalized":
		must(db.Exec("UPDATE knowledges SET folder_path='../A./spec ' WHERE tenant_id=1 AND knowledge_base_id='kb'").Error)
	case "count-table-missing":
		must(db.Exec("ALTER TABLE knowledges RENAME TO hidden_knowledges").Error)
	case "write-error", "partial-failure":
		if pgDSN == "" {
			must(db.Exec("CREATE TRIGGER block_folder BEFORE UPDATE OF folder_path ON knowledges WHEN OLD.id='d2' BEGIN SELECT RAISE(ABORT,'controlled folder write failure'); END").Error)
		} else {
			must(db.Exec("CREATE OR REPLACE FUNCTION block_folder() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'controlled folder write failure'; END $$").Error)
			must(db.Exec("CREATE TRIGGER block_folder BEFORE UPDATE OF folder_path ON knowledges FOR EACH ROW WHEN (OLD.id='d2') EXECUTE FUNCTION block_folder()").Error)
		}
	}
	audit := svc.NewAuditLogService(repo.NewAuditLogRepository(db))
	shares := svc.NewKBShareService(repo.NewKBShareRepository(db), repo.NewOrganizationRepository(db), nil, nil, nil, audit)
	agents := svc.NewAgentShareService(repo.NewAgentShareRepository(db), repo.NewTenantDisabledSharedAgentRepository(db), repo.NewOrganizationRepository(db), repo.NewCustomAgentRepository(db), nil, nil, nil)
	br, kr, cr := repo.NewKnowledgeBaseRepository(db), repo.NewKnowledgeRepository(db), repo.NewChunkRepository(db)
	kbs := svc.NewKnowledgeBaseService(br, kr, cr, nil, shares, nil, nil, nil, repo.NewTenantRepository(db), nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil)
	kg, e := svc.NewKnowledgeService(&config.Config{}, kr, nil, kbs, nil, nil, nil, cr, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, shares, nil, nil, nil, nil, nil, audit)
	must(e)
	enabled := true
	cfg := &config.Config{Tenant: &config.TenantConfig{EnableRBAC: &enabled}}
	h := handler.NewKnowledgeHandler(cfg, kg, kbs, shares, agents, nil, nil, nil, nil, nil, nil)
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

	gate.Register("GET", "/api/v1/knowledge-bases/:id/knowledge/folders", readPolicy)
	gate.Register("PUT", "/api/v1/knowledge-bases/:id/knowledge/folders", writePolicy)
	gate.Register("POST", "/api/v1/knowledge/folder", writePolicy)
	own := middleware.RequireOwnershipOrRole(types.TenantRoleAdmin, handler.NewKnowledgeBaseHandler(cfg, kbs, kg, shares, agents, nil, nil, nil, nil, nil, nil).KBCreatorLookup, cfg)
	write := middleware.RequireKBAccess(middleware.KBIDFromParam("id"), types.OrgRoleEditor, kbs, shares, agents, cfg)
	read := middleware.RequireKBAccess(middleware.KBIDFromParam("id"), types.OrgRoleViewer, kbs, shares, agents, cfg)
	router.GET("/api/v1/knowledge-bases/:id/knowledge/folders", gate.Middleware(), middleware.RequireRole(types.TenantRoleViewer, cfg), read, h.ListKnowledgeFolders)
	router.PUT("/api/v1/knowledge-bases/:id/knowledge/folders", gate.Middleware(), own, write, h.RenameKnowledgeFolder)
	router.POST("/api/v1/knowledge/folder", gate.Middleware(), middleware.RequireRole(types.TenantRoleContributor, cfg), h.MoveKnowledgeToFolder)
	method, target, from, to := "GET", "/api/v1/knowledge-bases/kb/knowledge/folders", "A", "New"
	ids := []string{"d1", "d2"}
	if s.operation == "rename" {
		method = "PUT"
	}
	if s.operation == "move" {
		method, target = "POST", "/api/v1/knowledge/folder"
	}
	switch s.variant {
	case "root-source":
		from = "../"
	case "root-target":
		to = "../"
	case "same":
		to = from
	case "self-child":
		to = "A/spec/new"
	case "merge":
		to = "B"
	case "missing-folder":
		from = "missing"
	case "percent":
		from = "a%_"
	case "underscore":
		from = "a%_"
	case "backslash":
		from = `C\windows`
	case "unicode":
		to = "新文件夹/说明"
	case "long-segment":
		to = strings.Repeat("中", 100)
	case "deep":
		to = strings.Repeat("x/", 20)
	case "long-path":
		to = strings.Repeat(strings.Repeat("中", 42)+"/", 16)
	case "source-normalize":
		from = "../A./"
	case "target-normalize":
		to = ` ./new./ ../ 子目录. `
	case "xss":
		to = `<script>alert(1)</script>`
	case "control":
		to = "x" + string(rune(1)) + "y"
	case "wrong-kb-doc":
		ids = []string{"d1", "other"}
	case "foreign-doc":
		ids = []string{"d1", "foreign"}
	case "absent-doc":
		ids = []string{"d1", "absent"}
	case "duplicate":
		ids = []string{" d1 ", "d1", "d2", " ", "d2"}
	case "empty-ids":
		ids = []string{" "}
	case "over-200":
		ids = []string{}
		for i := 0; i < 201; i++ {
			ids = append(ids, fmt.Sprintf("id%d", i))
		}
	}
	payload := ""
	if method == "PUT" {
		b, _ := json.Marshal(map[string]any{"from": from, "to": to})
		payload = string(b)
	}
	if method == "POST" {
		b, _ := json.Marshal(map[string]any{"kb_id": "kb", "knowledge_ids": ids, "folder_path": to})
		payload = string(b)
	}
	if strings.Contains(s.name, "-body-") {
		payload = s.target
	}
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
	must(db.Table(table).Select("id,tenant_id,knowledge_base_id,folder_path,parse_status,CASE WHEN deleted_at IS NOT NULL THEN 1 ELSE 0 END AS deleted,CASE WHEN updated_at = ? THEN 0 ELSE 1 END AS updated", stamp).Order("id").Find(&rows).Error)
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
		must(db.Exec("ALTER TABLE hidden_knowledges RENAME TO knowledges").Error)
	}
	return map[string]any{"name": s.name, "operation": s.operation, "variant": s.variant, "method": method, "target": target, "request_body": payload, "status": w.Code, "body": body, "documents": rows, "audits": audits, "caller_tenant": tenant, "user": user, "role": role, "effective_role": types.TenantRoleFromContext(context.WithValue(context.Background(), types.TenantRoleContextKey, role)), "machine": machine, "scope": scope}
}
