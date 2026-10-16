//go:build ignore

// Actual Go SQL index mover, including physical vector/FTS rows and destination retrieval.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	pg "github.com/Tencent/WeKnora/internal/application/repository/retriever/postgres"
	lite "github.com/Tencent/WeKnora/internal/application/repository/retriever/sqlite"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	sqlitevec "github.com/asg017/sqlite-vec-go-bindings/cgo"
	"github.com/jackc/pgx/v5/pgconn"
	sqlite3 "github.com/mattn/go-sqlite3"
	"gorm.io/driver/postgres"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strings"
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
	var pgerr *pgconn.PgError
	if errors.As(e, &pgerr) {
		return pgerr.Code
	}
	var liteerr sqlite3.Error
	if errors.As(e, &liteerr) {
		return int(liteerr.Code)
	}
	return e.Error()
}
func main() {
	engine := "sqlite"
	dsn := os.Getenv("WEKNORA_INDEX_MOVE_PG_DSN")
	if dsn != "" {
		engine = "postgres"
		u, e := url.Parse(dsn)
		must(e)
		if u.Hostname() != "127.0.0.1" || u.Path != "/weknora_index_move_parity" {
			panic("owned index move PG required")
		}
	}
	rows := []any{}
	for _, name := range []string{"ordinary", "nil-chunks", "wrong-chunks", "wrong-dimension", "wrong-type", "same-kb", "empty-source", "empty-target", "empty-document", "quoted-scope", "not-found", "update-failure", "rollback"} {
		var db *gorm.DB
		var err error
		if engine == "postgres" {
			db, err = gorm.Open(postgres.Open(dsn), &gorm.Config{Logger: logger.Default.LogMode(logger.Silent)})
		} else {
			sqlitevec.Auto()
			dir, e := os.MkdirTemp("", "weknora-go-index-move-")
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
		if engine == "postgres" {
			must(db.Exec(`DROP TRIGGER IF EXISTS java_index_move_fault ON embeddings`).Error)
			must(db.Exec(`TRUNCATE embeddings RESTART IDENTITY`).Error)
		}
		create := func(db *gorm.DB) interfaces.RetrieveEngineRepository {
			if engine == "postgres" {
				return pg.NewPostgresRetrieveEngineRepository(db)
			}
			return lite.NewSQLiteRetrieveEngineRepository(db)
		}
		repository := create(db)
		from, to, doc := "source", "target", "doc"
		chunks := []string{"a", "b"}
		dimension := 2
		kind := "document"
		switch name {
		case "nil-chunks":
			chunks = nil
		case "wrong-chunks":
			chunks = []string{"nonexistent"}
		case "wrong-dimension":
			dimension = 41
		case "wrong-type":
			kind = "faq"
		case "same-kb":
			to = from
		case "empty-source":
			from = ""
		case "empty-target":
			to = ""
		case "empty-document":
			doc = ""
		case "quoted-scope":
			from = "source' OR 1=1 --"
			to = "target' <>&😀"
			doc = "doc' OR 1=1 --"
		}
		entries := []*types.IndexInfo{
			{SourceID: "source-a", SourceType: types.ChunkSourceType, ChunkID: "a", KnowledgeID: doc, KnowledgeBaseID: from, TagID: "tag", Content: "refund alpha", IsEnabled: true},
			{SourceID: "source-b", SourceType: types.ChunkSourceType, ChunkID: "b", KnowledgeID: doc, KnowledgeBaseID: from, TagID: "tag", Content: "refund disabled", IsEnabled: false},
			{SourceID: "source-a-question", SourceType: types.ChunkSourceType, ChunkID: "a", KnowledgeID: doc, KnowledgeBaseID: from, TagID: "tag", Content: "refund question", IsEnabled: true},
			{SourceID: "other-doc", SourceType: types.ChunkSourceType, ChunkID: "other-doc", KnowledgeID: "other-doc", KnowledgeBaseID: from, TagID: "tag", Content: "refund other doc", IsEnabled: true},
			{SourceID: "other-kb", SourceType: types.ChunkSourceType, ChunkID: "other-kb", KnowledgeID: doc, KnowledgeBaseID: "third", TagID: "tag", Content: "refund other kb", IsEnabled: true},
			{SourceID: "already-target", SourceType: types.ChunkSourceType, ChunkID: "already-target", KnowledgeID: doc, KnowledgeBaseID: to, TagID: "tag", Content: "refund target", IsEnabled: true},
		}
		vectors := map[string][]float32{"source-a": {1, 0}, "source-b": {0, 1}, "source-a-question": {.8, .6}, "other-doc": {0, 1}, "other-kb": {-1, 0}, "already-target": {.6, .8}}
		must(repository.BatchSave(context.Background(), entries, map[string]any{"embedding": vectors}))
		table := "lite_embeddings"
		if engine == "postgres" {
			table = "embeddings"
		}
		must(db.Exec("UPDATE " + table + " SET created_at='2026-01-01 00:00:00+00:00',updated_at='2026-01-01 00:00:00+00:00'").Error)
		if name == "not-found" {
			doc = "missing"
		}
		seed := []string{}
		reset := []string{}
		if name == "update-failure" {
			if engine == "postgres" {
				seed = append(seed, `CREATE OR REPLACE FUNCTION java_index_move_fault_fn() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'controlled metadata failure' USING ERRCODE='P0001'; END $$`, `CREATE TRIGGER java_index_move_fault BEFORE UPDATE ON embeddings FOR EACH ROW EXECUTE FUNCTION java_index_move_fault_fn()`)
				reset = append(reset, `DROP TRIGGER java_index_move_fault ON embeddings`)
			} else {
				seed = append(seed, `CREATE TRIGGER java_index_move_fault BEFORE UPDATE ON lite_embeddings BEGIN SELECT RAISE(ABORT,'controlled metadata failure'); END`)
				reset = append(reset, `DROP TRIGGER java_index_move_fault`)
			}
		}
		for _, s := range seed {
			must(db.Exec(s).Error)
		}
		snapshot := func() map[string]any {
			selected := []map[string]any{}
			physical := []map[string]any{}
			fts := []map[string]any{}
			selectSQL := `SELECT id,source_id,source_type,chunk_id,knowledge_id,knowledge_base_id,tag_id,content,dimension,is_enabled,updated_at='2026-01-01 00:00:00+00:00' AS unchanged,created_at='2026-01-01 00:00:00+00:00' AS original_created FROM ` + table + ` ORDER BY id`
			must(db.Raw(selectSQL).Scan(&selected).Error)
			if engine == "postgres" {
				must(db.Raw(`SELECT id,embedding::text AS embedding FROM embeddings ORDER BY id`).Scan(&physical).Error)
			} else {
				must(db.Raw(`SELECT rowid AS id,hex(embedding) AS embedding FROM vec_embeddings_2 ORDER BY rowid`).Scan(&physical).Error)
				must(db.Raw(`SELECT id,hex(block) AS block FROM lite_embeddings_fts_data ORDER BY id`).Scan(&fts).Error)
			}
			queries := []any{}
			for _, kb := range []string{from, to, "third"} {
				for _, method := range []types.RetrieverType{types.VectorRetrieverType, types.KeywordsRetrieverType} {
					p := types.RetrieveParams{Query: "refund", Embedding: []float32{1, 0}, KnowledgeBaseIDs: []string{kb}, TopK: 20, RetrieverType: method}
					result, e := repository.Retrieve(context.Background(), p)
					hits := []any{}
					if e == nil {
						for _, block := range result {
							for _, h := range block.Results {
								hits = append(hits, map[string]any{"id": h.ID, "source": h.SourceID, "chunk": h.ChunkID, "document": h.KnowledgeID, "kb": h.KnowledgeBaseID, "tag": h.TagID, "content": h.Content})
							}
						}
						sort.Slice(hits, func(i, j int) bool {
							return fmt.Sprint(hits[i].(map[string]any)["source"]) < fmt.Sprint(hits[j].(map[string]any)["source"])
						})
					}
					queries = append(queries, map[string]any{"kb": kb, "kind": method, "hits": hits, "error_code": code(e)})
				}
			}
			return map[string]any{"metadata": selected, "physical": physical, "fts": fts, "queries": queries}
		}
		before := snapshot()
		states := []any{}
		for attempt := 0; attempt < 2; attempt++ {
			var moveErr error
			if name == "rollback" {
				moveErr = db.Transaction(func(tx *gorm.DB) error {
					if e := create(tx).(interfaces.KnowledgeIndexMover).MoveKnowledgeIndices(context.Background(), from, to, doc, chunks, dimension, kind); e != nil {
						return e
					}
					return errors.New("controlled rollback")
				})
			} else {
				moveErr = repository.(interfaces.KnowledgeIndexMover).MoveKnowledgeIndices(context.Background(), from, to, doc, chunks, dimension, kind)
			}
			states = append(states, map[string]any{"error_code": code(moveErr), "state": snapshot()})
			if attempt == 0 {
				for _, s := range reset {
					must(db.Exec(s).Error)
				}
			}
		}
		rows = append(rows, map[string]any{"engine": engine, "name": name, "entries": entries, "vectors": vectors, "source": from, "target": to, "document": doc, "chunks": chunks, "dimension": dimension, "knowledge_type": kind, "seed_statements": seed, "reset_statements": reset, "before": before, "steps": states})
		must(sqlDB.Close())
	}
	raw, e := json.MarshalIndent(rows, "", "  ")
	must(e)
	must(os.WriteFile(os.Args[1], append(raw, '\n'), 0600))
	fmt.Println(strings.Join([]string{engine, "index move cases", fmt.Sprint(len(rows))}, " "))
}
