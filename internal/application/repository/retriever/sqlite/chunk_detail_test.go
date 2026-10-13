package sqlite

import (
	"context"
	"testing"

	"github.com/Tencent/WeKnora/internal/types"
	"github.com/stretchr/testify/require"
)

func TestChunkIndexRecordsScopeVectorsAndMissingPhysicalRows(t *testing.T) {
	repo := newSQLiteRetrieverTestRepository(t)
	for _, row := range []struct{ chunk, source, kb, doc string }{
		{"chunk", "chunk", "kb", "doc"}, {"chunk", "chunk-q", "kb", "doc"},
		{"chunk", "foreign-kb", "other", "doc"}, {"chunk", "foreign-doc", "kb", "other"}, {"other", "other", "kb", "doc"},
	} {
		info := sqliteTestIndex(row.chunk, row.kb, row.doc, "", false)
		info.SourceID = row.source
		info.Content = "Actual indexed text"
		saveSQLiteTestVector(t, repo, info, []float32{1, -0.5})
	}
	rows, err := repo.ListChunkIndexRecords(context.Background(), "kb", "doc", "chunk", types.VectorRetrieverType)
	require.NoError(t, err)
	require.Len(t, rows, 2)
	for _, row := range rows {
		require.Equal(t, "chunk", row.ChunkID)
		require.Equal(t, []float32{1, -0.5}, row.Embedding)
		require.False(t, row.IsEnabled)
		require.Equal(t, "Actual indexed text", row.Content)
	}
	require.NoError(t, repo.db.Exec("DELETE FROM vec_embeddings_2 WHERE rowid IN (SELECT id FROM lite_embeddings WHERE source_id = ?)", "chunk-q").Error)
	rows, err = repo.ListChunkIndexRecords(context.Background(), "kb", "doc", "chunk", types.VectorRetrieverType)
	require.NoError(t, err)
	require.Len(t, rows, 1)
	require.Equal(t, "chunk", rows[0].SourceID)
	rows, err = repo.ListChunkIndexRecords(context.Background(), "kb", "doc", "chunk", types.KeywordsRetrieverType)
	require.NoError(t, err)
	require.Len(t, rows, 2)
	require.Empty(t, rows[0].Embedding)
	require.NoError(t, repo.db.Exec("DELETE FROM lite_embeddings_fts WHERE rowid IN (SELECT id FROM lite_embeddings WHERE source_id = ?)", "chunk-q").Error)
	rows, err = repo.ListChunkIndexRecords(context.Background(), "kb", "doc", "chunk", types.KeywordsRetrieverType)
	require.NoError(t, err)
	require.Len(t, rows, 1)
}
