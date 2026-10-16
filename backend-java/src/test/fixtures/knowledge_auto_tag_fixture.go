//go:build ignore

// Real Go automatic tagging: existing SQL repositories, production model HTTP and tracker.
package main

import (
	"context"
	"encoding/json"
	"fmt"
	repo "github.com/Tencent/WeKnora/internal/application/repository"
	svc "github.com/Tencent/WeKnora/internal/application/service"
	filesvc "github.com/Tencent/WeKnora/internal/application/service/file"
	"github.com/Tencent/WeKnora/internal/logger"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/utils"
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
	names := []string{"success", "missing-confidence", "percent", "low-confidence", "duplicates", "out-of-range", "empty", "null", "array", "malformed", "wrong-index-type", "wrong-confidence-type", "fenced", "prose", "max-zero", "max-over", "no-tags", "capped-tags", "already-tagged", "append", "all-attached", "disabled", "no-model", "model-missing", "missing-knowledge", "foreign-knowledge", "foreign-kb", "missing-kb", "faq", "cancelled", "deleting", "failed", "no-text", "disabled-chunk", "image-chunks", "long-content", "chat-error", "cancel-during-chat", "deleted-tag-during-chat", "foreign-chunk", "revision-during-chat", "superseded-during-chat", "superseded-entry", "payload-owner-zero", "payload-owner-foreign", "payload-knowledge-empty", "payload-base-empty", "payload-array", "payload-wrong-tenant-type", "payload-wrong-attempt-type", "payload-null", "summary-during-chat"}
	out := []any{}
	for _, name := range names {
		dir, e := os.MkdirTemp("", "weknora-go-auto-tag-")
		must(e)
		db, e := gorm.Open(sqlite.Open(filepath.Join(dir, "test.db")), &gorm.Config{Logger: gl.Default.LogMode(gl.Silent)})
		must(e)
		sql, e := db.DB()
		must(e)
		sql.SetMaxOpenConns(1)
		for _, m := range []string{"000000_init", "000002_knowledge_folder_path", "000003_knowledge_base_auto_tag_config", "000004_memory", "000007_system_admin_and_settings", "000008_processing_spans_and_pending_subtasks", "000010_knowledge_multi_tags", "000011_principal_model", "000021_knowledge_profiles", "000031_model_catalog_config", "000033_chunk_source_locators"} {
			b, e := os.ReadFile("migrations/sqlite/" + m + ".up.sql")
			must(e)
			must(db.Exec(string(b)).Error)
		}
		tr := repo.NewTenantRepository(db)
		must(tr.CreateTenant(context.Background(), &types.Tenant{ID: 1, Name: "Owner", Business: "test", Status: "active"}))
		kr := repo.NewKnowledgeRepository(db)
		cr := repo.NewChunkRepository(db)
		br := repo.NewKnowledgeBaseRepository(db)
		tags := repo.NewKnowledgeTagRepository(db)
		config := &types.AutoTagConfig{Enabled: name != "disabled", MaxTags: 3}
		if name == "max-zero" {
			config.MaxTags = 0
		}
		if name == "max-over" {
			config.MaxTags = 20
		}
		if name == "append" || name == "all-attached" {
			v := false
			config.SkipIfTagged = &v
		}
		kb := &types.KnowledgeBase{ID: "kb", TenantID: 1, Name: "KB", Type: "document", SummaryModelID: "summary", AutoTagConfig: config}
		if name == "no-model" {
			kb.SummaryModelID = ""
		}
		if name == "model-missing" {
			kb.SummaryModelID = "missing"
		}
		must(br.CreateKnowledgeBase(context.Background(), kb))
		knowledge := &types.Knowledge{ID: "knowledge", TenantID: 1, KnowledgeBaseID: "kb", Type: "file", FileName: " policy.pdf ", Description: " existing summary 中文 ", ParseStatus: "completed", EnableStatus: "enabled", SummaryStatus: "completed", PendingSubtasksCount: 2}
		if name == "no-text" {
			knowledge.FileName = ""
			knowledge.Description = ""
		}
		if name == "cancelled" || name == "deleting" || name == "failed" {
			knowledge.ParseStatus = name
		}
		must(kr.CreateKnowledge(context.Background(), knowledge))
		chunks := []*types.Chunk{{ID: "text-1", TenantID: 1, KnowledgeBaseID: "kb", KnowledgeID: "knowledge", ChunkType: "text", Content: " First chunk explains policy. ", ChunkIndex: 0, StartAt: 60, IsEnabled: true}, {ID: "text-2", TenantID: 1, KnowledgeBaseID: "kb", KnowledgeID: "knowledge", ChunkType: "text", Content: " Second chunk explains release. ", ChunkIndex: 1, StartAt: 0, IsEnabled: true}}
		if name == "no-text" {
			chunks = nil
		}
		if name == "image-chunks" {
			chunks[0].ChunkType = "image_ocr"
			chunks[1].ChunkType = "image_caption"
		}
		if name == "long-content" {
			chunks[0].Content = strings.Repeat("😀中文policy", 2400)
		}
		if len(chunks) > 0 {
			must(cr.CreateChunks(context.Background(), chunks))
		}
		if name == "disabled-chunk" {
			must(db.Exec("UPDATE chunks SET is_enabled=false WHERE id='text-1'").Error)
		}
		if name == "foreign-chunk" {
			must(db.Exec("UPDATE chunks SET knowledge_base_id='foreign' WHERE id='text-1'").Error)
		}
		tagCount := 4
		if name == "no-tags" {
			tagCount = 0
		}
		if name == "capped-tags" {
			tagCount = 505
		}
		for i := 0; i < tagCount; i++ {
			must(tags.Create(context.Background(), &types.KnowledgeTag{ID: fmt.Sprintf("tag-%03d", i+1), TenantID: 1, KnowledgeBaseID: "kb", Name: fmt.Sprintf("标签 %d", i+1), SortOrder: i, CreatedAt: time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)}))
		}
		if name == "already-tagged" || name == "append" || name == "all-attached" {
			must(kr.SetKnowledgeTags(context.Background(), "knowledge", []string{"tag-001"}))
		}
		reply := `{"matches":[{"index":1,"confidence":0.9},{"index":2,"confidence":0.8}]}`
		switch name {
		case "missing-confidence":
			reply = `{"matches":[{"index":2}]}`
		case "percent":
			reply = `{"matches":[{"index":3,"confidence":95},{"index":1,"confidence":75}]}`
		case "low-confidence":
			reply = `{"matches":[{"index":1,"confidence":0.74},{"index":2,"confidence":0}]}`
		case "duplicates":
			reply = `{"matches":[{"index":1},{"index":1},{"index":2,"confidence":0.75}]}`
		case "out-of-range":
			reply = `{"matches":[{"index":0},{"index":10},{"index":-1},{"index":3}]}`
		case "empty":
			reply = `{"matches":[]}`
		case "null":
			reply = `null`
		case "array":
			reply = `[]`
		case "malformed":
			reply = `not JSON`
		case "wrong-index-type":
			reply = `{"matches":[{"index":1.0}]}`
		case "wrong-confidence-type":
			reply = `{"matches":[{"index":1,"confidence":"90"}]}`
		case "fenced":
			reply = "```json\n" + reply + "\n```"
		case "prose":
			reply = "Sure [here] is JSON " + reply + " trailing [1]"
		case "all-attached":
			reply = `{"matches":[{"index":1}]}`
		case "max-zero", "max-over":
			reply = `{"matches":[{"index":1},{"index":2},{"index":3},{"index":4}]}`
		}
		requests := []any{}
		tracker := svc.NewSpanTracker(repo.NewKnowledgeSpanRepository(db), db)
		ctx := context.WithValue(context.Background(), types.TenantIDContextKey, uint64(1))
		_, attempt, e := tracker.OpenAttempt(ctx, "knowledge", "auto-tag-oracle")
		must(e)
		post := tracker.BeginStage(ctx, "knowledge", attempt, types.StagePostProcess, nil)
		tracker.EndSpan(ctx, post, nil)
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			var body any
			must(json.NewDecoder(r.Body).Decode(&body))
			requests = append(requests, map[string]any{"path": r.URL.Path, "body": body})
			if name == "cancel-during-chat" {
				must(db.Exec("UPDATE knowledges SET parse_status='cancelled' WHERE id='knowledge'").Error)
			}
			if name == "deleted-tag-during-chat" {
				must(db.Exec("DELETE FROM knowledge_tags WHERE id='tag-001'").Error)
			}
			if name == "summary-during-chat" {
				must(db.Exec("UPDATE knowledges SET description='Fresh parallel summary' WHERE id='knowledge'").Error)
			}
			if name == "revision-during-chat" {
				must(db.Exec("UPDATE chunks SET content_revision=1 WHERE id='text-1'").Error)
			}
			if name == "superseded-during-chat" {
				_, _, e := tracker.OpenAttempt(ctx, "knowledge", "successor")
				must(e)
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
		must(mr.Create(ctx, &types.Model{ID: "summary", TenantID: 1, Name: "unknown", Type: types.ModelTypeKnowledgeQA, Source: types.ModelSourceRemote, Status: types.ModelStatusActive, Parameters: types.ModelParameters{BaseURL: server.URL + "/v1", APIKey: "synthetic-key", Provider: "generic"}}))
		models := svc.NewModelService(mr, br, nil, nil, nil, nil)
		local := filesvc.NewLocalFileService(dir, "")
		base := svc.NewKnowledgeBaseService(br, kr, cr, nil, nil, models, nil, nil, tr, local, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil)
		chunkService := svc.NewChunkService(cr, kr, br, models, nil, nil, nil, nil)
		if name == "missing-knowledge" {
			must(db.Exec("DELETE FROM knowledges WHERE id='knowledge'").Error)
		}
		if name == "foreign-knowledge" {
			must(db.Exec("UPDATE knowledges SET tenant_id=2 WHERE id='knowledge'").Error)
		}
		if name == "foreign-kb" {
			must(db.Exec("UPDATE knowledge_bases SET tenant_id=2 WHERE id='kb'").Error)
		}
		if name == "missing-kb" {
			must(db.Exec("DELETE FROM knowledge_bases WHERE id='kb'").Error)
		}
		if name == "faq" {
			must(db.Exec("UPDATE knowledge_bases SET type='faq' WHERE id='kb'").Error)
		}
		if name == "superseded-entry" {
			_, _, e := tracker.OpenAttempt(ctx, "knowledge", "successor")
			must(e)
		}
		payload := types.KnowledgeAutoTagPayload{TenantID: 1, KnowledgeID: "knowledge", KnowledgeBaseID: "kb", Language: "en-US", Attempt: attempt}

		switch name {
		case "payload-owner-zero":
			payload.TenantID = 0
		case "payload-owner-foreign":
			payload.TenantID = 2
		case "payload-knowledge-empty":
			payload.KnowledgeID = ""
		case "payload-base-empty":
			payload.KnowledgeBaseID = ""
		}
		raw, e := json.Marshal(payload)
		must(e)
		switch name {
		case "payload-array":
			raw = []byte(`[]`)
		case "payload-null":
			raw = []byte(`null`)
		case "payload-wrong-tenant-type":
			raw = []byte(`{"tenant_id":"1","knowledge_id":"knowledge","knowledge_base_id":"kb"}`)
		case "payload-wrong-attempt-type":
			raw = []byte(`{"tenant_id":1,"knowledge_id":"knowledge","knowledge_base_id":"kb","attempt":1.0}`)
		}
		var actualPayload any
		must(json.Unmarshal(raw, &actualPayload))
		handler := svc.NewKnowledgeAutoTagService(kr, tags, base, chunkService, models, tracker)
		err := handler.Handle(ctx, asynq.NewTask(types.TypeKnowledgeAutoTag, raw))
		message := ""
		if err != nil {
			message = err.Error()
		}
		relations := []string{}
		must(db.Table("knowledge_tag_relations").Where("knowledge_id='knowledge'").Order("tag_id").Pluck("tag_id", &relations).Error)
		spans := []types.KnowledgeProcessingSpan{}
		must(db.Where("name='postprocess.auto_tag'").Order("created_at").Find(&spans).Error)
		var span any = nil
		if len(spans) > 0 {
			s := spans[len(spans)-1]
			span = map[string]any{"name": s.Name, "status": s.Status, "input": s.Input, "output": s.Output, "error_code": s.ErrorCode}
		}
		out = append(out, map[string]any{"name": name, "reply": reply, "config": config, "payload": json.RawMessage(raw), "knowledge": knowledge, "chunks": chunks, "tag_count": tagCount, "requests": requests, "error": message, "relations": relations, "span": span})
		server.Close()
		sql.Close()
		os.RemoveAll(dir)
	}
	must(json.NewEncoder(os.Stdout).Encode(out))
}
