package chatpipeline

import (
	"context"
	"sort"
	"sync"

	"github.com/Tencent/WeKnora/internal/logger"
	"github.com/Tencent/WeKnora/internal/searchutil"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
)

// PluginSearch implements search functionality for chat pipeline
type PluginSearchEntity struct {
	graphRepo     interfaces.RetrieveGraphRepository
	chunkRepo     interfaces.ChunkRepository
	knowledgeRepo interfaces.KnowledgeRepository
	bases         interfaces.KnowledgeBaseRepository
	shares        interfaces.KBShareService
}

// NewPluginSearchEntity creates a new plugin search entity
func NewPluginSearchEntity(
	eventManager *EventManager,
	graphRepository interfaces.RetrieveGraphRepository,
	chunkRepository interfaces.ChunkRepository,
	knowledgeRepository interfaces.KnowledgeRepository,
) *PluginSearchEntity {
	res := &PluginSearchEntity{
		graphRepo:     graphRepository,
		chunkRepo:     chunkRepository,
		knowledgeRepo: knowledgeRepository,
	}
	eventManager.Register(res)
	return res
}

// NewPluginSearchEntityAuthorized wires the same caller-bound permission resolver as extraction.
func NewPluginSearchEntityAuthorized(manager *EventManager, graph interfaces.RetrieveGraphRepository,
	chunks interfaces.ChunkRepository, rows interfaces.KnowledgeRepository, bases interfaces.KnowledgeBaseRepository,
	shares interfaces.KBShareService) *PluginSearchEntity {
	p := NewPluginSearchEntity(manager, graph, chunks, rows)
	p.bases = bases
	p.shares = shares
	return p
}

// ActivationEvents returns the list of event types this plugin responds to
func (p *PluginSearchEntity) ActivationEvents() []types.EventType {
	return []types.EventType{types.ENTITY_SEARCH}
}

// OnEvent processes triggered events
func (p *PluginSearchEntity) OnEvent(ctx context.Context,
	eventType types.EventType, chatManage *types.ChatManage, next func() *PluginError,
) *PluginError {
	entity := chatManage.Entity
	if len(entity) == 0 {
		logger.Infof(ctx, "No entity found")
		return next()
	}

	if ctx.Err() != nil {
		return next()
	}
	targets := graphQueryTargets(ctx, chatManage.SearchTargets, p.bases, p.knowledgeRepo, p.shares)
	// Retain the authorized target union, not raw IDs or the extraction map alone.
	enabled := make(map[string]bool)
	for _, kb := range chatManage.EntityKBIDs {
		enabled[kb] = true
	}
	for _, kb := range chatManage.EntityKnowledge {
		enabled[kb] = true
	}
	knowledgeBaseIDs := []string{}
	entityKnowledge := make(map[string]string)
	full := make(map[string]bool)
	for _, target := range targets {
		if target == nil || target.TenantID == 0 || !enabled[target.KnowledgeBaseID] {
			continue
		}
		if target.Type == types.SearchTargetTypeKnowledgeBase && !full[target.KnowledgeBaseID] {
			full[target.KnowledgeBaseID] = true
			knowledgeBaseIDs = append(knowledgeBaseIDs, target.KnowledgeBaseID)
		}
		if target.Type == types.SearchTargetTypeKnowledge {
			for _, id := range target.KnowledgeIDs {
				if id != "" {
					entityKnowledge[id] = target.KnowledgeBaseID
				}
			}
		}
	}
	for id, kb := range entityKnowledge {
		if full[kb] {
			delete(entityKnowledge, id)
		}
	}
	if len(knowledgeBaseIDs) == 0 && len(entityKnowledge) == 0 {
		return next()
	}

	// Canonical namespaces preserve a stable 30-chunk boundary across concurrent queries.
	var namespaces []types.NameSpace
	for _, kb := range knowledgeBaseIDs {
		namespaces = append(namespaces, types.NameSpace{KnowledgeBase: kb})
	}
	for doc, kb := range entityKnowledge {
		namespaces = append(namespaces, types.NameSpace{KnowledgeBase: kb, Knowledge: doc})
	}
	sort.Slice(namespaces, func(i, j int) bool {
		a, b := namespaces[i], namespaces[j]
		return a.KnowledgeBase < b.KnowledgeBase || a.KnowledgeBase == b.KnowledgeBase && a.Knowledge < b.Knowledge
	})
	graphs := make([]*types.GraphData, len(namespaces))
	var wg sync.WaitGroup
	for i, ns := range namespaces {
		wg.Add(1)
		go func(i int, ns types.NameSpace) {
			defer wg.Done()
			g, err := p.graphRepo.SearchNode(ctx, ns, entity)
			if err != nil {
				logger.Errorf(ctx, "Failed to search graph namespace: %v", err)
				return
			}
			graphs[i] = g
		}(i, ns)
	}
	wg.Wait()
	var allNodes []*types.GraphNode
	var allRelations []*types.GraphRelation
	for _, g := range graphs {
		if g != nil {
			allNodes = append(allNodes, g.Node...)
			allRelations = append(allRelations, g.Relation...)
		}
	}

	if ctx.Err() != nil {
		return next()
	}
	targets = graphQueryTargets(ctx, targets, p.bases, p.knowledgeRepo, p.shares)
	allowedFresh := map[string]bool{}
	for _, t := range targets {
		allowedFresh[t.KnowledgeBaseID] = true
	}
	for i, ns := range namespaces {
		if !allowedFresh[ns.KnowledgeBase] {
			graphs[i] = nil
		}
	}
	allNodes = nil
	allRelations = nil
	for _, g := range graphs {
		if g != nil {
			allNodes = append(allNodes, g.Node...)
			allRelations = append(allRelations, g.Relation...)
		}
	}
	// Merge graph data
	chatManage.GraphResult = &types.GraphData{
		Node:     allNodes,
		Relation: allRelations,
	}
	logger.Infof(ctx, "Total entity search result: %d nodes, %d relations", len(allNodes), len(allRelations))

	chunkIDs := filterSeenChunk(ctx, chatManage.GraphResult, chatManage.SearchResult)
	if len(chunkIDs) == 0 {
		logger.Infof(ctx, "No new chunk found")
		return next()
	}
	// Graph hits from an org-shared KB belong to the sharing workspace, so
	// the lookups are not tenant scoped (as for parents, #3342). The graph
	// namespaces are the KBs in this turn's scope; a chunk is only used when
	// its row belongs to one of them.
	allowedKBs := make(map[string]bool, len(knowledgeBaseIDs)+len(entityKnowledge))
	for _, id := range knowledgeBaseIDs {
		allowedKBs[id] = true
	}
	for _, kbID := range entityKnowledge {
		allowedKBs[kbID] = true
	}
	listed, err := p.chunkRepo.ListChunksByIDOnly(ctx, chunkIDs)
	if err != nil {
		logger.Errorf(ctx, "Failed to list chunks, session_id: %s, error: %v", chatManage.SessionID, err)
		return next()
	}
	sort.SliceStable(listed, func(i, j int) bool { return listed[i] != nil && (listed[j] == nil || listed[i].ID < listed[j].ID) })
	chunks := make([]*types.Chunk, 0, len(listed))
	knowledgeIDs := []string{}
	for _, chunk := range listed {
		if chunk == nil || !allowedKBs[chunk.KnowledgeBaseID] || !graphTargetAllowsChunk(targets, chunk) {
			continue
		}
		chunks = append(chunks, chunk)
		knowledgeIDs = append(knowledgeIDs, chunk.KnowledgeID)
	}
	knowledges, err := p.knowledgeRepo.GetKnowledgeBatchByIDOnly(ctx, knowledgeIDs)
	if err != nil {
		logger.Errorf(ctx, "Failed to list knowledge, session_id: %s, error: %v", chatManage.SessionID, err)
		return next()
	}

	knowledgeMap := map[string]*types.Knowledge{}
	for _, knowledge := range knowledges {
		knowledgeMap[knowledge.ID] = knowledge
	}
	var entityResults []*types.SearchResult
	for _, chunk := range chunks {
		// A chunk can outlive its soft-deleted document, and a disabled
		// chunk must not come back through the graph either.
		knowledge := knowledgeMap[chunk.KnowledgeID]
		if knowledge == nil || !chunk.IsEnabled || knowledge.TenantID != chunk.TenantID || knowledge.KnowledgeBaseID != chunk.KnowledgeBaseID {
			continue
		}
		entityResults = append(entityResults, chunk2SearchResult(chunk, knowledge))
	}
	searchutil.EnrichSearchResultsImageInfoOnly(ctx, graphBoundChunkRepository{ChunkRepository: p.chunkRepo}, entityResults)
	if ctx.Err() != nil {
		return next()
	}
	targets = graphQueryTargets(ctx, targets, p.bases, p.knowledgeRepo, p.shares)
	safeResults := make([]*types.SearchResult, 0, len(entityResults))
	chunkMap := map[string]*types.Chunk{}
	for _, c := range chunks {
		chunkMap[c.ID] = c
	}
	for _, r := range entityResults {
		if graphTargetAllowsChunk(targets, chunkMap[r.ID]) {
			safeResults = append(safeResults, r)
		}
	}
	chatManage.SearchResult = append(chatManage.SearchResult, safeResults...)
	// remove duplicate results
	chatManage.SearchResult = removeDuplicateResults(chatManage.SearchResult)
	if len(chatManage.SearchResult) == 0 {
		logger.Infof(ctx, "No new search result, session_id: %s", chatManage.SessionID)
		return ErrSearchNothing
	}
	logger.Infof(
		ctx,
		"search entity result count: %d, session_id: %s",
		len(chatManage.SearchResult),
		chatManage.SessionID,
	)
	return next()
}

// maxEntityChunks bounds the chunks graph entity search adds to a turn.
const maxEntityChunks = 30

// filterSeenChunk filters seen chunks from the graph
func filterSeenChunk(ctx context.Context, graph *types.GraphData, searchResult []*types.SearchResult) []string {
	seen := map[string]bool{}
	for _, chunk := range searchResult {
		seen[chunk.ID] = true
	}
	logger.Infof(ctx, "filterSeenChunk: seen count: %d", len(seen))

	chunkIDs := []string{}
	for _, node := range graph.Node {
		for _, chunkID := range node.Chunks {
			// Every chunk of every matched node was fetched and passed on;
			// entity search runs on an empty result list, so nothing was
			// ever filtered as seen. Cap it.
			if len(chunkIDs) >= maxEntityChunks {
				break
			}
			if seen[chunkID] {
				continue
			}
			seen[chunkID] = true
			chunkIDs = append(chunkIDs, chunkID)
		}
	}
	logger.Infof(ctx, "filterSeenChunk: new chunkIDs count: %d", len(chunkIDs))
	return chunkIDs
}

// chunk2SearchResult converts a chunk to a search result.
//
// Graph hits come from entity lookups, not similarity search, so they have no
// retrieval score. They used to carry a fixed 1.0, which ranked them above
// every scored hit whenever rerank did not run. With 0 they fill the slots
// scored hits leave; when rerank runs, CompositeScore substitutes the model
// score for the missing retrieval score.
func chunk2SearchResult(chunk *types.Chunk, knowledge *types.Knowledge) *types.SearchResult {
	return &types.SearchResult{
		ID:                chunk.ID,
		Content:           chunk.Content,
		ContentRevision:   chunk.ContentRevision,
		KnowledgeID:       chunk.KnowledgeID,
		ChunkIndex:        chunk.ChunkIndex,
		KnowledgeTitle:    knowledge.Title,
		StartAt:           chunk.StartAt,
		EndAt:             chunk.EndAt,
		Seq:               chunk.ChunkIndex,
		Score:             0,
		MatchType:         types.MatchTypeGraph,
		Metadata:          knowledge.GetMetadata(),
		ChunkType:         string(chunk.ChunkType),
		ParentChunkID:     chunk.ParentChunkID,
		ImageInfo:         chunk.ImageInfo,
		KnowledgeFilename: knowledge.FileName,
		KnowledgeSource:   knowledge.Source,
		KnowledgeChannel:  knowledge.Channel,
		ChunkMetadata:     chunk.Metadata,
		KnowledgeBaseID:   knowledge.KnowledgeBaseID,
		SourceLocators:    chunk.SourceLocators,
	}
}

// Rows returned by globally addressed shared-KB queries still need exact target binding.
func graphTargetAllowsChunk(targets types.SearchTargets, chunk *types.Chunk) bool {
	if chunk == nil {
		return false
	}
	for _, target := range targets {
		if target == nil || target.TenantID == 0 || target.TenantID != chunk.TenantID || target.KnowledgeBaseID != chunk.KnowledgeBaseID {
			continue
		}
		if target.Type == types.SearchTargetTypeKnowledge {
			found := false
			for _, id := range target.KnowledgeIDs {
				if id == chunk.KnowledgeID {
					found = true
					break
				}
			}
			if !found {
				continue
			}
		} else if target.Type != types.SearchTargetTypeKnowledgeBase {
			continue
		}
		if len(target.TagIDs) > 0 {
			found := false
			for _, tag := range target.TagIDs {
				if tag == chunk.TagID {
					found = true
					break
				}
			}
			if !found {
				continue
			}
		}
		return true
	}
	return false
}
