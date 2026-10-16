package repository

import (
	"context"
	"testing"

	"github.com/Tencent/WeKnora/internal/types"
	"github.com/stretchr/testify/require"
)

func TestChunkInspectionIncludesEveryTypeAndStateWithinDocumentScope(t *testing.T) {
	db := setupChunkTestDB(t)
	repo := NewChunkRepository(db)
	ctx := context.Background()
	rows := []*types.Chunk{}
	for index, kind := range []string{types.ChunkTypeText, types.ChunkTypeParentText, types.ChunkTypeImageOCR, types.ChunkTypeSummary, types.ChunkTypeTableColumn} {
		chunk := makeChunk("kb", "doc", kind)
		chunk.ChunkIndex = index
		chunk.Status = index % 3
		rows = append(rows, chunk)
	}
	foreign := makeChunk("kb", "doc", types.ChunkTypeText)
	foreign.TenantID = 2
	other := makeChunk("kb", "other", types.ChunkTypeText)
	deleted := makeChunk("kb", "doc", types.ChunkTypeText)
	rows = append(rows, foreign, other, deleted)
	require.NoError(t, repo.CreateChunks(ctx, rows))
	require.NoError(t, db.Delete(deleted).Error)
	require.NoError(t, db.Model(rows[3]).Update("is_enabled", false).Error)

	seen := []string{}
	for page := 1; page <= 3; page++ {
		chunks, total, err := repo.ListPagedChunksByKnowledgeID(ctx, 1, "doc", &types.Pagination{Page: page, PageSize: 2},
			[]types.ChunkType{types.ChunkTypeAll}, nil, "", "", "", "", nil)
		require.NoError(t, err)
		require.EqualValues(t, 5, total)
		for _, chunk := range chunks {
			require.Equal(t, uint64(1), chunk.TenantID)
			require.Equal(t, "doc", chunk.KnowledgeID)
			seen = append(seen, chunk.ID)
			if chunk.ID == rows[3].ID {
				require.False(t, chunk.IsEnabled)
			}
		}
	}
	require.Equal(t, []string{rows[0].ID, rows[1].ID, rows[2].ID, rows[3].ID, rows[4].ID}, seen)
	chunks, total, err := repo.ListPagedChunksByKnowledgeID(ctx, 1, "doc", &types.Pagination{Page: 1, PageSize: 100},
		[]types.ChunkType{types.ChunkTypeText}, nil, "", "", "", "", nil)
	require.NoError(t, err)
	require.EqualValues(t, 1, total)
	require.Equal(t, rows[0].ID, chunks[0].ID)
}
