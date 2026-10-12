package service

import (
	"context"
	"testing"
	"time"

	"github.com/Tencent/WeKnora/internal/application/service/retriever"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"github.com/stretchr/testify/require"
)

func TestDocumentIndexStateUsesEvidence(t *testing.T) {
	done := func(name string) types.KnowledgeProcessingSpan {
		return types.KnowledgeProcessingSpan{Name: name, Status: types.SpanStatusDone}
	}
	post := done(types.StagePostProcess)
	post.Output = types.JSONMap{"enqueued_graph_count": 2, "enqueued_question_count": 2}
	group := done("postprocess.question")
	group.Input = types.JSONMap{"batch_count": 2}
	tests := []struct {
		name        string
		row         types.KnowledgeIndexStatusRow
		parseStatus string
		spans       []types.KnowledgeProcessingSpan
		want        string
	}{
		{"legacy completion is not proof", types.KnowledgeIndexStatusRow{Kind: "vector", Enabled: true, Count: indexCount(5)}, "completed", nil, "unknown"},
		{"enabled graph is not built", types.KnowledgeIndexStatusRow{Kind: "graph", Enabled: true, Count: indexCount(0)}, "completed", nil, "stale"},
		{"disabled optional index", types.KnowledgeIndexStatusRow{Kind: "graph"}, "completed", nil, "disabled"},
		{"statistics errors are not zero", types.KnowledgeIndexStatusRow{Kind: "graph", Enabled: true}, "completed", []types.KnowledgeProcessingSpan{done("postprocess.graph.chunk[0]")}, "unknown"},
		{"graph partly failed despite completed document", types.KnowledgeIndexStatusRow{Kind: "graph", Enabled: true, Count: indexCount(3)}, "completed", []types.KnowledgeProcessingSpan{post, done("postprocess.graph.chunk[0]"), {Name: "postprocess.graph.chunk[1]", Status: types.SpanStatusFailed}}, "partial_failed"},
		{"queued graph worker not mistaken for complete", types.KnowledgeIndexStatusRow{Kind: "graph", Enabled: true, Count: indexCount(3)}, "finalizing", []types.KnowledgeProcessingSpan{post, done("postprocess.graph.chunk[0]")}, "building"},
		{"question fanout container does not prove success", types.KnowledgeIndexStatusRow{Kind: "questions", Enabled: true, Count: indexCount(0), IndexedCount: indexCount(0)}, "finalizing", []types.KnowledgeProcessingSpan{post, group}, "waiting"},
		{"questions written but not indexed", types.KnowledgeIndexStatusRow{Kind: "questions", Enabled: true, Count: indexCount(5), IndexedCount: indexCount(2)}, "completed", []types.KnowledgeProcessingSpan{done("postprocess.question.batch[0]")}, "partial_failed"},
		{"questions with unknown indexing", types.KnowledgeIndexStatusRow{Kind: "questions", Enabled: true, Count: indexCount(5)}, "completed", []types.KnowledgeProcessingSpan{done("postprocess.question.batch[0]")}, "unknown"},
		{"cancelled graph stops reporting building", types.KnowledgeIndexStatusRow{Kind: "graph", Enabled: true, Count: indexCount(0)}, "cancelled", []types.KnowledgeProcessingSpan{{Name: "postprocess.graph.chunk[0]", Status: types.SpanStatusRunning}}, "cancelled"},
		{"wiki map succeeded but page writes dropped", types.KnowledgeIndexStatusRow{Kind: "wiki", Enabled: true, Count: indexCount(2)}, "completed", []types.KnowledgeProcessingSpan{{Name: "postprocess.wiki", Status: types.SpanStatusDone, Output: types.JSONMap{"pages_dropped": 1}}}, "partial_failed"},
		{"multimodal completion is not vector evidence", types.KnowledgeIndexStatusRow{Kind: "vector", Enabled: true, Count: indexCount(0)}, "completed", []types.KnowledgeProcessingSpan{done(types.StageMultimodal)}, "stale"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			resolveDocumentIndexState(&tt.row, tt.parseStatus, tt.spans)
			require.Equal(t, tt.want, tt.row.Status)
		})
	}
}

func TestDocumentIndexStateDetectsMissingRecordsAndConfigChanges(t *testing.T) {
	span := types.KnowledgeProcessingSpan{Name: types.StageEmbedding, Status: types.SpanStatusDone,
		Input: types.JSONMap{"vector_enabled": true}, Output: types.JSONMap{"vectors_written": 5}}
	row := types.KnowledgeIndexStatusRow{Kind: "vector", Enabled: true, Count: indexCount(3)}
	resolveDocumentIndexState(&row, "completed", []types.KnowledgeProcessingSpan{span})
	require.Equal(t, "stale", row.Status)
	require.Equal(t, "missing_index", row.Reason)
	span.Input["vector_enabled"] = false
	row.Count = indexCount(5)
	resolveDocumentIndexState(&row, "completed", []types.KnowledgeProcessingSpan{span})
	require.Equal(t, "configuration_changed", row.Reason)
}

func TestDocumentIndexStateIgnoresSupersededRetry(t *testing.T) {
	now := time.Now()
	row := types.KnowledgeIndexStatusRow{Kind: "graph", Enabled: true, Count: indexCount(4)}
	resolveDocumentIndexState(&row, "completed", []types.KnowledgeProcessingSpan{
		{Name: "postprocess.graph.chunk[0]", Status: types.SpanStatusFailed, UpdatedAt: now.Add(-time.Minute)},
		{Name: "postprocess.graph.chunk[0]", Status: types.SpanStatusDone, UpdatedAt: now, FinishedAt: &now},
	})
	require.Equal(t, "completed", row.Status)
	require.Equal(t, &now, row.LastBuiltAt)
}

type indexStatusKBService struct {
	interfaces.KnowledgeBaseService
	kb *types.KnowledgeBase
}

func (s indexStatusKBService) GetKnowledgeBaseByIDOnly(context.Context, string) (*types.KnowledgeBase, error) {
	return s.kb, nil
}

type indexStatusTenantRepo struct{ interfaces.TenantRepository }

func (indexStatusTenantRepo) GetTenantByID(context.Context, uint64) (*types.Tenant, error) {
	return &types.Tenant{ID: 7, RetrieverEngines: types.RetrieverEngines{Engines: []types.RetrieverEngineParams{
		{RetrieverEngineType: types.PostgresRetrieverEngineType, RetrieverType: types.VectorRetrieverType},
		{RetrieverEngineType: types.PostgresRetrieverEngineType, RetrieverType: types.KeywordsRetrieverType},
	}}}, nil
}

type indexStatusEngine struct {
	interfaces.RetrieveEngineService
	calls int
}

func (*indexStatusEngine) EngineType() types.RetrieverEngineType {
	return types.PostgresRetrieverEngineType
}
func (*indexStatusEngine) Support() []types.RetrieverType {
	return []types.RetrieverType{types.VectorRetrieverType, types.KeywordsRetrieverType}
}
func (e *indexStatusEngine) CountKnowledgeIndices(_ context.Context, kbID, id string, _ types.RetrieverType) (*types.KnowledgeRetrievalIndexCounts, error) {
	if kbID != "kb" || id != "doc" {
		panic("unscoped statistics")
	}
	e.calls++
	return &types.KnowledgeRetrievalIndexCounts{Chunks: 2, Questions: 1}, nil
}

type indexStatusChunks struct{ interfaces.ChunkRepository }

func (indexStatusChunks) ListIndexStatusChunks(_ context.Context, tenant uint64, id string) ([]*types.Chunk, error) {
	if tenant != 7 || id != "doc" {
		panic("unscoped chunks")
	}
	return []*types.Chunk{{ID: "chunk", KnowledgeID: "doc", KnowledgeBaseID: "kb", TenantID: 7,
		ContentRevision: 2, Metadata: types.JSON(`{"generated_questions":[{"id":"q","question":"Why?","content_revision":1}]}`)}}, nil
}

func TestKnowledgeIndexStatusUsesOwnerScopeAndQuestionRevisions(t *testing.T) {
	registry := retriever.NewRetrieveEngineRegistry(nil, nil)
	engine := &indexStatusEngine{}
	require.NoError(t, registry.Register(engine))
	s := &knowledgeService{kbService: indexStatusKBService{kb: &types.KnowledgeBase{ID: "kb", TenantID: 7,
		IndexingStrategy: types.DefaultIndexingStrategy(), QuestionGenerationConfig: &types.QuestionGenerationConfig{Enabled: true}}},
		tenantRepo: indexStatusTenantRepo{}, retrieveEngine: registry, chunkRepo: indexStatusChunks{}}
	ctx := context.WithValue(context.Background(), types.TenantIDContextKey, uint64(7))
	knowledge := &types.Knowledge{ID: "doc", TenantID: 7, KnowledgeBaseID: "kb", ParseStatus: "completed"}
	report, err := s.GetKnowledgeIndexStatus(ctx, knowledge, []types.KnowledgeProcessingSpan{
		{Name: types.StageEmbedding, Status: types.SpanStatusDone, Output: types.JSONMap{"vectors_written": 2}},
		{Name: "postprocess.question.batch[0]", Status: types.SpanStatusDone},
	})
	require.NoError(t, err)
	require.Len(t, report.Indexes, 5)
	require.Equal(t, int64(2), *report.Indexes[0].Count)
	require.Equal(t, int64(1), *report.Indexes[4].Count)
	require.Equal(t, int64(1), *report.Indexes[4].IndexedCount)
	require.Equal(t, "stale", report.Indexes[4].Status)
	require.Equal(t, "content_changed", report.Indexes[4].Reason)
	require.Equal(t, 2, engine.calls)
	ctx = context.WithValue(ctx, types.TenantIDContextKey, uint64(8))
	_, err = s.GetKnowledgeIndexStatus(ctx, knowledge, nil)
	require.Error(t, err)
	require.Equal(t, 2, engine.calls, "cross-tenant requests must not reach index stores")
}
