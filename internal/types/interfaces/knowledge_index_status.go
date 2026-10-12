package interfaces

import (
	"context"
	"errors"

	"github.com/Tencent/WeKnora/internal/types"
)

var ErrIndexStatisticsUnsupported = errors.New("index statistics unavailable")

// Optional read capabilities let drivers that cannot count their stored
// indices report unknown without pretending a successful zero result.
type KnowledgeIndexCounter interface {
	CountKnowledgeIndices(context.Context, string, string, types.RetrieverType) (*types.KnowledgeRetrievalIndexCounts, error)
}

type KnowledgeGraphCounter interface {
	CountKnowledgeGraph(context.Context, types.NameSpace) (*types.KnowledgeGraphCounts, error)
}

type KnowledgeIndexChunkReader interface {
	ListIndexStatusChunks(context.Context, uint64, string) ([]*types.Chunk, error)
}

type KnowledgeIndexStatusService interface {
	GetKnowledgeIndexStatus(context.Context, *types.Knowledge, []types.KnowledgeProcessingSpan) (*types.KnowledgeIndexStatus, error)
}
