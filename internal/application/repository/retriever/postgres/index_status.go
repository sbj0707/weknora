package postgres

import (
	"context"

	"github.com/Tencent/WeKnora/internal/types"
)

func (r *pgRepository) CountKnowledgeIndices(
	ctx context.Context, kbID, knowledgeID string, kind types.RetrieverType,
) (*types.KnowledgeRetrievalIndexCounts, error) {
	query := r.db.WithContext(ctx).Model(&pgVector{}).
		Where("knowledge_base_id = ? AND knowledge_id = ? AND source_type = ?", kbID, knowledgeID, types.ChunkSourceType)
	if kind == types.VectorRetrieverType {
		query = query.Where("dimension > 0 AND embedding IS NOT NULL")
	}
	var counts types.KnowledgeRetrievalIndexCounts
	err := query.Select(`COUNT(DISTINCT CASE WHEN source_id = chunk_id THEN chunk_id END) AS chunks,
		COUNT(DISTINCT CASE WHEN source_id <> chunk_id THEN source_id END) AS questions`).Scan(&counts).Error
	return &counts, err
}
