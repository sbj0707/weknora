//go:build ignore

// Actual URL import HTTP/service/SQL/Asynq behavior, with no remote source download at admission.
package main

import (
	"bytes"
	"context"
	"crypto/md5"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	repo "github.com/Tencent/WeKnora/internal/application/repository"
	svc "github.com/Tencent/WeKnora/internal/application/service"
	filesvc "github.com/Tencent/WeKnora/internal/application/service/file"
	"github.com/Tencent/WeKnora/internal/config"
	handler "github.com/Tencent/WeKnora/internal/handler"
	"github.com/Tencent/WeKnora/internal/logger"
	"github.com/Tencent/WeKnora/internal/middleware"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/utils"
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

type scene struct{ name, variant, body string }
type capture struct {
	client *asynq.Client
	fail   bool
	rows   []any
}

func (c *capture) Enqueue(t *asynq.Task, opts ...asynq.Option) (*asynq.TaskInfo, error) {
	if c.fail {
		return nil, errors.New("controlled enqueue failure")
	}
	info, err := c.client.Enqueue(t, opts...)
	if err != nil {
		return nil, err
	}
	var p any
	d := json.NewDecoder(bytes.NewReader(t.Payload()))
	d.UseNumber()
	must(d.Decode(&p))
	c.rows = append(c.rows, map[string]any{"type": t.Type(), "payload": p, "queue": info.Queue, "max_retry": info.MaxRetry, "timeout": info.Timeout / time.Millisecond})
	return info, nil
}

var scenes = []scene{}

func main() {
	time.Local = time.UTC
	logger.SetOutput(os.Stderr)
	gin.SetMode(gin.TestMode)
	must(os.Setenv("SSRF_WHITELIST", "127.0.0.1"))
	must(os.Setenv("SSRF_DNS_WHITELIST_ONLY", "true"))
	utils.ResetSSRFWhitelistForTest()
	dsn := os.Getenv("WEKNORA_URL_PARITY_PG_DSN")
	if dsn != "" {
		u, e := url.Parse(dsn)
		must(e)
		if u.Hostname() != "127.0.0.1" || u.Path != "/weknora_url_parity" {
			panic("Owned loopback URL parity DB required")
		}
		must(os.Setenv("RETRIEVE_DRIVER", "postgres"))
	} else {
		must(os.Setenv("RETRIEVE_DRIVER", "sqlite"))
	}
	for n, body := range []string{
		`{"url":"http://127.0.0.1:9999/page"}`, `{"url":"http://127.0.0.1:9999/a.TXT?x=1"}`, `{"url":"http://127.0.0.1:9999/data.csv"}`, `{"url":"http://127.0.0.1:9999/page","file_name":"guide.md"}`, `{"url":"http://127.0.0.1:9999/page","file_type":" .TXT "}`, `{"url":"http://127.0.0.1:9999/page","file_name":"folder/guide.md","title":"Custom title","channel":"api"}`,
		``, `null`, `{}`, `[]`, `{`, `{"url":7}`, `{"url":true}`, `{"url":""}`, `{"url":null}`, `{"url":" "}`,
		`{"URL":"http://127.0.0.1:9999/page","TITLE":"  Original title  "}`, `{"url":"http://127.0.0.1:9999/page","url":null}`, `{"url":"http://127.0.0.1:9999/page"}{"url":"ignored"}`,
		`{"url":"http://127.0.0.1:9999/page","tag_ids":["tag","tag"]}`, `{"url":"http://127.0.0.1:9999/page","tag_ids":[null]}`, `{"url":"http://127.0.0.1:9999/page","tag_ids":["absent"]}`, `{"url":"http://127.0.0.1:9999/page","tag_ids":["other-tag"]}`, `{"url":"http://127.0.0.1:9999/page","tag_ids":"tag"}`,
		`{"url":"http://127.0.0.1:9999/page","file_name":"unknown"}`, `{"url":"http://127.0.0.1:9999/page","file_type":"exe"}`, `{"url":"http://127.0.0.1:9999/page","file_name":"video.mp4"}`, `{"url":"http://127.0.0.1:9999/page","file_name":"image.png"}`, `{"url":"http://127.0.0.1:9999/audio.mp3"}`, `{"url":"http://127.0.0.1:9999/no.exe"}`,
		`{"url":"http://127.0.0.1:9999/page","file_name":"<script>x</script>.txt"}`, `{"url":"http://127.0.0.1:9999/page","file_name":"a\u0001.txt"}`, `{"url":"http://127.0.0.1:9999/page","file_type":3}`, `{"url":"http://127.0.0.1:9999/page","enable_multimodel":"yes"}`,
		`{"url":"http://127.0.0.1:9999/page","process_config":{"summary_enabled":false,"question_generation_config":{"enabled":true,"question_count":0}}}`, `{"url":"http://127.0.0.1:9999/file.pdf","process_config":{"enable_multimodel":false}}`, `{"url":"http://127.0.0.1:9999/page","process_config":{"summary_enabled":"no"}}`,
		`{"url":"HTTP://127.0.0.1:9999/page"}`, `{"url":"ftp://127.0.0.1/page"}`, `{"url":"http:///page"}`, `{"url":"http://localhost/page"}`, `{"url":"127.0.0.1/page"}`, `{"url":"http://user:pass@127.0.0.1:9999/page"}`,
		`{"url": "http://127.0.0.1:9999/a b"}`,
		`{"url": "http://127.0.0.1:9999/a b.txt"}`,
		`{"url": "http://127.0.0.1:9999/中文.txt"}`,
		`{"url": "http://127.0.0.1:9999/a%20b.txt"}`,
		`{"url": "http://127.0.0.1:9999/a%ZZ"}`,
		`{"url": "http://127.0.0.1:9999/a%"}`,
		`{"url": "http://127.0.0.1:bad/page"}`,
		`{"url": "http://127.0.0.1:99999/page"}`,
		`{"url": "http://127.0.0.1:9999/a\\b.txt"}`,
		`{"url": "http://127.0.0.1:9999/a|b.txt"}`,
		`{"url": "http://127.0.0.1:9999/a#fragment"}`,
		`{"url": "http://127.0.0.1:9999/a?x=a b"}`,
		`{"url": "http://127.0.0.1:9999/a\nline"}`,
		`{"url": "http://127.0.0.1:9999/aline"}`,
		`{"url": "http://127.0.0.1:9999/<button>.txt"}`,
		`{"url": "http://127.0.0.1:9999/page?x=javascript:alert(1)"}`,
	} {
		scenes = append(scenes, scene{fmt.Sprintf("body-%d", n), "ok", body})
	}
	for _, variant := range []string{"queue-error", "file-queue-error", "table-queue-error", "missing-kb", "deleted-kb", "creator-viewer", "noncreator-contributor", "noncreator-viewer", "shared-editor", "shared-viewer", "shared-role-viewer", "shared-revoked", "shared-no-source", "api-ingest", "api-retrieve", "api-full", "api-wrong-kb", "api-audit", "quota-full", "quota-over", "quota-zero", "shared-owner-quota", "shared-caller-quota", "faq-web", "faq-file", "duplicate-url", "duplicate-hash", "duplicate-failed", "duplicate-deleting", "duplicate-deleted", "duplicate-other-tenant", "duplicate-file-url", "duplicate-file-row", "question-negative", "file-type-mismatch"} {
		body := `{"url":"http://127.0.0.1:9999/page"}`
		if strings.Contains(variant, "file") || variant == "table-queue-error" {
			body = `{"url":"http://127.0.0.1:9999/source.txt"}`
		}
		if variant == "table-queue-error" {
			body = `{"url":"http://127.0.0.1:9999/source.csv"}`
		}
		if variant == "file-type-mismatch" {
			body = `{"url":"http://127.0.0.1:9999/source.pdf","file_name":"custom.csv","file_type":"md"}`
		}
		scenes = append(scenes, scene{variant, variant, body})
	}
	out := []any{}
	for _, s := range scenes {
		out = append(out, run(s, dsn))
	}
	b, e := json.Marshal(out)
	must(e)
	must(os.WriteFile(os.Args[1], append(b, '\n'), 0600))
	fmt.Println("exported", len(out), "actual Go URL HTTP/service/SQL/Asynq scenes")
}
func run(s scene, dsn string) any {
	folder, e := os.MkdirTemp("", "weknora-go-url-")
	must(e)
	defer os.RemoveAll(folder)
	var dialect gorm.Dialector = sqlite.Open(filepath.Join(folder, "db.sqlite"))
	if dsn != "" {
		dialect = postgres.Open(dsn)
	}
	db, e := gorm.Open(dialect, &gorm.Config{Logger: gl.Default.LogMode(gl.Silent)})
	must(e)
	sql, e := db.DB()
	must(e)
	defer sql.Close()
	sql.SetMaxOpenConns(1)
	if dsn == "" {
		for _, m := range []string{"000000_init", "000002_knowledge_folder_path", "000003_knowledge_base_auto_tag_config", "000007_system_admin_and_settings", "000008_processing_spans_and_pending_subtasks", "000010_knowledge_multi_tags", "000011_principal_model", "000021_knowledge_profiles", "000031_model_catalog_config", "000033_chunk_source_locators"} {
			b, e := os.ReadFile("migrations/sqlite/" + m + ".up.sql")
			must(e)
			must(db.Exec(string(b)).Error)
		}
	} else {
		must(db.Exec("TRUNCATE tenants,knowledge_bases,knowledge_tags,knowledge_tag_relations,knowledges,tenant_api_keys,chunks,organizations,organization_tenant_members,kb_shares,audit_logs RESTART IDENTITY CASCADE").Error)
		must(db.Exec("ALTER SEQUENCE knowledge_tags_seq_id_seq RESTART WITH 10000000").Error)
	}
	seeds := []any{}
	seed := func(q string, args ...any) {
		seeds = append(seeds, map[string]any{"sql": q, "args": args})
		must(db.Exec(q, args...).Error)
	}
	seed("INSERT INTO tenants(id,name,business,storage_used,storage_quota) VALUES(1,'Owner','isolated',10,100),(2,'Caller','isolated',0,100)")
	seed("INSERT INTO knowledge_bases(id,tenant_id,creator_id,name,type,embedding_model_id,summary_model_id) VALUES('kb',1,'caller','KB','document','embedding','summary'),('other-kb',1,'other','Other','document','embedding','')")
	seed("INSERT INTO knowledge_tags(id,tenant_id,knowledge_base_id,name,seq_id,created_at,updated_at) VALUES('tag',1,'kb','Tag',1,'2026-01-01T00:00:00Z','2026-01-01T00:00:00Z'),('other-tag',1,'other-kb','Other',2,'2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')")
	role, user, tenant := types.TenantRoleAdmin, "caller", uint64(1)
	if strings.HasPrefix(s.variant, "shared-") {
		tenant = 2
		permission := "editor"
		if s.variant == "shared-viewer" {
			permission = "viewer"
		}
		if s.variant == "shared-role-viewer" {
			role = types.TenantRoleViewer
		}
		seed("INSERT INTO organizations(id,name,owner_tenant_id,owner_id) VALUES('org','Org',1,'caller')")
		if s.variant != "shared-no-source" {
			seed("INSERT INTO organization_tenant_members(id,organization_id,tenant_id,role) VALUES('source','org',1,'admin')")
		}
		seed("INSERT INTO organization_tenant_members(id,organization_id,tenant_id,role) VALUES('member','org',2,'editor')")
		seed("INSERT INTO kb_shares(id,knowledge_base_id,organization_id,source_tenant_id,permission,shared_by_user_id) VALUES('share','kb','org',1,?,'caller')", permission)
		if s.variant == "shared-revoked" {
			seed("UPDATE kb_shares SET deleted_at='2026-01-01T00:00:00Z' WHERE id='share'")
		}
	}
	switch s.variant {
	case "missing-kb":
		seed("DELETE FROM knowledge_bases WHERE id='kb'")
	case "deleted-kb":
		seed("UPDATE knowledge_bases SET deleted_at='2026-01-01T00:00:00Z' WHERE id='kb'")
	case "creator-viewer":
		role = types.TenantRoleViewer
	case "noncreator-contributor":
		user = "intruder"
		role = types.TenantRoleContributor
	case "noncreator-viewer":
		user = "intruder"
		role = types.TenantRoleViewer
	case "quota-full", "shared-owner-quota":
		seed("UPDATE tenants SET storage_quota=10 WHERE id=1")
	case "quota-over":
		seed("UPDATE tenants SET storage_quota=9 WHERE id=1")
	case "quota-zero":
		seed("UPDATE tenants SET storage_quota=0 WHERE id=1")
	case "shared-caller-quota":
		seed("UPDATE tenants SET storage_quota=1,storage_used=1 WHERE id=2")
	case "faq-web", "faq-file":
		seed("UPDATE knowledge_bases SET type='faq' WHERE id='kb'")
	case "question-negative":
		seed("UPDATE knowledge_bases SET question_generation_config=? WHERE id='kb'", `{"enabled":true,"question_count":-2}`)
	}
	if strings.HasPrefix(s.variant, "duplicate-") {
		var request map[string]any
		must(json.Unmarshal([]byte(s.body), &request))
		source := request["url"].(string)
		hash := md5.Sum([]byte(source))
		fileHash := hex.EncodeToString(hash[:])
		kind := "url"
		state := "completed"
		owner := 1
		deleted := any(nil)
		switch s.variant {
		case "duplicate-hash":
			source = "http://127.0.0.1:9999/other"
		case "duplicate-url":
			fileHash = "other-hash"
		case "duplicate-failed":
			state = "failed"
		case "duplicate-deleting":
			state = "deleting"
		case "duplicate-deleted":
			deleted = "2026-01-01T00:00:00Z"
		case "duplicate-other-tenant":
			owner = 2
		case "duplicate-file-url":
			kind = "file_url"
		case "duplicate-file-row":
			kind = "file"
		}
		seed("INSERT INTO knowledges(id,tenant_id,knowledge_base_id,type,title,source,file_hash,file_name,file_type,parse_status,created_at,updated_at,deleted_at) VALUES('old',?,'kb',?,'Existing',?,?,'source.txt','txt',?,'2026-01-01T00:00:00Z','2026-01-01T00:00:00Z',?)", owner, kind, source, fileHash, state, deleted)
	}
	br, kr, cr, tr := repo.NewKnowledgeBaseRepository(db), repo.NewKnowledgeRepository(db), repo.NewChunkRepository(db), repo.NewKnowledgeTagRepository(db)
	audit := svc.NewAuditLogService(repo.NewAuditLogRepository(db))
	shares := svc.NewKBShareService(repo.NewKBShareRepository(db), repo.NewOrganizationRepository(db), nil, nil, nil, audit)
	agents := svc.NewAgentShareService(repo.NewAgentShareRepository(db), repo.NewTenantDisabledSharedAgentRepository(db), repo.NewOrganizationRepository(db), repo.NewCustomAgentRepository(db), nil, nil, nil)
	kbs := svc.NewKnowledgeBaseService(br, kr, cr, nil, shares, nil, nil, nil, repo.NewTenantRepository(db), nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil)
	redis, e := miniredis.Run()
	must(e)
	defer redis.Close()
	client := asynq.NewClient(asynq.RedisClientOpt{Addr: redis.Addr()})
	defer client.Close()
	captured := &capture{client: client, fail: strings.HasSuffix(s.variant, "queue-error"), rows: []any{}}
	tags, e := svc.NewKnowledgeTagService(kbs, tr, kr, cr, nil, nil, nil, captured, shares, repo.NewTenantRepository(db), audit)
	must(e)
	kg, e := svc.NewKnowledgeService(&config.Config{}, kr, nil, kbs, repo.NewTenantRepository(db), nil, nil, cr, tr, tags, filesvc.NewLocalFileService(folder, ""), nil, nil, nil, captured, nil, nil, nil, nil, nil, shares, nil, nil, nil, nil, nil, audit)
	must(e)
	enabled := true
	cfg := &config.Config{Tenant: &config.TenantConfig{EnableRBAC: &enabled}}
	kbHandler := handler.NewKnowledgeBaseHandler(cfg, kbs, nil, shares, agents, nil, nil, nil, nil, nil, nil)
	h := handler.NewKnowledgeHandler(cfg, kg, kbs, shares, agents, nil, nil, nil, captured, nil, nil)
	machine := strings.HasPrefix(s.variant, "api-")
	scope := types.TenantAPIKeyScope{Capabilities: types.StringArray{"ingest"}, KnowledgeBaseIDs: []string{"kb"}}
	switch s.variant {
	case "api-retrieve":
		scope.Capabilities = types.StringArray{"retrieve"}
	case "api-full":
		scope.FullAccess = true
	case "api-wrong-kb":
		scope.KnowledgeBaseIDs = []string{"other-kb"}
	case "api-audit":
		scope.KeyID = 77
		scope.Name = "Automation"
		user = "system-1"
		seed("INSERT INTO tenant_api_keys(id,tenant_id,name,key_hash,api_key) VALUES(77,1,'Automation','synthetic','synthetic')")
	}
	var tenantInfo types.Tenant
	must(db.First(&tenantInfo, "id=?", tenant).Error)
	r := gin.New()
	r.Use(middleware.ErrorHandler(), middleware.AuditServiceProvider(audit))
	r.Use(func(c *gin.Context) {
		ctx := context.WithValue(c.Request.Context(), types.TenantIDContextKey, tenant)
		ctx = context.WithValue(ctx, types.TenantInfoContextKey, &tenantInfo)
		ctx = context.WithValue(ctx, types.UserIDContextKey, user)
		ctx = context.WithValue(ctx, types.TenantRoleContextKey, role)
		ctx = context.WithValue(ctx, types.LanguageContextKey, "en-US")
		if machine {
			ctx = types.WithTenantAPIKeyScope(ctx, scope)
		}
		c.Request = c.Request.WithContext(ctx)
		c.Set(types.TenantIDContextKey.String(), tenant)
		c.Set(types.UserIDContextKey.String(), user)
		c.Next()
	})
	path := "/api/v1/knowledge-bases/:id/knowledge/url"
	gate := middleware.NewAPIKeyRouteAuthorizer()
	gate.Register("POST", path, middleware.APIKeyRoutePolicy{RequireFullAccess: true}.WithCapability(types.APIKeyCapabilityIngest))
	r.POST(path, gate.Middleware(), middleware.RequireOwnershipOrRole(types.TenantRoleAdmin, kbHandler.KBCreatorLookup, cfg), middleware.RequireKBAccess(middleware.KBIDFromParam("id"), types.OrgRoleEditor, kbs, shares, agents, cfg), h.CreateKnowledgeFromURL)
	request := httptest.NewRequest("POST", "/api/v1/knowledge-bases/kb/knowledge/url", strings.NewReader(s.body))
	request.Header.Set("Content-Type", "application/json")
	response := httptest.NewRecorder()
	r.ServeHTTP(response, request)
	var body any
	decoder := json.NewDecoder(bytes.NewReader(response.Body.Bytes()))
	decoder.UseNumber()
	must(decoder.Decode(&body))
	var docs, audits, relations, tenants []map[string]any
	must(db.Table("knowledges").Select("id,tenant_id,knowledge_base_id,type,title,description,source,channel,parse_status,pending_subtasks_count,summary_status,enable_status,embedding_model_id,file_name,file_type,file_size,file_hash,file_path,storage_size,metadata,custom_metadata,error_message,CASE WHEN created_at='2026-01-01T00:00:00Z' THEN 1 ELSE 0 END AS original_created,CASE WHEN updated_at='2026-01-01T00:00:00Z' THEN 1 ELSE 0 END AS original_updated,CASE WHEN processed_at IS NULL THEN 1 ELSE 0 END AS processed_null,CASE WHEN deleted_at IS NULL THEN 0 ELSE 1 END AS deleted").Order("id").Find(&docs).Error)
	must(db.Table("audit_logs").Select("tenant_id,actor_user_id,actor_role,action,target_type,target_id,scope_type,scope_id,request_path,request_method,outcome,details").Order("id").Find(&audits).Error)
	for _, a := range audits {
		var v any
		raw := a["details"]
		var data []byte
		switch b := raw.(type) {
		case []byte:
			data = b
		case string:
			data = []byte(b)
		}
		d := json.NewDecoder(bytes.NewReader(data))
		d.UseNumber()
		must(d.Decode(&v))
		a["details"] = v
	}
	must(db.Table("knowledge_tag_relations").Select("knowledge_id,tag_id").Order("knowledge_id,tag_id").Find(&relations).Error)
	must(db.Table("tenants").Select("id,storage_used,storage_quota").Order("id").Find(&tenants).Error)
	return map[string]any{"name": s.name, "variant": s.variant, "request": s.body, "status": response.Code, "body": body, "seed_sql": seeds, "documents": docs, "audits": audits, "relations": relations, "tenants": tenants, "tasks": captured.rows, "caller_tenant": tenant, "user": user, "role": role, "machine": machine, "scope": scope}
}
