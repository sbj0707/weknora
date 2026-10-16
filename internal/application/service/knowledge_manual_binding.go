package service

import (
	"context"
	"errors"
	"fmt"
	"reflect"

	"github.com/Tencent/WeKnora/internal/application/repository"
	"github.com/Tencent/WeKnora/internal/application/service/retriever"
	"github.com/Tencent/WeKnora/internal/models/embedding"
	"github.com/Tencent/WeKnora/internal/types"
)

// Manual tasks carry body text, rather than an immutable file path. Freeze the
// revision independently of the mutable Knowledge object used by processChunks.
type manualSourceGuardKey struct{}

var errManualSourceChanged = errors.New("manual knowledge source changed")

func (s *knowledgeService) withManualSourceGuard(ctx context.Context, row *types.Knowledge) context.Context {
	owner, id, kb, title, filename := row.TenantID, row.ID, row.KnowledgeBaseID, row.Title, row.FileName
	metadata := append(types.JSON(nil), row.Metadata...)
	guard := func(ctx context.Context) error {
		if err := ctx.Err(); err != nil {
			return err
		}
		current, err := s.repo.GetKnowledgeByID(ctx, owner, id)
		if errors.Is(err, repository.ErrKnowledgeNotFound) {
			return errManualSourceChanged
		}
		if err != nil {
			return fmt.Errorf("read manual source: %w", err)
		}
		if current == nil || current.TenantID != owner || current.ID != id || current.KnowledgeBaseID != kb || current.Type != "manual" || current.Title != title || current.FileName != filename || !reflect.DeepEqual(current.Metadata, metadata) || current.ParseStatus == types.ParseStatusCancelled || current.ParseStatus == types.ParseStatusDeleting || s.currentAttemptSuperseded(ctx, id) {
			return errManualSourceChanged
		}
		base, err := s.kbService.GetKnowledgeBaseByID(ctx, kb)
		if err != nil {
			return fmt.Errorf("read manual source KB: %w", err)
		}
		if base == nil || base.TenantID != owner || base.ID != kb {
			return errManualSourceChanged
		}
		return nil
	}
	return context.WithValue(retriever.WithManualBatchOrder(ctx), manualSourceGuardKey{}, guard)
}

func checkManualSource(ctx context.Context) error {
	if guard, ok := ctx.Value(manualSourceGuardKey{}).(func(context.Context) error); ok {
		return guard(ctx)
	}
	return nil
}

func (s *knowledgeService) stopManualSource(ctx context.Context, knowledge string, err error) error {
	if errors.Is(err, errManualSourceChanged) {
		s.tracker().AbortAttempt(ctx, knowledge, attemptFromCtx(ctx), "TASK_SUPERSEDED", "manual knowledge source changed", "manual knowledge source changed")
		return nil
	}
	return err
}

// The embedding/indexer boundary must reject stale bodies AFTER the provider
// returns, before BatchSave can publish vectors. Only manual contexts use it.
type manualFencedEmbedder struct{ embedding.Embedder }

func (e manualFencedEmbedder) BatchEmbedWithPool(ctx context.Context, model embedding.Embedder, texts []string) ([][]float32, error) {
	if err := checkManualSource(ctx); err != nil {
		return nil, err
	}
	result, err := e.Embedder.BatchEmbedWithPool(ctx, model, texts)
	if err != nil {
		return nil, err
	}
	if err := checkManualSource(ctx); err != nil {
		return nil, err
	}
	return result, nil
}
