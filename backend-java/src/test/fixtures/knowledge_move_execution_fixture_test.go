//go:build ignore

// Production moveOneKnowledge, markMoveItemFailed and real SQL; only external systems are controlled.
package service

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"reflect"
	"strings"
	"testing"
	"time"

	"github.com/Tencent/WeKnora/internal/application/access"
	"github.com/Tencent/WeKnora/internal/config"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"github.com/hibiken/asynq"
	"github.com/stretchr/testify/require"
	"gorm.io/gorm"
)

type javaMoveExecutionEngine struct {
	javaMovePlanEngine
	events *[]any
	fail   bool
}

func (e javaMoveExecutionEngine) ValidateKnowledgeIndexMove(context.Context) error { return nil }
func (e javaMoveExecutionEngine) MoveKnowledgeIndices(_ context.Context, source, target, id string, chunks []string, dim int, kind string) error {
	*e.events = append(*e.events, map[string]any{"kind": "move_indices", "source": source, "target": target, "id": id, "chunks": chunks, "dimensions": dim, "kb_type": kind})
	if e.fail {
		return errors.New("controlled index failure")
	}
	return nil
}
func (e javaMoveExecutionEngine) DeleteByKnowledgeIDList(_ context.Context, ids []string, dim int, kind string) error {
	*e.events = append(*e.events, map[string]any{"kind": "delete_indices", "ids": ids, "dimensions": dim, "kb_type": kind})
	if e.fail {
		return errors.New("controlled index failure")
	}
	return nil
}

type javaMoveExecutionGraph struct {
	interfaces.RetrieveGraphRepository
	events *[]any
	fail   bool
}

func (g javaMoveExecutionGraph) DelGraph(_ context.Context, namespaces []types.NameSpace) error {
	for _, n := range namespaces {
		*g.events = append(*g.events, map[string]any{"kind": "delete_graph", "source": n.KnowledgeBase, "id": n.Knowledge})
	}
	if g.fail {
		return errors.New("controlled graph failure")
	}
	return nil
}

type javaMoveExecutionWiki struct {
	interfaces.WikiPageRepository
	events *[]any
}

func (w javaMoveExecutionWiki) ListBySourceRef(_ context.Context, kb, id string) ([]*types.WikiPage, error) {
	*w.events = append(*w.events, map[string]any{"kind": "wiki_lookup", "source": kb, "id": id})
	return nil, errors.New("controlled wiki lookup failure")
}

type javaMoveExecutionPending struct {
	interfaces.TaskPendingOpsRepository
	events *[]any
}

func (p javaMoveExecutionPending) Enqueue(_ context.Context, op *types.TaskPendingOp) error {
	*p.events = append(*p.events, map[string]any{"kind": "wiki_ingest", "tenant": op.TenantID, "target": op.ScopeID, "id": op.DedupKey})
	return errors.New("controlled wiki pending failure")
}

type javaMoveExecutionQueue struct {
	events    *[]any
	outcome   string
	db        *gorm.DB
	mutations []string
}

func (q javaMoveExecutionQueue) Enqueue(task *asynq.Task, extra ...asynq.Option) (*asynq.TaskInfo, error) {
	var payload any
	if err := json.Unmarshal(task.Payload(), &payload); err != nil {
		return nil, err
	}
	event := map[string]any{"kind": "enqueue", "type": task.Type(), "payload": payload}
	embedded := reflect.ValueOf(task).Elem().FieldByName("opts")
	for i := 0; i < embedded.Len(); i++ {
		o := embedded.Index(i).Elem()
		switch o.Type().Name() {
		case "queueOption":
			event["queue"] = o.String()
		case "taskIDOption":
			event["id"] = o.String()
		case "retryOption":
			event["max_retries"] = o.Int()
		case "timeoutOption":
			event["timeout_seconds"] = o.Int() / int64(time.Second)
		case "retentionOption":
			event["retention_seconds"] = o.Int() / int64(time.Second)
		}
	}
	for _, o := range extra {
		switch o.Type() {
		case asynq.QueueOpt:
			event["queue"] = o.Value()
		case asynq.MaxRetryOpt:
			event["max_retries"] = o.Value()
		case asynq.TimeoutOpt:
			event["timeout_seconds"] = int64(o.Value().(time.Duration) / time.Second)
		case asynq.RetentionOpt:
			event["retention_seconds"] = int64(o.Value().(time.Duration) / time.Second)
		case asynq.TaskIDOpt:
			event["id"] = o.Value()
		}
	}
	*q.events = append(*q.events, event)
	for _, s := range q.mutations {
		if e := q.db.Exec(s).Error; e != nil {
			return nil, e
		}
	}
	switch q.outcome {
	case "failure":
		return nil, errors.New("controlled queue failure")
	case "conflict":
		return nil, asynq.ErrTaskIDConflict
	case "duplicate":
		return nil, asynq.ErrDuplicateTask
	}
	return &asynq.TaskInfo{ID: "accepted"}, nil
}

func TestJavaKnowledgeMoveExecutionExport(t *testing.T) {
	rows := []any{}
	variants := []string{"reuse", "reuse-no-model", "reuse-no-graph", "reuse-all-types", "reuse-deleted-chunk", "reuse-split-chunks", "reuse-engine-failure", "reuse-graph-failure", "reuse-chunk-failure", "reuse-tag-failure", "reuse-commit-failure", "claim-failure", "missing", "wrong-owner", "not-completed", "bad-metadata", "wrong-parent", "wrong-tag", "cross-store", "wrong-pair-model", "moving-retry", "moving-failed-retry", "done-retry", "reparse-manual", "reparse-file", "reparse-file-uppercase", "reparse-file-no-extension", "reparse-file-empty-extension", "reparse-question-default", "reparse-question-count", "reparse-vlm-new", "reparse-vlm-old", "reparse-timeout", "reparse-queue-failure", "reparse-conflict", "reparse-duplicate", "reparse-engine-failure", "reparse-graph-failure", "reparse-image-read-failure", "reparse-chunk-failure", "reparse-tag-failure", "reparse-quota-failure", "reparse-ack-failure", "reparse-worker-started", "reparse-worker-metadata", "reparse-worker-done", "reparse-worker-wrong-kb", "reparse-worker-wrong-state", "pending-retry", "pending-failed-retry", "pending-empty-file", "pending-invalid-manual", "done-reparse-retry", "failure-bookkeeping-processing", "failure-bookkeeping-pending", "failure-bookkeeping-worker", "failure-bookkeeping-done", "failure-bookkeeping-other-task"}
	variants = append(variants, "source-wiki-reuse", "source-wiki-reparse", "source-wiki-invalid-parent", "target-wiki-reuse", "target-wiki-reparse", "target-wiki-done")
	for _, name := range variants {
		t.Run(name, func(t *testing.T) {
			f := javaMoveFixture(t)
			pg := os.Getenv("WEKNORA_MOVE_PLAN_PG_DSN") != ""
			if !pg {
				require.NoError(t, f.db.AutoMigrate(&types.Tenant{}))
			}
			for _, table := range []string{"chunks", "knowledges", "knowledge_tags", "knowledge_tag_relations", "tenants"} {
				require.NoError(t, f.db.Exec("DELETE FROM "+table).Error)
			}
			source, target := f.kbs.values["kb"], f.kbs.values["other"]
			source.Type = "document"
			target.Type = "document"
			source.EmbeddingModelID = "embedding"
			target.EmbeddingModelID = "embedding"
			mode := "reuse_vectors"
			if strings.HasPrefix(name, "reparse-") || strings.HasPrefix(name, "pending-") || name == "done-reparse-retry" || strings.HasPrefix(name, "failure-bookkeeping-") {
				mode = "reparse"
			}
			if strings.HasSuffix(name, "wiki-reparse") {
				mode = "reparse"
			}
			if strings.HasPrefix(name, "source-wiki-") {
				source.IndexingStrategy.WikiEnabled = true
			}
			if strings.HasPrefix(name, "target-wiki-") {
				target.IndexingStrategy.WikiEnabled = true
			}
			sql := []string{
				`INSERT INTO tenants(id,name,business,storage_used) VALUES(7,'owner','test',9)`,
				`INSERT INTO knowledges(id,tenant_id,knowledge_base_id,type,parse_status,metadata,file_path,file_name,file_type,embedding_model_id,storage_size,custom_metadata,title,source,description,enable_status,error_message,updated_at) VALUES('doc',7,'kb','manual','completed','{"content":"# Original body","format":"markdown","version":2,"custom":"keep"}','original.txt','original.txt','txt','embedding',5,'{}','original','','source summary','enabled','','2026-01-01 00:00:00+00:00')`,
				`INSERT INTO knowledge_tags(id,tenant_id,knowledge_base_id,name) VALUES('tag',7,'kb','Tag')`,
				`INSERT INTO chunks(id,tenant_id,knowledge_id,knowledge_base_id,chunk_type,tag_id,parent_chunk_id,content,chunk_index,start_at,end_at) VALUES('chunk',7,'doc','kb','text','tag','','Original chunk',0,0,14)`,
				`INSERT INTO knowledge_tag_relations(knowledge_id,tag_id) VALUES('doc','tag')`,
			}
			add := func(s string) { sql = append(sql, s) }
			reset := []string{}
			queueMutations := []string{}
			bookkeeping := strings.HasPrefix(name, "failure-bookkeeping-")
			fault := func(table, event, condition, label string) {
				if pg {
					sql = append(sql, `CREATE OR REPLACE FUNCTION java_move_fault_fn() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION '`+label+`' USING ERRCODE='P0001'; END $$`, `CREATE TRIGGER java_move_fault BEFORE `+event+` ON `+table+` FOR EACH ROW `+condition+` EXECUTE FUNCTION java_move_fault_fn()`)
					reset = append(reset, `DROP TRIGGER java_move_fault ON `+table)
				} else {
					sql = append(sql, `CREATE TRIGGER java_move_fault BEFORE `+event+` ON `+table+` `+condition+` BEGIN SELECT RAISE(ABORT,'`+label+`'); END`)
					reset = append(reset, `DROP TRIGGER java_move_fault`)
				}
			}
			switch name {
			case "reuse-no-model":
				add(`UPDATE knowledges SET embedding_model_id=''`)
			case "reuse-all-types":
				for _, kind := range []string{"image_ocr", "image_caption", "summary", "question"} {
					add(`INSERT INTO chunks(id,tenant_id,knowledge_id,knowledge_base_id,chunk_type,tag_id,parent_chunk_id,content,chunk_index,start_at,end_at) VALUES('` + kind + `',7,'doc','kb','` + kind + `','tag','chunk','child',1,0,5)`)
				}
			case "reuse-deleted-chunk":
				add(`UPDATE chunks SET deleted_at='2026-01-01 00:00:00+00:00'`)
			case "reuse-split-chunks":
				add(`UPDATE chunks SET knowledge_base_id='other',tag_id=''`)
			case "missing":
				add(`DELETE FROM knowledges`)
			case "wrong-owner":
				add(`UPDATE knowledges SET tenant_id=8`)
			case "not-completed":
				add(`UPDATE knowledges SET parse_status='processing'`)
			case "bad-metadata":
				add(`UPDATE knowledges SET metadata='[]'`)
			case "wrong-parent", "source-wiki-invalid-parent":
				add(`UPDATE chunks SET parent_chunk_id='missing'`)
			case "wrong-tag":
				add(`UPDATE knowledge_tags SET knowledge_base_id='other'`)
			case "cross-store":
				value := "other-store"
				target.VectorStoreID = &value
			case "wrong-pair-model":
				target.EmbeddingModelID = "other-model"
			}
			if strings.HasPrefix(name, "reparse-file") || strings.HasPrefix(name, "reparse-question") || strings.HasPrefix(name, "reparse-vlm") || name == "reparse-timeout" {
				add(`UPDATE knowledges SET type='file'`)
			}
			switch name {
			case "reparse-file-uppercase":
				add(`UPDATE knowledges SET file_name='FILE.PDF'`)
			case "reparse-file-no-extension":
				add(`UPDATE knowledges SET file_name='filename'`)
			case "reparse-file-empty-extension":
				add(`UPDATE knowledges SET file_name='file.'`)
			case "reparse-question-default":
				target.QuestionGenerationConfig = &types.QuestionGenerationConfig{Enabled: true, QuestionCount: -1}
			case "reparse-question-count":
				target.QuestionGenerationConfig = &types.QuestionGenerationConfig{Enabled: true, QuestionCount: 7}
			case "reparse-vlm-new":
				target.VLMConfig = types.VLMConfig{Enabled: true, ModelID: "vision"}
			case "reparse-vlm-old":
				target.VLMConfig = types.VLMConfig{ModelName: "legacy", BaseURL: "http://example.invalid"}
			}
			state := knowledgeTransferState{TaskID: "transfer-task", Operation: access.KBTransferMove, SourceKB: "kb", TargetKB: "other", SourceID: "doc", Mode: mode, Phase: "moving"}
			if strings.HasPrefix(name, "moving-") || strings.HasPrefix(name, "pending-") || strings.HasPrefix(name, "done-") || name == "target-wiki-done" || bookkeeping {
				k := &types.Knowledge{Metadata: types.JSON(`{"content":"# Original body","format":"markdown","version":2,"custom":"keep"}`)}
				status := "processing"
				kb := "kb"
				if strings.HasPrefix(name, "pending-") || name == "done-reparse-retry" || name == "failure-bookkeeping-pending" || name == "failure-bookkeeping-worker" || name == "failure-bookkeeping-done" {
					kb = "other"
					status = "pending"
					state.Phase = "reparse_pending"
				}
				if strings.Contains(name, "failed-retry") {
					status = "failed"
				}
				if strings.HasPrefix(name, "done-") || name == "failure-bookkeeping-done" || name == "target-wiki-done" {
					kb = "other"
					status = "completed"
					state.Phase = "done"
				}
				if name == "failure-bookkeeping-worker" {
					status = "processing"
				}
				if name == "failure-bookkeeping-other-task" {
					state.TaskID = "other-task"
				}
				require.NoError(t, setTransferState(k, state))
				add(`UPDATE knowledges SET metadata='` + string(k.Metadata) + `',knowledge_base_id='` + kb + `',parse_status='` + status + `'`)
				if kb == "other" {
					add(`UPDATE knowledges SET storage_size=0`)
					add(`UPDATE tenants SET storage_used=4`)
					add(`UPDATE chunks SET knowledge_base_id='other',tag_id=''`)
				}
				if name == "pending-empty-file" {
					add(`UPDATE knowledges SET type='file',file_path=''`)
				}
				if name == "pending-invalid-manual" {
					add(`UPDATE knowledges SET metadata='{"version":"bad","_knowledge_transfer":` + mustJavaMoveState(t, state) + `}'`)
				}
			}
			switch name {
			case "reuse-chunk-failure", "reparse-chunk-failure":
				fault("chunks", "UPDATE", "", "controlled chunk failure")
			case "reuse-tag-failure", "reparse-tag-failure":
				fault("knowledge_tag_relations", "DELETE", "", "controlled tag failure")
			case "reuse-commit-failure":
				fault("knowledges", "UPDATE", "WHEN (NEW.knowledge_base_id='other')", "controlled checkpoint failure")
			case "claim-failure":
				fault("knowledges", "UPDATE", "WHEN (NEW.parse_status='processing')", "controlled checkpoint failure")
			case "reparse-quota-failure":
				fault("tenants", "UPDATE", "", "controlled quota failure")
			case "reparse-ack-failure":
				fault("knowledges", "UPDATE", "WHEN (OLD.knowledge_base_id='other')", "controlled acknowledgement failure")
			}
			if strings.HasPrefix(name, "reparse-worker-") {
				mutation := `UPDATE knowledges SET parse_status='processing',description='worker summary',pending_subtasks_count=3,metadata='{"content":"worker body","custom":"worker","_knowledge_transfer":` + mustJavaMoveState(t, knowledgeTransferState{TaskID: "transfer-task", Operation: access.KBTransferMove, SourceKB: "kb", TargetKB: "other", SourceID: "doc", Mode: "reparse", Phase: "reparse_pending"}) + `}' WHERE id='doc'`
				if name == "reparse-worker-done" {
					mutation = strings.ReplaceAll(mutation, "reparse_pending", "done")
				}
				if name == "reparse-worker-wrong-kb" {
					mutation = `UPDATE knowledges SET knowledge_base_id='kb' WHERE id='doc'`
				}
				if name == "reparse-worker-wrong-state" {
					mutation = `UPDATE knowledges SET metadata='{}' WHERE id='doc'`
				}
				queueMutations = append(queueMutations, mutation)
			}
			for _, s := range sql {
				require.NoError(t, f.db.Exec(s).Error)
			}
			events := []any{}
			engineFail := strings.Contains(name, "engine-failure")
			graphFail := strings.Contains(name, "graph-failure")
			if strings.HasPrefix(name, "source-wiki-") {
				f.svc.wikiRepo = javaMoveExecutionWiki{events: &events}
			}
			if strings.HasPrefix(name, "target-wiki-") {
				f.svc.taskPendingRepo = javaMoveExecutionPending{events: &events}
			}
			f.svc.retrieveEngine = javaMovePlanRegistry{engine: javaMoveExecutionEngine{events: &events, fail: engineFail}}
			f.svc.modelService = parentChildModelService{embedder: parentChildEmbedder{}}
			f.svc.graphEngine = javaMoveExecutionGraph{events: &events, fail: graphFail}
			if name == "reuse-no-graph" {
				f.svc.graphEngine = nil
			}
			outcome := ""
			if name == "reparse-queue-failure" {
				outcome = "failure"
			}
			if name == "reparse-conflict" {
				outcome = "conflict"
			}
			if name == "reparse-duplicate" {
				outcome = "duplicate"
			}
			f.svc.task = javaMoveExecutionQueue{events: &events, outcome: outcome, db: f.db, mutations: queueMutations}
			if name == "reparse-image-read-failure" {
				f.chunkRepo.imageErr = errors.New("controlled image read failure")
			}
			timeout := int64(7200)
			if name == "reparse-timeout" {
				timeout = 41
				f.svc.config = &config.Config{KnowledgeBase: &config.KnowledgeBaseConfig{DocumentProcessTimeout: 41 * time.Second}}
			}
			ctx, err := access.WithKBTransferTask(context.Background(), source, target, 7, access.KBTransferMove, "transfer-task", false)
			require.NoError(t, err)
			tenant := &types.Tenant{ID: 7, StorageUsed: 9, RetrieverEngines: types.RetrieverEngines{Engines: []types.RetrieverEngineParams{{RetrieverType: types.VectorRetrieverType, RetrieverEngineType: types.PostgresRetrieverEngineType}}}}
			ctx = context.WithValue(ctx, types.TenantInfoContextKey, tenant)
			stages := []any{}
			snapshot := func(err error) map[string]any {
				docs, chunks, relations := []map[string]any{}, []map[string]any{}, []map[string]any{}
				require.NoError(t, f.db.Raw(`SELECT id,tenant_id,knowledge_base_id,type,parse_status,CAST(metadata AS TEXT) AS metadata,file_path,file_name,file_type,embedding_model_id,storage_size,title,description,enable_status,COALESCE(error_message,'') AS error_message,pending_subtasks_count FROM knowledges ORDER BY id`).Scan(&docs).Error)
				require.NoError(t, f.db.Raw(`SELECT id,tenant_id,knowledge_id,knowledge_base_id,chunk_type,tag_id,parent_chunk_id,content,deleted_at IS NOT NULL AS deleted FROM chunks ORDER BY id`).Scan(&chunks).Error)
				require.NoError(t, f.db.Raw(`SELECT knowledge_id,tag_id FROM knowledge_tag_relations ORDER BY knowledge_id,tag_id`).Scan(&relations).Error)
				var stored types.Tenant
				require.NoError(t, f.db.First(&stored, 7).Error)
				require.Empty(t, f.tenants.adjustments, "resource cleanup must not deduct quota before destination CAS")
				// Copy events before appending a retry; don't retain the mutable slice backing array.
				copied := append([]any{}, events...)
				return map[string]any{"error": javaMoveError(err), "documents": docs, "chunks": chunks, "relations": relations, "storage_used": stored.StorageUsed, "events": copied}
			}
			if bookkeeping {
				f.svc.markMoveItemFailed(ctx, "doc", source, target, mode, errors.New("controlled final move failure"))
				stages = append(stages, snapshot(nil))
			} else {
				err = f.svc.moveOneKnowledge(ctx, "doc", source, target, mode)
				stages = append(stages, snapshot(err))
				if err != nil {
					f.svc.markMoveItemFailed(ctx, "doc", source, target, mode, err)
					stages = append(stages, snapshot(nil))
					for _, s := range reset {
						require.NoError(t, f.db.Exec(s).Error)
					}
					f.svc.retrieveEngine = javaMovePlanRegistry{engine: javaMoveExecutionEngine{events: &events}}
					if name != "reuse-no-graph" {
						f.svc.graphEngine = javaMoveExecutionGraph{events: &events}
					}
					f.svc.task = javaMoveExecutionQueue{events: &events, db: f.db}
					f.chunkRepo.imageErr = nil
					retryErr := f.svc.moveOneKnowledge(ctx, "doc", source, target, mode)
					stages = append(stages, snapshot(retryErr))
				} else {
					again := f.svc.moveOneKnowledge(ctx, "doc", source, target, mode)
					stages = append(stages, snapshot(again))
				}
			}
			rows = append(rows, map[string]any{"name": name, "source": source, "target": target, "tenant": tenant, "mode": mode, "seed_statements": sql, "reset_statements": reset, "queue_mutations": queueMutations, "timeout_seconds": timeout, "stages": stages})
		})
	}
	if t.Failed() {
		return
	}
	raw, err := json.MarshalIndent(rows, "", "  ")
	require.NoError(t, err)
	require.NoError(t, os.WriteFile(os.Getenv("WEKNORA_MOVE_EXECUTION_ORACLE"), raw, 0600))
}
func mustJavaMoveState(t *testing.T, s knowledgeTransferState) string {
	t.Helper()
	b, e := json.Marshal(s)
	require.NoError(t, e)
	return string(b)
}
