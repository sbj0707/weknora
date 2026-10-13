package service

import (
	"context"
	"testing"

	"github.com/Tencent/WeKnora/internal/application/service/retriever"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"github.com/stretchr/testify/require"
)

type chunkDetailRepoStub struct {
	interfaces.ChunkRepository
	chunk *types.Chunk
	calls int
}

func (r *chunkDetailRepoStub) GetChunkByID(_ context.Context, tenant uint64, id string) (*types.Chunk, error) {
	r.calls++
	if tenant != 7 || id != "chunk" {
		panic("invalid owner scope")
	}
	return r.chunk, nil
}

type chunkDetailEngineStub struct{ indexStatusEngine }

func (r *chunkDetailRepoStub) ListChunkByParentID(_ context.Context, tenant uint64, parentID string) ([]*types.Chunk, error) {
	if tenant != 7 || parentID != "chunk" {
		panic("invalid children scope")
	}
	return []*types.Chunk{{ID: "image-ocr", TenantID: 7, KnowledgeID: "doc", KnowledgeBaseID: "kb",
		ParentChunkID: "chunk", ChunkType: types.ChunkTypeImageOCR, Content: "OCR text"}}, nil
}

func (e *chunkDetailEngineStub) ListChunkIndexRecords(_ context.Context, kb, doc, chunk string, kind types.RetrieverType) ([]types.ChunkRetrievalRecord, error) {
	if kb != "kb" || doc != "doc" || chunk != "chunk" {
		panic("invalid index scope")
	}
	e.calls++
	rows := []types.ChunkRetrievalRecord{{SourceID: "chunk", ChunkID: "chunk", StoreType: types.PostgresRetrieverEngineType, Content: "Actual index input"}}
	if kind == types.VectorRetrieverType {
		rows = append(rows, types.ChunkRetrievalRecord{SourceID: "chunk-q", ChunkID: "chunk", StoreType: types.PostgresRetrieverEngineType, Embedding: []float32{1, 0}})
	}
	return rows, nil
}
func TestChunkDetailScopeAndQuestionIndexEvidence(t *testing.T) {
	chunk := &types.Chunk{ID: "chunk", TenantID: 7, KnowledgeID: "doc", KnowledgeBaseID: "kb", Content: "Current body", SourceContent: "Parser body", ContextHeader: "Heading", IndexStatus: "ready", Metadata: types.JSON(`{"generated_questions":[{"id":"q","question":"Why?","content_revision":0}]}`)}
	repo := &chunkDetailRepoStub{chunk: chunk}
	registry := retriever.NewRetrieveEngineRegistry(nil, nil)
	engine := &chunkDetailEngineStub{}
	require.NoError(t, registry.Register(engine))
	svc := &knowledgeService{kbService: indexStatusKBService{kb: &types.KnowledgeBase{ID: "kb", TenantID: 7, IndexingStrategy: types.DefaultIndexingStrategy()}}, chunkRepo: repo, tenantRepo: indexStatusTenantRepo{}, retrieveEngine: registry}
	doc := &types.Knowledge{ID: "doc", KnowledgeBaseID: "kb", TenantID: 7, ParseStatus: "completed"}
	ctx := context.WithValue(context.Background(), types.TenantIDContextKey, uint64(7))
	detail, err := svc.GetChunkDetail(ctx, doc, "chunk")
	require.NoError(t, err)
	require.Equal(t, "Parser body", detail.SourceContent)
	require.Equal(t, "Heading", detail.ContextHeader)
	require.Equal(t, "Current body", detail.Chunk.Content)
	require.True(t, detail.ChildrenAvailable)
	require.Len(t, detail.Children, 1)
	require.Equal(t, "OCR text", detail.Children[0].Content)
	require.Len(t, detail.Indexes, 5)
	require.Equal(t, "completed", detail.Indexes[0].Status)
	require.Equal(t, "unknown", detail.Indexes[2].Status)
	require.Len(t, detail.Indexes[4].Questions, 1)
	question := detail.Indexes[4].Questions[0]
	require.True(t, *question.VectorIndexed)
	require.False(t, *question.KeywordIndexed)
	require.Equal(t, "partial_failed", detail.Indexes[4].Status)
	_, err = svc.GetChunkDetail(context.WithValue(ctx, types.TenantIDContextKey, uint64(8)), doc, "chunk")
	require.Error(t, err)
	require.Equal(t, 1, repo.calls)
	chunk.KnowledgeID = "other"
	_, err = svc.GetChunkDetail(ctx, doc, "chunk")
	require.ErrorIs(t, err, ErrChunkNotFound)
	require.Equal(t, 2, engine.calls, "foreign chunks must never query stores")
}
func TestChunkIndexEvidenceRequiresAllStoresAndContextOnly(t *testing.T) {
	row := types.ChunkIndexDetail{Kind: "vector", Available: true, Enabled: true, Stores: []types.RetrieverEngineType{types.PostgresRetrieverEngineType, types.SQLiteRetrieverEngineType}, Records: []types.ChunkRetrievalRecord{{StoreType: types.PostgresRetrieverEngineType, SourceID: "chunk"}}}
	require.False(t, *chunkSourceIndexed(row, "chunk"))
	resolveChunkIndexDetail(&row, &types.Chunk{ID: "chunk", IndexStatus: "ready"}, false)
	require.Equal(t, "stale", row.Status)
	resolveChunkIndexDetail(&row, &types.Chunk{ID: "parent", ChunkType: types.ChunkTypeParentText}, false)
	require.Equal(t, "context_only", row.Reason)
	row.Available = false
	require.Nil(t, chunkSourceIndexed(row, "chunk"))
}
