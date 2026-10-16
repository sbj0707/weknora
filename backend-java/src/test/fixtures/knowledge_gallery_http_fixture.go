//go:build ignore

// Export actual Go gallery handlers, DB projection, typed settings and access policies.
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

func must(e error) {
	if e != nil {
		panic(e)
	}
}
func write(path string, v any) {
	b, e := json.MarshalIndent(v, "", "  ")
	must(e)
	must(os.WriteFile(path, append(b, '\n'), 0600))
}
func schema(t reflect.Type) map[string]any {
	s := map[string]any{"kind": t.Kind().String()}
	switch t.Kind() {
	case reflect.Pointer:
		s["kind"] = "pointer"
		s["items"] = schema(t.Elem())
	case reflect.Map, reflect.Slice, reflect.Array:
		s["items"] = schema(t.Elem())
	case reflect.Struct:
		fields := map[string]any{}
		for i := 0; i < t.NumField(); i++ {
			f := t.Field(i)
			if !f.IsExported() {
				continue
			}
			tag := strings.Split(f.Tag.Get("json"), ",")
			name := tag[0]
			if name == "-" {
				continue
			}
			if name == "" {
				name = f.Name
			}
			x := schema(f.Type)
			x["omit_empty"] = strings.Contains(f.Tag.Get("json"), ",omitempty")
			fields[name] = x
		}
		s["fields"] = fields
	case reflect.Int, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64, reflect.Uint, reflect.Uint8, reflect.Uint16, reflect.Uint32, reflect.Uint64:
		s["bits"] = t.Bits()
	}
	return s
}

type scene struct{ Name, Endpoint, Query, Variant, Policy, Preferences string }

func main() {
	time.Local = time.UTC
	logger.SetOutput(os.Stderr)
	gin.SetMode(gin.TestMode)
	if len(os.Args) > 2 {
		write(os.Args[2], map[string]any{"sources": types.GalleryAttrSources("kb"), "policy_schema": schema(reflect.TypeOf(types.GalleryPolicyTier{})), "preferences_schema": schema(reflect.TypeOf(types.UserPreferences{})), "filters_schema": schema(reflect.TypeOf(map[string][]string{})), "rules_schema": schema(reflect.TypeOf(map[string]map[string]string{}))})
	}
	scenes := []scene{}
	add := func(endpoint, q, v, policy, prefs string) {
		scenes = append(scenes, scene{fmt.Sprintf("gallery-%03d-%s-%s", len(scenes), endpoint, v), endpoint, q, v, policy, prefs})
	}
	for _, endpoint := range []string{"gallery-config", "images"} {
		for _, v := range []string{"ok", "viewer", "unknown-role", "shared-viewer", "shared-revoked", "api-retrieve", "api-ingest", "api-full", "api-wrong-kb", "api-full-wrong-kb", "missing-kb", "deleted-kb", "settings-missing", "users-missing", "images-missing", "documents-missing", "no-user"} {
			add(endpoint, "", v, "", "")
		}
	}
	for _, q := range []string{"page=0&page_size=0", "page=%20-1%20&page_size=1001", "page_size=bad&page=-1", "page_size=%20%20", "page=%202%20&page_size=%20%2B2%20", "page=%E3%80%802%E3%80%80", "page=%20bad%20", "page=2&page_size=2", "page=999&page_size=2", "page=9223372036854775807&page_size=1000", "page=-1", "page_size=-1", "page_size=1001", "page=NaN", "page_size=NaN", "page=%2B2", "page=+2", "page=&page=2", "page_size=1&page_size=2", "keyword=alpha", "keyword=%25", "keyword=_", "keyword=back%5C", "keyword=%C3%89", "keyword=%E4%B8%AD%E6%96%87", "keyword=OCR&search_in=builtin:ocr_text", "keyword=alpha&search_in=system:contain.text", "keyword=alpha&search_in=missing,builtin:caption", "sort_by=builtin:caption&sort_order=asc", "sort_by=builtin:updated_at&sort_order=asc", "sort_by=caption&sort_order=asc", "sort_by=system:contain.text&sort_order=asc", "sort_order=ASC", "sort_order=", "is_enabled=true", "is_enabled=false", "is_enabled=TRUE", "is_enabled=1", "is_enabled="} {
		add("images", q, "ok", "", "")
	}
	filters := []string{`{"system:contain.text":["block"]}`, `{"system:contain.text":[" block ",null]}`, `{"builtin:is_enabled":["false"]}`, `{"builtin:caption":["alpha"]}`, `{"missing":["x"]}`, `{"system:contain.text":null}`, `{"system:contain.text":[1]}`, `{"system:contain.text":true}`, `null`, `[]`, `{`, `{"system:contain.text":["block"]}garbage`, `{"system:contain.text":["block"],"system:contain.text":["none"]}`}
	for _, f := range filters {
		add("images", "attr_filters="+url.QueryEscape(f), "ok", "", "")
	}
	for _, f := range []string{`{"system:contain.text":{"block":"off"}}`, `{"system:contain.text":{"none":"off","block":"on"},"builtin:is_enabled":{"true":"off"}}`, `{"system:contain.text":{"none":"on"}}`, `{"system:contain.text":{"block":null}}`, `{"system:contain.text":{"block":true}}`, `{"system:contain.text":[]}`, `null`, `[]`, `{"system:contain.text":{"block":"off"}}{}`} {
		add("images", "attr_rules="+url.QueryEscape(f), "ok", "", "")
	}
	for _, policy := range []string{`{"sources":{"system":false}}`, `{"sources":{"builtin":false,"system":false}}`, `{"sources":{"system":null}}`, `{"sources":{"system":false},"sources":{"builtin":false}}`, `{"sources":{"system":false},"sources":null}`, `{"overrides":{"system:contain.text":{"in_filter":false,"in_searchfield":true,"in_sortfield":true},"builtin:caption":{"in_searchfield":false}}}`, `{"OVERRIDES":{"system:contain.text":{"IN_SEARCHFIELD":true}}}`, `{"overrides":{"system:contain.text":{"in_searchfield":true}},"overrides":{"builtin:caption":{"in_filter":true}}}`, `{"overrides":{"system:contain.text":{"in_searchfield":true}},"overrides":{"system:contain.text":{"in_filter":false}}}`, `{"overrides":{"system:contain.text":null}}`, `{"overrides":{"system:contain.text":{"in_filter":null}}}`, `{"sources":{"system":"false"}}`, `{"sources":{"system":false},"mode":1}`, `{"overrides":{"system:contain.text":{"in_filter":0}}}`, `{"status":{"x":false}}`, `{"mode":"custom","status":{"x":"on"}}`, `null`, `[]`, `"x"`, `{`, `{}garbage`} {
		for _, endpoint := range []string{"gallery-config", "images"} {
			add(endpoint, "keyword=block&search_in=system:contain.text&sort_by=system:contain.text", "policy", policy, "")
		}
	}
	for _, prefs := range []string{`{"gallery":{"mode":"custom","status":{"builtin:caption":"off","unknown":"on","bad":"ON"}}}`, `{"gallery":{"mode":"invalid","status":{"x":"off"},"sources":{"builtin":false},"overrides":{"builtin:caption":{"in_filter":true}}}}`, `{"gallery":{"mode":"custom"},"gallery":{"status":{"x":"on"}}}`, `{"gallery":{"mode":"custom","status":{"x":"on"}},"gallery":{"status":null}}`, `{"gallery":{"mode":"custom"},"gallery":null}`, `{"GALLERY":{"MODE":"custom","STATUS":{"x":"on"}}}`, `{"gallery":{"mode":"custom","status":{"x":null}}}`, `{"gallery":{"mode":"custom"},"last_active_tenant_id":-1}`, `{"gallery":{"mode":"custom"},"oidc_only_login":"yes"}`, `{"gallery":{"mode":"custom","status":{"x":true}}}`, `{"gallery":[]}`, `null`, `[]`, `{`, `{}garbage`} {
		add("gallery-config", "", "prefs", "", prefs)
	}
	out := []any{}
	for _, s := range scenes {
		out = append(out, run(s))
	}
	write(os.Args[1], out)
	fmt.Println("exported", len(out), "actual Go gallery HTTP cases")
}
func run(s scene) any {
	dir, e := os.MkdirTemp("", "weknora-go-gallery-")
	must(e)
	defer os.RemoveAll(dir)
	dsn := os.Getenv("WEKNORA_GALLERY_PG_DSN")
	var dialect gorm.Dialector = sqlite.Open(filepath.Join(dir, "case.db"))
	if dsn != "" {
		u, e := url.Parse(dsn)
		must(e)
		if u.Hostname() != "127.0.0.1" || u.Path != "/weknora_gallery_parity" {
			panic("Owned loopback gallery database required")
		}
		dialect = postgres.Open(dsn)
		if s.Preferences != "" && !json.Valid([]byte(s.Preferences)) {
			return map[string]any{"name": s.Name, "pg_unsupported_invalid_json": true}
		}
	}
	db, e := gorm.Open(dialect, &gorm.Config{Logger: gl.Default.LogMode(gl.Silent)})
	must(e)
	sql, e := db.DB()
	must(e)
	defer sql.Close()
	sql.SetMaxOpenConns(1)
	if dsn == "" {
		for _, m := range []string{"000000_init", "000002_knowledge_folder_path", "000003_knowledge_base_auto_tag_config", "000004_memory", "000007_system_admin_and_settings", "000008_processing_spans_and_pending_subtasks", "000010_knowledge_multi_tags", "000011_principal_model", "000021_knowledge_profiles", "000031_model_catalog_config", "000032_chunk_images", "000033_chunk_source_locators"} {
			b, e := os.ReadFile("migrations/sqlite/" + m + ".up.sql")
			must(e)
			must(db.Exec(string(b)).Error)
		}
	} else {
		for _, t := range []string{"chunk_images", "knowledges", "users", "system_settings"} {
			var n int64
			must(db.Raw("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public' AND table_name=?", "hidden_"+t).Scan(&n).Error)
			if n > 0 {
				must(db.Exec("ALTER TABLE hidden_" + t + " RENAME TO " + t).Error)
			}
		}
		must(db.Exec("TRUNCATE tenants,knowledge_bases,knowledges,chunks,chunk_images,users,system_settings,organizations,organization_tenant_members,kb_shares,audit_logs RESTART IDENTITY CASCADE").Error)
	}

	seeds := []string{}
	seed := func(q string) { must(db.Exec(q).Error); seeds = append(seeds, q) }
	quote := func(s string) string { return "'" + strings.ReplaceAll(s, "'", "''") + "'" }
	seed("INSERT INTO tenants(id,name,business) VALUES(1,'Owner','test'),(2,'Caller','test')")
	seed("INSERT INTO knowledge_bases(id,tenant_id,creator_id,name,type,embedding_model_id,summary_model_id) VALUES('kb',1,'caller','KB','document','',''),('other-kb',1,'other','Other','document','','')")
	seed("INSERT INTO knowledges(id,tenant_id,knowledge_base_id,type,title,source,parse_status) VALUES('d1',1,'kb','file','Source One','manual','completed'),('d2',1,'kb','file','Deleted source','manual','completed')")
	seed("UPDATE knowledges SET deleted_at=CURRENT_TIMESTAMP WHERE id='d2'")
	pref := s.Preferences
	if pref == "" {
		pref = `{}`
	}
	seed("INSERT INTO users(id,username,email,password_hash,tenant_id,is_active,preferences) VALUES('caller','Caller','caller@example.test','unused',1,true," + quote(pref) + ")")
	if s.Policy != "" {
		b, e := json.Marshal(s.Policy)
		must(e)
		seed("INSERT INTO system_settings(key,value,value_type,category) VALUES('gallery.policy'," + quote(string(b)) + ",'string','gallery')")
	}
	for i, x := range []struct {
		id, doc, images string
		owner           int
		enabled         bool
		day             string
	}{
		{"c-a", "d1", `[{"url":"same","caption":"old hidden copy","attrs":{"attrs":{"contain.text":"none"}}}]`, 1, true, "01"},
		{"c-b", "d1", `[{"url":"same","caption":"alpha winner","ocr_text":"OCR 中文","attrs":{"attrs":{"contain.text":"block","contain.data_visual":"none"}}},{"url":"second","caption":"100% back\\ slash_ É","attrs":{"attrs":{"contain.text":"none"}}}]`, 1, true, "02"},
		{"c-c", "d2", `[{"original_url":"third","caption":"alpha disabled","ocr_text":"OCR","attrs":{"attrs":{"contain.text":true}}}]`, 1, false, "03"},
		{"c-d", "missing", `[{"caption":"without URL"},{"url":"fourth","attrs":{"attrs":{"contain.text":null}}},null,12,"skip"]`, 1, true, "04"},
		{"c-e", "d1", `[{"url":"same","caption":"tie loser"}]`, 1, true, "02"},
		{"foreign", "d1", `[{"url":"foreign","caption":"alpha"}]`, 2, true, "05"},
	} {
		images := x.images
		seed(fmt.Sprintf("INSERT INTO chunks(id,tenant_id,knowledge_id,knowledge_base_id,content,chunk_index,start_at,end_at,is_enabled,chunk_type,status,image_info,created_at,updated_at) VALUES(%s,%d,%s,'kb','source',%d,0,1,%t,'image_caption',%d,%s,'2026-01-%s 00:00:00+00:00','2026-01-%s 00:00:00+00:00')", quote(x.id), x.owner, quote(x.doc), i, x.enabled, i, quote(images), x.day, x.day))
	}
	tenant := uint64(1)
	user, role := "caller", "owner"
	if s.Variant == "viewer" {
		role = "viewer"
	}
	if s.Variant == "unknown-role" {
		role = "unknown"
	}
	if s.Variant == "no-user" {
		user = ""
	}
	if strings.HasPrefix(s.Variant, "shared-") {
		tenant = 2
		seed("INSERT INTO organizations(id,name,owner_tenant_id,owner_id) VALUES('org','Org',1,'caller')")
		seed("INSERT INTO organization_tenant_members(id,organization_id,tenant_id,role) VALUES('src','org',1,'admin'),('dst','org',2,'viewer')")
		seed("INSERT INTO kb_shares(id,knowledge_base_id,organization_id,source_tenant_id,permission,shared_by_user_id) VALUES('share','kb','org',1,'viewer','caller')")
		if s.Variant == "shared-revoked" {
			seed("UPDATE kb_shares SET deleted_at=CURRENT_TIMESTAMP")
		}
	}
	switch s.Variant {
	case "missing-kb":
		seed("DELETE FROM knowledge_bases WHERE id='kb'")
	case "deleted-kb":
		seed("UPDATE knowledge_bases SET deleted_at=CURRENT_TIMESTAMP WHERE id='kb'")
	case "settings-missing":
		seed("ALTER TABLE system_settings RENAME TO hidden_system_settings")
	case "users-missing":
		seed("ALTER TABLE users RENAME TO hidden_users")
	case "images-missing":
		seed("ALTER TABLE chunk_images RENAME TO hidden_chunk_images")
	case "documents-missing":
		seed("ALTER TABLE knowledges RENAME TO hidden_knowledges")
	}
	machine := strings.HasPrefix(s.Variant, "api-")
	scope := types.TenantAPIKeyScope{Capabilities: types.StringArray{"retrieve"}, KnowledgeBaseIDs: []string{"kb"}}
	if s.Variant == "api-ingest" {
		scope.Capabilities = types.StringArray{"ingest"}
	}
	if strings.Contains(s.Variant, "full") {
		scope.FullAccess = true
	}
	if strings.HasSuffix(s.Variant, "wrong-kb") {
		scope.KnowledgeBaseIDs = []string{"other-kb"}
	}
	audit := svc.NewAuditLogService(repo.NewAuditLogRepository(db))
	shares := svc.NewKBShareService(repo.NewKBShareRepository(db), repo.NewOrganizationRepository(db), nil, nil, nil, audit)
	agents := svc.NewAgentShareService(repo.NewAgentShareRepository(db), repo.NewTenantDisabledSharedAgentRepository(db), repo.NewOrganizationRepository(db), repo.NewCustomAgentRepository(db), nil, nil, nil)
	br, kr, cr := repo.NewKnowledgeBaseRepository(db), repo.NewKnowledgeRepository(db), repo.NewChunkRepository(db)
	kbs := svc.NewKnowledgeBaseService(br, kr, cr, nil, shares, nil, nil, nil, repo.NewTenantRepository(db), nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil)
	chunks := svc.NewChunkService(cr, kr, br, nil, nil, nil, nil, nil)
	enabled := true
	cfg := &config.Config{Tenant: &config.TenantConfig{EnableRBAC: &enabled}}
	settings := svc.NewSystemSettingService(repo.NewSystemSettingRepository(db), audit, nil, cfg)
	users := svc.NewUserService(cfg, repo.NewUserRepository(db), nil, nil, nil, settings)
	h := handler.NewKnowledgeHandler(cfg, nil, kbs, shares, agents, chunks, settings, users, nil, nil, nil)
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
	path := "/api/v1/knowledge-bases/:id/" + s.Endpoint
	gate.Register("GET", path, middleware.APIKeyRoutePolicy{RequireFullAccess: true}.WithCapability(types.APIKeyCapabilityRetrieve))
	fn := h.GetGalleryConfig
	if s.Endpoint == "images" {
		fn = h.ListImages
	}
	router.GET(path, gate.Middleware(), middleware.RequireRole(types.TenantRoleViewer, cfg), middleware.RequireKBAccess(middleware.KBIDFromParam("id"), types.OrgRoleViewer, kbs, shares, agents, cfg), fn)
	target := "/api/v1/knowledge-bases/kb/" + s.Endpoint
	if s.Query != "" {
		target += "?" + s.Query
	}
	w := httptest.NewRecorder()
	request := httptest.NewRequest("GET", target, nil)
	queryBefore := request.URL.Query()
	router.ServeHTTP(w, request)
	var body any
	dec := json.NewDecoder(bytes.NewReader(w.Body.Bytes()))
	dec.UseNumber()
	must(dec.Decode(&body))
	if dsn != "" {
		for _, t := range []string{"chunk_images", "knowledges", "users", "system_settings"} {
			var n int64
			must(db.Raw("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public' AND table_name=?", "hidden_"+t).Scan(&n).Error)
			if n > 0 {
				must(db.Exec("ALTER TABLE hidden_" + t + " RENAME TO " + t).Error)
			}
		}
	}
	return map[string]any{"name": s.Name, "variant": s.Variant, "target": target, "parsed_query": queryBefore, "seed_statements": seeds, "body": body, "status": w.Code, "caller_tenant": tenant, "user": user, "effective_role": types.TenantRoleFromContext(context.WithValue(context.Background(), types.TenantRoleContextKey, role)), "machine": machine, "scope": scope}
}
