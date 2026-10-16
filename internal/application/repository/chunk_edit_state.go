package repository

import (
	"context"
	"encoding/json"
	"github.com/Tencent/WeKnora/internal/common"
	"github.com/Tencent/WeKnora/internal/types"
)

// A synchronous question response must not restore an obsolete whole chunk.
func (r *chunkRepository) UpdateChunkQuestions(ctx context.Context, expected, updated *types.Chunk) error {
	result := r.db.WithContext(ctx).Exec(`UPDATE chunks SET metadata=?,source_content=?,parent_chunk_id=?,updated_at=?
WHERE id=? AND tenant_id=? AND knowledge_id=? AND knowledge_base_id=?
AND content_revision=? AND content=? AND is_enabled=?
AND COALESCE(CAST(metadata AS TEXT),'')=? AND deleted_at IS NULL
AND EXISTS(SELECT 1 FROM knowledges k JOIN knowledge_bases b ON b.id=k.knowledge_base_id
 WHERE k.id=chunks.knowledge_id AND k.tenant_id=chunks.tenant_id AND b.tenant_id=chunks.tenant_id
 AND k.knowledge_base_id=chunks.knowledge_base_id AND k.deleted_at IS NULL AND b.deleted_at IS NULL)`,
		json.RawMessage(updated.Metadata), updated.SourceContent, updated.ParentChunkID, updated.UpdatedAt,
		expected.ID, expected.TenantID, expected.KnowledgeID, expected.KnowledgeBaseID,
		expected.ContentRevision, common.CleanInvalidUTF8(expected.Content), expected.IsEnabled, string(expected.Metadata))
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected != 1 {
		return ErrChunkRevisionConflict
	}
	return nil
}

// A completion changes only index state. GORM Save of the old full chunk would
// otherwise overwrite a newer revision, questions or ownership.
func (r *chunkRepository) UpdateChunkIndexState(ctx context.Context, chunk *types.Chunk) error {
	result := r.db.WithContext(ctx).Exec(`UPDATE chunks SET index_status=?,updated_at=?
WHERE id=? AND tenant_id=? AND content_revision=? AND content=? AND is_enabled=?
AND COALESCE(CAST(metadata AS TEXT),'')=? AND deleted_at IS NULL
AND EXISTS(SELECT 1 FROM knowledges k JOIN knowledge_bases b ON b.id=k.knowledge_base_id
 WHERE k.id=chunks.knowledge_id AND k.tenant_id=chunks.tenant_id AND b.tenant_id=chunks.tenant_id
 AND k.knowledge_base_id=chunks.knowledge_base_id AND k.deleted_at IS NULL AND b.deleted_at IS NULL)`,
		chunk.IndexStatus, chunk.UpdatedAt, chunk.ID, chunk.TenantID, chunk.ContentRevision, common.CleanInvalidUTF8(chunk.Content), chunk.IsEnabled, string(chunk.Metadata))
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected != 1 {
		return ErrChunkRevisionConflict
	}
	return nil
}
