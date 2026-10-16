//go:build ignore

// Actual Go CloneChunk and repositories; controlled external boundaries expose ordering.
package service

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"testing"

	"github.com/Tencent/WeKnora/internal/application/access"
	"github.com/Tencent/WeKnora/internal/application/repository"
	"github.com/Tencent/WeKnora/internal/models/embedding"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"github.com/google/uuid"
	"github.com/stretchr/testify/require"
	"gorm.io/driver/postgres"
	"gorm.io/gorm"
)

type javaCloneChunkControl struct {
	t         *testing.T
	name      string
	events    []any
	ids       map[string]string
	sourceIDs []string
	pages     int
	saves     int
	db        *gorm.DB
	mu        sync.Mutex
}

func (c *javaCloneChunkControl) event(kind string, values map[string]any) {
	c.mu.Lock()
	defer c.mu.Unlock()
	values["kind"] = kind
	c.events = append(c.events, values)
}

type javaCloneChunkFiles struct {
	interfaces.FileService
	c *javaCloneChunkControl
}

func (f javaCloneChunkFiles) GetFile(_ context.Context, path string) (io.ReadCloser, error) {
	f.c.event("read", map[string]any{"path": path})
	if f.c.name == "image-read-failure" || f.c.name == "image-partial-failure" && strings.Contains(path, "second") {
		return nil, errors.New("controlled read failure")
	}
	if f.c.name == "image-buffer-failure" {
		return javaCloneChunkReader{}, nil
	}
	data := path
	switch f.c.name {
	case "sniff-png":
		data = "\x89PNG\r\n\x1a\n"
	case "sniff-jpeg":
		data = "\xff\xd8\xff"
	case "sniff-gif":
		data = "GIF89a"
	case "sniff-webp":
		data = "RIFFxxxxWEBPVP"
	case "sniff-bmp":
		data = "BM"
	}
	if f.c.name == "image-close-failure" {
		return javaCloneChunkCloseReader{Reader: strings.NewReader(data)}, nil
	}
	return io.NopCloser(strings.NewReader(data)), nil
}

type javaCloneChunkCloseReader struct{ io.Reader }

func (javaCloneChunkCloseReader) Close() error { return errors.New("controlled close failure") }

type javaCloneChunkReader struct{}

func (javaCloneChunkReader) Read([]byte) (int, error) {
	return 0, errors.New("controlled buffer failure")
}
func (javaCloneChunkReader) Close() error { return nil }
func (f javaCloneChunkFiles) SaveBytes(_ context.Context, data []byte, tenant uint64, name string, temp bool) (string, error) {
	_, e := uuid.Parse(strings.TrimSuffix(name, filepath.Ext(name)))
	require.NoError(f.c.t, e)
	f.c.saves++
	path := fmt.Sprintf("local://%d/exports/copied-%d%s", tenant, f.c.saves, filepath.Ext(name))
	f.c.event("save", map[string]any{"tenant": tenant, "extension": filepath.Ext(name), "temporary": temp, "data": []byte(data), "path": path})
	if f.c.name == "image-save-failure" {
		return "", errors.New("controlled save failure")
	}
	return path, nil
}
func (f javaCloneChunkFiles) DeleteFile(_ context.Context, path string) error {
	f.c.event("delete_image", map[string]any{"path": path})
	if f.c.name == "image-cleanup-failure" {
		return errors.New("controlled image cleanup failure")
	}
	return nil
}

type javaCloneChunkRepo struct {
	interfaces.ChunkRepository
	c *javaCloneChunkControl
}

func (r javaCloneChunkRepo) CreateChunks(ctx context.Context, rows []*types.Chunk) error {
	start := r.c.pages * 100
	r.c.pages++
	for i, row := range rows {
		_, e := uuid.Parse(row.ID)
		require.NoError(r.c.t, e)
		_, exists := r.c.ids[row.ID]
		require.False(r.c.t, exists)
		r.c.ids[row.ID] = "copy:" + r.c.sourceIDs[start+i]
	}
	r.c.event("create_chunks", map[string]any{"count": len(rows)})
	if r.c.name == "create-before" || r.c.name == "late-page-failure" && r.c.pages == 2 {
		return errors.New("controlled chunk create failure")
	}
	if e := r.ChunkRepository.CreateChunks(ctx, rows); e != nil {
		return e
	}
	if r.c.name == "create-ack-failure" || r.c.name == "claim-conflict" {
		if r.c.name == "claim-conflict" {
			require.NoError(r.c.t, r.c.db.Exec("UPDATE knowledges SET parse_status='completed' WHERE id='dst'").Error)
		}
		return errors.New("controlled chunk create failure")
	}
	return nil
}
func (r javaCloneChunkRepo) DeleteChunksByKnowledgeID(ctx context.Context, tenant uint64, id string) error {
	r.c.event("delete_chunks", map[string]any{"tenant": tenant, "id": id})
	if r.c.name == "chunk-cleanup-failure" {
		return errors.New("controlled chunk cleanup failure")
	}
	return r.ChunkRepository.DeleteChunksByKnowledgeID(ctx, tenant, id)
}

type javaCloneChunkModels struct {
	interfaces.ModelService
	c *javaCloneChunkControl
}

func (m javaCloneChunkModels) GetEmbeddingModel(_ context.Context, id string) (embedding.Embedder, error) {
	m.c.event("model", map[string]any{"id": id})
	if m.c.name == "model-failure" {
		return nil, errors.New("controlled model failure")
	}
	return kbCleanupEmbedder{}, nil
}

type javaCloneChunkEngine struct {
	interfaces.RetrieveEngineService
	c *javaCloneChunkControl
}

func (e javaCloneChunkEngine) EngineType() types.RetrieverEngineType {
	return types.PostgresRetrieverEngineType
}
func (e javaCloneChunkEngine) Support() []types.RetrieverType {
	return []types.RetrieverType{types.VectorRetrieverType}
}
func (e javaCloneChunkEngine) CopyIndices(_ context.Context, src string, docs, chunks map[string]string, dst string, dim int, kind string) error {
	e.c.event("copy_indices", map[string]any{"source": src, "target": dst, "documents": docs, "chunks": chunks, "dimensions": dim, "kb_type": kind})
	if strings.Contains(e.c.name, "index-failure") || e.c.name == "index-cleanup-failure" || e.c.name == "chunk-cleanup-failure" || e.c.name == "image-cleanup-failure" {
		return errors.New("controlled index failure")
	}
	return nil
}
func (e javaCloneChunkEngine) DeleteByKnowledgeIDList(_ context.Context, ids []string, dim int, kind string) error {
	e.c.event("delete_indices", map[string]any{"ids": ids, "dimensions": dim, "kb_type": kind})
	if e.c.name == "index-cleanup-failure" {
		return errors.New("controlled index cleanup failure")
	}
	return nil
}

type javaCloneChunkRegistry struct {
	interfaces.RetrieveEngineRegistry
	c *javaCloneChunkControl
}

func (r javaCloneChunkRegistry) GetRetrieveEngineService(types.RetrieverEngineType) (interfaces.RetrieveEngineService, error) {
	r.c.event("engine", map[string]any{})
	if r.c.name == "engine-failure" {
		return nil, errors.New("controlled engine failure")
	}
	return javaCloneChunkEngine{c: r.c}, nil
}

func javaCloneChunksFixture(t *testing.T) *documentWriteFixture {
	f := newDocumentWriteFixture(t)
	if dsn := os.Getenv("WEKNORA_CLONE_CHUNKS_PG_DSN"); dsn != "" {
		u, e := url.Parse(dsn)
		require.NoError(t, e)
		require.Equal(t, "127.0.0.1", u.Hostname())
		require.Equal(t, "/weknora_clone_chunks_parity", u.Path)
		db, e := gorm.Open(postgres.New(postgres.Config{DSN: dsn}), &gorm.Config{})
		require.NoError(t, e)
		sql, e := db.DB()
		require.NoError(t, e)
		sql.SetMaxOpenConns(1)
		t.Cleanup(func() { require.NoError(t, sql.Close()) })
		f.db = db
		f.repo.KnowledgeRepository = repository.NewKnowledgeRepository(db)
		f.chunkRepo.ChunkRepository = repository.NewChunkRepository(db)
	}
	f.svc.tagRepo = repository.NewKnowledgeTagRepository(f.db)
	for _, table := range []string{"knowledge_tag_relations", "chunks", "knowledges", "knowledge_tags"} {
		require.NoError(t, f.db.Exec("DELETE FROM "+table).Error)
	}
	return f
}

func TestJavaKnowledgeCloneChunksExport(t *testing.T) {
	names := []string{"ordinary", "no-model", "no-chunks", "all-fields", "all-types", "blank-index-status", "blank-chunk-type", "existing-tag", "new-tag", "untagged", "disabled", "deleted-source", "wrong-parent", "wrong-chunk-kb", "wrong-tag", "missing-destination", "destination-wrong-kb", "destination-wrong-tenant", "destination-deleted", "image-empty", "image-null", "image-empty-array", "image-nil-entry", "image-empty-url", "image-matched-original", "image-external-original", "image-unknown-fields", "image-attrs", "image-attrs-numbers", "image-attrs-map-order", "image-duplicate-fields", "image-null-fields", "image-close-failure", "image-duplicate", "image-across-chunks", "image-parent-reference", "image-prefix-urls", "image-invalid-json", "image-object", "image-string-url", "image-string-position", "image-read-failure", "image-buffer-failure", "image-save-failure", "image-partial-failure", "image-late-invalid", "create-before", "create-ack-failure", "claim-conflict", "late-page-failure", "engine-failure", "model-failure", "index-failure", "index-cleanup-failure", "chunk-cleanup-failure", "image-cleanup-failure", "batch-99", "batch-100", "batch-101", "batch-201", "sniff-png", "sniff-jpeg", "sniff-gif", "sniff-webp", "sniff-bmp", "extension-uppercase", "extension-svg", "extension-unknown"}
	out := []any{}
	for _, name := range names {
		t.Run(name, func(t *testing.T) {
			f := javaCloneChunksFixture(t)
			c := &javaCloneChunkControl{t: t, name: name, events: []any{}, ids: map[string]string{}, db: f.db}
			sqls := []string{}
			add := func(sql string) { sqls = append(sqls, sql) }
			quote := func(s string) string { return "'" + strings.ReplaceAll(s, "'", "''") + "'" }
			if f.db.Dialector.Name() == "postgres" {
				add("SELECT setval('knowledge_tags_seq_id_seq',10000000,false)")
			}
			add(`INSERT INTO knowledges(id,tenant_id,knowledge_base_id,type,title,source,parse_status,embedding_model_id,metadata,custom_metadata,storage_size,updated_at) VALUES('src',7,'kb','manual','','','completed','source-model','{}','{}',9,'2026-01-01 00:00:00+00:00'),('dst',7,'other','manual','','','processing','target-model','{}','{}',0,'2026-01-01 00:00:00+00:00'),('protected',8,'foreign','manual','','','completed','','{}','{}',0,'2026-01-01 00:00:00+00:00')`)
			add(`INSERT INTO knowledge_tags(id,tenant_id,knowledge_base_id,name,color,sort_order) VALUES('tag',7,'kb','source tag','#abc',4)`)
			count := 1
			if name == "all-types" {
				count = 12
			}
			if name == "image-across-chunks" || name == "image-parent-reference" || name == "image-late-invalid" {
				count = 2
			}
			if name == "late-page-failure" || name == "batch-101" {
				count = 101
			}
			if name == "batch-99" {
				count = 99
			}
			if name == "batch-100" {
				count = 100
			}
			if name == "batch-201" {
				count = 201
			}
			if name == "no-chunks" {
				count = 0
			}
			for i := 0; i < count; i++ {
				id := fmt.Sprintf("c%03d", i)
				c.sourceIDs = append(c.sourceIDs, id)
				add(fmt.Sprintf(`INSERT INTO chunks(id,seq_id,tenant_id,knowledge_id,knowledge_base_id,tag_id,content,source_content,content_revision,index_status,last_editor_id,chunk_index,is_enabled,flags,status,start_at,end_at,pre_chunk_id,next_chunk_id,chunk_type,parent_chunk_id,metadata,content_hash,image_info,context_header,source_locators,relation_chunks,indirect_relation_chunks,created_at,updated_at) VALUES('%s',%d,7,'src','kb','','body %d','old parser',3,'processing','editor',%d,true,0,2,2,9,'missing-prev','missing-next','text','','{"k":7}','hash','','context',NULL,'["old"]','["indirect"]','2026-01-01 00:00:00+00:00','2026-01-01 00:00:00+00:00')`, id, i+1, i, i))
			}
			image := `[{"url":"local://7/exports/a.png","original_url":"local://7/exports/a.png","caption":"cap","start_pos":2,"end_pos":9}]`
			switch name {
			case "no-model":
				add(`UPDATE knowledges SET embedding_model_id='' WHERE id='dst'`)
			case "all-fields":
				add(`UPDATE chunks SET source_locators='[{"kind":"pdf","page":2}]',is_enabled=false,flags=7,metadata='{"arbitrary":[1,true,"x"]}',tag_id='tag',pre_chunk_id='c000',next_chunk_id='c000'`)
			case "all-types":
				kinds := []string{"text", "parent_text", "image_ocr", "image_caption", "summary", "entity", "relationship", "faq", "web_search", "table_summary", "table_column", "wiki_page"}
				for i, kind := range kinds {
					add(fmt.Sprintf("UPDATE chunks SET chunk_type='%s',parent_chunk_id='c000' WHERE id='c%03d'", kind, i))
				}
			case "blank-index-status":
				add(`UPDATE chunks SET index_status=''`)
			case "blank-chunk-type":
				add(`UPDATE chunks SET chunk_type=''`)
			case "existing-tag":
				add(`UPDATE chunks SET tag_id='tag'`)
				add(`INSERT INTO knowledge_tags(id,tenant_id,knowledge_base_id,name,color,sort_order) VALUES('existing',7,'other','source tag','#def',8)`)
			case "new-tag":
				add(`UPDATE chunks SET tag_id='tag'`)
			case "untagged":
				add(`UPDATE chunks SET tag_id='tag'`)
				add(`UPDATE knowledge_tags SET name='未分类'`)
			case "disabled":
				add(`UPDATE chunks SET is_enabled=false`)
			case "deleted-source":
				add(`UPDATE chunks SET deleted_at='2026-02-01 00:00:00+00:00'`)
			case "wrong-parent":
				add(`UPDATE chunks SET parent_chunk_id='foreign'`)
			case "wrong-chunk-kb":
				add(`UPDATE chunks SET knowledge_base_id='other'`)
			case "wrong-tag":
				add(`UPDATE chunks SET tag_id='tag'`)
				add(`UPDATE knowledge_tags SET knowledge_base_id='other'`)
			case "missing-destination":
				add(`DELETE FROM knowledges WHERE id='dst'`)
			case "destination-wrong-kb":
				add(`UPDATE knowledges SET knowledge_base_id='kb' WHERE id='dst'`)
			case "destination-wrong-tenant":
				add(`UPDATE knowledges SET tenant_id=8 WHERE id='dst'`)
			case "destination-deleted":
				add(`UPDATE knowledges SET deleted_at='2026-02-01 00:00:00+00:00' WHERE id='dst'`)
			case "image-null":
				image = `null`
			case "image-empty-array":
				image = `[]`
			case "image-nil-entry":
				image = `[null]`
			case "image-empty-url":
				image = `[{"caption":"empty"}]`
			case "image-external-original":
				image = `[{"url":"local://7/exports/a.png","original_url":"https://external/original"}]`
			case "image-unknown-fields":
				image = `[{"URL":"local://7/exports/a.png","other":5,"caption":"<hi>"}]`
			case "image-attrs":
				image = `[{"url":"local://7/exports/a.png","sha256":"abc","attrs":{"schema":"attrs/2","source":{"schema":"attrs/2","prompt":"x","custom":true},"attrs":{"contain.text":"block","unknown":false}}}]`
			case "image-attrs-numbers":
				image = `[{"url":"local://7/exports/a.png","attrs":{"attrs":{"z":1e-7,"a":1e21,"p":0.000001,"big":9007199254740993,"neg":-0.0,"small":5e-324}}}]`
			case "image-attrs-map-order":
				image = `[{"url":"local://7/exports/a.png","attrs":{"attrs":{"z":{"b":2,"a":1},"a":[{"y":false,"x":"<&>"}]}}}]`
			case "image-duplicate-fields":
				image = `[{"url":"wrong","URL":"local://7/exports/a.png","caption":"keep","caption":null,"start_pos":3,"start_pos":null}]`
			case "image-null-fields":
				image = `[{"url":null,"attrs":null,"start_pos":null}]`
			case "image-duplicate":
				image = `[{"url":"local://7/exports/a.png"},{"url":"local://7/exports/a.png"}]`
			case "image-prefix-urls":
				image = `[{"url":"local://7/exports/a.png"},{"url":"local://7/exports/a.png-more.png"}]`
				add(`UPDATE chunks SET content='local://7/exports/a.png-more.png local://7/exports/a.png'`)
			case "image-invalid-json":
				image = `{broken`
			case "image-object":
				image = `{}`
			case "image-string-url":
				image = `[{"url":7}]`
			case "image-string-position":
				image = `[{"start_pos":"7"}]`
			case "image-partial-failure":
				image = `[{"url":"local://7/exports/a.png"},{"url":"local://7/exports/second.png"}]`
			case "sniff-png", "sniff-jpeg", "sniff-gif", "sniff-webp", "sniff-bmp":
				image = `[{"url":"local://7/exports/noextension"}]`
			case "extension-uppercase":
				image = `[{"url":"local://7/exports/a.JPEG"}]`
			case "extension-svg":
				image = `[{"url":"local://7/exports/a.svg"}]`
			case "extension-unknown":
				image = `[{"url":"local://7/exports/a.pdf"}]`
			}
			if strings.HasPrefix(name, "image-") && name != "image-empty" || strings.HasPrefix(name, "sniff-") || strings.HasPrefix(name, "extension-") || strings.Contains(name, "failure") || name == "create-before" || name == "claim-conflict" {
				add("UPDATE chunks SET image_info=" + quote(image) + " WHERE id='c000'")
			}
			if name == "image-across-chunks" {
				add("UPDATE chunks SET image_info=" + quote(image))
			}
			if name == "image-parent-reference" {
				add(`UPDATE chunks SET image_info='',content='![image](local://7/exports/a.png)' WHERE id='c000'`)
				add("UPDATE chunks SET image_info=" + quote(image) + ",parent_chunk_id='c000',chunk_type='image_ocr' WHERE id='c001'")
			}
			if name == "image-late-invalid" {
				add(`UPDATE chunks SET image_info='{bad' WHERE id='c001'`)
			}
			if f.db.Dialector.Name() == "postgres" {
				add("SELECT setval('chunks_seq_id_seq',GREATEST(COALESCE((SELECT MAX(seq_id) FROM chunks),0)+1,100000000),false)")
			}
			for _, sql := range sqls {
				require.NoError(t, f.db.Exec(sql).Error)
			}
			src, e := f.repo.GetKnowledgeByID(f.ctx, 7, "src")
			require.NoError(t, e)
			dst := &types.Knowledge{ID: "dst", TenantID: 7, KnowledgeBaseID: "other", Type: "manual", EmbeddingModelID: "target-model"}
			if name == "no-model" {
				dst.EmbeddingModelID = ""
			}
			f.svc.chunkRepo = javaCloneChunkRepo{ChunkRepository: f.chunkRepo.ChunkRepository, c: c}
			f.svc.fileSvc = javaCloneChunkFiles{c: c}
			f.svc.modelService = javaCloneChunkModels{c: c}
			f.svc.retrieveEngine = javaCloneChunkRegistry{c: c}
			tenant := &types.Tenant{ID: 7, RetrieverEngines: types.RetrieverEngines{Engines: []types.RetrieverEngineParams{{RetrieverEngineType: types.PostgresRetrieverEngineType, RetrieverType: types.VectorRetrieverType}}}}
			ctx := context.WithValue(f.ctx, types.TenantInfoContextKey, tenant)
			ctx, e = access.WithKBTransferTask(ctx, f.kbs.values["kb"], f.kbs.values["other"], 7, access.KBTransferClone, "clone-task", false)
			require.NoError(t, e)
			err := f.svc.CloneChunk(ctx, src, dst)
			message := ""
			if err != nil {
				message = err.Error()
			}
			var tags []map[string]any
			require.NoError(t, f.db.Raw("SELECT id,seq_id,tenant_id,knowledge_base_id,name,color,sort_order FROM knowledge_tags").Scan(&tags).Error)
			for _, tag := range tags {
				id := fmt.Sprint(tag["id"])
				if id != "tag" && id != "existing" {
					_, e := uuid.Parse(id)
					require.NoError(t, e)
					c.ids[id] = "tag:" + fmt.Sprint(tag["name"])
				}
			}
			snap := map[string]any{"error": message, "events": c.events}
			queries := map[string]string{
				"documents": "SELECT id,tenant_id,knowledge_base_id,parse_status,COALESCE(error_message,'') AS error_message,storage_size FROM knowledges ORDER BY id",
				"chunks":    "SELECT id,seq_id,tenant_id,knowledge_id,knowledge_base_id,COALESCE(tag_id,'') AS tag_id,content,COALESCE(source_content,'') AS source_content,content_revision,index_status,last_editor_id,chunk_index,CASE WHEN is_enabled THEN 1 ELSE 0 END AS is_enabled,flags,status,start_at,end_at,COALESCE(pre_chunk_id,'') AS pre_chunk_id,COALESCE(next_chunk_id,'') AS next_chunk_id,chunk_type,COALESCE(parent_chunk_id,'') AS parent_chunk_id,COALESCE(CAST(metadata AS TEXT),'') AS metadata,COALESCE(content_hash,'') AS content_hash,COALESCE(image_info,'') AS image_info,COALESCE(image_info,'') AS image_info_raw,COALESCE(context_header,'') AS context_header,COALESCE(CAST(source_locators AS TEXT),'') AS source_locators,COALESCE(CAST(relation_chunks AS TEXT),'') AS relation_chunks,COALESCE(CAST(indirect_relation_chunks AS TEXT),'') AS indirect_relation_chunks,CASE WHEN deleted_at IS NULL THEN 0 ELSE 1 END AS deleted,CASE WHEN created_at=updated_at THEN 1 ELSE 0 END AS same_timestamp FROM chunks",
				"tags":      "SELECT id,seq_id,tenant_id,knowledge_base_id,name,color,sort_order FROM knowledge_tags",
			}
			if f.db.Dialector.Name() == "sqlite" {
				queries["chunks"] = strings.Replace(queries["chunks"], "SELECT id,", "SELECT typeof(metadata) AS metadata_storage_type,typeof(source_locators) AS locator_storage_type,id,", 1)
			}
			for key, query := range queries {
				var rows []map[string]any
				require.NoError(t, f.db.Raw(query).Scan(&rows).Error)
				snap[key] = rows
			}
			normalized := javaCloneChunkNormalize(t, snap, c.ids)
			out = append(out, map[string]any{"name": name, "seed_statements": sqls, "source": f.kbs.values["kb"], "target": f.kbs.values["other"], "queries": queries, "snapshot": normalized})
		})
	}
	raw, e := json.MarshalIndent(out, "", "  ")
	require.NoError(t, e)
	require.NoError(t, os.WriteFile(os.Getenv("WEKNORA_CLONE_CHUNKS_ORACLE"), raw, 0600))
}

func javaCloneChunkNormalize(t *testing.T, value any, ids map[string]string) any {
	raw, e := json.Marshal(value)
	require.NoError(t, e)
	var tree any
	decoder := json.NewDecoder(strings.NewReader(string(raw)))
	decoder.UseNumber()
	require.NoError(t, decoder.Decode(&tree))
	var visit func(any) any
	visit = func(v any) any {
		switch x := v.(type) {
		case string:
			if mapped, ok := ids[x]; ok {
				return mapped
			}
			return x
		case []any:
			for i, item := range x {
				x[i] = visit(item)
			}
			return x
		case map[string]any:
			r := map[string]any{}
			for key, item := range x {
				if s, ok := item.(string); ok && (key == "metadata" || key == "image_info" || key == "source_locators" || key == "relation_chunks" || key == "indirect_relation_chunks") && s != "" {
					var decoded any
					decoder := json.NewDecoder(strings.NewReader(s))
					decoder.UseNumber()
					if decoder.Decode(&decoded) == nil {
						item = decoded
					}
				}
				normalizedKey := key
				if mapped, ok := ids[key]; ok {
					normalizedKey = mapped
				}
				r[normalizedKey] = visit(item)
			}
			return r
		}
		return v
	}
	result := visit(tree).(map[string]any)
	for _, key := range []string{"documents", "chunks", "tags"} {
		rows, ok := result[key].([]any)
		if !ok {
			result[key] = []any{}
			continue
		}
		sort.Slice(rows, func(i, j int) bool {
			return fmt.Sprint(rows[i].(map[string]any)["id"]) < fmt.Sprint(rows[j].(map[string]any)["id"])
		})
	}
	return result
}
