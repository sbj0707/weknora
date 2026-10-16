//go:build ignore

// Production FAQ/tag deletion produces real Asynq index cleanup tasks from admitted KB grants.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	access "github.com/Tencent/WeKnora/internal/application/access"
	repo "github.com/Tencent/WeKnora/internal/application/repository"
	svc "github.com/Tencent/WeKnora/internal/application/service"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"github.com/alicebob/miniredis/v2"
	"github.com/hibiken/asynq"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
	gl "gorm.io/gorm/logger"
	"os"
	"path/filepath"
	"time"
)

func must(e error) {
	if e != nil {
		panic(e)
	}
}

type bases struct {
	interfaces.KnowledgeBaseService
	kb *types.KnowledgeBase
}

func (b *bases) GetKnowledgeBaseByID(context.Context, string) (*types.KnowledgeBase, error) {
	return b.kb, nil
}

type capture struct {
	client *asynq.Client
	fail   bool
	rows   []any
}

func (c *capture) Enqueue(t *asynq.Task, options ...asynq.Option) (*asynq.TaskInfo, error) {
	if c.fail {
		return nil, errors.New("controlled enqueue failure")
	}
	info, e := c.client.Enqueue(t, options...)
	if e != nil {
		return nil, e
	}
	var payload any
	must(json.Unmarshal(t.Payload(), &payload))
	c.rows = append(c.rows, map[string]any{"type": t.Type(), "payload": payload, "queue": info.Queue, "max_retry": info.MaxRetry, "timeout": info.Timeout / time.Millisecond})
	return info, nil
}
func main() {
	must(os.Setenv("RETRIEVE_DRIVER", "sqlite"))
	out := []any{}
	for _, name := range []string{"force", "content-only", "exclude", "all-excluded", "enqueue-failure", "no-force", "bound-snapshot", "default-engines", "no-content"} {
		folder, e := os.MkdirTemp("", "weknora-go-index-producer-")
		must(e)
		db, e := gorm.Open(sqlite.Open(filepath.Join(folder, "db.sqlite")), &gorm.Config{Logger: gl.Default.LogMode(gl.Silent)})
		must(e)
		sql, e := db.DB()
		must(e)
		sql.SetMaxOpenConns(1)
		for _, m := range []string{"000000_init", "000003_knowledge_base_auto_tag_config", "000007_system_admin_and_settings", "000008_processing_spans_and_pending_subtasks", "000010_knowledge_multi_tags", "000011_principal_model", "000031_model_catalog_config", "000033_chunk_source_locators"} {
			b, e := os.ReadFile("migrations/sqlite/" + m + ".up.sql")
			must(e)
			must(db.Exec(string(b)).Error)
		}
		must(db.Exec("INSERT INTO tenants(id,name,business) VALUES(1,'owner','isolated')").Error)
		must(db.Exec("INSERT INTO knowledge_bases(id,tenant_id,name,type,embedding_model_id,summary_model_id) VALUES('kb',1,'KB','faq','embedding','')").Error)
		must(db.Exec("INSERT INTO knowledge_tags(id,tenant_id,knowledge_base_id,name,seq_id) VALUES('tag',1,'kb','Tag',1)").Error)
		ids := []string{"chunk-1", "chunk-2"}
		if name == "no-content" {
			ids = nil
		}
		for i, id := range ids {
			must(db.Exec("INSERT INTO chunks(id,tenant_id,knowledge_base_id,knowledge_id,chunk_type,tag_id,seq_id,chunk_index,start_at,end_at,content) VALUES(?,1,'kb','faq-doc','faq','tag',?,0,0,0,'FAQ content')", id, i+1).Error)
		}
		kb := &types.KnowledgeBase{ID: "kb", TenantID: 1, Name: "KB", Type: "faq", EmbeddingModelID: "embedding"}
		if name == "bound-snapshot" {
			store := "bound-store-at-admission"
			kb.VectorStoreID = &store
		}
		tenant := &types.Tenant{ID: 1, RetrieverEngines: types.RetrieverEngines{Engines: []types.RetrieverEngineParams{{RetrieverEngineType: types.SQLiteRetrieverEngineType, RetrieverType: types.VectorRetrieverType}, {RetrieverEngineType: types.SQLiteRetrieverEngineType, RetrieverType: types.KeywordsRetrieverType}}}}
		if name == "default-engines" {
			tenant.RetrieverEngines.Engines = nil
		}
		ctx := types.WithCaller(context.Background(), types.Caller{UserID: "owner", TenantID: 1, Role: types.TenantRoleOwner})
		granted, e := access.ResolveKB(ctx, access.KBRequest{Caller: types.CallerFromContext(ctx)}, kb, types.OrgRoleEditor, nil, nil)
		must(e)
		ctx = granted.Context(ctx)
		ctx = context.WithValue(ctx, types.TenantInfoContextKey, tenant)
		redis, e := miniredis.Run()
		must(e)
		client := asynq.NewClient(asynq.RedisClientOpt{Addr: redis.Addr()})
		captured := &capture{client: client, fail: name == "enqueue-failure", rows: []any{}}
		service, e := svc.NewKnowledgeTagService(&bases{kb: kb}, repo.NewKnowledgeTagRepository(db), repo.NewKnowledgeRepository(db), repo.NewChunkRepository(db), nil, nil, nil, captured, nil, nil, nil)
		must(e)
		excluded := []string{}
		if name == "exclude" {
			excluded = []string{"chunk-2"}
		}
		if name == "all-excluded" {
			excluded = ids
		}
		force := name != "no-force"
		content := name == "content-only"
		err := service.DeleteTag(ctx, "tag", force, content, excluded)
		message := ""
		if err != nil {
			message = err.Error()
		}
		var active, deleted, tagCount int64
		db.Model(&types.Chunk{}).Count(&active)
		db.Unscoped().Model(&types.Chunk{}).Where("deleted_at IS NOT NULL").Count(&deleted)
		db.Model(&types.KnowledgeTag{}).Count(&tagCount)
		out = append(out, map[string]any{"name": name, "base": kb, "engines": tenant.GetEffectiveEngines(), "deleted_ids": func() []string {
			var v []string
			db.Unscoped().Model(&types.Chunk{}).Where("deleted_at IS NOT NULL").Order("id").Pluck("id", &v)
			return v
		}(), "error": message, "tasks": captured.rows, "active_chunks": active, "deleted_chunks": deleted, "tags": tagCount})
		client.Close()
		redis.Close()
		sql.Close()
		os.RemoveAll(folder)
	}
	b, e := json.Marshal(out)
	must(e)
	must(os.WriteFile(os.Args[1], append(b, '\n'), 0600))
	fmt.Println("exported", len(out), "production FAQ/tag index producers")
}
