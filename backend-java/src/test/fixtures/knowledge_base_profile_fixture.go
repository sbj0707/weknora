//go:build ignore

// Actual production Go kb:profile service, SQL repositories and HTTP model adapter.
package main

import (
	"context"
	"encoding/json"
	"fmt"
	repo "github.com/Tencent/WeKnora/internal/application/repository"
	svc "github.com/Tencent/WeKnora/internal/application/service"
	"github.com/Tencent/WeKnora/internal/config"
	httpHandler "github.com/Tencent/WeKnora/internal/handler"
	"github.com/Tencent/WeKnora/internal/logger"
	"github.com/Tencent/WeKnora/internal/middleware"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"github.com/Tencent/WeKnora/internal/utils"
	"github.com/gin-gonic/gin"
	"github.com/hibiken/asynq"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
	gl "gorm.io/gorm/logger"
	"net/http"
	"net/http/httptest"
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
func main() {
	time.Local = time.UTC
	logger.SetOutput(os.Stderr)
	must(os.Setenv("SSRF_WHITELIST", "127.0.0.1"))
	must(os.Setenv("SYSTEM_AES_KEY", ""))
	utils.ResetSSRFWhitelistForTest()
	names := []string{"success", "empty", "disabled", "nil-config", "force-disabled", "faq", "temporary", "missing-kb", "foreign-kb", "model-fallback", "model-override", "no-model", "model-missing", "chat-error", "empty-output", "null-output", "array-output", "malformed-output", "wrong-gist-type", "wrong-topics-type", "null-topic", "fenced", "prose", "normalize", "caps", "cache-hit", "force-cache", "cache-stale", "failed-keeps-text", "filter-rows", "many-rows", "payload-null", "payload-array", "payload-wrong-owner", "payload-owner-zero", "payload-owner-foreign", "payload-force-string", "custom-instructions", "language-zh", "owner-changed-during-chat", "delete-during-chat", "name-changed-during-chat", "typed-prefix-fence", "partial-fence-fallback", "casefold-output", "duplicate-null-output", "array-first-prose", "trailing-object", "wrong-question-type", "gist-null", "configured-prompt", "configured-custom-prompt"}
	if len(os.Args) > 2 && os.Args[2] == "--http" {
		names = []string{"http/success", "http/empty", "http/force-disabled", "http/faq", "http/temporary", "http/no-model", "http/model-missing", "http/malformed-output", "http/shared-editor", "http/shared-viewer", "http/shared-revoked", "http/foreign-denied", "http/noncreator", "http/creator-contributor", "http/creator-viewer", "http/api-manage", "http/api-ingest", "http/api-full", "http/api-scope", "http/missing-kb", "http/locale-header", "http/locale-env", "http/locale-empty-env"}
	}
	out := []any{}
	for _, caseName := range names {
		public := strings.HasPrefix(caseName, "http/")
		name := strings.TrimPrefix(caseName, "http/")
		dir, e := os.MkdirTemp("", "weknora-go-kb-profile-")
		must(e)
		db, e := gorm.Open(sqlite.Open(filepath.Join(dir, "test.db")), &gorm.Config{Logger: gl.Default.LogMode(gl.Silent)})
		must(e)
		sql, e := db.DB()
		must(e)
		sql.SetMaxOpenConns(1)
		for _, m := range []string{"000000_init", "000002_knowledge_folder_path", "000003_knowledge_base_auto_tag_config", "000004_memory", "000007_system_admin_and_settings", "000008_processing_spans_and_pending_subtasks", "000010_knowledge_multi_tags", "000011_principal_model", "000021_knowledge_profiles", "000031_model_catalog_config"} {
			b, e := os.ReadFile("migrations/sqlite/" + m + ".up.sql")
			must(e)
			must(db.Exec(string(b)).Error)
		}
		br := repo.NewKnowledgeBaseRepository(db)
		kr := repo.NewKnowledgeRepository(db)
		tr := repo.NewKnowledgeTagRepository(db)
		kb := &types.KnowledgeBase{ID: "kb", TenantID: 1, CreatorID: "caller", Name: "  Ops handbook 中文  ", Type: "document", SummaryModelID: "summary", ProfileConfig: &types.KnowledgeBaseProfileConfig{Enabled: name != "disabled" && name != "force-disabled"}}
		if name == "nil-config" {
			kb.ProfileConfig = nil
		}
		if name == "faq" {
			kb.Type = "faq"
		}
		if name == "temporary" {
			kb.IsTemporary = true
		}
		if name == "no-model" {
			kb.SummaryModelID = ""
		}
		if name == "model-missing" {
			kb.SummaryModelID = "missing"
		}
		if name == "model-override" {
			kb.ProfileConfig.ModelID = " override "
		}
		if name == "custom-instructions" {
			kb.ProfileConfig.CustomInstructions = "  Keep original terms 中文\n "
		}
		must(br.CreateKnowledgeBase(context.Background(), kb))
		count := 3
		if name == "empty" {
			count = 0
		}
		if name == "many-rows" {
			count = 530
		}
		docs := []*types.Knowledge{}
		for i := 0; i < count; i++ {
			p := &types.KnowledgeProfile{Gist: fmt.Sprintf("Gist %d\n\t中文", i), Topics: []string{" Kubernetes. ", "kubernetes", "K8s", "中文主题"}, DocType: " User  manual ", TypicalQuestion: fmt.Sprintf(" Question %d? ", i%45)}
			if i%3 == 2 {
				p = nil
			}
			d := &types.Knowledge{ID: fmt.Sprintf("doc-%04d", i), TenantID: 1, KnowledgeBaseID: "kb", Type: "file", Title: fmt.Sprintf("Title %d 中文", i), FileName: fmt.Sprintf(" fallback %d.pdf ", i), FileType: []string{".PDF", " md ", ""}[i%3], FolderPath: fmt.Sprintf(" /Folder%02d/sub/ ", i%25), CreatedAt: time.Date(2026, 1, 1, 0, i, 0, 0, time.UTC), ParseStatus: "completed", EnableStatus: "enabled", Profile: p}
			if i == 1 {
				d.Title = " \t "
				d.ParseStatus = "finalizing"
			}
			must(kr.CreateKnowledge(context.Background(), d))
			docs = append(docs, d)
		}
		if name == "filter-rows" {
			for i, state := range []string{"pending", "processing", "failed", "cancelled", "deleting"} {
				must(kr.CreateKnowledge(context.Background(), &types.Knowledge{ID: fmt.Sprintf("excluded-%d", i), TenantID: 1, KnowledgeBaseID: "kb", Title: "Excluded", ParseStatus: state, EnableStatus: "enabled", CreatedAt: time.Date(2020, 1, 1, 0, 0, 0, 0, time.UTC)}))
			}
			must(db.Exec("UPDATE knowledges SET enable_status='disabled' WHERE id='doc-0000'").Error)
			must(db.Exec("UPDATE knowledges SET tenant_id=2 WHERE id='doc-0002'").Error)
		}
		for i := 0; i < 35; i++ {
			tag := &types.KnowledgeTag{ID: fmt.Sprintf("tag-%02d", i), TenantID: 1, KnowledgeBaseID: "kb", Name: fmt.Sprintf(" 标签 %02d ", i)}
			must(tr.Create(context.Background(), tag))
			if count > 0 {
				must(db.Exec("INSERT INTO knowledge_tag_relations(knowledge_id,tag_id) VALUES(?,?)", "doc-0000", tag.ID).Error)
			}
		}
		reply := `{"gist":"Collection covers release operations 中文","topics":["Ops","Release"],"typical_questions":["How do releases work?","Who owns release?"]}`
		switch name {
		case "empty-output":
			reply = ""
		case "null-output":
			reply = "null"
		case "array-output":
			reply = "[]"
		case "malformed-output":
			reply = "not JSON"
		case "wrong-gist-type":
			reply = `{"gist":123}`
		case "wrong-topics-type":
			reply = `{"gist":"valid","topics":"Ops"}`
		case "null-topic":
			reply = `{"gist":"valid","topics":[null,"Ops"]}`
		case "fenced":
			reply = "```json\n" + reply + "\n```"
		case "prose":
			reply = "Here is the profile " + reply + " trailing"
		case "normalize":
			reply = `{"gist":"  Line\n\t中文   ","topics":[" Ops. ","ops","...","中文"," Ops!"],"typical_questions":[" How? ","how?","","中文问题"]}`
		case "typed-prefix-fence":
			reply = "{\"gist\":123}\n```json\n" + reply + "\n```"
		case "partial-fence-fallback":
			reply = "{\"gist\":\"Earlier valid gist\",\"topics\":23}\n```json\n{\"topics\":[\"Later topic\"]}\n```"
		case "casefold-output":
			reply = `{"GIST":"Case folded gist","TOPICS":["Upper"],"TYPICAL_QUESTIONS":["Why?"]}`
		case "duplicate-null-output":
			reply = `{"gist":"First useful gist","gist":null,"topics":["old"],"TOPICS":["new"],"typical_questions":["old?"],"typical_questions":null}`
		case "array-first-prose":
			reply = "First [1] then " + reply
		case "trailing-object":
			reply = reply + ` {"gist":"Trailing unused"}`
		case "wrong-question-type":
			reply = `{"gist":"valid","typical_questions":[12]}`
		case "gist-null":
			reply = `{"gist":null}`
		case "caps":
			b, e := json.Marshal(map[string]any{"gist": strings.Repeat("😀中文 ", 150), "topics": []string{strings.Repeat("中", 70), "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k"}, "typical_questions": []string{strings.Repeat("😀", 200), "A?", "B?", "C?", "D?", "E?", "F?"}})
			must(e)
			reply = string(b)
		}
		requests := []any{}
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			var body any
			must(json.NewDecoder(r.Body).Decode(&body))
			requests = append(requests, map[string]any{"path": r.URL.Path, "body": body})
			switch name {
			case "owner-changed-during-chat":
				must(db.Exec("UPDATE knowledge_bases SET tenant_id=2 WHERE id='kb'").Error)
			case "delete-during-chat":
				must(db.Exec("DELETE FROM knowledge_bases WHERE id='kb'").Error)
			case "name-changed-during-chat":
				must(db.Exec("UPDATE knowledge_bases SET name='Renamed while model runs' WHERE id='kb'").Error)
			}
			w.Header().Set("Content-Type", "application/json")
			if name == "chat-error" {
				w.WriteHeader(503)
				fmt.Fprint(w, `{"error":{"message":"synthetic provider failure"}}`)
				return
			}
			must(json.NewEncoder(w).Encode(map[string]any{"choices": []any{map[string]any{"finish_reason": "stop", "message": map[string]any{"role": "assistant", "content": reply}}}}))
		}))
		mr := repo.NewModelRepository(db)
		for _, id := range []string{"summary", "override"} {
			must(mr.Create(context.Background(), &types.Model{ID: id, TenantID: 1, Name: "unknown", Type: types.ModelTypeKnowledgeQA, Source: types.ModelSourceRemote, Status: types.ModelStatusActive, Parameters: types.ModelParameters{BaseURL: server.URL + "/v1", APIKey: "synthetic-key", Provider: "generic"}}))
		}
		models := svc.NewModelService(mr, br, nil, nil, nil, nil)
		var configured *config.Config
		if name == "configured-prompt" {
			var loadError error
			configured, loadError = config.LoadConfig()
			must(loadError)
		}
		if name == "configured-custom-prompt" {
			configured = &config.Config{Conversation: &config.ConversationConfig{GenerateKBDescriptionPrompt: "Describe in {{language}}. {{custom_instructions}}"}}
		}
		configuredPrompt := ""
		if configured != nil {
			configuredPrompt = configured.Conversation.GenerateKBDescriptionPrompt
		}
		handler := svc.NewKnowledgeBaseProfileService(configured, br, kr, models, nil)
		ctx := types.WithExecutionTenant(context.Background(), 1)
		ctx = context.WithValue(ctx, types.LanguageContextKey, "en-US")
		agg, e := handler.BuildAggregate(ctx, kb)
		if name != "faq" && name != "temporary" {
			must(e)
		}
		if strings.HasPrefix(name, "cache-") || name == "force-cache" || name == "failed-keeps-text" {
			t := time.Date(2020, 1, 1, 0, 0, 0, 0, time.UTC)
			hash := agg.Hash
			if name == "cache-stale" || name == "failed-keeps-text" {
				hash = "old-hash"
			}
			kb.GeneratedProfile = &types.KnowledgeBaseProfile{Gist: "Previous useful description", Topics: []string{"old"}, Stats: types.KnowledgeBaseProfileStats{DocumentCount: 99}, Status: "ready", AggregateHash: hash, ModelID: "old-model", GeneratedAt: &t}
			must(br.UpdateKnowledgeBaseGeneratedProfile(ctx, kb.ID, kb.GeneratedProfile))
			if name == "failed-keeps-text" {
				reply = "not JSON"
			}
		}
		beforeKB, e := br.GetKnowledgeBaseByIDAndTenant(ctx, "kb", 1)
		must(e)
		beforeRows, e := kr.ListKnowledgeProfileRows(ctx, 1, "kb")
		must(e)
		for _, row := range beforeRows {
			tags, e := kr.GetKnowledgeTags(ctx, []string{row.ID})
			must(e)
			for _, tag := range tags[row.ID] {
				row.Tags = append(row.Tags, strings.TrimSpace(tag.Name))
			}
		}
		payload := types.KnowledgeBaseProfilePayload{TenantID: 1, KnowledgeBaseID: "kb", Language: "en-US", Force: name == "force-disabled" || name == "force-cache"}
		if name == "language-zh" {
			payload.Language = "zh-CN"
		}
		if name == "payload-owner-zero" {
			payload.TenantID = 0
		}
		if name == "payload-owner-foreign" {
			payload.TenantID = 2
		}
		raw, e := json.Marshal(payload)
		must(e)
		switch name {
		case "payload-null":
			raw = []byte("null")
		case "payload-array":
			raw = []byte("[]")
		case "payload-wrong-owner":
			raw = []byte(`{"tenant_id":"1","knowledge_base_id":"kb"}`)
		case "payload-force-string":
			raw = []byte(`{"tenant_id":1,"knowledge_base_id":"kb","force":"true"}`)
		case "missing-kb":
			must(db.Exec("DELETE FROM knowledge_bases WHERE id='kb'").Error)
		case "foreign-kb":
			must(db.Exec("UPDATE knowledge_bases SET tenant_id=2 WHERE id='kb'").Error)
		}
		var httpResult any
		var err error
		if public {
			httpResult = publicProfile(db, br, kr, models, handler, name)
		} else {
			err = handler.Handle(ctx, asynq.NewTask(types.TypeKnowledgeBaseProfile, raw))
		}
		message := ""
		if err != nil {
			message = err.Error()
		}
		var stored struct {
			TenantID         uint64
			Name             string
			GeneratedProfile *types.KnowledgeBaseProfile
		}
		must(db.Table("knowledge_bases").Where("id='kb'").Scan(&stored).Error)
		out = append(out, map[string]any{"name": caseName, "http": httpResult, "configured_prompt": configuredPrompt, "knowledge_base": beforeKB, "rows": beforeRows, "aggregate": agg, "payload": json.RawMessage(raw), "reply": reply, "requests": requests, "error": message, "stored": stored})
		server.Close()
		sql.Close()
		os.RemoveAll(dir)
	}
	if len(os.Args) > 1 {
		b, e := json.Marshal(out)
		must(e)
		must(os.WriteFile(os.Args[1], append(b, byte(10)), 0600))
	} else {
		must(json.NewEncoder(os.Stdout).Encode(out))
	}
}

func publicProfile(db *gorm.DB, br interfaces.KnowledgeBaseRepository, kr interfaces.KnowledgeRepository, models interfaces.ModelService, profile interfaces.KnowledgeBaseProfileService, name string) any {
	callerTenant := uint64(1)
	role := types.TenantRoleOwner
	machine := strings.HasPrefix(name, "api-")
	scope := types.TenantAPIKeyScope{FullAccess: name == "api-full"}
	header := "en-US,zh;q=0.9"
	env := ""
	if name == "noncreator" {
		role = types.TenantRoleContributor
		must(db.Exec("UPDATE knowledge_bases SET creator_id='another' WHERE id='kb'").Error)
	}
	if name == "creator-contributor" {
		role = types.TenantRoleContributor
	}
	if name == "creator-viewer" {
		role = types.TenantRoleViewer
	}
	if strings.HasPrefix(name, "shared-") || name == "foreign-denied" {
		callerTenant = 2
		role = types.TenantRoleContributor
		must(db.Exec("INSERT INTO organizations(id,name,owner_tenant_id,owner_id) VALUES('org','Org',1,'caller')").Error)
		member := "editor"
		if name == "shared-viewer" {
			member = "viewer"
		}
		must(db.Exec("INSERT INTO organization_tenant_members(id,organization_id,tenant_id,role) VALUES('source','org',1,'admin'),('member','org',2,?)", member).Error)
		if name != "foreign-denied" {
			must(db.Exec("INSERT INTO kb_shares(id,knowledge_base_id,organization_id,source_tenant_id,permission,shared_by_user_id) VALUES('share','kb','org',1,'editor','caller')").Error)
		}
		if name == "shared-revoked" {
			must(db.Exec("UPDATE kb_shares SET deleted_at=CURRENT_TIMESTAMP WHERE id='share'").Error)
		}
	}
	if name == "api-manage" || name == "api-scope" {
		scope.Capabilities = types.StringArray{string(types.APIKeyCapabilityManageKnowledgeBases)}
	}
	if name == "api-ingest" {
		scope.Capabilities = types.StringArray{string(types.APIKeyCapabilityIngest)}
	}
	if name == "api-scope" {
		scope.KnowledgeBaseIDs = []string{"other"}
	}
	if name == "locale-env" {
		env = " ko-KR "
	}
	if name == "locale-empty-env" {
		env = " \t "
		header = ""
	}
	if name == "locale-header" {
		header = " en-US ;q=0.1,zh-CN;q=1"
	}
	must(os.Setenv("WEKNORA_LANGUAGE", env))
	defer os.Unsetenv("WEKNORA_LANGUAGE")
	audit := svc.NewAuditLogService(repo.NewAuditLogRepository(db))
	shares := svc.NewKBShareService(repo.NewKBShareRepository(db), repo.NewOrganizationRepository(db), nil, nil, nil, audit)
	agents := svc.NewAgentShareService(repo.NewAgentShareRepository(db), repo.NewTenantDisabledSharedAgentRepository(db), repo.NewOrganizationRepository(db), repo.NewCustomAgentRepository(db), nil, nil, nil)
	kbs := svc.NewKnowledgeBaseService(br, kr, repo.NewChunkRepository(db), nil, shares, models, nil, nil, repo.NewTenantRepository(db), nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil)
	enabled := true
	cfg := &config.Config{Tenant: &config.TenantConfig{EnableRBAC: &enabled}}
	h := httpHandler.NewKnowledgeBaseHandler(cfg, kbs, nil, shares, agents, nil, nil, nil, nil, nil, profile)
	template := "/api/v1/knowledge-bases/:id/profile/generate"
	path := "/api/v1/knowledge-bases/kb/profile/generate"
	gate := middleware.NewAPIKeyRouteAuthorizer()
	gate.Register("POST", template, middleware.APIKeyRoutePolicy{RequireFullAccess: true}.WithCapability(types.APIKeyCapabilityManageKnowledgeBases))
	gin.SetMode(gin.TestMode)
	router := gin.New()
	router.Use(middleware.ErrorHandler(), middleware.AuditServiceProvider(audit), middleware.Language())
	router.Use(func(c *gin.Context) {
		ctx := context.WithValue(c.Request.Context(), types.TenantIDContextKey, callerTenant)
		ctx = context.WithValue(ctx, types.UserIDContextKey, "caller")
		ctx = context.WithValue(ctx, types.TenantRoleContextKey, role)
		if machine {
			ctx = types.WithTenantAPIKeyScope(ctx, scope)
		}
		c.Request = c.Request.WithContext(ctx)
		c.Set(types.TenantIDContextKey.String(), callerTenant)
		c.Set(types.UserIDContextKey.String(), "caller")
		c.Next()
	})
	router.POST(template, gate.Middleware(), middleware.RequireOwnershipOrRole(types.TenantRoleAdmin, h.KBCreatorLookup, cfg), middleware.RequireKBAccess(middleware.KBIDFromParam("id"), types.OrgRoleEditor, kbs, shares, agents, cfg), h.GenerateKnowledgeBaseProfile)
	request := httptest.NewRequest("POST", path, nil)
	request.Header.Set("Accept-Language", header)
	w := httptest.NewRecorder()
	router.ServeHTTP(w, request)
	var body any
	must(json.Unmarshal(w.Body.Bytes(), &body))
	return map[string]any{"status": w.Code, "body": body, "caller_tenant": callerTenant, "role": role, "machine": machine, "scope": scope, "header": header, "environment": env}
}
