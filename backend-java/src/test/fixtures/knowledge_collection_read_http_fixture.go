//go:build ignore

// Actual Gin handlers, SQL repositories, organization/agent access, role/API-key policies.
package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	repo "github.com/Tencent/WeKnora/internal/application/repository"
	svc "github.com/Tencent/WeKnora/internal/application/service"
	appconfig "github.com/Tencent/WeKnora/internal/config"
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

func must(e error) {
	if e != nil {
		panic(e)
	}
}

type scene struct{ Name, Path, Query, Variant string }

func main() {
	time.Local = time.UTC
	logger.SetOutput(os.Stderr)
	gin.SetMode(gin.TestMode)
	scenes := []scene{}
	add := func(path, query, variant string) {
		scenes = append(scenes, scene{fmt.Sprintf("%s-%03d-%s", path, len(scenes), variant), path, query, variant})
	}
	for _, q := range []string{"", "ids=", "ids=d1", "ids=d2&ids=d1&ids=d2&ids=shared&ids=private&ids=foreign&ids=missing", "ids=d1,d2", "ids[]=d1", "ids=deleted", "ids=deleting", "ids=orphan", "ids=pending", "ids=processing", "ids=finalizing", "ids=failed", "ids=d1&kb_id=kb", "ids=d1&ids=shared&kb_id=shared-kb", "ids=d1&kb_id=private-kb", "ids=d1&kb_id=missing", "ids=d1&kb_id=kb%0A", "ids=d1&agent_source_tenant_id=", "ids=d1&agent_source_tenant_id=0", "ids=d1&agent_source_tenant_id=+2", "ids=d1&agent_source_tenant_id=%2B2", "ids=d1&agent_source_tenant_id=-1", "ids=d1&agent_source_tenant_id=18446744073709551615", "ids=d1&agent_source_tenant_id=18446744073709551616", "agent_source_tenant_id=bad", "ids=d1&agent_source_tenant_id=bad"} {
		add("batch", q, "ok")
	}
	for _, q := range []string{"", "keyword=", "query=Document", "keyword=missing&query=Document", "keyword=%20&query=Document", "recent=true", "recent=1", "recent=TRUE", "recent=yes", "keyword=document", "keyword=D_", "keyword=100%25", "keyword=back%5C", "keyword=%E4%B8%AD%E6%96%87", "keyword=document&offset=1&limit=2", "keyword=document&offset=999", "keyword=document&offset=-1&limit=0", "keyword=document&limit=0", "keyword=document&limit=101", "keyword=document&limit=100", "keyword=document&offset=9223372036854775808", "keyword=document&limit=NaN", "recent=true&file_types=.XLSX", "recent=true&file_types=doc", "recent=true&file_types=png", "recent=true&file_types=URL", "recent=true&file_types=html", "recent=true&file_types=.%25", "recent=true&file_types=txt,,csv", "recent=true&file_types=.%20", "recent=true&agent_source_tenant_id=bad"} {
		add("search", q, "ok")
	}
	for _, variant := range []string{"viewer", "contributor", "unknown-role", "no-user", "api-retrieve", "api-full", "api-ingest", "api-empty", "api-wrong-kb", "api-unrestricted", "api-shared-kb", "api-full-wrong-kb", "revoked-share", "source-missing", "org-deleted", "source-mismatch", "share-table-missing", "kb-table-missing", "documents-missing", "all-deleted", "no-kbs", "spans-missing", "shared-invalid-permission"} {
		add("batch", "ids=d2&ids=d1&ids=shared&ids=private&ids=orphan&ids=pending", variant)
		add("search", "recent=true&limit=2", variant)
	}
	for _, mode := range []string{"all", "selected", "none", "invalid", "selected-empty", "quick", "graph", "wiki", "auxiliary", "primary", "builtin", "builtin-id", "deleted-agent", "agent-missing", "invalid-config", "revoked-agent", "agent-source-missing", "disabled-agent", "other-source", "agent-table-missing"} {
		for _, path := range []string{"batch", "search"} {
			q := "agent_id=agent&agent_source_tenant_id=2"
			if path == "batch" {
				q += "&ids=d1&ids=shared&ids=private&ids=foreign"
			} else {
				q += "&recent=true"
			}
			add(path, q, mode)
		}
	}
	for _, q := range []string{"agent_id=agent", "agent_id=agent&agent_source_tenant_id=3", "agent_id=agent&agent_source_tenant_id=1", "agent_id=agent&agent_source_tenant_id=bad", "agent_id=agent&agent_source_tenant_id=%202%20", "agent_id=agent%0A&agent_source_tenant_id=2", "agent_id=missing", "agent_id=agent&kb_id=kb", "agent_id=agent&kb_id=shared-kb", "agent_id=agent&kb_id=private-kb", "agent_id=agent&kb_id=foreign-kb"} {
		add("batch", q+"&ids=shared&ids=private&ids=foreign", "selected")
		add("search", q+"&recent=true", "selected")
	}
	for _, variant := range []string{"api-retrieve", "api-full", "api-shared-kb", "api-wrong-kb", "no-user"} {
		add("batch", "ids=shared&ids=private&agent_id=agent&agent_source_tenant_id=2", variant)
		add("search", "recent=true&agent_id=agent&agent_source_tenant_id=2", variant)
	}
	for _, variant := range []string{"full-span-time", "empty-permission", "empty-org-role", "invalid-org-role", "deleted-kb", "wiki-bytes", "null-config"} {
		add("batch", "ids=processing&ids=shared", variant)
		add("search", "recent=true&agent_id=agent&agent_source_tenant_id=2", variant)
	}
	for _, q := range []string{"keyword=document&limit=%2B1", "keyword=document&offset=%2B0", "keyword=document&offset=%20%20", "keyword=document&file_types=.JpG", "keyword=Document&file_types=%25", "keyword=%C4%B0", "keyword=missing&recent=true", "keyword=Document&limit=1&limit=0"} {
		add("search", q, "ok")
	}
	for _, q := range []string{"ids=%20d1%20", "ids=d1&ids=shared&ids=private&kb_id=shared-kb", "ids=d1&agent_source_tenant_id=%20", "ids=d1&agent_source_tenant_id=0002", "ids=d1&agent_source_tenant_id=2&agent_source_tenant_id=bad"} {
		add("batch", q, "ok")
	}

	out := []any{}
	for _, s := range scenes {
		out = append(out, run(s))
	}
	b, e := json.Marshal(out)
	must(e)
	must(os.WriteFile(os.Args[1], append(b, '\n'), 0600))
	fmt.Println("exported", len(out), "actual Go collection HTTP cases")
}
func run(s scene) any {
	dir, e := os.MkdirTemp("", "weknora-go-collection-")
	must(e)
	defer os.RemoveAll(dir)
	var dial gorm.Dialector = sqlite.Open(filepath.Join(dir, "case.db"))
	dsn := os.Getenv("WEKNORA_COLLECTION_PARITY_PG_DSN")
	if dsn != "" {
		u, e := url.Parse(dsn)
		must(e)
		if u.Hostname() != "127.0.0.1" || u.Path != "/weknora_file_parity" {
			panic("owned loopback collection database required")
		}
		dial = postgres.Open(dsn)
	}
	db, e := gorm.Open(dial, &gorm.Config{Logger: gl.Default.LogMode(gl.Silent)})
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
		must(db.Exec("TRUNCATE tenants,knowledge_bases,knowledges,organizations,organization_tenant_members,kb_shares,agent_shares,custom_agents,knowledge_processing_spans,audit_logs,tenant_disabled_shared_agents RESTART IDENTITY CASCADE").Error)
	}
	must(db.Exec("INSERT INTO tenants(id,name,business) VALUES(1,'Caller','test'),(2,'Source','test'),(3,'Foreign','test')").Error)
	for _, kb := range []struct {
		id, typ string
		tenant  int
	}{{"kb", "document", 1}, {"shared-kb", "document", 2}, {"private-kb", "document", 2}, {"foreign-kb", "document", 3}, {"faq-kb", "faq", 1}, {"wiki-kb", "document", 2}} {
		must(db.Exec("INSERT INTO knowledge_bases(id,tenant_id,creator_id,name,type,embedding_model_id,summary_model_id) VALUES(?,?,'caller',?,?,'','')", kb.id, kb.tenant, kb.id+" name", kb.typ).Error)
	}
	must(db.Exec("UPDATE knowledge_bases SET indexing_strategy=? WHERE id='wiki-kb'", `{"vector_enabled":false,"keyword_enabled":false,"wiki_enabled":true}`).Error)
	for i, d := range []struct {
		id, kb, filename, typ, status string
		tenant                        int
	}{{"d1", "kb", "Document.XLSX", "file", "completed", 1}, {"d2", "kb", "Document.doc", "file", "completed", 1}, {"shared", "shared-kb", "Document.png", "file", "completed", 2}, {"private", "private-kb", "Document.jpeg", "file", "completed", 2}, {"foreign", "foreign-kb", "Document.txt", "file", "completed", 3}, {"deleted", "kb", "Document.txt", "file", "completed", 1}, {"deleting", "kb", "Document.csv", "file", "deleting", 1}, {"orphan", "missing-kb", "Document.txt", "file", "completed", 1}, {"faq", "faq-kb", "Document.txt", "file", "completed", 1}, {"url", "kb", "Document.html", "url", "completed", 1}, {"special", "kb", "D_100% back\\中文.txt", "file", "completed", 1}, {"pending", "kb", "Document.txt", "file", "pending", 1}, {"processing", "kb", "Document.txt", "file", "processing", 1}, {"finalizing", "kb", "Document.txt", "file", "finalizing", 1}, {"failed", "kb", "Document.txt", "file", "failed", 1}, {"wiki", "wiki-kb", "Document.txt", "file", "completed", 2}} {
		when := time.Date(2026, 1, 1, 0, i, 0, 0, time.UTC)
		must(db.Exec("INSERT INTO knowledges(id,tenant_id,knowledge_base_id,type,title,file_name,source,parse_status,created_at,updated_at,custom_metadata) VALUES(?,?,?,?,?,?,'manual',?,?,?,?)", d.id, d.tenant, d.kb, d.typ, d.filename, d.filename, d.status, when, when, []byte(`{"source":"seed"}`)).Error)
	}
	must(db.Exec("UPDATE knowledges SET deleted_at='2026-01-02' WHERE id='deleted'").Error)
	must(db.Exec("INSERT INTO knowledge_processing_spans(knowledge_id,attempt,span_id,name,kind,status,created_at,updated_at) VALUES('processing',2,'span','chunk','stage','running','2026-02-01','2026-02-01')").Error)
	must(db.Exec("INSERT INTO organizations(id,name,owner_tenant_id,owner_id) VALUES('org','Org',2,'owner')").Error)
	must(db.Exec("INSERT INTO organization_tenant_members(id,organization_id,tenant_id,role) VALUES('source','org',2,'admin'),('member','org',1,'editor')").Error)
	must(db.Exec("INSERT INTO kb_shares(id,knowledge_base_id,organization_id,source_tenant_id,permission,shared_by_user_id) VALUES('share','shared-kb','org',2,'viewer','owner')").Error)
	mode := "all"
	if s.Variant == "selected" || s.Variant == "selected-empty" {
		mode = "selected"
	}
	if s.Variant == "none" || s.Variant == "invalid" {
		mode = s.Variant
	}
	config := map[string]any{"kb_selection_mode": mode, "knowledge_bases": []string{"shared-kb"}}
	if s.Variant == "selected-empty" {
		config["knowledge_bases"] = []string{""}
	}
	switch s.Variant {
	case "quick":
		config["agent_mode"] = "quick-answer"
	case "graph":
		config["allowed_tools"] = []string{"query_knowledge_graph"}
	case "wiki":
		config["allowed_tools"] = []string{"wiki_search"}
	case "auxiliary":
		config["allowed_tools"] = []string{"read_document"}
	case "primary":
		config["allowed_tools"] = []string{"read_document", "search_knowledge"}
	}
	cfgJSON, e := json.Marshal(config)
	must(e)
	must(db.Exec("INSERT INTO custom_agents(id,tenant_id,name,created_by,config) VALUES('agent',2,'Shared','owner',?)", string(cfgJSON)).Error)
	must(db.Exec("INSERT INTO agent_shares(id,agent_id,organization_id,source_tenant_id,permission,shared_by_user_id) VALUES('agent-share','agent','org',2,'viewer','owner')").Error)
	switch s.Variant {
	case "disabled-agent":
		must(db.Exec("INSERT INTO tenant_disabled_shared_agents(tenant_id,agent_id,source_tenant_id) VALUES(1,'agent',2)").Error)
	case "other-source":
		must(db.Exec("INSERT INTO organization_tenant_members(id,organization_id,tenant_id,role) VALUES('third','org',3,'editor')").Error)
		must(db.Exec("INSERT INTO custom_agents(id,tenant_id,name,created_by,config) VALUES('agent',3,'Third','other',?)", string(cfgJSON)).Error)
		must(db.Exec("INSERT INTO agent_shares(id,agent_id,organization_id,source_tenant_id,permission,shared_by_user_id) VALUES('0-first','agent','org',3,'viewer','other')").Error)
	case "full-span-time":
		must(db.Exec("UPDATE knowledge_processing_spans SET updated_at='2026-02-01T00:00:00Z'").Error)
	case "empty-permission":
		must(db.Exec("UPDATE kb_shares SET permission=''").Error)
	case "empty-org-role":
		must(db.Exec("UPDATE organization_tenant_members SET role='' WHERE id='member'").Error)
	case "invalid-org-role":
		must(db.Exec("UPDATE organization_tenant_members SET role='invalid' WHERE id='member'").Error)
	case "deleted-kb":
		must(db.Exec("UPDATE knowledge_bases SET deleted_at='2026-01-02' WHERE id IN ('kb','shared-kb')").Error)
	case "wiki-bytes":
		must(db.Exec("UPDATE knowledge_bases SET indexing_strategy=? WHERE id='wiki-kb'", []byte(`{"vector_enabled":false,"keyword_enabled":false,"wiki_enabled":true}`)).Error)
	case "null-config":
		must(db.Exec("UPDATE custom_agents SET config='null'").Error)
	case "revoked-share":
		must(db.Exec("UPDATE kb_shares SET deleted_at='2026-01-02'").Error)
	case "source-missing", "agent-source-missing":
		must(db.Exec("DELETE FROM organization_tenant_members WHERE id='source'").Error)
	case "org-deleted":
		must(db.Exec("UPDATE organizations SET deleted_at='2026-01-02'").Error)
	case "source-mismatch":
		must(db.Exec("UPDATE kb_shares SET source_tenant_id=3").Error)
	case "shared-invalid-permission":
		must(db.Exec("UPDATE kb_shares SET permission='invalid'").Error)
	case "builtin":
		must(db.Exec("UPDATE custom_agents SET is_builtin=true").Error)
	case "builtin-id":
		s.Query = strings.ReplaceAll(s.Query, "agent_id=agent", "agent_id="+types.GetBuiltinAgentIDs()[0])
	case "deleted-agent":
		must(db.Exec("UPDATE custom_agents SET deleted_at='2026-01-02'").Error)
	case "agent-missing":
		must(db.Exec("DELETE FROM custom_agents").Error)
	case "invalid-config":
		must(db.Exec("UPDATE custom_agents SET config=?", `{"kb_selection_mode":true}`).Error)
	case "revoked-agent":
		must(db.Exec("UPDATE agent_shares SET deleted_at='2026-01-02'").Error)
	case "all-deleted":
		must(db.Exec("UPDATE knowledges SET deleted_at='2026-01-02'").Error)
	case "no-kbs":
		must(db.Exec("DELETE FROM knowledge_bases").Error)
	}
	audit := svc.NewAuditLogService(repo.NewAuditLogRepository(db))
	shares := svc.NewKBShareService(repo.NewKBShareRepository(db), repo.NewOrganizationRepository(db), repo.NewKnowledgeBaseRepository(db), repo.NewKnowledgeRepository(db), repo.NewChunkRepository(db), audit)
	agents := svc.NewAgentShareService(repo.NewAgentShareRepository(db), repo.NewTenantDisabledSharedAgentRepository(db), repo.NewOrganizationRepository(db), repo.NewCustomAgentRepository(db), nil, nil, nil)
	br, kr, cr := repo.NewKnowledgeBaseRepository(db), repo.NewKnowledgeRepository(db), repo.NewChunkRepository(db)
	kbs := svc.NewKnowledgeBaseService(br, kr, cr, nil, shares, nil, nil, nil, repo.NewTenantRepository(db), nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil)
	kg, e := svc.NewKnowledgeService(&appconfig.Config{}, kr, nil, kbs, repo.NewTenantRepository(db), nil, nil, cr, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, shares, nil, nil, nil, nil, nil, audit)
	must(e)
	enabled := true
	cfg := &appconfig.Config{Tenant: &appconfig.TenantConfig{EnableRBAC: &enabled}}
	h := handler.NewKnowledgeHandler(cfg, kg, kbs, shares, agents, nil, nil, nil, nil, repo.NewKnowledgeSpanRepository(db), nil)
	role, user := types.TenantRoleAdmin, "caller"
	switch s.Variant {
	case "viewer":
		role = types.TenantRoleViewer
	case "contributor":
		role = types.TenantRoleContributor
	case "unknown-role":
		role = "unknown"
	case "no-user":
		user = ""
	}
	machine := strings.HasPrefix(s.Variant, "api-")
	scope := types.TenantAPIKeyScope{Capabilities: types.StringArray{"retrieve"}, KnowledgeBaseIDs: []string{"kb"}}
	if s.Variant == "api-ingest" {
		scope.Capabilities = types.StringArray{"ingest"}
	}
	if s.Variant == "api-empty" {
		scope.Capabilities = nil
	}
	if strings.HasPrefix(s.Variant, "api-full") {
		scope.FullAccess = true
	}
	if strings.HasSuffix(s.Variant, "wrong-kb") {
		scope.KnowledgeBaseIDs = []string{"missing"}
	}
	if s.Variant == "api-shared-kb" {
		scope.KnowledgeBaseIDs = []string{"shared-kb"}
	}
	if s.Variant == "api-unrestricted" {
		scope.KnowledgeBaseIDs = nil
	}
	seed := []any{}
	for _, table := range []string{"tenants", "knowledge_bases", "knowledges", "organizations", "organization_tenant_members", "kb_shares", "agent_shares", "custom_agents", "knowledge_processing_spans", "tenant_disabled_shared_agents"} {
		var rows []map[string]any
		must(db.Table(table).Find(&rows).Error)
		for _, row := range rows {
			if dsn == "" && table == "knowledge_processing_spans" {
				var times map[string]any
				must(db.Raw("SELECT CAST(created_at AS TEXT) AS created_at,CAST(updated_at AS TEXT) AS updated_at FROM knowledge_processing_spans WHERE id=?", row["id"]).Scan(&times).Error)
				row["created_at"] = times["created_at"]
				row["updated_at"] = times["updated_at"]
			}
			for k, v := range row {
				if b, ok := v.([]byte); ok {
					row[k] = map[string]any{"base64": base64.StdEncoding.EncodeToString(b)}
				}
			}
			seed = append(seed, map[string]any{"table": table, "row": row})
		}
	}
	hidden := ""
	switch s.Variant {
	case "documents-missing":
		hidden = "knowledges"
	case "kb-table-missing":
		hidden = "knowledge_bases"
	case "share-table-missing":
		hidden = "kb_shares"
	case "agent-table-missing":
		hidden = "agent_shares"
	case "spans-missing":
		hidden = "knowledge_processing_spans"
	}
	if hidden != "" {
		must(db.Exec("ALTER TABLE " + hidden + " RENAME TO hidden_" + hidden).Error)
		if dsn != "" {
			defer func() { must(db.Exec("ALTER TABLE hidden_" + hidden + " RENAME TO " + hidden).Error) }()
		}
	}
	r := gin.New()
	r.Use(middleware.ErrorHandler(), middleware.AuditServiceProvider(audit))
	r.Use(func(c *gin.Context) {
		ctx := context.WithValue(c.Request.Context(), types.TenantIDContextKey, uint64(1))
		ctx = context.WithValue(ctx, types.UserIDContextKey, user)
		ctx = context.WithValue(ctx, types.TenantRoleContextKey, role)
		if machine {
			ctx = types.WithTenantAPIKeyScope(ctx, scope)
		}
		c.Request = c.Request.WithContext(ctx)
		c.Set(types.TenantIDContextKey.String(), uint64(1))
		c.Set(types.UserIDContextKey.String(), user)
		c.Next()
	})
	gate := middleware.NewAPIKeyRouteAuthorizer()
	pattern := "/api/v1/knowledge/" + s.Path
	gate.Register("GET", pattern, middleware.APIKeyRoutePolicy{RequireFullAccess: true}.WithCapability(types.APIKeyCapabilityRetrieve))
	if s.Path == "batch" {
		r.GET(pattern, gate.Middleware(), middleware.RequireRole(types.TenantRoleViewer, cfg), h.GetKnowledgeBatch)
	} else {
		r.GET(pattern, gate.Middleware(), middleware.RequireRole(types.TenantRoleViewer, cfg), h.SearchKnowledge)
	}
	target := pattern + "?" + s.Query
	_, e = url.ParseRequestURI(target)
	must(e)
	if dsn != "" && os.Getenv("WEKNORA_COLLECTION_PARITY_ANALYZE") == "1" {
		table := "knowledges"
		if hidden == "knowledges" {
			table = "hidden_knowledges"
		}
		must(db.Exec("ANALYZE " + table).Error)
	}
	var originalBody any
	originalStatus := 0
	if dsn != "" {
		first := httptest.NewRecorder()
		r.ServeHTTP(first, httptest.NewRequest("GET", target, nil))
		originalStatus = first.Code
		must(json.Unmarshal(first.Body.Bytes(), &originalBody))
		// Replay the exact full SQL snapshot that the independent Java process receives.
		// The original partial INSERT/UPDATE setup can choose a different unordered PG plan.
		if hidden != "" {
			must(db.Exec("ALTER TABLE hidden_" + hidden + " RENAME TO " + hidden).Error)
		}
		must(db.Exec("TRUNCATE tenants,knowledge_bases,knowledges,organizations,organization_tenant_members,kb_shares,agent_shares,custom_agents,knowledge_processing_spans,audit_logs,tenant_disabled_shared_agents RESTART IDENTITY CASCADE").Error)
		encoded, e := json.Marshal(seed)
		must(e)
		dec := json.NewDecoder(strings.NewReader(string(encoded)))
		dec.UseNumber()
		var snapshot []struct {
			Table string         `json:"table"`
			Row   map[string]any `json:"row"`
		}
		must(dec.Decode(&snapshot))
		// Parent agents precede FK-dependent shares, as in the Java replay.
		for _, dependent := range []bool{false, true} {
			for _, item := range snapshot {
				if (item.Table == "agent_shares") != dependent {
					continue
				}
				cols := []string{}
				values := []any{}
				for col, value := range item.Row {
					cols = append(cols, col)
					switch v := value.(type) {
					case map[string]any:
						if b, ok := v["base64"].(string); ok {
							raw, e := base64.StdEncoding.DecodeString(b)
							must(e)
							value = raw
						}
					case json.Number:
						n, e := v.Int64()
						must(e)
						value = n
					}
					values = append(values, value)
				}
				must(db.Exec("INSERT INTO "+item.Table+"("+strings.Join(cols, ",")+") VALUES("+strings.TrimSuffix(strings.Repeat("?,", len(cols)), ",")+")", values...).Error)
			}
		}
		if os.Getenv("WEKNORA_COLLECTION_PARITY_ANALYZE") == "1" {
			must(db.Exec("ANALYZE knowledges").Error)
		}
		if hidden != "" {
			must(db.Exec("ALTER TABLE " + hidden + " RENAME TO hidden_" + hidden).Error)
		}
	}
	if os.Getenv("WEKNORA_COLLECTION_PARITY_DEBUG") != "" && dsn != "" && s.Path == "batch" && (s.Variant == "viewer" || s.Variant == "all") {
		db.Callback().Query().After("gorm:query").Register("collection_plan", func(tx *gorm.DB) {
			query := tx.Statement.SQL.String()
			if strings.Contains(query, "FROM \"knowledges\"") && strings.Contains(query, "tenant_id =") {
				rows, e := tx.Statement.ConnPool.QueryContext(context.Background(), "EXPLAIN (FORMAT JSON) "+query, tx.Statement.Vars...)
				if e != nil {
					fmt.Fprintln(os.Stderr, "GO_PLAN_ERROR", e)
					return
				}
				defer rows.Close()
				var plan string
				if rows.Next() {
					must(rows.Scan(&plan))
				}
				fmt.Fprintln(os.Stderr, "GO_PLAN", s.Name, query, tx.Statement.Vars, plan)
			}
		})
	}
	w := httptest.NewRecorder()
	r.ServeHTTP(w, httptest.NewRequest("GET", target, nil))
	var body any
	must(json.Unmarshal(w.Body.Bytes(), &body))
	var audits []map[string]any
	must(db.Table("audit_logs").Select("tenant_id,actor_user_id,actor_role,action,request_path,request_method,outcome,details").Order("id").Find(&audits).Error)
	for _, a := range audits {
		var d any
		switch v := a["details"].(type) {
		case []byte:
			must(json.Unmarshal(v, &d))
		case string:
			must(json.Unmarshal([]byte(v), &d))
		}
		a["details"] = d
	}
	return map[string]any{"original_go_body": originalBody, "original_go_status": originalStatus, "name": s.Name, "target": target, "variant": s.Variant, "hidden": hidden, "seed": seed, "status": w.Code, "body": body, "audits": audits, "caller_tenant": 1, "user": user, "effective_role": types.TenantRoleFromContext(context.WithValue(context.Background(), types.TenantRoleContextKey, role)), "machine": machine, "scope": scope}
}
