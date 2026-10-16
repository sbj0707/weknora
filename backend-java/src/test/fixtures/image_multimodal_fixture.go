//go:build ignore

// Actual image:multimodal service/provider HTTP/SQL/SQLite vec+FTS/Asynq oracle.
package main

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	repo "github.com/Tencent/WeKnora/internal/application/repository"
	pgindex "github.com/Tencent/WeKnora/internal/application/repository/retriever/postgres"
	sqliteindex "github.com/Tencent/WeKnora/internal/application/repository/retriever/sqlite"
	svc "github.com/Tencent/WeKnora/internal/application/service"
	filesvc "github.com/Tencent/WeKnora/internal/application/service/file"
	"github.com/Tencent/WeKnora/internal/application/service/retriever"
	"github.com/Tencent/WeKnora/internal/logger"
	"github.com/Tencent/WeKnora/internal/models/embedding"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	utils "github.com/Tencent/WeKnora/internal/utils"
	"github.com/alicebob/miniredis/v2"
	vec "github.com/asg017/sqlite-vec-go-bindings/cgo"
	"github.com/hibiken/asynq"
	"github.com/panjf2000/ants/v2"
	"github.com/redis/go-redis/v9"
	"gorm.io/driver/postgres"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
	gl "gorm.io/gorm/logger"
	"math"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

func must(e error) {
	if e != nil {
		panic(e)
	}
}

type queue struct {
	client *asynq.Client
	tasks  []any
	fail   bool
}

func (q *queue) Enqueue(t *asynq.Task, o ...asynq.Option) (*asynq.TaskInfo, error) {
	if q.fail {
		return nil, errors.New("controlled queue unavailable")
	}
	i, e := q.client.Enqueue(t, o...)
	if e != nil {
		return nil, e
	}
	var p any
	must(json.Unmarshal(t.Payload(), &p))
	q.tasks = append(q.tasks, map[string]any{"type": t.Type(), "payload": p, "queue": i.Queue, "max_retry": i.MaxRetry, "timeout": i.Timeout / time.Millisecond})
	return i, nil
}
func main() {
	logger.SetOutput(os.Stderr)
	time.Local = time.UTC
	pgDSN := os.Getenv("WEKNORA_IMAGE_WORKER_PG_DSN")
	pg := pgDSN != ""
	if pg {
		if !strings.Contains(pgDSN, "@127.0.0.1:") || !strings.Contains(pgDSN, "/weknora_manual_parity?") {
			panic("owned loopback image PG required")
		}
		must(os.Setenv("RETRIEVE_DRIVER", "postgres"))
	} else {
		must(os.Setenv("RETRIEVE_DRIVER", "sqlite"))
	}
	must(os.Setenv("SSRF_WHITELIST", "127.0.0.1"))
	utils.ResetSSRFWhitelistForTest()
	vec.Auto()
	pool, e := ants.NewPool(1)
	must(e)
	defer pool.Release()
	names := []string{"caption-ocr", "caption-only", "ocr-only", "both-disabled", "attrs-block", "attrs-negative", "attrs-chart", "attrs-prose", "attrs-invalid", "attrs-only", "attrs-no-caption", "attrs-incomplete-strict", "custom-language", "scanned-pdf", "ocr-code", "ocr-empty", "ocr-html", "caption-error", "ocr-error", "both-error", "truncated", "missing-image", "missing-vlm", "foreign-vlm", "wrong-model-type", "vlm-disabled", "disabled-indexes", "missing-embedding", "embedding-error", "post-error", "missing-document", "missing-base", "cancelled", "deleting", "foreign-document", "foreign-base", "wrong-base", "foreign-parent", "wrong-parent-document", "missing-parent", "stale-attempt", "cancel-during-chat", "edit-during-chat", "owner-during-chat", "kb-during-chat", "parent-during-chat", "fresh-attempt-during-chat", "payload-array", "payload-owner-string", "payload-trailing", "final-missing-image", "pending-two", "payload-actions-string", "payload-condition-number", "payload-condition-slice", "payload-unknown-policy-number", "payload-locators-object", "payload-locator-type-number", "payload-locator-page-float", "payload-locator-bbox-string", "payload-locator-bbox-overflow", "locators-valid", "locators-empty", "locators-null-entry", "foreign-image-reference", "foreign-storage-reference"}
	out := []any{}
	for _, name := range names {
		dir, e := os.MkdirTemp("", "weknora-go-image-worker-")
		must(e)
		var dialect gorm.Dialector = sqlite.Open(filepath.Join(dir, "case.db"))
		if pg {
			dialect = postgres.Open(pgDSN)
		}
		db, e := gorm.Open(dialect, &gorm.Config{Logger: gl.Default.LogMode(gl.Silent)})
		must(e)
		sql, e := db.DB()
		must(e)
		sql.SetMaxOpenConns(1)
		if pg {
			var name string
			must(db.Raw("SELECT current_database()").Scan(&name).Error)
			if name != "weknora_manual_parity" {
				panic("owned image PG required")
			}
			must(db.Exec("TRUNCATE tenants,knowledge_bases,knowledges,chunks,models,embeddings,knowledge_processing_spans RESTART IDENTITY CASCADE").Error)
			must(db.Exec("ALTER SEQUENCE chunks_seq_id_seq RESTART WITH 100000000").Error)
		} else {
			for _, m := range []string{"000000_init", "000002_knowledge_folder_path", "000003_knowledge_base_auto_tag_config", "000004_memory", "000007_system_admin_and_settings", "000008_processing_spans_and_pending_subtasks", "000010_knowledge_multi_tags", "000011_principal_model", "000021_knowledge_profiles", "000031_model_catalog_config", "000033_chunk_source_locators"} {
				b, e := os.ReadFile("migrations/sqlite/" + m + ".up.sql")
				must(e)
				must(db.Exec(string(b)).Error)
			}
		}
		tr, kr, cr, br := repo.NewTenantRepository(db), repo.NewKnowledgeRepository(db), repo.NewChunkRepository(db), repo.NewKnowledgeBaseRepository(db)
		must(tr.CreateTenant(context.Background(), &types.Tenant{ID: 1, Name: "Owner", Business: "isolated", Status: "active"}))
		must(tr.CreateTenant(context.Background(), &types.Tenant{ID: 2, Name: "Foreign", Business: "isolated", Status: "active"}))
		kb := &types.KnowledgeBase{ID: "kb", TenantID: 1, Name: "KB", Type: "document", EmbeddingModelID: "embedding", IndexingStrategy: types.IndexingStrategy{VectorEnabled: true, KeywordEnabled: true}, VLMConfig: types.VLMConfig{Enabled: true, ModelID: "vlm"}}
		if name == "disabled-indexes" {
			kb.IndexingStrategy = types.IndexingStrategy{GraphEnabled: true}
		}
		if name == "vlm-disabled" {
			kb.VLMConfig.Enabled = false
		}
		if name == "custom-language" {
			kb.VLMConfig.DescriptionLanguage = "French"
			kb.VLMConfig.CustomInstructions = "Focus on network access."
		}
		must(br.CreateKnowledgeBase(context.Background(), kb))
		row := &types.Knowledge{ID: "doc", TenantID: 1, KnowledgeBaseID: "kb", Type: "manual", Title: "Manual title", FileName: "Manual title.md", FileType: "manual", EmbeddingModelID: "embedding", ParseStatus: "processing", EnableStatus: "enabled"}
		must(row.SetManualMetadata(types.NewManualKnowledgeMetadata("Image body", "publish", 1)))
		must(kr.CreateKnowledge(context.Background(), row))
		must(cr.CreateChunks(context.Background(), []*types.Chunk{{ID: "parent", TenantID: 1, KnowledgeBaseID: "kb", KnowledgeID: "doc", Content: "Image body", ChunkType: "text", IsEnabled: true}}))
		tracker := svc.NewSpanTracker(repo.NewKnowledgeSpanRepository(db), db)
		_, a, e := tracker.OpenAttempt(context.Background(), "doc", "")
		must(e)
		tracker.BeginStage(context.Background(), "doc", a, types.StageMultimodal, types.JSONMap{"image_count": 1})
		calls := []any{}
		chatCalls := 0
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			var req map[string]any
			must(json.NewDecoder(r.Body).Decode(&req))
			calls = append(calls, map[string]any{"path": r.URL.Path, "body": req})
			w.Header().Set("Content-Type", "application/json")
			if strings.HasSuffix(r.URL.Path, "/embeddings") {
				if name == "embedding-error" {
					w.WriteHeader(500)
					fmt.Fprint(w, `{"error":{"message":"controlled embedding failure"}}`)
					return
				}
				data := []any{}
				for i, t := range req["input"].([]any) {
					s := fmt.Sprint(t)
					sum := 0
					for _, b := range []byte(s) {
						sum += int(b)
					}
					data = append(data, map[string]any{"index": i, "embedding": []float32{float32(len(s)), float32(sum % 251)}})
				}
				must(json.NewEncoder(w).Encode(map[string]any{"data": data}))
				return
			}
			chatCalls++
			if chatCalls == 1 {
				switch name {
				case "cancel-during-chat":
					must(db.Exec("UPDATE knowledges SET parse_status='cancelled' WHERE id='doc'").Error)
				case "edit-during-chat":
					must(db.Exec("UPDATE knowledges SET metadata='{}',title='New title' WHERE id='doc'").Error)
				case "owner-during-chat":
					must(db.Exec("UPDATE knowledges SET tenant_id=2 WHERE id='doc'").Error)
				case "kb-during-chat":
					must(db.Exec("UPDATE knowledge_bases SET tenant_id=2 WHERE id='kb'").Error)
				case "parent-during-chat":
					must(db.Exec("UPDATE chunks SET knowledge_id='other' WHERE id='parent'").Error)
				case "fresh-attempt-during-chat":
					_, _, e := tracker.OpenAttempt(context.Background(), "doc", "")
					must(e)
				}
			}
			prompt := req["messages"].([]any)[0].(map[string]any)["content"].([]any)[0].(map[string]any)["text"].(string)
			ocr := strings.Contains(prompt, "OCR ")
			if name == "both-error" || (name == "caption-error" && !ocr) || (name == "ocr-error" && ocr) {
				w.WriteHeader(500)
				fmt.Fprint(w, `{"error":{"message":"controlled VLM failure"}}`)
				return
			}
			text := " A concise image description. "
			if strings.HasPrefix(prompt, "Observe") {
				text = "contain.text: block\ncontain.data_visual: false\nDESCRIPTION: A concise image description."
				switch name {
				case "attrs-negative":
					text = "contain.text: sparse\ncontain.data_visual: false\nDESCRIPTION: A logo."
				case "attrs-chart":
					text = "contain.text: sparse\ncontain.data_visual: true\nDESCRIPTION: A chart."
				case "attrs-prose":
					text = "A prose answer without attributes."
				case "attrs-invalid":
					text = "contain.text: maybe\ncontain.data_visual: no\nDESCRIPTION: Uncertain."
				case "attrs-only":
					text = "contain.text: block\ncontain.data_visual: false"
				case "attrs-incomplete-strict":
					text = "contain.text: sparse\nDESCRIPTION: Incomplete."
				}
			}
			if ocr {
				text = "# Extracted access\n\nNetwork entry is controlled by account permissions."
				switch name {
				case "ocr-empty":
					text = "No text content."
				case "ocr-code":
					text = "```markdown\n# Exact OCR\n\nActual body text here.\n```"
				case "ocr-html":
					text = "<h1>Access policy</h1><p>Actual body text and role permissions.</p>"
				}
			}
			reason := "stop"
			if name == "truncated" {
				text = ""
				reason = "length"
			}
			must(json.NewEncoder(w).Encode(map[string]any{"choices": []any{map[string]any{"message": map[string]any{"role": "assistant", "content": text}, "finish_reason": reason}}}))
		}))
		for _, m := range []struct{ id, kind, model string }{{"vlm", "VLLM", "vision-model"}, {"embedding", "Embedding", "image-embedding"}} {
			params, e := json.Marshal(map[string]any{"provider": "generic", "base_url": server.URL + "/v1", "embedding_parameters": map[string]any{"dimension": 2}})
			must(e)
			must(db.Exec("INSERT INTO models(id,tenant_id,name,type,source,parameters,status) VALUES(?,1,?,?,'remote',?,'active')", m.id, m.model, m.kind, params).Error)
		}
		var index interfaces.RetrieveEngineRepository = sqliteindex.NewSQLiteRetrieveEngineRepository(db)
		engineType := types.SQLiteRetrieverEngineType
		if pg {
			index = pgindex.NewPostgresRetrieveEngineRepository(db)
			engineType = types.PostgresRetrieverEngineType
		}
		registry := retriever.NewRetrieveEngineRegistry(nil, nil)
		must(registry.Register(retriever.NewKVHybridRetrieveEngine(index, engineType)))
		models := svc.NewModelService(repo.NewModelRepository(db), br, nil, nil, embedding.NewBatchEmbedder(pool), nil)
		red, e := miniredis.Run()
		must(e)
		client := asynq.NewClient(asynq.RedisClientOpt{Addr: red.Addr()})
		rc := redis.NewClient(&redis.Options{Addr: red.Addr()})
		queued := &queue{client: client, tasks: []any{}, fail: name == "post-error"}
		must(rc.Set(context.Background(), "multimodal:pending:doc", 1, time.Hour).Err())
		if name == "pending-two" {
			must(rc.Set(context.Background(), "multimodal:pending:doc", 2, time.Hour).Err())
		}
		local := filesvc.NewLocalFileService(dir, "")
		image := append([]byte{137, 'P', 'N', 'G', 13, 10, 26, 10}, []byte(strings.Repeat("v", 592))...)
		imageURL, e := local.SaveBytes(context.Background(), image, 1, "image.png", false)
		must(e)
		if name == "foreign-image-reference" || name == "foreign-storage-reference" {
			imageURL, e = local.SaveBytes(context.Background(), []byte(strings.Repeat("FOREIGN", 100)), 2, "foreign.png", false)
			must(e)
			if name == "foreign-storage-reference" {
				imageURL = "storage://owned-backend/" + imageURL
			}
		}
		base := svc.NewKnowledgeBaseService(br, kr, cr, nil, nil, nil, nil, nil, tr, local, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil)
		chunks := svc.NewChunkService(cr, kr, br, models, registry, nil, queued, tracker)
		handler := svc.NewImageMultimodalService(chunks, models, base, kr, tr, registry, nil, nil, queued, rc, local, nil, nil, tracker)
		payload := types.ImageMultimodalPayload{TenantID: 1, KnowledgeID: "doc", KnowledgeBaseID: "kb", ChunkID: "parent", ImageURL: imageURL, EnableOCR: true, EnableCaption: true, Language: "en", Attempt: a, ImageAttrsEnabled: strings.HasPrefix(name, "attrs-"), ImageActions: types.DefaultImageActions()}
		switch name {
		case "caption-only":
			payload.EnableOCR = false
		case "ocr-only":
			payload.EnableCaption = false
		case "both-disabled":
			payload.EnableOCR = false
			payload.EnableCaption = false
		case "attrs-no-caption":
			payload.EnableCaption = false
		case "attrs-incomplete-strict":
			payload.ImageActions.OCR.OnUnobserved = false
		case "locators-valid":
			payload.SourceLocators = types.SourceLocators{{Type: "pdf", Page: 2, BBox: []float64{0.1, 0.2, 0.5, 0.6}, Quote: "Exact image evidence"}}
		case "locators-empty":
			payload.SourceLocators = types.SourceLocators{}
		case "locators-null-entry":
			payload.SourceLocators = types.SourceLocators{{}}
		case "scanned-pdf":
			payload.ImageSourceType = "scanned_pdf"
		case "missing-image", "final-missing-image":
			payload.ImageURL = "local://1/missing.png"
		case "missing-vlm":
			must(db.Exec("DELETE FROM models WHERE id='vlm'").Error)
		case "foreign-vlm":
			must(db.Exec("UPDATE models SET tenant_id=2 WHERE id='vlm'").Error)
		case "wrong-model-type":
			must(db.Exec("UPDATE models SET type='Embedding' WHERE id='vlm'").Error)
		case "missing-embedding":
			must(db.Exec("DELETE FROM models WHERE id='embedding'").Error)
		case "missing-document":
			must(db.Exec("DELETE FROM knowledges").Error)
		case "missing-base":
			must(db.Exec("DELETE FROM knowledge_bases").Error)
		case "cancelled", "deleting":
			must(db.Exec("UPDATE knowledges SET parse_status=? WHERE id='doc'", name).Error)
		case "foreign-document":
			must(db.Exec("UPDATE knowledges SET tenant_id=2").Error)
		case "foreign-base":
			must(db.Exec("UPDATE knowledge_bases SET tenant_id=2").Error)
		case "wrong-base":
			payload.KnowledgeBaseID = "other"
		case "foreign-parent":
			must(db.Exec("UPDATE chunks SET tenant_id=2 WHERE id='parent'").Error)
		case "wrong-parent-document":
			must(db.Exec("UPDATE chunks SET knowledge_id='other' WHERE id='parent'").Error)
		case "missing-parent":
			must(db.Exec("DELETE FROM chunks").Error)
		case "stale-attempt":
			_, _, e := tracker.OpenAttempt(context.Background(), "doc", "")
			must(e)
		}
		raw, e := json.Marshal(payload)
		must(e)
		switch name {
		case "payload-array":
			raw = []byte(`[]`)
		case "payload-owner-string":
			raw = []byte(`{"tenant_id":"1"}`)
		case "payload-trailing":
			raw = append(raw, []byte(`{}`)...)
		case "payload-actions-string":
			raw = []byte(`{"tenant_id":1,"image_actions":"wrong"}`)
		case "payload-condition-number":
			raw = []byte(`{"tenant_id":1,"image_actions":{"ocr":{"on":[{"is":42}]}}}`)
		case "payload-condition-slice":
			raw = []byte(`{"tenant_id":1,"image_actions":{"ocr":{"on":"wrong"}}}`)
		case "payload-unknown-policy-number":
			raw = []byte(`{"tenant_id":1,"image_actions":{"ocr":{"on_unobserved":42}}}`)
		case "payload-locators-object":
			raw = []byte(`{"tenant_id":1,"source_locators":{}}`)
		case "payload-locator-type-number":
			raw = []byte(`{"tenant_id":1,"source_locators":[{"type":42}]}`)
		case "payload-locator-page-float":
			raw = []byte(`{"tenant_id":1,"source_locators":[{"page":1.5}]}`)
		case "payload-locator-bbox-string":
			raw = []byte(`{"tenant_id":1,"source_locators":[{"bbox":["wrong"]}]}`)
		case "payload-locator-bbox-overflow":
			raw = []byte(`{"tenant_id":1,"source_locators":[{"bbox":[1e999]}]}`)
		case "locators-null-entry":
			var record map[string]any
			must(json.Unmarshal(raw, &record))
			record["source_locators"] = []any{nil}
			raw, e = json.Marshal(record)
			must(e)
		}
		ctx := context.Background()
		if name == "final-missing-image" {
			ctx = types.WithTaskRetryMetadata(ctx, 3, 3)
		}
		err := handler.Handle(ctx, asynq.NewTask(types.TypeImageMultimodal, raw))
		message := ""
		if err != nil {
			message = err.Error()
		}
		var rows []types.Chunk
		must(db.Where("chunk_type <> 'text'").Order("seq_id").Find(&rows).Error)
		ids := map[string]string{"parent": "parent"}
		for _, c := range rows {
			ids[c.ID] = c.ChunkType
		}
		children := []any{}
		for _, c := range rows {
			var info any
			must(json.Unmarshal([]byte(c.ImageInfo), &info))
			children = append(children, map[string]any{"type": c.ChunkType, "content": c.Content, "owner": c.TenantID, "knowledge": c.KnowledgeID, "kb": c.KnowledgeBaseID, "parent": c.ParentChunkID, "status": c.Status, "enabled": c.IsEnabled, "flags": c.Flags, "seq_id": c.SeqID, "image_info": info, "locators": c.SourceLocators})
		}
		var indexes []map[string]any
		indexTable := "lite_embeddings"
		if pg {
			indexTable = "embeddings"
		}
		must(db.Table(indexTable).Select("source_id,chunk_id,knowledge_id,knowledge_base_id,content,is_enabled").Find(&indexes).Error)
		for _, x := range indexes {
			x["source_id"] = ids[fmt.Sprint(x["source_id"])]
			x["chunk_id"] = ids[fmt.Sprint(x["chunk_id"])]
		}
		sort.Slice(indexes, func(i, j int) bool { return fmt.Sprint(indexes[i]["source_id"]) < fmt.Sprint(indexes[j]["source_id"]) })
		vectors := []any{}
		var exists int64
		if !pg {
			must(db.Raw("SELECT COUNT(*) FROM sqlite_master WHERE name='vec_embeddings_2'").Scan(&exists).Error)
		}
		if exists > 0 {
			var rs []struct {
				SourceID  string
				Embedding []byte
			}
			must(db.Raw("SELECT e.source_id,v.embedding FROM lite_embeddings e JOIN vec_embeddings_2 v ON v.rowid=e.id").Scan(&rs).Error)
			sort.Slice(rs, func(i, j int) bool { return ids[rs[i].SourceID] < ids[rs[j].SourceID] })
			for _, r := range rs {
				bits := []uint32{}
				for i := 0; i+4 <= len(r.Embedding); i += 4 {
					bits = append(bits, binary.LittleEndian.Uint32(r.Embedding[i:i+4]))
				}
				vectors = append(vectors, map[string]any{"source_id": ids[r.SourceID], "bits": bits})
			}
		}
		if pg {
			var rows []struct {
				SourceID  string
				Embedding string
			}
			must(db.Raw("SELECT source_id,embedding::text FROM embeddings WHERE dimension=2").Scan(&rows).Error)
			sort.Slice(rows, func(i, j int) bool { return ids[rows[i].SourceID] < ids[rows[j].SourceID] })
			for _, r := range rows {
				var values []float32
				must(json.Unmarshal([]byte(r.Embedding), &values))
				bits := []uint32{}
				for _, v := range values {
					bits = append(bits, math.Float32bits(v))
				}
				vectors = append(vectors, map[string]any{"source_id": ids[r.SourceID], "bits": bits})
			}
		}
		var spans []map[string]any
		must(db.Table("knowledge_processing_spans").Select("attempt,name,kind,status,CAST(input AS TEXT) AS input,CAST(output AS TEXT) AS output,error_code,error_message").Order("attempt,id").Find(&spans).Error)
		pending, _ := rc.Get(context.Background(), "multimodal:pending:doc").Int64()
		entry := map[string]any{"name": name, "payload": payload, "raw_payload": string(raw), "error": message, "retry": err != nil && !errors.Is(err, asynq.SkipRetry), "chunks": children, "indexes": indexes, "vectors": vectors, "provider": calls, "tasks": queued.tasks, "spans": spans, "pending": pending}
		b, e := json.Marshal(entry)
		must(e)
		canonicalURL := "local://1/image.png"
		if name == "foreign-image-reference" {
			canonicalURL = "local://2/image.png"
		}
		if name == "foreign-storage-reference" {
			canonicalURL = "storage://owned-backend/local://2/image.png"
		}
		s := strings.ReplaceAll(string(b), imageURL, canonicalURL)
		s = strings.ReplaceAll(s, server.URL, "http://provider")
		s = strings.ReplaceAll(s, dir, "/owned/image-fixture")
		var stable any
		must(json.Unmarshal([]byte(s), &stable))
		out = append(out, stable)
		server.Close()
		client.Close()
		rc.Close()
		red.Close()
		sql.Close()
		os.RemoveAll(dir)
	}
	b, e := json.Marshal(out)
	must(e)
	must(os.WriteFile(os.Args[1], append(b, '\n'), 0600))
	fmt.Println("exported", len(out), "actual image multimodal scenes")
}
