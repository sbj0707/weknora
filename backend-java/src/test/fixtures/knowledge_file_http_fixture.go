//go:build ignore

// Exports actual production Gin authorization, knowledge/file services and HTTP transport.
package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	repo "github.com/Tencent/WeKnora/internal/application/repository"
	svc "github.com/Tencent/WeKnora/internal/application/service"
	files "github.com/Tencent/WeKnora/internal/application/service/file"
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

func must(e error) {
	if e != nil {
		panic(e)
	}
}

type scene struct {
	Name, Operation, Variant, Filename, Metadata, Title string
	Headers                                             map[string]string
}

func main() {
	time.Local = time.UTC
	logger.SetOutput(os.Stderr)
	gin.SetMode(gin.TestMode)
	scenes := []scene{}
	for _, op := range []string{"download", "preview"} {
		for _, v := range []string{"ok", "own-viewer", "own-contributor", "unknown-role", "shared-editor", "shared-viewer", "shared-caller-viewer", "shared-revoked", "shared-source-missing", "shared-org-deleted", "api-retrieve", "api-ingest", "api-full", "api-wrong-kb", "api-full-wrong-kb", "missing-document", "deleted-document", "deleting-document", "moving-document", "foreign-document", "missing-kb", "deleted-kb", "manual", "manual-null", "manual-empty", "file-empty", "file-missing", "raw-control-same", "raw-newline", "api-raw-other-kb", "api-full-raw-other-kb", "raw-foreign"} {
			scenes = append(scenes, scene{Name: op + "-" + v, Operation: op, Variant: v})
		}
		for _, r := range []string{"bytes=0-4", "bytes=5-", "bytes=-5", "bytes=-0", "bytes=0-99999", "bytes=999-", "bytes=abc-def", "items=0-1", "BYTES=0-1", "bytes=", "bytes=, ,", "bytes=+0-+3", "bytes=4-2", "bytes=0-1,8-10", "bytes=999-,0-1", "bytes=0-10,0-10,0-10", "bytes=-99999", "bytes=0-1,-2", "bytes=0-1,what", "bytes=9223372036854775808-", "bytes=0-9223372036854775808", "bytes= 0 - 3 "} {
			scenes = append(scenes, scene{Name: fmt.Sprintf("%s-range-%d", op, len(scenes)), Operation: op, Variant: "ok", Headers: map[string]string{"Range": r}})
		}
		for _, h := range []map[string]string{{"If-Match": "*"}, {"If-Match": "\"tag\""}, {"If-Match": "invalid"}, {"If-Match": "\"tag\", *"}, {"If-None-Match": "*"}, {"If-None-Match": "\"tag\""}, {"If-None-Match": "bad,*"}, {"If-Modified-Since": "Fri, 01 Jan 2038 00:00:00 GMT"}, {"If-Unmodified-Since": "Fri, 01 Jan 1999 00:00:00 GMT"}, {"Range": "bytes=0-2", "If-Range": "\"tag\""}, {"Range": "bytes=0-2", "If-Range": "Fri, 01 Jan 2038 00:00:00 GMT"}, {"If-Match": "\"tag\"", "If-None-Match": "*"}} {
			scenes = append(scenes, scene{Name: fmt.Sprintf("%s-conditional-%d", op, len(scenes)), Operation: op, Variant: "ok", Headers: h})
		}
		for _, v := range []string{"manual", "file-empty"} {
			for _, r := range []string{"bytes=0-2", "bytes=999-", "bad"} {
				scenes = append(scenes, scene{Name: fmt.Sprintf("%s-%s-range-%d", op, v, len(scenes)), Operation: op, Variant: v, Headers: map[string]string{"Range": r}})
			}
		}
	}
	for _, name := range []string{"source.pdf", "source.txt", "source.md", "source.csv", "source.json", "source.doc", "source.docx", "source.ppt", "source.pptx", "source.xls", "source.xlsx", "source.png", "source.JPG", "source.gif", "source.webp", "source.mp4", "source.mp3", "source.wav", "source.zip", "source.tar", "source.gz", "source.svg", "source.SVGZ", "source.html", "source.htm", "source.xhtml", "source.xml", "source.js", "source.mjs", "source.css", "source.unknown", "source", "", "中文 说明.pdf", "a\"quote\\file.txt", "../nested/source.txt", "a;b.txt", "a%name.txt"} {
		scenes = append(scenes, scene{Name: fmt.Sprintf("preview-filename-%d", len(scenes)), Operation: "preview", Variant: "ok", Filename: name, Title: "filename"})
	}
	for _, raw := range []string{"null", "{}", "[]", "1", "true", `"text"`, `{"content":"# Markdown\\n中文"}`, `{"CONTENT":"Uppercase"}`, `{"content":"first","content":null}`, `{"content":1}`, `{"format":1}`, `{"status":false}`, `{"updated_at":[]}`, `{"version":1.5}`, `{"version":9223372036854775808}`, `{"version":-1}`, `{"content":"ok"} garbage`, `{`} {
		scenes = append(scenes, scene{Name: fmt.Sprintf("manual-metadata-%d", len(scenes)), Operation: "download", Variant: "manual-custom", Metadata: raw})
	}
	for _, title := range []string{"", " \t\n ", "Hello", "Existing.MD", "a/b\\c\"d\r\n\t", "中文 手记", "../report", "x.html", "a;name"} {
		scenes = append(scenes, scene{Name: fmt.Sprintf("manual-title-%d", len(scenes)), Operation: "download", Variant: "manual-custom", Metadata: `{"content":"body"}`, Title: title})
	}
	out := []any{}
	for _, s := range scenes {
		out = append(out, run(s))
	}
	b, e := json.Marshal(out)
	must(e)
	must(os.WriteFile(os.Args[1], append(b, '\n'), 0600))
	fmt.Println("exported", len(out), "actual Go file HTTP cases")
}
func run(s scene) any {
	dir, e := os.MkdirTemp("", "weknora-go-file-")
	must(e)
	defer os.RemoveAll(dir)
	base := filepath.Join(dir, "files")
	must(os.MkdirAll(filepath.Join(base, "1", "d1"), 0755))
	physical := filepath.Join(base, "1", "d1", "source")
	content := []byte("0123456789abcdefghij\n中文\x00tail")
	if s.Variant == "file-empty" {
		content = []byte{}
	}
	must(os.WriteFile(physical, content, 0600))
	var dial gorm.Dialector = sqlite.Open(filepath.Join(dir, "case.db"))
	dsn := os.Getenv("WEKNORA_FILE_PARITY_PG_DSN")
	if dsn != "" {
		u, e := url.Parse(dsn)
		must(e)
		if u.Hostname() != "127.0.0.1" || u.Path != "/weknora_file_parity" {
			panic("owned loopback database required")
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
		must(db.Exec("TRUNCATE tenants,knowledge_bases,knowledges,organizations,organization_tenant_members,kb_shares,agent_shares,custom_agents,audit_logs RESTART IDENTITY CASCADE").Error)
	}
	must(db.Exec("INSERT INTO tenants(id,name,business) VALUES(1,'Owner','test'),(2,'Caller','test')").Error)
	must(db.Exec("INSERT INTO knowledge_bases(id,tenant_id,creator_id,name,type,embedding_model_id,summary_model_id) VALUES('kb',1,'other','KB','document','','')").Error)
	typ, metadata, title, filename := "file", "{}", "Document", "source.pdf"
	if strings.HasPrefix(s.Variant, "manual") {
		typ, metadata = "manual", `{"content":"# Manual\n中文"}`
	}
	if s.Variant == "manual-null" {
		metadata = "null"
	}
	if s.Variant == "manual-empty" {
		metadata = ""
	}
	if s.Variant == "manual-custom" {
		metadata = s.Metadata
		title = s.Title
	}
	if s.Title == "filename" {
		filename = s.Filename
	}
	// Retain raw JSON metadata, including invalid SQLite values rejected by PostgreSQL jsonb.
	var raw any = []byte(metadata)
	if metadata == "" {
		raw = nil
	}
	if dsn != "" {
		raw = metadata
		if metadata == "" {
			raw = nil
		}
		if !json.Valid([]byte(metadata)) && metadata != "" {
			return map[string]any{"name": s.Name, "pg_unsupported_invalid_json": true}
		}
	}
	must(db.Exec("INSERT INTO knowledges(id,tenant_id,knowledge_base_id,type,title,file_name,file_type,file_path,source,parse_status,metadata) VALUES('d1',1,'kb',?,?,?,'pdf',?,'manual','completed',?)", typ, title, filename, "local://1/d1/source", raw).Error)
	switch s.Variant {
	case "missing-document":
		must(db.Exec("DELETE FROM knowledges").Error)
	case "deleted-document":
		must(db.Exec("UPDATE knowledges SET deleted_at=CURRENT_TIMESTAMP").Error)
	case "deleting-document":
		must(db.Exec("UPDATE knowledges SET parse_status='deleting'").Error)
	case "moving-document":
		must(db.Exec("UPDATE knowledges SET metadata=?", []byte(`{"_knowledge_transfer":{"operation":"move","phase":"moving"}}`)).Error)
	case "foreign-document":
		must(db.Exec("UPDATE knowledges SET tenant_id=2").Error)
	case "missing-kb":
		must(db.Exec("DELETE FROM knowledge_bases").Error)
	case "deleted-kb":
		must(db.Exec("UPDATE knowledge_bases SET deleted_at=CURRENT_TIMESTAMP").Error)
	case "file-missing":
		must(os.Remove(physical))
	}
	audit := svc.NewAuditLogService(repo.NewAuditLogRepository(db))
	shares := svc.NewKBShareService(repo.NewKBShareRepository(db), repo.NewOrganizationRepository(db), nil, nil, nil, audit)
	agents := svc.NewAgentShareService(repo.NewAgentShareRepository(db), repo.NewTenantDisabledSharedAgentRepository(db), repo.NewOrganizationRepository(db), repo.NewCustomAgentRepository(db), nil, nil, nil)
	br, kr, cr := repo.NewKnowledgeBaseRepository(db), repo.NewKnowledgeRepository(db), repo.NewChunkRepository(db)
	kbs := svc.NewKnowledgeBaseService(br, kr, cr, nil, shares, nil, nil, nil, repo.NewTenantRepository(db), nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil)
	kg, e := svc.NewKnowledgeService(&config.Config{}, kr, nil, kbs, repo.NewTenantRepository(db), nil, nil, cr, nil, nil, files.NewLocalFileService(base, ""), nil, nil, nil, nil, nil, nil, nil, nil, nil, shares, nil, nil, nil, nil, nil, audit)
	must(e)
	enabled := true
	cfg := &config.Config{Tenant: &config.TenantConfig{EnableRBAC: &enabled}}
	h := handler.NewKnowledgeHandler(cfg, kg, kbs, shares, agents, nil, nil, nil, nil, nil, nil)
	role, user, tenant := types.TenantRoleAdmin, "caller", uint64(1)
	if s.Variant == "own-viewer" || s.Variant == "shared-caller-viewer" {
		role = types.TenantRoleViewer
	}
	if s.Variant == "own-contributor" {
		role = types.TenantRoleContributor
	}
	if s.Variant == "unknown-role" {
		role = "unknown"
	}
	if strings.HasPrefix(s.Variant, "shared-") {
		tenant = 2
		if s.Variant != "shared-caller-viewer" {
			role = types.TenantRoleContributor
		}
		permission := "editor"
		if s.Variant == "shared-viewer" {
			permission = "viewer"
		}
		must(db.Exec("INSERT INTO organizations(id,name,owner_tenant_id,owner_id) VALUES('org','Org',1,'caller')").Error)
		must(db.Exec("INSERT INTO organization_tenant_members(id,organization_id,tenant_id,role) VALUES('source','org',1,'admin'),('member','org',2,'editor')").Error)
		must(db.Exec("INSERT INTO kb_shares(id,knowledge_base_id,organization_id,source_tenant_id,permission,shared_by_user_id) VALUES('share','kb','org',1,?,'caller')", permission).Error)
		if s.Variant == "shared-revoked" {
			must(db.Exec("UPDATE kb_shares SET deleted_at=CURRENT_TIMESTAMP").Error)
		}
		if s.Variant == "shared-source-missing" {
			must(db.Exec("DELETE FROM organization_tenant_members WHERE id='source'").Error)
		}
		if s.Variant == "shared-org-deleted" {
			must(db.Exec("UPDATE organizations SET deleted_at=CURRENT_TIMESTAMP").Error)
		}
	}
	machine := strings.HasPrefix(s.Variant, "api-")
	scope := types.TenantAPIKeyScope{Capabilities: types.StringArray{"retrieve"}, KnowledgeBaseIDs: []string{"kb"}}
	if s.Variant == "api-ingest" {
		scope.Capabilities = types.StringArray{"ingest"}
	}
	if strings.HasPrefix(s.Variant, "api-full") {
		scope.FullAccess = true
	}
	if strings.HasSuffix(s.Variant, "wrong-kb") {
		scope.KnowledgeBaseIDs = []string{"other"}
	}

	rawID := "d1"
	if strings.Contains(s.Variant, "raw-") {
		rawID = "d1" + string(rune(1))
		if s.Variant == "raw-newline" {
			rawID = "d1\n"
		}
		must(db.Exec("INSERT INTO knowledges(id,tenant_id,knowledge_base_id,type,title,file_name,file_type,file_path,source,parse_status,metadata) SELECT ?,1,'kb',type,title,file_name,file_type,file_path,source,parse_status,metadata FROM knowledges WHERE id='d1'", rawID).Error)
		if strings.Contains(s.Variant, "other-kb") || s.Variant == "raw-foreign" {
			owner := 1
			if s.Variant == "raw-foreign" {
				owner = 2
			}
			must(db.Exec("INSERT INTO knowledge_bases(id,tenant_id,name,type,embedding_model_id,summary_model_id) VALUES('other-kb',?,'Other','document','','')", owner).Error)
			must(db.Exec("UPDATE knowledges SET knowledge_base_id='other-kb',tenant_id=? WHERE id='d1'", owner).Error)
		}
	}
	seedSQL := []any{}
	for _, table := range []string{"tenants", "knowledge_bases", "knowledges", "organizations", "organization_tenant_members", "kb_shares"} {
		var rows []map[string]any
		must(db.Table(table).Find(&rows).Error)
		for _, row := range rows {
			for k, v := range row {
				if b, ok := v.([]byte); ok {
					row[k] = map[string]any{"base64": base64.StdEncoding.EncodeToString(b)}
				}
			}
			seedSQL = append(seedSQL, map[string]any{"table": table, "row": row})
		}
	}
	r := gin.New()
	r.Use(middleware.ErrorHandler(), middleware.AuditServiceProvider(audit))
	r.Use(func(c *gin.Context) {
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
	target := "/api/v1/knowledge/" + url.PathEscape(rawID) + "/" + s.Operation
	pattern := "/api/v1/knowledge/:id/" + s.Operation
	permission := types.OrgRoleViewer
	floor := types.TenantRoleViewer
	handle := h.PreviewKnowledgeFile
	if s.Operation == "download" {
		permission = types.OrgRoleEditor
		floor = types.TenantRoleContributor
		handle = h.DownloadKnowledgeFile
	}
	gate := middleware.NewAPIKeyRouteAuthorizer()
	gate.Register("GET", pattern, middleware.APIKeyRoutePolicy{RequireFullAccess: true}.WithCapability(types.APIKeyCapabilityRetrieve))
	r.GET(pattern, gate.Middleware(), middleware.RequireRole(floor, cfg), middleware.RequireKBAccess(middleware.KBIDFromKnowledgeIDParam("id", kg), permission, kbs, shares, agents, cfg), handle)
	req := httptest.NewRequest("GET", target, nil)
	for k, v := range s.Headers {
		req.Header.Set(k, v)
	}
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	headers := map[string]string{}
	for k, v := range w.Header() {
		headers[k] = strings.Join(v, ",")
	}
	body := strings.ReplaceAll(w.Body.String(), base, "_BASE_")
	if strings.HasPrefix(headers["Content-Type"], "multipart/byteranges; boundary=") {
		boundary := strings.TrimPrefix(headers["Content-Type"], "multipart/byteranges; boundary=")
		if len(boundary) != 60 {
			panic("unexpected boundary")
		}
		headers["Content-Type"] = "multipart/byteranges; boundary=" + strings.Repeat("b", 60)
		body = strings.ReplaceAll(body, boundary, strings.Repeat("b", 60))
	}
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
	return map[string]any{"name": s.Name, "operation": s.Operation, "variant": s.Variant, "metadata": metadata, "filename": filename, "title": title, "target": target, "request_headers": s.Headers, "status": w.Code, "headers": headers, "body_base64": base64.StdEncoding.EncodeToString([]byte(body)), "seed": seedSQL, "audits": audits, "caller_tenant": tenant, "role": role, "effective_role": types.TenantRoleFromContext(context.WithValue(context.Background(), types.TenantRoleContextKey, role)), "user": user, "machine": machine, "scope": scope, "file_base64": base64.StdEncoding.EncodeToString(content)}
}
