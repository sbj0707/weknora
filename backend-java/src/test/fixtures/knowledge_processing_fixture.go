//go:build ignore

package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/Tencent/WeKnora/internal/application/repository"
	"github.com/Tencent/WeKnora/internal/application/service"
	"github.com/Tencent/WeKnora/internal/logger"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"github.com/hibiken/asynq"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
	gl "gorm.io/gorm/logger"
	"io"
	"os"
	"reflect"
	"sort"
	"strings"
)

type kbService struct {
	interfaces.KnowledgeBaseService
	kb *types.KnowledgeBase
}

func (k *kbService) GetKnowledgeBaseByID(context.Context, string) (*types.KnowledgeBase, error) {
	return k.kb, nil
}

func (k *kbService) GetKnowledgeBaseByIDOnly(context.Context, string) (*types.KnowledgeBase, error) {
	return k.kb, nil
}

type tenantRepo struct{ interfaces.TenantRepository }

func (t *tenantRepo) GetTenantByID(context.Context, uint64) (*types.Tenant, error) {
	return &types.Tenant{ID: 1}, nil
}
func (t *tenantRepo) AdjustStorageUsed(context.Context, uint64, int64) error { return nil }

type tenantService struct{ interfaces.TenantService }

func (t *tenantService) GetWeKnoraCloudCredentials(context.Context) *types.WeKnoraCloudCredentials {
	return nil
}

type files struct {
	interfaces.FileService
	content string
	calls   int
	fail    bool
}

func (f *files) GetFile(context.Context, string) (io.ReadCloser, error) {
	f.calls++
	if f.fail {
		return nil, errors.New("file unavailable")
	}
	return io.NopCloser(strings.NewReader(f.content)), nil
}

type graph struct {
	interfaces.RetrieveGraphRepository
}

func (g *graph) DelGraph(context.Context, []types.NameSpace) error { return nil }

type registry struct {
	interfaces.RetrieveEngineRegistry
}

func (r *registry) GetRetrieveEngineService(types.RetrieverEngineType) (interfaces.RetrieveEngineService, error) {
	return nil, errors.New("no configured engine")
}

type tasks struct {
	rows  []map[string]interface{}
	saved []*asynq.Task
}

func (e *tasks) Enqueue(t *asynq.Task, opts ...asynq.Option) (*asynq.TaskInfo, error) {
	var payload interface{}
	json.Unmarshal(t.Payload(), &payload)
	options := []interface{}{}
	embedded := reflect.ValueOf(t).Elem().FieldByName("opts")
	for i := 0; i < embedded.Len(); i++ {
		opt := embedded.Index(i).Elem()
		var value interface{}
		if opt.Kind() == reflect.String {
			value = opt.String()
		} else {
			value = opt.Int()
		}
		options = append(options, map[string]interface{}{"type": opt.Type().Name(), "value": value})
	}
	e.rows = append(e.rows, map[string]interface{}{"type": t.Type(), "payload": payload, "options": options})
	e.saved = append(e.saved, t)
	return &asynq.TaskInfo{ID: "task", Queue: "postprocess"}, nil
}
func snapshot(db *gorm.DB, k *types.Knowledge) map[string]interface{} {
	var stored types.Knowledge
	db.Where("id=?", k.ID).First(&stored)
	chunks := []types.Chunk{}
	db.Where("knowledge_id=?", k.ID).Find(&chunks)
	sort.Slice(chunks, func(i, j int) bool {
		if chunks[i].ChunkType == chunks[j].ChunkType {
			return chunks[i].ChunkIndex < chunks[j].ChunkIndex
		}
		return chunks[i].ChunkType < chunks[j].ChunkType
	})
	ids := map[string]string{"": ""}
	for _, c := range chunks {
		ids[c.ID] = fmt.Sprintf("%s-%d", c.ChunkType, c.ChunkIndex)
	}
	out := []interface{}{}
	for _, c := range chunks {
		out = append(out, map[string]interface{}{"id": ids[c.ID], "content": c.Content, "context_header": c.ContextHeader, "index": c.ChunkIndex, "start": c.StartAt, "end": c.EndAt, "type": c.ChunkType, "parent": ids[c.ParentChunkID], "previous": ids[c.PreChunkID], "next": ids[c.NextChunkID], "enabled": c.IsEnabled, "status": c.Status, "source_locators": c.SourceLocators})
	}
	return map[string]interface{}{"parse_status": stored.ParseStatus, "enable_status": stored.EnableStatus, "summary_status": stored.SummaryStatus, "error": stored.ErrorMessage, "storage_size": stored.StorageSize, "pending": stored.PendingSubtasksCount, "chunks": out}
}
func main() {
	logger.SetOutput(os.Stderr)
	inputs := []map[string]interface{}{}
	for _, text := range []string{"hello world", "标题\n\n你好世界。下一段！", "first\r\nsecond\rthird", "", "   \n\t", "# Heading\n\nFirst para.\n\n## Sub\n\nMore body.", strings.Repeat("甲乙丙丁，hello world。\n", 80)} {
		for _, parent := range []bool{false, true} {
			inputs = append(inputs, map[string]interface{}{"content": text, "file_type": "txt", "parent": parent, "strategy": "legacy"})
		}
	}
	for _, typ := range []string{"md", "csv", "json"} {
		content := "title\n\ncontent"
		if typ == "csv" {
			content = "name,age\nAlice,3\nBob,4\n"
		}
		if typ == "json" {
			content = "{\"title\":\"hello\",\"array\":[1,2]}"
		}
		inputs = append(inputs, map[string]interface{}{"content": content, "file_type": typ, "strategy": "heading", "parent": false})
	}
	for _, state := range []string{"completed", "cancelled", "deleting", "failed"} {
		inputs = append(inputs, map[string]interface{}{"content": "hello world", "file_type": "txt", "state": state})
	}
	inputs = append(inputs, map[string]interface{}{"content": "hello world", "file_type": "txt", "stale_source": true}, map[string]interface{}{"content": "hello world", "file_type": "txt", "read_fail": true}, map[string]interface{}{"content": "{", "file_type": "json"})
	rows := []interface{}{}
	for _, input := range inputs {
		db, err := gorm.Open(sqlite.Open(":memory:"), &gorm.Config{Logger: gl.Default.LogMode(gl.Silent)})
		if err != nil {
			panic(err)
		}
		sql, _ := db.DB()
		sql.SetMaxOpenConns(1)
		db.AutoMigrate(&types.Knowledge{}, &types.Chunk{})
		repo := repository.NewKnowledgeRepository(db)
		chunkRepo := repository.NewChunkRepository(db)
		kb := &types.KnowledgeBase{ID: "kb", TenantID: 1, Type: "document", IndexingStrategy: types.IndexingStrategy{GraphEnabled: true}, ChunkingConfig: types.ChunkingConfig{ChunkSize: 64, ChunkOverlap: 10, Separators: []string{"\n\n", "\n", "。"}, EnableParentChild: input["parent"] == true, ParentChunkSize: 128, ChildChunkSize: 32}}
		if strategy, ok := input["strategy"].(string); ok {
			kb.ChunkingConfig.Strategy = strategy
		}
		fileType := input["file_type"].(string)
		k := &types.Knowledge{ID: "knowledge", TenantID: 1, KnowledgeBaseID: "kb", Type: "file", Title: "  File title  ", FileName: "document." + fileType, FileType: fileType, FilePath: "stored", FileHash: "hash", ParseStatus: "pending", EnableStatus: "disabled"}
		summaryFalse := false
		k.SetProcessOverrides(&types.KnowledgeProcessOverrides{SummaryEnabled: &summaryFalse})
		if state, ok := input["state"].(string); ok {
			k.ParseStatus = state
			k.ErrorMessage = "old error"
		}
		repo.CreateKnowledge(context.Background(), k)
		file := &files{content: input["content"].(string), fail: input["read_fail"] == true}
		queued := &tasks{}
		svc, _ := service.NewKnowledgeService(nil, repo, nil, &kbService{kb: kb}, &tenantRepo{}, &tenantService{}, nil, chunkRepo, nil, nil, file, nil, nil, nil, queued, nil, &graph{}, &registry{}, nil, nil, nil, nil, nil, nil, nil, nil, nil)
		payload := types.DocumentProcessPayload{TenantID: 1, KnowledgeID: k.ID, KnowledgeBaseID: "kb", FilePath: "stored", FileName: k.FileName, FileType: fileType, Language: "zh-CN"}
		if input["stale_source"] == true {
			payload.FilePath = "old-source"
		}
		raw, _ := json.Marshal(payload)
		err = svc.ProcessDocument(context.Background(), asynq.NewTask(types.TypeDocumentProcess, raw))
		message := ""
		if err != nil {
			message = err.Error()
		}
		before := snapshot(db, k)
		post := service.NewKnowledgePostProcessService(repo, &kbService{kb: kb}, nil, chunkRepo, queued, nil, nil, nil)
		for _, task := range append([]*asynq.Task{}, queued.saved...) {
			if task.Type() == types.TypeKnowledgePostProcess {
				if err := post.Handle(context.Background(), task); err != nil {
					message += " post: " + err.Error()
				}
			}
		}
		rows = append(rows, map[string]interface{}{"input": input, "before_post": before, "after_post": snapshot(db, k), "error": message, "read_calls": file.calls, "tasks": queued.rows})
		sql.Close()
	}
	var out bytes.Buffer
	json.NewEncoder(&out).Encode(rows)
	os.Stdout.Write(out.Bytes())
}
