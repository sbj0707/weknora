//go:build ignore

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
	"github.com/Tencent/WeKnora/internal/application/repository"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"github.com/google/uuid"
	"github.com/stretchr/testify/require"
)

type javaCloneExecutionControl struct {
	c       *javaCloneChunkControl
	attempt int
	copies  map[string]int
}
type javaCloneExecutionRepo struct {
	interfaces.KnowledgeRepository
	x *javaCloneExecutionControl
}

func (r javaCloneExecutionRepo) CreateKnowledge(ctx context.Context, k *types.Knowledge) error {
	_, err := uuid.Parse(k.ID)
	require.NoError(r.x.c.t, err)
	r.x.copies[k.Source]++
	r.x.c.ids[k.ID] = fmt.Sprintf("copy:%s:%d", k.Source, r.x.copies[k.Source])
	r.x.c.event("create_document", map[string]any{"id": k.ID, "source": k.Source})
	if r.x.attempt == 1 && r.x.c.name == "copy-second-create-failure" && k.Source == "src-b" {
		return errors.New("controlled second document failure")
	}
	return r.KnowledgeRepository.CreateKnowledge(ctx, k)
}

type javaCloneExecutionChunks struct {
	interfaces.ChunkRepository
	x *javaCloneExecutionControl
}

func (r javaCloneExecutionChunks) CreateChunks(ctx context.Context, rows []*types.Chunk) error {
	for _, row := range rows {
		_, err := uuid.Parse(row.ID)
		require.NoError(r.x.c.t, err)
		r.x.c.ids[row.ID] = "chunk:" + r.x.c.ids[row.KnowledgeID]
	}
	r.x.c.event("create_chunks", map[string]any{"id": rows[0].KnowledgeID, "count": len(rows)})
	return r.ChunkRepository.CreateChunks(ctx, rows)
}
func (r javaCloneExecutionChunks) DeleteChunksByKnowledgeID(ctx context.Context, tenant uint64, id string) error {
	r.x.c.event("delete_chunks", map[string]any{"tenant": tenant, "id": id})
	return r.ChunkRepository.DeleteChunksByKnowledgeID(ctx, tenant, id)
}

type javaCloneExecutionEngine struct {
	javaCloneChunkEngine
	x *javaCloneExecutionControl
}

func (e javaCloneExecutionEngine) CopyIndices(ctx context.Context, src string, docs, chunks map[string]string, dst string, dim int, kind string) error {
	e.x.c.event("copy_indices", map[string]any{"source": src, "target": dst, "documents": docs, "chunks": chunks, "dimensions": dim, "kb_type": kind})
	if e.x.attempt == 1 && e.x.c.name == "copy-second-index-failure" {
		if _, ok := docs["src-b"]; ok {
			return errors.New("controlled second index failure")
		}
	}
	return nil
}
func (e javaCloneExecutionEngine) DeleteByKnowledgeIDList(ctx context.Context, ids []string, dim int, kind string) error {
	if err := e.javaCloneChunkEngine.DeleteByKnowledgeIDList(ctx, ids, dim, kind); err != nil {
		return err
	}
	if e.x.attempt == 1 && e.x.c.name == "delete-index-failure" {
		return errors.New("controlled deletion index failure")
	}
	return nil
}

type javaCloneExecutionRegistry struct {
	javaCloneChunkRegistry
	x *javaCloneExecutionControl
}

func (r javaCloneExecutionRegistry) GetRetrieveEngineService(kind types.RetrieverEngineType) (interfaces.RetrieveEngineService, error) {
	engine, err := r.javaCloneChunkRegistry.GetRetrieveEngineService(kind)
	if err != nil {
		return nil, err
	}
	return javaCloneExecutionEngine{engine.(javaCloneChunkEngine), r.x}, nil
}

type javaCloneExecutionGraph struct {
	interfaces.RetrieveGraphRepository
	x *javaCloneExecutionControl
}

func (g javaCloneExecutionGraph) DelGraph(_ context.Context, namespaces []types.NameSpace) error {
	ids := []string{}
	for _, ns := range namespaces {
		ids = append(ids, ns.Knowledge)
	}
	g.x.c.event("delete_graph", map[string]any{"ids": ids})
	if g.x.attempt == 1 && g.x.c.name == "delete-graph-failure" {
		return errors.New("controlled deletion graph failure")
	}
	return nil
}

type javaCloneExecutionFiles struct {
	javaCloneChunkFiles
	x *javaCloneExecutionControl
}

func (f javaCloneExecutionFiles) DeleteFile(ctx context.Context, path string) error {
	if err := f.javaCloneChunkFiles.DeleteFile(ctx, path); err != nil {
		return err
	}
	if f.x.attempt == 1 && f.x.c.name == "delete-file-failure" {
		return errors.New("controlled deletion file failure")
	}
	return nil
}

func TestJavaKnowledgeCloneExecutionExport(t *testing.T) {
	names := []string{"ordinary", "empty-all", "source-empty", "target-empty", "no-progress", "matching-done", "matching-unfinished", "hash-match", "duplicate-source-hash", "source-pending-preflight", "target-busy-preflight", "bad-source-chunk", "bad-target-chunk", "bad-target-metadata", "target-moved-after-plan", "target-deleted-after-plan", "source-mutated-after-plan", "source-chunk-rebound-after-plan", "delete-mark-second-failure", "delete-chunks-failure", "delete-graph-failure", "delete-documents-failure", "delete-tags-failure", "delete-file-failure", "delete-quota-failure", "delete-index-failure", "copy-second-create-failure", "copy-second-index-failure", "copy-second-final-failure", "delete-image-invalid", "target-moving", "target-marker-extra-invalid"}
	names = append(names, "target-pending-after-plan", "target-moving-after-plan", "target-invalid-marker-after-plan", "target-foreign-after-plan", "target-chunk-rebound-after-plan", "target-storage-after-plan", "source-deleted-after-plan", "source-file-copy", "delete-image-duplicate", "delete-image-typed-invalid", "delete-quota-clamp", "delete-zero-storage", "delete-vector-success", "delete-vector-whitespace")
	out := []any{}
	for _, name := range names {
		t.Run(name, func(t *testing.T) {
			f := javaCloneChunksFixture(t)
			require.NoError(t, f.db.AutoMigrate(&types.Tenant{}))
			c := &javaCloneChunkControl{t: t, name: name, events: []any{}, ids: map[string]string{}, db: f.db}
			x := &javaCloneExecutionControl{c: c, copies: map[string]int{}}
			sqls := []string{`INSERT INTO tenants(id,name,business,storage_used) VALUES(7,'Owner','clone',100)`,
				`INSERT INTO knowledges(id,tenant_id,knowledge_base_id,type,title,source,parse_status,embedding_model_id,file_hash,storage_size,metadata,custom_metadata,updated_at,created_at) VALUES('src-a',7,'kb','manual','A','src-a','completed','source-model','hash-a',9,'{}','{}','2026-01-01 00:00:00+00:00','2026-01-01 00:00:00+00:00'),('src-b',7,'kb','manual','B','src-b','completed','source-model','hash-b',11,'{}','{}','2026-01-01 00:00:00+00:00','2026-01-02 00:00:00+00:00'),('dst-a',7,'other','manual','old A','dst-a','completed','','old-a',5,'{}','{}','2026-01-01 00:00:00+00:00','2026-01-01 00:00:00+00:00'),('dst-b',7,'other','manual','old B','dst-b','completed','','old-b',6,'{}','{}','2026-01-01 00:00:00+00:00','2026-01-02 00:00:00+00:00'),('protected',8,'foreign','manual','protected','protected','completed','','',17,'{}','{}','2026-01-01 00:00:00+00:00','2026-01-01 00:00:00+00:00')`,
				`UPDATE knowledges SET file_path='local://7/'||id||'/source.pdf' WHERE knowledge_base_id='other'`,
				`INSERT INTO knowledge_tags(id,tenant_id,knowledge_base_id,name,color) VALUES('tag-source',7,'kb','source','#abc'),('tag-target',7,'other','old','#def')`,
				`INSERT INTO knowledge_tag_relations(knowledge_id,tag_id) VALUES('src-a','tag-source'),('dst-a','tag-target'),('dst-b','tag-target')`,
			}
			add := func(sql string) { sqls = append(sqls, sql) }
			for i, id := range []string{"src-a", "src-b", "dst-a", "dst-b"} {
				kb, tag := "kb", "tag-source"
				if strings.HasPrefix(id, "dst-") {
					kb = "other"
					tag = "tag-target"
				}
				add(fmt.Sprintf(`INSERT INTO chunks(id,seq_id,tenant_id,knowledge_id,knowledge_base_id,chunk_type,content,chunk_index,start_at,end_at,is_enabled,tag_id,image_info,created_at,updated_at) VALUES('c-%s',%d,7,'%s','%s','text','body %s',0,0,6,true,'%s','[{"url":"local://7/exports/%s.png"}]','2026-01-01 00:00:00+00:00','2026-01-01 00:00:00+00:00')`, id, i+1, id, kb, id, tag, id))
			}
			state := func(id, phase string) {
				add(fmt.Sprintf(`UPDATE knowledges SET metadata='{"_knowledge_transfer":{"task_id":"clone-task","operation":"clone","source_kb":"kb","target_kb":"other","source_id":"%s","phase":"%s"}}' WHERE id='dst-a'`, id, phase))
			}
			repair := []string{}
			mutations := []string{}
			switch name {
			case "empty-all":
				add(`DELETE FROM chunks`)
				add(`DELETE FROM knowledges WHERE tenant_id=7`)
			case "source-empty":
				add(`DELETE FROM chunks WHERE knowledge_base_id='kb'`)
				add(`DELETE FROM knowledges WHERE knowledge_base_id='kb'`)
			case "target-empty":
				add(`DELETE FROM chunks WHERE knowledge_base_id='other'`)
				add(`DELETE FROM knowledges WHERE knowledge_base_id='other'`)
			case "matching-done":
				state("src-a", "done")
			case "matching-unfinished":
				state("src-a", "cloning")
				add(`UPDATE knowledges SET parse_status='failed' WHERE id='dst-a'`)
			case "hash-match":
				add(`UPDATE knowledges SET file_hash='hash-a' WHERE id='dst-a'`)
			case "duplicate-source-hash":
				add(`UPDATE knowledges SET file_hash='hash-a' WHERE id IN ('src-b','dst-a')`)
			case "source-pending-preflight":
				add(`UPDATE knowledges SET parse_status='pending' WHERE id='src-b'`)
			case "target-busy-preflight":
				add(`UPDATE knowledges SET parse_status='processing' WHERE id='dst-b'`)
			case "bad-source-chunk":
				add(`UPDATE chunks SET knowledge_base_id='other' WHERE id='c-src-b'`)
			case "bad-target-chunk":
				add(`UPDATE chunks SET parent_chunk_id='c-src-a' WHERE id='c-dst-b'`)
			case "bad-target-metadata":
				add(`UPDATE knowledges SET metadata='[]' WHERE id='dst-b'`)
			case "target-moving":
				add(`UPDATE knowledges SET metadata='{"_knowledge_transfer":{"operation":"move","phase":"moving"}}' WHERE id='dst-a'`)
			case "target-marker-extra-invalid":
				add(`UPDATE knowledges SET metadata='{"_knowledge_transfer":{"task_id":9}}' WHERE id='dst-a'`)
			case "target-moved-after-plan":
				mutations = append(mutations, `UPDATE knowledges SET knowledge_base_id='kb' WHERE id='dst-a'`)
			case "target-deleted-after-plan":
				mutations = append(mutations, `UPDATE knowledges SET deleted_at='2026-02-01 00:00:00+00:00' WHERE knowledge_base_id='other'`)
			case "source-mutated-after-plan":
				mutations = append(mutations, `UPDATE knowledges SET parse_status='pending' WHERE id='src-b'`)
			case "source-chunk-rebound-after-plan":
				mutations = append(mutations, `UPDATE chunks SET knowledge_base_id='other' WHERE id='c-src-b'`)
			case "target-pending-after-plan":
				mutations = append(mutations, `UPDATE knowledges SET parse_status='pending' WHERE id='dst-b'`)
			case "target-moving-after-plan":
				mutations = append(mutations, `UPDATE knowledges SET metadata='{"_knowledge_transfer":{"operation":"move","phase":"moving"}}' WHERE id='dst-b'`)
			case "target-invalid-marker-after-plan":
				mutations = append(mutations, `UPDATE knowledges SET metadata='{"_knowledge_transfer":{"phase":9}}' WHERE id='dst-b'`)
			case "target-foreign-after-plan":
				mutations = append(mutations, `UPDATE knowledges SET tenant_id=8 WHERE id='dst-b'`)
			case "target-chunk-rebound-after-plan":
				mutations = append(mutations, `UPDATE chunks SET knowledge_base_id='kb' WHERE id='c-dst-b'`)
			case "target-storage-after-plan":
				mutations = append(mutations, `UPDATE knowledges SET storage_size=30 WHERE id='dst-b'`)
			case "source-deleted-after-plan":
				mutations = append(mutations, `UPDATE knowledges SET deleted_at='2026-02-01 00:00:00+00:00' WHERE id='src-b'`)
			case "source-file-copy":
				add(`UPDATE knowledges SET file_path='local://7/'||id||'/source.pdf' WHERE knowledge_base_id='kb'`)
			case "delete-image-duplicate":
				add(`UPDATE chunks SET image_info='[{"url":"local://7/shared.png","original_url":"local://7/original.png"},{"url":"local://7/shared.png"}]' WHERE knowledge_base_id='other'`)
			case "delete-image-typed-invalid":
				add(`UPDATE chunks SET image_info='[{"url":"local://7/shared.png","start_pos":"invalid"}]' WHERE knowledge_base_id='other'`)
			case "delete-quota-clamp":
				add(`UPDATE tenants SET storage_used=4 WHERE id=7`)
			case "delete-zero-storage":
				add(`UPDATE knowledges SET storage_size=0 WHERE knowledge_base_id='other'`)
			case "delete-vector-success":
				add(`UPDATE knowledges SET embedding_model_id='target-model' WHERE knowledge_base_id='other'`)
			case "delete-vector-whitespace":
				add(`UPDATE knowledges SET embedding_model_id='  ' WHERE knowledge_base_id='other'`)
			case "delete-mark-second-failure":
				add(`CREATE TRIGGER failure BEFORE UPDATE ON knowledges WHEN OLD.id='dst-b' AND NEW.parse_status='deleting' BEGIN SELECT RAISE(FAIL,'controlled mark failure'); END`)
				repair = append(repair, `DROP TRIGGER failure`)
			case "delete-chunks-failure":
				add(`CREATE TRIGGER failure BEFORE UPDATE ON chunks WHEN OLD.knowledge_id LIKE 'dst-%' AND NEW.deleted_at IS NOT NULL BEGIN SELECT RAISE(FAIL,'controlled chunk deletion failure'); END`)
				repair = append(repair, `DROP TRIGGER failure`)
			case "delete-documents-failure":
				add(`CREATE TRIGGER failure BEFORE UPDATE ON knowledges WHEN OLD.id LIKE 'dst-%' AND NEW.deleted_at IS NOT NULL BEGIN SELECT RAISE(FAIL,'controlled document deletion failure'); END`)
				repair = append(repair, `DROP TRIGGER failure`)
			case "delete-tags-failure":
				add(`CREATE TRIGGER failure BEFORE DELETE ON knowledge_tag_relations WHEN OLD.knowledge_id LIKE 'dst-%' BEGIN SELECT RAISE(FAIL,'controlled relation deletion failure'); END`)
				repair = append(repair, `DROP TRIGGER failure`)
			case "delete-quota-failure":
				add(`CREATE TRIGGER failure BEFORE UPDATE ON tenants WHEN NEW.storage_used<OLD.storage_used BEGIN SELECT RAISE(FAIL,'controlled deletion quota failure'); END`)
				repair = append(repair, `DROP TRIGGER failure`)
			case "delete-index-failure":
				add(`UPDATE knowledges SET embedding_model_id='target-model' WHERE knowledge_base_id='other'`)
			case "copy-second-final-failure":
				add(`CREATE TRIGGER failure BEFORE UPDATE ON knowledges WHEN NEW.id NOT LIKE 'src-%' AND NEW.source='src-b' AND NEW.parse_status='completed' BEGIN SELECT RAISE(FAIL,'controlled second final failure'); END`)
				repair = append(repair, `DROP TRIGGER failure`)
			case "delete-image-invalid":
				add(`UPDATE chunks SET image_info='{bad' WHERE knowledge_base_id='other'`)
			}
			for _, sql := range sqls {
				require.NoError(t, f.db.Exec(sql).Error)
			}
			f.kbs.values["kb"].Type = "document"
			f.kbs.values["other"].Type = "document"
			f.kbs.values["other"].EmbeddingModelID = "target-model"
			f.svc.repo = javaCloneExecutionRepo{f.repo.KnowledgeRepository, x}
			f.svc.chunkRepo = javaCloneExecutionChunks{f.chunkRepo.ChunkRepository, x}
			f.svc.tenantRepo = repository.NewTenantRepository(f.db)
			f.svc.fileSvc = javaCloneExecutionFiles{javaCloneChunkFiles{c: c}, x}
			f.svc.graphEngine = javaCloneExecutionGraph{x: x}
			f.svc.modelService = javaCloneChunkModels{c: c}
			f.svc.retrieveEngine = javaCloneExecutionRegistry{javaCloneChunkRegistry{c: c}, x}
			tenant := &types.Tenant{ID: 7, RetrieverEngines: types.RetrieverEngines{Engines: []types.RetrieverEngineParams{{RetrieverEngineType: types.PostgresRetrieverEngineType, RetrieverType: types.VectorRetrieverType}}}}
			ctx := context.WithValue(f.ctx, types.TenantInfoContextKey, tenant)
			ctx, err := access.WithKBTransferTask(ctx, f.kbs.values["kb"], f.kbs.values["other"], 7, access.KBTransferClone, "clone-task", false)
			require.NoError(t, err)
			queries := map[string]string{
				"documents": `SELECT id,tenant_id,knowledge_base_id,type,source,parse_status,COALESCE(error_message,'') AS error_message,file_hash,COALESCE(file_path,'') AS file_path,storage_size,COALESCE(CAST(metadata AS TEXT),'') AS metadata,CASE WHEN deleted_at IS NULL THEN 0 ELSE 1 END AS deleted FROM knowledges`,
				"chunks":    `SELECT id,seq_id,tenant_id,knowledge_id,knowledge_base_id,tag_id,content,chunk_type,COALESCE(image_info,'') AS image_info_raw,CASE WHEN deleted_at IS NULL THEN 0 ELSE 1 END AS deleted FROM chunks`,
				"tags":      `SELECT id,seq_id,knowledge_base_id,name,color,sort_order FROM knowledge_tags`,
				"relations": `SELECT knowledge_id,tag_id FROM knowledge_tag_relations ORDER BY knowledge_id,tag_id`,
				"tenants":   `SELECT id,storage_used FROM tenants ORDER BY id`,
			}
			snapshots := []any{}
			for attempt := 1; attempt <= 2; attempt++ {
				x.attempt = attempt
				c.events = []any{}
				progress := func(done, total int) {
					c.event("progress", map[string]any{"done": done, "total": total})
					if attempt == 1 && done == 0 {
						for _, sql := range mutations {
							require.NoError(t, f.db.Exec(sql).Error)
						}
					}
				}
				if name == "no-progress" {
					progress = nil
				}
				err := f.svc.executeKnowledgeClone(ctx, f.kbs.values["kb"], f.kbs.values["other"], progress)
				message := ""
				if err != nil {
					message = err.Error()
				}
				tags := []map[string]any{}
				require.NoError(t, f.db.Raw("SELECT id,name FROM knowledge_tags").Scan(&tags).Error)
				for _, tag := range tags {
					id := fmt.Sprint(tag["id"])
					if id != "tag-source" && id != "tag-target" {
						c.ids[id] = "tag:" + fmt.Sprint(tag["name"])
					}
				}
				snap := map[string]any{"events": c.events, "error": message}
				for key, q := range queries {
					rows := []map[string]any{}
					require.NoError(t, f.db.Raw(q).Scan(&rows).Error)
					snap[key] = rows
				}
				snapshots = append(snapshots, javaCloneChunkNormalize(t, snap, c.ids))
				if attempt == 1 {
					for _, sql := range repair {
						require.NoError(t, f.db.Exec(sql).Error)
					}
				}
			}
			out = append(out, map[string]any{"name": name, "seed_statements": sqls, "progress_mutations": mutations, "repair_statements": repair, "source": f.kbs.values["kb"], "target": f.kbs.values["other"], "queries": queries, "snapshots": snapshots})
		})
	}
	raw, err := json.MarshalIndent(out, "", "  ")
	require.NoError(t, err)
	require.NoError(t, os.WriteFile(os.Getenv("WEKNORA_CLONE_EXECUTION_ORACLE"), raw, 0600))
}
