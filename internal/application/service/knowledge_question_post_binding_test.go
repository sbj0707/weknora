package service

import (
	"context"
	"errors"
	repo "github.com/Tencent/WeKnora/internal/application/repository"
	filesvc "github.com/Tencent/WeKnora/internal/application/service/file"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/hibiken/asynq"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
	"os"
	"path/filepath"
	"testing"
)

// Exercise the production postprocess handler, not a second implementation of its guard.
func TestQuestionFanoutRejectsWrongBaseAndClosesActualAttempt(t *testing.T) {
	db, err := gorm.Open(sqlite.Open(filepath.Join(t.TempDir(), "test.db")), &gorm.Config{})
	if err != nil {
		t.Fatal(err)
	}
	sql, err := db.DB()
	if err != nil {
		t.Fatal(err)
	}
	defer sql.Close()
	sql.SetMaxOpenConns(1)
	for _, m := range []string{"000000_init", "000002_knowledge_folder_path", "000003_knowledge_base_auto_tag_config", "000004_memory", "000007_system_admin_and_settings", "000008_processing_spans_and_pending_subtasks", "000010_knowledge_multi_tags", "000011_principal_model", "000021_knowledge_profiles", "000031_model_catalog_config", "000033_chunk_source_locators"} {
		body, e := os.ReadFile("../../../migrations/sqlite/" + m + ".up.sql")
		if e != nil {
			t.Fatal(e)
		}
		if e = db.Exec(string(body)).Error; e != nil {
			t.Fatal(e)
		}
	}
	tr := repo.NewTenantRepository(db)
	kr := repo.NewKnowledgeRepository(db)
	cr := repo.NewChunkRepository(db)
	br := repo.NewKnowledgeBaseRepository(db)
	ctx := context.WithValue(context.Background(), types.TenantIDContextKey, uint64(1))
	if e := tr.CreateTenant(ctx, &types.Tenant{ID: 1, Name: "Owner", Business: "test", Status: "active"}); e != nil {
		t.Fatal(e)
	}
	if e := br.CreateKnowledgeBase(ctx, &types.KnowledgeBase{ID: "kb", TenantID: 1, Name: "KB", Type: "document"}); e != nil {
		t.Fatal(e)
	}
	if e := kr.CreateKnowledge(ctx, &types.Knowledge{ID: "doc", TenantID: 1, KnowledgeBaseID: "kb", Type: "file", ParseStatus: "processing", EnableStatus: "enabled"}); e != nil {
		t.Fatal(e)
	}
	if e := cr.CreateChunks(ctx, []*types.Chunk{{ID: "bad", TenantID: 1, KnowledgeID: "doc", KnowledgeBaseID: "different-kb", Content: "Untrusted wrong-base row", ChunkType: types.ChunkTypeText}}); e != nil {
		t.Fatal(e)
	}
	tracker := NewSpanTracker(repo.NewKnowledgeSpanRepository(db), db)
	_, attempt, e := tracker.OpenAttempt(ctx, "doc", "")
	if e != nil {
		t.Fatal(e)
	}
	local := filesvc.NewLocalFileService(t.TempDir(), "")
	base := NewKnowledgeBaseService(br, kr, cr, nil, nil, nil, nil, nil, tr, local, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil)
	chunks := NewChunkService(cr, kr, br, nil, nil, nil, nil, nil)
	handler := NewKnowledgePostProcessService(kr, base, chunks, cr, nil, nil, nil, tracker)
	e = handler.Handle(ctx, asynq.NewTask(types.TypeKnowledgePostProcess, []byte(`{"tenant_id":1,"knowledge_id":"doc","knowledge_base_id":"kb","attempt":1}`)))
	if !errors.Is(e, asynq.SkipRetry) {
		t.Fatalf("expected terminal scope rejection,got %v", e)
	}
	var spans []types.KnowledgeProcessingSpan
	if e = db.Where("knowledge_id=? AND attempt=?", "doc", attempt).Find(&spans).Error; e != nil {
		t.Fatal(e)
	}
	var found int
	for _, span := range spans {
		if span.Kind == types.SpanKindRoot || span.Name == types.StagePostProcess {
			found++
			if span.Status != types.SpanStatusFailed {
				t.Fatalf("scope rejection left %s in %s", span.Name, span.Status)
			}
		}
	}
	if found != 2 {
		t.Fatalf("root/post span count=%d", found)
	}
	var knowledge types.Knowledge
	if e = db.Where("id='doc'").First(&knowledge).Error; e != nil {
		t.Fatal(e)
	}
	if knowledge.ParseStatus != "processing" || knowledge.PendingSubtasksCount != 0 {
		t.Fatalf("rejection mutated handoff: %+v", knowledge)
	}
}
