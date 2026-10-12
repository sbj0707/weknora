package service

import (
	"context"
	"encoding/json"
	"fmt"
	"sort"
	"strings"

	"github.com/Tencent/WeKnora/internal/application/service/retriever"
	"github.com/Tencent/WeKnora/internal/logger"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
)

// GetKnowledgeIndexStatus is called only after the handler's document/KB read
// access check. All subsequent reads use the document owner's tenant and KB.
func (s *knowledgeService) GetKnowledgeIndexStatus(
	ctx context.Context, knowledge *types.Knowledge, spans []types.KnowledgeProcessingSpan,
) (*types.KnowledgeIndexStatus, error) {
	tenantID, _ := ctx.Value(types.TenantIDContextKey).(uint64)
	if knowledge == nil || tenantID == 0 || tenantID != knowledge.TenantID {
		return nil, fmt.Errorf("invalid document index scope")
	}
	kb, err := s.kbService.GetKnowledgeBaseByIDOnly(ctx, knowledge.KnowledgeBaseID)
	if err != nil || kb == nil || kb.TenantID != knowledge.TenantID || kb.ID != knowledge.KnowledgeBaseID {
		return nil, fmt.Errorf("document index knowledge base unavailable")
	}
	overrides, err := knowledge.ProcessOverrides()
	if err != nil {
		return nil, err
	}
	eff := ResolveProcessConfig(kb, overrides)
	rows := []types.KnowledgeIndexStatusRow{
		{Kind: "vector", Enabled: kb.IndexingStrategy.VectorEnabled},
		{Kind: "keywords", Enabled: kb.IndexingStrategy.KeywordEnabled},
		{Kind: "graph", Enabled: eff.GraphEnabled},
		{Kind: "wiki", Enabled: kb.IsWikiEnabled()},
		{Kind: "questions", Enabled: eff.QuestionGenerationConfig.Enabled && kb.NeedsEmbeddingModel()},
	}
	statistics := s.knowledgeRetrievalCounts(ctx, knowledge, kb)
	if counts := statistics[types.VectorRetrieverType]; counts != nil {
		rows[0].Count = indexCount(counts.Chunks)
	}
	if counts := statistics[types.KeywordsRetrieverType]; counts != nil {
		rows[1].Count = indexCount(counts.Chunks)
	}
	if counter, ok := s.graphEngine.(interfaces.KnowledgeGraphCounter); ok {
		counts, err := counter.CountKnowledgeGraph(ctx, types.NameSpace{KnowledgeBase: kb.ID, Knowledge: knowledge.ID})
		if err == nil && counts != nil {
			rows[2].Count = indexCount(counts.Entities)
			rows[2].RelationCount = indexCount(counts.Relations)
		} else if err != nil {
			logger.Warnf(ctx, "document graph statistics unavailable: %v", err)
		}
	}
	if s.wikiRepo != nil {
		slugs, err := s.wikiRepo.ListSlugsBySourceRef(ctx, kb.ID, knowledge.ID)
		if err == nil {
			sort.Strings(slugs)
			rows[3].Count = indexCount(int64(len(slugs)))
			// Keep the payload bounded; total count includes every related page.
			preview := slugs[:min(len(slugs), 50)]
			for _, slug := range preview {
				rows[3].Pages = append(rows[3].Pages, types.WikiIndexPage{Slug: slug, Title: slug})
			}
			if len(preview) > 0 {
				if pages, err := s.wikiRepo.ListBySlugs(ctx, kb.ID, preview); err == nil {
					for i := range rows[3].Pages {
						if page := pages[rows[3].Pages[i].Slug]; page != nil {
							rows[3].Pages[i].Title = page.Title
						}
					}
				}
			}
		} else {
			logger.Warnf(ctx, "document wiki statistics unavailable: %v", err)
		}
	}
	questionsStale := false
	if reader, ok := s.chunkRepo.(interfaces.KnowledgeIndexChunkReader); ok {
		chunks, err := reader.ListIndexStatusChunks(ctx, knowledge.TenantID, knowledge.ID)
		if err == nil {
			var count int64
			valid := true
			for _, chunk := range chunks {
				if chunk == nil || chunk.KnowledgeID != knowledge.ID || chunk.KnowledgeBaseID != kb.ID ||
					chunk.TenantID != knowledge.TenantID {
					valid = false
					break
				}
				if len(chunk.Metadata) == 0 {
					continue
				}
				var metadata types.DocumentChunkMetadata
				if err := json.Unmarshal(chunk.Metadata, &metadata); err != nil {
					valid = false
					break
				}
				count += int64(len(metadata.GeneratedQuestions))
				for _, question := range metadata.GeneratedQuestions {
					if !metadata.IsQuestionCurrent(question, chunk.ContentRevision) {
						questionsStale = true
					}
				}
			}
			if valid {
				rows[4].Count = indexCount(count)
			}
		}
	}
	// A generated question is searchable only after every configured retrieval
	// path has its alias. Missing statistics remain nil rather than a false zero.
	for _, pair := range []struct {
		enabled bool
		kind    types.RetrieverType
	}{
		{rows[0].Enabled, types.VectorRetrieverType}, {rows[1].Enabled, types.KeywordsRetrieverType},
	} {
		if !pair.enabled {
			continue
		}
		counts := statistics[pair.kind]
		if counts == nil {
			rows[4].IndexedCount = nil
			break
		}
		if rows[4].IndexedCount == nil {
			rows[4].IndexedCount = indexCount(counts.Questions)
		} else {
			*rows[4].IndexedCount = min(*rows[4].IndexedCount, counts.Questions)
		}
	}
	modelID := knowledge.EmbeddingModelID
	for _, span := range spans {
		if span.Name == types.StageEmbedding {
			if id, ok := span.Input["model_id"].(string); ok && id != "" {
				modelID = id
			}
		}
	}
	rows[0].ModelID = modelID
	if modelID != "" && s.modelService != nil {
		if model, err := s.modelService.GetModelByID(ctx, modelID); err == nil && model != nil {
			rows[0].ModelName = model.DisplayName
			if rows[0].ModelName == "" {
				rows[0].ModelName = model.Name
			}
		}
	}
	report := &types.KnowledgeIndexStatus{KnowledgeID: knowledge.ID, Indexes: rows,
		Refreshing: indexBuildInFlight(knowledge.ParseStatus)}
	for i := range report.Indexes {
		row := &report.Indexes[i]
		resolveDocumentIndexState(row, knowledge.ParseStatus, spans)
		if row.Kind == "questions" && questionsStale && row.Status == "completed" {
			row.Status, row.Reason = "stale", "content_changed"
		}
		if row.LastBuiltAt != nil && (report.LastBuiltAt == nil || row.LastBuiltAt.After(*report.LastBuiltAt)) {
			report.LastBuiltAt = row.LastBuiltAt
		}
	}
	for _, span := range spans {
		report.Attempt = max(report.Attempt, span.Attempt)
	}
	return report, nil
}

func (s *knowledgeService) knowledgeRetrievalCounts(
	ctx context.Context, knowledge *types.Knowledge, kb *types.KnowledgeBase,
) map[types.RetrieverType]*types.KnowledgeRetrievalIndexCounts {
	counts := make(map[types.RetrieverType]*types.KnowledgeRetrievalIndexCounts)
	if s.retrieveEngine == nil {
		return counts
	}
	var engines []types.RetrieverEngineParams
	if kb.VectorStoreID == nil || *kb.VectorStoreID == "" {
		if s.tenantRepo == nil {
			return counts
		}
		tenant, err := s.tenantRepo.GetTenantByID(ctx, knowledge.TenantID)
		if err != nil || tenant == nil || tenant.ID != knowledge.TenantID {
			return counts
		}
		engines = tenant.GetEffectiveEngines()
	}
	engine, err := retriever.CreateRetrieveEngineFromPayload(ctx, s.retrieveEngine, s.ownership,
		knowledge.TenantID, engines, kb.VectorStoreID)
	if err != nil {
		logger.Warnf(ctx, "document retrieval statistics unavailable: %v", err)
		return counts
	}
	for _, kind := range []types.RetrieverType{types.VectorRetrieverType, types.KeywordsRetrieverType} {
		current, err := engine.CountKnowledgeIndices(ctx, kb.ID, knowledge.ID, kind)
		if err == nil {
			counts[kind] = current
		} else {
			logger.Warnf(ctx, "document %s statistics unavailable: %v", kind, err)
		}
	}
	return counts
}

func indexCount(n int64) *int64 { return &n }

func indexBuildInFlight(status string) bool {
	return status == types.ParseStatusPending || status == types.ParseStatusProcessing || status == types.ParseStatusFinalizing
}

// resolveDocumentIndexState uses the latest attempt and latest retry of each
// named span. A done fan-out container does not mean its children succeeded.
func resolveDocumentIndexState(row *types.KnowledgeIndexStatusRow, parseStatus string, spans []types.KnowledgeProcessingSpan) {
	latest := make(map[string]types.KnowledgeProcessingSpan)
	for _, span := range spans {
		old, exists := latest[span.Name]
		if !exists || span.UpdatedAt.After(old.UpdatedAt) || (span.UpdatedAt.Equal(old.UpdatedAt) && span.ID > old.ID) {
			latest[span.Name] = span
		}
	}
	post := latest[types.StagePostProcess]
	var selected []types.KnowledgeProcessingSpan
	for name, span := range latest {
		match := false
		switch row.Kind {
		case "vector", "keywords":
			match = name == types.StageEmbedding || name == types.StageMultimodal
		case "graph":
			match = strings.HasPrefix(name, "postprocess.graph.chunk[")
		case "wiki":
			match = name == "postprocess.wiki"
		case "questions":
			match = strings.HasPrefix(name, "postprocess.question.batch[")
			if name == "postprocess.question" {
				_, isGroup := span.Input["batch_count"]
				match = !isGroup
			}
		}
		if match {
			selected = append(selected, span)
		}
	}
	sort.Slice(selected, func(i, j int) bool { return selected[i].Name < selected[j].Name })
	var done, failed, active, cancelled, skipped int
	var expected int64
	configKey := row.Kind + "_enabled"
	if row.Kind == "keywords" {
		configKey = "keyword_enabled"
	}
	if row.Kind == "questions" {
		configKey = "question_enabled"
	}
	configSource := post.Input
	if row.Kind == "vector" || row.Kind == "keywords" {
		configSource = latest[types.StageEmbedding].Input
	}
	builtEnabled, configKnown := configSource[configKey].(bool)
	for _, span := range selected {
		switch span.Status {
		case types.SpanStatusDone:
			done++
			if row.LastBuiltAt == nil || (span.FinishedAt != nil && span.FinishedAt.After(*row.LastBuiltAt)) {
				row.LastBuiltAt = span.FinishedAt
			}
			if span.Name == types.StageEmbedding {
				expected = indexNumber(span.Output["vectors_written"])
			}
			if indexNumber(span.Output["llm_failed"]) > 0 || indexNumber(span.Output["pages_dropped"]) > 0 {
				failed++
			}
		case types.SpanStatusFailed:
			failed++
			if row.ErrorMessage == "" {
				row.ErrorMessage = span.ErrorMessage
			}
		case types.SpanStatusPending, types.SpanStatusRunning:
			active++
		case types.SpanStatusCancelled:
			cancelled++
		case types.SpanStatusSkipped:
			skipped++
		}
	}
	plannedKey := "planned_" + row.Kind + "_count"
	enqueuedKey := "enqueued_" + row.Kind + "_count"
	if row.Kind == "questions" {
		plannedKey, enqueuedKey = "planned_question_count", "enqueued_question_count"
	}
	planned := indexNumber(post.Output[plannedKey])
	enqueued := indexNumber(post.Output[enqueuedKey])
	if row.Kind == "wiki" {
		if enqueuedWiki, _ := post.Output["enqueued_wiki"].(bool); enqueuedWiki {
			enqueued = 1
		}
	}
	row.Status = "unknown"
	switch {
	case !row.Enabled && (row.Count == nil || *row.Count == 0):
		row.Status = "disabled"
	case active > 0 && indexBuildInFlight(parseStatus):
		row.Status = "building"
	case failed > 0 || (planned > enqueued && !indexBuildInFlight(parseStatus)):
		row.Status = "failed"
		if done > 0 || (row.Count != nil && *row.Count > 0) {
			row.Status = "partial_failed"
		}
		if planned > enqueued {
			row.Reason = "enqueue_failed"
		}
	case cancelled > 0 || (active > 0 && parseStatus == types.ParseStatusCancelled):
		row.Status = "cancelled"
		if done > 0 {
			row.Status = "partial_failed"
		}
	case active > 0:
		row.Reason = "history_incomplete"
	case done > 0 && (enqueued == 0 || int64(done) >= enqueued):
		row.Status = "completed"
	case done > 0 && enqueued > int64(done) && indexBuildInFlight(parseStatus):
		row.Status = "building"
	case indexBuildInFlight(parseStatus) && row.Enabled:
		row.Status = "waiting"
	case row.Enabled && row.Count != nil && *row.Count == 0:
		row.Status, row.Reason = "stale", "not_built"
	default:
		row.Reason = "history_missing"
	}
	if row.Status == "completed" && row.Count != nil && expected > 0 && *row.Count < expected {
		row.Status, row.Reason = "stale", "missing_index"
	}
	if row.Kind == "questions" && row.Status == "completed" && row.Count != nil &&
		row.IndexedCount != nil && *row.IndexedCount < *row.Count {
		row.Status, row.Reason = "partial_failed", "missing_index"
	}
	if configKnown && builtEnabled != row.Enabled && row.Status != "building" && row.Status != "waiting" {
		row.Status, row.Reason = "stale", "configuration_changed"
	}
	if skipped > 0 && done == 0 && row.Enabled && !indexBuildInFlight(parseStatus) {
		row.Status, row.Reason = "stale", "not_built"
	}
	if row.Count == nil && row.Status != "disabled" && row.Status != "building" && row.Status != "waiting" {
		row.Status, row.Reason = "unknown", "statistics_unavailable"
	}
	if row.Kind == "questions" && row.Status == "completed" && row.IndexedCount == nil {
		row.Status, row.Reason = "unknown", "statistics_unavailable"
	}
	if (row.Kind == "vector" || row.Kind == "keywords") && row.Status == "completed" {
		embedding := latest[types.StageEmbedding]
		if embedding.Status != types.SpanStatusDone && (row.Count == nil || *row.Count == 0) {
			row.Status, row.Reason = "stale", "not_built"
		}
	}
}

func indexNumber(value any) int64 {
	switch v := value.(type) {
	case int:
		return int64(v)
	case int64:
		return v
	case float64:
		return int64(v)
	case json.Number:
		n, _ := v.Int64()
		return n
	default:
		return 0
	}
}

// Interface assertions keep the optional status endpoint wired to production.
var _ interfaces.KnowledgeIndexStatusService = (*knowledgeService)(nil)
