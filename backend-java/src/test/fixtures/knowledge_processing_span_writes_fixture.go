//go:build ignore

// Production Go span repository writes, SQL types, nil-content merge and identity preservation.
package main

import (
	"compress/gzip"
	"context"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"github.com/Tencent/WeKnora/internal/application/repository"
	"github.com/Tencent/WeKnora/internal/types"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
	"os"
	"path/filepath"
	"strings"
	"time"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}
func main() {
	if len(os.Args) != 2 {
		panic("provide explicit gzip fixture output")
	}
	time.Local = time.UTC
	fixed, _ := time.Parse(time.RFC3339Nano, "2026-10-14T01:02:03.123456789Z")
	var cases []any
	for _, mode := range []string{"nil-content", "empty-content", "replace-content", "escaped-content", "immutable-identity", "clear-times", "zero-attempt", "new-attempt", "clean-null"} {
		dir, err := os.MkdirTemp("", "go-span-write-")
		must(err)
		db, err := gorm.Open(sqlite.Open(filepath.Join(dir, "case.db")), &gorm.Config{Logger: logger.Default.LogMode(logger.Silent)})
		must(err)
		for _, script := range []string{"000000_init.up.sql", "000008_processing_spans_and_pending_subtasks.up.sql"} {
			b, e := os.ReadFile("migrations/sqlite/" + script)
			must(e)
			must(db.Exec(string(b)).Error)
		}
		must(db.Exec("INSERT INTO tenants(id,name,business) VALUES(7,'Owner','test')").Error)
		must(db.Exec("INSERT INTO knowledge_bases(id,name,tenant_id,embedding_model_id,summary_model_id) VALUES('kb','KB',7,'','')").Error)
		must(db.Exec("INSERT INTO knowledges(id,tenant_id,knowledge_base_id,type,title,source,parse_status) VALUES('doc',7,'kb','file','Document','test','processing')").Error)
		sqlDB, e := db.DB()
		must(e)
		seed := snapshot(sqlDB)
		repo := repository.NewKnowledgeSpanRepository(db)
		full := types.KnowledgeProcessingSpan{KnowledgeID: "doc", Attempt: 1, SpanID: "span", ParentSpanID: "root", Name: "embedding", Kind: "stage", Status: "running", Input: types.JSONMap{"model_id": "embedding"}, Output: types.JSONMap{"old": true}, Metadata: types.JSONMap{"langfuse_trace_id": "trace"}, ErrorCode: "OLD_ERROR", ErrorMessage: "old", ErrorDetail: "PRIVATE", StartedAt: &fixed, FinishedAt: &fixed, DurationMs: 50, CreatedAt: fixed, UpdatedAt: fixed}
		update := types.KnowledgeProcessingSpan{KnowledgeID: "doc", Attempt: 1, SpanID: "span", ParentSpanID: "changed-parent", Name: "changed-name", Kind: "subspan", Status: "done", CreatedAt: fixed.Add(-time.Hour), UpdatedAt: fixed}
		switch mode {
		case "empty-content":
			update.Input = types.JSONMap{}
			update.Output = types.JSONMap{}
			update.Metadata = types.JSONMap{}
		case "replace-content":
			update.Input = types.JSONMap{"new": 1}
			update.Output = types.JSONMap{"count": 2}
			update.Metadata = types.JSONMap{"trace": "new"}
		case "escaped-content":
			update.Input = types.JSONMap{"z": "<>&\u2028\u2029中文", "a": []any{types.JSONMap{"z": true, "a": nil}}}
			update.Output = types.JSONMap{"text": "\b\f\n\r\t\\\""}
		case "clear-times":
			update.StartedAt = nil
			update.FinishedAt = nil
			update.DurationMs = 0
		case "zero-attempt":
			full.Attempt = 0
			update.Attempt = 0
		case "new-attempt":
			update.Attempt = 2
		case "clean-null":
			update.ErrorCode = "CODE\x00X"
			update.ErrorMessage = "a\x00b"
			update.ErrorDetail = "raw\x00private"
		}
		var ops []any
		for _, row := range []*types.KnowledgeProcessingSpan{&full, &update} {
			ops = append(ops, map[string]any{"knowledge_id": row.KnowledgeID, "attempt": row.Attempt, "span_id": row.SpanID, "parent_span_id": row.ParentSpanID, "name": row.Name, "kind": row.Kind, "status": row.Status, "input": row.Input, "output": row.Output, "metadata": row.Metadata, "error_code": row.ErrorCode, "error_message": row.ErrorMessage, "error_detail": row.ErrorDetail, "started_at": row.StartedAt, "finished_at": row.FinishedAt, "duration_ms": row.DurationMs, "created_at": row.CreatedAt, "updated_at": row.UpdatedAt})
			must(repo.Upsert(context.Background(), row))
		}
		latest, e := repo.LatestAttempt(context.Background(), "doc")
		must(e)
		next, e := repo.NextAttempt(context.Background(), "doc")
		must(e)
		cases = append(cases, map[string]any{"name": mode, "clock": fixed.Format(time.RFC3339Nano), "seed": seed, "ops": ops, "after": snapshot(sqlDB), "latest": latest, "next": next})
		must(sqlDB.Close())
		must(os.RemoveAll(dir))
	}
	f, e := os.Create(os.Args[1])
	must(e)
	gz := gzip.NewWriter(f)
	must(json.NewEncoder(gz).Encode(cases))
	must(gz.Close())
	must(f.Close())
	fmt.Printf("exported %d production Go span write scenes\n", len(cases))
}
func snapshot(db *sql.DB) any {
	schemas := []map[string]any{}
	rows, err := db.Query("SELECT name,type,sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%' ORDER BY CASE type WHEN 'table' THEN 0 ELSE 1 END,name")
	must(err)
	var tables []string
	for rows.Next() {
		var name, kind, query string
		must(rows.Scan(&name, &kind, &query))
		if strings.HasPrefix(name, "lite_embeddings_fts_") || strings.HasPrefix(name, "vec_embeddings_2_") {
			continue
		}
		schemas = append(schemas, map[string]any{"name": name, "type": kind, "sql": query})
		if kind == "table" {
			tables = append(tables, name)
		}
	}
	must(rows.Close())
	data := map[string]any{}
	for _, table := range tables {
		query := "SELECT * FROM \"" + strings.ReplaceAll(table, "\"", "\"\"") + "\""
		if table == "vec_embeddings_2" {
			query = "SELECT rowid,embedding FROM vec_embeddings_2"
		}
		if table == "lite_embeddings_fts" {
			query = "SELECT rowid FROM lite_embeddings_fts"
		}
		results, e := db.Query(query)
		must(e)
		columns, e := results.Columns()
		must(e)
		var entries []any
		for results.Next() {
			values := make([]any, len(columns))
			pointers := make([]any, len(columns))
			for i := range values {
				pointers[i] = &values[i]
			}
			must(results.Scan(pointers...))
			entry := map[string]any{}
			for i, key := range columns {
				value := values[i]
				if b, ok := value.([]byte); ok {
					value = map[string]any{"$blob": base64.StdEncoding.EncodeToString(b)}
				}
				if stamp, ok := value.(time.Time); ok {
					value = stamp.Format(time.RFC3339Nano)
				}
				entry[key] = value
			}
			entries = append(entries, entry)
		}
		must(results.Close())
		if entries == nil {
			entries = []any{}
		}
		data[table] = entries
	}
	return map[string]any{"schema": schemas, "tables": data}
}
