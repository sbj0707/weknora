package service

import (
	"context"
	"fmt"

	"github.com/Tencent/WeKnora/internal/application/service/retriever"
	apperrors "github.com/Tencent/WeKnora/internal/errors"
	"github.com/Tencent/WeKnora/internal/types"
)

// Applies only to fixed-schema drivers; all other stores keep existing model
// selection behavior. The factory verifies owner scope including async paths.
func (s *knowledgeBaseService) validateBoundVectorDimensions(ctx context.Context, kb *types.KnowledgeBase) error {
	if !kb.HasVectorStore() || !kb.IsVectorEnabled() {
		return nil
	}
	dimension, err := retriever.BoundVectorDimensions(ctx, s.retrieveEngine, s.ownership, kb.TenantID, *kb.VectorStoreID)
	if err != nil {
		return err
	}
	if dimension == 0 {
		return nil
	}
	// KB initialization may assign the embedding model in a separate request.
	// Once selected, its declared and actual output sizes are both validated.
	if kb.EmbeddingModelID == "" {
		return nil
	}
	if s.modelService == nil {
		return apperrors.NewValidationError("embedding model service is unavailable")
	}
	model, err := s.modelService.GetEmbeddingModelForTenant(ctx, kb.EmbeddingModelID, kb.TenantID)
	if err != nil {
		return err
	}
	if model == nil || model.GetDimensions() <= 0 {
		return apperrors.NewValidationError("set the embedding model dimension before binding it to Azure AI Search")
	}
	if model.GetDimensions() != dimension {
		return apperrors.NewValidationError(fmt.Sprintf("Azure AI Search index dimension is %d, but the embedding model dimension is %d", dimension, model.GetDimensions()))
	}
	return nil
}

func (s *knowledgeBaseService) validateBoundEmbeddingModelChange(ctx context.Context, kb *types.KnowledgeBase, modelID string) error {
	if !kb.HasVectorStore() || kb.EmbeddingModelID == "" || kb.EmbeddingModelID == modelID {
		return nil
	}
	dimension, err := retriever.BoundVectorDimensions(ctx, s.retrieveEngine, s.ownership, kb.TenantID, *kb.VectorStoreID)
	if err != nil {
		return err
	}
	if dimension == 0 {
		return nil
	}
	if s.kgRepo == nil {
		return apperrors.NewValidationError("cannot verify whether the knowledge base needs an embedding rebuild")
	}
	count, err := s.kgRepo.CountKnowledgeByKnowledgeBaseID(ctx, kb.TenantID, kb.ID)
	if err != nil {
		return err
	}
	if count > 0 {
		return apperrors.NewValidationError("changing the embedding model of a populated Azure AI Search knowledge base requires rebuilding it in a new knowledge base")
	}
	return nil
}
