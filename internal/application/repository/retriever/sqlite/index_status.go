package sqlite

import (
	"context"
	"fmt"
	"strings"

	"github.com/Tencent/WeKnora/internal/types"
)

func (r *sqliteRepository) CountKnowledgeIndices(
	ctx context.Context, kbID, knowledgeID string, kind types.RetrieverType,
) (*types.KnowledgeRetrievalIndexCounts, error) {
	query := r.db.WithContext(ctx).Table("lite_embeddings AS e").
		Where("e.knowledge_base_id = ? AND e.knowledge_id = ? AND e.source_type = ?", kbID, knowledgeID, types.ChunkSourceType)
	if kind == types.KeywordsRetrieverType {
		query = query.Where("EXISTS (SELECT 1 FROM lite_embeddings_fts f WHERE f.rowid = e.id)")
	} else {
		r.vecMu.RLock()
		var conditions []string
		for dim, ready := range r.vecTables {
			if ready && dim > 0 {
				conditions = append(conditions, fmt.Sprintf(
					"(e.dimension = %d AND EXISTS (SELECT 1 FROM %s v WHERE v.rowid = e.id))", dim, vecTableName(dim)))
			}
		}
		r.vecMu.RUnlock()
		if len(conditions) == 0 {
			return &types.KnowledgeRetrievalIndexCounts{}, nil
		}
		query = query.Where("(" + strings.Join(conditions, " OR ") + ")")
	}
	var counts types.KnowledgeRetrievalIndexCounts
	err := query.Select(`COUNT(DISTINCT CASE WHEN e.source_id = e.chunk_id THEN e.chunk_id END) AS chunks,
		COUNT(DISTINCT CASE WHEN e.source_id <> e.chunk_id THEN e.source_id END) AS questions`).Scan(&counts).Error
	return &counts, err
}
