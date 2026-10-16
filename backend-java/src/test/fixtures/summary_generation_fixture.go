//go:build ignore

// Real production Go worker/model HTTP/SQLite oracle, including committed failure checkpoints.
//
package main

import (
	"context"
	"encoding/json"
	"fmt"
	repo "github.com/Tencent/WeKnora/internal/application/repository"
	sr "github.com/Tencent/WeKnora/internal/application/repository/retriever/sqlite"
	svc "github.com/Tencent/WeKnora/internal/application/service"
	filesvc "github.com/Tencent/WeKnora/internal/application/service/file"
	retrieve "github.com/Tencent/WeKnora/internal/application/service/retriever"
	"github.com/Tencent/WeKnora/internal/config"
	"github.com/Tencent/WeKnora/internal/logger"
	"github.com/Tencent/WeKnora/internal/models/embedding"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/utils"
	sqlitevec "github.com/asg017/sqlite-vec-go-bindings/cgo"
	"github.com/hibiken/asynq"
	"github.com/panjf2000/ants/v2"
	"gopkg.in/yaml.v3"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
	gl "gorm.io/gorm/logger"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
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
	sqlitevec.Auto()
	must(os.Setenv("RETRIEVE_DRIVER", "sqlite"))
	must(os.Setenv("SSRF_WHITELIST", "127.0.0.1"))
	must(os.Setenv("SYSTEM_AES_KEY", ""))
	utils.ResetSSRFWhitelistForTest()
	names := []string{"default-prompt", "graph-only", "wiki-only", "plain", "profile", "fenced", "wrapped", "gist-only", "json-empty", "json-mistyped", "json-null", "unicode-profile", "no-model", "no-text", "short", "image-only", "image-ocr", "disabled-initial", "edited", "overlap", "custom-metadata", "long", "small-limit", "chat-empty-retry", "chat-empty-final", "chat-error-retry", "chat-error-final", "model-missing-retry", "model-missing-final", "embedding-error-retry", "embedding-error-final", "chunk-error", "index-error", "revision-changed", "metadata-changed", "cancelled", "deleting", "cancel-during-chat", "refresh", "refresh-duplicates", "refresh-disabled", "refresh-stale", "refresh-error-final", "refresh-empty"}
	out := []any{}
	for _, name := range names {
		dir, e := os.MkdirTemp("", "weknora-go-document-summary-")
		must(e)
		db, e := gorm.Open(sqlite.Open(filepath.Join(dir, "test.db")), &gorm.Config{Logger: gl.Default.LogMode(gl.Silent)})
		must(e)
		sqldb, e := db.DB()
		must(e)
		sqldb.SetMaxOpenConns(1)
		for _, m := range []string{"000000_init", "000002_knowledge_folder_path", "000003_knowledge_base_auto_tag_config", "000004_memory", "000007_system_admin_and_settings", "000008_processing_spans_and_pending_subtasks", "000010_knowledge_multi_tags", "000011_principal_model", "000021_knowledge_profiles", "000031_model_catalog_config", "000033_chunk_source_locators"} {
			b, e := os.ReadFile("migrations/sqlite/" + m + ".up.sql")
			must(e)
			must(db.Exec(string(b)).Error)
		}
		tr := repo.NewTenantRepository(db)
		tenant := &types.Tenant{ID: 1, Name: "Owner", Business: "test", Status: "active"}
		must(tr.CreateTenant(context.Background(), tenant))
		kr := repo.NewKnowledgeRepository(db)
		cr := repo.NewChunkRepository(db)
		br := repo.NewKnowledgeBaseRepository(db)
		kb := &types.KnowledgeBase{ID: "kb", TenantID: 1, Name: "KB", Type: "document", EmbeddingModelID: "embedding", SummaryModelID: "summary", IndexingStrategy: types.IndexingStrategy{VectorEnabled: true, KeywordEnabled: true}}
		if name == "graph-only" {
			kb.IndexingStrategy = types.IndexingStrategy{GraphEnabled: true}
		}
		if name == "wiki-only" {
			kb.IndexingStrategy = types.IndexingStrategy{WikiEnabled: true}
		}
		if name == "no-model" {
			kb.SummaryModelID = ""
		}
		if strings.HasPrefix(name, "model-missing") {
			kb.SummaryModelID = "missing"
		}
		must(br.CreateKnowledgeBase(context.Background(), kb))
		state := "finalizing"
		if name == "cancelled" || name == "deleting" {
			state = name
		}
		k := &types.Knowledge{ID: "knowledge", TenantID: 1, KnowledgeBaseID: "kb", Type: "file", FileType: "txt", Title: "SECRET_FILENAME_EXCLUDED", Description: "old summary", Profile: &types.KnowledgeProfile{Gist: "Old profile"}, ParseStatus: state, EnableStatus: "enabled", SummaryStatus: "pending", PendingSubtasksCount: 1, ErrorMessage: "old error", StorageSize: 11}
		if name == "custom-metadata" {
			k.CustomMetadata = types.JSON(`{"z":null,"a":"  approved  ","bool":true,"list":["x",2,null],"object":{"b":2,"a":"x"}}`)
		}
		must(kr.CreateKnowledge(context.Background(), k))
		body := "A production document explains onboarding, access controls and delivery checkpoints."
		if name == "short" {
			body = "短😀"
		}
		if name == "image-only" || name == "image-ocr" {
			body = "![page](page.png)"
		}
		if name == "long" || name == "small-limit" {
			body = strings.Repeat("中文😀abcdefghi", 2000)
		}
		chunks := []*types.Chunk{{ID: "text-1", TenantID: 1, KnowledgeID: k.ID, KnowledgeBaseID: kb.ID, ChunkType: types.ChunkTypeText, Content: body, ChunkIndex: 0, StartAt: 0, EndAt: len([]rune(body)), IsEnabled: true}}
		if name == "no-text" {
			chunks = nil
		}
		if name == "disabled-initial" || name == "refresh-disabled" {
			chunks[0].IsEnabled = false
		}
		if name == "edited" {
			chunks[0].ContentRevision = 1
			chunks = append(chunks, &types.Chunk{ID: "text-2", TenantID: 1, KnowledgeID: k.ID, KnowledgeBaseID: kb.ID, ChunkType: types.ChunkTypeText, Content: "Edited replacement 😀 delivered last.", ChunkIndex: 1, StartAt: 0, EndAt: 20, IsEnabled: true})
		}
		if name == "overlap" {
			chunks[0].Content = "Alpha beta gamma delta epsilon "
			chunks[0].EndAt = 31
			chunks = append(chunks, &types.Chunk{ID: "text-2", TenantID: 1, KnowledgeID: k.ID, KnowledgeBaseID: kb.ID, ChunkType: types.ChunkTypeText, Content: "gamma delta epsilon zeta eta final.", ChunkIndex: 1, StartAt: 11, EndAt: 50, IsEnabled: true})
		}
		if name == "image-ocr" {
			chunks = append(chunks, &types.Chunk{ID: "image-1", TenantID: 1, KnowledgeID: k.ID, KnowledgeBaseID: kb.ID, ChunkType: types.ChunkTypeImageOCR, Content: "Recognized onboarding process details.", ChunkIndex: 1, ParentChunkID: "text-1", IsEnabled: true, ImageInfo: `[{"url":"page.png","caption":"Page caption","ocr_text":"Recognized onboarding process details."}]`})
		}
		if name == "refresh" || name == "refresh-duplicates" {
			chunks = append(chunks, &types.Chunk{ID: "summary-existing", TenantID: 1, KnowledgeID: k.ID, KnowledgeBaseID: kb.ID, ChunkType: types.ChunkTypeSummary, Content: "old summary chunk", ChunkIndex: 2, IsEnabled: false, ParentChunkID: "text-1"})
		}
		if name == "refresh-duplicates" {
			chunks = append(chunks, &types.Chunk{ID: "summary-duplicate", TenantID: 1, KnowledgeID: k.ID, KnowledgeBaseID: kb.ID, ChunkType: types.ChunkTypeSummary, Content: "old duplicate chunk", ChunkIndex: 3, IsEnabled: true, ParentChunkID: "text-1"})
		}
		if len(chunks) > 0 {
			must(cr.CreateChunks(context.Background(), chunks))
		}
		if name == "disabled-initial" || name == "refresh-disabled" {
			must(db.Exec("UPDATE chunks SET is_enabled=false WHERE id='text-1'").Error)
		}
		if name == "plain" {
			must(db.Exec("INSERT INTO chunks(id,tenant_id,knowledge_base_id,knowledge_id,content,chunk_index,start_at,end_at,chunk_type,seq_id,deleted_at) VALUES('deleted-sequence',2,'kb','knowledge','deleted history',0,0,0,'text',10000,'2026-01-01')").Error)
		}
		requests := []any{}
		var mu sync.Mutex
		reply := "  A short objective summary 中文😀.  "
		switch name {
		case "profile", "fenced", "wrapped":
			reply = `{"summary":" Profile summary 中文 ","gist":" A headline ","topics":[" Kubernetes ","kubernetes.","Delivery"],"doc_type":" policy ","typical_question":" How to onboard? "}`
			if name == "fenced" {
				reply = "```json\n" + reply + "\n```"
			}
			if name == "wrapped" {
				reply = "Here is JSON: " + reply + " trailing [1]"
			}
		case "gist-only":
			reply = `{"gist":" Headline only ","topics":["topic"]}`
		case "json-empty":
			reply = `{"topics":["topic"]}`
		case "json-mistyped":
			reply = `{"summary":55,"topics":[3]}`
		case "json-null":
			reply = `null`
		case "unicode-profile":
			reply = `{"summary":"Résumé 😀","gist":"  ` + strings.Repeat("中😀", 80) + `  ","topics":[" (TOPIC!) ","topic","Alpha","Beta","Gamma","Delta","Epsilon"],"typical_question":"Line\n break?"}`
		}
		if strings.HasPrefix(name, "chat-empty") || name == "refresh-empty" {
			reply = " \n\t\u00a0"
		}
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			var body map[string]any
			must(json.NewDecoder(r.Body).Decode(&body))
			mu.Lock()
			requests = append(requests, map[string]any{"path": r.URL.Path, "body": body})
			mu.Unlock()
			w.Header().Set("Content-Type", "application/json")
			if r.URL.Path == "/v1/chat/completions" {
				if strings.HasPrefix(name, "chat-error") || name == "refresh-error-final" {
					w.WriteHeader(503)
					fmt.Fprint(w, `{"error":{"message":"synthetic provider unavailable"}}`)
					return
				}
				if name == "revision-changed" || name == "refresh-stale" {
					must(db.Exec("UPDATE chunks SET content_revision=1 WHERE id='text-1'").Error)
				}
				if name == "metadata-changed" {
					must(db.Exec(`UPDATE knowledges SET custom_metadata='{"new":"metadata"}' WHERE id='knowledge'`).Error)
				}
				if name == "cancel-during-chat" {
					must(db.Exec("UPDATE knowledges SET parse_status='cancelled' WHERE id='knowledge'").Error)
				}
				must(json.NewEncoder(w).Encode(map[string]any{"choices": []any{map[string]any{"message": map[string]any{"role": "assistant", "content": reply}, "finish_reason": "stop"}}}))
				return
			}
			if strings.HasPrefix(name, "embedding-error") {
				w.WriteHeader(400)
				fmt.Fprint(w, `{"error":{"message":"synthetic embedding unavailable"}}`)
				return
			}
			rows := []any{}
			for i := range body["input"].([]any) {
				rows = append(rows, map[string]any{"index": i, "embedding": []float32{1, 0, 0}})
			}
			must(json.NewEncoder(w).Encode(map[string]any{"data": rows}))
		}))
		mr := repo.NewModelRepository(db)
		for _, m := range []*types.Model{{ID: "summary", TenantID: 1, Name: "unknown", Type: types.ModelTypeKnowledgeQA, Source: types.ModelSourceRemote, Status: types.ModelStatusActive, Parameters: types.ModelParameters{BaseURL: server.URL + "/v1", APIKey: "synthetic-key", Provider: "generic"}}, {ID: "embedding", TenantID: 1, Name: "unknown", Type: types.ModelTypeEmbedding, Source: types.ModelSourceRemote, Status: types.ModelStatusActive, Parameters: types.ModelParameters{BaseURL: server.URL + "/v1", APIKey: "synthetic-key", Provider: "generic", EmbeddingParameters: types.EmbeddingParameters{Dimension: 3}}}} {
			must(mr.Create(context.Background(), m))
		}
		pool, e := ants.NewPool(2)
		must(e)
		models := svc.NewModelService(mr, br, nil, nil, embedding.NewBatchEmbedder(pool), nil)
		local := filesvc.NewLocalFileService(dir, "")
		registry := retrieve.NewRetrieveEngineRegistry(nil, nil)
		index := sr.NewSQLiteRetrieveEngineRepository(db)
		must(registry.Register(retrieve.NewKVHybridRetrieveEngine(index, types.SQLiteRetrieverEngineType)))
		ts := svc.NewTenantService(tr, nil)
		chunkSvc := svc.NewChunkService(cr, kr, br, models, registry, nil, nil, nil)
		baseSvc := svc.NewKnowledgeBaseService(br, kr, cr, nil, nil, models, registry, nil, tr, local, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil)
		prompt := "PROFILE {{language}}"
		if name == "default-prompt" {
			b, e := os.ReadFile("config/prompt_templates/generate_summary.yaml")
			must(e)
			var templates struct{ Templates []struct{ Content string } }
			must(yaml.Unmarshal(b, &templates))
			prompt = templates.Templates[0].Content
		}
		max := 8192
		if name == "small-limit" {
			max = 80
		}
		cfg := &config.Config{Conversation: &config.ConversationConfig{GenerateSummaryPrompt: prompt, Summary: &config.SummaryConfig{MaxInputChars: max, MaxCompletionTokens: 1024}}}
		service, e := svc.NewKnowledgeService(cfg, kr, nil, baseSvc, tr, ts, chunkSvc, cr, nil, nil, local, nil, nil, models, nil, nil, nil, registry, nil, nil, nil, nil, nil, nil, nil, nil, nil)
		must(e)
		if name == "chunk-error" {
			must(db.Exec("CREATE TRIGGER fail_chunk BEFORE INSERT ON chunks BEGIN SELECT RAISE(FAIL,'synthetic chunk failure');END").Error)
		}
		if name == "index-error" {
			must(db.Exec("CREATE TRIGGER fail_index BEFORE INSERT ON lite_embeddings BEGIN SELECT RAISE(FAIL,'synthetic index failure');END").Error)
		}
		payload := types.SummaryGenerationPayload{TenantID: 1, KnowledgeBaseID: "kb", KnowledgeID: "knowledge", Language: "en-US", Refresh: strings.HasPrefix(name, "refresh"), Attempt: 0}
		raw, e := json.Marshal(payload)
		must(e)
		ctx := context.WithValue(context.Background(), types.TenantInfoContextKey, tenant)
		retry := 0
		if strings.HasSuffix(name, "-final") {
			retry = 3
		}
		ctx = types.WithTaskRetryMetadata(ctx, retry, 3)
		err := service.ProcessSummaryGeneration(ctx, asynq.NewTask(types.TypeSummaryGeneration, raw))
		message := ""
		if err != nil {
			message = err.Error()
		}
		stored := types.Knowledge{}
		must(db.Where("id='knowledge'").First(&stored).Error)
		cc := []types.Chunk{}
		must(db.Where("knowledge_id='knowledge'").Order("chunk_index,id").Find(&cc).Error)
		ids := map[string]string{}
		for _, c := range cc {
			ids[c.ID] = c.ID
			if c.ChunkType == types.ChunkTypeSummary && !strings.HasPrefix(c.ID, "summary-") {
				ids[c.ID] = "new-summary"
			}
		}
		normalized := []any{}
		for _, c := range cc {
			if c.ChunkType != types.ChunkTypeSummary {
				continue
			}
			normalized = append(normalized, map[string]any{"id": ids[c.ID], "content": c.Content, "source_content": c.SourceContent, "type": c.ChunkType, "index": c.ChunkIndex, "parent": c.ParentChunkID, "status": c.Status, "seq_id": c.SeqID, "enabled": c.IsEnabled, "start": c.StartAt, "end": c.EndAt})
		}
		sort.Slice(normalized, func(i, j int) bool {
			return normalized[i].(map[string]any)["id"].(string) < normalized[j].(map[string]any)["id"].(string)
		})
		emb := []map[string]any{}
		must(db.Raw("SELECT source_id,source_type,chunk_id,knowledge_id,knowledge_base_id,content,dimension,is_enabled FROM lite_embeddings ORDER BY source_id").Scan(&emb).Error)
		for _, e := range emb {
			e["source_id"] = ids[e["source_id"].(string)]
			e["chunk_id"] = ids[e["chunk_id"].(string)]
		}
		sort.Slice(emb, func(i, j int) bool { return emb[i]["source_id"].(string) < emb[j]["source_id"].(string) })
		out = append(out, map[string]any{"name": name, "body": body, "reply": reply, "maximum": max, "retry": retry, "payload": payload, "requests": requests, "error": message, "chunks": normalized, "indexes": emb, "knowledge": map[string]any{"description": stored.Description, "profile": stored.Profile, "summary_status": stored.SummaryStatus, "parse_status": stored.ParseStatus, "pending_subtasks_count": stored.PendingSubtasksCount, "error_message": stored.ErrorMessage, "storage_size": stored.StorageSize}})
		server.Close()
		pool.Release()
		must(sqldb.Close())
		must(os.RemoveAll(dir))
	}
	must(json.NewEncoder(os.Stdout).Encode(out))
}
