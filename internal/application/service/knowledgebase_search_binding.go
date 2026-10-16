package service

import (
	"context"
	"encoding/json"
	"sort"

	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
)

type hybridSearchBinding struct {
	tenant       uint64
	kb, document string
}

func hybridBinding(c *types.Chunk) hybridSearchBinding {
	return hybridSearchBinding{c.TenantID, c.KnowledgeBaseID, c.KnowledgeID}
}

// Index metadata and chunk tenant columns cannot grant access to a different
// persisted document or KB owner, even when each row would pass a separate grant.
func (s *knowledgeBaseService) bindPrimarySearchChunks(ctx context.Context,
	input []*types.IndexWithScore, documents map[string]*types.Knowledge, chunks []*types.Chunk,
) ([]*types.Chunk, error) {
	ids := make([]string, 0, len(documents))
	for _, d := range documents {
		ids = append(ids, d.KnowledgeBaseID)
	}
	rows, err := s.repo.GetKnowledgeBaseByIDs(ctx, ids)
	if err != nil {
		return nil, err
	}
	owners := map[string]uint64{}
	for _, kb := range rows {
		if kb != nil {
			owners[kb.ID] = kb.TenantID
		}
	}
	indexed := map[string]*types.IndexWithScore{}
	for _, hit := range input {
		indexed[hit.ChunkID] = hit
	}
	out := make([]*types.Chunk, 0, len(chunks))
	for _, c := range chunks {
		if c == nil {
			continue
		}
		d := documents[c.KnowledgeID]
		hit := indexed[c.ID]
		if d != nil && hit != nil && owners[d.KnowledgeBaseID] == d.TenantID && c.TenantID == d.TenantID && c.KnowledgeBaseID == d.KnowledgeBaseID && hit.KnowledgeID == d.ID && hit.KnowledgeBaseID == d.KnowledgeBaseID {
			out = append(out, c)
		}
	}
	return out, nil
}

// Context follows the exact persisted origin tuple; a parent/neighbor/relation
// link never widens document scope or switches to a different KB or owner.
func bindSearchContext(origin, fetched []*types.Chunk, parentsOnly bool) []*types.Chunk {
	allowed := map[string]map[hybridSearchBinding]bool{}
	for _, c := range origin {
		ids := []string{c.ParentChunkID}
		if !parentsOnly {
			ids = append(ids, c.NextChunkID, c.PreChunkID)
			var relations []string
			if json.Unmarshal(c.RelationChunks, &relations) == nil {
				ids = append(ids, relations...)
			}
		}
		for _, id := range ids {
			if id == "" {
				continue
			}
			if allowed[id] == nil {
				allowed[id] = map[hybridSearchBinding]bool{}
			}
			allowed[id][hybridBinding(c)] = true
		}
	}
	out := make([]*types.Chunk, 0, len(fetched))
	for _, c := range fetched {
		if c != nil && allowed[c.ID][hybridBinding(c)] {
			out = append(out, c)
		}
	}
	return out
}

type hybridBoundImageRepository struct {
	interfaces.ChunkRepository
	allowed map[hybridSearchBinding]bool
}

func (r hybridBoundImageRepository) ListChunksByParentIDsOnly(ctx context.Context, ids []string) ([]*types.Chunk, error) {
	parents, err := r.ListChunksByIDOnly(ctx, ids)
	if err != nil {
		return nil, err
	}
	bound := map[string]*types.Chunk{}
	for _, p := range parents {
		if p != nil && p.IsEnabled && r.allowed[hybridBinding(p)] {
			bound[p.ID] = p
		}
	}
	children, err := r.ChunkRepository.ListChunksByParentIDsOnly(ctx, ids)
	if err != nil {
		return nil, err
	}
	out := make([]*types.Chunk, 0, len(children))
	for _, c := range children {
		if c == nil {
			continue
		}
		p := bound[c.ParentChunkID]
		if p != nil && hybridBinding(c) == hybridBinding(p) {
			out = append(out, c)
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].ID < out[j].ID })
	return out, nil
}
