package azureaisearch

import (
	"context"
	"fmt"

	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
)

var _ interfaces.KnowledgeIndexCounter = (*Repository)(nil)
var _ interfaces.ChunkIndexReader = (*Repository)(nil)

func (r *Repository) ListChunkIndexRecords(ctx context.Context, kb, knowledge, chunk string, kind types.RetrieverType) ([]types.ChunkRetrievalRecord, error) {
	if kb == "" || knowledge == "" || chunk == "" {
		return nil, fmt.Errorf("Azure AI Search chunk details require knowledge base, document and chunk")
	}
	if kind != types.VectorRetrieverType && kind != types.KeywordsRetrieverType {
		return nil, interfaces.ErrIndexStatisticsUnsupported
	}
	records := []types.ChunkRetrievalRecord{}
	err := r.scan(ctx, and("knowledge_base_id eq "+quote(kb), "knowledge_id eq "+quote(knowledge), "chunk_id eq "+quote(chunk)), true, func(docs []document) error {
		for _, d := range docs {
			if kind == types.VectorRetrieverType && len(d.Embedding) == 0 {
				continue
			}
			record := types.ChunkRetrievalRecord{ID: d.SourceID, StoreType: r.EngineType(), SourceID: d.SourceID, ChunkID: d.ChunkID, Content: d.Content, Dimension: len(d.Embedding), IsEnabled: d.IsEnabled}
			if kind == types.VectorRetrieverType {
				record.Embedding = d.Embedding
			}
			records = append(records, record)
		}
		return nil
	})
	return records, err
}
func (r *Repository) CountKnowledgeIndices(ctx context.Context, kb, knowledge string, kind types.RetrieverType) (*types.KnowledgeRetrievalIndexCounts, error) {
	if kb == "" || knowledge == "" {
		return nil, fmt.Errorf("Azure AI Search index counts require knowledge base and document")
	}
	if kind != types.VectorRetrieverType && kind != types.KeywordsRetrieverType {
		return nil, interfaces.ErrIndexStatisticsUnsupported
	}
	counts := &types.KnowledgeRetrievalIndexCounts{}
	// Vector presence isn't filterable in Azure, so inspect source vectors.
	err := r.scan(ctx, and("knowledge_base_id eq "+quote(kb), "knowledge_id eq "+quote(knowledge)), kind == types.VectorRetrieverType, func(docs []document) error {
		for _, d := range docs {
			if kind == types.VectorRetrieverType && len(d.Embedding) == 0 {
				continue
			}
			if d.SourceID == d.ChunkID {
				counts.Chunks++
			} else {
				counts.Questions++
			}
		}
		return nil
	})
	return counts, err
}
