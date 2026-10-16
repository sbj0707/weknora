//go:build ignore

// Actual production Go partial update handler, services, authorization, Asynq admission and SQL state.
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
	"github.com/Tencent/WeKnora/internal/handler"
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
	out := []any{}

	for _, variant := range []string{"ok", "missing-kb", "deleted-kb", "creator-viewer", "creator-contributor", "noncreator-contributor", "noncreator-viewer", "noncreator-admin", "shared-editor", "shared-viewer", "shared-revoked", "api-ingest", "api-retrieve", "api-full", "api-wrong-kb", "api-full-wrong-kb", "unknown-role", "write-error", "moving", "foreign-document", "deleted-document", "wrong-kb-doc", "summary-none", "summary-empty", "summary-no-model", "queue-error", "same-metadata", "metadata-null-existing", "metadata-array-existing", "metadata-inflight"} {
		out = append(out, run(scene{"update-" + variant, "update", variant, ""}))
	}
	for _, body := range []string{"", `null`, `{}`, `[]`, `true`, `1`, `"x"`, `{"title":1}`, `{"description":false}`, `{"TITLE":"中文","DESCRIPTION":"说明"}`, `{"title":"New","title":null}`, `{"description":"New","description":null}`, `{"description":""}`, `{"title":""}`, `{"title":"   "}`, `{"description":"Updated"}garbage`, `{"title":"New"}{}`, `{"title":"New","unknown":3}`, `{"title":1,"title":"New"}`, `{"custom_metadata":null}`, `{"custom_metadata":{}}`, `{"custom_metadata":[]}`, `{"custom_metadata":true}`, `{"custom_metadata":1}`, `{"custom_metadata":"x"}`, `{"custom_metadata":{"num":1e309}}`, `{"custom_metadata":{"num":9007199254740993}}`, `{"custom_metadata":{"num":-0.0,"flag":true,"empty":null}}`, `{"custom_metadata":{"x":"value"},"custom_metadata":null}`, `{"CUSTOM_METADATA":{"x":"value"}}`, `{"custom_metadata":{" ":"x"}}`, `{"custom_metadata":{"nested":{"x":1}}}`, `{"custom_metadata":{"array":[1,2]}}`, `{"custom_metadata":{"x":"value"}}`, `{"custom_metadata":{"x":"value","x":null}}`, `{"custom_metadata":{"x":"value","x":"last"}}`, `{"title":"<script>title</script>"}`, `{"description":"\u0000\u0001"}`, `{"title":"\ud800"}`, `{"description":null}`, `{"title":null}`} {
		out = append(out, run(scene{fmt.Sprintf("update-body-%d", len(out)), "update", "ok", body}))
	}
	for _, count := range []int{20, 21} {
		m := map[string]any{}
		for i := 0; i < count; i++ {
			m[fmt.Sprintf("key-%d", i)] = i
		}
		b, _ := json.Marshal(map[string]any{"custom_metadata": m})
		out = append(out, run(scene{fmt.Sprintf("update-fields-%d", count), "update", "ok", string(b)}))
	}
	for _, size := range []int{64, 65} {
		b, _ := json.Marshal(map[string]any{"custom_metadata": map[string]any{strings.Repeat("k", size): "value"}})
		out = append(out, run(scene{fmt.Sprintf("update-key-%d", size), "update", "ok", string(b)}))
	}
	for _, size := range []int{1000, 1001} {
		b, _ := json.Marshal(map[string]any{"custom_metadata": map[string]any{"key": strings.Repeat("x", size)}})
		out = append(out, run(scene{fmt.Sprintf("update-value-%d", size), "update", "ok", string(b)}))
		b, _ = json.Marshal(map[string]any{"custom_metadata": map[string]any{"key": strings.Repeat("中", size/3+1)}})
		out = append(out, run(scene{fmt.Sprintf("update-unicode-%d", size), "update", "ok", string(b)}))
	}

	for _, variant := range []string{"same-zero", "same-float", "same-precision", "same-order", "empty-array-to-object", "null-to-null", "summary-failed", "parse-cancelled", "parse-deleting", "custom-string-storage", "metadata-partial-overflow", "summary-description-cleared"} {
		out = append(out, run(scene{"update-" + variant, "update", variant, ""}))
	}
	for i, body := range []string{
		`{"custom_metadata": { "b" : true, "a" : 1.0 } }`,
		`{"custom_metadata":{"key":"\ud800"}}`,
		`{"custom_metadata":{"\u0000":{}}}`,
		`{"custom_metadata":{"\u007f":{}}}`,
		`{"custom_metadata":{"\u00a0":{}}}`,
		`{"custom_metadata":{"\u200b":{}}}`,
		`{"custom_metadata":{"key":"\u0000"}}`,
		`{"custom_metadata":{"number":1e-400}}`,
		`{"custom_metadata":{"number":1e308}}`,
		`{"custom_metadata":{"number":-1e309}}`,
		`{"title":"New",`,
		`{"description":`,
		`{"title":"New" "description":"x"}`,
		`{"custom_metadata":{"same":true,"same":false}}`,
		`{"custom_metadata": {"space":"a"},"CUSTOM_METADATA": { "space" : "b" } }`,
		`{"title":"New","TITLE":null}`,
		`{"description":"New","DESCRIPTION":null}`,
		`{"custom_metadata":{"emoji":"😀😀"}}`,
		` { "title" : "Indented" } garbage`,
		`{"custom_metadata":{"empty":[]}}`,
	} {
		out = append(out, run(scene{fmt.Sprintf("update-extra-%02d", i), "update", "body", body}))
	}
	out = append(out, run(scene{"update-body-empty", "update", "body-empty", ""}))
	b, e := json.Marshal(out)
	must(e)
	must(os.WriteFile(os.Args[1], append(b, '\n'), 0600))
	fmt.Println("exported", len(out), "actual Go document update scenes")
}
func run(s scene) any {
	dir, e := os.MkdirTemp("", "weknora-go-update-")
	must(e)
	defer os.RemoveAll(dir)
	var dialect gorm.Dialector = sqlite.Open(filepath.Join(dir, "case.db"))
	pgDSN := os.Getenv("WEKNORA_UPDATE_PARITY_PG_DSN")
	if pgDSN != "" {
		u, e := url.Parse(pgDSN)
		must(e)
		if u.Hostname() != "127.0.0.1" || u.Path != "/weknora_update_parity" {
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
		must(db.Exec("DROP TRIGGER IF EXISTS block_update ON knowledges").Error)
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

	must(db.Exec("UPDATE knowledges SET title='Old title',description='Old description',summary_status='completed',custom_metadata=? WHERE id='d1'", `{"old":"value"}`).Error)
	must(db.Exec("UPDATE knowledge_bases SET summary_model_id='summary' WHERE id='kb'").Error)
	switch s.variant {
	case "missing-kb":
		must(db.Exec("DELETE FROM knowledge_bases WHERE id='kb'").Error)
	case "deleted-kb":
		must(db.Exec("UPDATE knowledge_bases SET deleted_at=CURRENT_TIMESTAMP WHERE id='kb'").Error)
	case "moving":
		must(db.Exec("UPDATE knowledges SET metadata=? WHERE id='d1'", []byte(`{"_knowledge_transfer":{"operation":"move","phase":"moving"}}`)).Error)
	case "foreign-document":
		must(db.Exec("UPDATE knowledges SET tenant_id=2 WHERE id='d1'").Error)
	case "deleted-document":
		must(db.Exec("UPDATE knowledges SET deleted_at=CURRENT_TIMESTAMP WHERE id='d1'").Error)
	case "wrong-kb-doc":
		must(db.Exec("UPDATE knowledges SET knowledge_base_id='other-kb' WHERE id='d1'").Error)
	case "summary-none":
		must(db.Exec("UPDATE knowledges SET summary_status='none' WHERE id='d1'").Error)
	case "summary-empty":
		must(db.Exec("UPDATE knowledges SET summary_status='' WHERE id='d1'").Error)
	case "summary-no-model":
		must(db.Exec("UPDATE knowledge_bases SET summary_model_id='' WHERE id='kb'").Error)
	case "same-metadata":
		must(db.Exec("UPDATE knowledges SET custom_metadata=? WHERE id='d1'", `{"new":"value"}`).Error)
	case "metadata-null-existing":
		must(db.Exec("UPDATE knowledges SET custom_metadata=? WHERE id='d1'", `null`).Error)
	case "metadata-array-existing":
		must(db.Exec("UPDATE knowledges SET custom_metadata=? WHERE id='d1'", `[]`).Error)
	case "metadata-inflight":
		must(db.Exec("UPDATE knowledges SET summary_status='processing' WHERE id='d1'").Error)

	case "same-zero":
		must(db.Exec("UPDATE knowledges SET custom_metadata=? WHERE id='d1'", `{"number":0}`).Error)
	case "same-float":
		must(db.Exec("UPDATE knowledges SET custom_metadata=? WHERE id='d1'", `{"number":1}`).Error)
	case "same-precision":
		must(db.Exec("UPDATE knowledges SET custom_metadata=? WHERE id='d1'", `{"number":9007199254740992}`).Error)
	case "same-order":
		must(db.Exec("UPDATE knowledges SET custom_metadata=? WHERE id='d1'", `{"a":1,"b":true}`).Error)
	case "empty-array-to-object":
		must(db.Exec("UPDATE knowledges SET custom_metadata=? WHERE id='d1'", `[]`).Error)
	case "null-to-null":
		must(db.Exec("UPDATE knowledges SET custom_metadata=? WHERE id='d1'", `null`).Error)
	case "summary-failed":
		must(db.Exec("UPDATE knowledges SET summary_status='failed' WHERE id='d1'").Error)
	case "parse-cancelled":
		must(db.Exec("UPDATE knowledges SET parse_status='cancelled' WHERE id='d1'").Error)
	case "parse-deleting":
		must(db.Exec("UPDATE knowledges SET parse_status='deleting' WHERE id='d1'").Error)
	case "custom-string-storage":
		must(db.Exec("UPDATE knowledges SET custom_metadata=? WHERE id='d1'", `""`).Error)
	case "metadata-partial-overflow":
		must(db.Exec("UPDATE knowledges SET custom_metadata=? WHERE id='d1'", `{"before":1,"bad":1e309,"after":2}`).Error)
	case "write-error":
		if pgDSN == "" {
			must(db.Exec("CREATE TRIGGER block_update BEFORE UPDATE OF title ON knowledges WHEN OLD.id='d1' BEGIN SELECT RAISE(ABORT,'controlled document write failure'); END").Error)
		} else {
			must(db.Exec("CREATE OR REPLACE FUNCTION block_update() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'controlled document write failure'; END $$").Error)
			must(db.Exec("CREATE TRIGGER block_update BEFORE UPDATE OF title ON knowledges FOR EACH ROW WHEN (OLD.id='d1') EXECUTE FUNCTION block_update()").Error)
		}
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
	kg, e := svc.NewKnowledgeService(&config.Config{}, kr, nil, kbs, repo.NewTenantRepository(db), nil, nil, cr, nil, nil, nil, nil, nil, nil, captured, nil, nil, nil, nil, nil, shares, nil, nil, nil, nil, nil, audit)
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

	gate.Register("PUT", "/api/v1/knowledge/:id", writePolicy)
	own := middleware.RequireOwnershipOrRole(types.TenantRoleAdmin, h.KBCreatorLookupFromKnowledgeID, cfg)
	write := middleware.RequireKBAccess(middleware.KBIDFromKnowledgeIDParam("id", kg), types.OrgRoleEditor, kbs, shares, agents, cfg)
	router.PUT("/api/v1/knowledge/:id", gate.Middleware(), own, write, h.UpdateKnowledge)
	method, target := "PUT", "/api/v1/knowledge/d1"
	payload := `{"title":"New title","description":"Updated description","custom_metadata":{"new":"value"}}`
	if s.variant == "summary-none" || s.variant == "summary-empty" {
		payload = `{"custom_metadata":{"new":"value"}}`
	}
	if s.target != "" {
		payload = s.target
	}

	switch s.variant {
	case "same-zero":
		payload = `{"custom_metadata":{"number":-0.0}}`
	case "same-float":
		payload = `{"custom_metadata":{"number":1.0}}`
	case "same-precision":
		payload = `{"custom_metadata":{"number":9007199254740993}}`
	case "same-order":
		payload = `{"custom_metadata": { "b" : true, "a" : 1.0 } }`
	case "empty-array-to-object", "custom-string-storage":
		payload = `{"custom_metadata":{}}`
	case "null-to-null":
		payload = `{"custom_metadata":null}`
	case "metadata-partial-overflow":
		payload = `{"custom_metadata":{"before":1,"bad":null,"after":2}}`
	case "summary-description-cleared":
		payload = `{"description":"","custom_metadata":{"new":"value"}}`
	case "body-empty":
		payload = ""
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
		must(db.Exec("ALTER TABLE hidden_knowledges RENAME TO knowledges").Error)
	}
	return map[string]any{"name": s.name, "operation": s.operation, "variant": s.variant, "method": method, "target": target, "request_body": payload, "status": w.Code, "body": body, "documents": rows, "tasks": captured.rows, "audits": audits, "caller_tenant": tenant, "user": user, "role": role, "effective_role": types.TenantRoleFromContext(context.WithValue(context.Background(), types.TenantRoleContextKey, role)), "machine": machine, "scope": scope}
}
