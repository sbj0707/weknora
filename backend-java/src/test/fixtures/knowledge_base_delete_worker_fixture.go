//go:build ignore

// Actual Go KB teardown with SQL repositories and recorded external effect boundaries.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	repo "github.com/Tencent/WeKnora/internal/application/repository"
	svc "github.com/Tencent/WeKnora/internal/application/service"
	"github.com/Tencent/WeKnora/internal/application/service/retriever"
	"github.com/Tencent/WeKnora/internal/logger"
	"github.com/Tencent/WeKnora/internal/models/embedding"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"github.com/hibiken/asynq"
	"gorm.io/driver/postgres"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
	gl "gorm.io/gorm/logger"
	"net/url"
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

type state struct {
	name   string
	events []any
}

func (s *state) event(ctx context.Context, op string, details map[string]any) error {
	if details == nil {
		details = map[string]any{}
	}
	details["op"] = op
	details["canceled"] = ctx.Err() != nil
	deadline, ok := ctx.Deadline()
	details["short_deadline"] = ok && time.Until(deadline) <= 6*time.Second
	s.events = append(s.events, details)
	if strings.Contains(s.name, "wiki-all-error") && strings.HasPrefix(op, "wiki_") {
		return errors.New("controlled " + op + " error")
	}
	if s.name == op+"-error" || (s.name == "skip-store-wiki-error" && op == "wiki_pages") {
		return errors.New("controlled " + op + " error")
	}
	return nil
}

type knowledge struct {
	interfaces.KnowledgeRepository
	s *state
}

func (r knowledge) ListKnowledgeByKnowledgeBaseID(c context.Context, t uint64, k string) ([]*types.Knowledge, error) {
	if e := r.s.event(c, "list", nil); e != nil {
		return nil, e
	}
	return r.KnowledgeRepository.ListKnowledgeByKnowledgeBaseID(c, t, k)
}
func (r knowledge) DeleteKnowledgeList(c context.Context, t uint64, ids []string) error {
	if e := r.s.event(c, "documents", map[string]any{"ids": ids}); e != nil {
		return e
	}
	return r.KnowledgeRepository.DeleteKnowledgeList(c, t, ids)
}

type chunks struct {
	interfaces.ChunkRepository
	s *state
}

func (r chunks) ListImageInfoByKnowledgeIDs(c context.Context, t uint64, ids []string) ([]interfaces.ChunkImageInfo, error) {
	if e := r.s.event(c, "images", map[string]any{"ids": ids}); e != nil {
		return nil, e
	}
	return r.ChunkRepository.ListImageInfoByKnowledgeIDs(c, t, ids)
}
func (r chunks) DeleteChunksByKnowledgeID(c context.Context, t uint64, id string) error {
	if e := r.s.event(c, "chunks", map[string]any{"id": id}); e != nil {
		return e
	}
	return r.ChunkRepository.DeleteChunksByKnowledgeID(c, t, id)
}

type tenant struct {
	interfaces.TenantRepository
	s *state
}

func (r tenant) AdjustStorageUsed(c context.Context, t uint64, d int64) error {
	if e := r.s.event(c, "accounting", map[string]any{"delta": d}); e != nil {
		return e
	}
	return r.TenantRepository.AdjustStorageUsed(c, t, d)
}

type wiki struct {
	interfaces.WikiPageRepository
	s *state
}

func (r wiki) DeleteByKnowledgeBaseID(c context.Context, t uint64, k string) error {
	if e := r.s.event(c, "wiki_pages", nil); e != nil {
		return e
	}
	return r.WikiPageRepository.DeleteByKnowledgeBaseID(c, t, k)
}
func (r wiki) DeleteFoldersByKnowledgeBaseID(c context.Context, t uint64, k string) error {
	if e := r.s.event(c, "wiki_folders", nil); e != nil {
		return e
	}
	return r.WikiPageRepository.DeleteFoldersByKnowledgeBaseID(c, t, k)
}
func (r wiki) DeleteRevisionsByKnowledgeBaseID(c context.Context, t uint64, k string) error {
	if e := r.s.event(c, "wiki_page_revisions", nil); e != nil {
		return e
	}
	return r.WikiPageRepository.DeleteRevisionsByKnowledgeBaseID(c, t, k)
}
func (r wiki) DeleteIssuesByKnowledgeBaseID(c context.Context, t uint64, k string) error {
	if e := r.s.event(c, "wiki_page_issues", nil); e != nil {
		return e
	}
	return r.WikiPageRepository.DeleteIssuesByKnowledgeBaseID(c, t, k)
}

type engine struct {
	interfaces.RetrieveEngineService
	s *state
}

func (r engine) EngineType() types.RetrieverEngineType { return types.SQLiteRetrieverEngineType }
func (r engine) Support() []types.RetrieverType {
	return []types.RetrieverType{types.VectorRetrieverType}
}
func (r engine) DeleteByKnowledgeIDList(c context.Context, ids []string, d int, k string) error {
	return r.s.event(c, "indexes", map[string]any{"ids": ids, "dimensions": d, "type": k})
}

type registry struct {
	interfaces.RetrieveEngineRegistry
	s *state
	e engine
}

func (r registry) GetRetrieveEngineService(t types.RetrieverEngineType) (interfaces.RetrieveEngineService, error) {
	return r.e, nil
}
func (r registry) GetOrLoadByStoreID(c context.Context, t uint64, id string) (interfaces.RetrieveEngineService, error) {
	r.s.event(c, "resolve", nil)
	switch r.s.name {
	case "skip-store", "skip-store-wiki-error":
		return nil, retriever.ErrVectorStoreNotFound
	case "store-error":
		return nil, retriever.ErrVectorStoreUnavailable
	case "store-cancel":
		return nil, context.Canceled
	}
	return r.e, nil
}

type ownership struct{}

func (ownership) StoreOwnedBy(context.Context, string, uint64) (bool, error) { return true, nil }

type embedder struct{ embedding.Embedder }

func (embedder) GetDimensions() int { return 2 }

type models struct {
	interfaces.ModelService
	s *state
}

func (r models) GetEmbeddingModel(c context.Context, id string) (embedding.Embedder, error) {
	if e := r.s.event(c, "model", map[string]any{"id": id}); e != nil {
		return nil, e
	}
	if id == "" {
		return nil, errors.New("empty model")
	}
	return embedder{}, nil
}

type files struct {
	interfaces.FileService
	s *state
}

func (r files) DeleteFile(c context.Context, p string) error {
	return r.s.event(c, "file", map[string]any{"reference": p})
}

type graph struct {
	interfaces.RetrieveGraphRepository
	s *state
}

func (r graph) DelGraph(c context.Context, n []types.NameSpace) error {
	return r.s.event(c, "graph", map[string]any{"namespaces": n})
}

type inspector struct {
	interfaces.TaskInspector
	s *state
}

func (r inspector) CancelTasksForKnowledgeBase(c context.Context, k string, ids, ds []string) (int, int, error) {
	return 0, 0, r.s.event(c, "queue", map[string]any{"ids": ids, "data_source_ids": ds})
}

type pending struct {
	interfaces.TaskPendingOpsRepository
	s *state
}

func (r pending) DeleteByScope(c context.Context, scope, id string) error {
	if e := r.s.event(c, "pending", nil); e != nil {
		return e
	}
	return r.TaskPendingOpsRepository.(interfaces.TaskPendingOpsScopeCleaner).DeleteByScope(c, scope, id)
}
func main() {
	logger.SetOutput(os.Stderr)
	time.Local = time.UTC
	out := []any{}
	scenes := []string{"normal", "empty", "deleted-kb", "deleted-document", "foreign-owner", "zero-owner", "empty-kb", "empty-model", "mixed-models", "model-error", "indexes-error", "file-error", "graph-error", "images-error", "chunks-error", "accounting-error", "documents-error", "list-error", "wiki_pages-error", "wiki_folders-error", "wiki_page_revisions-error", "wiki_page_issues-error", "wiki-all-error", "skip-store", "skip-store-wiki-error", "store-error", "store-cancel", "queue-error", "pending-error", "context-cancel", "parent-deadline", "retry-success", "retry-document-error", "storage-underflow", "malformed-image", "typed-invalid-image", "image-casefold", "image-invalid-caption", "image-duplicate-type", "image-null-entry"}
	for _, name := range scenes {
		out = append(out, run(name))
	}
	b, e := json.MarshalIndent(out, "", "  ")
	must(e)
	must(os.WriteFile(os.Args[1], append(b, '\n'), 0600))
	fmt.Println("exported", len(out), "actual Go KB delete worker cases")
}
func run(name string) any {
	dir, e := os.MkdirTemp("", "weknora-kb-delete-")
	must(e)
	defer os.RemoveAll(dir)
	dsn := os.Getenv("WEKNORA_KB_DELETE_PG_DSN")
	var dialect gorm.Dialector = sqlite.Open(filepath.Join(dir, "case.db"))
	if dsn != "" {
		u, e := url.Parse(dsn)
		must(e)
		if u.Hostname() != "127.0.0.1" || u.Path != "/weknora_kb_delete_parity" {
			panic("owned loopback KB-delete database required")
		}
		dialect = postgres.Open(dsn)
	}
	db, e := gorm.Open(dialect, &gorm.Config{Logger: gl.Default.LogMode(gl.Silent)})
	must(e)
	sql, e := db.DB()
	must(e)
	defer sql.Close()
	sql.SetMaxOpenConns(1)
	if dsn == "" {
		for _, m := range []string{"000000_init", "000003_knowledge_base_auto_tag_config", "000004_memory", "000006_task_queue_and_dead_letters", "000007_system_admin_and_settings", "000008_processing_spans_and_pending_subtasks", "000010_knowledge_multi_tags", "000011_principal_model", "000021_knowledge_profiles", "000031_model_catalog_config", "000033_chunk_source_locators"} {
			b, e := os.ReadFile("migrations/sqlite/" + m + ".up.sql")
			must(e)
			must(db.Exec(string(b)).Error)
		}
	} else {
		must(db.Exec("TRUNCATE tenants,knowledge_bases,knowledges,chunks,chunk_images,wiki_pages,wiki_folders,wiki_page_revisions,wiki_page_issues,task_pending_ops RESTART IDENTITY CASCADE").Error)
	}
	seeds := []string{}
	seed := func(q string) { must(db.Exec(q).Error); seeds = append(seeds, q) }
	seed("INSERT INTO tenants(id,name,business,storage_used) VALUES(1,'Owner','isolated',500),(2,'Peer','isolated',700)")
	seed("INSERT INTO knowledge_bases(id,tenant_id,name,type,embedding_model_id,summary_model_id) VALUES('kb',1,'KB','document','embed',''),('other-kb',1,'Other','document','embed',''),('peer-kb',2,'Peer','document','embed','')")
	for i, row := range []struct {
		id, kb string
		owner  int
	}{{"a", "kb", 1}, {"b", "kb", 1}, {"other", "other-kb", 1}, {"peer", "peer-kb", 2}} {
		seed(fmt.Sprintf("INSERT INTO knowledges(id,tenant_id,knowledge_base_id,type,source,title,parse_status,embedding_model_id,file_path,storage_size,created_at) VALUES('%s',%d,'%s','file','manual','Document','completed','embed','local://%s.txt',100,'2026-01-%02d 00:00:00+00:00')", row.id, row.owner, row.kb, row.id, i+1))
		seed(fmt.Sprintf("INSERT INTO chunks(id,tenant_id,knowledge_base_id,knowledge_id,chunk_index,start_at,end_at,content,image_info) VALUES('%s-chunk',%d,'%s','%s',0,0,4,'body','[{\"url\":\"local://image.png\"},{\"url\":\"local://image.png\"}]')", row.id, row.owner, row.kb, row.id))
		seed(fmt.Sprintf("INSERT INTO wiki_pages(id,tenant_id,knowledge_base_id,slug) VALUES('%s',%d,'%s','%s')", row.id, row.owner, row.kb, row.id))
		seed(fmt.Sprintf("INSERT INTO wiki_folders(id,tenant_id,knowledge_base_id,name) VALUES('%s',%d,'%s','%s')", row.id, row.owner, row.kb, row.id))
		seed(fmt.Sprintf("INSERT INTO wiki_page_revisions(id,tenant_id,knowledge_base_id,page_id,slug,version) VALUES('%s',%d,'%s','%s','%s',1)", row.id, row.owner, row.kb, row.id, row.id))
		seed(fmt.Sprintf("INSERT INTO wiki_page_issues(id,tenant_id,knowledge_base_id,slug,issue_type,description,reported_by) VALUES('%s',%d,'%s','%s','test','test','test')", row.id, row.owner, row.kb, row.id))
		seed(fmt.Sprintf("INSERT INTO task_pending_ops(tenant_id,task_type,scope,scope_id,op) VALUES(%d,'wiki:ingest','knowledge_base','%s','test')", row.owner, row.kb))
	}
	switch name {
	case "empty":
		seed("DELETE FROM knowledges WHERE knowledge_base_id='kb'")
	case "deleted-kb":
		seed("UPDATE knowledge_bases SET deleted_at=CURRENT_TIMESTAMP WHERE id='kb'")
	case "deleted-document":
		seed("UPDATE knowledges SET deleted_at=CURRENT_TIMESTAMP WHERE id='a'")
	case "empty-model":
		seed("UPDATE knowledges SET embedding_model_id='' WHERE id='a'")
	case "mixed-models":
		seed("UPDATE knowledges SET embedding_model_id='other-embed' WHERE id='a'")
	case "storage-underflow":
		seed("UPDATE tenants SET storage_used=50 WHERE id=1")
	case "malformed-image":
		seed("UPDATE chunks SET image_info='bad' WHERE id='a-chunk'")
	case "typed-invalid-image":
		seed("UPDATE chunks SET image_info='[{\"url\":\"local://invalid.png\"},{\"url\":3}]' WHERE id='a-chunk'")
	case "image-casefold":
		seed(`UPDATE chunks SET image_info='[{"URL":"local://folded.png"}]' WHERE id='a-chunk'`)
	case "image-invalid-caption":
		seed(`UPDATE chunks SET image_info='[{"url":"local://invalid.png","caption":3}]' WHERE id='a-chunk'`)
	case "image-duplicate-type":
		seed(`UPDATE chunks SET image_info='[{"url":3,"url":"local://invalid.png"}]' WHERE id='a-chunk'`)
	case "image-null-entry":
		seed(`UPDATE chunks SET image_info='[null]' WHERE id='a-chunk'`)
	}
	s := &state{name: name, events: []any{}}
	kr := knowledge{repo.NewKnowledgeRepository(db), s}
	cr := chunks{repo.NewChunkRepository(db), s}
	tr := tenant{repo.NewTenantRepository(db), s}
	wr := wiki{repo.NewWikiPageRepository(db), s}
	eng := engine{s: s}
	reg := registry{s: s, e: eng}
	service := svc.NewKnowledgeBaseService(repo.NewKnowledgeBaseRepository(db), kr, cr, nil, nil, models{s: s}, reg, ownership{}, tr, files{s: s}, nil, graph{s: s}, nil, inspector{s: s}, pending{repo.NewTaskPendingOpsRepository(db), s}, nil, nil, nil, nil, nil, wr)
	payload := types.KBDeletePayload{TenantID: 1, KnowledgeBaseID: "kb", DataSourceIDs: []string{"ds"}, EffectiveEngines: []types.RetrieverEngineParams{{RetrieverEngineType: types.SQLiteRetrieverEngineType, RetrieverType: types.VectorRetrieverType}}}
	if strings.Contains(name, "store") {
		id := "bound"
		payload.VectorStoreID = &id
	}
	if name == "foreign-owner" {
		payload.TenantID = 2
	}
	if name == "zero-owner" {
		payload.TenantID = 0
	}
	if name == "empty-kb" {
		payload.KnowledgeBaseID = ""
	}
	ctx := context.Background()
	if name == "context-cancel" {
		c, cancel := context.WithCancel(ctx)
		cancel()
		ctx = c
	}
	if name == "parent-deadline" {
		c, cancel := context.WithTimeout(ctx, time.Hour)
		defer cancel()
		ctx = c
	}
	attempts := 1
	if strings.HasPrefix(name, "retry-") {
		attempts = 2
	}
	results := []any{}
	if name == "retry-document-error" {
		s.name = "documents-error"
	}
	for i := 0; i < attempts; i++ {
		b, e := json.Marshal(payload)
		must(e)
		err := func() (err error) {
			defer func() {
				if recovered := recover(); recovered != nil {
					err = fmt.Errorf("panic: %v", recovered)
				}
			}()
			return service.ProcessKBDelete(ctx, asynq.NewTask(types.TypeKBDelete, b))
		}()
		verdict := "success"
		message := ""
		if err != nil {
			verdict = "retry"
			message = err.Error()
			if errors.Is(err, asynq.SkipRetry) {
				verdict = "skip_retry"
			}
		}
		results = append(results, map[string]any{"verdict": verdict, "message": message})
		s.name = name
	}
	projections := map[string]any{}
	for _, table := range []string{"knowledges", "chunks", "knowledge_bases", "wiki_pages", "wiki_folders", "wiki_page_issues"} {
		rows := []map[string]any{}
		scope := "knowledge_base_id,"
		if table == "knowledge_bases" {
			scope = ""
		}
		must(db.Raw("SELECT id,tenant_id," + scope + "CASE WHEN deleted_at IS NULL THEN 0 ELSE 1 END AS deleted FROM " + table + " ORDER BY id").Scan(&rows).Error)
		projections[table] = rows
	}
	for table, query := range map[string]string{"tenants": "SELECT id,storage_used FROM tenants ORDER BY id", "wiki_page_revisions": "SELECT id,tenant_id,knowledge_base_id FROM wiki_page_revisions ORDER BY id", "task_pending_ops": "SELECT tenant_id,task_type,scope,scope_id,op FROM task_pending_ops ORDER BY id"} {
		rows := []map[string]any{}
		must(db.Raw(query).Scan(&rows).Error)
		projections[table] = rows
	}
	return map[string]any{"name": name, "payload": payload, "seed_statements": seeds, "events": s.events, "results": results, "state": projections}
}
