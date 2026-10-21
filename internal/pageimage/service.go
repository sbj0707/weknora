// Package pageimage manages version-bound PDF page assets and answer evidence.
package pageimage

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/Tencent/WeKnora/internal/application/access"
	"github.com/Tencent/WeKnora/internal/application/repository"
	apperrors "github.com/Tencent/WeKnora/internal/errors"
	"github.com/Tencent/WeKnora/internal/logger"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"github.com/google/uuid"
	"github.com/hibiken/asynq"
	"gorm.io/gorm"
	"gorm.io/gorm/clause"
)

const maxPDFBytes = 256 << 20
const maxImageBytes = 4 << 20

type Service struct {
	Assets    *repository.DocumentPageImageRepository
	Knowledge interfaces.KnowledgeRepository
	KB        interfaces.KnowledgeBaseRepository
	Chunks    interfaces.ChunkRepository
	KBService interfaces.KnowledgeBaseService
	Shares    interfaces.KBShareService
	Storage   interfaces.StorageBackendResolver
	Tenants   interfaces.TenantRepository
	Files     interfaces.FileService
	Catalog   interfaces.ResourceCatalog
	Tasks     interfaces.TaskEnqueuer
	Renderer  interfaces.PDFPageRenderer
}

func NewService(assets *repository.DocumentPageImageRepository, knowledge interfaces.KnowledgeRepository, kb interfaces.KnowledgeBaseRepository, chunks interfaces.ChunkRepository, kbs interfaces.KnowledgeBaseService, storage interfaces.StorageBackendResolver, tenants interfaces.TenantRepository, files interfaces.FileService, catalog interfaces.ResourceCatalog, tasks interfaces.TaskEnqueuer, shares interfaces.KBShareService, reader interfaces.DocumentReader) interfaces.DocumentPageImageService {
	renderer, _ := reader.(interfaces.PDFPageRenderer)
	return &Service{Assets: assets, Knowledge: knowledge, KB: kb, Chunks: chunks, KBService: kbs, Storage: storage, Tenants: tenants, Files: files, Catalog: catalog, Tasks: tasks, Renderer: renderer, Shares: shares}
}

func (s *Service) load(ctx context.Context, id string, write bool) (*types.Knowledge, *types.KnowledgeBase, error) {
	k, err := s.Knowledge.GetKnowledgeByIDOnly(ctx, id)
	if err != nil || k == nil {
		return nil, nil, apperrors.NewNotFoundError("文档不存在")
	}
	kb, err := s.KBService.GetKnowledgeBaseByID(ctx, k.KnowledgeBaseID)
	if err != nil {
		return nil, nil, err
	}
	if kb.TenantID != k.TenantID {
		return nil, nil, apperrors.NewForbiddenError("文档归属不一致")
	}
	if err := access.AuthorizeKBAccess(ctx, s.Shares, []*types.KnowledgeBase{kb}); err != nil {
		return nil, nil, err
	}
	if write {
		if err := access.RequireKBWrite(ctx, kb); err != nil {
			return nil, nil, apperrors.NewForbiddenError("无权修改该知识库")
		}
	}
	if k.ParseStatus == types.ParseStatusDeleting {
		return nil, nil, apperrors.NewConflictError("文档正在删除")
	}
	return k, kb, nil
}

func supported(k *types.Knowledge) bool {
	return strings.EqualFold(strings.TrimPrefix(k.FileType, "."), "pdf") && k.FilePath != "" && k.FileHash != ""
}

// Only current, verified mappings may automatically contribute images.
func AutomaticPages(chunk *types.Chunk, hash string) []int {
	seen := map[int]bool{}
	for _, l := range chunk.SourceLocators {
		if l.Type == types.SourceLocatorPDF && l.Mapping == "exact" && l.SourceHash == hash && hash != "" && l.Page > 0 {
			seen[l.Page] = true
		}
	}
	pages := make([]int, 0, len(seen))
	for p := range seen {
		pages = append(pages, p)
	}
	sort.Ints(pages)
	return pages
}

func (s *Service) chunkPages(ctx context.Context, k *types.Knowledge, id string) ([]int, bool, *types.Chunk, error) {
	chunk, err := s.Chunks.GetChunkByID(ctx, k.TenantID, id)
	if err != nil || chunk == nil || chunk.KnowledgeID != k.ID {
		return nil, false, nil, apperrors.NewNotFoundError("Chunk 不属于当前文档")
	}
	pages, err := s.Assets.Links(ctx, chunk, k.FileHash)
	if err != nil {
		return nil, false, nil, err
	}
	if len(pages) > 0 {
		return pages, true, chunk, nil
	}
	return AutomaticPages(chunk, k.FileHash), false, chunk, nil
}

func (s *Service) State(ctx context.Context, id, chunkID string) (*types.PageImageState, error) {
	k, kb, err := s.load(ctx, id, false)
	if err != nil {
		return nil, err
	}
	state := &types.PageImageState{Enabled: kb.ImageProcessingConfig.UsePageImagesForAnswer, Supported: supported(k), SourceHash: k.FileHash, Pages: []int{}, Images: []types.DocumentPageImage{}}
	if !state.Supported {
		return state, nil
	}
	if chunkID != "" {
		state.Pages, state.Manual, _, err = s.chunkPages(ctx, k, chunkID)
		if err != nil {
			return nil, err
		}
	}
	state.Images, err = s.Assets.List(ctx, k.TenantID, k.ID, k.FileHash)
	return state, err
}

func (s *Service) fileService(ctx context.Context, kb *types.KnowledgeBase, path string) (interfaces.FileService, error) {
	tenant, err := s.Tenants.GetTenantByID(ctx, kb.TenantID)
	if err != nil {
		return nil, err
	}
	backend, provider := "", kb.GetStorageProvider()
	if kb.StorageBackendID != nil {
		backend = *kb.StorageBackendID
	}
	if path != "" {
		if _, ok := types.ParseResourcePath(path); ok && s.Catalog != nil {
			resource, e := s.Catalog.Resolve(ctx, path)
			if e != nil {
				return nil, e
			}
			if resource.TenantID != kb.TenantID {
				return nil, apperrors.NewForbiddenError("图片资源归属不一致")
			}
			backend, provider = resource.StorageBackendID, resource.Provider
		} else if b, inner, ok := types.ParseStorageBackendPath(path); ok {
			backend, provider = b, types.ParseProviderScheme(inner)
		} else if p := types.InferStorageFromFilePath(path); p != "" {
			provider = p
		}
	}
	if s.Storage == nil {
		return s.Files, nil
	}
	fs, _, err := s.Storage.ResolveFileService(ctx, tenant, backend, provider, os.Getenv("LOCAL_STORAGE_BASE_DIR"))
	return fs, err
}

func (s *Service) sourceFile(ctx context.Context, k *types.Knowledge, kb *types.KnowledgeBase) (string, func(), error) {
	fs, err := s.fileService(ctx, kb, k.FilePath)
	if err != nil {
		return "", func() {}, err
	}
	r, err := fs.GetFile(ctx, k.FilePath)
	if err != nil {
		return "", func() {}, err
	}
	defer r.Close()
	f, err := os.CreateTemp("", "weknora-page-source-*.pdf")
	if err != nil {
		return "", func() {}, err
	}
	cleanup := func() { os.Remove(f.Name()) }
	n, err := io.Copy(f, io.LimitReader(r, maxPDFBytes+1))
	closeErr := f.Close()
	if err == nil {
		err = closeErr
	}
	if n > maxPDFBytes {
		err = apperrors.NewBadRequestError("PDF 超过页面图片处理大小限制（256 MiB）")
	}
	if err != nil {
		cleanup()
		return "", func() {}, err
	}
	return f.Name(), cleanup, nil
}

type taskPayload struct {
	TenantID        uint64 `json:"tenant_id"`
	KnowledgeID     string `json:"knowledge_id"`
	KnowledgeBaseID string `json:"knowledge_base_id"`
	SourceHash      string `json:"source_hash"`
	Pages           []int  `json:"pages,omitempty"`
	Force           bool   `json:"force,omitempty"`
	Automatic       bool   `json:"automatic,omitempty"`
}

func (s *Service) enqueue(k *types.Knowledge, pages []int, force, automatic bool) error {
	if s.Tasks == nil {
		return apperrors.NewServiceUnavailableError("页面图片任务服务不可用")
	}
	payload, _ := json.Marshal(taskPayload{k.TenantID, k.ID, k.KnowledgeBaseID, k.FileHash, pages, force, automatic})
	_, err := s.Tasks.Enqueue(asynq.NewTask(types.TypeDocumentPageImages, payload), asynq.Queue(types.QueueMultimodal), asynq.MaxRetry(10), asynq.Timeout(2*time.Hour))
	return err
}

func (s *Service) Request(ctx context.Context, id string, req types.PageImageRequest) (*types.PageImageState, error) {
	k, kb, err := s.load(ctx, id, true)
	if err != nil {
		return nil, err
	}
	if !kb.ImageProcessingConfig.UsePageImagesForAnswer {
		return nil, apperrors.NewBadRequestError("请先开启使用页面图片辅助回答")
	}
	if !supported(k) {
		return nil, apperrors.NewBadRequestError("页面图片目前仅支持有原文件的 PDF 文档")
	}
	if req.SourceHash == "" || req.SourceHash != k.FileHash {
		return nil, apperrors.NewConflictError("原文件已变化，请刷新后重试")
	}
	pages := req.Pages
	var chunk *types.Chunk
	if req.ChunkID != "" {
		associated, _, c, e := s.chunkPages(ctx, k, req.ChunkID)
		if e != nil {
			return nil, e
		}
		chunk = c
		if req.ContentRevision == nil || *req.ContentRevision != chunk.ContentRevision {
			return nil, apperrors.NewConflictError("Chunk 已变化，请先保存并刷新后重试")
		}
		if !req.Manual {
			if len(pages) > 0 {
				return nil, apperrors.NewBadRequestError("指定页面需要确认手工关联")
			}
			pages = associated
			if len(pages) == 0 {
				return nil, apperrors.NewBadRequestError("无有效原文定位，请在原文预览中选择并确认页面")
			}
		}
	} else if req.Manual {
		return nil, apperrors.NewBadRequestError("手工关联必须指定 Chunk")
	}
	path, cleanup, err := s.sourceFile(ctx, k, kb)
	if err != nil {
		return nil, err
	}
	defer cleanup()
	document, err := s.openPDF(ctx, path)
	if err != nil {
		return nil, apperrors.NewServiceUnavailableError(err.Error())
	}
	defer document.Close()
	count := document.PageCount()
	seen := map[int]bool{}
	for _, p := range pages {
		if p < 1 || p > count {
			return nil, apperrors.NewBadRequestError("页面超出原文范围")
		}
		seen[p] = true
	}
	if len(pages) == 0 {
		if req.Manual {
			return nil, apperrors.NewBadRequestError("请至少选择一页")
		}
		for p := 1; p <= count; p++ {
			seen[p] = true
		}
	}
	pages = make([]int, 0, len(seen))
	for p := range seen {
		pages = append(pages, p)
	}
	sort.Ints(pages)
	// File may have been replaced while the renderer opened it.
	current, err := s.Knowledge.GetKnowledgeByID(ctx, k.TenantID, k.ID)
	if err != nil {
		return nil, err
	}
	if current.FileHash != k.FileHash || current.KnowledgeBaseID != kb.ID {
		return nil, apperrors.NewConflictError("原文件或文档归属已变化")
	}
	if req.Manual {
		user, _ := types.UserIDFromContext(ctx)
		if err := s.Assets.SetLinks(ctx, k, chunk, pages, user); err != nil {
			return nil, apperrors.NewConflictError("Chunk 已变化，请刷新后重试")
		}
	}
	if err := s.Assets.Ensure(ctx, k, pages); err != nil {
		return nil, err
	}
	if req.Force {
		if err := s.Assets.DB.WithContext(ctx).Model(&types.DocumentPageImage{}).Where("tenant_id = ? AND knowledge_id = ? AND source_hash = ? AND page_number IN ? AND render_profile = ?", k.TenantID, k.ID, k.FileHash, pages, types.PageImageRenderProfile).Where("lease_until IS NULL OR lease_until < ?", time.Now()).Updates(map[string]any{"status": "pending", "error_message": ""}).Error; err != nil {
			return nil, err
		}
	}
	if err := s.enqueue(k, pages, req.Force, false); err != nil {
		return nil, err
	}
	return s.State(ctx, k.ID, req.ChunkID)
}

func (s *Service) AutoSave(ctx context.Context, k *types.Knowledge, kb *types.KnowledgeBase) error {
	if !supported(k) || !kb.ImageProcessingConfig.UsePageImagesForAnswer || !kb.ImageProcessingConfig.AutoSavePageImages {
		return nil
	}
	return s.enqueue(k, nil, false, true)
}

func (s *Service) Handle(ctx context.Context, task *asynq.Task) (err error) {
	var p taskPayload
	if err := json.Unmarshal(task.Payload(), &p); err != nil {
		return fmt.Errorf("invalid page image task: %w", asynq.SkipRetry)
	}
	defer func() {
		if err == nil {
			return
		}
		logger.Warnf(ctx, "[PageImages] task failed document=%s: %v", p.KnowledgeID, err)
		// Downloads/storage outages must not leave requested pages waiting forever.
		cleanupCtx, cancelCleanup := context.WithTimeout(context.WithoutCancel(ctx), 5*time.Second)
		defer cancelCleanup()
		q := s.Assets.DB.WithContext(cleanupCtx).Model(&types.DocumentPageImage{}).
			Where("tenant_id = ? AND knowledge_id = ? AND source_hash = ? AND status <> ?", p.TenantID, p.KnowledgeID, p.SourceHash, "completed").
			Where("lease_until IS NULL OR lease_until < ?", time.Now())
		if len(p.Pages) > 0 {
			q = q.Where("page_number IN ?", p.Pages)
		}
		_ = q.Updates(map[string]any{"status": "failed", "error_message": "页面图片任务失败，可重试；原有文本索引不受影响"}).Error
	}()
	k, err := s.Knowledge.GetKnowledgeByID(ctx, p.TenantID, p.KnowledgeID)
	if errors.Is(err, gorm.ErrRecordNotFound) || errors.Is(err, repository.ErrKnowledgeNotFound) {
		return nil
	}
	if err != nil {
		return err
	}
	kb, err := s.KB.GetKnowledgeBaseByIDAndTenant(ctx, p.KnowledgeBaseID, p.TenantID)
	if errors.Is(err, gorm.ErrRecordNotFound) || errors.Is(err, repository.ErrKnowledgeBaseNotFound) {
		return nil
	}
	if err != nil {
		return err
	}
	if k.FileHash != p.SourceHash || k.KnowledgeBaseID != kb.ID || k.ParseStatus == types.ParseStatusDeleting || !kb.ImageProcessingConfig.UsePageImagesForAnswer || (p.Automatic && !kb.ImageProcessingConfig.AutoSavePageImages) {
		return nil
	}
	path, cleanup, err := s.sourceFile(ctx, k, kb)
	if err != nil {
		return err
	}
	defer cleanup()
	document, err := s.openPDF(ctx, path)
	if err != nil {
		return err
	}
	defer document.Close()
	if len(p.Pages) == 0 {
		count := document.PageCount()
		for n := 1; n <= count; n++ {
			p.Pages = append(p.Pages, n)
		}
		if err := s.Assets.Ensure(ctx, k, p.Pages); err != nil {
			return err
		}
	}
	rows, err := s.Assets.List(ctx, k.TenantID, k.ID, k.FileHash)
	if err != nil {
		return err
	}
	wanted := map[int]bool{}
	for _, n := range p.Pages {
		wanted[n] = true
	}
	var failures []error
	for i := range rows {
		if !wanted[rows[i].PageNumber] {
			continue
		}
		if err := ctx.Err(); err != nil {
			return err
		}
		current, e := s.Knowledge.GetKnowledgeByID(ctx, k.TenantID, k.ID)
		if errors.Is(e, gorm.ErrRecordNotFound) || errors.Is(e, repository.ErrKnowledgeNotFound) {
			return nil
		}
		if e != nil {
			return e
		}
		if current.FileHash != k.FileHash || current.KnowledgeBaseID != kb.ID || current.ParseStatus == types.ParseStatusDeleting {
			return nil
		}
		latest, e := s.KB.GetKnowledgeBaseByIDAndTenant(ctx, kb.ID, kb.TenantID)
		if e != nil {
			return e
		}
		if !latest.ImageProcessingConfig.UsePageImagesForAnswer || (p.Automatic && !latest.ImageProcessingConfig.AutoSavePageImages) {
			return nil
		}
		row := &rows[i]
		token := uuid.NewString()
		claimed, e := s.Assets.Claim(ctx, row.ID, false, token)
		if e != nil {
			return e
		}
		if !claimed {
			if row.Status != "completed" {
				failures = append(failures, fmt.Errorf("page renderer lease is busy"))
			}
			continue
		}
		oldKey := row.StorageKey
		err = s.renderAndStore(ctx, k, kb, document, row)
		if err != nil {
			row.Status = "failed"
			row.ErrorMessage = "页面图片生成失败，请检查 DocReader 或存储后重试"
			failures = append(failures, err)
		} else {
			row.Status = "completed"
			row.ErrorMessage = ""
		}
		// Finish only if document and KB are still current. A concurrent delete removes this row.
		current, e = s.Knowledge.GetKnowledgeByID(ctx, k.TenantID, k.ID)
		valid := e == nil && current.FileHash == k.FileHash && current.KnowledgeBaseID == kb.ID && current.ParseStatus != types.ParseStatusDeleting
		if !valid {
			if row.StorageKey != oldKey {
				s.removeObject(ctx, kb, row.StorageKey, row.KnowledgeID)
			}
			return nil
		}
		logger.Infof(ctx, "[PageImages] document=%s page=%d asset=%s status=%s bytes=%d", k.ID, row.PageNumber, row.ID, row.Status, row.Size)
		ok, e := s.Assets.Finish(ctx, row, token)
		if e != nil || !ok {
			if row.StorageKey != oldKey {
				s.removeObject(ctx, kb, row.StorageKey, row.KnowledgeID)
			}
			if e != nil {
				return e
			}
		} else if err == nil && oldKey != "" && oldKey != row.StorageKey {
			s.removeObject(ctx, kb, oldKey, k.ID)
		}
	}
	return errors.Join(failures...)
}

func (s *Service) renderAndStore(ctx context.Context, k *types.Knowledge, kb *types.KnowledgeBase, document interfaces.PDFPageSession, row *types.DocumentPageImage) error {
	data, w, h, err := document.Render(ctx, row.PageNumber)
	if err != nil {
		return err
	}
	if len(data) > maxImageBytes || len(data) == 0 {
		return fmt.Errorf("page image exceeds size limit")
	}
	fs, err := s.fileService(ctx, kb, "")
	if err != nil {
		return err
	}
	key, err := s.saveImage(ctx, fs, k, row.PageNumber, data)
	if err != nil {
		return err
	}
	row.StorageKey = key
	row.Width = w
	row.Height = h
	row.Size = int64(len(data))
	return nil
}

func (s *Service) Open(ctx context.Context, doc, id string) (io.ReadCloser, string, error) {
	k, kb, err := s.load(ctx, doc, false)
	if err != nil {
		return nil, "", err
	}
	var row types.DocumentPageImage
	if err := s.Assets.DB.WithContext(ctx).Where("id = ? AND tenant_id = ? AND knowledge_id = ? AND source_hash = ? AND status = ?", id, k.TenantID, k.ID, k.FileHash, "completed").First(&row).Error; err != nil {
		return nil, "", apperrors.NewNotFoundError("页面图片不存在或已失效")
	}
	fs, err := s.fileService(ctx, kb, row.StorageKey)
	if err != nil {
		return nil, "", err
	}
	r, err := fs.GetFile(ctx, row.StorageKey)
	return r, row.MimeType, err
}

func NewBudget(vision bool, reserved int, reservedBytes int64) *types.PageImageBudget {
	return &types.PageImageBudget{Seen: map[string]bool{}, Count: reserved, Bytes: reservedBytes, SupportsVision: vision, MaxImages: positiveEnv("PAGE_IMAGE_MAX_IMAGES", 6), MaxBytes: int64(positiveEnv("PAGE_IMAGE_MAX_BYTES", 12<<20))}
}
func positiveEnv(key string, fallback int) int {
	v, e := strconv.Atoi(os.Getenv(key))
	if e != nil || v <= 0 {
		return fallback
	}
	return v
}

func (s *Service) Collect(ctx context.Context, results []*types.SearchResult, scope types.SearchTargets, budget *types.PageImageBudget) types.PageImageEvidence {
	var evidence types.PageImageEvidence
	if budget == nil {
		return evidence
	}
	budget.Mu.Lock()
	defer budget.Mu.Unlock()
	if !budget.Reserved {
		if images, ok := ctx.Value(userImagesKey{}).([]string); ok {
			budget.Count += len(images)
			budget.Bytes += ImageBytes(images)
		}
		budget.Reserved = true
	}
	if budget.Seen == nil {
		budget.Seen = map[string]bool{}
	}
	allowed := map[string]uint64{}
	for _, target := range scope {
		if target != nil {
			allowed[target.KnowledgeBaseID] = target.TenantID
		}
	}
	byKey := map[string]int{}
	for _, result := range results {
		if result == nil || result.KnowledgeID == "" {
			continue
		}
		tenant, ok := allowed[result.KnowledgeBaseID]
		if !ok || tenant == 0 {
			continue
		}
		inScope := false
		for _, target := range scope {
			if target == nil || target.KnowledgeBaseID != result.KnowledgeBaseID || target.TenantID != tenant {
				continue
			}
			if target.Type == types.SearchTargetTypeKnowledgeBase {
				inScope = true
				break
			}
			for _, id := range target.KnowledgeIDs {
				if id == result.KnowledgeID {
					inScope = true
					break
				}
			}
		}
		if !inScope {
			continue
		}
		// Scope originates from the authorized retrieval path; still resolve current KB access.
		k, kb, err := s.load(ctx, result.KnowledgeID, false)
		if err != nil || k.TenantID != tenant || kb.ID != result.KnowledgeBaseID || !supported(k) || k.ParseStatus != types.ParseStatusCompleted || !kb.ImageProcessingConfig.UsePageImagesForAnswer {
			continue
		}
		if !budget.SupportsVision {
			evidence.Warnings = appendUnique(evidence.Warnings, "当前回答模型不支持图片，本次使用文本证据回答。")
			continue
		}
		pages, manual, chunk, err := s.chunkPages(ctx, k, result.ID)
		if err != nil {
			evidence.Warnings = appendUnique(evidence.Warnings, "部分 Chunk 的当前页面关联不可用，本次使用文本证据。")
			continue
		}
		// The text and its page relation must describe the same current chunk revision.
		if !chunk.IsEnabled || chunk.ContentRevision != result.ContentRevision || (!manual && len(pages) == 0) {
			evidence.Warnings = appendUnique(evidence.Warnings, "部分 Chunk 无当前有效页面关联，本次仅使用其文本证据。")
			continue
		}
		rows, err := s.Assets.List(ctx, tenant, k.ID, k.FileHash)
		if err != nil {
			evidence.Warnings = appendUnique(evidence.Warnings, "页面图片状态暂不可用，本次使用文本证据。")
			continue
		}
		for _, page := range pages {
			var asset *types.DocumentPageImage
			for i := range rows {
				if rows[i].PageNumber == page && rows[i].Status == "completed" {
					asset = &rows[i]
					break
				}
			}
			if asset == nil {
				evidence.Warnings = appendUnique(evidence.Warnings, "部分页面图片尚未就绪，本次使用其文本证据。")
				continue
			}
			key := asset.ID
			if at, ok := byKey[key]; ok {
				evidence.Sources[at].ChunkIDs = appendUnique(evidence.Sources[at].ChunkIDs, chunk.ID)
				continue
			}
			if budget.Seen[key] {
				continue
			}
			if budget.Count >= budget.MaxImages || asset.Size <= 0 || asset.Size > maxImageBytes || budget.Bytes+asset.Size > budget.MaxBytes {
				evidence.Warnings = appendUnique(evidence.Warnings, "部分页面图片因输入预算限制未发送，回答结合其余图片和文本证据。")
				continue
			}
			fs, err := s.fileService(ctx, kb, asset.StorageKey)
			if err != nil {
				evidence.Warnings = appendUnique(evidence.Warnings, "页面图片存储暂不可用，本次使用文本证据。")
				continue
			}
			r, err := fs.GetFile(ctx, asset.StorageKey)
			if err != nil {
				evidence.Warnings = appendUnique(evidence.Warnings, "部分页面图片读取失败，本次使用其文本证据。")
				continue
			}
			data, err := io.ReadAll(io.LimitReader(r, maxImageBytes+1))
			r.Close()
			if err != nil || len(data) == 0 || len(data) > maxImageBytes || budget.Bytes+int64(len(data)) > budget.MaxBytes {
				evidence.Warnings = appendUnique(evidence.Warnings, "部分页面图片读取失败或超过输入限制，本次使用其文本证据。")
				continue
			}
			evidence.Images = append(evidence.Images, "data:image/jpeg;base64,"+base64.StdEncoding.EncodeToString(data))
			byKey[key] = len(evidence.Sources)
			evidence.Sources = append(evidence.Sources, types.PageImageSource{ID: asset.ID, KnowledgeBaseID: kb.ID, KnowledgeID: k.ID, Title: k.Title, Page: page, ChunkIDs: []string{chunk.ID}, SourceHash: k.FileHash, Size: int64(len(data))})
			budget.Seen[key] = true
			budget.Count++
			budget.Bytes += int64(len(data))
		}
	}
	logger.Infof(ctx, "[PageImages] collected=%d total_images=%d total_bytes=%d warnings=%d", len(evidence.Images), budget.Count, budget.Bytes, len(evidence.Warnings))
	return evidence
}

func appendUnique(list []string, value string) []string {
	for _, s := range list {
		if s == value {
			return list
		}
	}
	return append(list, value)
}

func (s *Service) saveImage(ctx context.Context, fs interfaces.FileService, k *types.Knowledge, page int, data []byte) (string, error) {
	key, err := fs.SaveBytes(ctx, data, k.TenantID, fmt.Sprintf("page-%s-%d.jpg", k.ID, page), false)
	if err != nil {
		return "", err
	}
	if s.Catalog != nil {
		if _, ok := types.ParseResourcePath(key); ok {
			if err := s.Catalog.Bind(ctx, key, types.ResourceOwnerKnowledge, k.ID, "page_image"); err != nil {
				_ = fs.DeleteFile(ctx, key)
				return "", err
			}
		}
	}
	return key, nil
}

func (s *Service) removeObject(ctx context.Context, kb *types.KnowledgeBase, key string, docs ...string) error {
	if key == "" {
		return nil
	}
	if s.Catalog != nil && len(docs) > 0 {
		remaining, err := s.Catalog.Release(ctx, key, types.ResourceOwnerKnowledge, docs[0])
		if err != nil {
			if errors.Is(err, gorm.ErrRecordNotFound) || err.Error() == "resource not found" {
				return nil
			}
			return err
		}
		if remaining > 0 {
			return nil
		}
	}
	fs, err := s.fileService(ctx, kb, key)
	if err != nil {
		return err
	}
	err = fs.DeleteFile(ctx, key)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	return err
}

func (s *Service) Delete(ctx context.Context, tenant uint64, ids []string) error {
	var rows []types.DocumentPageImage
	if err := s.Assets.DB.WithContext(ctx).Where("tenant_id = ? AND knowledge_id IN ?", tenant, ids).Find(&rows).Error; err != nil {
		return err
	}
	for _, row := range rows {
		kb, err := s.KB.GetKnowledgeBaseByIDAndTenant(ctx, row.KnowledgeBaseID, tenant)
		if errors.Is(err, repository.ErrKnowledgeBaseNotFound) || errors.Is(err, gorm.ErrRecordNotFound) {
			// KB deletion soft-deletes its row before starting the cleanup worker.
			var owner types.KnowledgeBase
			err = s.Assets.DB.Unscoped().WithContext(ctx).Where("id = ? AND tenant_id = ?", row.KnowledgeBaseID, tenant).First(&owner).Error
			kb = &owner
		}
		if err != nil {
			return err
		}
		if err := s.removeObject(ctx, kb, row.StorageKey, row.KnowledgeID); err != nil {
			return err
		}
		if err := s.Assets.DB.WithContext(ctx).Where("id = ?", row.ID).Delete(&types.DocumentPageImage{}).Error; err != nil {
			return err
		}
	}
	return s.Assets.DB.WithContext(ctx).Where("tenant_id = ? AND knowledge_id IN ?", tenant, ids).Delete(&types.ChunkPageImageLink{}).Error
}

func (s *Service) Copy(ctx context.Context, src, dst *types.Knowledge, chunks map[string]string) error {
	rows, err := s.Assets.List(ctx, src.TenantID, src.ID, src.FileHash)
	if err != nil {
		return err
	}
	kb, err := s.KB.GetKnowledgeBaseByIDAndTenant(ctx, dst.KnowledgeBaseID, dst.TenantID)
	if err != nil {
		return err
	}
	srcKB, err := s.KB.GetKnowledgeBaseByIDAndTenant(ctx, src.KnowledgeBaseID, src.TenantID)
	if err != nil {
		return err
	}
	for _, row := range rows {
		if row.Status != "completed" {
			continue
		}
		var existing types.DocumentPageImage
		e := s.Assets.DB.WithContext(ctx).Where("tenant_id = ? AND knowledge_id = ? AND source_hash = ? AND page_number = ? AND render_profile = ?", dst.TenantID, dst.ID, dst.FileHash, row.PageNumber, row.RenderProfile).First(&existing).Error
		if e == nil {
			continue
		}
		if !errors.Is(e, gorm.ErrRecordNotFound) {
			return e
		}
		fs, e := s.fileService(ctx, srcKB, row.StorageKey)
		if e != nil {
			return e
		}
		reader, e := fs.GetFile(ctx, row.StorageKey)
		if e != nil {
			return e
		}
		data, e := io.ReadAll(io.LimitReader(reader, maxImageBytes+1))
		reader.Close()
		if e != nil {
			return e
		}
		if len(data) > maxImageBytes {
			return fmt.Errorf("page image exceeds copy size limit")
		}
		target, e := s.fileService(ctx, kb, "")
		if e != nil {
			return e
		}
		key, e := s.saveImage(ctx, target, dst, row.PageNumber, data)
		if e != nil {
			return e
		}
		row.ID = uuid.NewString()
		row.TenantID = dst.TenantID
		row.KnowledgeID = dst.ID
		row.KnowledgeBaseID = dst.KnowledgeBaseID
		row.SourceHash = dst.FileHash
		row.StorageKey = key
		row.TaskToken = ""
		row.LeaseUntil = nil
		row.CreatedAt = time.Now()
		row.UpdatedAt = row.CreatedAt
		created := s.Assets.DB.WithContext(ctx).Clauses(clause.OnConflict{DoNothing: true}).Create(&row)
		if created.Error != nil || created.RowsAffected == 0 {
			s.removeObject(ctx, kb, key, dst.ID)
			if created.Error != nil {
				return created.Error
			}
		}
	}
	var links []types.ChunkPageImageLink
	if err := s.Assets.DB.WithContext(ctx).Where("tenant_id = ? AND knowledge_id = ? AND source_hash = ?", src.TenantID, src.ID, src.FileHash).Find(&links).Error; err != nil {
		return err
	}
	for _, link := range links {
		newID, ok := chunks[link.ChunkID]
		if !ok {
			continue
		}
		var sourceChunk, targetChunk types.Chunk
		if e := s.Assets.DB.WithContext(ctx).Where("id = ? AND tenant_id = ? AND content_revision = ?", link.ChunkID, src.TenantID, link.ContentRevision).First(&sourceChunk).Error; errors.Is(e, gorm.ErrRecordNotFound) {
			continue
		} else if e != nil {
			return e
		}
		if e := s.Assets.DB.WithContext(ctx).Where("id = ? AND tenant_id = ?", newID, dst.TenantID).First(&targetChunk).Error; e != nil {
			return e
		}
		link.ContentRevision = targetChunk.ContentRevision
		link.ID = uuid.NewString()
		link.ChunkID = newID
		link.TenantID = dst.TenantID
		link.KnowledgeID = dst.ID
		link.KnowledgeBaseID = dst.KnowledgeBaseID
		link.SourceHash = dst.FileHash
		if e := s.Assets.DB.WithContext(ctx).Clauses(clause.OnConflict{DoNothing: true}).Create(&link).Error; e != nil {
			return e
		}
	}
	return nil
}

func (s *Service) Move(ctx context.Context, k *types.Knowledge, dst *types.KnowledgeBase) error {
	// Copy bytes to the destination storage before releasing source objects.
	target := *k
	target.KnowledgeBaseID = dst.ID
	target.TenantID = dst.TenantID
	var rows []types.DocumentPageImage
	err := s.Assets.DB.WithContext(ctx).Where("tenant_id = ? AND knowledge_id = ?", k.TenantID, k.ID).Find(&rows).Error
	if err != nil {
		return err
	}
	srcKB, err := s.KB.GetKnowledgeBaseByIDAndTenant(ctx, k.KnowledgeBaseID, k.TenantID)
	if err != nil {
		return err
	}
	for _, row := range rows {
		if row.KnowledgeBaseID == dst.ID {
			continue
		}
		if row.Status == "completed" && row.SourceHash == k.FileHash {
			fs, e := s.fileService(ctx, srcKB, row.StorageKey)
			if e != nil {
				return e
			}
			r, e := fs.GetFile(ctx, row.StorageKey)
			if e != nil {
				return e
			}
			data, e := io.ReadAll(io.LimitReader(r, maxImageBytes+1))
			r.Close()
			if e != nil {
				return e
			}
			if len(data) > maxImageBytes {
				return fmt.Errorf("page image exceeds move size limit")
			}
			fs, e = s.fileService(ctx, dst, "")
			if e != nil {
				return e
			}
			key, e := s.saveImage(ctx, fs, &target, row.PageNumber, data)
			if e != nil {
				return e
			}
			old := row.StorageKey
			row.StorageKey = key
			row.KnowledgeBaseID = dst.ID
			row.TenantID = dst.TenantID
			if e = s.Assets.DB.WithContext(ctx).Save(&row).Error; e != nil {
				s.removeObject(ctx, dst, key, k.ID)
				return e
			}
			s.removeObject(ctx, srcKB, old, k.ID)
		} else {
			if e := s.removeObject(ctx, srcKB, row.StorageKey, k.ID); e != nil {
				return e
			}
			if e := s.Assets.DB.WithContext(ctx).Where("id = ?", row.ID).Delete(&types.DocumentPageImage{}).Error; e != nil {
				return e
			}
		}
	}
	return s.Assets.DB.WithContext(ctx).Model(&types.ChunkPageImageLink{}).Where("tenant_id = ? AND knowledge_id = ?", k.TenantID, k.ID).Updates(map[string]any{"tenant_id": dst.TenantID, "knowledge_base_id": dst.ID}).Error
}

var _ interfaces.DocumentPageImageService = (*Service)(nil)
