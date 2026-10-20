package pageimage

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"image"
	"image/color"
	"image/jpeg"
	"io"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"github.com/Tencent/WeKnora/internal/application/access"
	"github.com/Tencent/WeKnora/internal/application/repository"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"github.com/google/uuid"
	"github.com/hibiken/asynq"
	"github.com/stretchr/testify/require"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
)

type knowledgeRepo struct {
	interfaces.KnowledgeRepository
	db *gorm.DB
}

func (r knowledgeRepo) GetKnowledgeByID(ctx context.Context, tenant uint64, id string) (*types.Knowledge, error) {
	var k types.Knowledge
	e := r.db.WithContext(ctx).Where("tenant_id = ? AND id = ?", tenant, id).First(&k).Error
	return &k, e
}
func (r knowledgeRepo) GetKnowledgeByIDOnly(ctx context.Context, id string) (*types.Knowledge, error) {
	var k types.Knowledge
	e := r.db.WithContext(ctx).First(&k, "id = ?", id).Error
	return &k, e
}

type chunkRepo struct {
	interfaces.ChunkRepository
	db *gorm.DB
}

func (r chunkRepo) GetChunkByID(ctx context.Context, tenant uint64, id string) (*types.Chunk, error) {
	var c types.Chunk
	e := r.db.WithContext(ctx).Where("tenant_id = ? AND id = ?", tenant, id).First(&c).Error
	return &c, e
}

type kbRepo struct {
	interfaces.KnowledgeBaseRepository
	values map[string]*types.KnowledgeBase
}

func (r kbRepo) GetKnowledgeBaseByIDAndTenant(_ context.Context, id string, tenant uint64) (*types.KnowledgeBase, error) {
	k := r.values[id]
	if k == nil || k.TenantID != tenant {
		return nil, gorm.ErrRecordNotFound
	}
	return k, nil
}

type kbService struct {
	interfaces.KnowledgeBaseService
	values map[string]*types.KnowledgeBase
}

func (r kbService) GetKnowledgeBaseByID(_ context.Context, id string) (*types.KnowledgeBase, error) {
	k := r.values[id]
	if k == nil {
		return nil, gorm.ErrRecordNotFound
	}
	return k, nil
}

type tenantRepo struct{ interfaces.TenantRepository }

func (tenantRepo) GetTenantByID(_ context.Context, id uint64) (*types.Tenant, error) {
	return &types.Tenant{ID: id}, nil
}

type memoryFiles struct {
	interfaces.FileService
	mu    sync.Mutex
	files map[string][]byte
	saves int
}

func (f *memoryFiles) SaveBytes(_ context.Context, b []byte, _ uint64, _ string, _ bool) (string, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	key := uuid.NewString()
	f.files[key] = append([]byte(nil), b...)
	f.saves++
	return key, nil
}
func (f *memoryFiles) GetFile(_ context.Context, key string) (io.ReadCloser, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	b, ok := f.files[key]
	if !ok {
		return nil, os.ErrNotExist
	}
	return io.NopCloser(bytes.NewReader(b)), nil
}
func (f *memoryFiles) DeleteFile(_ context.Context, key string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if _, ok := f.files[key]; !ok {
		return os.ErrNotExist
	}
	delete(f.files, key)
	return nil
}

type taskQueue struct {
	interfaces.TaskEnqueuer
	tasks []*asynq.Task
}

func (q *taskQueue) Enqueue(t *asynq.Task, _ ...asynq.Option) (*asynq.TaskInfo, error) {
	q.tasks = append(q.tasks, t)
	return &asynq.TaskInfo{}, nil
}

type testRenderer struct {
	calls    int
	failPage int
	opens    int
	closes   int
	onRender func()
}

func (r *testRenderer) OpenPDF(context.Context, io.Reader) (interfaces.PDFPageSession, error) {
	r.opens++
	return r, nil
}
func (*testRenderer) PageCount() int { return 3 }
func (r *testRenderer) Close() error { r.closes++; return nil }
func (r *testRenderer) Render(_ context.Context, page int) ([]byte, int, int, error) {
	r.calls++
	if r.onRender != nil {
		r.onRender()
	}
	if r.failPage == page {
		return nil, 0, 0, errors.New("render failed")
	}
	im := image.NewRGBA(image.Rect(0, 0, 16, 24))
	im.Set(0, 0, color.White)
	var b bytes.Buffer
	_ = jpeg.Encode(&b, im, nil)
	return b.Bytes(), 16, 24, nil
}

type fixture struct {
	s        *Service
	db       *gorm.DB
	ctx      context.Context
	k        *types.Knowledge
	kb       *types.KnowledgeBase
	tasks    *taskQueue
	renderer *testRenderer
	files    *memoryFiles
}

func newFixture(t *testing.T) *fixture {
	t.Helper()
	db, e := gorm.Open(sqlite.Open(filepath.Join(t.TempDir(), "pages.db")), &gorm.Config{})
	require.NoError(t, e)
	sqlDB, e := db.DB()
	require.NoError(t, e)
	t.Cleanup(func() { sqlDB.Close() })
	require.NoError(t, db.AutoMigrate(&types.Knowledge{}, &types.Chunk{}, &types.KnowledgeBase{}))
	migration, e := os.ReadFile("../../migrations/sqlite/000035_document_page_images.up.sql")
	require.NoError(t, e)
	require.NoError(t, db.Exec(string(migration)).Error)
	k := &types.Knowledge{ID: "doc", TenantID: 7, KnowledgeBaseID: "kb", Title: "Report", FileType: "pdf", FileHash: "current", FilePath: "original", ParseStatus: types.ParseStatusCompleted}
	require.NoError(t, db.Create(k).Error)
	kb := &types.KnowledgeBase{ID: "kb", TenantID: 7, ImageProcessingConfig: types.ImageProcessingConfig{UsePageImagesForAnswer: true, AutoSavePageImages: true}}
	require.NoError(t, db.Create(kb).Error)
	values := map[string]*types.KnowledgeBase{"kb": kb}
	ctx := types.WithCaller(context.Background(), types.Caller{TenantID: 7, UserID: "owner"})
	ctx, e = access.WithKBTaskWrite(ctx, kb, 7)
	require.NoError(t, e)
	files := &memoryFiles{files: map[string][]byte{"original": []byte("pdf fixture")}}
	q := &taskQueue{}
	renderer := &testRenderer{}
	s := &Service{Assets: repository.NewDocumentPageImageRepository(db), Knowledge: knowledgeRepo{db: db}, KB: kbRepo{values: values}, Chunks: chunkRepo{db: db}, KBService: kbService{values: values}, Tenants: tenantRepo{}, Files: files, Tasks: q, Renderer: renderer}
	return &fixture{s, db, ctx, k, kb, q, renderer, files}
}
func (f *fixture) chunk(t *testing.T, id string, pages ...int) *types.Chunk {
	t.Helper()
	c := &types.Chunk{ID: id, TenantID: 7, KnowledgeBaseID: "kb", KnowledgeID: "doc", IsEnabled: true, Content: "Evidence"}
	for _, p := range pages {
		c.SourceLocators = append(c.SourceLocators, types.SourceLocator{Type: types.SourceLocatorPDF, Mapping: "exact", SourceHash: "current", Page: p})
	}
	require.NoError(t, f.db.Create(c).Error)
	return c
}
func (f *fixture) render(t *testing.T) {
	t.Helper()
	_, e := f.s.Request(f.ctx, "doc", types.PageImageRequest{SourceHash: "current"})
	require.NoError(t, e)
	require.NoError(t, f.s.Handle(f.ctx, f.tasks.tasks[len(f.tasks.tasks)-1]))
}
func (f *fixture) scope() types.SearchTargets {
	return types.SearchTargets{&types.SearchTarget{Type: types.SearchTargetTypeKnowledgeBase, KnowledgeBaseID: "kb", TenantID: 7}}
}
func hit(id string) *types.SearchResult {
	return &types.SearchResult{ID: id, KnowledgeID: "doc", KnowledgeBaseID: "kb"}
}

func TestRequestPermissionRevisionAndManualMapping(t *testing.T) {
	f := newFixture(t)
	c := f.chunk(t, "chunk")
	revision := 0
	_, e := f.s.Request(types.WithCaller(context.Background(), types.Caller{TenantID: 7}), "doc", types.PageImageRequest{SourceHash: "current"})
	require.Error(t, e, "ownership alone must not mint a write grant")
	_, e = f.s.State(types.WithCaller(context.Background(), types.Caller{TenantID: 8}), "doc", "")
	require.Error(t, e, "foreign caller cannot inspect page assets")
	for _, req := range []types.PageImageRequest{
		{SourceHash: "old"}, {SourceHash: "current", ChunkID: "chunk"},
		{SourceHash: "current", ChunkID: "chunk", ContentRevision: &revision},
		{SourceHash: "current", ChunkID: "chunk", ContentRevision: &revision, Manual: true, Pages: []int{4}},
		{SourceHash: "current", Manual: true, Pages: []int{1}},
	} {
		_, e = f.s.Request(f.ctx, "doc", req)
		require.Error(t, e)
	}
	require.Empty(t, f.tasks.tasks)
	state, e := f.s.Request(f.ctx, "doc", types.PageImageRequest{SourceHash: "current", ChunkID: "chunk", ContentRevision: &revision, Manual: true, Pages: []int{2, 2, 1}})
	require.NoError(t, e)
	require.True(t, state.Manual)
	require.Equal(t, []int{1, 2}, state.Pages)
	require.Len(t, state.Images, 2)
	require.NoError(t, f.db.First(c, "id = ?", c.ID).Error)
	require.Empty(t, c.SourceLocators, "manual confirmation never fabricates verified source locators")
	require.NoError(t, f.db.Model(c).Updates(map[string]any{"content_revision": 1, "content": "Edited"}).Error)
	state, e = f.s.State(f.ctx, "doc", "chunk")
	require.NoError(t, e)
	require.Empty(t, state.Pages, "editing invalidates manual association")
	_, e = f.s.Request(f.ctx, "doc", types.PageImageRequest{SourceHash: "current", ChunkID: "chunk", ContentRevision: &revision, Manual: true, Pages: []int{1}})
	require.Error(t, e)
	require.NoError(t, f.db.Model(f.k).Update("file_hash", "replacement").Error)
	require.NoError(t, f.s.Handle(f.ctx, f.tasks.tasks[0]))
	require.Zero(t, f.renderer.calls, "superseded task cannot render")
}

func TestRenderRetryDedupAndLease(t *testing.T) {
	f := newFixture(t)
	f.renderer.failPage = 2
	state, e := f.s.Request(f.ctx, "doc", types.PageImageRequest{SourceHash: "current"})
	require.NoError(t, e)
	require.Len(t, state.Images, 3)
	task := f.tasks.tasks[0]
	opens, closes := f.renderer.opens, f.renderer.closes
	require.Error(t, f.s.Handle(f.ctx, task))
	require.Equal(t, opens+1, f.renderer.opens, "a multi-page job uploads the PDF only once")
	require.Equal(t, closes+1, f.renderer.closes, "a partially failed job must release its DocReader session")
	state, e = f.s.State(f.ctx, "doc", "")
	require.NoError(t, e)
	require.Equal(t, "completed", state.Images[0].Status)
	require.Equal(t, "failed", state.Images[1].Status)
	require.Equal(t, "completed", state.Images[2].Status)
	current, e := f.s.Knowledge.GetKnowledgeByID(f.ctx, 7, "doc")
	require.NoError(t, e)
	require.Equal(t, types.ParseStatusCompleted, current.ParseStatus, "page failure cannot fail successful text ingestion")
	f.renderer.failPage = 0
	require.NoError(t, f.s.Handle(f.ctx, task))
	require.Equal(t, 4, f.renderer.calls)
	require.NoError(t, f.s.Handle(f.ctx, task))
	require.Equal(t, 4, f.renderer.calls, "successful pages must never render twice")
	require.NoError(t, f.s.Assets.Ensure(f.ctx, f.k, []int{1, 2, 3}))
	rows, e := f.s.Assets.List(f.ctx, 7, "doc", "current")
	require.NoError(t, e)
	require.Len(t, rows, 3)
	row := rows[0]
	require.NoError(t, f.db.Model(&row).Update("status", "pending").Error)
	ok, e := f.s.Assets.Claim(f.ctx, row.ID, false, "old-worker")
	require.NoError(t, e)
	require.True(t, ok)
	ok, e = f.s.Assets.Claim(f.ctx, row.ID, false, "new-worker")
	require.NoError(t, e)
	require.False(t, ok)
	require.Error(t, f.s.Handle(f.ctx, task), "busy abandoned lease must be retried rather than forgotten")
	require.NoError(t, f.db.Model(&row).Update("lease_until", time.Now().Add(-time.Minute)).Error)
	ok, e = f.s.Assets.Claim(f.ctx, row.ID, false, "new-worker")
	require.NoError(t, e)
	require.True(t, ok)
	ok, e = f.s.Assets.Finish(f.ctx, &row, "old-worker")
	require.NoError(t, e)
	require.False(t, ok, "superseded worker cannot publish")
}

func TestCollectCurrentEvidenceBudgetAndRealImageBytes(t *testing.T) {
	f := newFixture(t)
	c := f.chunk(t, "a", 1, 2)
	f.chunk(t, "b", 1)
	f.render(t)
	budget := NewBudget(true, 0, 0)
	evidence := f.s.Collect(f.ctx, []*types.SearchResult{hit("a"), hit("b")}, f.scope(), budget)
	require.Len(t, evidence.Images, 2)
	require.ElementsMatch(t, []string{"a", "b"}, evidence.Sources[0].ChunkIDs)
	require.Equal(t, 1, evidence.Sources[0].Page)
	parts := MessageParts("question", []string{"data:image/jpeg;base64,/9j/"}, evidence)
	require.Len(t, parts, 6)
	require.Contains(t, parts[2].Text, "Report")
	require.Contains(t, parts[2].Text, "\"page\":1")
	require.Equal(t, evidence.Images[0], parts[3].ImageURL.URL)
	require.Empty(t, f.s.Collect(f.ctx, []*types.SearchResult{hit("a")}, f.scope(), budget).Images, "Agent calls share page deduplication")
	limited := NewBudget(true, 0, 0)
	limited.MaxImages = 1
	evidence = f.s.Collect(f.ctx, []*types.SearchResult{hit("a")}, f.scope(), limited)
	require.Len(t, evidence.Images, 1)
	require.NotEmpty(t, evidence.Warnings)
	reserved := WithUserImages(f.ctx, []string{"remote-image"})
	limited = NewBudget(true, 0, 0)
	limited.MaxImages = 1
	require.Empty(t, f.s.Collect(reserved, []*types.SearchResult{hit("a")}, f.scope(), limited).Images)
	evidence = f.s.Collect(f.ctx, []*types.SearchResult{hit("a")}, f.scope(), NewBudget(false, 0, 0))
	require.Empty(t, evidence.Images)
	require.NotEmpty(t, evidence.Warnings)
	scope := types.SearchTargets{&types.SearchTarget{Type: types.SearchTargetTypeKnowledge, KnowledgeBaseID: "kb", TenantID: 7, KnowledgeIDs: []string{"another-document"}}}
	require.Empty(t, f.s.Collect(f.ctx, []*types.SearchResult{hit("a")}, scope, NewBudget(true, 0, 0)).Images)
	require.NoError(t, f.db.Model(c).Update("content_revision", 1).Error)
	require.Empty(t, f.s.Collect(f.ctx, []*types.SearchResult{hit("a")}, f.scope(), NewBudget(true, 0, 0)).Images)
	f.kb.ImageProcessingConfig.UsePageImagesForAnswer = false
	require.Empty(t, f.s.Collect(f.ctx, []*types.SearchResult{hit("b")}, f.scope(), NewBudget(true, 0, 0)).Images)
	require.Equal(t, 3, f.renderer.calls, "answer generation only reads saved images")
}

func TestCopyMoveDeleteOwnIndependentAssets(t *testing.T) {
	f := newFixture(t)
	f.chunk(t, "a", 1)
	f.render(t)
	revision := 0
	_, e := f.s.Request(f.ctx, "doc", types.PageImageRequest{SourceHash: "current", ChunkID: "a", ContentRevision: &revision, Manual: true, Pages: []int{1}})
	require.NoError(t, e)
	dst := *f.k
	dst.ID = "copy"
	require.NoError(t, f.db.Create(&dst).Error)
	target := &types.Chunk{ID: "copy-chunk", TenantID: 7, KnowledgeBaseID: "kb", KnowledgeID: "copy", IsEnabled: true}
	require.NoError(t, f.db.Create(target).Error)
	require.NoError(t, f.s.Copy(f.ctx, f.k, &dst, map[string]string{"a": "copy-chunk"}))
	src, e := f.s.Assets.List(f.ctx, 7, "doc", "current")
	require.NoError(t, e)
	copied, e := f.s.Assets.List(f.ctx, 7, "copy", "current")
	require.NoError(t, e)
	require.Len(t, copied, 3)
	saves := f.files.saves
	require.NoError(t, f.s.Copy(f.ctx, f.k, &dst, map[string]string{"a": "copy-chunk"}))
	require.Equal(t, saves, f.files.saves, "clone retries reuse independent destination assets")
	require.NotEqual(t, src[0].StorageKey, copied[0].StorageKey)
	pages, e := f.s.Assets.Links(f.ctx, target, "current")
	require.NoError(t, e)
	require.Equal(t, []int{1}, pages)
	dstKB := &types.KnowledgeBase{ID: "target-kb", TenantID: 7}
	f.s.KB.(kbRepo).values[dstKB.ID] = dstKB
	require.NoError(t, f.s.Move(f.ctx, &dst, dstKB))
	require.NoError(t, f.s.Move(f.ctx, &dst, dstKB), "move is repeatable before ownership checkpoint")
	require.NoError(t, f.s.Delete(f.ctx, 7, []string{"doc"}))
	require.NoError(t, f.s.Delete(f.ctx, 7, []string{"doc"}))
	_, e = f.files.GetFile(f.ctx, copied[0].StorageKey)
	require.Error(t, e, "move released old destination storage")
	moved, e := f.s.Assets.List(f.ctx, 7, "copy", "current")
	require.NoError(t, e)
	require.Equal(t, dstKB.ID, moved[0].KnowledgeBaseID)
	r, e := f.files.GetFile(f.ctx, moved[0].StorageKey)
	require.NoError(t, e)
	r.Close()
	require.NoError(t, f.s.Delete(f.ctx, 7, []string{"copy"}))
	require.Len(t, f.files.files, 1, "only the original PDF fixture remains")
}

func TestAutomaticAndVersionChangeDuringRender(t *testing.T) {
	f := newFixture(t)
	f.kb.ImageProcessingConfig.AutoSavePageImages = false
	require.NoError(t, f.s.AutoSave(f.ctx, f.k, f.kb))
	require.Empty(t, f.tasks.tasks)
	f.kb.ImageProcessingConfig.AutoSavePageImages = true
	require.NoError(t, f.s.AutoSave(f.ctx, f.k, f.kb))
	require.Len(t, f.tasks.tasks, 1)
	f.renderer.onRender = func() { require.NoError(t, f.db.Model(f.k).Update("file_hash", "replacement").Error) }
	require.NoError(t, f.s.Handle(f.ctx, f.tasks.tasks[0]))
	require.Len(t, f.files.files, 1, "superseded render cannot leak its new file")
	rows, e := f.s.Assets.List(f.ctx, 7, "doc", "current")
	require.NoError(t, e)
	for _, r := range rows {
		require.NotEqual(t, "completed", r.Status)
	}
}

func TestPageEvidenceMetadataDoesNotPersistBase64(t *testing.T) {
	e := types.PageImageEvidence{Images: []string{"SENSITIVE_IMAGE_BYTES"}, Sources: []types.PageImageSource{{ID: "image", KnowledgeID: "doc", Page: 3}}}
	data, err := json.Marshal(e)
	require.NoError(t, err)
	require.NotContains(t, string(data), "SENSITIVE_IMAGE_BYTES")
	require.Contains(t, string(data), "sources")
}

func TestDeleteAssetsAfterKBSoftDelete(t *testing.T) {
	f := newFixture(t)
	f.render(t)
	require.NoError(t, f.db.Delete(f.kb).Error)
	delete(f.s.KB.(kbRepo).values, "kb")
	require.NoError(t, f.s.Delete(f.ctx, 7, []string{"doc"}))
	require.Len(t, f.files.files, 1)
}

func TestPageImageConfigurationSurvivesSQLiteJSONText(t *testing.T) {
	f := newFixture(t)
	// SQLite migrations/SQL imports may return JSON as text, PostgreSQL as bytes.
	payload := `{"use_page_images_for_answer":true,"auto_save_page_images":true,"model_id":"keep"}`
	require.NoError(t, f.db.Model(f.kb).Update("image_processing_config", payload).Error)
	var current types.KnowledgeBase
	require.NoError(t, f.db.First(&current, "id = ?", f.kb.ID).Error)
	require.True(t, current.ImageProcessingConfig.UsePageImagesForAnswer)
	require.True(t, current.ImageProcessingConfig.AutoSavePageImages)
	require.Equal(t, "keep", current.ImageProcessingConfig.ModelID)
	var old types.ImageProcessingConfig
	require.NoError(t, old.Scan([]byte(`{"model_id":"legacy"}`)))
	require.False(t, old.UsePageImagesForAnswer)
	require.False(t, old.AutoSavePageImages)
}

func TestManualAssociationCanSupplyImagesWithoutVerifiedLocators(t *testing.T) {
	f := newFixture(t)
	c := f.chunk(t, "edited")
	require.NoError(t, f.db.Model(c).Updates(map[string]any{"content_revision": 3, "content": "Manually edited evidence"}).Error)
	revision := 3
	_, err := f.s.Request(f.ctx, "doc", types.PageImageRequest{SourceHash: "current", ChunkID: "edited", ContentRevision: &revision, Manual: true, Pages: []int{2}})
	require.NoError(t, err)
	require.NoError(t, f.s.Handle(f.ctx, f.tasks.tasks[0]))
	result := hit("edited")
	result.ContentRevision = 3
	evidence := f.s.Collect(f.ctx, []*types.SearchResult{result}, f.scope(), NewBudget(true, 0, 0))
	require.Len(t, evidence.Images, 1)
	require.Equal(t, 2, evidence.Sources[0].Page)
	require.NoError(t, f.db.Model(f.k).Update("file_hash", "new-source").Error)
	require.Empty(t, f.s.Collect(f.ctx, []*types.SearchResult{result}, f.scope(), NewBudget(true, 0, 0)).Images)
}

func TestMergedContextImagesFollowOriginalCitationChunks(t *testing.T) {
	f := newFixture(t)
	f.chunk(t, "kept", 2)
	f.chunk(t, "discarded", 3)
	f.render(t)
	merged := &types.SearchResult{ID: "expanded-parent", KnowledgeID: "doc", KnowledgeBaseID: "kb", CitationSources: []*types.SearchResult{hit("kept")}}
	evidence := f.s.Collect(f.ctx, CitationResults([]*types.SearchResult{merged}), f.scope(), NewBudget(true, 0, 0))
	require.Len(t, evidence.Images, 1)
	require.Equal(t, 2, evidence.Sources[0].Page)
	AnnotateResults([]*types.SearchResult{merged}, evidence)
	require.Len(t, merged.PageImageSources, 1)
	require.Len(t, merged.CitationSources[0].PageImageSources, 1)
}

func TestImageProvenanceStaysWithItsCitationChunk(t *testing.T) {
	first, second := hit("first"), hit("second")
	evidence := types.PageImageEvidence{Sources: []types.PageImageSource{
		{KnowledgeID: "doc", Page: 2, ChunkIDs: []string{"first"}},
		{KnowledgeID: "doc", Page: 7, ChunkIDs: []string{"second"}},
	}}
	AnnotateResults([]*types.SearchResult{first, second}, evidence)
	require.Len(t, first.PageImageSources, 1)
	require.Equal(t, 2, first.PageImageSources[0].Page)
	require.Len(t, second.PageImageSources, 1)
	require.Equal(t, 7, second.PageImageSources[0].Page)
}
