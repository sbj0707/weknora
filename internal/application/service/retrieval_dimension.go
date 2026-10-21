package service

import (
	"context"

	"github.com/Tencent/WeKnora/internal/application/service/retriever"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
)

// Fixed-schema stores can maintain keyword-only records and clean up indices
// after the old embedding model has been removed. Other engines retain their
// model-based collection routing. Zero means there were no routed indices.
func retrievalDimension(ctx context.Context, engine *retriever.CompositeRetrieveEngine, models interfaces.ModelService, modelID string) (int, error) {
	if fixed := engine.VectorDimensions(); fixed > 0 {
		return fixed, nil
	}
	if modelID == "" {
		return 0, nil
	}
	model, err := models.GetEmbeddingModel(ctx, modelID)
	if err != nil {
		return 0, err
	}
	return model.GetDimensions(), nil
}
