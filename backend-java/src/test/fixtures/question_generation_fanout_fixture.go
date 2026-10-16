//go:build ignore

// Actual production Go postprocess fanout, SQL counter and task queue options.
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
	"github.com/alicebob/miniredis/v2"
	"github.com/hibiken/asynq"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
	gl "gorm.io/gorm/logger"
	"os"
	"path/filepath"
	"sort"
	"time"
)

func must(e error) {
	if e != nil {
		panic(e)
	}
}

type capture struct {
	client *asynq.Client
	rows   []any
}

func (c *capture) Enqueue(task *asynq.Task, opts ...asynq.Option) (*asynq.TaskInfo, error) {
	info, e := c.client.Enqueue(task, opts...)
	if e != nil {
		return nil, e
	}
	var payload any
	must(json.Unmarshal(task.Payload(), &payload))
	c.rows = append(c.rows, map[string]any{"type": task.Type(), "payload": payload, "queue": info.Queue, "max_retry": info.MaxRetry, "timeout": int64(info.Timeout / time.Millisecond)})
	return info, nil
}
func main() {
	logger.SetOutput(os.Stderr)
	out := []any{}
	for _, name := range []string{"mixed", "question-only", "summary-only", "count-zero", "count-over", "empty", "image-only", "image-prose", "image-nested-url", "ocr-only", "disabled-indexes", "disabled-questions", "no-enhancement", "windows-41", "disabled-chunk", "foreign-document", "foreign-kb"} {
		dir, e := os.MkdirTemp("", "weknora-go-question-fanout-")
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
		count := 3
		if name == "count-zero" {
			count = 0
		}
		if name == "count-over" {
			count = 15
		}
		q := name != "summary-only" && name != "disabled-questions" && name != "no-enhancement"
		summary := name != "question-only" && name != "no-enhancement"
		kb := &types.KnowledgeBase{ID: "kb", TenantID: 1, Name: "KB", Type: "document", EmbeddingModelID: "embedding", SummaryModelID: "summary", IndexingStrategy: types.IndexingStrategy{VectorEnabled: name != "disabled-indexes", KeywordEnabled: name != "disabled-indexes"}, QuestionGenerationConfig: &types.QuestionGenerationConfig{Enabled: q, QuestionCount: count}}
		must(br.CreateKnowledgeBase(context.Background(), kb))
		if name == "disabled-indexes" {
			kb.IndexingStrategy = types.IndexingStrategy{GraphEnabled: true}
			must(db.Exec("UPDATE knowledge_bases SET indexing_strategy=? WHERE id='kb'", []byte(`{"vector_enabled":false,"keyword_enabled":false,"graph_enabled":true,"wiki_enabled":false}`)).Error)
		}
		knowledge := &types.Knowledge{ID: "knowledge", TenantID: 1, KnowledgeBaseID: "kb", Type: "file", FileType: "txt", Title: "Title", ParseStatus: "processing", EnableStatus: "enabled", SummaryStatus: "none"}
		must(knowledge.SetProcessOverrides(&types.KnowledgeProcessOverrides{SummaryEnabled: &summary}))
		must(kr.CreateKnowledge(context.Background(), knowledge))
		total := 21
		if name == "windows-41" {
			total = 41
		}
		if name == "empty" {
			total = 0
		}
		if name == "ocr-only" {
			total = 1
		}
		if name == "image-only" || name == "image-prose" || name == "image-nested-url" {
			total = 2
		}
		chunks := []*types.Chunk{}
		for i := 0; i < total; i++ {
			content := fmt.Sprintf("Access section %d describes deployment and onboarding controls.", i)
			kind := types.ChunkTypeText
			if name == "image-only" {
				content = "![page](page.png)"
			}
			if name == "image-prose" {
				content = "![page](page.png) meaningful prose"
			}
			if name == "image-nested-url" {
				content = "![page](https://example.test/image(a).png \"A title (nested)\")"
			}
			if name == "ocr-only" {
				kind = types.ChunkTypeImageOCR
			}
			chunks = append(chunks, &types.Chunk{ID: fmt.Sprintf("chunk-%02d", i), TenantID: 1, KnowledgeBaseID: "kb", KnowledgeID: "knowledge", ChunkType: kind, Content: content, ChunkIndex: i, StartAt: i * 60, EndAt: (i + 1) * 60, IsEnabled: true})
		}
		if len(chunks) > 0 {
			must(cr.CreateChunks(context.Background(), chunks))
		}
		if name == "disabled-chunk" {
			must(db.Exec("UPDATE chunks SET is_enabled=false WHERE id='chunk-00'").Error)
		}
		if name == "foreign-document" {
			must(db.Exec("UPDATE chunks SET knowledge_id='foreign' WHERE id='chunk-00'").Error)
		}
		if name == "foreign-kb" {
			must(db.Exec("UPDATE chunks SET knowledge_base_id='foreign' WHERE id='chunk-00'").Error)
		}
		local := filesvc.NewLocalFileService(dir, "")
		base := svc.NewKnowledgeBaseService(br, kr, cr, nil, nil, nil, nil, nil, tr, local, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil)
		chunkService := svc.NewChunkService(cr, kr, br, nil, nil, nil, nil, nil)
		redis, e := miniredis.Run()
		must(e)
		client := asynq.NewClient(asynq.RedisClientOpt{Addr: redis.Addr()})
		enqueuer := &capture{client: client, rows: []any{}}
		tracker := svc.NewSpanTracker(repo.NewKnowledgeSpanRepository(db), db)
		ctx := context.WithValue(context.Background(), types.TenantIDContextKey, uint64(1))
		_, attempt, e := tracker.OpenAttempt(ctx, "knowledge", "native-fanout-oracle")
		must(e)
		service := svc.NewKnowledgePostProcessService(kr, base, chunkService, cr, enqueuer, nil, nil, tracker)
		payload := types.KnowledgePostProcessPayload{TenantID: 1, KnowledgeBaseID: "kb", KnowledgeID: "knowledge", Language: "en-US", Attempt: attempt}
		raw, e := json.Marshal(payload)
		must(e)
		err := service.Handle(ctx, asynq.NewTask(types.TypeKnowledgePostProcess, raw))
		message := ""
		if err != nil {
			message = err.Error()
		}
		stored := types.Knowledge{}
		must(db.Where("id='knowledge'").First(&stored).Error)
		spanRows := []types.KnowledgeProcessingSpan{}
		must(db.Where("name IN ?", []string{"postprocess", "postprocess.question"}).Find(&spanRows).Error)
		spans := []any{}
		sort.Slice(spanRows, func(i, j int) bool { return spanRows[i].Name < spanRows[j].Name })
		for _, span := range spanRows {
			spans = append(spans, map[string]any{"name": span.Name, "status": span.Status, "input": span.Input, "output": span.Output})
		}
		out = append(out, map[string]any{"name": name, "payload": payload, "summary": summary, "question": q, "count": count, "chunks": chunks, "tasks": enqueuer.rows, "error": message, "spans": spans, "knowledge": map[string]any{"parse_status": stored.ParseStatus, "summary_status": stored.SummaryStatus, "pending_subtasks_count": stored.PendingSubtasksCount}})
		client.Close()
		redis.Close()
		sql.Close()
		os.RemoveAll(dir)
	}
	must(json.NewEncoder(os.Stdout).Encode(out))
}
