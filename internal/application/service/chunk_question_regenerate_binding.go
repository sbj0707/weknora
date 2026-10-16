package service

import (
	"context"
	"github.com/Tencent/WeKnora/internal/types"
	"reflect"
	"time"
)

// Synchronous Doc2Query has two remote calls. Both must remain bound to the
// original document, owner, revision, metadata and model configuration.
func (s *knowledgeService) regeneratedQuestionCurrent(ctx context.Context, expected *types.Chunk, document *types.Knowledge, kb *types.KnowledgeBase) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	current, err := s.chunkRepo.GetChunkByID(ctx, expected.TenantID, expected.ID)
	if err != nil || current == nil || !sameChunkDocument(expected, current) || current.ContentRevision != expected.ContentRevision || current.Content != expected.Content || current.ChunkType != expected.ChunkType || current.IsEnabled != expected.IsEnabled || current.ContextHeader != expected.ContextHeader || current.ParentChunkID != expected.ParentChunkID || !sameChunkIndexMetadata(current.Metadata, expected.Metadata) {
		return ErrChunkRevisionConflict
	}
	knowledge, err := s.repo.GetKnowledgeByID(ctx, expected.TenantID, expected.KnowledgeID)
	if err != nil || knowledge == nil || knowledge.TenantID != expected.TenantID || knowledge.KnowledgeBaseID != expected.KnowledgeBaseID || knowledge.Title != document.Title || knowledge.ParseStatus != document.ParseStatus || !sameChunkIndexMetadata(knowledge.Metadata, document.Metadata) {
		return ErrChunkRevisionConflict
	}
	base, err := s.kbService.GetKnowledgeBaseByID(ctx, expected.KnowledgeBaseID)
	if err != nil || base == nil || base.TenantID != expected.TenantID || base.SummaryModelID != kb.SummaryModelID || base.EmbeddingModelID != kb.EmbeddingModelID || base.Type != kb.Type || vectorStoreBinding(base.VectorStoreID) != vectorStoreBinding(kb.VectorStoreID) || !reflect.DeepEqual(base.QuestionGenerationConfig, kb.QuestionGenerationConfig) {
		return ErrChunkRevisionConflict
	}
	return nil
}

type chunkQuestionsSaver interface {
	UpdateChunkQuestions(context.Context, *types.Chunk, *types.Chunk) error
}

func (s *knowledgeService) saveRegeneratedQuestions(ctx context.Context, expected, updated *types.Chunk) error {
	updated.UpdatedAt = time.Now()
	if saver, ok := s.chunkRepo.(chunkQuestionsSaver); ok {
		return saver.UpdateChunkQuestions(ctx, expected, updated)
	}
	return s.chunkRepo.UpdateChunk(ctx, updated)
}
