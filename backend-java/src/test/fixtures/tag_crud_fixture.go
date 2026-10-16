//go:build ignore

// Next-batch audit: real Go tag CRUD handler, role/KB/API-key middleware, SQL and Asynq producer.
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
	pgDSN := os.Getenv("WEKNORA_TAG_PARITY_PG_DSN")
	pg := pgDSN != ""
	if pg {
		u, e := url.Parse(pgDSN)
		must(e)
		if u.Hostname() != "127.0.0.1" || u.Path != "/weknora_tag_parity" {
			panic("tag parity only permits its owned loopback weknora_tag_parity database")
		}
		must(os.Setenv("RETRIEVE_DRIVER", "postgres"))
	} else {
		must(os.Setenv("RETRIEVE_DRIVER", "sqlite"))
	}
	cases := []scenario{
		{"list", "GET", "", ""}, {"list-empty-page", "GET", "?page=2&page_size=10", ""}, {"list-zero", "GET", "?page=0&page_size=0", ""}, {"list-negative", "GET", "?page=-1", ""}, {"list-oversized", "GET", "?page_size=1001", ""}, {"list-invalid", "GET", "?page=x", ""}, {"list-empty-param", "GET", "?page=&page_size=", ""}, {"list-keyword", "GET", "?keyword=Tag", ""}, {"list-percent", "GET", "?keyword=%25", ""}, {"list-underscore", "GET", "?keyword=_", ""},
		{"create", "POST", "", `{"name":" New ","color":" #fff ","sort_order":4}`}, {"create-empty", "POST", "", ``}, {"create-null", "POST", "", `null`}, {"create-object", "POST", "", `{}`}, {"create-array", "POST", "", `[]`}, {"create-space", "POST", "", `{"name":" \t "}`}, {"create-duplicate", "POST", "", `{"name":" Tag "}`}, {"create-number-name", "POST", "", `{"name":2}`}, {"create-fraction", "POST", "", `{"name":"New","sort_order":1.0}`}, {"create-untagged", "POST", "", `{"name":"未分类","sort_order":42}`}, {"create-casefold", "POST", "", `{"NAME":"Fold","SORT_ORDER":-4}`}, {"create-trailing", "POST", "", `{"name":"First"}{"name":"Second"}`},
		{"update", "PUT", "/tag", `{"name":" Renamed ","color":" #abc ","sort_order":-2}`}, {"update-seq", "PUT", "/1", `{"name":"Seq"}`}, {"update-plus-seq", "PUT", "/+1", `{"name":"Seq"}`}, {"update-empty", "PUT", "/tag", `{}`}, {"update-null", "PUT", "/tag", `null`}, {"update-null-fields", "PUT", "/tag", `{"name":null,"color":null,"sort_order":null}`}, {"update-empty-name", "PUT", "/tag", `{"name":" "}`}, {"update-name-number", "PUT", "/tag", `{"name":1}`}, {"update-color-number", "PUT", "/tag", `{"color":1}`}, {"update-sort-fraction", "PUT", "/tag", `{"sort_order":1.0}`}, {"update-missing", "PUT", "/missing", `{}`}, {"update-other-kb", "PUT", "/other-tag", `{"name":"Foreign"}`},
		{"delete-referenced", "DELETE", "/tag", ``}, {"delete-force", "DELETE", "/tag?force=true", ``}, {"delete-content-only", "DELETE", "/tag?content_only=true", ``}, {"delete-force-upper", "DELETE", "/tag?force=TRUE", ``}, {"delete-null", "DELETE", "/tag?force=true", `null`}, {"delete-malformed", "DELETE", "/tag?force=true", `{`}, {"delete-exclude", "DELETE", "/tag?force=true", `{"exclude_ids":[2]}`}, {"delete-exclude-missing", "DELETE", "/tag?force=true", `{"exclude_ids":[999]}`}, {"delete-exclude-zero", "DELETE", "/tag?force=true", `{"exclude_ids":[0]}`}, {"delete-exclude-fraction", "DELETE", "/tag?force=true", `{"exclude_ids":[1.0]}`}, {"delete-other-kb", "DELETE", "/other-tag?force=true", ``},
		{"creator-viewer", "POST", "", `{"name":"Creator"}`}, {"noncreator-contributor", "POST", "", `{"name":"Unowned"}`}, {"api-retrieve-create", "POST", "", `{"name":"Scoped"}`}, {"api-ingest-create", "POST", "", `{"name":"Scoped"}`}, {"api-retrieve-list", "GET", "", ``}, {"api-ingest-list", "GET", "", ``}, {"api-wrong-kb", "POST", "", `{"name":"Scoped"}`},
	}
	cases = append(cases, []scenario{
		{"create-null-name", "POST", "", `{"name":null}`}, {"create-null-sort", "POST", "", `{"name":"Nullable","sort_order":null}`},
		{"create-max-sort", "POST", "", `{"name":"Max","sort_order":9223372036854775807}`}, {"create-overflow", "POST", "", `{"name":"Overflow","sort_order":9223372036854775808}`},
		{"create-duplicate-key", "POST", "", `{"name":"First","name":"Last"}`}, {"create-duplicate-null", "POST", "", `{"name":"First","name":null}`},
		{"create-name-controls", "POST", "", `{"name":"A\nB\tC\u0001","color":"C\rD"}`}, {"create-name-unicode-space", "POST", "", `{"name":"\u00a0Unicode\u3000"}`},
		{"update-duplicate-name", "PUT", "/tag", `{"name":"First","name":"Last"}`}, {"update-name-controls", "PUT", "/tag", `{"name":"A\nB\tC\u0001"}`},
		{"update-seq-missing", "PUT", "/999", `{}`}, {"update-seq-overflow", "PUT", "/9223372036854775808", `{}`},
		{"delete-empty-object", "DELETE", "/tag?force=true", `{}`}, {"delete-array", "DELETE", "/tag?force=true", `[]`}, {"delete-duplicate-exclude", "DELETE", "/tag?force=true", `{"exclude_ids":[2,2]}`},
		{"list-overflow", "GET", "?page=9223372036854775808", ""},
		{"shared-list", "GET", "", ""}, {"shared-create", "POST", "", `{"name":"Shared"}`}, {"shared-update", "PUT", "/tag", `{"name":"Shared"}`}, {"shared-delete", "DELETE", "/tag?force=true", ""},
		{"shared-viewer-create", "POST", "", `{"name":"Denied"}`}, {"shared-role-viewer-create", "POST", "", `{"name":"Denied"}`}, {"shared-revoked-list", "GET", "", ""}, {"shared-no-source-list", "GET", "", ""},
	}...)
	if os.Getenv("WEKNORA_TAG_DOCUMENT_PARITY") == "true" {
		cases = []scenario{
			{"document-force", "DELETE", "/tag?force=true", ""}, {"document-content", "DELETE", "/tag?content_only=true", ""},
			{"document-referenced", "DELETE", "/tag", ""}, {"document-empty-ref", "DELETE", "/tag?force=true", ""},
			{"document-queue-error", "DELETE", "/tag?force=true", ""}, {"document-exclude", "DELETE", "/tag?force=true", `{"exclude_ids":[2]}`},
			{"shared-document-force", "DELETE", "/tag?force=true", ""}, {"shared-document-content", "DELETE", "/tag?content_only=true", ""},
			{"shared-document-viewer-force", "DELETE", "/tag?force=true", ""}, {"api-document-ingest", "DELETE", "/tag?force=true", ""},
			{"api-document-retrieve", "DELETE", "/tag?force=true", ""}, {"api-document-audit", "DELETE", "/tag?content_only=true", ""},
		}
	}
	out := []any{}
	for _, scene := range cases {
		folder, e := os.MkdirTemp("", "weknora-go-tag-public-")
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
			for _, m := range []string{"000000_init", "000003_knowledge_base_auto_tag_config", "000004_memory", "000007_system_admin_and_settings", "000008_processing_spans_and_pending_subtasks", "000010_knowledge_multi_tags", "000011_principal_model", "000021_knowledge_profiles", "000031_model_catalog_config", "000033_chunk_source_locators"} {
				b, e := os.ReadFile("migrations/sqlite/" + m + ".up.sql")
				must(e)
				must(db.Exec(string(b)).Error)
			}
		} else {
			must(db.Exec("TRUNCATE tenants,knowledge_bases,knowledge_tags,knowledge_tag_relations,knowledges,tenant_api_keys,chunks,organizations,organization_tenant_members,kb_shares,audit_logs RESTART IDENTITY CASCADE").Error)
			must(db.Exec("ALTER SEQUENCE knowledge_tags_seq_id_seq RESTART WITH 10000000").Error)
		}
		must(db.Exec("INSERT INTO tenants(id,name,business) VALUES(1,'owner','isolated'),(2,'caller','isolated')").Error)
		must(db.Exec("INSERT INTO knowledge_bases(id,tenant_id,creator_id,name,type,embedding_model_id,summary_model_id) VALUES('kb',1,'caller','KB','faq','embedding',''),('other-kb',1,'other','Other','faq','embedding','')").Error)
		must(db.Exec("INSERT INTO knowledge_tags(id,tenant_id,knowledge_base_id,name,seq_id,color,sort_order,created_at,updated_at) VALUES('tag',1,'kb','Tag',1,'#fff',0,'2026-01-01 00:00:00','2026-01-01 00:00:00'),('other-tag',1,'other-kb','Other',2,'',0,'2026-01-01 00:00:00','2026-01-01 00:00:00')").Error)
		for i, id := range []string{"chunk-1", "chunk-2"} {
			must(db.Exec("INSERT INTO chunks(id,tenant_id,knowledge_base_id,knowledge_id,chunk_type,tag_id,seq_id,chunk_index,start_at,end_at,content) VALUES(?,1,'kb','faq-doc','faq','tag',?,0,0,0,'FAQ content')", id, i+1).Error)
		}
		if strings.Contains(scene.name, "document-") {
			must(db.Exec("UPDATE knowledge_bases SET type='document' WHERE id='kb'").Error)
			must(db.Exec("INSERT INTO knowledges(id,tenant_id,knowledge_base_id,type,source,title,parse_status,created_at,updated_at) VALUES('faq-doc',1,'kb','file','manual','Document','completed',?,?)", time.Date(2026,1,1,0,0,0,0,time.UTC), time.Date(2026,1,1,0,0,0,0,time.UTC)).Error)
			must(db.Exec("UPDATE chunks SET chunk_type='text'").Error)
			if scene.name != "document-empty-ref" { must(db.Exec("INSERT INTO knowledge_tag_relations(knowledge_id,tag_id) VALUES('faq-doc','tag')").Error) }
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
		captured.fail = scene.name == "document-queue-error"
		tags, e := svc.NewKnowledgeTagService(kbs, tr, kr, cr, nil, nil, nil, captured, shares, repo.NewTenantRepository(db), audit)
		must(e)
		enabled := true
		cfg := &config.Config{Tenant: &config.TenantConfig{EnableRBAC: &enabled}}
		kbHandler := httpHandler.NewKnowledgeBaseHandler(cfg, kbs, nil, shares, agents, nil, nil, nil, nil, nil, nil)
		handler := httpHandler.NewTagHandler(tags, tr, cr)
		role, user := types.TenantRoleAdmin, "caller"
		callerTenant := uint64(1)
		if strings.HasPrefix(scene.name, "shared-") {
			callerTenant = 2
			permission := "editor"
			if scene.name == "shared-viewer-create" || scene.name == "shared-document-viewer-force" {
				permission = "viewer"
			}
			if scene.name == "shared-role-viewer-create" {
				role = types.TenantRoleViewer
			}
			must(db.Exec("INSERT INTO organizations(id,name,owner_tenant_id,owner_id) VALUES('org','Org',1,'caller')").Error)
			if scene.name != "shared-no-source-list" {
				must(db.Exec("INSERT INTO organization_tenant_members(id,organization_id,tenant_id,role) VALUES('source','org',1,'admin')").Error)
			}
			must(db.Exec("INSERT INTO organization_tenant_members(id,organization_id,tenant_id,role) VALUES('member','org',2,'editor')").Error)
			must(db.Exec("INSERT INTO kb_shares(id,knowledge_base_id,organization_id,source_tenant_id,permission,shared_by_user_id) VALUES('share','kb','org',1,?,'caller')", permission).Error)
			if scene.name == "shared-revoked-list" {
				must(db.Exec("UPDATE kb_shares SET deleted_at=CURRENT_TIMESTAMP WHERE id='share'").Error)
			}
		}
		if scene.name == "creator-viewer" {
			role = types.TenantRoleViewer
		}
		if scene.name == "noncreator-contributor" {
			role = types.TenantRoleContributor
			user = "intruder"
		}
		machine := strings.HasPrefix(scene.name, "api-")
		scope := types.TenantAPIKeyScope{Capabilities: types.StringArray{string(types.APIKeyCapabilityIngest)}, KnowledgeBaseIDs: []string{"kb"}}
		if strings.Contains(scene.name, "retrieve") {
			scope.Capabilities = types.StringArray{string(types.APIKeyCapabilityRetrieve)}
		}
		if scene.name == "api-wrong-kb" {
			scope.KnowledgeBaseIDs = []string{"other-kb"}
		}
		if scene.name == "api-document-audit" { scope.KeyID=77; scope.Name="Automation"; user="system-1" }
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
		template := "/api/v1/knowledge-bases/:id/tags"
		gate := middleware.NewAPIKeyRouteAuthorizer()
		for _, method := range []string{"GET", "POST", "PUT", "DELETE"} {
			path := template
			if method == "PUT" || method == "DELETE" {
				path += "/:tag_id"
			}
			cap := types.APIKeyCapabilityIngest
			if method == "GET" {
				cap = types.APIKeyCapabilityRetrieve
			}
			gate.Register(method, path, middleware.APIKeyRoutePolicy{RequireFullAccess: true}.WithCapability(cap))
		}
		router.GET(template, gate.Middleware(), middleware.RequireRole(types.TenantRoleViewer, cfg), middleware.RequireKBAccess(middleware.KBIDFromParam("id"), types.OrgRoleViewer, kbs, shares, agents, cfg), handler.ListTags)
		writer := middleware.RequireOwnershipOrRole(types.TenantRoleAdmin, kbHandler.KBCreatorLookup, cfg)
		writeKB := middleware.RequireKBAccess(middleware.KBIDFromParam("id"), types.OrgRoleEditor, kbs, shares, agents, cfg)
		router.POST(template, gate.Middleware(), writer, writeKB, handler.CreateTag)
		router.PUT(template+"/:tag_id", gate.Middleware(), writer, writeKB, handler.UpdateTag)
		router.DELETE(template+"/:tag_id", gate.Middleware(), writer, writeKB, handler.DeleteTag)
		request := httptest.NewRequest(scene.method, "/api/v1/knowledge-bases/kb/tags"+scene.target, strings.NewReader(scene.body))
		request.Header.Set("Content-Type", "application/json")
		response := httptest.NewRecorder()
		router.ServeHTTP(response, request)
		var body any
		decoder := json.NewDecoder(bytes.NewReader(response.Body.Bytes()))
		decoder.UseNumber()
		must(decoder.Decode(&body))
		var rows,audits []map[string]any
		must(db.Table("knowledge_tags").Select("id,seq_id,tenant_id,knowledge_base_id,name,color,sort_order").Order("seq_id").Find(&rows).Error)
		var active, deleted int64
		db.Model(&types.Chunk{}).Count(&active)
		db.Unscoped().Model(&types.Chunk{}).Where("deleted_at IS NOT NULL").Count(&deleted)
		must(db.Table("audit_logs").Select("tenant_id,actor_user_id,actor_role,action,target_type,target_id,scope_type,scope_id,outcome,details").Where("action IN ?",[]string{"tag.created","tag.updated","tag.deleted"}).Order("id").Find(&audits).Error)
		out = append(out, map[string]any{"name": scene.name, "method": scene.method, "target": scene.target, "request": scene.body, "status": response.Code, "body": body, "tags": rows, "active_chunks": active, "deleted_chunks": deleted, "tasks": captured.rows, "role": role, "user": user, "machine": machine, "scope": scope, "caller_tenant": callerTenant,"audits":audits})
		client.Close()
		redis.Close()
		sql.Close()
		os.RemoveAll(folder)
	}
	b, e := json.Marshal(out)
	must(e)
	must(os.WriteFile(os.Args[1], append(b, '\n'), 0600))
	fmt.Println("exported", len(out), "production Go tag CRUD/middleware/SQL/task scenes")
}
