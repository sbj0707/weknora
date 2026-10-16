//go:build ignore

package service

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"

	"github.com/Tencent/WeKnora/internal/application/access"
	"github.com/Tencent/WeKnora/internal/models/embedding"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"github.com/alicebob/miniredis/v2"
	"github.com/redis/go-redis/v9"
	"github.com/stretchr/testify/require"
)

type javaFAQCloneControl struct {
	c                                                 *javaCloneChunkControl
	attempt, created, documents, engines, sourceReads int
}

func (x *javaFAQCloneControl) fail(name string) bool { return x.attempt == 1 && x.c.name == name }

type javaFAQCloneRepo struct {
	interfaces.KnowledgeRepository
	x *javaFAQCloneControl
}

func (r javaFAQCloneRepo) ListKnowledgeByKnowledgeBaseID(ctx context.Context, owner uint64, kb string) ([]*types.Knowledge, error) {
	if kb == "kb" {
		r.x.sourceReads++
		if r.x.sourceReads == 2 && r.x.fail("source-reload-failure") {
			return nil, errors.New("controlled source reload failure")
		}
	}
	return r.KnowledgeRepository.ListKnowledgeByKnowledgeBaseID(ctx, owner, kb)
}
func (r javaFAQCloneRepo) CreateKnowledge(ctx context.Context, k *types.Knowledge) error {
	r.x.documents++
	r.x.c.ids[k.ID] = fmt.Sprintf("new-document:%d", r.x.documents)
	r.x.c.event("create_document", map[string]any{"id": k.ID})
	if r.x.fail("container-create-failure") {
		return errors.New("controlled container create failure")
	}
	return r.KnowledgeRepository.CreateKnowledge(ctx, k)
}
func (r javaFAQCloneRepo) UpdateKnowledge(ctx context.Context, k *types.Knowledge) error {
	r.x.c.event("update_document", map[string]any{"id": k.ID})
	if r.x.fail("document-update-failure") {
		return errors.New("controlled document update failure")
	}
	return r.KnowledgeRepository.UpdateKnowledge(ctx, k)
}

type javaFAQCloneChunks struct {
	interfaces.ChunkRepository
	x *javaFAQCloneControl
}

func (r javaFAQCloneChunks) FAQChunkDiff(ctx context.Context, so uint64, src string, to uint64, dst string) (*types.FAQChunkDiffResult, error) {
	if r.x.fail("diff-failure") {
		return nil, errors.New("controlled diff failure")
	}
	diff, e := r.ChunkRepository.FAQChunkDiff(ctx, so, src, to, dst)
	if e == nil && r.x.attempt == 1 {
		switch r.x.c.name {
		case "escaped-add":
			diff.ChunksToAdd = append(diff.ChunksToAdd, "protected-chunk")
		case "escaped-delete":
			diff.ChunksToDelete = append(diff.ChunksToDelete, "protected-chunk")
		case "escaped-match":
			diff.MatchedPairs = append(diff.MatchedPairs, types.FAQChunkSyncPair{SrcChunkID: "protected-chunk", DstChunkID: "dst-b"})
		}
	}
	return diff, e
}
func (r javaFAQCloneChunks) ListFAQChunkStatusByIDs(ctx context.Context, owner uint64, ids []string) (map[string]*types.FAQChunkStatus, error) {
	if len(ids) > 0 && r.x.fail("status-read-failure") {
		return nil, errors.New("controlled status read failure")
	}
	return r.ChunkRepository.ListFAQChunkStatusByIDs(ctx, owner, ids)
}
func (r javaFAQCloneChunks) CreateChunks(ctx context.Context, rows []*types.Chunk) error {
	ids := []string{}
	for _, row := range rows {
		r.x.created++
		r.x.c.ids[row.ID] = fmt.Sprintf("new-chunk:%d", r.x.created)
		ids = append(ids, row.ID)
	}
	r.x.c.event("create_chunks", map[string]any{"ids": ids})
	if r.x.fail("chunk-create-failure") || r.x.fail("late-chunk-create-failure") && r.x.created > 50 {
		return errors.New("controlled chunk create failure")
	}
	e := r.ChunkRepository.CreateChunks(ctx, rows)
	if e == nil && r.x.fail("chunk-create-ack-failure") {
		return errors.New("controlled chunk create failure")
	}
	return e
}
func (r javaFAQCloneChunks) DeleteChunks(ctx context.Context, owner uint64, ids []string) error {
	r.x.c.event("delete_chunks", map[string]any{"ids": ids, "tenant": owner})
	return r.ChunkRepository.DeleteChunks(ctx, owner, ids)
}
func (r javaFAQCloneChunks) UpdateChunks(ctx context.Context, rows []*types.Chunk) error {
	values := []any{}
	for _, row := range rows {
		values = append(values, map[string]any{"id": row.ID, "content": row.Content, "enabled": row.IsEnabled, "flags": row.Flags, "status": row.Status, "tag_id": row.TagID})
	}
	r.x.c.event("update_rows", map[string]any{"rows": values})
	return r.ChunkRepository.UpdateChunks(ctx, rows)
}
func (r javaFAQCloneChunks) UpdateChunk(ctx context.Context, row *types.Chunk) error {
	r.x.c.event("patch_metadata", map[string]any{"id": row.ID, "metadata": string(row.Metadata)})
	return r.ChunkRepository.UpdateChunk(ctx, row)
}

type javaFAQCloneEngine struct {
	interfaces.RetrieveEngineService
	x *javaFAQCloneControl
}

func (e javaFAQCloneEngine) EngineType() types.RetrieverEngineType {
	return types.PostgresRetrieverEngineType
}
func (e javaFAQCloneEngine) Support() []types.RetrieverType {
	return []types.RetrieverType{types.VectorRetrieverType}
}
func (e javaFAQCloneEngine) DeleteByChunkIDList(ctx context.Context, ids []string, dimensions int, kind string) error {
	e.x.c.event("delete_indices", map[string]any{"ids": ids, "dimensions": dimensions, "kb_type": kind})
	if e.x.fail("delete-index-failure") {
		return errors.New("controlled index delete failure")
	}
	return nil
}
func (e javaFAQCloneEngine) BatchIndex(_ context.Context, _ embedding.Embedder, entries []*types.IndexInfo, _ []types.RetrieverType) error {
	values := []any{}
	for _, v := range entries {
		if alias, ok := e.x.c.ids[v.ChunkID]; ok {
			e.x.c.ids[v.SourceID] = alias + strings.TrimPrefix(v.SourceID, v.ChunkID)
		}
		values = append(values, map[string]any{"content": v.Content, "sourceId": v.SourceID, "sourceType": v.SourceType, "chunkId": v.ChunkID, "knowledgeId": v.KnowledgeID, "knowledgeBaseId": v.KnowledgeBaseID, "knowledgeType": v.KnowledgeType, "tagId": v.TagID, "enabled": v.IsEnabled, "recommended": v.IsRecommended})
	}
	e.x.c.event("index", map[string]any{"entries": values})
	if e.x.fail("index-failure") || e.x.fail("late-index-failure") && e.x.created > 50 {
		return errors.New("controlled index failure")
	}
	return nil
}
func (e javaFAQCloneEngine) BatchUpdateChunkEnabledStatus(_ context.Context, values map[string]bool) error {
	e.x.c.event("enabled", map[string]any{"values": values})
	if e.x.fail("enabled-failure") {
		return errors.New("controlled enabled failure")
	}
	return nil
}
func (e javaFAQCloneEngine) BatchUpdateChunkTagID(_ context.Context, values map[string]string) error {
	e.x.c.event("tags", map[string]any{"values": values})
	if e.x.fail("tags-failure") {
		return errors.New("controlled tags failure")
	}
	return nil
}

type javaFAQCloneRegistry struct {
	interfaces.RetrieveEngineRegistry
	x *javaFAQCloneControl
}

func (r javaFAQCloneRegistry) GetRetrieveEngineService(types.RetrieverEngineType) (interfaces.RetrieveEngineService, error) {
	r.x.engines++
	r.x.c.event("engine", map[string]any{})
	if r.x.fail("engine-failure") || r.x.fail("index-engine-failure") && r.x.engines == 2 {
		return nil, errors.New("controlled engine failure")
	}
	return javaFAQCloneEngine{x: r.x}, nil
}

type javaFAQCloneModels struct {
	interfaces.ModelService
	x *javaFAQCloneControl
}

func (m javaFAQCloneModels) GetEmbeddingModel(_ context.Context, id string) (embedding.Embedder, error) {
	m.x.c.event("model", map[string]any{"id": id})
	if m.x.fail("model-failure") {
		return nil, errors.New("controlled model failure")
	}
	return kbCleanupEmbedder{}, nil
}

type javaFAQCloneRedis struct{ x *javaFAQCloneControl }

func (h javaFAQCloneRedis) DialHook(next redis.DialHook) redis.DialHook {
	return func(ctx context.Context, network, addr string) (net.Conn, error) { return next(ctx, network, addr) }
}
func (h javaFAQCloneRedis) ProcessPipelineHook(next redis.ProcessPipelineHook) redis.ProcessPipelineHook {
	return next
}
func (h javaFAQCloneRedis) ProcessHook(next redis.ProcessHook) redis.ProcessHook {
	return func(ctx context.Context, cmd redis.Cmder) error {
		if cmd.Name() == "set" {
			var value map[string]any
			switch raw := cmd.Args()[2].(type) {
			case []byte:
				_ = json.Unmarshal(raw, &value)
			case string:
				_ = json.Unmarshal([]byte(raw), &value)
			}
			value["created_at"] = 0
			value["updated_at"] = 0
			h.x.c.event("progress", map[string]any{"value": value})
			if h.x.fail("progress-failure") {
				return errors.New("controlled progress failure")
			}
		}
		return next(ctx, cmd)
	}
}

func TestJavaKnowledgeFAQCloneExport(t *testing.T) {
	names := []string{"ordinary", "empty-source", "empty-target", "empty-chunks", "already-sync", "unindexed-match", "duplicate-target", "empty-hashes", "source-duplicates", "status-enabled", "status-flags", "status-strategy", "status-tags", "status-all", "status-invalid-source", "status-invalid-target", "source-reload-failure", "diff-failure", "escaped-add", "escaped-delete", "escaped-match", "status-read-failure", "engine-failure", "model-failure", "delete-index-failure", "delete-sql-failure", "container-create-failure", "chunk-create-failure", "chunk-create-ack-failure", "index-failure", "index-engine-failure", "document-update-failure", "status-write-failure", "sync-write-failure", "metadata-write-failure", "enabled-failure", "tags-failure", "progress-failure", "images", "image-read-failure", "image-partial-failure", "image-cleanup-failure", "batch-51", "late-index-failure", "late-chunk-create-failure", "question-only", "separate-questions", "separate-question-only", "invalid-metadata", "null-metadata", "source-transfer-metadata", "invalid-container-metadata", "bad-parent", "bad-target-type"}
	output := []any{}
	for _, name := range names {
		t.Run(name, func(t *testing.T) {
			f := javaCloneChunksFixture(t)
			// Run both runtimes against the same checked-in schema, including SQL column ordinals.
			tables, schemaErr := f.db.Migrator().GetTables()
			require.NoError(t, schemaErr)
			for _, table := range tables {
				require.NoError(t, f.db.Migrator().DropTable(table))
			}
			for _, migration := range []string{"000000_init", "000002_knowledge_folder_path", "000003_knowledge_base_auto_tag_config", "000007_system_admin_and_settings", "000008_processing_spans_and_pending_subtasks", "000010_knowledge_multi_tags", "000011_principal_model", "000021_knowledge_profiles", "000031_model_catalog_config", "000033_chunk_source_locators"} {
				raw, e := os.ReadFile(filepath.Join(os.Getenv("WEKNORA_FAQ_CLONE_ROOT"), "migrations/sqlite/"+migration+".up.sql"))
				require.NoError(t, e)
				require.NoError(t, f.db.Exec(string(raw)).Error)
			}
			c := &javaCloneChunkControl{t: t, name: name, events: []any{}, ids: map[string]string{}, db: f.db}
			x := &javaFAQCloneControl{c: c}
			seed := []string{
				`INSERT INTO knowledges(id,tenant_id,knowledge_base_id,type,title,description,source,channel,parse_status,enable_status,embedding_model_id,metadata,custom_metadata,storage_size,updated_at,created_at) VALUES('src',7,'kb','faq','Source FAQ','Description','source','api','completed','enabled','source-model','{}','{}',5,'2026-01-01 00:00:00+00:00','2026-01-01 00:00:00+00:00'),('dst',7,'other','faq','Destination FAQ','','','web','completed','enabled','target-model','{}','{}',7,'2026-01-01 00:00:00+00:00','2026-01-01 00:00:00+00:00'),('protected',8,'foreign','faq','Protected','','','web','completed','enabled','','{}','{}',11,'2026-01-01 00:00:00+00:00','2026-01-01 00:00:00+00:00')`,
				`INSERT INTO knowledge_tags(id,tenant_id,knowledge_base_id,name,color) VALUES('tag-source',7,'kb','source','#abc'),('tag-target',7,'other','source','#def')`,
			}
			meta := `{"standard_question":" Hello? ","similar_questions":[" Similar ","Similar",""],"answers":[" Answer ","Answer"],"answer_strategy":"all","version":2,"extra":true}`
			for i, id := range []string{"src-a", "src-b", "dst-a", "dst-b", "protected-chunk"} {
				owner, kb, doc, tag, hash := 7, "kb", "src", "tag-source", "new"
				if strings.HasPrefix(id, "dst") {
					kb, doc, tag, hash = "other", "dst", "tag-target", "old"
				}
				if strings.HasSuffix(id, "b") {
					hash = "shared"
				}
				if id == "protected-chunk" {
					owner, kb, doc, tag, hash = 8, "foreign", "protected", "", "protected"
				}
				seed = append(seed, fmt.Sprintf(`INSERT INTO chunks(id,seq_id,tenant_id,knowledge_base_id,knowledge_id,chunk_type,content,chunk_index,start_at,end_at,is_enabled,flags,status,tag_id,content_hash,metadata,image_info,created_at,updated_at) VALUES('%s',%d,%d,'%s','%s','faq','body %s',%d,0,0,true,1,2,'%s','%s','%s','','2026-01-01 00:00:00+00:00','2026-01-01 00:00:00+00:00')`, id, i+1, owner, kb, doc, id, i, tag, hash, meta))
			}
			add := func(sql string) { seed = append(seed, sql) }
			repair := []string{}
			switch name {
			case "empty-source":
				add(`DELETE FROM knowledges WHERE id='src'`)
				add(`DELETE FROM chunks WHERE knowledge_id='src'`)
			case "empty-target", "container-create-failure", "source-transfer-metadata", "invalid-container-metadata":
				add(`DELETE FROM knowledges WHERE id='dst'`)
				add(`DELETE FROM chunks WHERE knowledge_id='dst'`)
			case "empty-chunks":
				add(`DELETE FROM chunks WHERE tenant_id=7`)
			case "already-sync":
				add(`UPDATE chunks SET content_hash='new' WHERE id='dst-a'`)
			case "unindexed-match":
				add(`UPDATE chunks SET status=1 WHERE id='dst-b'`)
			case "duplicate-target":
				add(`UPDATE chunks SET content_hash='shared' WHERE id='dst-a'`)
			case "empty-hashes":
				add(`UPDATE chunks SET content_hash='' WHERE tenant_id=7`)
			case "source-duplicates":
				add(`UPDATE chunks SET content_hash='new' WHERE id='src-b'`)
			case "status-enabled", "enabled-failure":
				add(`UPDATE chunks SET is_enabled=false WHERE id='src-b'`)
			case "status-flags":
				add(`UPDATE chunks SET flags=2 WHERE id='src-b'`)
			case "status-strategy", "metadata-write-failure":
				add(`UPDATE chunks SET metadata='{"answer_strategy":"random"}' WHERE id='src-b'`)
			case "status-tags", "tags-failure":
				add(`UPDATE chunks SET tag_id='' WHERE id='src-b'`)
			case "status-all", "sync-write-failure":
				add(`UPDATE chunks SET is_enabled=false,flags=2,tag_id='',metadata='{"answer_strategy":"random"}' WHERE id='src-b'`)
			case "status-invalid-source":
				add(`UPDATE chunks SET metadata='{bad' WHERE id='src-b'`)
			case "status-invalid-target":
				add(`UPDATE chunks SET metadata='{bad' WHERE id='dst-b'`)
				add(`UPDATE chunks SET metadata='{"answer_strategy":"random"}' WHERE id='src-b'`)
			case "invalid-metadata":
				add(`UPDATE chunks SET metadata='{"standard_question":2}' WHERE id='src-a'`)
			case "null-metadata":
				add(`UPDATE chunks SET metadata=NULL WHERE id='src-a'`)
			case "bad-parent":
				add(`UPDATE chunks SET parent_chunk_id='dst-a' WHERE id='src-a'`)
			case "bad-target-type":
				add(`UPDATE chunks SET chunk_type='text' WHERE id='dst-a'`)
			}
			if name == "source-transfer-metadata" {
				add(`UPDATE knowledges SET metadata='{"x":1e+03,"_knowledge_transfer":{"old":true}}' WHERE id='src'`)
			}
			if name == "invalid-container-metadata" {
				add(`UPDATE knowledges SET metadata='[]' WHERE id='src'`)
			}
			if name == "images" || strings.HasPrefix(name, "image-") {
				add(`UPDATE chunks SET image_info='[{"url":"local://7/first.png","original_url":"local://7/first.png"},{"url":"local://7/second.png"}]',content='![first](local://7/first.png)' WHERE knowledge_id='src'`)
			}
			if name == "image-cleanup-failure" {
				add(`CREATE TRIGGER failure BEFORE INSERT ON chunks WHEN NEW.id NOT LIKE 'src-%' BEGIN SELECT RAISE(FAIL,'controlled chunk create failure'); END`)
				repair = append(repair, `DROP TRIGGER failure`)
			}
			if name == "delete-sql-failure" {
				add(`CREATE TRIGGER failure BEFORE UPDATE ON chunks WHEN OLD.id='dst-a' AND NEW.deleted_at IS NOT NULL BEGIN SELECT RAISE(FAIL,'controlled chunk delete failure'); END`)
				repair = append(repair, `DROP TRIGGER failure`)
			}
			if name == "status-write-failure" {
				add(`CREATE TRIGGER failure BEFORE UPDATE ON chunks WHEN OLD.id NOT LIKE 'src-%' AND NEW.status=2 BEGIN SELECT RAISE(FAIL,'controlled status write failure'); END`)
				repair = append(repair, `DROP TRIGGER failure`)
			}
			if name == "sync-write-failure" {
				add(`CREATE TRIGGER failure BEFORE UPDATE ON chunks WHEN OLD.id='dst-b' AND NEW.status=0 BEGIN SELECT RAISE(FAIL,'controlled sync write failure'); END`)
				repair = append(repair, `DROP TRIGGER failure`)
			}
			if name == "metadata-write-failure" {
				add(`CREATE TRIGGER failure BEFORE UPDATE ON chunks WHEN OLD.id='dst-b' AND CAST(NEW.metadata AS TEXT) LIKE '%random%' BEGIN SELECT RAISE(FAIL,'controlled metadata write failure'); END`)
				repair = append(repair, `DROP TRIGGER failure`)
			}
			if name == "batch-51" || strings.HasPrefix(name, "late-") {
				add(`DELETE FROM chunks WHERE tenant_id=7`)
				for i := 0; i < 51; i++ {
					add(fmt.Sprintf(`INSERT INTO chunks(id,seq_id,tenant_id,knowledge_id,knowledge_base_id,chunk_type,content,chunk_index,start_at,end_at,is_enabled,flags,status,content_hash,metadata,image_info,created_at,updated_at) VALUES('src-%03d',%d,7,'src','kb','faq','batch %d',%d,0,0,true,1,2,'hash-%d','%s','[{"url":"local://7/first.png"}]','2026-01-01 00:00:00+00:00','2026-01-01 00:00:00+00:00')`, i, i+10, i, i, i, meta))
				}
			}
			for _, sql := range seed {
				require.NoError(t, f.db.Exec(sql).Error)
			}
			src, dst := f.kbs.values["kb"], f.kbs.values["other"]
			src.Type = "faq"
			dst.Type = "faq"
			dst.EmbeddingModelID = "target-model"
			if name == "question-only" || name == "separate-question-only" {
				dst.FAQConfig = &types.FAQConfig{IndexMode: types.FAQIndexModeQuestionOnly}
			}
			if strings.HasPrefix(name, "separate-") {
				if dst.FAQConfig == nil {
					dst.FAQConfig = &types.FAQConfig{}
				}
				dst.FAQConfig.QuestionIndexMode = types.FAQQuestionIndexModeSeparate
			}
			mini := miniredis.RunT(t)
			client := redis.NewClient(&redis.Options{Addr: mini.Addr(), MaxRetries: -1})
			t.Cleanup(func() { _ = client.Close() })
			client.AddHook(javaFAQCloneRedis{x})
			f.svc.repo = javaFAQCloneRepo{f.repo.KnowledgeRepository, x}
			f.svc.chunkRepo = javaFAQCloneChunks{f.chunkRepo.ChunkRepository, x}
			f.svc.modelService = javaFAQCloneModels{x: x}
			f.svc.retrieveEngine = javaFAQCloneRegistry{x: x}
			f.svc.fileSvc = javaCloneChunkFiles{c: c}
			f.svc.redisClient = client
			tenant := &types.Tenant{ID: 7, RetrieverEngines: types.RetrieverEngines{Engines: []types.RetrieverEngineParams{{RetrieverEngineType: types.PostgresRetrieverEngineType, RetrieverType: types.VectorRetrieverType}}}}
			ctx := context.WithValue(f.ctx, types.TenantInfoContextKey, tenant)
			ctx, e := access.WithKBTransferTask(ctx, src, dst, 7, access.KBTransferClone, "clone-task", false)
			require.NoError(t, e)
			queries := map[string]string{
				"documents": `SELECT id,tenant_id,knowledge_base_id,type,title,description,source,channel,parse_status,enable_status,embedding_model_id,COALESCE(CAST(metadata AS TEXT),'') AS metadata,COALESCE(CAST(custom_metadata AS TEXT),'') AS custom_metadata,storage_size,COALESCE(error_message,'') AS error_message,CASE WHEN processed_at IS NULL THEN 0 ELSE 1 END AS processed FROM knowledges`,
				"chunks":    `SELECT id,seq_id,tenant_id,knowledge_id,knowledge_base_id,COALESCE(tag_id,'') AS tag_id,content,chunk_index,start_at,end_at,is_enabled,flags,status,COALESCE(content_hash,'') AS content_hash,COALESCE(CAST(metadata AS TEXT),'') AS metadata,COALESCE(image_info,'') AS image_info_raw,COALESCE(source_content,'') AS source_content,COALESCE(parent_chunk_id,'') AS parent_chunk_id,COALESCE(index_status,'') AS index_status,CASE WHEN deleted_at IS NULL THEN 0 ELSE 1 END AS deleted FROM chunks`,
				"tags":      `SELECT id,seq_id,knowledge_base_id,name,color,sort_order FROM knowledge_tags`,
			}
			snapshots := []any{}
			for attempt := 1; attempt <= 2; attempt++ {
				x.attempt = attempt
				x.engines = 0
				x.sourceReads = 0
				c.events = []any{}
				progress := &types.KBCloneProgress{TaskID: "clone-task", SourceID: "kb", TargetID: "other", Status: types.KBCloneStatusProcessing}
				err := f.svc.cloneFAQKnowledgeBase(ctx, src, dst, progress, func(_ *types.KBCloneProgress, err error, message string) {
					c.event("error", map[string]any{"error": err.Error(), "message": message})
				})
				message := ""
				if err != nil {
					message = err.Error()
				}
				progress.UpdatedAt = 0
				tagRows := []map[string]any{}
				require.NoError(t, f.db.Raw("SELECT id,name FROM knowledge_tags").Scan(&tagRows).Error)
				for _, row := range tagRows {
					id := fmt.Sprint(row["id"])
					if id != "tag-source" && id != "tag-target" {
						c.ids[id] = "tag:" + fmt.Sprint(row["name"])
					}
				}
				snapshot := map[string]any{"error": message, "events": c.events, "progress": progress}
				for key, query := range queries {
					rows := []map[string]any{}
					require.NoError(t, f.db.Raw(query).Scan(&rows).Error)
					snapshot[key] = rows
				}
				snapshots = append(snapshots, javaCloneChunkNormalize(t, snapshot, c.ids))
				if attempt == 1 {
					for _, sql := range repair {
						require.NoError(t, f.db.Exec(sql).Error)
					}
				}
			}
			output = append(output, map[string]any{"name": name, "seed_statements": seed, "repair_statements": repair, "source": src, "target": dst, "queries": queries, "snapshots": snapshots})
		})
	}
	raw, e := json.MarshalIndent(output, "", "  ")
	require.NoError(t, e)
	require.NoError(t, os.WriteFile(os.Getenv("WEKNORA_FAQ_CLONE_ORACLE"), raw, 0600))
}
