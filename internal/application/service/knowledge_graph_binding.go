package service

import "github.com/Tencent/WeKnora/internal/types"

// A task may never expose an unrelated owner document or KB to graph extraction.
func graphChunkBound(chunk *types.Chunk, knowledge *types.Knowledge, owner uint64) bool {
	return chunk != nil && knowledge != nil && chunk.TenantID == owner && knowledge.TenantID == owner && chunk.KnowledgeID == knowledge.ID && chunk.KnowledgeBaseID == knowledge.KnowledgeBaseID
}
