package service

import (
	"context"
	"testing"

	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"github.com/stretchr/testify/require"
)

type documentTableCleanupIndex struct {
	interfaces.RetrieveEngineService
	deleted []string
}

func (i *documentTableCleanupIndex) DeleteByChunkIDList(_ context.Context, ids []string, dim int, kind string) error {
	i.deleted = append(i.deleted, ids...)
	return nil
}

func TestDocumentCleanupPreservesParallelTableMetadataAndReparseRemovesOldAttempt(t *testing.T) {
	f := newDocumentWriteFixture(t)
	knowledge, err := f.repo.GetKnowledgeByID(f.ctx, 7, "doc")
	require.NoError(t, err)
	knowledge.Type = "file"
	knowledge.FileType = "csv"
	for _, typ := range []string{types.ChunkTypeTableSummary, types.ChunkTypeTableColumn} {
		require.NoError(t, f.db.Create(&types.Chunk{ID: typ, TenantID: 7, KnowledgeID: "doc", KnowledgeBaseID: "kb", ChunkType: typ}).Error)
	}
	index := &documentTableCleanupIndex{}
	require.NoError(t, f.svc.cleanupDocumentChunksPreservingTable(f.ctx, knowledge, index, 3, true))
	require.Equal(t, []string{"chunk"}, index.deleted)
	chunks, err := f.chunkRepo.ListAllChunksByKnowledgeID(f.ctx, 7, "doc")
	require.NoError(t, err)
	require.Len(t, chunks, 2)
	// A retry finding only this attempt's metadata must not issue document-wide deletion.
	require.NoError(t, f.svc.cleanupDocumentChunksPreservingTable(f.ctx, knowledge, index, 3, true))
	require.Equal(t, []string{"chunk"}, index.deleted)
	require.NoError(t, f.svc.cleanupKnowledgeResources(f.ctx, knowledge))
	chunks, err = f.chunkRepo.ListAllChunksByKnowledgeID(f.ctx, 7, "doc")
	require.NoError(t, err)
	require.Empty(t, chunks)
	foreign, err := f.chunkRepo.ListAllChunksByKnowledgeID(f.ctx, 7, "other-doc")
	require.NoError(t, err)
	require.Len(t, foreign, 1)
}
