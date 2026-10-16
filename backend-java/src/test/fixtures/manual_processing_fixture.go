//go:build ignore

// Actual manual:process worker, SQLite vec/FTS, provider HTTP, SQL and Asynq.
package main

import (
	"context"
	databaseSql "database/sql"
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
	"github.com/Tencent/WeKnora/internal/config"
	"github.com/Tencent/WeKnora/internal/logger"
	"github.com/Tencent/WeKnora/internal/models/embedding"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	utils "github.com/Tencent/WeKnora/internal/utils"
	"github.com/alicebob/miniredis/v2"
	vec "github.com/asg017/sqlite-vec-go-bindings/cgo"
	"github.com/hibiken/asynq"
	"github.com/panjf2000/ants/v2"
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

type graph struct {
	interfaces.RetrieveGraphRepository
	calls int
}

func (g *graph) DelGraph(context.Context, []types.NameSpace) error { g.calls++; return nil }

type queue struct {
	client *asynq.Client
	tasks  []any
	fail   bool
}

func (q *queue) Enqueue(task *asynq.Task, opts ...asynq.Option) (*asynq.TaskInfo, error) {
	if q.fail {
		return nil, errors.New("controlled queue unavailable")
	}
	info, e := q.client.Enqueue(task, opts...)
	if e != nil {
		return nil, e
	}
	var payload any
	must(json.Unmarshal(task.Payload(), &payload))
	q.tasks = append(q.tasks, map[string]any{"type": task.Type(), "payload": payload, "queue": info.Queue, "max_retry": info.MaxRetry, "timeout": info.Timeout / time.Millisecond})
	return info, nil
}
func main() {
	logger.SetOutput(os.Stderr)
	time.Local = time.UTC
	pgDSN := os.Getenv("WEKNORA_MANUAL_WORKER_PG_DSN")
	pg := pgDSN != ""
	if pg {
		if !strings.Contains(pgDSN, "@127.0.0.1:") || !strings.Contains(pgDSN, "/weknora_manual_parity?") {
			panic("owned loopback manual PG required")
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
	out := []any{}
	names := []string{"success", "cleanup", "crlf", "parent-child", "heading", "html-table", "unicode", "empty", "whitespace", "completed", "cancelled", "deleting", "draft", "failed", "missing-knowledge", "missing-base", "missing-tenant", "foreign-document", "wrong-kb", "foreign-base", "model-missing", "model-foreign", "model-type", "embedding-error", "quota", "disabled-indexes", "queue-error", "stale-content", "edit-during-embedding", "cancel-during-embedding", "kb-during-embedding", "payload-null", "payload-array", "payload-owner-string", "payload-content-number", "payload-cleanup-number", "payload-negative-owner", "payload-trailing", "extra-attempt", "same-content", "distinct-vectors", "existing-attempt", "wrong-type", "delete-during-embedding", "fresh-attempt-during-embedding"}
	for _, name := range names {
		dir, e := os.MkdirTemp("", "weknora-go-manual-worker-")
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
				panic("owned manual PG required")
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
		content := "# Access\n\nNative manual content describes secure deployment and onboarding controls."
		if name == "crlf" {
			content = " first\r\nsecond\rthird "
		}
		if name == "parent-child" {
			content = strings.Repeat("Native access and deployment controls.\n\n", 12)
		}
		if name == "heading" {
			content = "# Title\n\nFirst body.\n\n## Child\n\nMore details."
		}
		if name == "html-table" {
			content = "<table><tr><th>Name</th><th>Role</th></tr><tr><td>Alice</td><td>Admin</td></tr></table>"
		}
		if name == "unicode" {
			content = " 标题\n\n你好世界。下一段！ "
		}
		if name == "same-content" {
			content = strings.Repeat("Repeated content.\n\n", 4)
		}
		if name == "distinct-vectors" {
			content = "# First\n\nShort body.\n\n# Second\n\nLong separate body has different length"
		}
		if name == "empty" {
			content = ""
		}
		if name == "whitespace" {
			content = " \n\t "
		}
		kb := &types.KnowledgeBase{ID: "kb", TenantID: 1, Name: "KB", Type: "document", EmbeddingModelID: "embedding", IndexingStrategy: types.IndexingStrategy{VectorEnabled: true, KeywordEnabled: true}, ChunkingConfig: types.ChunkingConfig{ChunkSize: 64, ChunkOverlap: 10, Strategy: "legacy", Separators: []string{"\n\n", "\n", "。"}, EnableParentChild: name == "parent-child", ParentChunkSize: 128, ChildChunkSize: 32}}
		if name == "heading" {
			kb.ChunkingConfig.Strategy = "heading"
		}
		if name == "disabled-indexes" {
			kb.IndexingStrategy = types.IndexingStrategy{GraphEnabled: true}
		}
		if name == "same-content" {
			kb.ChunkingConfig.ChunkSize = 20
			kb.ChunkingConfig.ChunkOverlap = 0
		}
		must(br.CreateKnowledgeBase(context.Background(), kb))
		row := &types.Knowledge{ID: "doc", TenantID: 1, KnowledgeBaseID: "kb", Type: "manual", Source: "manual", Title: "Manual title", FileName: "Manual title.md", FileType: "manual", EmbeddingModelID: "embedding", ParseStatus: "pending", EnableStatus: "disabled"}
		must(row.SetManualMetadata(types.NewManualKnowledgeMetadata(content, "publish", 3)))
		off := false
		must(row.SetProcessOverrides(&types.KnowledgeProcessOverrides{SummaryEnabled: &off}))
		must(kr.CreateKnowledge(context.Background(), row))
		calls := []any{}
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			var input map[string]any
			must(json.NewDecoder(r.Body).Decode(&input))
			calls = append(calls, input)
			if name == "edit-during-embedding" {
				must(db.Exec("UPDATE knowledges SET metadata=?,title='New title',parse_status='pending' WHERE id='doc'", `{"content":"New content","format":"markdown","status":"publish","version":4}`).Error)
			}
			if name == "cancel-during-embedding" {
				must(db.Exec("UPDATE knowledges SET parse_status='cancelled' WHERE id='doc'").Error)
			}
			if name == "kb-during-embedding" {
				must(db.Exec("UPDATE knowledge_bases SET tenant_id=2 WHERE id='kb'").Error)
			}
			if name == "delete-during-embedding" {
				must(db.Exec("DELETE FROM knowledges WHERE id='doc'").Error)
			}
			if name == "fresh-attempt-during-embedding" {
				_, _, e := svc.NewSpanTracker(repo.NewKnowledgeSpanRepository(db), db).OpenAttempt(context.Background(), "doc", "")
				must(e)
			}
			if name == "embedding-error" {
				w.WriteHeader(500)
				_, _ = w.Write([]byte(`{"error":{"message":"controlled embedding failure"}}`))
				return
			}
			var items []any
			switch v := input["input"].(type) {
			case []any:
				items = v
			case string:
				items = []any{v}
			}
			data := []any{}
			for i, item := range items {
				text := fmt.Sprint(item)
				sum := 0
				for _, b := range []byte(text) {
					sum += int(b)
				}
				data = append(data, map[string]any{"index": i, "embedding": []float32{float32(len([]byte(text))), float32(sum % 251)}})
			}
			w.Header().Set("Content-Type", "application/json")
			must(json.NewEncoder(w).Encode(map[string]any{"data": data}))
		}))
		params, e := json.Marshal(map[string]any{"provider": "generic", "base_url": server.URL + "/v1", "embedding_parameters": map[string]any{"dimension": 2}})
		must(e)
		must(db.Exec("INSERT INTO models(id,tenant_id,name,type,source,parameters,status) VALUES('embedding',1,'manual-embedding','Embedding','remote',?,'active')", params).Error)
		if name == "model-missing" {
			must(db.Exec("DELETE FROM models").Error)
		}
		if name == "model-foreign" {
			must(db.Exec("UPDATE models SET tenant_id=2").Error)
		}
		if name == "model-type" {
			must(db.Exec("UPDATE models SET type='KnowledgeQA'").Error)
		}
		var index interfaces.RetrieveEngineRepository
		engineType := types.SQLiteRetrieverEngineType
		if pg {
			index = pgindex.NewPostgresRetrieveEngineRepository(db)
			engineType = types.PostgresRetrieverEngineType
		} else {
			index = sqliteindex.NewSQLiteRetrieveEngineRepository(db)
		}
		registry := retriever.NewRetrieveEngineRegistry(nil, nil)
		must(registry.Register(retriever.NewKVHybridRetrieveEngine(index, engineType)))
		models := svc.NewModelService(repo.NewModelRepository(db), br, nil, nil, embedding.NewBatchEmbedder(pool), nil)
		redis, e := miniredis.Run()
		must(e)
		client := asynq.NewClient(asynq.RedisClientOpt{Addr: redis.Addr()})
		queued := &queue{client: client, tasks: []any{}, fail: name == "queue-error"}
		g := &graph{}
		local := filesvc.NewLocalFileService(dir, "")
		base := svc.NewKnowledgeBaseService(br, kr, cr, nil, nil, nil, nil, nil, tr, local, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil)
		tracker := svc.NewSpanTracker(repo.NewKnowledgeSpanRepository(db), db)
		if name == "existing-attempt" {
			_, _, e := tracker.OpenAttempt(context.Background(), "doc", "")
			must(e)
		}
		chunks := svc.NewChunkService(cr, kr, br, models, registry, nil, queued, tracker)
		knowledge, e := svc.NewKnowledgeService(&config.Config{}, kr, nil, base, tr, nil, chunks, cr, nil, nil, local, nil, nil, models, queued, nil, g, registry, nil, nil, nil, nil, nil, nil, nil, tracker, nil)
		must(e)
		payload := types.ManualProcessPayload{TenantID: 1, KnowledgeID: "doc", KnowledgeBaseID: "kb", Content: content, NeedCleanup: name == "cleanup"}
		if name == "stale-content" {
			payload.Content = "Old obsolete content"
		}
		if name == "cleanup" {
			must(cr.CreateChunks(context.Background(), []*types.Chunk{{ID: "old", TenantID: 1, KnowledgeBaseID: "kb", KnowledgeID: "doc", ChunkType: "text", Content: "Old indexed content", IsEnabled: true}}))
			must(index.BatchSave(context.Background(), []*types.IndexInfo{{SourceID: "old", ChunkID: "old", KnowledgeID: "doc", KnowledgeBaseID: "kb", Content: "Old indexed content", IsEnabled: true}}, map[string]any{"embedding": map[string][]float32{"old": {1, 0}}}))
			must(db.Exec("UPDATE knowledges SET storage_size=20 WHERE id='doc'").Error)
			must(db.Exec("UPDATE tenants SET storage_used=20 WHERE id=1").Error)
		}
		if name == "quota" {
			must(db.Exec("UPDATE tenants SET storage_quota=1 WHERE id=1").Error)
		}
		if name == "missing-knowledge" {
			must(db.Exec("DELETE FROM knowledges").Error)
		}
		if name == "missing-base" {
			must(db.Exec("DELETE FROM knowledge_bases").Error)
		}
		if name == "missing-tenant" {
			must(db.Exec("DELETE FROM tenants WHERE id=1").Error)
		}
		if name == "foreign-document" {
			must(db.Exec("UPDATE knowledges SET tenant_id=2").Error)
		}
		if name == "wrong-kb" {
			payload.KnowledgeBaseID = "other"
		}
		if name == "foreign-base" {
			must(db.Exec("UPDATE knowledge_bases SET tenant_id=2").Error)
		}
		if name == "completed" || name == "cancelled" || name == "deleting" || name == "draft" || name == "failed" {
			must(db.Exec("UPDATE knowledges SET parse_status=? WHERE id='doc'", name).Error)
		}
		if name == "wrong-type" {
			must(db.Exec("UPDATE knowledges SET type='file' WHERE id='doc'").Error)
		}
		raw, e := json.Marshal(payload)
		must(e)
		switch name {
		case "payload-null":
			raw = []byte(`null`)
		case "payload-array":
			raw = []byte(`[]`)
		case "payload-owner-string":
			raw = []byte(`{"tenant_id":"1"}`)
		case "payload-content-number":
			raw = []byte(`{"tenant_id":1,"content":7}`)
		case "payload-cleanup-number":
			raw = []byte(`{"tenant_id":1,"need_cleanup":7}`)
		case "payload-negative-owner":
			raw = []byte(`{"tenant_id":-1}`)
		case "payload-trailing":
			raw = append(raw, []byte(`{}`)...)
		case "extra-attempt":
			raw = append(raw[:len(raw)-1], []byte(`,"attempt":99}`)...)
		}
		err := knowledge.ProcessManualUpdate(context.Background(), asynq.NewTask(types.TypeManualProcess, raw))
		message := ""
		if err != nil {
			message = err.Error()
		}
		var stored types.Knowledge
		db.Unscoped().Where("id='doc'").First(&stored)
		var chunkRows []types.Chunk
		must(db.Where("knowledge_id='doc'").Order("chunk_type,chunk_index").Find(&chunkRows).Error)
		ids := map[string]string{"": "", "old": "old"}
		for _, c := range chunkRows {
			ids[c.ID] = fmt.Sprintf("%s-%d", c.ChunkType, c.ChunkIndex)
		}
		outputChunks := []any{}
		for _, c := range chunkRows {
			var storedTag databaseSql.NullString
			must(db.Raw("SELECT tag_id FROM chunks WHERE id=?", c.ID).Row().Scan(&storedTag))
			var tag any
			if storedTag.Valid {
				tag = storedTag.String
			}
			outputChunks = append(outputChunks, map[string]any{"tag_id": tag, "id": ids[c.ID], "content": c.Content, "context_header": c.ContextHeader, "index": c.ChunkIndex, "start": c.StartAt, "end": c.EndAt, "type": c.ChunkType, "parent": ids[c.ParentChunkID], "previous": ids[c.PreChunkID], "next": ids[c.NextChunkID], "enabled": c.IsEnabled, "status": c.Status, "seq_id": c.SeqID})
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
			var rawVectors []struct {
				SourceID  string
				Embedding []byte
			}
			must(db.Raw("SELECT e.source_id,v.embedding FROM lite_embeddings e JOIN vec_embeddings_2 v ON v.rowid=e.id").Scan(&rawVectors).Error)
			sort.Slice(rawVectors, func(i, j int) bool { return ids[rawVectors[i].SourceID] < ids[rawVectors[j].SourceID] })
			for _, v := range rawVectors {
				bits := []uint32{}
				for i := 0; i+4 <= len(v.Embedding); i += 4 {
					bits = append(bits, math.Float32bits(math.Float32frombits(binary.LittleEndian.Uint32(v.Embedding[i:i+4]))))
				}
				vectors = append(vectors, map[string]any{"source_id": ids[v.SourceID], "bits": bits})
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
		var used int64
		must(db.Table("tenants").Select("COALESCE(SUM(storage_used),0)").Scan(&used).Error)
		out = append(out, map[string]any{"name": name, "content": content, "raw_payload": string(raw), "payload": payload, "error": message, "retry": err != nil && !errors.Is(err, asynq.SkipRetry), "knowledge": map[string]any{"metadata": stored.Metadata, "title": stored.Title, "parse_status": stored.ParseStatus, "enable_status": stored.EnableStatus, "summary_status": stored.SummaryStatus, "error": stored.ErrorMessage, "pending": stored.PendingSubtasksCount, "storage_size": stored.StorageSize}, "chunks": outputChunks, "indexes": indexes, "vectors": vectors, "storage_used": used, "tasks": queued.tasks, "provider": calls, "spans": spans, "graph_cleanup_calls": g.calls})
		server.Close()
		client.Close()
		redis.Close()
		sql.Close()
		os.RemoveAll(dir)
	}
	b, e := json.Marshal(out)
	must(e)
	must(os.WriteFile(os.Args[1], append(b, '\n'), 0600))
	fmt.Println("exported", len(out), "actual manual processing scenes")
}
