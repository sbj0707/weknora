//go:build ignore

// Actual private cloneKnowledge plus real GORM document/chunk/checkpoint repositories.
package service

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"strings"
	"testing"

	"github.com/Tencent/WeKnora/internal/application/access"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"github.com/google/uuid"
	"github.com/stretchr/testify/require"
)

type javaCloneDocumentRepo struct {
	interfaces.KnowledgeRepository
	c     *javaCloneChunkControl
	reads int
}

func (r *javaCloneDocumentRepo) CreateKnowledge(ctx context.Context, k *types.Knowledge) error {
	_, e := uuid.Parse(k.ID)
	require.NoError(r.c.t, e)
	r.c.ids[k.ID] = "dst"
	r.c.event("create_document", map[string]any{"id": k.ID, "kb": k.KnowledgeBaseID, "parse_status": k.ParseStatus, "enable_status": k.EnableStatus, "embedding_model_id": k.EmbeddingModelID, "storage_size": k.StorageSize, "file_path": k.FilePath})
	if r.c.name == "document-create-before" || r.c.name == "document-cleanup-failure" || r.c.name == "document-create-before-read-uncertain" {
		return errors.New("controlled document create failure")
	}
	if e := r.KnowledgeRepository.CreateKnowledge(ctx, k); e != nil {
		return e
	}
	updates := map[string]string{
		"created-wrong-kb": "knowledge_base_id='kb'", "created-wrong-tenant": "tenant_id=8", "created-completed": "parse_status='completed'",
		"created-missing":             "deleted_at='2026-02-01 00:00:00+00:00'",
		"created-other-marker":        `metadata='{"_knowledge_transfer":{"task_id":"another","operation":"clone","source_kb":"kb","target_kb":"other","source_id":"src","phase":"cloning"}}'`,
		"document-ack-other-marker":   `metadata='{"_knowledge_transfer":{"task_id":"another","phase":"cloning"}}'`,
		"document-ack-null-marker":    `metadata='{"_knowledge_transfer":null}'`,
		"document-ack-invalid-marker": `metadata='{"_knowledge_transfer":7}'`,
		"document-ack-missing-marker": `metadata='{}'`,
		"document-ack-completed":      "parse_status='completed'",
	}
	if update := updates[r.c.name]; update != "" {
		require.NoError(r.c.t, r.c.db.Exec("UPDATE knowledges SET "+update+" WHERE id=?", k.ID).Error)
	}
	if r.c.name == "document-create-ack" || r.c.name == "document-create-uncertain" || strings.HasPrefix(r.c.name, "document-ack-") || r.c.name == "failed-bookkeeping-sql-failure" {
		return errors.New("controlled document create failure")
	}
	return nil
}
func (r *javaCloneDocumentRepo) GetKnowledgeByID(ctx context.Context, tenant uint64, id string) (*types.Knowledge, error) {
	r.reads++
	if r.c.name == "document-create-uncertain" || r.c.name == "document-create-before-read-uncertain" || r.c.name == "reload-always-failure" || r.c.name == "reload-once-failure" && r.reads == 1 {
		return nil, errors.New("controlled document read failure")
	}
	return r.KnowledgeRepository.GetKnowledgeByID(ctx, tenant, id)
}

type javaCloneDocumentKB struct {
	interfaces.KnowledgeBaseService
	f     *documentWriteFixture
	name  string
	reads int
}

func (r *javaCloneDocumentKB) GetKnowledgeBaseByID(ctx context.Context, id string) (*types.KnowledgeBase, error) {
	r.reads++
	if r.name == "source-kb-reload-failure" && r.reads == 2 {
		return nil, errors.New("controlled source KB failure")
	}
	return r.f.kbs.GetKnowledgeBaseByID(ctx, id)
}

type javaCloneDocumentFiles struct{ javaCloneChunkFiles }

func (f javaCloneDocumentFiles) DeleteFile(ctx context.Context, path string) error {
	if f.c.name == "document-cleanup-failure" {
		f.c.event("delete_image", map[string]any{"path": path})
		return errors.New("controlled image cleanup failure")
	}
	return f.javaCloneChunkFiles.DeleteFile(ctx, path)
}

type javaCloneDocumentEngine struct{ javaCloneChunkEngine }

func (e javaCloneDocumentEngine) CopyIndices(ctx context.Context, src string, docs, chunks map[string]string, dst string, dim int, kind string) error {
	if err := e.javaCloneChunkEngine.CopyIndices(ctx, src, docs, chunks, dst, dim, kind); err != nil {
		return err
	}
	updates := map[string]string{
		"final-completed": "parse_status='completed'", "final-timestamp": "updated_at='2026-03-01 00:00:00+00:00'",
		"final-other-marker": `metadata='{"_knowledge_transfer":{"task_id":"another","phase":"cloning"}}',updated_at='2026-03-01 00:00:00+00:00'`,
		"final-wrong-kb":     "knowledge_base_id='kb'", "final-deleted": "deleted_at='2026-03-01 00:00:00+00:00'",
	}
	if update := updates[e.c.name]; update != "" {
		for _, id := range docs {
			require.NoError(e.c.t, e.c.db.Exec("UPDATE knowledges SET "+update+" WHERE id=?", id).Error)
		}
	}
	return nil
}

type javaCloneDocumentRegistry struct{ javaCloneChunkRegistry }

func (r javaCloneDocumentRegistry) GetRetrieveEngineService(kind types.RetrieverEngineType) (interfaces.RetrieveEngineService, error) {
	engine, err := r.javaCloneChunkRegistry.GetRetrieveEngineService(kind)
	if err != nil {
		return nil, err
	}
	return javaCloneDocumentEngine{engine.(javaCloneChunkEngine)}, nil
}

func TestJavaKnowledgeCloneDocumentExport(t *testing.T) {
	names := []string{"ordinary", "no-file", "no-model", "no-chunks", "all-fields", "channel-default", "url", "profile-empty", "profile-null", "profile-topics", "custom-null", "custom-array", "custom-lexical", "metadata-null", "metadata-array", "metadata-previous-marker", "source-pending", "source-nil", "source-empty-id", "source-empty-kb", "source-zero-tenant", "source-wrong-tenant", "wrong-parent", "wrong-chunk-kb", "wrong-tag", "zero-storage", "negative-storage", "tenant-missing", "tenant-missing-zero", "source-kb-reload-failure", "image-read-failure", "image-buffer-failure", "image-save-failure", "image-close-failure", "document-create-before", "document-create-ack", "document-create-uncertain", "document-cleanup-failure", "reload-once-failure", "reload-always-failure", "created-wrong-kb", "created-wrong-tenant", "created-completed", "created-missing", "created-other-marker", "create-before", "create-ack-failure", "engine-failure", "model-failure", "index-failure", "index-cleanup-failure", "chunk-cleanup-failure", "image-cleanup-failure", "image-invalid-json", "final-completed", "final-timestamp", "final-other-marker", "final-wrong-kb", "final-deleted", "final-sql-failure", "quota-sql-failure"}
	out := []any{}
	names = append(names, "profile-absent", "custom-empty", "metadata-empty", "metadata-malformed", "document-ack-other-marker", "document-ack-null-marker", "document-ack-invalid-marker", "document-ack-missing-marker", "document-ack-completed", "document-create-before-read-uncertain", "document-create-sql-failure", "failed-bookkeeping-sql-failure", "final-and-bookkeeping-sql-failure", "raw-file-matches-image")
	for _, name := range names {
		t.Run(name, func(t *testing.T) {
			f := javaCloneChunksFixture(t)
			require.Equal(t, "sqlite", f.db.Dialector.Name(), "This fixture currently verifies SQLite only")
			require.NoError(t, f.db.Exec("CREATE TABLE tenants(id INTEGER PRIMARY KEY,name TEXT,business TEXT,storage_used INTEGER,deleted_at DATETIME)").Error)
			c := &javaCloneChunkControl{t: t, name: name, events: []any{}, ids: map[string]string{}, sourceIDs: []string{"c000"}, db: f.db}
			sqls := []string{
				`INSERT INTO tenants(id,name,business,storage_used) VALUES(7,'clone','clone',100)`,
				`INSERT INTO knowledges(id,tenant_id,knowledge_base_id,type,title,description,source,channel,parse_status,pending_subtasks_count,summary_status,profile,enable_status,embedding_model_id,file_name,folder_path,file_type,file_size,file_hash,file_path,storage_size,metadata,custom_metadata,last_faq_import_result,created_at,updated_at,processed_at,error_message) VALUES('src',7,'kb','file','title','description','original','api','completed',4,'generated','{"gist":"<gist>","topics":[" topic "," topic ","三"],"doc_type":"文档","typical_question":"why?"}','disabled','source-model','source.pdf','docs/spec','pdf',37,'file-hash','local://7/src/source.pdf',9,'{"content":"manual text","version":2}','{"project":"clone"}','{"total":3}','2026-01-01 00:00:00+00:00','2026-01-01 00:00:00+00:00','2026-01-02 00:00:00+00:00','old error')`,
				`INSERT INTO knowledge_tags(id,tenant_id,knowledge_base_id,name,color,sort_order) VALUES('tag',7,'kb','source tag','#abc',4)`,
				`INSERT INTO knowledge_tag_relations(knowledge_id,tag_id) VALUES('src','tag')`,
				`INSERT INTO chunks(id,seq_id,tenant_id,knowledge_id,knowledge_base_id,tag_id,content,source_content,content_revision,index_status,last_editor_id,chunk_index,is_enabled,flags,status,start_at,end_at,chunk_type,metadata,image_info,context_header,created_at,updated_at) VALUES('c000',1,7,'src','kb','tag','![img](local://7/exports/a.png)','parser',4,'processing','editor',0,false,0,2,2,9,'text','{"k":7}','[{"url":"local://7/exports/a.png","original_url":"local://7/exports/a.png","caption":"cap"}]','context','2026-01-01 00:00:00+00:00','2026-01-01 00:00:00+00:00')`,
			}
			add := func(s string) { sqls = append(sqls, s) }
			switch name {
			case "no-file":
				add(`UPDATE knowledges SET file_path='',type='manual'`)
			case "no-chunks":
				add(`DELETE FROM chunks`)
				c.sourceIDs = []string{}
			case "channel-default":
				add(`UPDATE knowledges SET channel=''`)
			case "url":
				add(`UPDATE knowledges SET type='url',source='https://example.test/page'`)
			case "profile-empty":
				add(`UPDATE knowledges SET profile='{}'`)
			case "profile-null":
				add(`UPDATE knowledges SET profile='null'`)
			case "profile-topics":
				add(`UPDATE knowledges SET profile='{"topics":["",null,"a","a","b","c","d","e"],"unknown":7,"gist":"  keep  "}'`)
			case "custom-null":
				add(`UPDATE knowledges SET custom_metadata='null'`)
			case "custom-array":
				add(`UPDATE knowledges SET custom_metadata='[1,true,"<&>"]'`)
			case "custom-lexical":
				add(`UPDATE knowledges SET custom_metadata=' { "n":9007199254740993, "x":1e-7 } '`)
			case "metadata-null":
				add(`UPDATE knowledges SET metadata='null'`)
			case "metadata-array":
				add(`UPDATE knowledges SET metadata='[]'`)
			case "metadata-previous-marker":
				add(`UPDATE knowledges SET metadata='{"_knowledge_transfer":{"task_id":"old","phase":"done"},"n":9007199254740993}'`)
			case "source-pending":
				add(`UPDATE knowledges SET parse_status='processing'`)
			case "wrong-parent":
				add(`UPDATE chunks SET parent_chunk_id='foreign'`)
			case "wrong-chunk-kb":
				add(`UPDATE chunks SET knowledge_base_id='other'`)
			case "wrong-tag":
				add(`UPDATE knowledge_tags SET knowledge_base_id='other'`)
			case "zero-storage", "tenant-missing-zero":
				add(`UPDATE knowledges SET storage_size=0`)
			case "negative-storage":
				add(`UPDATE knowledges SET storage_size=-200`)
			case "image-invalid-json":
				add(`UPDATE chunks SET image_info='{bad'`)
			case "final-sql-failure", "final-and-bookkeeping-sql-failure":
				add(`CREATE TRIGGER fail_final BEFORE UPDATE ON knowledges WHEN OLD.id!='src' AND NEW.parse_status='completed' BEGIN SELECT RAISE(FAIL,'controlled final update failure'); END`)
			case "document-create-sql-failure":
				add(`CREATE TRIGGER fail_create BEFORE INSERT ON knowledges WHEN NEW.id!='src' BEGIN SELECT RAISE(FAIL,'controlled document SQL create failure'); END`)
			case "raw-file-matches-image":
				add(`UPDATE knowledges SET file_path='local://7/exports/a.png'`)
			case "quota-sql-failure":
				add(`CREATE TRIGGER fail_quota BEFORE UPDATE ON tenants BEGIN SELECT RAISE(FAIL,'controlled quota update failure'); END`)
			case "all-fields":
				add(`UPDATE chunks SET source_locators='[{"kind":"pdf","page":2}]',pre_chunk_id='c000',next_chunk_id='c000'`)
			}
			if strings.HasPrefix(name, "tenant-missing") {
				add(`DELETE FROM tenants`)
			}
			if name == "failed-bookkeeping-sql-failure" || name == "final-and-bookkeeping-sql-failure" {
				add(`CREATE TRIGGER fail_bookkeeping BEFORE UPDATE ON knowledges WHEN OLD.id!='src' AND NEW.parse_status='failed' BEGIN SELECT RAISE(FAIL,'controlled bookkeeping failure'); END`)
			}
			for _, s := range sqls {
				require.NoError(t, f.db.Exec(s).Error)
			}
			src, e := f.repo.GetKnowledgeByID(f.ctx, 7, "src")
			require.NoError(t, e)
			overrides := map[string]any{}
			switch name {
			case "profile-absent":
				src.Profile = nil
				overrides["profile"] = nil
			case "custom-empty":
				src.CustomMetadata = nil
				overrides["custom_metadata"] = ""
			case "metadata-empty":
				src.Metadata = nil
				overrides["metadata"] = ""
			case "metadata-malformed":
				src.Metadata = types.JSON(`{bad`)
				overrides["metadata"] = `{bad`
			case "source-nil":
				src = nil
			case "source-empty-id":
				src.ID = ""
				overrides["id"] = ""
			case "source-empty-kb":
				src.KnowledgeBaseID = ""
				overrides["knowledge_base_id"] = ""
			case "source-zero-tenant":
				src.TenantID = 0
				overrides["tenant_id"] = 0
			case "source-wrong-tenant":
				src.TenantID = 8
				overrides["tenant_id"] = 8
			}
			f.kbs.values["kb"].Type = "document"
			f.kbs.values["other"].Type = "document"
			f.kbs.values["other"].EmbeddingModelID = "target-model"
			if name == "no-model" {
				f.kbs.values["other"].EmbeddingModelID = ""
			}
			f.svc.repo = &javaCloneDocumentRepo{KnowledgeRepository: f.repo.KnowledgeRepository, c: c}
			f.svc.kbService = &javaCloneDocumentKB{KnowledgeBaseService: f.kbs, f: f, name: name}
			f.svc.chunkRepo = javaCloneChunkRepo{ChunkRepository: f.chunkRepo.ChunkRepository, c: c}
			f.svc.fileSvc = javaCloneDocumentFiles{javaCloneChunkFiles{c: c}}
			f.svc.modelService = javaCloneChunkModels{c: c}
			f.svc.retrieveEngine = javaCloneDocumentRegistry{javaCloneChunkRegistry{c: c}}
			tenant := &types.Tenant{ID: 7, RetrieverEngines: types.RetrieverEngines{Engines: []types.RetrieverEngineParams{{RetrieverEngineType: types.PostgresRetrieverEngineType, RetrieverType: types.VectorRetrieverType}}}}
			ctx := context.WithValue(f.ctx, types.TenantInfoContextKey, tenant)
			ctx, e = access.WithKBTransferTask(ctx, f.kbs.values["kb"], f.kbs.values["other"], 7, access.KBTransferClone, "clone-task", false)
			require.NoError(t, e)
			err := f.svc.cloneKnowledge(ctx, src, f.kbs.values["other"])
			message := ""
			if err != nil {
				message = err.Error()
			}
			var tags []map[string]any
			require.NoError(t, f.db.Raw("SELECT id,name FROM knowledge_tags").Scan(&tags).Error)
			for _, tag := range tags {
				id := fmt.Sprint(tag["id"])
				if id != "tag" {
					_, e := uuid.Parse(id)
					require.NoError(t, e)
					c.ids[id] = "tag:" + fmt.Sprint(tag["name"])
				}
			}
			queries := map[string]string{
				"documents": `SELECT id,tenant_id,knowledge_base_id,type,title,COALESCE(description,'') AS description,source,channel,parse_status,pending_subtasks_count,summary_status,COALESCE(CAST(profile AS TEXT),'') AS profile_raw,typeof(profile) AS profile_storage_type,enable_status,embedding_model_id,file_name,folder_path,file_type,file_size,file_hash,file_path,storage_size,COALESCE(CAST(metadata AS TEXT),'') AS metadata,typeof(metadata) AS metadata_storage_type,COALESCE(CAST(custom_metadata AS TEXT),'') AS custom_metadata_raw,typeof(custom_metadata) AS custom_storage_type,COALESCE(CAST(last_faq_import_result AS TEXT),'') AS last_faq_import_result,CASE WHEN processed_at IS NULL THEN 0 ELSE 1 END AS processed,COALESCE(error_message,'') AS error_message,CASE WHEN deleted_at IS NULL THEN 0 ELSE 1 END AS deleted,CASE WHEN created_at=updated_at THEN 1 ELSE 0 END AS same_timestamp FROM knowledges`,
				"chunks":    `SELECT id,seq_id,tenant_id,knowledge_id,knowledge_base_id,COALESCE(tag_id,'') AS tag_id,content,COALESCE(source_content,'') AS source_content,content_revision,index_status,last_editor_id,chunk_index,CASE WHEN is_enabled THEN 1 ELSE 0 END AS is_enabled,flags,status,start_at,end_at,COALESCE(pre_chunk_id,'') AS pre_chunk_id,COALESCE(next_chunk_id,'') AS next_chunk_id,chunk_type,COALESCE(parent_chunk_id,'') AS parent_chunk_id,COALESCE(CAST(metadata AS TEXT),'') AS metadata,COALESCE(image_info,'') AS image_info_raw,COALESCE(context_header,'') AS context_header,COALESCE(CAST(source_locators AS TEXT),'') AS source_locators,COALESCE(CAST(relation_chunks AS TEXT),'') AS relation_chunks,CASE WHEN deleted_at IS NULL THEN 0 ELSE 1 END AS deleted,CASE WHEN created_at=updated_at THEN 1 ELSE 0 END AS same_timestamp FROM chunks`,
				"tags":      `SELECT id,seq_id,tenant_id,knowledge_base_id,name,color,sort_order FROM knowledge_tags`,
				"relations": `SELECT knowledge_id,tag_id FROM knowledge_tag_relations ORDER BY knowledge_id,tag_id`,
				"tenants":   `SELECT id,storage_used FROM tenants ORDER BY id`,
			}
			snap := map[string]any{"error": message, "events": c.events}
			for key, q := range queries {
				rows := []map[string]any{}
				require.NoError(t, f.db.Raw(q).Scan(&rows).Error)
				snap[key] = rows
			}
			out = append(out, map[string]any{"name": name, "seed_statements": sqls, "source": f.kbs.values["kb"], "target": f.kbs.values["other"], "source_override": overrides, "source_nil": src == nil, "queries": queries, "snapshot": javaCloneChunkNormalize(t, snap, c.ids)})
		})
	}
	raw, e := json.MarshalIndent(out, "", "  ")
	require.NoError(t, e)
	require.NoError(t, os.WriteFile(os.Getenv("WEKNORA_CLONE_DOCUMENT_ORACLE"), raw, 0600))
}
