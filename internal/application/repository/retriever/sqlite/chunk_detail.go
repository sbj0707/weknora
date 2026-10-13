package sqlite

import (
	"context"
	"encoding/binary"
	"fmt"
	"math"
	"strconv"

	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
)

func (r *sqliteRepository) ListChunkIndexRecords(ctx context.Context, kbID, docID, chunkID string, kind types.RetrieverType) ([]types.ChunkRetrievalRecord, error) {
	if kind != types.VectorRetrieverType && kind != types.KeywordsRetrieverType {
		return nil, interfaces.ErrIndexStatisticsUnsupported
	}
	query := r.db.WithContext(ctx).Model(&sqliteEmbedding{}).Where("knowledge_base_id = ? AND knowledge_id = ? AND chunk_id = ? AND source_type = ?", kbID, docID, chunkID, types.ChunkSourceType)
	if kind == types.KeywordsRetrieverType {
		query = query.Where("EXISTS (SELECT 1 FROM lite_embeddings_fts f WHERE f.rowid = lite_embeddings.id)")
	}
	var rows []sqliteEmbedding
	if err := query.Order("source_id ASC, id ASC").Find(&rows).Error; err != nil {
		return nil, err
	}
	records := make([]types.ChunkRetrievalRecord, 0, len(rows))
	for _, row := range rows {
		record := types.ChunkRetrievalRecord{ID: strconv.FormatUint(uint64(row.ID), 10), StoreType: types.SQLiteRetrieverEngineType, SourceID: row.SourceID, ChunkID: row.ChunkID, Content: row.Content, Dimension: row.Dimension, IsEnabled: row.IsEnabled != nil && *row.IsEnabled, UpdatedAt: row.UpdatedAt}
		if kind == types.VectorRetrieverType {
			if row.Dimension <= 0 || !r.hasVecTable(row.Dimension) {
				continue
			}
			var vectors []struct{ Embedding []byte }
			if err := r.db.WithContext(ctx).Table(vecTableName(row.Dimension)).Select("embedding").Where("rowid = ?", row.ID).Scan(&vectors).Error; err != nil {
				return nil, err
			}
			if len(vectors) == 0 {
				continue
			}
			blob := vectors[0].Embedding
			if len(blob) != row.Dimension*4 {
				return nil, fmt.Errorf("invalid stored vector dimension")
			}
			record.Embedding = make([]float32, row.Dimension)
			for i := range record.Embedding {
				record.Embedding[i] = math.Float32frombits(binary.LittleEndian.Uint32(blob[i*4 : i*4+4]))
			}
		}
		records = append(records, record)
	}
	return records, nil
}
