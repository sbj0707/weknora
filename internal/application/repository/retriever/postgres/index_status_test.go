package postgres

import (
	"context"
	"testing"

	"github.com/Tencent/WeKnora/internal/types"
	"github.com/stretchr/testify/require"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
)

func TestCountKnowledgeIndicesScopesAndSeparatesQuestions(t *testing.T) {
	db, err := gorm.Open(sqlite.Open(":memory:"), &gorm.Config{})
	require.NoError(t, err)
	sqlDB, err := db.DB()
	require.NoError(t, err)
	t.Cleanup(func() { _ = sqlDB.Close() })
	require.NoError(t, db.Exec(`CREATE TABLE embeddings (
		knowledge_base_id TEXT, knowledge_id TEXT, source_type INTEGER,
		source_id TEXT, chunk_id TEXT, dimension INTEGER, embedding TEXT)`).Error)
	for _, entry := range []struct {
		kb, doc, source, chunk string
		dim                    int
	}{
		{"kb", "doc", "chunk", "chunk", 3},
		{"kb", "doc", "chunk-q", "chunk", 3},
		{"kb", "doc", "keyword-only", "keyword-only", 0},
		{"other-kb", "doc", "foreign", "foreign", 3},
		{"kb", "other-doc", "other", "other", 3},
	} {
		require.NoError(t, db.Exec("INSERT INTO embeddings VALUES (?, ?, ?, ?, ?, ?, ?)",
			entry.kb, entry.doc, types.ChunkSourceType, entry.source, entry.chunk, entry.dim, "vector").Error)
	}
	repo := &pgRepository{db: db}
	vector, err := repo.CountKnowledgeIndices(context.Background(), "kb", "doc", types.VectorRetrieverType)
	require.NoError(t, err)
	require.Equal(t, &types.KnowledgeRetrievalIndexCounts{Chunks: 1, Questions: 1}, vector)
	keywords, err := repo.CountKnowledgeIndices(context.Background(), "kb", "doc", types.KeywordsRetrieverType)
	require.NoError(t, err)
	require.Equal(t, &types.KnowledgeRetrievalIndexCounts{Chunks: 2, Questions: 1}, keywords)
}
