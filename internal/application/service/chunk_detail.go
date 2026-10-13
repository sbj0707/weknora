package service

import (
	"context"
	"fmt"
	"slices"

	"github.com/Tencent/WeKnora/internal/application/service/retriever"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
)

func (s *knowledgeService) GetChunkDetail(ctx context.Context, knowledge *types.Knowledge, chunkID string) (*types.ChunkDetail, error) {
	tenantID, _ := ctx.Value(types.TenantIDContextKey).(uint64)
	if knowledge == nil || tenantID == 0 || tenantID != knowledge.TenantID {
		return nil, fmt.Errorf("invalid chunk detail scope")
	}
	chunk, err := s.chunkRepo.GetChunkByID(ctx, tenantID, chunkID)
	if err != nil {
		return nil, err
	}
	if chunk == nil || chunk.TenantID != tenantID || chunk.KnowledgeID != knowledge.ID || chunk.KnowledgeBaseID != knowledge.KnowledgeBaseID {
		return nil, ErrChunkNotFound
	}
	kb, err := s.kbService.GetKnowledgeBaseByIDOnly(ctx, knowledge.KnowledgeBaseID)
	if err != nil || kb == nil || kb.TenantID != tenantID || kb.ID != knowledge.KnowledgeBaseID {
		return nil, fmt.Errorf("chunk detail knowledge base unavailable")
	}
	overrides, err := knowledge.ProcessOverrides()
	if err != nil {
		return nil, err
	}
	eff := ResolveProcessConfig(kb, overrides)
	detail := &types.ChunkDetail{Chunk: chunk, SourceContent: chunk.SourceContent, ContextHeader: chunk.ContextHeader, KnowledgeTitle: knowledge.Title, ModelID: knowledge.EmbeddingModelID,
		Refreshing: chunk.IndexStatus == "processing" || indexBuildInFlight(knowledge.ParseStatus),
		Indexes: []types.ChunkIndexDetail{
			{Kind: "vector", Enabled: kb.IndexingStrategy.VectorEnabled},
			{Kind: "keywords", Enabled: kb.IndexingStrategy.KeywordEnabled},
			{Kind: "graph", Enabled: eff.GraphEnabled},
			{Kind: "wiki", Enabled: kb.IsWikiEnabled()},
			{Kind: "questions", Enabled: eff.QuestionGenerationConfig.Enabled && kb.NeedsEmbeddingModel()},
		},
	}
	if children, err := s.chunkRepo.ListChunkByParentID(ctx, tenantID, chunk.ID); err == nil {
		detail.ChildrenAvailable = true
		detail.Children = make([]*types.Chunk, 0, len(children))
		for _, child := range children {
			if child == nil || child.TenantID != tenantID || child.KnowledgeID != knowledge.ID ||
				child.KnowledgeBaseID != kb.ID || child.ParentChunkID != chunk.ID {
				return nil, fmt.Errorf("invalid related chunk scope")
			}
			detail.Children = append(detail.Children, child)
		}
	}
	if detail.ModelID != "" && s.modelService != nil {
		if model, err := s.modelService.GetModelByID(ctx, detail.ModelID); err == nil && model != nil {
			detail.ModelName = model.DisplayName
			if detail.ModelName == "" {
				detail.ModelName = model.Name
			}
		}
	}
	engine, engineErr := s.chunkDetailEngine(ctx, knowledge, kb)
	if engineErr == nil {
		for i, kind := range []types.RetrieverType{types.VectorRetrieverType, types.KeywordsRetrieverType} {
			detail.Indexes[i].Stores = engine.ChunkIndexStores(kind)
			records, err := engine.ListChunkIndexRecords(ctx, kb.ID, knowledge.ID, chunk.ID, kind)
			if err == nil {
				detail.Indexes[i].Available = true
				detail.Indexes[i].Records = records
			}
		}
	}
	if reader, ok := s.graphEngine.(interfaces.ChunkGraphReader); ok {
		if graph, err := reader.GetChunkGraph(ctx, types.NameSpace{KnowledgeBase: kb.ID, Knowledge: knowledge.ID}, chunk.ID); err == nil && graph != nil {
			detail.Indexes[2].Available, detail.Indexes[2].Graph = true, graph
		}
	}
	if reader, ok := s.wikiRepo.(interfaces.ChunkWikiReader); ok {
		if pages, err := reader.ListChunkWikiPages(ctx, tenantID, kb.ID, knowledge.ID, chunk.ID); err == nil {
			detail.Indexes[3].Available = true
			for _, page := range pages {
				if page == nil || page.TenantID != tenantID || page.KnowledgeBaseID != kb.ID {
					return nil, fmt.Errorf("invalid chunk wiki scope")
				}
				scope := "document"
				if slices.Contains(page.ChunkRefs, chunk.ID) {
					scope = "chunk"
				}
				detail.Indexes[3].Pages = append(detail.Indexes[3].Pages, types.ChunkWikiPage{Page: page, Scope: scope})
			}
		}
	}
	metadata, err := chunk.DocumentMetadata()
	if err == nil {
		detail.Indexes[4].Available = true
		if metadata != nil {
			for _, question := range metadata.GeneratedQuestions {
				sourceID := types.GeneratedQuestionSourceID(chunk.ID, question.ID)
				var vectorIndexed, keywordIndexed *bool
				if detail.Indexes[0].Enabled {
					vectorIndexed = chunkSourceIndexed(detail.Indexes[0], sourceID)
				}
				if detail.Indexes[1].Enabled {
					keywordIndexed = chunkSourceIndexed(detail.Indexes[1], sourceID)
				}
				detail.Indexes[4].Questions = append(detail.Indexes[4].Questions, types.ChunkQuestionDetail{
					ID: question.ID, Question: question.Question, SourceID: sourceID, Current: metadata.IsQuestionCurrent(question, chunk.ContentRevision),
					VectorIndexed: vectorIndexed, KeywordIndexed: keywordIndexed,
				})
			}
		}
	}
	for i := range detail.Indexes {
		resolveChunkIndexDetail(&detail.Indexes[i], chunk, detail.Refreshing)
	}
	questions := &detail.Indexes[4]
	if questions.Status == "completed" && ((detail.Indexes[0].Enabled && !detail.Indexes[0].Available) ||
		(detail.Indexes[1].Enabled && !detail.Indexes[1].Available)) {
		questions.Status, questions.Reason = "unknown", "statistics_unavailable"
	}
	return detail, nil
}

func (s *knowledgeService) chunkDetailEngine(ctx context.Context, knowledge *types.Knowledge, kb *types.KnowledgeBase) (*retriever.CompositeRetrieveEngine, error) {
	if s.retrieveEngine == nil {
		return nil, interfaces.ErrIndexStatisticsUnsupported
	}
	var engines []types.RetrieverEngineParams
	if kb.VectorStoreID == nil || *kb.VectorStoreID == "" {
		if s.tenantRepo == nil {
			return nil, interfaces.ErrIndexStatisticsUnsupported
		}
		tenant, err := s.tenantRepo.GetTenantByID(ctx, knowledge.TenantID)
		if err != nil || tenant == nil || tenant.ID != knowledge.TenantID {
			return nil, fmt.Errorf("invalid chunk storage owner")
		}
		engines = tenant.GetEffectiveEngines()
	}
	return retriever.CreateRetrieveEngineFromPayload(ctx, s.retrieveEngine, s.ownership, knowledge.TenantID, engines, kb.VectorStoreID)
}

// Each configured replicated store must contain the source, not just one copy.
func chunkSourceIndexed(index types.ChunkIndexDetail, sourceID string) *bool {
	if !index.Available {
		return nil
	}
	stores := map[types.RetrieverEngineType]bool{}
	for _, store := range index.Stores {
		stores[store] = false
	}
	for _, record := range index.Records {
		if _, exists := stores[record.StoreType]; !exists {
			stores[record.StoreType] = false
		}
		if record.SourceID == sourceID {
			stores[record.StoreType] = true
		}
	}
	indexed := len(stores) > 0
	for _, found := range stores {
		indexed = indexed && found
	}
	return &indexed
}

func resolveChunkIndexDetail(index *types.ChunkIndexDetail, chunk *types.Chunk, refreshing bool) {
	hasData := len(index.Records) > 0 || len(index.Pages) > 0 || len(index.Questions) > 0 || (index.Graph != nil && len(index.Graph.Nodes) > 0)
	switch {
	case !index.Available:
		index.Status, index.Reason = "unknown", "statistics_unavailable"
	case hasData:
		index.Status = "completed"
	case !index.Enabled:
		index.Status = "disabled"
	case refreshing:
		index.Status = "waiting"
	default:
		index.Status, index.Reason = "stale", "not_built"
	}
	if (index.Kind == "vector" || index.Kind == "keywords") && index.Enabled {
		if chunk.ChunkType == types.ChunkTypeParentText {
			index.Status, index.Reason = "disabled", "context_only"
			return
		}
		if chunk.IndexStatus == "processing" {
			index.Status = "building"
		} else if chunk.IndexStatus == "failed" {
			index.Status = "failed"
		}
		primary := chunkSourceIndexed(*index, chunk.ID)
		if index.Available && chunk.IndexStatus == "ready" && primary != nil && !*primary {
			index.Status, index.Reason = "stale", "missing_index"
		}
	}
	if index.Kind == "questions" && index.Status == "completed" {
		for _, question := range index.Questions {
			if !question.Current {
				index.Status, index.Reason = "stale", "content_changed"
				return
			}
			for _, indexed := range []*bool{question.VectorIndexed, question.KeywordIndexed} {
				if indexed != nil && !*indexed {
					index.Status, index.Reason = "partial_failed", "missing_index"
				}
			}
		}
	}
}

var _ interfaces.ChunkDetailService = (*knowledgeService)(nil)
