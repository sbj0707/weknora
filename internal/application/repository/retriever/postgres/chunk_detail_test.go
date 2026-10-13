package postgres

import (
	"context"
	"testing"

	"github.com/Tencent/WeKnora/internal/types"
	"github.com/stretchr/testify/require"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
)

func TestChunkIndexRecordsScopesAndReadsActualHalfVector(t *testing.T) {
	db, err := gorm.Open(sqlite.Open(":memory:"), &gorm.Config{})
	require.NoError(t, err)
	sqlDB, err := db.DB()
	require.NoError(t, err)
	t.Cleanup(func() { _ = sqlDB.Close() })
	require.NoError(t, db.Exec(`CREATE TABLE embeddings(id INTEGER PRIMARY KEY, knowledge_base_id TEXT,knowledge_id TEXT,chunk_id TEXT,source_id TEXT,source_type INTEGER,content TEXT,dimension INTEGER,embedding TEXT,is_enabled BOOLEAN,updated_at DATETIME)`).Error)
	for i, row := range []struct {
		kb, doc, chunk, source string
		dim                    int
	}{
		{"kb", "doc", "chunk", "chunk", 2}, {"kb", "doc", "chunk", "chunk-q", 2}, {"other", "doc", "chunk", "foreign", 2}, {"kb", "other", "chunk", "foreign-doc", 2}, {"kb", "doc", "other", "other", 2}, {"kb", "doc", "chunk", "keyword-only", 0},
	} {
		require.NoError(t, db.Exec("INSERT INTO embeddings VALUES(?,?,?,?,?,?,?,?,?,?,?)", i+1, row.kb, row.doc, row.chunk, row.source, types.ChunkSourceType, "Stored input", row.dim, "[1,-0.5]", false, "2026-10-12 10:00:00").Error)
	}
	repo := &pgRepository{db: db}
	rows, err := repo.ListChunkIndexRecords(context.Background(), "kb", "doc", "chunk", types.VectorRetrieverType)
	require.NoError(t, err)
	require.Len(t, rows, 2)
	require.Equal(t, []float32{1, -0.5}, rows[0].Embedding)
	require.False(t, rows[0].IsEnabled)
	require.Equal(t, "Stored input", rows[0].Content)
	rows, err = repo.ListChunkIndexRecords(context.Background(), "kb", "doc", "chunk", types.KeywordsRetrieverType)
	require.NoError(t, err)
	require.Len(t, rows, 3)
	require.Empty(t, rows[0].Embedding)
}
