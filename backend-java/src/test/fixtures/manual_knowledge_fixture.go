//go:build ignore

// Real Go manual create/update handler, ownership/share/API scopes, SQL and Redis-backed Asynq producer.
package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	repo "github.com/Tencent/WeKnora/internal/application/repository"
	svc "github.com/Tencent/WeKnora/internal/application/service"
	filesvc "github.com/Tencent/WeKnora/internal/application/service/file"
	"github.com/Tencent/WeKnora/internal/config"
	httpHandler "github.com/Tencent/WeKnora/internal/handler"
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

type bases struct {
	interfaces.KnowledgeBaseService
	kb *types.KnowledgeBase
}

func (b *bases) GetKnowledgeBaseByID(context.Context, string) (*types.KnowledgeBase, error) {
	return b.kb, nil
}

type capture struct {
	client *asynq.Client
	fail   bool
	rows   []any
}

func (c *capture) Enqueue(t *asynq.Task, options ...asynq.Option) (*asynq.TaskInfo, error) {
	if c.fail {
		return nil, errors.New("controlled enqueue failure")
	}
	info, e := c.client.Enqueue(t, options...)
	if e != nil {
		return nil, e
	}
	var payload any
	must(json.Unmarshal(t.Payload(), &payload))
	c.rows = append(c.rows, map[string]any{"type": t.Type(), "payload": payload, "queue": info.Queue, "max_retry": info.MaxRetry, "timeout": info.Timeout / time.Millisecond})
	return info, nil
}

type scenario struct{ name, method, target, body string }

func main() {
	logger.SetOutput(os.Stderr)
	gin.SetMode(gin.TestMode)
	time.Local = time.UTC
	pgDSN := os.Getenv("WEKNORA_MANUAL_PARITY_PG_DSN")
	pg := pgDSN != ""
	if pg {
		u, e := url.Parse(pgDSN)
		must(e)
		if u.Hostname() != "127.0.0.1" || u.Path != "/weknora_manual_parity" {
			panic("tag parity only permits its owned loopback weknora_manual_parity database")
		}
		must(os.Setenv("RETRIEVE_DRIVER", "postgres"))
	} else {
		must(os.Setenv("RETRIEVE_DRIVER", "sqlite"))
	}
	cases := []scenario{}
	for _, method := range []string{"POST", "PUT"} {
		for _, v := range []struct{ name, body string }{
			{"draft", `{"title":" Guide ","content":"# Access\r\n\r\nNative manual content."}`},
			{"publish", `{"title":"Guide","content":"# Access\n\nNative manual content.","status":"publish"}`},
			{"null", `null`}, {"empty", ``}, {"object", `{}`}, {"array", `[]`}, {"incomplete", `{`},
			{"content-empty", `{"title":"Guide","content":" \t\n"}`}, {"script-clean", `{"title":"Guide","content":"<script>alert(1)</script># Safe"}`},
			{"script-newline", `{"title":"Guide","content":"<script>\nalert(1)</script># Kept"}`},
			{"title-script", `{"title":"<script>x</script>","content":"Text"}`}, {"title-control", `{"title":"A\u0001B","content":"Text"}`},
			{"title-empty", `{"content":"Text"}`}, {"title-extension", `{"title":"Title.MD","content":"Text"}`},
			{"status-fold", `{"title":"Guide","content":"Text","status":" PUBLISH "}`}, {"status-invalid", `{"content":"Text","status":"published"}`},
			{"field-fold", `{"TITLE":"Fold","CONTENT":"Text","STATUS":"draft"}`}, {"trailing", `{"title":"First","content":"Text"}{"title":"Ignored"}`},
			{"duplicate-null", `{"title":"First","title":null,"content":"Text"}`}, {"number-title", `{"title":7,"content":"Text"}`},
			{"number-content", `{"content":7}`}, {"number-status", `{"content":"Text","status":true}`},
			{"tags", `{"title":"Guide","content":"Text","tag_ids":["tag","tag"]}`}, {"missing-tag", `{"content":"Text","tag_ids":["missing"]}`},
			{"foreign-tag", `{"content":"Text","tag_ids":["other-tag"]}`}, {"null-tag", `{"content":"Text","tag_ids":[null]}`},
			{"channel", `{"content":"Text","channel":"api"}`}, {"override-draft", `{"content":"Text","process_config":{"summary_enabled":false}}`},
			{"override-publish", `{"content":"Text","status":"publish","process_config":{"summary_enabled":false}}`},
			{"override-typed", `{"content":"Text","process_config":{"summary_enabled":"no"}}`},
		} {
			cases = append(cases, scenario{strings.ToLower(method) + "-" + v.name, method, "", v.body})
		}
		for _, v := range []string{"queue-error", "creator-viewer", "noncreator-contributor", "shared-editor", "shared-viewer", "shared-role-viewer", "shared-revoked", "shared-no-source", "api-ingest", "api-retrieve", "api-wrong-kb", "api-full", "not-manual", "missing-doc", "metadata-null", "metadata-array", "metadata-invalid-version", "preserve-transfer", "preserve-process"} {
			cases = append(cases, scenario{strings.ToLower(method) + "-" + v, method, "", `{"title":"Guide","content":"Current text","status":"publish"}`})
		}
	}
	out := []any{}
	for _, scene := range cases {
		folder, e := os.MkdirTemp("", "weknora-go-manual-public-")
		must(e)
		var dialect gorm.Dialector = sqlite.Open(filepath.Join(folder, "db.sqlite"))
		if pg {
			dialect = postgres.Open(pgDSN)
		}
		db, e := gorm.Open(dialect, &gorm.Config{Logger: gl.Default.LogMode(gl.Silent)})
		must(e)
		sql, e := db.DB()
		must(e)
		sql.SetMaxOpenConns(1)
		if !pg {
			for _, m := range []string{"000000_init", "000002_knowledge_folder_path", "000003_knowledge_base_auto_tag_config", "000004_memory", "000007_system_admin_and_settings", "000008_processing_spans_and_pending_subtasks", "000010_knowledge_multi_tags", "000011_principal_model", "000021_knowledge_profiles", "000031_model_catalog_config", "000033_chunk_source_locators"} {
				b, e := os.ReadFile("migrations/sqlite/" + m + ".up.sql")
				must(e)
				must(db.Exec(string(b)).Error)
			}
		} else {
			must(db.Exec("TRUNCATE tenants,knowledge_bases,knowledge_tags,knowledge_tag_relations,knowledges,tenant_api_keys,chunks,organizations,organization_tenant_members,kb_shares,audit_logs RESTART IDENTITY CASCADE").Error)
			must(db.Exec("ALTER SEQUENCE knowledge_tags_seq_id_seq RESTART WITH 10000000").Error)
		}
		must(db.Exec("INSERT INTO tenants(id,name,business) VALUES(1,'owner','isolated'),(2,'caller','isolated')").Error)
		must(db.Exec("INSERT INTO knowledge_bases(id,tenant_id,creator_id,name,type,embedding_model_id,summary_model_id) VALUES('kb',1,'caller','KB','document','embedding',''),('other-kb',1,'other','Other','faq','embedding','')").Error)
		must(db.Exec("INSERT INTO knowledge_tags(id,tenant_id,knowledge_base_id,name,seq_id,color,sort_order,created_at,updated_at) VALUES('tag',1,'kb','Tag',1,'#fff',0,'2026-01-01 00:00:00','2026-01-01 00:00:00'),('other-tag',1,'other-kb','Other',2,'',0,'2026-01-01 00:00:00','2026-01-01 00:00:00')").Error)
		for i, id := range []string{"chunk-1", "chunk-2"} {
			must(db.Exec("INSERT INTO chunks(id,tenant_id,knowledge_base_id,knowledge_id,chunk_type,tag_id,seq_id,chunk_index,start_at,end_at,content) VALUES(?,1,'kb','faq-doc','faq','tag',?,0,0,0,'FAQ content')", id, i+1).Error)
		}
		meta := `{"content":"Old manual text","format":"markdown","status":"publish","version":2,"updated_at":"2026-01-01T00:00:00Z"}`
		if strings.HasSuffix(scene.name, "-metadata-null") {
			meta = `null`
		}
		if strings.HasSuffix(scene.name, "-metadata-array") {
			meta = `[]`
		}
		if strings.HasSuffix(scene.name, "-metadata-invalid-version") {
			meta = `{"content":"Old","version":"bad"}`
		}
		if strings.HasSuffix(scene.name, "-preserve-transfer") {
			meta = `{"content":"Old","version":3,"_knowledge_transfer":{"operation":"copy","phase":"moving"},"custom":"discarded"}`
		}
		if strings.HasSuffix(scene.name, "-preserve-process") {
			meta = `{"content":"Old","version":3,"process_overrides":{"summary_enabled":false},"custom":"discarded"}`
		}
		must(db.Exec("INSERT INTO knowledges(id,tenant_id,knowledge_base_id,type,source,title,parse_status,enable_status,description,file_name,file_type,embedding_model_id,metadata,created_at,updated_at) VALUES('doc',1,'kb','manual','manual','Old title','completed','enabled','Old description','Old title.md','manual','old-embedding',?,?,?)", meta, time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC), time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)).Error)
		if strings.HasSuffix(scene.name, "-not-manual") {
			must(db.Exec("UPDATE knowledges SET type='file' WHERE id='doc'").Error)
		}
		if strings.HasSuffix(scene.name, "-missing-doc") {
			must(db.Exec("DELETE FROM knowledges WHERE id='doc'").Error)
		}
		if strings.Contains(scene.name, "document-") {
			must(db.Exec("UPDATE knowledge_bases SET type='document' WHERE id='kb'").Error)
			must(db.Exec("INSERT INTO knowledges(id,tenant_id,knowledge_base_id,type,source,title,parse_status,created_at,updated_at) VALUES('faq-doc',1,'kb','file','manual','Document','completed',?,?)", time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC), time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)).Error)
			must(db.Exec("UPDATE chunks SET chunk_type='text'").Error)
			if scene.name != "document-empty-ref" {
				must(db.Exec("INSERT INTO knowledge_tag_relations(knowledge_id,tag_id) VALUES('faq-doc','tag')").Error)
			}
		}
		br, kr, cr, tr := repo.NewKnowledgeBaseRepository(db), repo.NewKnowledgeRepository(db), repo.NewChunkRepository(db), repo.NewKnowledgeTagRepository(db)
		audit := svc.NewAuditLogService(repo.NewAuditLogRepository(db))
		shares := svc.NewKBShareService(repo.NewKBShareRepository(db), repo.NewOrganizationRepository(db), nil, nil, nil, audit)
		agents := svc.NewAgentShareService(repo.NewAgentShareRepository(db), repo.NewTenantDisabledSharedAgentRepository(db), repo.NewOrganizationRepository(db), repo.NewCustomAgentRepository(db), nil, nil, nil)
		kbs := svc.NewKnowledgeBaseService(br, kr, cr, nil, shares, nil, nil, nil, repo.NewTenantRepository(db), nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil)
		redis, e := miniredis.Run()
		must(e)
		client := asynq.NewClient(asynq.RedisClientOpt{Addr: redis.Addr()})
		captured := &capture{client: client, rows: []any{}}
		captured.fail = strings.HasSuffix(scene.name, "-queue-error")
		tags, e := svc.NewKnowledgeTagService(kbs, tr, kr, cr, nil, nil, nil, captured, shares, repo.NewTenantRepository(db), audit)
		must(e)
		enabled := true
		cfg := &config.Config{Tenant: &config.TenantConfig{EnableRBAC: &enabled}}
		kbHandler := httpHandler.NewKnowledgeBaseHandler(cfg, kbs, nil, shares, agents, nil, nil, nil, nil, nil, nil)
		local := filesvc.NewLocalFileService(folder, "")
		kg, e := svc.NewKnowledgeService(&config.Config{}, kr, nil, kbs, repo.NewTenantRepository(db), nil, nil, cr, tr, tags, local, nil, nil, nil, captured, nil, nil, nil, nil, nil, shares, nil, nil, nil, nil, nil, audit)
		must(e)
		handler := httpHandler.NewKnowledgeHandler(cfg, kg, kbs, shares, agents, nil, nil, nil, captured, nil, nil)
		role, user := types.TenantRoleAdmin, "caller"
		callerTenant := uint64(1)
		if strings.Contains(scene.name, "-shared-") {
			callerTenant = 2
			permission := "editor"
			if strings.HasSuffix(scene.name, "-shared-viewer") {
				permission = "viewer"
			}
			if strings.HasSuffix(scene.name, "-shared-role-viewer") {
				role = types.TenantRoleViewer
			}
			must(db.Exec("INSERT INTO organizations(id,name,owner_tenant_id,owner_id) VALUES('org','Org',1,'caller')").Error)
			if !strings.HasSuffix(scene.name, "-shared-no-source") {
				must(db.Exec("INSERT INTO organization_tenant_members(id,organization_id,tenant_id,role) VALUES('source','org',1,'admin')").Error)
			}
			must(db.Exec("INSERT INTO organization_tenant_members(id,organization_id,tenant_id,role) VALUES('member','org',2,'editor')").Error)
			must(db.Exec("INSERT INTO kb_shares(id,knowledge_base_id,organization_id,source_tenant_id,permission,shared_by_user_id) VALUES('share','kb','org',1,?,'caller')", permission).Error)
			if strings.HasSuffix(scene.name, "-shared-revoked") {
				must(db.Exec("UPDATE kb_shares SET deleted_at=CURRENT_TIMESTAMP WHERE id='share'").Error)
			}
		}
		if strings.HasSuffix(scene.name, "-creator-viewer") {
			role = types.TenantRoleViewer
		}
		if strings.HasSuffix(scene.name, "-noncreator-contributor") {
			role = types.TenantRoleContributor
			user = "intruder"
		}
		machine := strings.Contains(scene.name, "-api-")
		scope := types.TenantAPIKeyScope{Capabilities: types.StringArray{string(types.APIKeyCapabilityIngest)}, KnowledgeBaseIDs: []string{"kb"}}
		if strings.Contains(scene.name, "retrieve") {
			scope.Capabilities = types.StringArray{string(types.APIKeyCapabilityRetrieve)}
		}
		if strings.HasSuffix(scene.name, "-api-wrong-kb") {
			scope.KnowledgeBaseIDs = []string{"other-kb"}
		}
		if scene.name == "api-document-audit" {
			scope.KeyID = 77
			scope.Name = "Automation"
			user = "system-1"
		}
		if strings.HasSuffix(scene.name, "-api-full") {
			scope.FullAccess = true
		}
		router := gin.New()
		router.Use(middleware.ErrorHandler(), middleware.AuditServiceProvider(audit))
		router.Use(func(c *gin.Context) {
			ctx := context.WithValue(c.Request.Context(), types.TenantIDContextKey, callerTenant)
			ctx = context.WithValue(ctx, types.UserIDContextKey, user)
			ctx = context.WithValue(ctx, types.TenantRoleContextKey, role)
			if machine {
				ctx = types.WithTenantAPIKeyScope(ctx, scope)
			}
			c.Request = c.Request.WithContext(ctx)
			c.Set(types.TenantIDContextKey.String(), callerTenant)
			c.Set(types.UserIDContextKey.String(), user)
			c.Next()
		})
		gate := middleware.NewAPIKeyRouteAuthorizer()
		createPath := "/api/v1/knowledge-bases/:id/knowledge/manual"
		updatePath := "/api/v1/knowledge/manual/:id"
		for _, p := range []string{createPath, updatePath} {
			method := "POST"
			if p == updatePath {
				method = "PUT"
			}
			gate.Register(method, p, middleware.APIKeyRoutePolicy{RequireFullAccess: true}.WithCapability(types.APIKeyCapabilityIngest))
		}
		router.POST(createPath, gate.Middleware(), middleware.RequireOwnershipOrRole(types.TenantRoleAdmin, kbHandler.KBCreatorLookup, cfg), middleware.RequireKBAccess(middleware.KBIDFromParam("id"), types.OrgRoleEditor, kbs, shares, agents, cfg), handler.CreateManualKnowledge)
		router.PUT(updatePath, gate.Middleware(), middleware.RequireOwnershipOrRole(types.TenantRoleAdmin, handler.KBCreatorLookupFromKnowledgeID, cfg), middleware.RequireKBAccess(middleware.KBIDFromKnowledgeIDParam("id", kg), types.OrgRoleEditor, kbs, shares, agents, cfg), handler.UpdateManualKnowledge)
		url := "/api/v1/knowledge-bases/kb/knowledge/manual"
		if scene.method == "PUT" {
			url = "/api/v1/knowledge/manual/doc"
		}
		request := httptest.NewRequest(scene.method, url, strings.NewReader(scene.body))
		request.Header.Set("Content-Type", "application/json")
		response := httptest.NewRecorder()
		router.ServeHTTP(response, request)
		var body any
		decoder := json.NewDecoder(bytes.NewReader(response.Body.Bytes()))
		decoder.UseNumber()
		must(decoder.Decode(&body))
		var rows, audits []map[string]any
		must(db.Table("knowledges").Select("id,tenant_id,knowledge_base_id,type,title,description,source,channel,parse_status,pending_subtasks_count,summary_status,enable_status,embedding_model_id,file_name,file_type,storage_size,metadata,custom_metadata,error_message,processed_at").Order("id").Find(&rows).Error)
		must(db.Table("audit_logs").Select("tenant_id,actor_user_id,actor_role,action,target_type,target_id,scope_type,scope_id,outcome,details").Where("action IN ?", []string{"knowledge.created", "knowledge.updated"}).Order("id").Find(&audits).Error)
		var relations []map[string]any
		must(db.Table("knowledge_tag_relations").Select("knowledge_id,tag_id").Order("knowledge_id,tag_id").Find(&relations).Error)
		out = append(out, map[string]any{"name": scene.name, "method": scene.method, "url": url, "request": scene.body, "status": response.Code, "body": body, "knowledges": rows, "tasks": captured.rows, "role": role, "user": user, "machine": machine, "scope": scope, "caller_tenant": callerTenant, "audits": audits, "relations": relations})
		client.Close()
		redis.Close()
		sql.Close()
		os.RemoveAll(folder)
	}
	b, e := json.Marshal(out)
	must(e)
	must(os.WriteFile(os.Args[1], append(b, '\n'), 0600))
	fmt.Println("exported", len(out), "actual Go manual create/update/middleware/SQL/task scenes")
}
