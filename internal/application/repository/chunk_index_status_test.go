package repository

import (
	"context"
	"testing"

	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"github.com/stretchr/testify/require"
)

func TestIndexStatusChunksExcludeForeignDeletedRowsAndBodies(t *testing.T) {
	db := setupChunkTestDB(t)
	for _, chunk := range []*types.Chunk{
		{ID: "target", TenantID: 7, KnowledgeBaseID: "kb", KnowledgeID: "doc", Content: "large body", Metadata: types.JSON(`{"generated_questions":[]}`)},
		{ID: "foreign", TenantID: 8, KnowledgeBaseID: "kb", KnowledgeID: "doc", Content: "secret"},
		{ID: "other-doc", TenantID: 7, KnowledgeBaseID: "kb", KnowledgeID: "other", Content: "secret"},
		{ID: "deleted", TenantID: 7, KnowledgeBaseID: "kb", KnowledgeID: "doc", Content: "old"},
	} {
		require.NoError(t, db.Create(chunk).Error)
	}
	require.NoError(t, db.Delete(&types.Chunk{ID: "deleted"}).Error)
	reader := NewChunkRepository(db).(interfaces.KnowledgeIndexChunkReader)
	chunks, err := reader.ListIndexStatusChunks(context.Background(), 7, "doc")
	require.NoError(t, err)
	require.Len(t, chunks, 1)
	require.Equal(t, "target", chunks[0].ID)
	require.Empty(t, chunks[0].Content)
	require.NotEmpty(t, chunks[0].Metadata)
}
