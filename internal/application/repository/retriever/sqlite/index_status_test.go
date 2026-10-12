package sqlite

import (
	"context"
	"testing"

	"github.com/Tencent/WeKnora/internal/types"
	"github.com/stretchr/testify/require"
)

func TestCountKnowledgeIndicesUsesPhysicalRecords(t *testing.T) {
	repo := newSQLiteRetrieverTestRepository(t)
	for _, row := range []struct{ chunk, source, kb, doc string }{
		{"chunk", "chunk", "kb", "doc"},
		{"chunk", "question", "kb", "doc"},
		{"other", "other", "foreign", "doc"},
		{"otherdoc", "otherdoc", "kb", "foreign"},
	} {
		info := sqliteTestIndex(row.chunk, row.kb, row.doc, "", false)
		info.SourceID = row.source
		saveSQLiteTestVector(t, repo, info, []float32{1, 0})
	}
	for _, kind := range []types.RetrieverType{types.VectorRetrieverType, types.KeywordsRetrieverType} {
		counts, err := repo.CountKnowledgeIndices(context.Background(), "kb", "doc", kind)
		require.NoError(t, err)
		require.Equal(t, int64(1), counts.Chunks)
		require.Equal(t, int64(1), counts.Questions)
	}
	// Metadata survives a damaged physical index: it must not inflate the count.
	require.NoError(t, repo.db.Exec("DELETE FROM vec_embeddings_2 WHERE rowid IN (SELECT id FROM lite_embeddings WHERE source_id = ?)", "question").Error)
	counts, err := repo.CountKnowledgeIndices(context.Background(), "kb", "doc", types.VectorRetrieverType)
	require.NoError(t, err)
	require.Equal(t, int64(1), counts.Chunks)
	require.Zero(t, counts.Questions)
	require.NoError(t, repo.db.Exec("DELETE FROM lite_embeddings_fts WHERE rowid IN (SELECT id FROM lite_embeddings WHERE source_id = ?)", "chunk").Error)
	counts, err = repo.CountKnowledgeIndices(context.Background(), "kb", "doc", types.KeywordsRetrieverType)
	require.NoError(t, err)
	require.Zero(t, counts.Chunks)
	require.Equal(t, int64(1), counts.Questions)
}
