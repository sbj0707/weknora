//go:build ignore

// Actual Go batch tags handler, production RBAC/API key/access/services/repositories and SQL checkpoints.
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
	"reflect"
	"strings"
	"time"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}

type scene struct{ name, variant, body string }

func main() {
	time.Local = time.UTC
	logger.SetOutput(os.Stderr)
	gin.SetMode(gin.TestMode)
	cases := []scene{}
	for _, v := range []string{"ok", "inferred", "clear", "empty-tags", "duplicates", "merge-updates", "replace-map-entry", "null-map-reset", "creator-contributor", "creator-viewer", "noncreator-contributor", "noncreator-admin", "noncreator-viewer", "unknown-role", "shared-editor", "shared-inferred", "shared-viewer", "shared-revoked", "api-ingest", "api-retrieve", "api-full", "api-wrong-kb", "api-full-wrong-kb", "foreign-document", "deleted-document", "deleting-document", "failed-document", "moving", "wrong-kb-doc", "mixed-kb", "missing-doc", "deleted-kb", "tag-other-kb", "tag-foreign", "tag-missing", "write-error", "delete-error", "partial-failure", "partial-clear-failure", "relations-missing", "tags-missing", "knowledge-missing", "whitespace-id", "empty-id", "same-tags", "tag-seq-is-not-id", "inferred-missing", "inferred-foreign", "inferred-deleted", "inferred-knowledge-missing", "shared-readonly", "shared-contributor", "shared-api-ingest", "shared-api-full", "shared-api-revoked", "invalid-metadata", "array-metadata", "bad-transfer-metadata"} {
		cases = append(cases, scene{"tags-" + v, v, ""})
	}
	for i, b := range []string{"", `null`, `{}`, `[]`, `true`, `1`, `"x"`, `{"updates":null}`, `{"updates":{}}`, `{"updates":[]}`, `{"updates":true}`, `{"updates":"x"}`, `{"updates":{"d1":"t1"}}`, `{"updates":{"d1":1}}`, `{"updates":{"d1":{}}}`, `{"updates":{"d1":[1]}}`, `{"updates":{"d1":[true]}}`, `{"updates":{"d1":[{}]}}`, `{"updates":{"d1":[[]]}}`, `{"updates":{"d1":[null,"t1"]},"kb_id":"kb"}`, `{"UPDATES":{"d1":["t1"]},"KB_ID":"kb"}`, `{"updates":{"d1":["t1"]},"kb_id":null}`, `{"updates":{"d1":["t1"]},"kb_id":1}`, `{"updates":{"d1":["t1"]},"kb_id":false}`, `{"updates":{"d1":["t1"]},"kb_id":[]}`, `{"updates":{"d1":["t1"]},"unknown":{}}garbage`, `{"updates":{"d1":["t1"]},"kb_id":"kb"}{}`, `{"updates":{"d1":["t1"]},`, `{"updates":`, `{"updates":{"d1":["t1"]} "kb_id":"kb"}`, `{"updates":{"d1":["t1"]},"updates":{}}`, `{"updates":{"d1":[1]},"updates":{"d1":["t1"]}}`, `{"updates":{"d1":["t1"]},"updates":null}`, `{"updates":null,"updates":{"d1":["t1"]}}`, `{"updates":{"d1":["t1"]},"kb_id":"kb","kb_id":null}`, `{"updates":{"d1":[" t1 "]},"kb_id":"kb"}`, `{"updates":{"d1":["\ud800"]},"kb_id":"kb"}`, `{"updateſ":{"d1":["t1"]},"KB_ID":"kb"}`} {
		cases = append(cases, scene{fmt.Sprintf("tags-body-%02d", i), "body", b})
	}
	out := []any{}
	for _, s := range cases {
		out = append(out, run(s))
	}
	b, e := json.Marshal(out)
	must(e)
	must(os.WriteFile(os.Args[1], append(b, '\n'), 0600))
	fmt.Println("exported", len(out), "actual Go batch tags HTTP cases")
}
func run(s scene) any {
	dir, e := os.MkdirTemp("", "weknora-go-batch-tags-")
	must(e)
	defer os.RemoveAll(dir)
	dsn := os.Getenv("WEKNORA_BATCH_TAGS_PG_DSN")
	var dialect gorm.Dialector = sqlite.Open(filepath.Join(dir, "case.db"))
	if dsn != "" {
		u, e := url.Parse(dsn)
		must(e)
		if u.Hostname() != "127.0.0.1" || u.Path != "/weknora_batch_tags_parity" {
			panic("Owned loopback batch tags database required")
		}
		dialect = postgres.Open(dsn)
	}
	db, e := gorm.Open(dialect, &gorm.Config{Logger: gl.Default.LogMode(gl.Silent)})
	must(e)
	sql, e := db.DB()
	must(e)
	defer sql.Close()
	sql.SetMaxOpenConns(1)
	if dsn == "" {
		for _, m := range []string{"000000_init", "000002_knowledge_folder_path", "000003_knowledge_base_auto_tag_config", "000004_memory", "000007_system_admin_and_settings", "000008_processing_spans_and_pending_subtasks", "000010_knowledge_multi_tags", "000011_principal_model", "000021_knowledge_profiles", "000031_model_catalog_config", "000033_chunk_source_locators"} {
			b, e := os.ReadFile("migrations/sqlite/" + m + ".up.sql")
			must(e)
			must(db.Exec(string(b)).Error)
		}
	} else {
		for _, t := range []string{"knowledge_tag_relations", "knowledge_tags", "knowledges"} {
			var n int64
			must(db.Raw("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public' AND table_name=?", "hidden_"+t).Scan(&n).Error)
			if n > 0 {
				must(db.Exec("ALTER TABLE hidden_" + t + " RENAME TO " + t).Error)
			}
		}
		must(db.Exec("DROP TRIGGER IF EXISTS block_batch_tags ON knowledge_tag_relations").Error)
		must(db.Exec("TRUNCATE tenants,knowledge_bases,knowledges,knowledge_tags,knowledge_tag_relations,organizations,organization_tenant_members,kb_shares,audit_logs RESTART IDENTITY CASCADE").Error)
	}
	seeds := []string{}
	seed := func(q string) { must(db.Exec(q).Error); seeds = append(seeds, q) }
	seed("INSERT INTO tenants(id,name,business) VALUES(1,'Owner','test'),(2,'Caller','test')")
	seed("INSERT INTO knowledge_bases(id,tenant_id,creator_id,name,type,embedding_model_id,summary_model_id) VALUES('kb',1,'caller','KB','document','',''),('other-kb',1,'other','Other','document','',''),('foreign-kb',2,'other','Foreign','document','','')")
	for _, d := range []struct {
		id, kb string
		owner  int
	}{{"d1", "kb", 1}, {"d2", "kb", 1}, {"other", "other-kb", 1}, {"foreign", "foreign-kb", 2}} {
		seed(fmt.Sprintf("INSERT INTO knowledges(id,tenant_id,knowledge_base_id,type,title,source,parse_status,created_at,updated_at) VALUES('%s',%d,'%s','file','Document','manual','completed','2026-01-01 00:00:00+00:00','2026-01-01 00:00:00+00:00')", d.id, d.owner, d.kb))
	}
	for i, t := range []struct {
		id, kb string
		owner  int
	}{{"old", "kb", 1}, {"t1", "kb", 1}, {"t2", "kb", 1}, {"outside", "other-kb", 1}, {"foreign-tag", "foreign-kb", 2}} {
		seed(fmt.Sprintf("INSERT INTO knowledge_tags(id,seq_id,tenant_id,knowledge_base_id,name,color,sort_order,created_at,updated_at) VALUES('%s',%d,%d,'%s','%s','blue',0,'2026-01-01 00:00:00+00:00','2026-01-01 00:00:00+00:00')", t.id, i+1, t.owner, t.kb, t.id))
	}
	seed("INSERT INTO knowledge_tag_relations(knowledge_id,tag_id,created_at) VALUES('d1','old','2026-01-01 00:00:00+00:00'),('d2','old','2026-01-01 00:00:00+00:00')")
	payload := `{"kb_id":"kb","updates":{"d1":["t1"],"d2":["t2"]}}`
	switch s.variant {
	case "inferred", "shared-inferred":
		payload = `{"updates":{"d1":["t1"],"d2":["t2"]}}`
	case "inferred-missing":
		payload = `{"updates":{"absent":["t1"]}}`
	case "inferred-foreign":
		payload = `{"updates":{"foreign":["foreign-tag"]}}`
	case "inferred-deleted":
		payload = `{"updates":{"d1":["t1"]}}`
		seed("UPDATE knowledges SET deleted_at=CURRENT_TIMESTAMP WHERE id='d1'")
	case "inferred-knowledge-missing":
		payload = `{"updates":{"d1":["t1"]}}`
	case "invalid-metadata", "array-metadata", "bad-transfer-metadata":
		raw := "["
		if s.variant == "array-metadata" {
			raw = "[]"
		}
		if s.variant == "bad-transfer-metadata" {
			raw = `{"_knowledge_transfer":{"operation":1}}`
		}
		if dsn == "" {
			seed("UPDATE knowledges SET metadata=CAST('" + raw + "' AS BLOB) WHERE id='d1'")
		} else if s.variant != "invalid-metadata" {
			seed("UPDATE knowledges SET metadata='" + raw + "' WHERE id='d1'")
		} else {
			return map[string]any{"name": s.name, "variant": s.variant, "pg_unsupported_invalid_json": true}
		}
	case "clear":
		payload = `{"kb_id":"kb","updates":{"d1":null,"d2":[]}}`
	case "empty-tags":
		payload = `{"kb_id":"kb","updates":{"d1":["",null,""]}}`
	case "duplicates":
		payload = `{"kb_id":"kb","updates":{"d1":["t1","t1","","t2","t2"]}}`
	case "merge-updates":
		payload = `{"kb_id":"kb","updates":{"d1":["t1"]},"UPDATES":{"d2":["t2"]}}`
	case "replace-map-entry":
		payload = `{"kb_id":"kb","updates":{"d1":["t1"]},"updates":{"d1":["t2"]}}`
	case "null-map-reset":
		payload = `{"kb_id":"kb","updates":{"d1":["t1"]},"updates":null,"updates":{"d2":["t2"]}}`
	case "foreign-document":
		seed("UPDATE knowledges SET tenant_id=2 WHERE id='d1'")
	case "deleted-document":
		seed("UPDATE knowledges SET deleted_at=CURRENT_TIMESTAMP WHERE id='d1'")
	case "deleting-document":
		seed("UPDATE knowledges SET parse_status='deleting' WHERE id='d1'")
	case "failed-document":
		seed("UPDATE knowledges SET parse_status='failed' WHERE id='d1'")
	case "moving":
		if dsn == "" {
			seed(`UPDATE knowledges SET metadata=CAST('{"_knowledge_transfer":{"operation":"move","phase":"moving"}}' AS BLOB) WHERE id='d1'`)
		} else {
			seed(`UPDATE knowledges SET metadata='{"_knowledge_transfer":{"operation":"move","phase":"moving"}}' WHERE id='d1'`)
		}
	case "wrong-kb-doc":
		seed("UPDATE knowledges SET knowledge_base_id='other-kb' WHERE id='d1'")
	case "mixed-kb":
		payload = `{"kb_id":"kb","updates":{"d1":["t1"],"other":["outside"]}}`
	case "missing-doc":
		payload = `{"kb_id":"kb","updates":{"absent":["t1"],"d2":["t2"]}}`
	case "deleted-kb":
		seed("UPDATE knowledge_bases SET deleted_at=CURRENT_TIMESTAMP WHERE id='kb'")
	case "tag-other-kb":
		payload = `{"kb_id":"kb","updates":{"d1":["outside"]}}`
	case "tag-foreign":
		payload = `{"kb_id":"kb","updates":{"d1":["foreign-tag"]}}`
	case "tag-missing":
		payload = `{"kb_id":"kb","updates":{"d1":["missing"]}}`
	case "same-tags":
		payload = `{"kb_id":"kb","updates":{"d1":["old"]}}`
	case "tag-seq-is-not-id":
		payload = `{"kb_id":"kb","updates":{"d1":["2"]}}`
	case "whitespace-id":
		payload = `{"kb_id":"kb","updates":{" ":["t1"]}}`
	case "empty-id":
		payload = `{"kb_id":"kb","updates":{"":[]}}`
	case "body":
		payload = s.body
	}
	if s.variant == "write-error" || s.variant == "partial-failure" || s.variant == "partial-clear-failure" || s.variant == "delete-error" {
		target, operation := "d1", "INSERT"
		if strings.HasPrefix(s.variant, "partial-") {
			target = "d2"
		}
		if s.variant == "delete-error" || s.variant == "partial-clear-failure" {
			operation = "DELETE"
		}
		if dsn == "" {
			record := "NEW"
			if operation == "DELETE" {
				record = "OLD"
			}
			seed(fmt.Sprintf("CREATE TRIGGER block_batch_tags BEFORE %s ON knowledge_tag_relations WHEN %s.knowledge_id='%s' BEGIN SELECT RAISE(ABORT,'controlled tag replacement failure'); END", operation, record, target))
		} else {
			record := "NEW"
			if operation == "DELETE" {
				record = "OLD"
			}
			seed(fmt.Sprintf("CREATE OR REPLACE FUNCTION block_batch_tags_fn() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF %s.knowledge_id='%s' THEN RAISE EXCEPTION 'controlled tag replacement failure'; END IF; RETURN %s; END $$", record, target, record))
			seed("CREATE TRIGGER block_batch_tags BEFORE " + operation + " ON knowledge_tag_relations FOR EACH ROW EXECUTE FUNCTION block_batch_tags_fn()")
		}
	}
	hidden := ""
	switch s.variant {
	case "relations-missing":
		hidden = "knowledge_tag_relations"
	case "tags-missing":
		hidden = "knowledge_tags"
	case "knowledge-missing", "inferred-knowledge-missing":
		hidden = "knowledges"
	}
	if hidden != "" {
		seed("ALTER TABLE " + hidden + " RENAME TO hidden_" + hidden)
	}
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
		if s.variant == "shared-viewer" || s.variant == "shared-readonly" {
			permission = "viewer"
		}
		seed("INSERT INTO organizations(id,name,owner_tenant_id,owner_id) VALUES('org','Org',1,'caller')")
		seed("INSERT INTO organization_tenant_members(id,organization_id,tenant_id,role) VALUES('source','org',1,'admin'),('member','org',2,'editor')")
		seed("INSERT INTO kb_shares(id,knowledge_base_id,organization_id,source_tenant_id,permission,shared_by_user_id) VALUES('share','kb','org',1,'" + permission + "','caller')")
		if s.variant == "shared-revoked" || s.variant == "shared-api-revoked" {
			seed("UPDATE kb_shares SET deleted_at=CURRENT_TIMESTAMP")
		}
	}
	machine := strings.Contains(s.variant, "api-")
	scope := types.TenantAPIKeyScope{Capabilities: types.StringArray{"ingest"}, KnowledgeBaseIDs: []string{"kb"}}
	if s.variant == "api-retrieve" {
		scope.Capabilities = types.StringArray{"retrieve"}
	}
	if strings.Contains(s.variant, "api-full") {
		scope.FullAccess = true
	}
	if strings.HasSuffix(s.variant, "wrong-kb") {
		scope.KnowledgeBaseIDs = []string{"other-kb"}
	}
	audit := svc.NewAuditLogService(repo.NewAuditLogRepository(db))
	shares := svc.NewKBShareService(repo.NewKBShareRepository(db), repo.NewOrganizationRepository(db), nil, nil, nil, audit)
	agents := svc.NewAgentShareService(repo.NewAgentShareRepository(db), repo.NewTenantDisabledSharedAgentRepository(db), repo.NewOrganizationRepository(db), repo.NewCustomAgentRepository(db), nil, nil, nil)
	br, kr, cr := repo.NewKnowledgeBaseRepository(db), repo.NewKnowledgeRepository(db), repo.NewChunkRepository(db)
	kbs := svc.NewKnowledgeBaseService(br, kr, cr, nil, shares, nil, nil, nil, repo.NewTenantRepository(db), nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil)
	kg, e := svc.NewKnowledgeService(&config.Config{}, kr, nil, kbs, repo.NewTenantRepository(db), nil, nil, cr, repo.NewKnowledgeTagRepository(db), nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, shares, nil, nil, nil, nil, nil, audit)
	must(e)
	enabled := true
	cfg := &config.Config{Tenant: &config.TenantConfig{EnableRBAC: &enabled}}
	h := handler.NewKnowledgeHandler(cfg, kg, kbs, shares, agents, nil, nil, nil, nil, nil, nil)
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
	gate.Register("PUT", "/api/v1/knowledge/tags", middleware.APIKeyRoutePolicy{RequireFullAccess: true}.WithCapability(types.APIKeyCapabilityIngest))
	router.PUT("/api/v1/knowledge/tags", gate.Middleware(), middleware.RequireRole(types.TenantRoleContributor, cfg), h.UpdateKnowledgeTagBatch)
	table := "knowledges"
	if hidden == table {
		table = "hidden_" + table
	}
	var before, after []map[string]any
	must(db.Table(table).Order("id").Find(&before).Error)
	request := httptest.NewRequest("PUT", "/api/v1/knowledge/tags", strings.NewReader(payload))
	request.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	router.ServeHTTP(w, request)
	var body any
	dec := json.NewDecoder(bytes.NewReader(w.Body.Bytes()))
	dec.UseNumber()
	must(dec.Decode(&body))
	must(db.Table(table).Order("id").Find(&after).Error)
	if !reflect.DeepEqual(before, after) {
		panic("Go batch tags unexpectedly mutated documents")
	}
	relationTable := "knowledge_tag_relations"
	if hidden == relationTable {
		relationTable = "hidden_" + relationTable
	}
	var relations []map[string]any
	must(db.Table(relationTable).Select("knowledge_id,tag_id,CASE WHEN created_at='2026-01-01 00:00:00+00:00' THEN 0 ELSE 1 END AS replaced").Order("knowledge_id,tag_id").Find(&relations).Error)
	var audits []map[string]any
	must(db.Table("audit_logs").Select("tenant_id,actor_user_id,actor_role,action,request_path,request_method,outcome,details").Order("id").Find(&audits).Error)
	for _, a := range audits {
		raw := a["details"]
		var v any
		switch x := raw.(type) {
		case []byte:
			must(json.Unmarshal(x, &v))
		case string:
			must(json.Unmarshal([]byte(x), &v))
		default:
			v = x
		}
		a["details"] = v
	}
	if dsn != "" && hidden != "" {
		must(db.Exec("ALTER TABLE hidden_" + hidden + " RENAME TO " + hidden).Error)
	}
	return map[string]any{"name": s.name, "variant": s.variant, "method": "PUT", "target": "/api/v1/knowledge/tags", "request_body": payload, "status": w.Code, "body": body, "seed_statements": seeds, "relations": relations, "documents_unchanged": true, "audits": audits, "caller_tenant": tenant, "user": user, "effective_role": types.TenantRoleFromContext(context.WithValue(context.Background(), types.TenantRoleContextKey, role)), "machine": machine, "scope": scope}
}
