package interfaces

import (
	"context"

	"github.com/Tencent/WeKnora/internal/types"
)

type ChunkDetailService interface {
	GetChunkDetail(context.Context, *types.Knowledge, string) (*types.ChunkDetail, error)
}

type ChunkIndexReader interface {
	ListChunkIndexRecords(context.Context, string, string, string, types.RetrieverType) ([]types.ChunkRetrievalRecord, error)
}

type ChunkGraphReader interface {
	GetChunkGraph(context.Context, types.NameSpace, string) (*types.ChunkGraphDetail, error)
}

type ChunkWikiReader interface {
	ListChunkWikiPages(context.Context, uint64, string, string, string) ([]*types.WikiPage, error)
}
