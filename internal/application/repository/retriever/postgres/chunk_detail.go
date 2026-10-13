package postgres

import (
	"context"
	"strconv"

	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
)

func (r *pgRepository) ListChunkIndexRecords(ctx context.Context, kbID, docID, chunkID string, kind types.RetrieverType) ([]types.ChunkRetrievalRecord, error) {
	if kind != types.VectorRetrieverType && kind != types.KeywordsRetrieverType {
		return nil, interfaces.ErrIndexStatisticsUnsupported
	}
	query := r.db.WithContext(ctx).Model(&pgVector{}).Where("knowledge_base_id = ? AND knowledge_id = ? AND chunk_id = ? AND source_type = ?", kbID, docID, chunkID, types.ChunkSourceType)
	if kind == types.VectorRetrieverType {
		query = query.Where("dimension > 0 AND embedding IS NOT NULL")
	} else {
		query = query.Select("id, source_id, chunk_id, content, dimension, is_enabled, updated_at")
	}
	var rows []pgVector
	if err := query.Order("source_id ASC, id ASC").Find(&rows).Error; err != nil {
		return nil, err
	}
	records := make([]types.ChunkRetrievalRecord, 0, len(rows))
	for _, row := range rows {
		record := types.ChunkRetrievalRecord{ID: strconv.FormatUint(uint64(row.ID), 10), StoreType: types.PostgresRetrieverEngineType, SourceID: row.SourceID, ChunkID: row.ChunkID, Content: row.Content, Dimension: row.Dimension, IsEnabled: row.IsEnabled, UpdatedAt: row.UpdatedAt}
		if kind == types.VectorRetrieverType {
			record.Embedding = row.Embedding.Slice()
		}
		records = append(records, record)
	}
	return records, nil
}
