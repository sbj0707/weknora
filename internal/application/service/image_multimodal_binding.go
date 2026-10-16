package service

import (
	"context"
	"errors"
	"fmt"
	"github.com/Tencent/WeKnora/internal/application/repository"
	"github.com/Tencent/WeKnora/internal/application/service/retriever"
	"github.com/Tencent/WeKnora/internal/models/embedding"
	"github.com/Tencent/WeKnora/internal/types"
	secutils "github.com/Tencent/WeKnora/internal/utils"
	"github.com/hibiken/asynq"
	"reflect"
	"strings"
)

type imageSourceGuardKey struct{}

var errImageSourceChanged = errors.New("image multimodal source changed")

// Queue payloads are not authority: verify the entire immutable parent binding
// before opening spans, reading bytes, or decrementing another parse's counter.
func (s *ImageMultimodalService) bindImageSource(ctx context.Context, p *types.ImageMultimodalPayload) (context.Context, error) {
	if s.knowledgeRepo == nil || s.kbService == nil {
		return ctx, nil
	} // legacy injected tests
	row, e := s.knowledgeRepo.GetKnowledgeByIDOnly(ctx, p.KnowledgeID)
	if e != nil {
		return ctx, e
	}
	if row == nil || row.TenantID != p.TenantID || row.ID != p.KnowledgeID || row.KnowledgeBaseID != p.KnowledgeBaseID {
		return ctx, fmt.Errorf("image multimodal document binding mismatch: %w", asynq.SkipRetry)
	}
	base, e := s.kbService.GetKnowledgeBaseByIDOnly(ctx, p.KnowledgeBaseID)
	if e != nil {
		return ctx, e
	}
	if base == nil || base.TenantID != p.TenantID || base.ID != p.KnowledgeBaseID {
		return ctx, fmt.Errorf("image multimodal knowledge base binding mismatch: %w", asynq.SkipRetry)
	}
	// Legacy provider URLs carry an owner namespace independently of the
	// document. Resource handles are authorized by their storage catalog.
	if !strings.HasPrefix(p.ImageURL, "http://") && !strings.HasPrefix(p.ImageURL, "https://") && !strings.HasPrefix(p.ImageURL, "resource://") {
		if owner := secutils.ParseTenantIDFromStoragePath(p.ImageURL); owner != 0 && owner != p.TenantID {
			return ctx, fmt.Errorf("image multimodal image resource binding mismatch: %w", asynq.SkipRetry)
		}
	}
	var parent *types.Chunk
	if p.ChunkID != "" && s.chunkService != nil {
		parent, e = s.chunkService.GetChunkByIDOnly(ctx, p.ChunkID)
		if errors.Is(e, repository.ErrChunkNotFound) {
			return ctx, fmt.Errorf("image multimodal parent chunk binding mismatch: %w", asynq.SkipRetry)
		}
		if e != nil {
			return ctx, e
		}
		if !imageParentMatches(parent, p) {
			return ctx, fmt.Errorf("image multimodal parent chunk binding mismatch: %w", asynq.SkipRetry)
		}
	}
	metadata := append(types.JSON(nil), row.Metadata...)
	title, filename, kind := row.Title, row.FileName, row.Type
	var parentContent, parentHeader string
	var locators types.SourceLocators
	if parent != nil {
		parentContent, parentHeader = parent.Content, parent.ContextHeader
		locators = append(types.SourceLocators(nil), parent.SourceLocators...)
	}
	guard := func(ctx context.Context) error {
		if e := ctx.Err(); e != nil {
			return e
		}
		current, e := s.knowledgeRepo.GetKnowledgeByIDOnly(ctx, p.KnowledgeID)
		if errors.Is(e, repository.ErrKnowledgeNotFound) {
			return errImageSourceChanged
		}
		if e != nil {
			return e
		}
		if current == nil || current.TenantID != p.TenantID || current.ID != p.KnowledgeID || current.KnowledgeBaseID != p.KnowledgeBaseID || current.Title != title || current.FileName != filename || current.Type != kind || !reflect.DeepEqual(current.Metadata, metadata) || current.ParseStatus == types.ParseStatusCancelled || current.ParseStatus == types.ParseStatusDeleting || attemptSuperseded(ctx, s.tracker(), p.KnowledgeID, p.Attempt) {
			return errImageSourceChanged
		}
		kb, e := s.kbService.GetKnowledgeBaseByIDOnly(ctx, p.KnowledgeBaseID)
		if errors.Is(e, repository.ErrKnowledgeBaseNotFound) {
			return errImageSourceChanged
		}
		if e != nil {
			return e
		}
		if kb == nil || kb.TenantID != p.TenantID {
			return errImageSourceChanged
		}
		if parent != nil {
			c, e := s.chunkService.GetChunkByIDOnly(ctx, p.ChunkID)
			if errors.Is(e, repository.ErrChunkNotFound) {
				return errImageSourceChanged
			}
			if e != nil {
				return e
			}
			if !imageParentMatches(c, p) || c.Content != parentContent || c.ContextHeader != parentHeader || !reflect.DeepEqual(c.SourceLocators, locators) {
				return errImageSourceChanged
			}
		}
		return nil
	}
	return context.WithValue(retriever.WithManualBatchOrder(ctx), imageSourceGuardKey{}, guard), nil
}
func imageParentMatches(c *types.Chunk, p *types.ImageMultimodalPayload) bool {
	return c != nil && c.TenantID == p.TenantID && c.KnowledgeID == p.KnowledgeID && c.KnowledgeBaseID == p.KnowledgeBaseID && c.ID == p.ChunkID
}
func checkImageSource(ctx context.Context) error {
	if guard, ok := ctx.Value(imageSourceGuardKey{}).(func(context.Context) error); ok {
		return guard(ctx)
	}
	return ctx.Err()
}

type imageFencedEmbedder struct{ embedding.Embedder }

func (e imageFencedEmbedder) BatchEmbedWithPool(ctx context.Context, model embedding.Embedder, texts []string) ([][]float32, error) {
	if err := checkImageSource(ctx); err != nil {
		return nil, err
	}
	vectors, err := e.Embedder.BatchEmbedWithPool(ctx, model, texts)
	if err != nil {
		return nil, err
	}
	if err := checkImageSource(ctx); err != nil {
		return nil, err
	}
	return vectors, nil
}
