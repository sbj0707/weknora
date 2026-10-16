//go:build ignore

// Actual Go question:generation worker, all payload modes, provider HTTP and SQL effects.
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
	"github.com/alicebob/miniredis/v2"
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
	names := []string{"success", "default-prompt", "count-zero", "count-over", "numbered", "empty-output", "chat-error", "chat-error-second", "model-missing", "embedding-missing", "embedding-error-retry", "embedding-error-final", "index-error-retry", "index-error-final", "metadata-error", "revision-changed", "cancel-during-chat", "cancelled", "deleting", "missing-chunk", "foreign-tenant-chunk", "foreign-document-chunk", "foreign-neighbor", "disabled", "boundary", "custom", "override", "image", "metadata-old", "idlong", "prompt-empty", "no-text", "duplicate-ids", "versioned", "kb-missing", "model-missing-final", "embedding-missing-final", "superseded-during-chat"}
	out := []any{}
	for _, mode := range []string{"legacy", "single", "batch"} {
		for _, name := range names {
			dir, e := os.MkdirTemp("", "weknora-go-questions-")
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
			tenant := &types.Tenant{ID: 1, Name: "Owner", Business: "test", Status: "active"}
			must(tr.CreateTenant(context.Background(), tenant))
			kr := repo.NewKnowledgeRepository(db)
			cr := repo.NewChunkRepository(db)
			br := repo.NewKnowledgeBaseRepository(db)
			kb := &types.KnowledgeBase{ID: "kb", TenantID: 1, Name: "KB", Type: "document", EmbeddingModelID: "embedding", SummaryModelID: "summary", IndexingStrategy: types.IndexingStrategy{VectorEnabled: true, KeywordEnabled: true}, QuestionGenerationConfig: &types.QuestionGenerationConfig{Enabled: true, QuestionCount: 3}}
			if name == "model-missing" || name == "model-missing-final" {
				kb.SummaryModelID = "missing"
			}
			if name == "embedding-missing" || name == "embedding-missing-final" {
				kb.EmbeddingModelID = "missing"
			}
			if name == "custom" || name == "override" {
				kb.QuestionGenerationConfig.CustomInstructions = "  KB business instruction 中文  "
			}
			must(br.CreateKnowledgeBase(context.Background(), kb))
			state := "finalizing"
			if name == "cancelled" || name == "deleting" {
				state = name
			}
			knowledge := &types.Knowledge{ID: "knowledge", TenantID: 1, KnowledgeBaseID: "kb", Type: "file", FileType: "txt", Title: "  Document title 中文  ", Description: "summary remains visible", ParseStatus: state, EnableStatus: "enabled", SummaryStatus: "completed", PendingSubtasksCount: 2, ErrorMessage: "old error", StorageSize: 11}
			if name == "override" {
				must(knowledge.SetProcessOverrides(&types.KnowledgeProcessOverrides{QuestionGenerationConfig: &types.QuestionGenerationConfig{Enabled: true, CustomInstructions: "  Override instruction 😀 "}}))
			}
			must(kr.CreateKnowledge(context.Background(), knowledge))
			id1, id2 := "text-1", "text-2"
			if name == "idlong" {
				id1 = strings.Repeat("x", 55)
			}
			body1 := "First chunk explains onboarding policy and access controls."
			body2 := "Second chunk describes release and deployment checkpoints."
			if name == "image" {
				body1 = "![page](page.png)"
			}
			if name == "no-text" {
				body1 = "  \n "
				body2 = ""
			}
			chunks := []*types.Chunk{{ID: id1, TenantID: 1, KnowledgeID: "knowledge", KnowledgeBaseID: "kb", ChunkType: types.ChunkTypeText, Content: body1, ChunkIndex: 0, StartAt: 0, EndAt: 60, IsEnabled: true}, {ID: id2, TenantID: 1, KnowledgeID: "knowledge", KnowledgeBaseID: "kb", ChunkType: types.ChunkTypeText, Content: body2, ChunkIndex: 1, StartAt: 60, EndAt: 120, IsEnabled: true}, {ID: "before", TenantID: 1, KnowledgeID: "knowledge", KnowledgeBaseID: "kb", ChunkType: types.ChunkTypeText, Content: "Preceding boundary context.", ChunkIndex: 2, StartAt: -40, EndAt: 0, IsEnabled: true}, {ID: "after", TenantID: 1, KnowledgeID: "knowledge", KnowledgeBaseID: "kb", ChunkType: types.ChunkTypeText, Content: "Following boundary context.", ChunkIndex: 3, StartAt: 120, EndAt: 150, IsEnabled: true}}
			if name != "boundary" {
				chunks = chunks[:2]
			}
			if name == "no-text" && mode == "legacy" {
				chunks = nil
			}
			if name == "image" {
				chunks = append(chunks, &types.Chunk{ID: "image", TenantID: 1, KnowledgeID: "knowledge", KnowledgeBaseID: "kb", ChunkType: types.ChunkTypeImageOCR, ParentChunkID: id1, Content: "OCR source", ChunkIndex: 4, IsEnabled: true, ImageInfo: `[{"url":"page.png","caption":"Caption text","ocr_text":"OCR policy text"}]`})
			}
			if name == "metadata-old" {
				chunks[0].Metadata = types.JSON(`{"generated_questions":[{"id":"old","question":"Old question","content_revision":0}],"unrelated":"old"}`)
			}
			if len(chunks) > 0 {
				must(cr.CreateChunks(context.Background(), chunks))
			}
			if name == "versioned" {
				must(db.Exec("UPDATE chunks SET content_revision=4 WHERE id=?", id1).Error)
			}
			if name == "disabled" {
				must(db.Exec("UPDATE chunks SET is_enabled=false WHERE id=?", id1).Error)
			}
			if name == "missing-chunk" {
				must(db.Delete(&types.Chunk{}, "id=?", id1).Error)
			}
			if name == "foreign-tenant-chunk" {
				must(db.Exec("UPDATE chunks SET tenant_id=2 WHERE id=?", id1).Error)
			}
			if name == "foreign-document-chunk" {
				must(db.Exec("UPDATE chunks SET knowledge_id='foreign-document' WHERE id=?", id1).Error)
			}
			if name == "foreign-neighbor" {
				must(db.Exec("UPDATE chunks SET knowledge_id='foreign-document' WHERE id=?", id2).Error)
			}
			requests := []any{}
			var mu sync.Mutex
			chatCount := 0
			onFirstChat := func() {}
			reply := "How do onboarding access controls work?\nWhat are release delivery checkpoints?\nWhich onboarding policy governs access?"
			if name == "numbered" {
				reply = "  1. How does access work?\n - 中文?\n*😀😀\n123abcde\n**) Sixsix\n4. Final question?"
			}
			if name == "empty-output" {
				reply = "\n x\n 12. abc\n"
			}
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				var body map[string]any
				must(json.NewDecoder(r.Body).Decode(&body))
				mu.Lock()
				requests = append(requests, map[string]any{"path": r.URL.Path, "body": body})
				mu.Unlock()
				w.Header().Set("Content-Type", "application/json")
				if r.URL.Path == "/v1/chat/completions" {
					chatCount++
					if chatCount == 1 {
						onFirstChat()
					}
					if name == "chat-error" || (name == "chat-error-second" && chatCount == 2) {
						w.WriteHeader(503)
						fmt.Fprint(w, `{"error":{"message":"synthetic provider unavailable"}}`)
						return
					}
					if name == "revision-changed" && chatCount == 1 {
						must(db.Exec("UPDATE chunks SET content_revision=1 WHERE id=?", id1).Error)
					}
					if name == "cancel-during-chat" && chatCount == 1 {
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
				values := body["input"].([]any)
				rows := []any{}
				for i := range values {
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
			chunkService := svc.NewChunkService(cr, kr, br, models, registry, nil, nil, nil)
			baseService := svc.NewKnowledgeBaseService(br, kr, cr, nil, nil, models, registry, nil, tr, local, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil)
			prompt := "Count {{question_count}} lang {{language}} title {{doc_name}}\n{{context}}{{content}}"
			if name == "default-prompt" {
				b, e := os.ReadFile("config/prompt_templates/generate_questions.yaml")
				must(e)
				var templates struct{ Templates []struct{ Content string } }
				must(yaml.Unmarshal(b, &templates))
				prompt = templates.Templates[0].Content
			}
			if name == "prompt-empty" {
				prompt = " \n "
			}
			cfg := &config.Config{Conversation: &config.ConversationConfig{GenerateQuestionsPrompt: prompt}}
			tracker := svc.NewSpanTracker(repo.NewKnowledgeSpanRepository(db), db)
			trackContext := context.WithValue(context.Background(), types.TenantIDContextKey, uint64(1))
			_, attempt, e := tracker.OpenAttempt(trackContext, "knowledge", "question-oracle-trace")
			must(e)
			if name == "superseded-during-chat" {
				onFirstChat = func() { _, _, err := tracker.OpenAttempt(trackContext, "knowledge", "successor"); must(err) }
			}
			stage := tracker.BeginStage(trackContext, "knowledge", attempt, types.StagePostProcess, nil)
			if mode != "legacy" {
				group := tracker.BeginSubSpan(trackContext, stage, "postprocess.question", types.SpanKindSubSpan, nil)
				tracker.EndSpan(trackContext, group, nil)
			}
			service, e := svc.NewKnowledgeService(cfg, kr, nil, baseService, tr, ts, chunkService, cr, nil, nil, local, nil, nil, models, nil, nil, nil, registry, nil, nil, nil, nil, nil, nil, nil, tracker, nil)
			must(e)
			if name == "metadata-error" {
				must(db.Exec("CREATE TRIGGER fail_metadata BEFORE UPDATE OF metadata ON chunks BEGIN SELECT RAISE(FAIL,'synthetic metadata failure');END").Error)
			}
			if strings.HasPrefix(name, "index-error") {
				must(db.Exec("CREATE TRIGGER fail_index BEFORE INSERT ON lite_embeddings BEGIN SELECT RAISE(FAIL,'synthetic index failure');END").Error)
			}
			payload := types.QuestionGenerationPayload{TenantID: 1, KnowledgeBaseID: "kb", KnowledgeID: "knowledge", Language: "en-US", QuestionCount: 3, Attempt: attempt}
			if mode == "single" {
				payload.ChunkID = id1
			}
			if mode == "batch" {
				payload.ChunkIDs = []string{id1, id2}
				payload.BatchIndex = 2
			}
			if name == "boundary" && mode != "legacy" {
				payload.PrevChunkID = "before"
				payload.NextChunkID = "after"
			}
			if name == "duplicate-ids" && mode == "batch" {
				payload.ChunkIDs = []string{id1, id1, id2}
			}
			if name == "count-zero" {
				payload.QuestionCount = 0
			}
			if name == "count-over" {
				payload.QuestionCount = 15
			}
			raw, e := json.Marshal(payload)
			must(e)
			retry := 0
			if strings.HasSuffix(name, "-final") {
				retry = 3
			}
			if name == "kb-missing" {
				must(db.Exec("UPDATE knowledge_bases SET deleted_at='2026-01-01' WHERE id='kb'").Error)
			}
			redis, e := miniredis.Run()
			must(e)
			redisOptions := asynq.RedisClientOpt{Addr: redis.Addr()}
			worker := asynq.NewServer(redisOptions, asynq.Config{Concurrency: 1, Queues: map[string]int{"question-oracle": 1}, RetryDelayFunc: func(int, error, *asynq.Task) time.Duration { return 0 }, DelayedTaskCheckInterval: time.Millisecond * 10, TaskCheckInterval: time.Millisecond * 10, ShutdownTimeout: time.Second})
			result := make(chan error, 1)
			must(worker.Start(asynq.HandlerFunc(func(ctx context.Context, task *asynq.Task) error {
				n, _ := asynq.GetRetryCount(ctx)
				if n < retry {
					return fmt.Errorf("fixture retry setup")
				}
				result <- service.ProcessQuestionGeneration(ctx, task)
				return nil
			})))
			client := asynq.NewClient(redisOptions)
			_, e = client.Enqueue(asynq.NewTask(types.TypeQuestionGeneration, raw), asynq.Queue("question-oracle"), asynq.MaxRetry(3), asynq.Timeout(30*time.Minute))
			must(e)
			var err error
			select {
			case err = <-result:
			case <-time.After(time.Minute):
				panic("question oracle worker timeout")
			}
			worker.Shutdown()
			client.Close()
			redis.Close()
			message := ""
			if err != nil {
				message = err.Error()
			}
			stored := types.Knowledge{}
			must(db.Where("id='knowledge'").First(&stored).Error)
			cc := []types.Chunk{}
			must(db.Where("chunk_type='text'").Order("id").Find(&cc).Error)
			ids := map[string]string{}
			normalized := []any{}
			for _, c := range cc {
				var metadata any
				var m types.DocumentChunkMetadata
				if len(c.Metadata) > 0 {
					must(json.Unmarshal(c.Metadata, &metadata))
					if json.Unmarshal(c.Metadata, &m) == nil {
						for i, q := range m.GeneratedQuestions {
							label := fmt.Sprintf("question-%d", i+1)
							ids[types.GeneratedQuestionSourceID(c.ID, q.ID)] = types.GeneratedQuestionSourceID(c.ID, label)
							if list, ok := metadata.(map[string]any)["generated_questions"].([]any); ok {
								list[i].(map[string]any)["id"] = label
							}
						}
					}
				}
				normalized = append(normalized, map[string]any{"id": c.ID, "knowledge_id": c.KnowledgeID, "tenant_id": c.TenantID, "metadata": metadata, "content_revision": c.ContentRevision, "enabled": c.IsEnabled})
			}
			emb := []map[string]any{}
			must(db.Raw("SELECT source_id,source_type,chunk_id,knowledge_id,knowledge_base_id,content,dimension,is_enabled FROM lite_embeddings ORDER BY source_id").Scan(&emb).Error)
			for _, e := range emb {
				source := e["source_id"].(string)
				if label, ok := ids[source]; ok {
					e["source_id"] = label
				} else {
					e["source_id"] = "stale-" + e["chunk_id"].(string) + "-" + e["content"].(string)
				}
			}
			sort.Slice(emb, func(i, j int) bool { return emb[i]["source_id"].(string) < emb[j]["source_id"].(string) })
			spanRows := []types.KnowledgeProcessingSpan{}
			must(db.Where("name = ?", "postprocess.question").Or("name = ?", "postprocess.question.batch[2]").Or("name = ?", "postprocess.question.batch[0]").Order("created_at").Find(&spanRows).Error)
			var workerSpan any
			for _, span := range spanRows {
				if span.Name == "postprocess.question" && mode != "legacy" {
					continue
				}
				var input, output any
				if len(span.Input) > 0 {
					input = span.Input
				}
				if len(span.Output) > 0 {
					output = span.Output
				}
				workerSpan = map[string]any{"name": span.Name, "status": span.Status, "input": input, "output": output, "error_code": span.ErrorCode}
			}
			out = append(out, map[string]any{"name": name, "mode": mode, "id1": id1, "body1": body1, "body2": body2, "reply": reply, "prompt": prompt, "retry": retry, "payload": payload, "requests": requests, "span": workerSpan, "error": message, "chunks": normalized, "indexes": emb, "knowledge": map[string]any{"description": stored.Description, "summary_status": stored.SummaryStatus, "parse_status": stored.ParseStatus, "pending_subtasks_count": stored.PendingSubtasksCount, "error_message": stored.ErrorMessage, "storage_size": stored.StorageSize}})
			server.Close()
			pool.Release()
			must(sql.Close())
			must(os.RemoveAll(dir))
		}
	}
	must(json.NewEncoder(os.Stdout).Encode(out))
}
