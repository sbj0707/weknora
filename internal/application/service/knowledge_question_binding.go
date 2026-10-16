package service

import (
	"context"
	"github.com/Tencent/WeKnora/internal/types"
)

// Task-supplied chunks and neighbors must belong to the same document and KB.
// Checking only tenant and ID exposes unrelated owner documents to the model.
func questionChunkBound(chunk *types.Chunk, payload types.QuestionGenerationPayload) bool {
	return chunk != nil && chunk.TenantID == payload.TenantID && chunk.KnowledgeID == payload.KnowledgeID && chunk.KnowledgeBaseID == payload.KnowledgeBaseID
}

// Recheck after provider calls before publishing metadata or question indexes.
func (s *knowledgeService) questionKnowledgeCurrent(ctx context.Context, payload types.QuestionGenerationPayload) bool {
	knowledge, err := s.repo.GetKnowledgeByID(ctx, payload.TenantID, payload.KnowledgeID)
	return err == nil && knowledge != nil && knowledge.KnowledgeBaseID == payload.KnowledgeBaseID && knowledge.ParseStatus != types.ParseStatusCancelled && knowledge.ParseStatus != types.ParseStatusDeleting
}
