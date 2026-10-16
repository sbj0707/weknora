package main

import (
	"context"
	"encoding/json"
	"net/url"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/alicebob/miniredis/v2"
	"github.com/google/uuid"
	"github.com/hibiken/asynq"
	"github.com/jackc/pgx/v5"
)

func TestMaintenanceRequired(t *testing.T) {
	o := options{ID: uuid.NewString(), Apply: true}
	if validateOptions(o) == nil {
		t.Fatal("apply must require explicit maintenance confirmation")
	}
	o.Maintenance = true
	if err := validateOptions(o); err != nil {
		t.Fatal(err)
	}
}

func TestEnvValuesPreserveSecrets(t *testing.T) {
	for _, tc := range []struct{ raw, want string }{
		{"password!@#", "password!@#"},
		{"password # comment", "password"},
		{"'password$VALUE#'", "password$VALUE#"},
		{"\"password$VALUE#\" # comment", "password$VALUE#"},
		{"\"quoted\\\"password\"", "quoted\"password"},
	} {
		got, err := envValue(tc.raw)
		if err != nil || got != tc.want {
			t.Fatalf("env parsing mismatch (values omitted), err=%v", err)
		}
	}
	if _, err := envValue("'unterminated"); err == nil {
		t.Fatal("malformed quoting must fail")
	}
}

func TestDeleteTaskScope(t *testing.T) {
	id := uuid.NewString()
	docs := map[string]bool{"document-1": true}
	for _, tc := range []struct {
		name, taskType, payload string
		want                    bool
	}{
		{"target", "kb:delete", `{"tenant_id":7,"knowledge_base_id":"` + id + `"}`, true},
		{"foreign tenant", "kb:delete", `{"tenant_id":8,"knowledge_base_id":"` + id + `"}`, false},
		{"foreign kb", "kb:delete", `{"tenant_id":7,"knowledge_base_id":"other"}`, false},
		{"not destructive", "document:process", `{"tenant_id":7,"knowledge_base_id":"` + id + `"}`, false},
		{"related doc", "knowledge:list_delete", `{"tenant_id":7,"knowledge_ids":["document-1"]}`, true},
		{"invalid json", "kb:delete", `{`, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if got := matchingDelete(&asynq.TaskInfo{Type: tc.taskType, Payload: []byte(tc.payload)}, id, 7, docs); got != tc.want {
				t.Fatalf("match=%v want=%v", got, tc.want)
			}
		})
	}
}

func TestQueueScanPaginationAndScope(t *testing.T) {
	r := miniredis.RunT(t)
	opt := asynq.RedisClientOpt{Addr: r.Addr()}
	client := asynq.NewClient(opt)
	defer client.Close()
	id := uuid.NewString()
	for n := 0; n < 105; n++ {
		payload, _ := json.Marshal(map[string]any{"tenant_id": 8, "knowledge_base_id": id})
		if _, err := client.Enqueue(asynq.NewTask("kb:delete", payload), asynq.Queue("low")); err != nil {
			t.Fatal(err)
		}
	}
	payload, _ := json.Marshal(map[string]any{"tenant_id": 7, "knowledge_base_id": id})
	target, err := client.Enqueue(asynq.NewTask("kb:delete", payload), asynq.Queue("low"))
	if err != nil {
		t.Fatal(err)
	}
	i := asynq.NewInspector(opt)
	defer i.Close()
	tasks, err := scanDeleteTasks(i, id, 7, nil)
	if err != nil {
		t.Fatal(err)
	}
	if len(tasks) != 1 || tasks[0].Info.ID != target.ID {
		t.Fatalf("wrong tasks: %+v", tasks)
	}
}

// Each integration test uses its own schema inside the explicitly supplied
// test DB; SQL objects and Redis jobs belong only to this test fixture.
func integrationConfig(t *testing.T) (config, *pgx.Conn, string) {
	t.Helper()
	dsn := os.Getenv("RESTORE_TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("RESTORE_TEST_DATABASE_URL not set")
	}
	ctx := context.Background()
	admin, err := pgx.Connect(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	schema := "restore_test_" + uuid.New().String()[:8]
	if _, err := admin.Exec(ctx, "CREATE SCHEMA "+pgx.Identifier{schema}.Sanitize()); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		_, err := admin.Exec(ctx, "DROP SCHEMA "+pgx.Identifier{schema}.Sanitize()+" CASCADE")
		if err != nil {
			t.Error(err)
		}
		admin.Close(ctx)
	})
	u, err := url.Parse(dsn)
	if err != nil {
		t.Fatal(err)
	}
	q := u.Query()
	q.Set("search_path", schema)
	u.RawQuery = q.Encode()
	c := config{DBURL: u.String(), Redis: asynq.RedisClientOpt{Addr: miniredis.RunT(t).Addr()}}
	conn, err := pgx.Connect(ctx, c.DBURL)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { conn.Close(ctx) })
	_, err = conn.Exec(ctx, `
CREATE TABLE tenants(id bigint PRIMARY KEY,deleted_at timestamptz,storage_used bigint);
CREATE TABLE knowledge_bases(id text PRIMARY KEY,tenant_id bigint,name text,deleted_at timestamptz);
CREATE TABLE knowledges(id text PRIMARY KEY,tenant_id bigint,knowledge_base_id text,deleted_at timestamptz,storage_size bigint);
CREATE TABLE chunks(id text PRIMARY KEY,tenant_id bigint,knowledge_base_id text,knowledge_id text,deleted_at timestamptz);
CREATE TABLE wiki_pages(id text PRIMARY KEY,tenant_id bigint,knowledge_base_id text,deleted_at timestamptz);
`)
	if err != nil {
		t.Fatal(err)
	}
	return c, conn, schema
}

func TestRestorePreservesEarlierDeletesAndOtherScopes(t *testing.T) {
	c, conn, _ := integrationConfig(t)
	ctx := context.Background()
	id, other := uuid.NewString(), uuid.NewString()
	deleted := time.Now().UTC().Truncate(time.Microsecond).Add(-time.Hour)
	_, err := conn.Exec(ctx, `
INSERT INTO tenants VALUES(7,NULL,999),(8,NULL,200);
INSERT INTO knowledge_bases VALUES($1,7,'target',$3::timestamptz),($2,7,'other',NULL);
INSERT INTO knowledges VALUES
 ('restored',7,$1,$3::timestamptz+interval '1 second',100),
 ('previously-deleted',7,$1,$3::timestamptz-interval '1 hour',300),
 ('already-live',7,$1,NULL,50),
 ('other-kb',7,$2,NULL,25),
 ('other-tenant',8,$1,$3::timestamptz,200);
INSERT INTO chunks VALUES
 ('restored-chunk',7,$1,'restored',$3::timestamptz+interval '2 seconds'),
 ('older-chunk',7,$1,'restored',$3::timestamptz-interval '1 hour'),
 ('previous-doc-chunk',7,$1,'previously-deleted',$3::timestamptz+interval '2 seconds'),
 ('other-tenant-chunk',8,$1,'other-tenant',$3::timestamptz);
INSERT INTO wiki_pages VALUES('restored-page',7,$1,$3::timestamptz+interval '1 second'),('old-page',7,$1,$3::timestamptz-interval '1 hour');
`, pgx.QueryExecModeSimpleProtocol, id, other, deleted)
	if err != nil {
		t.Fatal(err)
	}
	client := asynq.NewClient(c.Redis)
	defer client.Close()
	payload, _ := json.Marshal(map[string]any{"tenant_id": 7, "knowledge_base_id": id})
	target, err := client.Enqueue(asynq.NewTask("kb:delete", payload), asynq.Queue("low"))
	if err != nil {
		t.Fatal(err)
	}
	foreignPayload, _ := json.Marshal(map[string]any{"tenant_id": 8, "knowledge_base_id": id})
	foreign, err := client.Enqueue(asynq.NewTask("kb:delete", foreignPayload), asynq.Queue("low"))
	if err != nil {
		t.Fatal(err)
	}
	backup := t.TempDir()
	o := options{ID: id, BackupDir: backup}
	if err := run(o, c); err != nil {
		t.Fatal(err)
	}
	var stillDeleted bool
	if err := conn.QueryRow(ctx, "SELECT deleted_at IS NOT NULL FROM knowledge_bases WHERE id=$1", id).Scan(&stillDeleted); err != nil || !stillDeleted {
		t.Fatal("dry-run modified KB", err)
	}
	entries, _ := os.ReadDir(backup)
	if len(entries) != 0 {
		t.Fatal("dry-run wrote backup")
	}
	readInspector := asynq.NewInspector(c.Redis)
	if _, err := readInspector.GetTaskInfo("low", target.ID); err != nil {
		t.Fatal("dry-run removed delete task", err)
	}
	readInspector.Close()
	o.Apply, o.Maintenance = true, true
	if err := run(o, c); err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		table, id string
		deleted   bool
	}{
		{"knowledge_bases", id, false}, {"knowledges", "restored", false}, {"knowledges", "previously-deleted", true}, {"knowledges", "other-tenant", true},
		{"chunks", "restored-chunk", false}, {"chunks", "older-chunk", true}, {"chunks", "previous-doc-chunk", true}, {"chunks", "other-tenant-chunk", true},
		{"wiki_pages", "restored-page", false}, {"wiki_pages", "old-page", true},
	} {
		var got bool
		if err := conn.QueryRow(ctx, "SELECT deleted_at IS NOT NULL FROM "+pgx.Identifier{tc.table}.Sanitize()+" WHERE id=$1", tc.id).Scan(&got); err != nil {
			t.Fatal(err)
		}
		if got != tc.deleted {
			t.Errorf("%s/%s deleted=%v want=%v", tc.table, tc.id, got, tc.deleted)
		}
	}
	var used int64
	if err := conn.QueryRow(ctx, "SELECT storage_used FROM tenants WHERE id=7").Scan(&used); err != nil || used != 175 {
		t.Fatalf("storage=%d err=%v", used, err)
	}
	i := asynq.NewInspector(c.Redis)
	defer i.Close()
	if _, err := i.GetTaskInfo("low", target.ID); err == nil {
		t.Fatal("target delete task still present")
	}
	if _, err := i.GetTaskInfo("low", foreign.ID); err != nil {
		t.Fatal("foreign task removed", err)
	}
	entries, _ = os.ReadDir(backup)
	if len(entries) != 1 {
		t.Fatal("backup missing")
	}
	data, err := os.ReadFile(filepath.Join(backup, entries[0].Name(), "knowledge_bases.json"))
	if err != nil {
		t.Fatal(err)
	}
	var before []map[string]any
	if json.Unmarshal(data, &before) != nil || len(before) != 1 || before[0]["deleted_at"] == nil {
		t.Fatal("backup does not preserve deleted row")
	}
	if err := run(o, c); err != nil {
		t.Fatal("idempotent rerun", err)
	}
	if err := conn.QueryRow(ctx, "SELECT storage_used FROM tenants WHERE id=7").Scan(&used); err != nil || used != 175 {
		t.Fatal("rerun double-counted", err)
	}
}

func TestMissingKBDoesNotCreateRecords(t *testing.T) {
	c, conn, _ := integrationConfig(t)
	o := options{ID: uuid.NewString(), Apply: true, Maintenance: true, BackupDir: t.TempDir()}
	if err := run(o, c); err == nil {
		t.Fatal("missing KB should fail")
	}
	var n int
	if err := conn.QueryRow(context.Background(), "SELECT COUNT(*) FROM knowledge_bases").Scan(&n); err != nil || n != 0 {
		t.Fatal("created empty KB", err)
	}
}

func TestMixedDocumentDeleteBlocksApply(t *testing.T) {
	c, conn, _ := integrationConfig(t)
	id := uuid.NewString()
	ctx := context.Background()
	if _, err := conn.Exec(ctx, `INSERT INTO tenants VALUES(7,NULL,0); INSERT INTO knowledge_bases VALUES($1,7,'target',now()); INSERT INTO knowledges VALUES('target-doc',7,$1,NULL,0);`, pgx.QueryExecModeSimpleProtocol, id); err != nil {
		t.Fatal(err)
	}
	client := asynq.NewClient(c.Redis)
	defer client.Close()
	payload, _ := json.Marshal(map[string]any{"tenant_id": 7, "knowledge_ids": []string{"target-doc", "another-kb-doc"}})
	job, err := client.Enqueue(asynq.NewTask("knowledge:list_delete", payload))
	if err != nil {
		t.Fatal(err)
	}
	o := options{ID: id, Apply: true, Maintenance: true, BackupDir: t.TempDir()}
	if err := run(o, c); err == nil {
		t.Fatal("mixed document deletion must block recovery")
	}
	i := asynq.NewInspector(c.Redis)
	defer i.Close()
	if _, err := i.GetTaskInfo("default", job.ID); err != nil {
		t.Fatal("mixed task was removed", err)
	}
	var deleted bool
	if err := conn.QueryRow(ctx, "SELECT deleted_at IS NOT NULL FROM knowledge_bases WHERE id=$1", id).Scan(&deleted); err != nil || !deleted {
		t.Fatal("blocked apply changed KB", err)
	}
}

func TestSQLFailureRollsBackRestoration(t *testing.T) {
	c, conn, _ := integrationConfig(t)
	id := uuid.NewString()
	ctx := context.Background()
	// Force accounting to fail after restoration updates, so the test exercises
	// rollback across the entire KB + child write transaction.
	if _, err := conn.Exec(ctx, `ALTER TABLE tenants ADD CONSTRAINT accounting_test CHECK(storage_used=0); INSERT INTO tenants VALUES(7,NULL,0); INSERT INTO knowledge_bases VALUES($1,7,'target',now()); INSERT INTO knowledges VALUES('restore-doc',7,$1,now()+interval '1 second',10);`, pgx.QueryExecModeSimpleProtocol, id); err != nil {
		t.Fatal(err)
	}
	o := options{ID: id, Apply: true, Maintenance: true, BackupDir: t.TempDir()}
	if err := run(o, c); err == nil {
		t.Fatal("accounting constraint must abort recovery")
	}
	for _, table := range []string{"knowledge_bases", "knowledges"} {
		var deleted bool
		if err := conn.QueryRow(ctx, "SELECT deleted_at IS NOT NULL FROM "+pgx.Identifier{table}.Sanitize()).Scan(&deleted); err != nil || !deleted {
			t.Fatal("transaction failed to roll back", table, err)
		}
	}
	entries, _ := os.ReadDir(o.BackupDir)
	if len(entries) != 1 {
		t.Fatal("failed recovery must retain before snapshot")
	}
}
