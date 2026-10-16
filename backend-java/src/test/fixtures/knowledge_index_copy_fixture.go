//go:build ignore

// Real Go CopyIndices, including independent commits and physical vector/FTS effects.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strings"

	pg "github.com/Tencent/WeKnora/internal/application/repository/retriever/postgres"
	lite "github.com/Tencent/WeKnora/internal/application/repository/retriever/sqlite"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	sqlitevec "github.com/asg017/sqlite-vec-go-bindings/cgo"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgconn"
	sqlite3 "github.com/mattn/go-sqlite3"
	"gorm.io/driver/postgres"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
)

func must(e error) {
	if e != nil {
		panic(e)
	}
}
func code(e error) any {
	if e == nil {
		return nil
	}
	var p *pgconn.PgError
	if errors.As(e, &p) {
		return p.Code
	}
	var s sqlite3.Error
	if errors.As(e, &s) {
		return int(s.Code)
	}
	return e.Error()
}
func sourceID(s string) string {
	if _, e := uuid.Parse(s); e == nil {
		return "<uuid>"
	}
	return s
}
func main() {
	engine, dsn := "sqlite", os.Getenv("WEKNORA_INDEX_COPY_PG_DSN")
	if dsn != "" {
		engine = "postgres"
		u, e := url.Parse(dsn)
		must(e)
		if u.Hostname() != "127.0.0.1" || u.Path != "/weknora_index_copy_parity" {
			panic("Owned loopback index copy PG required")
		}
	}
	results := []any{}
	names := []string{"ordinary", "empty-chunks", "empty-documents", "wrong-source", "wrong-dimension", "wrong-kind", "same-kb", "empty-target", "quoted-scope", "unmapped-document", "partial-chunks", "unknown-source", "nullable-fields", "duplicate-target", "metadata-fault", "missing-vector", "missing-vector-table", "missing-fts", "source-query-fault", "page-boundary", "late-page-fault"}
	for _, name := range names {
		var db *gorm.DB
		var err error
		if engine == "postgres" {
			db, err = gorm.Open(postgres.Open(dsn), &gorm.Config{Logger: logger.Default.LogMode(logger.Silent)})
		} else {
			sqlitevec.Auto()
			dir, e := os.MkdirTemp("", "weknora-go-index-copy-")
			must(e)
			defer os.RemoveAll(dir)
			db, err = gorm.Open(sqlite.Open(filepath.Join(dir, "data.db")), &gorm.Config{Logger: logger.Default.LogMode(logger.Silent)})
			must(err)
			raw, e := os.ReadFile("migrations/sqlite/000000_init.up.sql")
			must(e)
			must(db.Exec(string(raw)).Error)
		}
		must(err)
		sqlDB, e := db.DB()
		must(e)
		sqlDB.SetMaxOpenConns(1)
		table := "lite_embeddings"
		if engine == "postgres" {
			table = "embeddings"
			must(db.Exec(`DROP TRIGGER IF EXISTS java_index_copy_fault ON embeddings`).Error)
			must(db.Exec(`TRUNCATE embeddings RESTART IDENTITY`).Error)
		}
		var repository interfaces.RetrieveEngineRepository
		if engine == "postgres" {
			repository = pg.NewPostgresRetrieveEngineRepository(db)
		} else {
			repository = lite.NewSQLiteRetrieveEngineRepository(db)
		}
		from, to := "source", "target"
		documents := map[string]string{"doc": "copied-doc"}
		chunks := map[string]string{"a": "copied-a", "b": "copied-b"}
		dimension, kind := 2, "document"
		if name == "same-kb" {
			to = from
		}
		if name == "empty-target" {
			to = ""
		}
		if name == "quoted-scope" {
			from = "source' OR 1=1 --"
			to = "target' <>&😀"
		}
		entry := func(source, chunk, doc, kb, tag, content string, enabled bool) *types.IndexInfo {
			return &types.IndexInfo{SourceID: source, SourceType: types.ChunkSourceType, ChunkID: chunk, KnowledgeID: doc, KnowledgeBaseID: kb, TagID: tag, Content: content, IsEnabled: enabled}
		}
		entries := []*types.IndexInfo{
			entry("a", "a", "doc", from, "tag", "refund alpha", true), entry("a-q1", "a", "doc", from, "tag", "refund question", false),
			entry("b", "b", "doc", from, "tag", "退款 bravo", true), entry("other-doc", "other-doc", "other", from, "other", "refund other", true),
			entry("foreign-a", "a", "foreign", "third", "foreign-tag", "refund foreign", true), entry("already-target", "peer", "peer", to, "peer", "refund target", true),
		}
		if name == "unknown-source" {
			entries = append(entries, entry("unrelated", "a", "doc", from, "tag", "refund unusual", true))
		}
		if name == "duplicate-target" {
			entries = append(entries, entry("copied-a", "existing", "existing", to, "existing", "refund collision", true))
		}
		if name == "page-boundary" || name == "late-page-fault" {
			entries = entries[:0]
			chunks = map[string]string{"a": "copied-a"}
			for i := 0; i < 501; i++ {
				entries = append(entries, entry(fmt.Sprintf("a-q%04d", i), "a", "doc", from, "tag", fmt.Sprintf("refund item %04d", i), i%2 == 0))
			}
		}
		vectors := map[string][]float32{}
		for i, row := range entries {
			if i%2 == 0 {
				vectors[row.SourceID] = []float32{1, 0}
			} else {
				vectors[row.SourceID] = []float32{0, 1}
			}
		}
		must(repository.BatchSave(context.Background(), entries, map[string]any{"embedding": vectors}))
		must(db.Exec("UPDATE " + table + " SET created_at='2026-01-01 00:00:00+00:00',updated_at='2026-01-01 00:00:00+00:00'").Error)
		switch name {
		case "empty-chunks":
			chunks = map[string]string{}
		case "empty-documents":
			documents = map[string]string{}
		case "wrong-source":
			from = "missing"
		case "wrong-dimension":
			dimension = 41
		case "wrong-kind":
			kind = "faq"
		case "unmapped-document":
			documents = map[string]string{"unrelated": "copied-other"}
		case "partial-chunks":
			chunks = map[string]string{"a": "copied-a"}
		}
		seed, reset := []string{}, []string{}
		switch name {
		case "nullable-fields":
			seed = append(seed, "UPDATE "+table+" SET is_enabled=NULL,tag_id=NULL WHERE source_id='a'")
		case "metadata-fault", "late-page-fault":
			failSource := "copied-a-q1"
			if name == "late-page-fault" {
				failSource = "copied-a-q0500"
			}
			if engine == "postgres" {
				seed = append(seed, `CREATE OR REPLACE FUNCTION java_index_copy_fault_fn() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'controlled index copy failure' USING ERRCODE='P0001'; END $$`, fmt.Sprintf(`CREATE TRIGGER java_index_copy_fault BEFORE INSERT ON embeddings FOR EACH ROW WHEN (NEW.source_id='%s') EXECUTE FUNCTION java_index_copy_fault_fn()`, failSource))
				reset = append(reset, `DROP TRIGGER java_index_copy_fault ON embeddings`)
			} else {
				seed = append(seed, fmt.Sprintf(`CREATE TRIGGER java_index_copy_fault BEFORE INSERT ON lite_embeddings WHEN NEW.source_id='%s' BEGIN SELECT RAISE(ABORT,'controlled index copy failure'); END`, failSource))
				reset = append(reset, `DROP TRIGGER java_index_copy_fault`)
			}
		case "missing-vector":
			if engine == "sqlite" {
				seed = append(seed, `DELETE FROM vec_embeddings_2 WHERE rowid=1`)
			}
		case "missing-vector-table":
			if engine == "sqlite" {
				seed = append(seed, `DROP TABLE vec_embeddings_2`)
			}
		case "missing-fts":
			if engine == "sqlite" {
				seed = append(seed, `DROP TABLE lite_embeddings_fts`)
			}
		case "source-query-fault":
			seed = append(seed, "ALTER TABLE "+table+" RENAME TO hidden_copy_index")
			reset = append(reset, "ALTER TABLE hidden_copy_index RENAME TO "+table)
		}
		for _, sql := range seed {
			must(db.Exec(sql).Error)
		}
		snapshot := func(hidden bool) map[string]any {
			metadata := []map[string]any{}
			physical := []map[string]any{}
			actualTable := table
			if hidden {
				actualTable = "hidden_copy_index"
			}
			must(db.Raw("SELECT id,source_id,source_type,chunk_id,knowledge_id,knowledge_base_id,tag_id,content,dimension,is_enabled,created_at='2026-01-01 00:00:00+00:00' AS original_created,updated_at='2026-01-01 00:00:00+00:00' AS unchanged FROM " + actualTable + " ORDER BY id").Scan(&metadata).Error)
			for _, r := range metadata {
				r["source_id"] = sourceID(fmt.Sprint(r["source_id"]))
			}
			var physicalError error
			if engine == "postgres" {
				physicalError = db.Raw("SELECT id,embedding::text AS embedding FROM " + actualTable + " ORDER BY id").Scan(&physical).Error
			} else {
				physicalError = db.Raw(`SELECT rowid AS id,hex(embedding) AS embedding FROM vec_embeddings_2 ORDER BY rowid`).Scan(&physical).Error
			}
			queries := []any{}
			for _, kb := range []string{from, to, "third"} {
				for _, method := range []types.RetrieverType{types.VectorRetrieverType, types.KeywordsRetrieverType} {
					blocks, e := repository.Retrieve(context.Background(), types.RetrieveParams{Query: "refund", Embedding: []float32{1, 0}, KnowledgeBaseIDs: []string{kb}, TopK: 1200, RetrieverType: method})
					hits := []any{}
					if e == nil {
						for _, block := range blocks {
							for _, h := range block.Results {
								hits = append(hits, map[string]any{"id": h.ID, "source": sourceID(h.SourceID), "chunk": h.ChunkID, "document": h.KnowledgeID, "kb": h.KnowledgeBaseID, "tag": h.TagID, "content": h.Content})
							}
						}
						sort.Slice(hits, func(i, j int) bool {
							return fmt.Sprint(hits[i].(map[string]any)["id"]) < fmt.Sprint(hits[j].(map[string]any)["id"])
						})
					}
					queries = append(queries, map[string]any{"kb": kb, "kind": method, "hits": hits, "error_code": code(e)})
				}
			}
			return map[string]any{"metadata": metadata, "physical": physical, "physical_error": code(physicalError), "queries": queries}
		}
		before := snapshot(name == "source-query-fault")
		steps := []any{}
		for attempt := 0; attempt < 2; attempt++ {
			err := repository.CopyIndices(context.Background(), from, documents, chunks, to, dimension, kind)
			steps = append(steps, map[string]any{"error_code": code(err), "state": snapshot(name == "source-query-fault" && attempt == 0)})
			if attempt == 0 {
				for _, sql := range reset {
					must(db.Exec(sql).Error)
				}
			}
		}
		results = append(results, map[string]any{"engine": engine, "name": name, "entries": entries, "vectors": vectors, "source": from, "target": to, "documents": documents, "chunks": chunks, "dimension": dimension, "knowledge_type": kind, "seed_statements": seed, "reset_statements": reset, "before": before, "steps": steps})
		must(sqlDB.Close())
	}
	raw, e := json.MarshalIndent(results, "", "  ")
	must(e)
	must(os.WriteFile(os.Args[1], append(raw, '\n'), 0600))
	fmt.Println(strings.Join([]string{engine, "index copy cases", fmt.Sprint(len(results))}, " "))
}
