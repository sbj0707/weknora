package service

import (
	"github.com/Tencent/WeKnora/internal/types"
	"testing"
)

func TestQuestionChunkBindingIncludesOwnerKnowledgeAndBase(t *testing.T) {
	payload := types.QuestionGenerationPayload{TenantID: 1, KnowledgeID: "document", KnowledgeBaseID: "kb"}
	cases := []struct {
		name  string
		chunk *types.Chunk
		want  bool
	}{
		{"missing", nil, false},
		{"same document", &types.Chunk{TenantID: 1, KnowledgeID: "document", KnowledgeBaseID: "kb"}, true},
		{"other owner", &types.Chunk{TenantID: 2, KnowledgeID: "document", KnowledgeBaseID: "kb"}, false},
		{"other document", &types.Chunk{TenantID: 1, KnowledgeID: "other", KnowledgeBaseID: "kb"}, false},
		{"other base", &types.Chunk{TenantID: 1, KnowledgeID: "document", KnowledgeBaseID: "other"}, false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := questionChunkBound(tc.chunk, payload); got != tc.want {
				t.Fatalf("binding=%v,want=%v", got, tc.want)
			}
		})
	}
}
