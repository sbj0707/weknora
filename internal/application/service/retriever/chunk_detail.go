package retriever

import (
	"context"
	"slices"

	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
)

func (c *CompositeRetrieveEngine) ChunkIndexStores(kind types.RetrieverType) []types.RetrieverEngineType {
	var stores []types.RetrieverEngineType
	for _, engine := range c.engineInfos {
		if engine != nil && slices.Contains(engine.retrieverType, kind) {
			stores = append(stores, engine.retrieveEngine.EngineType())
		}
	}
	return stores
}

func (v *KeywordsVectorHybridRetrieveEngineService) ListChunkIndexRecords(ctx context.Context, kbID, docID, chunkID string, kind types.RetrieverType) ([]types.ChunkRetrievalRecord, error) {
	reader, ok := v.indexRepository.(interfaces.ChunkIndexReader)
	if !ok {
		return nil, interfaces.ErrIndexStatisticsUnsupported
	}
	return reader.ListChunkIndexRecords(ctx, kbID, docID, chunkID, kind)
}

func (c *CompositeRetrieveEngine) ListChunkIndexRecords(ctx context.Context, kbID, docID, chunkID string, kind types.RetrieverType) ([]types.ChunkRetrievalRecord, error) {
	records := make([]types.ChunkRetrievalRecord, 0)
	found := false
	for _, engine := range c.engineInfos {
		if engine == nil || !slices.Contains(engine.retrieverType, kind) {
			continue
		}
		found = true
		reader, ok := engine.retrieveEngine.(interfaces.ChunkIndexReader)
		if !ok {
			return nil, interfaces.ErrIndexStatisticsUnsupported
		}
		rows, err := reader.ListChunkIndexRecords(ctx, kbID, docID, chunkID, kind)
		if err != nil {
			return nil, err
		}
		records = append(records, rows...)
	}
	if !found {
		return nil, interfaces.ErrIndexStatisticsUnsupported
	}
	slices.SortFunc(records, func(a, b types.ChunkRetrievalRecord) int {
		if a.StoreType < b.StoreType {
			return -1
		}
		if a.StoreType > b.StoreType {
			return 1
		}
		if a.SourceID < b.SourceID {
			return -1
		}
		if a.SourceID > b.SourceID {
			return 1
		}
		return 0
	})
	return records, nil
}
