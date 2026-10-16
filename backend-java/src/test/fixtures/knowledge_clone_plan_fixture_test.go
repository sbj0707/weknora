//go:build ignore

// Export actual private clone planners using a test overlay, without editing production Go.
package service

import (
	"context"
	"encoding/json"
	"fmt"
	"net/url"
	"os"
	"sort"
	"strings"
	"testing"

	"github.com/Tencent/WeKnora/internal/application/access"
	repo "github.com/Tencent/WeKnora/internal/application/repository"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/stretchr/testify/require"
	"gorm.io/driver/postgres"
	"gorm.io/gorm"
)

func javaClonePlanFixture(t *testing.T) *documentWriteFixture {
	f := newDocumentWriteFixture(t)
	if dsn := os.Getenv("WEKNORA_CLONE_PLAN_PG_DSN"); dsn != "" {
		u, e := url.Parse(dsn)
		require.NoError(t, e)
		require.Equal(t, "127.0.0.1", u.Hostname())
		require.Equal(t, "/weknora_clone_plan_parity", u.Path)
		db, e := gorm.Open(postgres.New(postgres.Config{DSN: dsn}), &gorm.Config{})
		require.NoError(t, e)
		sqlDB, e := db.DB()
		require.NoError(t, e)
		sqlDB.SetMaxOpenConns(1)
		t.Cleanup(func() { require.NoError(t, sqlDB.Close()) })
		f.db = db
		f.repo.KnowledgeRepository = repo.NewKnowledgeRepository(db)
		f.chunkRepo.ChunkRepository = repo.NewChunkRepository(db)
		f.svc.tagRepo = repo.NewKnowledgeTagRepository(db)
	}
	for _, table := range []string{"knowledge_tag_relations", "chunks", "knowledges", "knowledge_tags"} {
		require.NoError(t, f.db.Exec("DELETE FROM "+table).Error)
	}
	return f
}

func TestJavaKnowledgeClonePlanExport(t *testing.T) {
	out := map[string]any{}
	compat := []any{}
	plans := []any{}
	faq := []any{}
	message := func(e error) string {
		if e == nil {
			return ""
		}
		return e.Error()
	}
	pairs := []struct{ name, source, target, tenant string }{
		{"ordinary", `{"id":"source"}`, `{"id":"target"}`, `{}`},
		{"null-source", `null`, `{"id":"target"}`, `{}`},
		{"null-target", `{"id":"source"}`, `null`, `{}`},
		{"same-id", `{"id":"source"}`, `{"id":"source"}`, `{}`},
		{"different-type", `{"id":"source","type":"faq"}`, `{"id":"target","type":"document"}`, `{}`},
		{"different-model", `{"id":"source","embedding_model_id":"one"}`, `{"id":"target","embedding_model_id":"two"}`, `{}`},
	}
	for _, a := range []string{`null`, `""`, `"one"`, `"two"`, `" "`} {
		for _, b := range []string{`null`, `""`, `"one"`, `"two"`, `" "`} {
			pairs = append(pairs, struct{ name, source, target, tenant string }{"store/" + a + "/" + b, `{"id":"source","vector_store_id":` + a + `}`, `{"id":"target","vector_store_id":` + b + `}`, `{}`})
		}
	}
	storage := []string{`{}`, `{"storage_backend_id":"one"}`, `{"storage_backend_id":" one "}`, `{"storage_backend_id":"two"}`, `{"storage_provider_config":{"provider":" COS "}}`, `{"storage_provider_config":{"provider":"__pending_env__"},"cos_config":{"provider":"LOCAL"}}`, `{"cos_config":{"provider":"minio"}}`}
	for i, a := range storage {
		for j, b := range storage {
			for _, tenant := range []string{`{}`, `{"default_storage_backend_id":"one","storage_engine_config":{"default_provider":"local"}}`} {
				pairs = append(pairs, struct{ name, source, target, tenant string }{fmt.Sprintf("storage/%d/%d/%s", i, j, tenant), strings.ReplaceAll(`{"id":"source",`+strings.TrimPrefix(a, "{"), `,}`, `}`), strings.ReplaceAll(`{"id":"target",`+strings.TrimPrefix(b, "{"), `,}`, `}`), tenant})
			}
		}
	}
	for _, p := range pairs {
		var source, target *types.KnowledgeBase
		var tenant *types.Tenant
		require.NoError(t, json.Unmarshal([]byte(p.source), &source))
		require.NoError(t, json.Unmarshal([]byte(p.target), &target))
		require.NoError(t, json.Unmarshal([]byte(p.tenant), &tenant))
		compat = append(compat, map[string]any{"name": p.name, "source": source, "target": target, "tenant": tenant, "error": message(access.ValidateKBTransferCompatibility(source, target, access.KBTransferClone, "", tenant))})
	}
	state := func(task, op, source, target, id, mode, phase string) string {
		raw, e := json.Marshal(map[string]any{types.KnowledgeTransferMetadataKey: map[string]any{"task_id": task, "operation": op, "source_kb": source, "target_kb": target, "source_id": id, "mode": mode, "phase": phase}})
		require.NoError(t, e)
		return string(raw)
	}
	names := []string{"ordinary", "both-empty", "source-empty", "target-empty", "same-hash", "duplicate-source-hash", "duplicate-target-hash", "empty-hash", "source-invalid-metadata", "target-invalid-metadata", "target-invalid-state", "target-null-metadata", "target-null-state", "no-source-invalid-target-metadata", "source-wrong-model", "source-foreign-ignored", "target-foreign-ignored", "source-deleted-ignored", "target-deleted-ignored", "matching-done", "matching-cloning", "matching-failed", "matching-done-failed", "matching-cloning-busy", "matching-then-hash", "matching-done-then-duplicate-hash", "other-task", "other-operation", "other-source", "other-target", "other-document", "other-mode", "blank-task", "hash-other-task-unfinished", "invalid-unused-target", "source-unicode-sort", "target-unicode-sort", "wrong-source-chunk-kb", "wrong-target-chunk-kb", "wrong-parent", "deleted-parent", "valid-parent", "wrong-source-tag", "wrong-target-tag", "missing-tag", "foreign-tag", "blank-tag", "valid-tag", "disabled-wrong-chunk", "deleted-wrong-chunk", "image-ocr", "image-caption", "question", "summary", "preflight-before-status"}
	for _, s := range []string{"pending", "processing", "deleting", "failed", "draft", "cancelled", "finalizing", ""} {
		names = append(names, "source-status/"+s, "target-status/"+s)
	}
	for _, name := range names {
		t.Run("clone-plan/"+name, func(t *testing.T) {
			f := javaClonePlanFixture(t)
			source, target := f.kbs.values["kb"], f.kbs.values["other"]
			sql := []string{}
			add := func(s string) { sql = append(sql, s) }
			add(`INSERT INTO knowledges(id,tenant_id,knowledge_base_id,title,type,parse_status,file_hash,embedding_model_id,created_at,metadata) VALUES('src-a',7,'kb','source','manual','completed','','','2026-01-01 00:00:00+00:00','{}'),('dst-a',7,'other','target','manual','completed','','','2026-01-02 00:00:00+00:00','{}'),('protected',8,'foreign','protected','manual','completed','','','2026-01-01 00:00:00+00:00','{}')`)
			add(`INSERT INTO chunks(id,tenant_id,knowledge_id,knowledge_base_id,chunk_type,content,chunk_index,start_at,end_at,is_enabled) VALUES('src-c',7,'src-a','kb','text','source',0,0,6,true),('dst-c',7,'dst-a','other','text','target',0,0,6,true)`)
			add(`INSERT INTO knowledge_tags(id,tenant_id,knowledge_base_id,name) VALUES('source-tag',7,'kb','source'),('target-tag',7,'other','target')`)
			putState := func(id, raw string) {
				add("UPDATE knowledges SET metadata='" + strings.ReplaceAll(raw, "'", "''") + "' WHERE id='" + id + "'")
			}
			switch name {
			case "both-empty":
				add(`DELETE FROM knowledges WHERE tenant_id=7`)
				add(`DELETE FROM chunks`)
			case "source-empty", "no-source-invalid-target-metadata":
				add(`DELETE FROM knowledges WHERE id='src-a'`)
				if name == "no-source-invalid-target-metadata" {
					putState("dst-a", `[]`)
				}
			case "target-empty":
				add(`DELETE FROM knowledges WHERE id='dst-a'`)
			case "same-hash", "hash-other-task-unfinished":
				add(`UPDATE knowledges SET file_hash='same' WHERE tenant_id=7`)
				if name == "hash-other-task-unfinished" {
					putState("dst-a", state("other", "clone", "kb", "other", "src-a", "", "cloning"))
				}
			case "duplicate-source-hash":
				add(`UPDATE knowledges SET file_hash='same' WHERE tenant_id=7`)
				add(`INSERT INTO knowledges(id,tenant_id,knowledge_base_id,title,type,parse_status,file_hash,created_at) VALUES('src-b',7,'kb','second','manual','completed','same','2026-01-03 00:00:00+00:00')`)
			case "duplicate-target-hash":
				add(`UPDATE knowledges SET file_hash='same' WHERE tenant_id=7`)
				add(`INSERT INTO knowledges(id,tenant_id,knowledge_base_id,title,type,parse_status,file_hash,created_at) VALUES('dst-b',7,'other','second','manual','completed','same','2026-01-03 00:00:00+00:00')`)
			case "source-invalid-metadata":
				putState("src-a", `[]`)
			case "target-invalid-metadata":
				putState("dst-a", `[]`)
			case "target-invalid-state":
				putState("dst-a", `{"_knowledge_transfer":{"task_id":3}}`)
			case "target-null-metadata":
				putState("dst-a", `null`)
			case "target-null-state":
				putState("dst-a", `{"_knowledge_transfer":null}`)
			case "source-wrong-model":
				add(`UPDATE knowledges SET embedding_model_id='unrelated' WHERE id='src-a'`)
			case "source-foreign-ignored":
				add(`UPDATE knowledges SET tenant_id=8 WHERE id='src-a'`)
			case "target-foreign-ignored":
				add(`UPDATE knowledges SET tenant_id=8 WHERE id='dst-a'`)
			case "source-deleted-ignored":
				add(`UPDATE knowledges SET deleted_at='2026-01-01 00:00:00+00:00' WHERE id='src-a'`)
			case "target-deleted-ignored":
				add(`UPDATE knowledges SET deleted_at='2026-01-01 00:00:00+00:00' WHERE id='dst-a'`)
			case "matching-done", "matching-cloning", "matching-failed", "matching-done-failed", "matching-cloning-busy", "matching-then-hash", "matching-done-then-duplicate-hash", "other-task", "other-operation", "other-source", "other-target", "other-document", "other-mode", "blank-task":
				task, op, s, d, id, mode, phase := "clone-task", "clone", "kb", "other", "src-a", "", "done"
				switch name {
				case "matching-cloning", "matching-cloning-busy", "matching-then-hash":
					phase = "cloning"
				case "matching-failed":
					phase = "failed"
				case "other-task":
					task = "other"
				case "other-operation":
					op = "move"
				case "other-source":
					s = "different"
				case "other-target":
					d = "different"
				case "other-document":
					id = "different"
				case "other-mode":
					mode = "reuse_vectors"
				case "blank-task":
					task = ""
				}
				putState("dst-a", state(task, op, s, d, id, mode, phase))
				if name == "matching-failed" || name == "matching-done-failed" {
					add(`UPDATE knowledges SET parse_status='failed' WHERE id='dst-a'`)
				}
				if name == "matching-cloning-busy" {
					add(`UPDATE knowledges SET parse_status='processing' WHERE id='dst-a'`)
				}
				if name == "matching-then-hash" || name == "matching-done-then-duplicate-hash" {
					add(`UPDATE knowledges SET file_hash='same' WHERE tenant_id=7`)
					add(`INSERT INTO knowledges(id,tenant_id,knowledge_base_id,title,type,parse_status,file_hash,created_at) VALUES('dst-b',7,'other','second','manual','completed','same','2026-01-03 00:00:00+00:00')`)
				}
			case "invalid-unused-target":
				add(`INSERT INTO knowledges(id,tenant_id,knowledge_base_id,title,type,parse_status,metadata,created_at) VALUES('dst-z',7,'other','bad','manual','completed','[]','2026-01-03 00:00:00+00:00')`)
				putState("dst-a", state("clone-task", "clone", "kb", "other", "src-a", "", "done"))
			case "source-unicode-sort":
				add(`INSERT INTO knowledges(id,tenant_id,knowledge_base_id,title,type,parse_status,created_at) VALUES('src-😀',7,'kb','emoji','manual','completed','2026-01-03 00:00:00+00:00'),('src-',7,'kb','private','manual','completed','2026-01-04 00:00:00+00:00')`)
			case "target-unicode-sort":
				add(`DELETE FROM knowledges WHERE id='dst-a'`)
				add(`UPDATE knowledges SET file_hash='same' WHERE id='src-a'`)
				add(`INSERT INTO knowledges(id,tenant_id,knowledge_base_id,title,type,parse_status,file_hash,created_at) VALUES('dst-😀',7,'other','emoji','manual','completed','same','2026-01-03 00:00:00+00:00'),('dst-',7,'other','private','manual','completed','same','2026-01-04 00:00:00+00:00')`)
			case "wrong-source-chunk-kb", "image-ocr", "image-caption", "question", "summary", "disabled-wrong-chunk", "deleted-wrong-chunk", "preflight-before-status":
				add(`UPDATE chunks SET knowledge_base_id='third' WHERE id='src-c'`)
				if name == "disabled-wrong-chunk" {
					add(`UPDATE chunks SET is_enabled=false WHERE id='src-c'`)
				}
				if name == "deleted-wrong-chunk" {
					add(`UPDATE chunks SET deleted_at='2026-01-01 00:00:00+00:00' WHERE id='src-c'`)
				}
				if name == "preflight-before-status" {
					add(`UPDATE knowledges SET parse_status='pending' WHERE id='src-a'`)
				}
				if name == "image-ocr" || name == "image-caption" || name == "question" || name == "summary" {
					add("UPDATE chunks SET chunk_type='" + strings.ReplaceAll(name, "-", "_") + "' WHERE id='src-c'")
				}
			case "wrong-target-chunk-kb":
				add(`UPDATE chunks SET knowledge_base_id='third' WHERE id='dst-c'`)
			case "wrong-parent":
				add(`UPDATE chunks SET parent_chunk_id='dst-c' WHERE id='src-c'`)
			case "deleted-parent", "valid-parent":
				add(`INSERT INTO chunks(id,tenant_id,knowledge_id,knowledge_base_id,chunk_type,content,chunk_index,start_at,end_at) VALUES('parent',7,'src-a','kb','parent','',0,0,0)`)
				add(`UPDATE chunks SET parent_chunk_id='parent' WHERE id='src-c'`)
				if name == "deleted-parent" {
					add(`UPDATE chunks SET deleted_at='2026-01-01 00:00:00+00:00' WHERE id='parent'`)
				}
			case "wrong-source-tag", "wrong-target-tag", "missing-tag", "foreign-tag", "blank-tag", "valid-tag":
				add(`UPDATE chunks SET tag_id='source-tag' WHERE id='src-c'`)
				switch name {
				case "wrong-source-tag":
					add(`UPDATE chunks SET tag_id='target-tag' WHERE id='src-c'`)
				case "wrong-target-tag":
					add(`UPDATE chunks SET tag_id='source-tag' WHERE id='dst-c'`)
				case "missing-tag":
					add(`DELETE FROM knowledge_tags WHERE id='source-tag'`)
				case "foreign-tag":
					add(`UPDATE knowledge_tags SET tenant_id=8 WHERE id='source-tag'`)
				case "blank-tag":
					add(`UPDATE knowledge_tags SET id=' ' WHERE id='source-tag'`)
					add(`UPDATE chunks SET tag_id=' ' WHERE id='src-c'`)
				}
			default:
				if strings.HasPrefix(name, "source-status/") {
					add("UPDATE knowledges SET parse_status='" + strings.TrimPrefix(name, "source-status/") + "' WHERE id='src-a'")
				}
				if strings.HasPrefix(name, "target-status/") {
					add("UPDATE knowledges SET parse_status='" + strings.TrimPrefix(name, "target-status/") + "' WHERE id='dst-a'")
				}
			}
			for i, statement := range sql {
				sql[i] = javaCloneSeed(statement)
				require.NoError(t, f.db.Exec(sql[i]).Error)
			}
			ctx, e := access.WithKBTransferTask(context.Background(), source, target, 7, access.KBTransferClone, "clone-task", false)
			require.NoError(t, e)
			before := javaClonePlanSnapshot(t, f)
			plan, e := f.svc.planKnowledgeClone(ctx, source, target)
			require.Equal(t, before, javaClonePlanSnapshot(t, f), "preflight may not write")
			added, removed := []string{}, []string{}
			if plan != nil {
				for _, row := range plan.add {
					added = append(added, row.ID)
				}
				removed = append(removed, plan.remove...)
			}
			plans = append(plans, map[string]any{"name": name, "source": source, "target": target, "seed_statements": sql, "add": added, "remove": removed, "error": message(e)})
		})
	}
	for _, name := range []string{"ordinary", "both-empty", "source-empty", "target-empty", "two-source-containers", "two-target-containers", "wrong-source-container", "wrong-target-container", "wrong-source-chunk", "wrong-target-chunk", "source-wrong-kb", "target-wrong-kb", "wrong-parent", "missing-tag", "wrong-tag", "disabled-invalid-chunk", "deleted-invalid-chunk", "pending-container", "failed-container"} {
		t.Run("faq-plan/"+name, func(t *testing.T) {
			f := javaClonePlanFixture(t)
			source, target := f.kbs.values["kb"], f.kbs.values["other"]
			sql := []string{`INSERT INTO knowledges(id,tenant_id,knowledge_base_id,title,type,parse_status,created_at) VALUES('src-a',7,'kb','source','faq','completed','2026-01-01 00:00:00+00:00'),('dst-a',7,'other','target','faq','completed','2026-01-02 00:00:00+00:00')`, `INSERT INTO chunks(id,tenant_id,knowledge_id,knowledge_base_id,chunk_type,content,chunk_index,start_at,end_at,is_enabled) VALUES('src-c',7,'src-a','kb','faq','source',0,0,6,true),('dst-c',7,'dst-a','other','faq','target',0,0,6,true)`}
			add := func(s string) { sql = append(sql, s) }
			switch name {
			case "both-empty":
				add(`DELETE FROM knowledges`)
			case "source-empty":
				add(`DELETE FROM knowledges WHERE id='src-a'`)
			case "target-empty":
				add(`DELETE FROM knowledges WHERE id='dst-a'`)
			case "two-source-containers":
				add(`INSERT INTO knowledges(id,tenant_id,knowledge_base_id,title,type,created_at) VALUES('src-b',7,'kb','extra','faq','2026-01-03 00:00:00+00:00')`)
			case "two-target-containers":
				add(`INSERT INTO knowledges(id,tenant_id,knowledge_base_id,title,type,created_at) VALUES('dst-b',7,'other','extra','faq','2026-01-03 00:00:00+00:00')`)
			case "wrong-source-container":
				add(`UPDATE knowledges SET type='file' WHERE id='src-a'`)
			case "wrong-target-container":
				add(`UPDATE knowledges SET type='manual' WHERE id='dst-a'`)
			case "wrong-source-chunk", "disabled-invalid-chunk", "deleted-invalid-chunk":
				add(`UPDATE chunks SET chunk_type='text' WHERE id='src-c'`)
				if name == "disabled-invalid-chunk" {
					add(`UPDATE chunks SET is_enabled=false WHERE id='src-c'`)
				}
				if name == "deleted-invalid-chunk" {
					add(`UPDATE chunks SET deleted_at='2026-01-01 00:00:00+00:00' WHERE id='src-c'`)
				}
			case "wrong-target-chunk":
				add(`UPDATE chunks SET chunk_type='text' WHERE id='dst-c'`)
			case "source-wrong-kb":
				add(`UPDATE chunks SET knowledge_base_id='other' WHERE id='src-c'`)
			case "target-wrong-kb":
				add(`UPDATE chunks SET knowledge_base_id='kb' WHERE id='dst-c'`)
			case "wrong-parent":
				add(`UPDATE chunks SET parent_chunk_id='dst-c' WHERE id='src-c'`)
			case "missing-tag":
				add(`UPDATE chunks SET tag_id='missing' WHERE id='src-c'`)
			case "wrong-tag":
				add(`INSERT INTO knowledge_tags(id,tenant_id,knowledge_base_id,name) VALUES('tag',7,'other','tag')`)
				add(`UPDATE chunks SET tag_id='tag' WHERE id='src-c'`)
			case "pending-container":
				add(`UPDATE knowledges SET parse_status='pending'`)
			case "failed-container":
				add(`UPDATE knowledges SET parse_status='failed'`)
			}
			for i, statement := range sql {
				sql[i] = javaCloneSeed(statement)
				require.NoError(t, f.db.Exec(sql[i]).Error)
			}
			ctx, e := access.WithKBTransferTask(context.Background(), source, target, 7, access.KBTransferClone, "clone-task", false)
			require.NoError(t, e)
			before := javaClonePlanSnapshot(t, f)
			a, b, e := f.svc.preflightFAQClone(ctx, source, target)
			require.Equal(t, before, javaClonePlanSnapshot(t, f))
			idsA, idsB := []string{}, []string{}
			for id := range a {
				idsA = append(idsA, id)
			}
			for id := range b {
				idsB = append(idsB, id)
			}
			sort.Strings(idsA)
			sort.Strings(idsB)
			faq = append(faq, map[string]any{"name": name, "source": source, "target": target, "seed_statements": sql, "source_chunks": idsA, "target_chunks": idsB, "error": message(e)})
		})
	}
	if t.Failed() {
		return
	}
	out["compatibility"], out["plans"], out["faq"] = compat, plans, faq
	raw, e := json.MarshalIndent(out, "", "  ")
	require.NoError(t, e)
	require.NoError(t, os.WriteFile(os.Getenv("WEKNORA_CLONE_PLAN_ORACLE"), append(raw, '\n'), 0600))
}

func javaClonePlanSnapshot(t *testing.T, f *documentWriteFixture) string {
	values := map[string]any{}
	for _, table := range []string{"knowledges", "chunks", "knowledge_tags", "knowledge_tag_relations"} {
		rows := []map[string]any{}
		order := "id"
		if table == "knowledge_tag_relations" {
			order = "knowledge_id,tag_id"
		}
		require.NoError(t, f.db.Raw("SELECT * FROM "+table+" ORDER BY "+order).Scan(&rows).Error)
		values[table] = rows
	}
	raw, e := json.Marshal(values)
	require.NoError(t, e)
	return string(raw)
}

// GORM AutoMigrate deliberately has no SQL default for this required field.
func javaCloneSeed(statement string) string {
	if !strings.HasPrefix(statement, "INSERT INTO knowledges(") {
		return statement
	}
	parts := strings.SplitN(statement, " VALUES", 2)
	return strings.TrimSuffix(parts[0], ")") + ",custom_metadata,source) VALUES" + strings.ReplaceAll(parts[1], ")", ",'{}','')")
}
