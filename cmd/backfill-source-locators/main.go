// backfill-source-locators repairs missing PDF page maps without re-chunking
// or rebuilding retrieval indexes. It previews changes unless --apply is set.
package main

import (
	"context"
	"crypto/md5"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/joho/godotenv"

	"github.com/Tencent/WeKnora/internal/infrastructure/chunker"
	"github.com/Tencent/WeKnora/internal/infrastructure/docparser"
	"github.com/Tencent/WeKnora/internal/sourceloc"
	"github.com/Tencent/WeKnora/internal/types"
)

type document struct {
	ID, KB, Hash, Status, FileType string
	Tenant                         int64
}
type chunkRow struct {
	ID, Kind, Content, SourceJSON string
	Start, End, Revision, Index   int
	Updated                       time.Time
}
type update struct {
	ID            string               `json:"id"`
	Index         int                  `json:"chunk_index"`
	ContentSHA256 string               `json:"content_sha256"`
	Old           json.RawMessage      `json:"old_source_locators"`
	Locators      types.SourceLocators `json:"new_source_locators"`
}
type querier interface {
	Query(context.Context, string, ...any) (pgx.Rows, error)
}

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
func run() error {
	id := flag.String("knowledge-id", "", "Document UUID to repair")
	source := flag.String("source", "", "Path to the unchanged original PDF")
	host := flag.String("db-host", "", "Override DB_HOST, e.g. 127.0.0.1 for local dev")
	port := flag.String("db-port", "", "Override DB_PORT")
	apply := flag.Bool("apply", false, "Apply verified mappings after writing a backup")
	backupDir := flag.String("backup-dir", ".local-data/repairs", "Private rollback record directory")
	flag.Parse()
	if *id == "" || *source == "" {
		return errors.New("--knowledge-id and --source are required")
	}
	values, err := godotenv.Read(".env")
	if err != nil {
		return err
	}
	if extra, err := godotenv.Read(".env.local"); err == nil {
		for k, v := range extra {
			values[k] = v
		}
	} else if !os.IsNotExist(err) {
		return err
	}
	get := func(key string) string {
		if v, ok := os.LookupEnv(key); ok {
			return v
		}
		return values[key]
	}
	if driver := get("DB_DRIVER"); driver != "" && driver != "postgres" {
		return errors.New("repair supports PostgreSQL only")
	}
	config, err := pgx.ParseConfig("")
	if err != nil {
		return errors.New("invalid PostgreSQL environment")
	}
	config.Host, config.User, config.Password, config.Database = get("DB_HOST"), get("DB_USER"), get("DB_PASSWORD"), get("DB_NAME")
	if *host != "" {
		config.Host = *host
	}
	portText := get("DB_PORT")
	if *port != "" {
		portText = *port
	}
	if portText != "" {
		var n int
		if _, err := fmt.Sscanf(portText, "%d", &n); err != nil || n < 1 || n > 65535 {
			return errors.New("invalid DB_PORT")
		}
		config.Port = uint16(n)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
	defer cancel()
	conn, err := pgx.ConnectConfig(ctx, config)
	if err != nil {
		return err
	}
	defer conn.Close(context.Background())
	var doc document
	if err := conn.QueryRow(ctx, "SELECT id,knowledge_base_id,tenant_id,file_hash,parse_status,file_type FROM knowledges WHERE id=$1 AND deleted_at IS NULL", *id).
		Scan(&doc.ID, &doc.KB, &doc.Tenant, &doc.Hash, &doc.Status, &doc.FileType); err != nil {
		return err
	}
	if doc.Status != "completed" || strings.ToLower(doc.FileType) != "pdf" {
		return errors.New("document must be a completed PDF")
	}
	data, err := os.ReadFile(*source)
	if err != nil {
		return err
	}
	if fmt.Sprintf("%x", md5.Sum(data)) != doc.Hash {
		return errors.New("original PDF hash differs from the document; no mappings written")
	}
	before, err := readRows(ctx, conn, doc, false)
	if err != nil {
		return err
	}
	result, err := docparser.NewAnydocReader(nil, nil).Read(ctx, &types.ReadRequest{FileType: "pdf", FileName: filepath.Base(*source), FileContent: data})
	if err != nil {
		return err
	}
	if len(result.ImageRefs) > 0 {
		return errors.New("image URL rewriting requires a separate repair; no mappings written")
	}
	markdown := docparser.NormalizeHTMLTables(chunker.NormalizeLineEndings(result.MarkdownContent))
	blocks := sourceloc.RemapBlocks(result.SourceBlocks, result.MarkdownContent, markdown)
	updates, err := plan(before, markdown, blocks, doc.Hash)
	if err != nil {
		return err
	}
	pages := map[int]int{}
	for _, u := range updates {
		seen := map[int]bool{}
		for _, l := range u.Locators {
			if !seen[l.Page] {
				pages[l.Page]++
				seen[l.Page] = true
			}
		}
	}
	report := struct {
		DocumentID string      `json:"document_id"`
		Changes    int         `json:"changes"`
		Pages      map[int]int `json:"chunks_by_page"`
	}{doc.ID, len(updates), pages}
	if err := json.NewEncoder(os.Stdout).Encode(report); err != nil {
		return err
	}
	if !*apply || len(updates) == 0 {
		return nil
	}
	tx, err := conn.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(context.Background())
	var current document
	if err := tx.QueryRow(ctx, "SELECT id,knowledge_base_id,tenant_id,file_hash,parse_status,file_type FROM knowledges WHERE id=$1 AND deleted_at IS NULL FOR UPDATE", doc.ID).
		Scan(&current.ID, &current.KB, &current.Tenant, &current.Hash, &current.Status, &current.FileType); err != nil {
		return err
	}
	after, err := readRows(ctx, tx, doc, true)
	if err != nil {
		return err
	}
	if current != doc || !reflect.DeepEqual(before, after) {
		return errors.New("document or chunks changed during planning; nothing written")
	}
	if err := os.MkdirAll(*backupDir, 0700); err != nil {
		return err
	}
	backup := filepath.Join(*backupDir, doc.ID+"-"+time.Now().UTC().Format("20060102T150405.000000000Z")+".json")
	f, err := os.OpenFile(backup, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if err != nil {
		return err
	}
	writeErr := json.NewEncoder(f).Encode(struct {
		Document document `json:"document"`
		Updates  []update `json:"updates"`
	}{doc, updates})
	syncErr := f.Sync()
	closeErr := f.Close()
	if err := errors.Join(writeErr, syncErr, closeErr); err != nil {
		return err
	}
	for _, u := range updates {
		raw, err := json.Marshal(u.Locators)
		if err != nil {
			return err
		}
		count, err := tx.Exec(ctx, "UPDATE chunks SET source_locators=$1::jsonb WHERE id=$2 AND knowledge_id=$3 AND tenant_id=$4 AND knowledge_base_id=$5 AND deleted_at IS NULL", string(raw), u.ID, doc.ID, doc.Tenant, doc.KB)
		if err != nil {
			return err
		}
		if count.RowsAffected() != 1 {
			return errors.New("chunk disappeared; transaction rolled back")
		}
	}
	if err := tx.Commit(ctx); err != nil {
		return err
	}
	fmt.Printf("Applied %d mappings; rollback record: %s\n", len(updates), backup)
	return nil
}
func readRows(ctx context.Context, q querier, doc document, lock bool) ([]chunkRow, error) {
	sql := "SELECT id,chunk_type,content,COALESCE(source_locators,'[]'::jsonb)::text,start_at,end_at,content_revision,chunk_index,updated_at FROM chunks WHERE knowledge_id=$1 AND tenant_id=$2 AND knowledge_base_id=$3 AND deleted_at IS NULL ORDER BY id"
	if lock {
		sql += " FOR UPDATE"
	}
	rows, err := q.Query(ctx, sql, doc.ID, doc.Tenant, doc.KB)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []chunkRow
	for rows.Next() {
		var r chunkRow
		if err := rows.Scan(&r.ID, &r.Kind, &r.Content, &r.SourceJSON, &r.Start, &r.End, &r.Revision, &r.Index, &r.Updated); err != nil {
			return nil, err
		}
		out = append(out, r)
	}
	return out, rows.Err()
}
func plan(rows []chunkRow, markdown string, blocks []types.SourceBlock, hash string) ([]update, error) {
	if len(blocks) == 0 {
		return nil, errors.New("no verified PDF page positions available")
	}
	runes := []rune(markdown)
	eligible, exact := 0, 0
	for _, r := range rows {
		if r.Revision != 0 || (r.Kind != "text" && r.Kind != "parent_text") {
			continue
		}
		eligible++
		if r.Start >= 0 && r.End > r.Start && r.End <= len(runes) && string(runes[r.Start:r.End]) == r.Content {
			exact++
		}
	}
	if eligible == 0 || exact*100 < eligible*90 {
		return nil, errors.New("stored chunk offsets do not match parser output; no mappings written")
	}
	var out []update
	for _, r := range rows {
		if r.Revision != 0 || (r.Kind != "text" && r.Kind != "parent_text") || r.Start < 0 || r.End <= r.Start || r.End > len(runes) {
			continue
		}
		var old types.SourceLocators
		if err := json.Unmarshal([]byte(r.SourceJSON), &old); err != nil {
			return nil, err
		}
		if len(old) > 0 {
			continue
		}
		var local []types.SourceBlock
		for _, b := range blocks {
			lo, hi := max(b.Start, r.Start), min(b.End, r.End)
			if hi <= lo {
				continue
			}
			b.Start, b.End = lo-r.Start, hi-r.Start
			b.Locator.SourceHash = hash
			local = append(local, b)
		}
		local = sourceloc.RemapBlocks(local, string(runes[r.Start:r.End]), r.Content)
		locators := sourceloc.NewIndex(r.Content, local).Locators(0, len([]rune(r.Content)))
		if len(locators) > 0 {
			out = append(out, update{r.ID, r.Index, fmt.Sprintf("%x", sha256.Sum256([]byte(r.Content))), json.RawMessage(r.SourceJSON), locators})
		}
	}
	return out, nil
}
