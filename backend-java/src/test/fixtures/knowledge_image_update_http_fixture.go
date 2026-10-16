//go:build ignore

// Production Gin handler/middleware, real SQL repositories and image update service.
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
	"github.com/Tencent/WeKnora/internal/models/embedding"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"github.com/gin-gonic/gin"
	"gorm.io/driver/postgres"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
	gl "gorm.io/gorm/logger"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

func must(e error) {
	if e != nil {
		panic(e)
	}
}

type model struct {
	interfaces.ModelService
	fail  bool
	calls *[]any
}

func (m model) GetEmbeddingModel(context.Context, string) (embedding.Embedder, error) {
	*m.calls = append(*m.calls, map[string]any{"op": "model"})
	if m.fail {
		return nil, errors.New("controlled model failure")
	}
	return embed{}, nil
}

type embed struct{}

func (embed) Embed(context.Context, string) ([]float32, error)          { return []float32{1, 0, 0}, nil }
func (embed) BatchEmbed(context.Context, []string) ([][]float32, error) { return nil, nil }
func (embed) BatchEmbedWithPool(context.Context, embedding.Embedder, []string) ([][]float32, error) {
	return nil, nil
}
func (embed) GetDimensions() int   { return 3 }
func (embed) GetModelName() string { return "image-test" }
func (embed) GetModelID() string   { return "image-test" }

type engine struct {
	interfaces.RetrieveEngineService
	variant string
	calls   *[]any
}

func (e engine) EngineType() types.RetrieverEngineType { return types.PostgresRetrieverEngineType }
func (e engine) Support() []types.RetrieverType {
	return []types.RetrieverType{types.VectorRetrieverType, types.KeywordsRetrieverType}
}
func (e engine) DeleteByChunkIDList(_ context.Context, ids []string, _ int, _ string) error {
	*e.calls = append(*e.calls, map[string]any{"op": "delete", "ids": ids})
	if e.variant == "delete-index-failure" {
		return errors.New("controlled index delete failure")
	}
	return nil
}
func (e engine) BatchIndex(_ context.Context, _ embedding.Embedder, rows []*types.IndexInfo, _ []types.RetrieverType) error {
	entries := []any{}
	for _, r := range rows {
		entries = append(entries, map[string]any{"source_id": r.SourceID, "chunk_id": r.ChunkID, "knowledge_id": r.KnowledgeID, "knowledge_base_id": r.KnowledgeBaseID, "content": r.Content, "enabled": r.IsEnabled, "tag_id": r.TagID})
	}
	*e.calls = append(*e.calls, map[string]any{"op": "save", "entries": entries})
	if e.variant == "save-index-failure" {
		return errors.New("controlled index save failure")
	}
	return nil
}

type registry struct {
	interfaces.RetrieveEngineRegistry
	e interfaces.RetrieveEngineService
}

func (r registry) GetRetrieveEngineService(types.RetrieverEngineType) (interfaces.RetrieveEngineService, error) {
	return r.e, nil
}

type scene struct{ name, variant, body string }

func main() {
	logger.SetOutput(os.Stderr)
	gin.SetMode(gin.TestMode)
	time.Local = time.UTC
	out := []any{}
	for _, v := range []string{"ok", "creator-viewer", "noncreator-viewer", "noncreator-contributor", "noncreator-admin", "shared-editor", "shared-viewer", "shared-revoked", "api-ingest", "api-retrieve", "api-full", "api-wrong-kb", "api-full-wrong-kb", "unknown-role", "missing-document", "deleted-document", "missing-kb", "deleted-kb", "moving", "foreign-document", "missing-chunk", "foreign-chunk", "cross-document-parent", "cross-kb-parent", "cross-document-child", "cross-kb-child", "no-children", "only-caption", "only-ocr", "same-content", "other-url", "malformed-child", "null-child", "empty-child", "disabled-parent", "parent-text", "questions", "bad-metadata", "add-failure", "parent-write-failure", "child-write-failure", "hash-write-failure", "model-failure", "delete-index-failure", "save-index-failure", "missing-chunk-zero", "missing-chunk-two", "duplicate-questions", "bad-question-revision"} {
		out = append(out, run(scene{"image-" + v, v, ""}))
	}
	for i, body := range []string{"", `null`, `{}`, `[]`, `true`, `1`, `"x"`, `{"image_info":1}`, `{"image_info":false}`, `{"image_info":null}`, `{"image_info":"[]"}`, `{"image_info":"null"}`, `{"image_info":"[{},{}]"}`, `{"image_info":"[null]"}`, `{"image_info":"bad"}`, `{"image_info":"{}"}`, `{"image_info":"[{\"caption\":3}]"}`, `{"IMAGE_INFO":"[{}]"}`, `{"image_info":"[{}]"}garbage`, `{"image_info":"[{}]","image_info":null}`, `{"image_info":3,"image_info":"[{}]"}`, `{"image_info":"[{\"attrs\":{\"attrs\":{\"a\":1e309}}}]"}`, `{"image_info":"[{\"start_pos\":1.0}]"}`, `{"image_info":"[{\"attrs\":{\"source\":{\"custom\":3}}}]"}`, `{"image_info":"[{\"original_url\":\"img\",\"caption\":\"\",\"ocr_text\":\"\"}]"}`} {
		out = append(out, run(scene{fmt.Sprintf("image-body-%02d", i), "body", body}))
	}
	for i, inner := range []string{`[{}] {}`, `[{},3]`, `[null,null]`, `[{"original_url":null,"caption":"😀 中文"}]`, `[{"caption":"old","caption":null}]`, `[{"caption":3,"caption":"last"}]`, `[{"start_pos":9223372036854775807,"end_pos":-9223372036854775808}]`, `[{"start_pos":9223372036854775808}]`, `[{"url":false}]`, `[{"attrs":false}]`, `[{"attrs":{"attrs":[1]}}]`, `[{"attrs":{"attrs":{"nested":{"array":[true,null,"text",1.2]}}}}]`, `[{"attrs":{"source":{"custom":null}}}]`, `[{"caption":"\ud800"}]`, `[{"original_url":"img","caption":"line\ncaption","ocr_text":"tabs\tOCR"}]`, `[{"original_url":"img","caption":" ","ocr_text":" "}]`, `[{"original_url":"img","caption":"New caption 中文","ocr_text":"Old OCR"}]`, `[{"original_url":"img","caption":"Old caption","ocr_text":"New OCR 中文"}]`} {
		body, e := json.Marshal(map[string]string{"image_info": inner})
		must(e)
		out = append(out, run(scene{fmt.Sprintf("image-inner-%02d", i), "body", string(body)}))
	}
	b, e := json.Marshal(out)
	must(e)
	must(os.WriteFile(os.Args[1], append(b, '\n'), 0600))
	fmt.Println("exported", len(out), "actual Go image update scenes")
}
func run(s scene) any {
	dir, e := os.MkdirTemp("", "weknora-go-image-update-")
	must(e)
	defer os.RemoveAll(dir)
	var dialect gorm.Dialector = sqlite.Open(filepath.Join(dir, "case.db"))
	pgDSN := os.Getenv("WEKNORA_IMAGE_PG_DSN")
	if pgDSN != "" {
		u, e := url.Parse(pgDSN)
		must(e)
		if u.Hostname() != "127.0.0.1" || u.Path != "/weknora_image_parity" {
			panic("Owned loopback image parity database required")
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
		for _, m := range []string{"000000_init", "000002_knowledge_folder_path", "000003_knowledge_base_auto_tag_config", "000004_memory", "000007_system_admin_and_settings", "000008_processing_spans_and_pending_subtasks", "000010_knowledge_multi_tags", "000011_principal_model", "000021_knowledge_profiles", "000031_model_catalog_config", "000032_chunk_images", "000033_chunk_source_locators"} {
			b, e := os.ReadFile("migrations/sqlite/" + m + ".up.sql")
			must(e)
			must(db.Exec(string(b)).Error)
		}
	} else {
		for _, trigger := range []struct{ name, table string }{{"block_add", "chunks"}, {"block_parent", "chunks"}, {"block_child", "chunks"}, {"block_hash", "knowledges"}} {
			must(db.Exec("DROP TRIGGER IF EXISTS " + trigger.name + " ON " + trigger.table).Error)
		}
		must(db.Exec("TRUNCATE tenants,knowledge_bases,knowledges,chunks,chunk_images,organizations,organization_tenant_members,kb_shares,audit_logs RESTART IDENTITY CASCADE").Error)
	}
	seed := []string{}
	exec := func(statement string) {
		if pgDSN != "" && strings.HasPrefix(statement, "CREATE TRIGGER ") {
			pattern := regexp.MustCompile(`CREATE TRIGGER (\w+) BEFORE (INSERT|UPDATE) ON (\w+) WHEN (.+) BEGIN SELECT RAISE\(ABORT,'([^']+)'\); END`)
			match := pattern.FindStringSubmatch(statement)
			if match == nil {
				panic(statement)
			}
			function := "image_" + match[1] + "_fn"
			definition := "CREATE OR REPLACE FUNCTION " + function + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION '" + match[5] + "'; END $$"
			must(db.Exec(definition).Error)
			seed = append(seed, definition)
			statement = "CREATE TRIGGER " + match[1] + " BEFORE " + match[2] + " ON " + match[3] + " FOR EACH ROW WHEN (" + match[4] + ") EXECUTE FUNCTION " + function + "()"
		}
		must(db.Exec(statement).Error)
		seed = append(seed, statement)
	}
	stamp := "2026-01-01T00:00:00Z"
	exec("INSERT INTO tenants(id,name,business) VALUES(1,'Owner','test'),(2,'Caller','test')")
	exec("INSERT INTO knowledge_bases(id,tenant_id,creator_id,name,type,embedding_model_id,summary_model_id) VALUES('kb',1,'caller','KB','document','embedding',''),('other-kb',1,'other','Other','document','','')")
	exec("INSERT INTO knowledges(id,tenant_id,knowledge_base_id,type,title,source,parse_status,file_hash,created_at,updated_at) VALUES('d1',1,'kb','file',' Title 中文 ','manual','completed','old-hash','" + stamp + "','" + stamp + "'),('other',1,'other-kb','file','Other','manual','completed','other-hash','" + stamp + "','" + stamp + "')")
	old := `[{"url":"stored","original_url":"img","caption":"Old caption","ocr_text":"Old OCR"}]`
	for i, row := range []struct{ id, kind, parent, content string }{{"parent", "text", "", "Parent body"}, {"caption", "image_caption", "parent", "Old caption"}, {"ocr", "image_ocr", "parent", "Old OCR"}} {
		exec(fmt.Sprintf("INSERT INTO chunks(id,seq_id,tenant_id,knowledge_id,knowledge_base_id,content,chunk_type,parent_chunk_id,image_info,chunk_index,start_at,end_at,is_enabled,status,created_at,updated_at) VALUES('%s',%d,1,'d1','kb','%s','%s','%s','%s',%d,0,12,true,0,'%s','%s')", row.id, i+1, row.content, row.kind, row.parent, old, i, stamp, stamp))
	}
	switch s.variant {
	case "missing-document":
		exec("DELETE FROM knowledges WHERE id='d1'")
	case "deleted-document":
		exec("UPDATE knowledges SET deleted_at=CURRENT_TIMESTAMP WHERE id='d1'")
	case "missing-kb":
		exec("DELETE FROM knowledge_bases WHERE id='kb'")
	case "deleted-kb":
		exec("UPDATE knowledge_bases SET deleted_at=CURRENT_TIMESTAMP WHERE id='kb'")
	case "moving":
		exec(`UPDATE knowledges SET metadata='{"_knowledge_transfer":{"operation":"move","phase":"moving"}}' WHERE id='d1'`)
	case "foreign-document":
		exec("UPDATE knowledges SET tenant_id=2 WHERE id='d1'")
	case "missing-chunk", "missing-chunk-zero", "missing-chunk-two":
		exec("DELETE FROM chunks WHERE id='parent'")
	case "foreign-chunk":
		exec("UPDATE chunks SET tenant_id=2 WHERE id='parent'")
	case "cross-document-parent":
		exec("UPDATE chunks SET knowledge_id='other' WHERE id='parent'")
	case "cross-kb-parent":
		exec("UPDATE chunks SET knowledge_base_id='other-kb' WHERE id='parent'")
	case "cross-document-child":
		exec("UPDATE chunks SET knowledge_id='other' WHERE id='caption'")
	case "cross-kb-child":
		exec("UPDATE chunks SET knowledge_base_id='other-kb' WHERE id='caption'")
	case "no-children", "add-failure", "parent-write-failure":
		exec("DELETE FROM chunks WHERE parent_chunk_id='parent'")
	case "only-caption":
		exec("DELETE FROM chunks WHERE id='ocr'")
	case "only-ocr":
		exec("DELETE FROM chunks WHERE id='caption'")
	case "other-url":
		exec(`UPDATE chunks SET image_info='[{"original_url":"other"}]' WHERE id='caption'`)
	case "malformed-child":
		exec("UPDATE chunks SET image_info='broken' WHERE id='caption'")
	case "null-child":
		exec("UPDATE chunks SET image_info='[null]' WHERE id='caption'")
	case "empty-child":
		exec("UPDATE chunks SET image_info='[]' WHERE id='caption'")
	case "disabled-parent":
		exec("UPDATE chunks SET is_enabled=false WHERE id='parent'")
	case "parent-text":
		exec("UPDATE chunks SET chunk_type='parent_text' WHERE id='parent'")
	case "questions":
		exec(`UPDATE chunks SET context_header='Heading',metadata='{"generated_questions":[{"id":"q1","question":"Alias question"},{"id":"blank","question":" "}]}' WHERE id='parent'`)
	case "duplicate-questions":
		exec(`UPDATE chunks SET metadata='{"generated_questions":[{"id":"same","question":"First alias"},{"id":"same","question":"Second alias"}]}' WHERE id='parent'`)
	case "bad-question-revision":
		exec(`UPDATE chunks SET metadata='{"generated_questions_revision":true}' WHERE id='parent'`)
	case "bad-metadata":
		exec(`UPDATE chunks SET metadata='{"generated_questions":3}' WHERE id='parent'`)
	}
	if s.variant == "add-failure" {
		exec("CREATE TRIGGER block_add BEFORE INSERT ON chunks WHEN NEW.chunk_type='image_ocr' BEGIN SELECT RAISE(ABORT,'controlled child insert failure'); END")
	}
	if s.variant == "parent-write-failure" {
		exec("CREATE TRIGGER block_parent BEFORE UPDATE ON chunks WHEN OLD.id='parent' BEGIN SELECT RAISE(ABORT,'controlled parent write failure'); END")
	}
	if s.variant == "child-write-failure" {
		exec("CREATE TRIGGER block_child BEFORE UPDATE ON chunks WHEN OLD.id='ocr' BEGIN SELECT RAISE(ABORT,'controlled child write failure'); END")
	}
	if s.variant == "hash-write-failure" {
		exec("CREATE TRIGGER block_hash BEFORE UPDATE ON knowledges WHEN OLD.id='d1' BEGIN SELECT RAISE(ABORT,'controlled hash write failure'); END")
	}
	if pgDSN != "" {
		exec("SELECT setval('chunks_seq_id_seq',GREATEST(COALESCE(MAX(seq_id),0)+1,100000000),false) FROM chunks")
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
		user = "intruder"
		role = types.TenantRole("unknown")
	}
	if strings.HasPrefix(s.variant, "shared-") {
		tenant = 2
		permission := "editor"
		if s.variant == "shared-viewer" {
			permission = "viewer"
		}
		exec("INSERT INTO organizations(id,name,owner_tenant_id,owner_id) VALUES('org','Org',1,'caller')")
		exec("INSERT INTO organization_tenant_members(id,organization_id,tenant_id,role) VALUES('source','org',1,'admin'),('member','org',2,'editor')")
		exec("INSERT INTO kb_shares(id,knowledge_base_id,organization_id,source_tenant_id,permission,shared_by_user_id) VALUES('share','kb','org',1,'" + permission + "','caller')")
		if s.variant == "shared-revoked" {
			exec("UPDATE kb_shares SET deleted_at=CURRENT_TIMESTAMP")
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
	audit := svc.NewAuditLogService(repo.NewAuditLogRepository(db))
	shares := svc.NewKBShareService(repo.NewKBShareRepository(db), repo.NewOrganizationRepository(db), nil, nil, nil, audit)
	agents := svc.NewAgentShareService(repo.NewAgentShareRepository(db), repo.NewTenantDisabledSharedAgentRepository(db), repo.NewOrganizationRepository(db), repo.NewCustomAgentRepository(db), nil, nil, nil)
	br, kr, cr := repo.NewKnowledgeBaseRepository(db), repo.NewKnowledgeRepository(db), repo.NewChunkRepository(db)
	kbs := svc.NewKnowledgeBaseService(br, kr, cr, nil, shares, nil, nil, nil, repo.NewTenantRepository(db), nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil)
	calls := []any{}
	models := model{fail: s.variant == "model-failure", calls: &calls}
	engines := registry{e: engine{variant: s.variant, calls: &calls}}
	chunks := svc.NewChunkService(cr, kr, br, models, engines, nil, nil, nil)
	kg, e := svc.NewKnowledgeService(&config.Config{}, kr, nil, kbs, repo.NewTenantRepository(db), nil, chunks, cr, nil, nil, nil, nil, nil, models, nil, nil, nil, engines, nil, nil, shares, nil, nil, nil, nil, nil, audit)
	must(e)
	enabled := true
	cfg := &config.Config{Tenant: &config.TenantConfig{EnableRBAC: &enabled}}
	h := handler.NewKnowledgeHandler(cfg, kg, kbs, shares, agents, nil, nil, nil, nil, nil, nil)
	router := gin.New()
	router.Use(middleware.Recovery(), middleware.ErrorHandler(), middleware.AuditServiceProvider(audit))
	router.Use(func(c *gin.Context) {
		ctx := context.WithValue(c.Request.Context(), types.TenantIDContextKey, tenant)
		ctx = context.WithValue(ctx, types.UserIDContextKey, user)
		ctx = context.WithValue(ctx, types.TenantRoleContextKey, role)
		ctx = context.WithValue(ctx, types.TenantInfoContextKey, &types.Tenant{ID: tenant, RetrieverEngines: types.RetrieverEngines{Engines: []types.RetrieverEngineParams{{RetrieverType: types.VectorRetrieverType, RetrieverEngineType: types.PostgresRetrieverEngineType}}}})
		if machine {
			ctx = types.WithTenantAPIKeyScope(ctx, scope)
		}
		c.Request = c.Request.WithContext(ctx)
		c.Set(types.TenantIDContextKey.String(), tenant)
		c.Set(types.UserIDContextKey.String(), user)
		c.Next()
	})
	gate := middleware.NewAPIKeyRouteAuthorizer()
	gate.Register("PUT", "/api/v1/knowledge/image/:id/:chunk_id", middleware.APIKeyRoutePolicy{RequireFullAccess: true}.WithCapability(types.APIKeyCapabilityIngest))
	own := middleware.RequireOwnershipOrRole(types.TenantRoleAdmin, h.KBCreatorLookupFromKnowledgeID, cfg)
	write := middleware.RequireKBAccess(middleware.KBIDFromKnowledgeIDParam("id", kg), types.OrgRoleEditor, kbs, shares, agents, cfg)
	router.PUT("/api/v1/knowledge/image/:id/:chunk_id", gate.Middleware(), own, write, h.UpdateImageInfo)
	image := `[{"url":"stored-new","original_url":"img","caption":"New caption 中文","ocr_text":"New OCR 中文"}]`
	if s.variant == "same-content" {
		image = old
	}
	if s.variant == "missing-chunk-zero" {
		image = "[]"
	}
	if s.variant == "missing-chunk-two" {
		image = "[{},{}]"
	}
	b, e := json.Marshal(map[string]string{"image_info": image})
	must(e)
	payload := string(b)
	if s.variant == "body" {
		payload = s.body
	}
	target := "/api/v1/knowledge/image/d1/parent"
	w := httptest.NewRecorder()
	r := httptest.NewRequest("PUT", target, strings.NewReader(payload))
	r.Header.Set("Content-Type", "application/json")
	router.ServeHTTP(w, r)
	var body any
	decoder := json.NewDecoder(bytes.NewReader(w.Body.Bytes()))
	decoder.UseNumber()
	must(decoder.Decode(&body))
	var rows []map[string]any
	must(db.Table("chunks").Select("id,seq_id,tenant_id,knowledge_id,knowledge_base_id,content,chunk_type,parent_chunk_id,image_info,is_enabled,flags,status,chunk_index,start_at,end_at,content_revision,index_status,last_editor_id,CASE WHEN updated_at=? THEN 0 ELSE 1 END AS updated", stamp).Order("seq_id").Find(&rows).Error)
	normalize := map[string]string{}
	for _, row := range rows {
		if len(fmt.Sprint(row["id"])) == 36 {
			normalize[fmt.Sprint(row["id"])] = fmt.Sprintf("<new-%v>", row["chunk_type"])
		}
	}
	// Normalize only valid generated UUIDs. Semantic IDs, all content and SQL defaults remain compared.
	normalizeTree := func(value any) any {
		b, e := json.Marshal(value)
		must(e)
		text := string(b)
		for from, to := range normalize {
			text = strings.ReplaceAll(text, from, to)
		}
		var v any
		d := json.NewDecoder(strings.NewReader(text))
		d.UseNumber()
		must(d.Decode(&v))
		return v
	}
	var docs []map[string]any
	must(db.Table("knowledges").Select("id,file_hash,CASE WHEN updated_at=? THEN 0 ELSE 1 END AS updated", stamp).Order("id").Find(&docs).Error)
	var audits []map[string]any
	must(db.Table("audit_logs").Select("tenant_id,actor_user_id,actor_role,action,request_path,request_method,outcome,details").Order("id").Find(&audits).Error)
	for _, row := range audits {
		var v any
		raw := []byte(fmt.Sprint(row["details"]))
		if b, ok := row["details"].([]byte); ok {
			raw = b
		}
		must(json.Unmarshal(raw, &v))
		row["details"] = v
	}
	return map[string]any{"name": s.name, "variant": s.variant, "seed_statements": seed, "method": "PUT", "target": target, "request_body": payload, "status": w.Code, "body": body, "chunks": normalizeTree(rows), "documents": docs, "calls": normalizeTree(calls), "audits": audits, "caller_tenant": tenant, "user": user, "effective_role": types.TenantRoleFromContext(context.WithValue(context.Background(), types.TenantRoleContextKey, role)), "machine": machine, "scope": scope}
}
