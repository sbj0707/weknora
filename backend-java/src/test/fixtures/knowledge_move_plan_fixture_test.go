//go:build ignore

// Export production unexported move preflight and checkpoint behavior through a Go test overlay.
// Run with backend-java/scripts/export-move-plan-oracle.py; no production Go file is modified.
package service

import (
	"context"
	"encoding/json"
	"errors"
	"github.com/Tencent/WeKnora/internal/application/access"
	repo "github.com/Tencent/WeKnora/internal/application/repository"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"github.com/stretchr/testify/require"
	"gorm.io/driver/postgres"
	"gorm.io/gorm"
	"net/url"
	"os"
	"strings"
	"testing"
	"time"
)

type javaMovePlanEngine struct {
	interfaces.RetrieveEngineService
	calls *int
	fail  bool
}

func (e javaMovePlanEngine) EngineType() types.RetrieverEngineType {
	return types.PostgresRetrieverEngineType
}
func (e javaMovePlanEngine) Support() []types.RetrieverType {
	return []types.RetrieverType{types.VectorRetrieverType, types.KeywordsRetrieverType}
}
func (e javaMovePlanEngine) ValidateKnowledgeIndexMove(context.Context) error {
	*e.calls++
	if e.fail {
		return errors.New("controlled engine validation failure")
	}
	return nil
}
func (e javaMovePlanEngine) MoveKnowledgeIndices(context.Context, string, string, string, []string, int, string) error {
	return nil
}

type javaMovePlanRegistry struct {
	interfaces.RetrieveEngineRegistry
	engine interfaces.RetrieveEngineService
}

func (r javaMovePlanRegistry) GetRetrieveEngineService(types.RetrieverEngineType) (interfaces.RetrieveEngineService, error) {
	return r.engine, nil
}
func javaMoveError(e error) string {
	if e == nil {
		return ""
	}
	return e.Error()
}

func javaMoveFixture(t *testing.T) *documentWriteFixture {
	t.Helper()
	f := newDocumentWriteFixture(t)
	dsn := os.Getenv("WEKNORA_MOVE_PLAN_PG_DSN")
	if dsn == "" {
		return f
	}
	parsed, err := url.Parse(dsn)
	require.NoError(t, err)
	require.Equal(t, "127.0.0.1", parsed.Hostname())
	require.Equal(t, "/weknora_move_plan_parity", parsed.Path)
	db, err := gorm.Open(postgres.New(postgres.Config{DSN: dsn}), &gorm.Config{})
	require.NoError(t, err)
	sqlDB, err := db.DB()
	require.NoError(t, err)
	sqlDB.SetMaxOpenConns(1)
	t.Cleanup(func() { require.NoError(t, sqlDB.Close()) })
	for _, statement := range []string{`DROP TRIGGER IF EXISTS block_transfer_quota ON tenants`, `DROP TRIGGER IF EXISTS truncate_checkpoint_time ON knowledges`, `TRUNCATE tenants,knowledges,chunks,knowledge_tags,knowledge_tag_relations RESTART IDENTITY CASCADE`} {
		require.NoError(t, db.Exec(statement).Error)
	}
	require.NoError(t, db.Create(&types.Knowledge{ID: "doc", TenantID: 7, KnowledgeBaseID: "kb", Title: "original", Type: types.KnowledgeTypeManual, ParseStatus: "draft", StorageSize: 5}).Error)
	f.db = db
	f.repo.KnowledgeRepository = repo.NewKnowledgeRepository(db)
	f.chunkRepo.ChunkRepository = repo.NewChunkRepository(db)
	f.svc.tagRepo = repo.NewKnowledgeTagRepository(db)
	return f
}

func TestJavaKnowledgeMovePlanExport(t *testing.T) {
	out := map[string]any{}
	compat := []any{}
	metadataCases := []any{}
	plans := []any{}
	checkpoints := []any{}
	// Exercise the production pair gate without applying any normalization beyond Go JSON decoding.
	pairs := []struct{ name, source, target, tenant, mode string }{
		{"empty", "{}", "{}", "{}", "reuse_vectors"},
		{"ordinary", `{"id":"source"}`, `{"id":"target"}`, `{}`, "reuse_vectors"},
		{"null-source", "null", `{"id":"target"}`, `{}`, "reparse"},
		{"null-target", `{"id":"source"}`, "null", `{}`, "reparse"},
		{"different-type", `{"id":"source","type":"faq"}`, `{"id":"target","type":"document"}`, `{}`, "reparse"},
		{"different-model", `{"id":"source","embedding_model_id":"a"}`, `{"id":"target","embedding_model_id":"b"}`, `{}`, "reparse"},
		{"invalid-mode", `{"id":"source"}`, `{"id":"target"}`, `{}`, "other"},
	}
	stores := []string{"null", `""`, `"store-a"`, `"store-b"`, `" "`}
	for _, a := range stores {
		for _, b := range stores {
			for _, mode := range []string{"reparse", "reuse_vectors"} {
				pairs = append(pairs, struct{ name, source, target, tenant, mode string }{"vector/" + a + "/" + b + "/" + mode, `{"id":"source","vector_store_id":` + a + `}`, `{"id":"target","vector_store_id":` + b + `}`, `{}`, mode})
			}
		}
	}
	storage := []string{`{}`, `{"storage_backend_id":"one"}`, `{"storage_backend_id":" one "}`, `{"storage_backend_id":"two"}`, `{"storage_provider_config":{"provider":" COS "}}`, `{"storage_provider_config":{"provider":"__pending_env__"},"cos_config":{"provider":"LOCAL"}}`, `{"cos_config":{"provider":"minio"}}`}
	for i, a := range storage {
		for j, b := range storage {
			for _, tenant := range []string{`{}`, `{"default_storage_backend_id":"one","storage_engine_config":{"default_provider":"local"}}`} {
				pairs = append(pairs, struct{ name, source, target, tenant, mode string }{strings.Join([]string{"storage", string(rune('a' + i)), string(rune('a' + j)), tenant}, "/"), `{"id":"source",` + strings.TrimPrefix(a, "{"), `{"id":"target",` + strings.TrimPrefix(b, "{"), tenant, "reparse"})
			}
		}
	}
	for _, c := range pairs {
		// Remove a trailing comma for the empty config object.
		c.source = strings.ReplaceAll(c.source, `,}`, `}`)
		c.target = strings.ReplaceAll(c.target, `,}`, `}`)
		var s, d *types.KnowledgeBase
		var tenant *types.Tenant
		require.NoError(t, json.Unmarshal([]byte(c.source), &s))
		require.NoError(t, json.Unmarshal([]byte(c.target), &d))
		require.NoError(t, json.Unmarshal([]byte(c.tenant), &tenant))
		err := access.ValidateKBTransferCompatibility(s, d, access.KBTransferMove, c.mode, tenant)
		compat = append(compat, map[string]any{"name": c.name, "source": s, "target": d, "tenant": tenant, "mode": c.mode, "error": javaMoveError(err)})
	}
	for _, raw := range []string{"", `null`, `{}`, `[]`, `true`, `1`, `"text"`, `{"_knowledge_transfer":null}`, `{"_knowledge_transfer":[]}`, `{"_knowledge_transfer":{"task_id":4}}`, `{"_knowledge_transfer":{"phase":false,"task_id":2}}`, `{"_knowledge_transfer":{"task_id":"first","task_id":null,"PHASE":"moving","operation":"move"}}`, `{"_knowledge_transfer":{"task_id":3,"task_id":"later"}}`, `{"_knowledge_transfer":{},"_knowledge_transfer":null}`, `{"_knowledge_transfer":{"wiki_chunk_ids":["a",null,"b"]}}`, `{"_knowledge_transfer":{"wiki_chunk_ids":[1]}}`, `{"_knowledge_transfer":{"mode":"reuse_vectors","wiki_summary":"<>&"}}`, `{"precise":9007199254740993,"huge":1e1000,"tiny":1e-1000,"negative":-0,"nested": {"b":2, "a":1}}`, `{"\ud83d\ude00":"<a>","\ue000":1,"_knowledge_transfer":{"phase":"done"}}`, `{"content":"a b\n<>&","_knowledge_transfer":{"operation":"move","phase":"moving"}}`, `{"x":1, "x":2}`, `{"_knowledge_transfer":{`, `{} true`} {
		k := &types.Knowledge{Metadata: types.JSON(raw)}
		state, readErr := transferState(k)
		newState := knowledgeTransferState{TaskID: "task", Operation: access.KBTransferMove, SourceKB: "source", TargetKB: "target", SourceID: "doc", Mode: "reparse", WikiChunkIDs: []string{"a", "b"}, WikiSummary: "<summary>", Phase: "moving"}
		writeErr := setTransferState(k, newState)
		written := ""
		if writeErr == nil {
			written = string(k.Metadata)
		}
		metadataCases = append(metadataCases, map[string]any{"raw": raw, "state": state, "read_error": javaMoveError(readErr), "new_state": newState, "written": written, "write_error": javaMoveError(writeErr)})
	}
	variants := []string{"valid", "duplicate", "empty-list", "empty-id", "space-id", "padded-id", "missing", "other-doc", "foreign-doc", "deleted-doc", "not-completed", "file-reparse", "no-file-reparse", "manual-reparse", "manual-empty", "manual-number", "manual-null", "manual-case-fold", "bad-metadata", "bad-state", "unfinished", "other-task-done", "other-task-moving", "matching-moving", "matching-failed", "matching-done", "matching-done-wrong-kb", "matching-pending", "matching-invalid-phase", "matching-processing-target", "split-children", "wrong-chunk-kb", "wrong-parent", "deleted-parent", "valid-parent", "valid-tag", "wrong-tag", "missing-tag", "blank-tag", "foreign-tag", "deleted-chunk", "image-caption", "image-ocr", "summary", "question", "different-doc-model", "engine-validation", "engine-failure", "two-model-docs", "pair-model", "pair-type", "cross-store", "cross-storage"}
	for _, variant := range variants {
		t.Run("plan/"+variant, func(t *testing.T) {
			f := javaMoveFixture(t)
			source, target := f.kbs.values["kb"], f.kbs.values["other"]
			source.Type = "document"
			target.Type = "document"
			sql := []string{
				`DELETE FROM chunks`, `DELETE FROM knowledges`, `DELETE FROM knowledge_tags`,
				`INSERT INTO knowledges(id,tenant_id,knowledge_base_id,type,parse_status,metadata,file_path,embedding_model_id,storage_size,custom_metadata,title,source) VALUES('doc',7,'kb','manual','completed',NULL,'','',5,'{}','',''),('second',7,'kb','file','completed',NULL,'original.txt','',3,'{}','',''),('other-doc',7,'other','file','completed',NULL,'other.txt','',0,'{}','','')`,
				`INSERT INTO chunks(id,tenant_id,knowledge_id,knowledge_base_id,chunk_type,tag_id,parent_chunk_id,content,chunk_index,start_at,end_at) VALUES('chunk',7,'doc','kb','text','','','',0,0,0),('second-chunk',7,'second','kb','text','','','',0,0,0),('other-chunk',7,'other-doc','other','text','','','',0,0,0)`,
				`INSERT INTO knowledge_tags(id,tenant_id,knowledge_base_id,name) VALUES('tag',7,'kb','Tag')`,
			}
			add := func(statement string) { sql = append(sql, statement) }
			ids := []string{"doc"}
			mode := "reuse_vectors"
			switch variant {
			case "duplicate":
				ids = []string{"second", "doc", "second", "doc"}
			case "empty-list":
				ids = nil
			case "empty-id":
				ids = []string{"doc", ""}
			case "space-id":
				ids = []string{"doc", "\u2003"}
			case "padded-id":
				ids = []string{" doc "}
			case "missing":
				ids = []string{"doc", "missing"}
			case "other-doc":
				ids = []string{"doc", "other-doc"}
			case "foreign-doc":
				add(`UPDATE knowledges SET tenant_id=8 WHERE id='doc'`)
			case "deleted-doc":
				add(`UPDATE knowledges SET deleted_at='2026-01-01 00:00:00+00:00' WHERE id='doc'`)
			case "not-completed":
				add(`UPDATE knowledges SET parse_status='processing' WHERE id='doc'`)
			case "file-reparse":
				mode = "reparse"
				ids = []string{"second"}
			case "no-file-reparse":
				mode = "reparse"
				add(`UPDATE knowledges SET type='file' WHERE id='doc'`)
			case "manual-reparse":
				mode = "reparse"
				add(`UPDATE knowledges SET metadata='{"content":"body"}' WHERE id='doc'`)
			case "manual-empty":
				mode = "reparse"
				add(`UPDATE knowledges SET metadata='{"content":""}' WHERE id='doc'`)
			case "manual-number":
				mode = "reparse"
				add(`UPDATE knowledges SET metadata='{"content":"body","version":1.1}' WHERE id='doc'`)
			case "manual-null":
				mode = "reparse"
				add(`UPDATE knowledges SET metadata='null' WHERE id='doc'`)
			case "manual-case-fold":
				mode = "reparse"
				add(`UPDATE knowledges SET metadata='{"CONTENT":"body","content":null}' WHERE id='doc'`)
			case "bad-metadata":
				add(`UPDATE knowledges SET metadata='[]' WHERE id='doc'`)
			case "bad-state":
				add(`UPDATE knowledges SET metadata='{"_knowledge_transfer":{"task_id":3}}' WHERE id='doc'`)
			case "unfinished":
				add(`UPDATE knowledges SET metadata='{"_knowledge_transfer":{"phase":"moving"}}' WHERE id='doc'`)
			case "other-task-done", "other-task-moving":
				phase := "done"
				if variant == "other-task-moving" {
					phase = "moving"
				}
				add(`UPDATE knowledges SET metadata='{"_knowledge_transfer":{"task_id":"other-task","phase":"` + phase + `"}}' WHERE id='doc'`)
			case "matching-moving", "matching-failed", "matching-done", "matching-done-wrong-kb", "matching-pending", "matching-invalid-phase", "matching-processing-target", "split-children":
				phase, kb, status := "moving", "kb", "processing"
				switch variant {
				case "matching-failed":
					status = "failed"
				case "matching-done":
					phase, kb, status = "done", "other", "completed"
				case "matching-done-wrong-kb":
					phase = "done"
				case "matching-pending":
					phase, kb, status = "reparse_pending", "other", "pending"
				case "matching-invalid-phase":
					phase = "bad"
				case "matching-processing-target":
					kb = "other"
				case "split-children":
					add(`UPDATE chunks SET knowledge_base_id='other' WHERE id='chunk'`)
				}
				add(`UPDATE knowledges SET parse_status='` + status + `',knowledge_base_id='` + kb + `',metadata='{"_knowledge_transfer":{"task_id":"transfer-task","operation":"move","source_kb":"kb","target_kb":"other","source_id":"doc","mode":"reuse_vectors","phase":"` + phase + `"}}' WHERE id='doc'`)
				if kb == "other" {
					add(`UPDATE chunks SET knowledge_base_id='other' WHERE id='chunk'`)
				}
			case "wrong-chunk-kb":
				add(`UPDATE chunks SET knowledge_base_id='third' WHERE id='chunk'`)
			case "wrong-parent":
				add(`UPDATE chunks SET parent_chunk_id='other-chunk' WHERE id='chunk'`)
			case "deleted-parent":
				add(`INSERT INTO chunks(id,tenant_id,knowledge_id,knowledge_base_id,deleted_at,content,chunk_index,start_at,end_at) VALUES('parent',7,'doc','kb','2026-01-01 00:00:00+00:00','',0,0,0)`)
				add(`UPDATE chunks SET parent_chunk_id='parent' WHERE id='chunk'`)
			case "valid-parent":
				add(`INSERT INTO chunks(id,tenant_id,knowledge_id,knowledge_base_id,content,chunk_index,start_at,end_at) VALUES('parent',7,'doc','kb','',0,0,0)`)
				add(`UPDATE chunks SET parent_chunk_id='parent' WHERE id='chunk'`)
			case "valid-tag", "wrong-tag", "missing-tag", "blank-tag", "foreign-tag":
				add(`UPDATE chunks SET tag_id='tag' WHERE id='chunk'`)
				switch variant {
				case "wrong-tag":
					add(`UPDATE knowledge_tags SET knowledge_base_id='other' WHERE id='tag'`)
				case "missing-tag":
					add(`DELETE FROM knowledge_tags`)
				case "blank-tag":
					add(`UPDATE knowledge_tags SET id=' '`)
					add(`UPDATE chunks SET tag_id=' ' WHERE id='chunk'`)
				case "foreign-tag":
					add(`UPDATE knowledge_tags SET tenant_id=8`)
				}
			case "deleted-chunk":
				add(`UPDATE chunks SET knowledge_base_id='third',deleted_at='2026-01-01 00:00:00+00:00' WHERE id='chunk'`)
			case "image-caption", "image-ocr", "summary", "question":
				kind := strings.ReplaceAll(variant, "-", "_")
				add(`UPDATE chunks SET chunk_type='` + kind + `',knowledge_base_id='third' WHERE id='chunk'`)
			case "different-doc-model":
				add(`UPDATE knowledges SET embedding_model_id='different' WHERE id='doc'`)
			case "engine-validation", "engine-failure", "two-model-docs":
				source.EmbeddingModelID = "model"
				target.EmbeddingModelID = "model"
				add(`UPDATE knowledges SET embedding_model_id='model' WHERE id IN ('doc','second')`)
				if variant == "two-model-docs" {
					ids = []string{"second", "doc"}
				}
			case "pair-model":
				target.EmbeddingModelID = "different"
			case "pair-type":
				target.Type = "faq"
			case "cross-store":
				value := "other-store"
				target.VectorStoreID = &value
			case "cross-storage":
				value := "other-storage"
				target.StorageBackendID = &value
			}
			for _, statement := range sql {
				require.NoError(t, f.db.Exec(statement).Error)
			}
			calls := 0
			f.svc.retrieveEngine = javaMovePlanRegistry{engine: javaMovePlanEngine{calls: &calls, fail: variant == "engine-failure"}}
			ctx, err := access.WithKBTransferTask(context.Background(), source, target, 7, access.KBTransferMove, "transfer-task", false)
			require.NoError(t, err)
			tenant := &types.Tenant{ID: 7, RetrieverEngines: types.RetrieverEngines{Engines: []types.RetrieverEngineParams{{RetrieverType: types.VectorRetrieverType, RetrieverEngineType: types.PostgresRetrieverEngineType}}}}
			ctx = context.WithValue(ctx, types.TenantInfoContextKey, tenant)
			snapshot := func() string {
				data := map[string]any{}
				for _, table := range []string{"knowledges", "chunks", "knowledge_tags", "knowledge_tag_relations"} {
					var rows []map[string]any
					require.NoError(t, f.db.Raw("SELECT * FROM "+table).Scan(&rows).Error)
					data[table] = rows
				}
				raw, err := json.Marshal(data)
				require.NoError(t, err)
				return string(raw)
			}
			beforeSQL := snapshot()
			rows, err := f.svc.planKnowledgeMove(ctx, source, target, ids, mode)
			ordered := []string{}
			for _, r := range rows {
				ordered = append(ordered, r.ID)
			}
			require.Equal(t, beforeSQL, snapshot(), "Go preflight may not mutate SQL")
			plans = append(plans, map[string]any{"name": variant, "source": source, "target": target, "tenant": tenant, "mode": mode, "ids": ids, "seed_statements": sql, "result_ids": ordered, "error": javaMoveError(err), "engine_calls": calls})
		})
	}
	for _, variant := range []string{"claim", "move", "subtract", "clamp", "add", "same-size", "seed-iso-z", "seed-iso-offset", "seed-no-zone", "seed-date", "seed-nanos", "seed-offset", "seed-null", "concurrent-body", "stale-time", "stale-kb", "stale-status", "deleted", "missing-tenant", "quota-sql-failure", "timestamp-round-trip"} {
		t.Run("checkpoint/"+variant, func(t *testing.T) {
			f := javaMoveFixture(t)
			if f.db.Dialector.Name() == "sqlite" {
				require.NoError(t, f.db.AutoMigrate(&types.Tenant{}))
			}
			require.NoError(t, f.db.Exec(`INSERT INTO tenants(id,name,business,storage_used) VALUES(7,'owner','test',9)`).Error)
			require.NoError(t, f.db.Exec(`UPDATE knowledges SET updated_at='2026-01-01T00:00:00Z',parse_status='completed',metadata='{"content":"keep"}',description='old',storage_size=5 WHERE id='doc'`).Error)
			require.NoError(t, f.db.Model(&types.Knowledge{}).Where("id=?", "doc").Update("updated_at", time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)).Error)
			if value, ok := map[string]string{"seed-iso-z": "2026-01-01T00:00:00Z", "seed-iso-offset": "2026-01-01T00:00:00+00:00", "seed-no-zone": "2026-01-01 00:00:00", "seed-date": "2026-01-01", "seed-nanos": "2026-01-01 00:00:00.123456789+00:00", "seed-offset": "2026-01-01 08:00:00+08:00"}[variant]; ok {
				require.NoError(t, f.db.Exec("UPDATE knowledges SET updated_at=? WHERE id='doc'", value).Error)
			}
			if variant == "seed-null" {
				require.NoError(t, f.db.Exec("UPDATE knowledges SET updated_at=NULL WHERE id='doc'").Error)
			}
			var seedUpdated *string
			require.NoError(t, f.db.Raw(`SELECT CAST(updated_at AS TEXT) FROM knowledges WHERE id='doc'`).Scan(&seedUpdated).Error)
			before, err := f.repo.GetKnowledgeByID(context.Background(), 7, "doc")
			require.NoError(t, err)
			after := *before
			after.ParseStatus = "processing"
			mutations := []string{}
			delta := int64(0)
			target := "kb"
			switch variant {
			case "move":
				target = "other"
				after.KnowledgeBaseID = target
			case "subtract":
				delta = -5
			case "clamp":
				delta = -5
				mutations = append(mutations, `UPDATE tenants SET storage_used=2 WHERE id=7`)
			case "add":
				delta = 4
			case "same-size":
				delta = 0
			case "concurrent-body":
				mutations = append(mutations, `UPDATE knowledges SET title='new title',custom_metadata='{"user":"new"}',pending_subtasks_count=9,file_path='retained' WHERE id='doc'`)
			case "stale-time":
				mutations = append(mutations, `UPDATE knowledges SET updated_at='2026-01-02T00:00:00Z' WHERE id='doc'`)
			case "stale-kb":
				mutations = append(mutations, `UPDATE knowledges SET knowledge_base_id='other' WHERE id='doc'`)
			case "stale-status":
				mutations = append(mutations, `UPDATE knowledges SET parse_status='failed' WHERE id='doc'`)
			case "deleted":
				mutations = append(mutations, `UPDATE knowledges SET deleted_at='2026-01-02T00:00:00Z' WHERE id='doc'`)
			case "missing-tenant":
				delta = -5
				mutations = append(mutations, `DELETE FROM tenants WHERE id=7`)
			case "quota-sql-failure":
				delta = -5
				mutations = append(mutations, `CREATE TRIGGER block_transfer_quota BEFORE UPDATE OF storage_used ON tenants BEGIN SELECT RAISE(ABORT,'controlled quota failure'); END`)
			case "timestamp-round-trip":
				mutations = append(mutations, `CREATE TRIGGER truncate_checkpoint_time AFTER UPDATE ON knowledges BEGIN UPDATE knowledges SET updated_at=strftime('%Y-%m-%d %H:%M:%S',NEW.updated_at)||'+00:00' WHERE id=NEW.id; END`)
			}
			if f.db.Dialector.Name() == "postgres" {
				if variant == "quota-sql-failure" {
					mutations = []string{`CREATE OR REPLACE FUNCTION java_move_quota_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'controlled quota failure'; END $$; CREATE TRIGGER block_transfer_quota BEFORE UPDATE OF storage_used ON tenants FOR EACH ROW EXECUTE FUNCTION java_move_quota_failure()`}
				}
				if variant == "timestamp-round-trip" {
					mutations = []string{`CREATE OR REPLACE FUNCTION java_move_round_time() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN NEW.updated_at := date_trunc('second', NEW.updated_at); RETURN NEW; END $$; CREATE TRIGGER truncate_checkpoint_time BEFORE UPDATE ON knowledges FOR EACH ROW EXECUTE FUNCTION java_move_round_time()`}
				}
			}
			after.StorageSize += delta
			for _, s := range mutations {
				require.NoError(t, f.db.Exec(s).Error)
			}
			err = f.repo.UpdateKnowledgeForTransfer(context.Background(), before, &after)
			firstError := javaMoveError(err)
			roundTrip := false
			if err == nil {
				var current types.Knowledge
				require.NoError(t, f.db.Where("id='doc'").First(&current).Error)
				roundTrip = after.UpdatedAt.Equal(current.UpdatedAt)
				final := after
				final.ParseStatus = "completed"
				err = f.repo.UpdateKnowledgeForTransfer(context.Background(), &after, &final)
				require.NoError(t, err)
			}
			var row map[string]any
			require.NoError(t, f.db.Raw(`SELECT knowledge_base_id,parse_status,storage_size,description,CAST(metadata AS TEXT) AS metadata,error_message,title,CAST(custom_metadata AS TEXT) AS custom_metadata,COALESCE(file_path,'') AS file_path,pending_subtasks_count FROM knowledges WHERE id='doc'`).Scan(&row).Error)
			var used *int64
			require.NoError(t, f.db.Raw(`SELECT storage_used FROM tenants WHERE id=7`).Scan(&used).Error)
			checkpoints = append(checkpoints, map[string]any{"name": variant, "seed_updated_at": seedUpdated, "mutations": mutations, "delta": delta, "target": target, "error": firstError, "row": row, "storage_used": used, "returned_timestamp_equals_stored": roundTrip})
		})
	}
	if t.Failed() {
		return
	}
	out["compatibility"] = compat
	out["metadata"] = metadataCases
	out["plans"] = plans
	out["checkpoints"] = checkpoints
	data, err := json.MarshalIndent(out, "", "  ")
	require.NoError(t, err)
	require.NoError(t, os.WriteFile(os.Getenv("WEKNORA_MOVE_PLAN_ORACLE"), append(data, '\n'), 0600))
}
