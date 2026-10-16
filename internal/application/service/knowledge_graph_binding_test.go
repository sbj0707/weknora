package service

import (
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/stretchr/testify/require"
	"testing"
)

func TestGraphChunkBinding(t *testing.T) {
	k := &types.Knowledge{ID: "doc", TenantID: 1, KnowledgeBaseID: "kb"}
	c := &types.Chunk{ID: "chunk", TenantID: 1, KnowledgeID: "doc", KnowledgeBaseID: "kb"}
	require.True(t, graphChunkBound(c, k, 1))
	for _, change := range []func(*types.Chunk){func(c *types.Chunk) { c.TenantID = 2 }, func(c *types.Chunk) { c.KnowledgeID = "other" }, func(c *types.Chunk) { c.KnowledgeBaseID = "other" }} {
		copy := *c
		change(&copy)
		require.False(t, graphChunkBound(&copy, k, 1))
	}
	require.False(t, graphChunkBound(c, nil, 1))
	require.False(t, graphChunkBound(nil, k, 1))
	require.False(t, graphChunkBound(c, k, 2))
}
