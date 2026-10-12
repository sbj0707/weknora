package retriever

import (
	"context"
	"slices"

	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
)

func (v *KeywordsVectorHybridRetrieveEngineService) CountKnowledgeIndices(
	ctx context.Context, kbID, knowledgeID string, kind types.RetrieverType,
) (*types.KnowledgeRetrievalIndexCounts, error) {
	counter, ok := v.indexRepository.(interfaces.KnowledgeIndexCounter)
	if !ok {
		return nil, interfaces.ErrIndexStatisticsUnsupported
	}
	return counter.CountKnowledgeIndices(ctx, kbID, knowledgeID, kind)
}

func (c *CompositeRetrieveEngine) CountKnowledgeIndices(
	ctx context.Context, kbID, knowledgeID string, kind types.RetrieverType,
) (*types.KnowledgeRetrievalIndexCounts, error) {
	var counts *types.KnowledgeRetrievalIndexCounts
	for _, engine := range c.engineInfos {
		if engine == nil || !slices.Contains(engine.retrieverType, kind) {
			continue
		}
		counter, ok := engine.retrieveEngine.(interfaces.KnowledgeIndexCounter)
		if !ok {
			return nil, interfaces.ErrIndexStatisticsUnsupported
		}
		current, err := counter.CountKnowledgeIndices(ctx, kbID, knowledgeID, kind)
		if err != nil || current == nil {
			if err == nil {
				err = interfaces.ErrIndexStatisticsUnsupported
			}
			return nil, err
		}
		if counts == nil {
			copy := *current
			counts = &copy
		} else {
			// Replicated stores must not multiply the same document's count.
			counts.Chunks = min(counts.Chunks, current.Chunks)
			counts.Questions = min(counts.Questions, current.Questions)
		}
	}
	if counts == nil {
		return nil, interfaces.ErrIndexStatisticsUnsupported
	}
	return counts, nil
}
