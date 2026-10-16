package service

import (
	"context"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"github.com/stretchr/testify/require"
	"testing"
)

type hybridBindingBases struct {
	interfaces.KnowledgeBaseRepository
	owner uint64
}

func (r hybridBindingBases) GetKnowledgeBaseByIDs(context.Context, []string) ([]*types.KnowledgeBase, error) {
	return []*types.KnowledgeBase{{ID: "kb", TenantID: r.owner}}, nil
}
func TestHybridPrimaryPersistenceBinding(t *testing.T) {
	document := &types.Knowledge{ID: "doc", KnowledgeBaseID: "kb", TenantID: 2}
	chunk := types.Chunk{ID: "chunk", KnowledgeID: "doc", KnowledgeBaseID: "kb", TenantID: 2}
	hit := types.IndexWithScore{ChunkID: "chunk", KnowledgeID: "doc", KnowledgeBaseID: "kb"}
	service := knowledgeBaseService{repo: hybridBindingBases{owner: 2}}
	for _, field := range []string{"valid", "chunkOwner", "chunkKB", "chunkDocument", "hitKB", "hitDocument", "kbOwner"} {
		t.Run(field, func(t *testing.T) {
			c, h := chunk, hit
			s := service
			switch field {
			case "chunkOwner":
				c.TenantID = 1
			case "chunkKB":
				c.KnowledgeBaseID = "other"
			case "chunkDocument":
				c.KnowledgeID = "other"
			case "hitKB":
				h.KnowledgeBaseID = "other"
			case "hitDocument":
				h.KnowledgeID = "other"
			case "kbOwner":
				s.repo = hybridBindingBases{owner: 9}
			}
			out, err := s.bindPrimarySearchChunks(context.Background(), []*types.IndexWithScore{&h}, map[string]*types.Knowledge{"doc": document}, []*types.Chunk{&c})
			require.NoError(t, err)
			if field == "valid" {
				require.Equal(t, []*types.Chunk{&c}, out)
			} else {
				require.Empty(t, out)
			}
		})
	}
}
func TestHybridContextLinksCannotWidenOriginDocument(t *testing.T) {
	origin := &types.Chunk{ID: "origin", TenantID: 2, KnowledgeBaseID: "kb", KnowledgeID: "doc", ParentChunkID: "parent", NextChunkID: "next", PreChunkID: "prev", RelationChunks: types.JSON([]byte(`["relation"]`))}
	for _, id := range []string{"parent", "next", "prev", "relation"} {
		for _, field := range []string{"valid", "owner", "kb", "document"} {
			t.Run(id+"/"+field, func(t *testing.T) {
				c := &types.Chunk{ID: id, TenantID: 2, KnowledgeBaseID: "kb", KnowledgeID: "doc"}
				switch field {
				case "owner":
					c.TenantID = 1
				case "kb":
					c.KnowledgeBaseID = "other"
				case "document":
					c.KnowledgeID = "other"
				}
				out := bindSearchContext([]*types.Chunk{origin}, []*types.Chunk{c}, false)
				if field == "valid" {
					require.Equal(t, []*types.Chunk{c}, out)
				} else {
					require.Empty(t, out)
				}
				if id != "parent" {
					require.Empty(t, bindSearchContext([]*types.Chunk{origin}, []*types.Chunk{c}, true))
				}
			})
		}
	}
}
