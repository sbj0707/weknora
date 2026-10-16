package service

import (
	"context"

	"github.com/Tencent/WeKnora/internal/logger"
	"github.com/Tencent/WeKnora/internal/types"
)

type documentChunkIndexCleaner interface {
	DeleteByChunkIDList(context.Context, []string, int, string) error
}

// Reparse deletes the old attempt before enqueue. This cleanup owns only the
// document worker's chunks; independently generated table metadata must survive
// both possible queue orders, including a table worker finishing first.
func (s *knowledgeService) cleanupDocumentChunksPreservingTable(ctx context.Context,
	knowledge *types.Knowledge, engine documentChunkIndexCleaner, dimension int, indexed bool,
) error {
	chunks, err := s.chunkRepo.ListAllChunksByKnowledgeID(ctx, knowledge.TenantID, knowledge.ID)
	if err != nil {
		return err
	}
	ids := make([]string, 0, len(chunks))
	for _, chunk := range chunks {
		if chunk.ChunkType != types.ChunkTypeTableSummary && chunk.ChunkType != types.ChunkTypeTableColumn {
			ids = append(ids, chunk.ID)
		}
	}
	if len(ids) == 0 {
		return nil
	}
	if err := s.chunkRepo.DeleteChunks(ctx, knowledge.TenantID, ids); err != nil {
		logger.Warnf(ctx, "Failed to delete existing document chunks: %v", err)
	}
	if indexed {
		if err := engine.DeleteByChunkIDList(ctx, ids, dimension, knowledge.Type); err != nil {
			logger.Warnf(ctx, "Failed to delete existing document index data: %v", err)
		}
	}
	return nil
}
