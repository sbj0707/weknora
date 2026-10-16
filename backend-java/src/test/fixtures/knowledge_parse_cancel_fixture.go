//go:build ignore

// Actual Go cancellation service, SQL rows and real tracker/audit/pending-op effects.
package main

import (
	"context"
	"encoding/json"
	"fmt"
	repo "github.com/Tencent/WeKnora/internal/application/repository"
	svc "github.com/Tencent/WeKnora/internal/application/service"
	"github.com/Tencent/WeKnora/internal/config"
	app "github.com/Tencent/WeKnora/internal/errors"
	"github.com/Tencent/WeKnora/internal/logger"
	"github.com/Tencent/WeKnora/internal/types"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
	gl "gorm.io/gorm/logger"
	"os"
	"path/filepath"
	"time"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}
func main() {
	time.Local = time.UTC
	logger.SetOutput(os.Stderr)
	out := []any{}
	for _, state := range []string{"pending", "processing", "finalizing", "cancelled", "completed", "failed", "deleting", "", "unexpected"} {
		for _, summary := range []string{"none", "pending", "processing", "completed", "failed"} {
			out = append(out, scene(state+"-"+summary, state, summary))
		}
	}
	for _, name := range []string{"missing", "foreign-owner", "soft-deleted", "repeat", "no-attempt", "missing-wiki", "missing-audit", "missing-spans", "update-error"} {
		out = append(out, scene(name, "processing", "pending"))
	}
	must(json.NewEncoder(os.Stdout).Encode(out))
}
func scene(name, state, summary string) any {
	dir, e := os.MkdirTemp("", "weknora-go-cancel-")
	must(e)
	defer os.RemoveAll(dir)
	db, e := gorm.Open(sqlite.Open(filepath.Join(dir, "case.db")), &gorm.Config{Logger: gl.Default.LogMode(gl.Silent)})
	must(e)
	sql, e := db.DB()
	must(e)
	defer sql.Close()
	sql.SetMaxOpenConns(1)
	scripts := []string{"000000_init", "000002_knowledge_folder_path", "000006_task_queue_and_dead_letters", "000008_processing_spans_and_pending_subtasks", "000010_knowledge_multi_tags", "000021_knowledge_profiles", "000033_chunk_source_locators"}
	for _, m := range scripts {
		b, e := os.ReadFile("migrations/sqlite/" + m + ".up.sql")
		must(e)
		must(db.Exec(string(b)).Error)
	}
	seeds := []string{
		`INSERT INTO tenants(id,name,business) VALUES(8,'Owner','test'),(9,'Foreign','test')`,
		`INSERT INTO knowledge_bases(id,tenant_id,name,type,creator_id,embedding_model_id,summary_model_id) VALUES('kb',8,'KB','document','owner','','')`,
		`INSERT INTO knowledges(id,tenant_id,knowledge_base_id,type,title,source,description,parse_status,summary_status,error_message,pending_subtasks_count,profile,created_at,updated_at) VALUES('doc',8,'kb','file','Title','test','Original description','` + state + `','` + summary + `','Previous error',3,'{"gist":"Original profile"}','2026-10-14T01:02:03Z','2026-10-14T01:02:03Z')`,
		`INSERT INTO chunks(id,tenant_id,knowledge_id,knowledge_base_id,content,chunk_type,chunk_index,start_at,end_at) VALUES('chunk',8,'doc','kb','Retained content','text',0,0,16)`,
		`INSERT INTO task_pending_ops(tenant_id,task_type,scope,scope_id,dedup_key,op) VALUES(8,'wiki:ingest','knowledge_base','kb','doc','ingest'),(8,'wiki:ingest','knowledge_base','kb','doc','retract'),(8,'wiki:ingest','knowledge_base','other','doc','ingest'),(8,'other:task','knowledge_base','kb','doc','ingest')`,
		`INSERT INTO knowledge_processing_spans(knowledge_id,attempt,span_id,name,kind,status,started_at) VALUES('doc',1,'root-old','knowledge_processing','root','done','2026-10-14T01:02:03Z'),('doc',2,'root','knowledge_processing','root','running','2026-10-14T01:02:03Z')`,
		`INSERT INTO knowledge_processing_spans(knowledge_id,attempt,span_id,parent_span_id,name,kind,status,started_at) VALUES('doc',2,'stage','root','postprocess','stage','running','2026-10-14T01:02:03Z'),('doc',2,'child','stage','postprocess.summary','subspan','pending','2026-10-14T01:02:03Z'),('doc',2,'done','root','embedding','stage','done','2026-10-14T01:02:03Z')`,
	}
	switch name {
	case "missing":
		seeds = append(seeds, `DELETE FROM knowledges WHERE id='doc'`)
	case "foreign-owner":
		seeds = append(seeds, `UPDATE knowledges SET tenant_id=9 WHERE id='doc'`)
	case "soft-deleted":
		seeds = append(seeds, `UPDATE knowledges SET deleted_at='2026-10-14T01:02:03Z' WHERE id='doc'`)
	case "no-attempt":
		seeds = append(seeds, `DELETE FROM knowledge_processing_spans`)
	case "missing-wiki":
		seeds = append(seeds, `DROP TABLE task_pending_ops`)
	case "missing-audit":
		seeds = append(seeds, `DROP TABLE audit_logs`)
	case "missing-spans":
		seeds = append(seeds, `DROP TABLE knowledge_processing_spans`)
	case "update-error":
		seeds = append(seeds, `CREATE TRIGGER fail_cancel BEFORE UPDATE ON knowledges BEGIN SELECT RAISE(ABORT,'synthetic update failure'); END`)
	}
	for _, q := range seeds {
		must(db.Exec(q).Error)
	}
	kr := repo.NewKnowledgeRepository(db)
	spans := repo.NewKnowledgeSpanRepository(db)
	tracker := svc.NewSpanTracker(spans, db)
	audit := svc.NewAuditLogService(repo.NewAuditLogRepository(db))
	pending := repo.NewTaskPendingOpsRepository(db)
	service, e := svc.NewKnowledgeService(&config.Config{}, kr, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, pending, tracker, audit)
	must(e)
	ctx := context.WithValue(context.Background(), types.TenantIDContextKey, uint64(8))
	ctx = context.WithValue(ctx, types.UserIDContextKey, "caller")
	ctx = context.WithValue(ctx, types.TenantRoleContextKey, types.TenantRoleAdmin)
	_, err := service.CancelKnowledgeParse(ctx, "doc")
	if name == "repeat" && err == nil {
		_, err = service.CancelKnowledgeParse(ctx, "doc")
	}
	code := 0
	message := ""
	if err != nil {
		message = err.Error()
		if ae, ok := app.IsAppError(err); ok {
			code = int(ae.Code)
			message = ae.Message
		}
	}
	projection := map[string]any{}
	for label, q := range map[string]string{
		"knowledge": `SELECT id,tenant_id,knowledge_base_id,parse_status,summary_status,error_message,pending_subtasks_count,description,profile,deleted_at FROM knowledges ORDER BY id`,
		"chunks":    `SELECT id,tenant_id,knowledge_id,content,chunk_type FROM chunks ORDER BY id`,
		"pending":   `SELECT tenant_id,task_type,scope,scope_id,dedup_key,op FROM task_pending_ops ORDER BY id`,
		"spans":     `SELECT knowledge_id,attempt,span_id,parent_span_id,name,kind,status,error_code,error_message FROM knowledge_processing_spans ORDER BY attempt,span_id`,
		"audit":     `SELECT tenant_id,actor_user_id,actor_role,action,target_type,target_id,scope_type,scope_id,outcome,details FROM audit_logs ORDER BY id`,
	} {
		rows := []map[string]any{}
		e := db.Raw(q).Scan(&rows).Error
		if e != nil {
			projection[label] = nil
		} else {
			for _, row := range rows {
				for k, v := range row {
					if b, ok := v.([]byte); ok {
						row[k] = string(b)
					}
				}
			}
			projection[label] = rows
		}
	}
	fmt.Fprintf(os.Stderr, "cancel oracle %s error=%s\n", name, message)
	return map[string]any{"name": name, "scripts": scripts, "seed": seeds, "error_code": code, "error_message": message, "projection": projection}
}
