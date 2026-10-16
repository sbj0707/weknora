package chatpipeline

import (
	"context"
	"github.com/Tencent/WeKnora/internal/application/access"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"sort"
)

// graphQueryTargets revalidates the original caller against server-loaded owner rows.
// SearchTargets is a restriction; neither raw IDs nor a graph reference create a grant.
func graphQueryTargets(ctx context.Context, targets types.SearchTargets, bases interfaces.KnowledgeBaseRepository, rows interfaces.KnowledgeRepository,
	shares interfaces.KBShareService) types.SearchTargets {
	if scope, ok := types.TenantAPIKeyScopeFromContext(ctx); ok && !scope.FullAccess && !scope.HasCapability(types.APIKeyCapabilityChat) {
		return nil
	}
	var ids []string
	seen := map[string]bool{}
	for _, t := range targets {
		if t != nil && t.TenantID != 0 && t.KnowledgeBaseID != "" && !seen[t.KnowledgeBaseID] {
			seen[t.KnowledgeBaseID] = true
			ids = append(ids, t.KnowledgeBaseID)
		}
	}
	if len(ids) == 0 {
		return nil
	}
	// Original constructors do not have a repository; keep only caller/grant-bound targets.
	owners := map[string]*types.KnowledgeBase{}
	if bases != nil {
		rows, err := bases.GetKnowledgeBaseByIDs(ctx, ids)
		if err != nil {
			return nil
		}
		for _, kb := range rows {
			if kb != nil {
				owners[kb.ID] = kb
			}
		}
	}
	permissions := access.NewKBPermissions(ctx, shares)
	var out types.SearchTargets
	for _, t := range targets {
		if t == nil || t.TenantID == 0 || t.KnowledgeBaseID == "" || t.Type != types.SearchTargetTypeKnowledgeBase && t.Type != types.SearchTargetTypeKnowledge || t.Type == types.SearchTargetTypeKnowledge && len(t.KnowledgeIDs) == 0 {
			continue
		}
		if bases != nil {
			kb := owners[t.KnowledgeBaseID]
			if kb == nil || kb.TenantID != t.TenantID || kb.ExtractConfig == nil || !kb.ExtractConfig.Enabled {
				continue
			}
		}
		allowed, err := permissions.Check(t.KnowledgeBaseID, t.TenantID, types.OrgRoleViewer)
		if err != nil || !allowed {
			continue
		}
		if t.Type == types.SearchTargetTypeKnowledge && rows != nil {
			documents, err := rows.GetKnowledgeBatchByIDOnly(ctx, t.KnowledgeIDs)
			if err != nil {
				continue
			}
			var ids []string
			for _, doc := range documents {
				if doc != nil && doc.TenantID == t.TenantID && doc.KnowledgeBaseID == t.KnowledgeBaseID {
					ids = append(ids, doc.ID)
				}
			}
			if len(ids) == 0 {
				continue
			}
			copy := *t
			copy.KnowledgeIDs = ids
			t = &copy
		}
		out = append(out, t)
	}
	return out
}

// graphBoundChunkRepository constrains image expansion to the persisted parent owner.
// It retains shared-parent support without allowing same-ID rows from another document.
type graphBoundChunkRepository struct{ interfaces.ChunkRepository }

func (r graphBoundChunkRepository) ListChunksByParentIDsOnly(ctx context.Context, ids []string) ([]*types.Chunk, error) {
	parents, err := r.ListChunksByIDOnly(ctx, ids)
	if err != nil {
		return nil, err
	}
	bound := map[string]*types.Chunk{}
	for _, p := range parents {
		if p != nil && p.IsEnabled {
			bound[p.ID] = p
		}
	}
	rows, err := r.ChunkRepository.ListChunksByParentIDsOnly(ctx, ids)
	if err != nil {
		return nil, err
	}
	out := make([]*types.Chunk, 0, len(rows))
	for _, c := range rows {
		if c == nil {
			continue
		}
		p := bound[c.ParentChunkID]
		if p != nil && c.TenantID == p.TenantID && c.KnowledgeBaseID == p.KnowledgeBaseID && c.KnowledgeID == p.KnowledgeID {
			out = append(out, c)
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].ID < out[j].ID })
	return out, nil
}
