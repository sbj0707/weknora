//go:build ignore

package main

import (
	"bytes"
	"context"
	"crypto/md5"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/Tencent/WeKnora/internal/application/repository"
	"github.com/Tencent/WeKnora/internal/application/service"
	werrors "github.com/Tencent/WeKnora/internal/errors"
	"github.com/Tencent/WeKnora/internal/logger"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"github.com/hibiken/asynq"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
	gl "gorm.io/gorm/logger"
	"mime/multipart"
	"net/http/httptest"
	"os"
	"reflect"
	"time"
)

type physical struct {
	interfaces.FileService
	saveCalls, deleteCalls int
	name, id               string
	fail                   bool
}

func (p *physical) SaveFile(_ context.Context, f *multipart.FileHeader, _ uint64, id string) (string, error) {
	p.saveCalls++
	p.name = f.Filename
	p.id = id
	if p.fail {
		return "", errors.New("storage unavailable")
	}
	return "stored/" + id, nil
}
func (p *physical) DeleteFile(context.Context, string) error { p.deleteCalls++; return nil }

type kbservice struct {
	interfaces.KnowledgeBaseService
	kb *types.KnowledgeBase
}

func (k *kbservice) GetKnowledgeBaseByID(context.Context, string) (*types.KnowledgeBase, error) {
	return k.kb, nil
}

type enqueue struct {
	rows        []interface{}
	fail        bool
	failSummary bool
}

func (e *enqueue) Enqueue(t *asynq.Task, opts ...asynq.Option) (*asynq.TaskInfo, error) {
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
	for _, opt := range opts {
		options = append(options, opt.String())
	}
	e.rows = append(e.rows, map[string]interface{}{"type": t.Type(), "payload": payload, "options": options})
	if e.fail || e.failSummary && len(e.rows) == 2 {
		return nil, errors.New("enqueue unavailable")
	}
	return &asynq.TaskInfo{ID: "task-1", Queue: "default"}, nil
}

type repoFail struct{ interfaces.KnowledgeRepository }

func (r *repoFail) CreateKnowledge(context.Context, *types.Knowledge) error {
	return errors.New("create unavailable")
}

type tagReadFail struct{ interfaces.KnowledgeRepository }

func (r *tagReadFail) GetKnowledgeTags(context.Context, []string) (map[string][]*types.KnowledgeTag, error) {
	return nil, errors.New("tag read unavailable")
}
func header(name, content string) *multipart.FileHeader {
	var body bytes.Buffer
	w := multipart.NewWriter(&body)
	part, _ := w.CreateFormFile("file", name)
	part.Write([]byte(content))
	w.Close()
	request := httptest.NewRequest("POST", "/", &body)
	request.Header.Set("Content-Type", w.FormDataContentType())
	request.ParseMultipartForm(50 * 1024 * 1024)
	return request.MultipartForm.File["file"][0]
}
func clean(value interface{}, id string) interface{} {
	data, _ := json.Marshal(value)
	var out interface{}
	json.Unmarshal(data, &out)
	var walk func(interface{}) interface{}
	walk = func(v interface{}) interface{} {
		switch node := v.(type) {
		case map[string]interface{}:
			for k, x := range node {
				if k == "created_at" || k == "updated_at" {
					node[k] = "<time>"
				} else {
					node[k] = walk(x)
				}
			}
		case []interface{}:
			for i, x := range node {
				node[i] = walk(x)
			}
		case string:
			if id != "" && node == id {
				return "<id>"
			}
			if id != "" && node == "stored/"+id {
				return "stored/<id>"
			}
		}
		return v
	}
	return walk(out)
}
func main() {
	logger.SetOutput(os.Stderr)
	if len(os.Args) > 1 && os.Args[1] == "default" {
		json.NewEncoder(os.Stdout).Encode(&types.Knowledge{CustomMetadata: types.JSON(`{}`)})
		return
	}
	inputs := []map[string]interface{}{}
	for _, name := range []string{"note.txt", "note.md", "note.csv", "note.json", "note.JSON", "image.png", "IMAGE.PNG", "audio.mp3", "AUDIO.MP3", "video.mp4", "book.epub", "note.xls", "note.xlsx", "slides.pptx", "mind.xmind", "data.mhtml", "file.invalid", "file", ".txt", "folder.txt/file"} {
		for _, configured := range []bool{false, true} {
			inputs = append(inputs, map[string]interface{}{"name": name, "content": "hello,图", "configured": configured})
		}
	}
	for _, extra := range []map[string]interface{}{{"custom": "docs/spec/design.md"}, {"custom": "docs\\spec\\design.txt"}, {"custom": "../file.txt"}, {"custom": "folder/"}, {"custom": "onerror=x.txt"}, {"custom": "safe.txt", "name": "original.pdf"}, {"metadata": map[string]string{"source": "api", "datasource_id": "ds", "external_id": "x"}, "channel": "gitlab"}, {"override": map[string]interface{}{}}, {"override": map[string]interface{}{"summary_enabled": false, "enable_multimodel": false, "question_generation_config": map[string]interface{}{"enabled": true, "question_count": 7}}}, {"override": map[string]interface{}{"chunking_config": map[string]interface{}{"chunk_size": 128, "enable_parent_child": true}, "parser_engine_overrides": map[string]string{"pdf_force_scanned": "true"}}}, {"quota": true}, {"save_fail": true}, {"create_fail": true}, {"enqueue_fail": true}, {"duplicate": true}, {"kb_type": "faq"}, {"tag_ids": []string{"missing"}}, {"tag_ids": []string{"other"}}, {"tag_ids": []string{"tag", "tag", ""}}} {
		row := map[string]interface{}{"name": "note.txt", "content": "hello,图"}
		for k, v := range extra {
			row[k] = v
		}
		inputs = append(inputs, row)
	}
	for _, state := range []string{"failed", "deleting", "cancelled", "processing", "completed"} {
		inputs = append(inputs, map[string]interface{}{"name": "note.txt", "content": "hello,图", "duplicate": true, "existing_status": state})
	}
	for _, extra := range []map[string]interface{}{
		{"duplicate": true, "existing_type": "md"}, {"duplicate": true, "existing_hash": "different", "existing_name": "note.txt", "existing_size": 9},
		{"duplicate": true, "existing_tenant": 2}, {"duplicate": true, "existing_deleted": true},
		{"duplicate": true, "channel": "gitlab", "metadata": map[string]string{"datasource_id": "ds", "external_id": "x"}, "existing_metadata": map[string]string{"datasource_id": "ds", "external_id": "x"}},
		{"duplicate": true, "channel": "gitlab", "metadata": map[string]string{"datasource_id": "ds", "external_id": "x"}, "existing_metadata": map[string]string{"datasource_id": "other", "external_id": "x"}},
		{"duplicate": true, "channel": "confluence", "metadata": map[string]string{"datasource_id": "ds", "external_id": "x"}, "existing_metadata": map[string]string{"datasource_id": "ds", "external_id": "other"}},
		{"duplicate": true, "channel": "web", "metadata": map[string]string{"datasource_id": "ds", "external_id": "x"}, "existing_metadata": map[string]string{"datasource_id": "other", "external_id": "x"}},
		{"duplicate": true, "channel": "gitlab", "metadata": map[string]string{"datasource_id": "ds"}, "existing_metadata": map[string]string{"datasource_id": "other", "external_id": "x"}},
		{"name": "note.csv", "enqueue_fail": true}, {"name": "note.csv", "summary_enqueue_fail": true},
		{"name": "IMAGE.PNG", "override": map[string]interface{}{}},
		{"name": "image.png", "configured": true, "override": map[string]interface{}{"vlm_config": map[string]interface{}{"enabled": false, "model_id": "override"}}},
		{"content": ""}, {"name": "invalid.json", "content": "{"}, {"quota_below": true}, {"tag_read_fail": true, "tag_ids": []string{"tag"}},
	} {
		row := map[string]interface{}{"name": "note.txt", "content": "hello,图"}
		for k, v := range extra {
			row[k] = v
		}
		inputs = append(inputs, row)
	}
	rows := []interface{}{}
	for _, input := range inputs {
		db, err := gorm.Open(sqlite.Open(":memory:"), &gorm.Config{Logger: gl.Default.LogMode(gl.Silent)})
		if err != nil {
			panic(err)
		}
		sql, _ := db.DB()
		sql.SetMaxOpenConns(1)
		if err := db.AutoMigrate(&types.Knowledge{}, &types.KnowledgeTag{}, &types.KnowledgeTagRelation{}); err != nil {
			panic(err)
		}
		kb := &types.KnowledgeBase{ID: "kb", TenantID: 1, Type: "document", EmbeddingModelID: "embedding", SummaryModelID: "summary", QuestionGenerationConfig: &types.QuestionGenerationConfig{Enabled: true, QuestionCount: 4}}
		if input["configured"] == true {
			kb.VLMConfig = types.VLMConfig{Enabled: true, ModelID: "vlm"}
			kb.ASRConfig = types.ASRConfig{Enabled: true, ModelID: "asr"}
		}
		if kind, ok := input["kb_type"].(string); ok {
			kb.Type = kind
		}
		repo := repository.NewKnowledgeRepository(db)
		var useRepo interfaces.KnowledgeRepository = repo
		if input["create_fail"] == true {
			useRepo = &repoFail{repo}
		}
		if input["tag_read_fail"] == true {
			useRepo = &tagReadFail{repo}
		}
		files := &physical{fail: input["save_fail"] == true}
		tasks := &enqueue{fail: input["enqueue_fail"] == true, failSummary: input["summary_enqueue_fail"] == true}
		tags := repository.NewKnowledgeTagRepository(db)
		db.Create(&types.KnowledgeTag{ID: "tag", TenantID: 1, KnowledgeBaseID: "kb", Name: "Tag"})
		db.Create(&types.KnowledgeTag{ID: "other", TenantID: 1, KnowledgeBaseID: "other-kb", Name: "Other"})
		svc, err := service.NewKnowledgeService(nil, useRepo, nil, &kbservice{kb: kb}, nil, nil, nil, nil, tags, nil, files, nil, nil, nil, tasks, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil)
		if err != nil {
			panic(err)
		}
		ctx := context.WithValue(context.Background(), types.TenantIDContextKey, uint64(1))
		tenant := &types.Tenant{ID: 1}
		if input["quota"] == true {
			tenant.StorageQuota = 1
			tenant.StorageUsed = 1
		}
		if input["quota_below"] == true {
			tenant.StorageQuota = 1
		}
		ctx = context.WithValue(ctx, types.TenantInfoContextKey, tenant)
		name := input["name"].(string)
		content := input["content"].(string)
		var metadata map[string]string
		var override *types.KnowledgeProcessOverrides
		var tagIDs []string
		if m, ok := input["metadata"]; ok {
			raw, _ := json.Marshal(m)
			json.Unmarshal(raw, &metadata)
		}
		if o, ok := input["override"]; ok {
			raw, _ := json.Marshal(o)
			json.Unmarshal(raw, &override)
		}
		if t, ok := input["tag_ids"]; ok {
			raw, _ := json.Marshal(t)
			json.Unmarshal(raw, &tagIDs)
		}
		custom, _ := input["custom"].(string)
		channel, _ := input["channel"].(string)
		if input["duplicate"] == true {
			var existing types.Knowledge
			existing = types.Knowledge{ID: "existing", TenantID: 1, KnowledgeBaseID: "kb", Type: "file", FileName: "older.txt", FileType: "TXT", FileHash: func() string { hash := md5.Sum([]byte(content)); return hex.EncodeToString(hash[:]) }(), ParseStatus: "pending", Source: "", CreatedAt: time.Unix(1, 0), UpdatedAt: time.Unix(1, 0)}
			if state, ok := input["existing_status"].(string); ok {
				existing.ParseStatus = state
			}
			if typ, ok := input["existing_type"].(string); ok {
				existing.FileType = typ
			}
			if hash, ok := input["existing_hash"].(string); ok {
				existing.FileHash = hash
			}
			if name, ok := input["existing_name"].(string); ok {
				existing.FileName = name
			}
			if size, ok := input["existing_size"].(int); ok {
				existing.FileSize = int64(size)
			}
			if tenant, ok := input["existing_tenant"].(int); ok {
				existing.TenantID = uint64(tenant)
			}
			if metadata, ok := input["existing_metadata"]; ok {
				raw, _ := json.Marshal(metadata)
				existing.Metadata = types.JSON(raw)
			}
			if input["existing_deleted"] == true {
				existing.DeletedAt = gorm.DeletedAt{Time: time.Unix(1, 0), Valid: true}
			}
			repo.CreateKnowledge(ctx, &existing)
		}
		knowledge, err := svc.CreateKnowledgeFromFile(ctx, "kb", header(name, content), metadata, nil, custom, tagIDs, channel, override)
		message := ""
		appcode := 0
		status := 0
		if err != nil {
			message = err.Error()
			if e, ok := werrors.IsAppError(err); ok {
				message = e.Message
				appcode = int(e.Code)
				status = e.HTTPCode
			}
		}
		id := files.id
		if knowledge != nil {
			id = knowledge.ID
		}
		var count int64
		db.Model(&types.Knowledge{}).Count(&count)
		persisted := []types.Knowledge{}
		db.Order("created_at, id").Find(&persisted)
		rows = append(rows, map[string]interface{}{"input": input, "knowledge": clean(knowledge, id), "error": message, "appcode": appcode, "status": status, "saved": files.saveCalls, "save_name": files.name, "deleted": files.deleteCalls, "tasks": clean(tasks.rows, id), "count": count, "persisted": clean(persisted, id)})
		sql.Close()
	}
	if err := json.NewEncoder(os.Stdout).Encode(rows); err != nil {
		panic(fmt.Sprint(err))
	}
}
