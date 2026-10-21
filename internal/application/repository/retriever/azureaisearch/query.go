package azureaisearch

import (
	"context"
	"encoding/json"
	"fmt"
	"math"
	"net/http"
	"slices"
	"strings"

	"github.com/Tencent/WeKnora/internal/types"
)

const resultFields = "id,tenant_id,store_id,content,source_id,source_type,chunk_id,knowledge_id,knowledge_base_id,knowledge_type,tag_id,is_enabled,is_recommended"

func quote(value string) string { return "'" + strings.ReplaceAll(value, "'", "''") + "'" }
func inFilter(field string, values []string) string {
	if len(values) == 0 {
		return ""
	}
	for _, separator := range []string{"|", ",", "~", "^"} {
		if !slices.ContainsFunc(values, func(v string) bool { return strings.Contains(v, separator) }) {
			return "search.in(" + field + ", " + quote(strings.Join(values, separator)) + ", " + quote(separator) + ")"
		}
	}
	terms := make([]string, 0, len(values))
	for _, v := range values {
		terms = append(terms, field+" eq "+quote(v))
	}
	return "(" + strings.Join(terms, " or ") + ")"
}
func and(filters ...string) string {
	result := make([]string, 0, len(filters))
	for _, f := range filters {
		if f != "" {
			result = append(result, "("+f+")")
		}
	}
	return strings.Join(result, " and ")
}
func (r *Repository) scope() string {
	return and("tenant_id eq "+quote(r.tenantID), "store_id eq "+quote(r.storeID))
}
func (r *Repository) retrievalFilter(p types.RetrieveParams) string {
	parts := []string{r.scope(), "is_enabled eq true", inFilter("knowledge_base_id", p.KnowledgeBaseIDs), inFilter("knowledge_id", p.KnowledgeIDs), inFilter("tag_id", p.TagIDs)}
	if len(p.ExcludeKnowledgeIDs) > 0 {
		parts = append(parts, "not ("+inFilter("knowledge_id", p.ExcludeKnowledgeIDs)+")")
	}
	if len(p.ExcludeChunkIDs) > 0 {
		parts = append(parts, "not ("+inFilter("chunk_id", p.ExcludeChunkIDs)+")")
	}
	if p.RetrieverType == types.VectorRetrieverType || p.KnowledgeType != "" {
		parts = append(parts, "knowledge_type eq "+quote(normalizedKnowledgeType(p.KnowledgeType)))
	}
	return and(parts...)
}
func literalSearch(text string) string {
	var out strings.Builder
	for _, ch := range text {
		if strings.ContainsRune(`+-|"()'*?\:/`, ch) {
			out.WriteRune('\\')
		}
		out.WriteRune(ch)
	}
	return out.String()
}

type searchResponse struct {
	Value []document     `json:"value"`
	Next  map[string]any `json:"@search.nextPageParameters"`
}

func (r *Repository) search(ctx context.Context, body map[string]any) (searchResponse, error) {
	var result searchResponse
	data, err := r.client.request(ctx, http.MethodPost, "/indexes/"+r.index+"/docs/search", nil, body, nil)
	if err != nil {
		return result, err
	}
	if err := json.Unmarshal(data, &result); err != nil {
		return result, fmt.Errorf("decode Azure AI Search results: %w", err)
	}
	return result, nil
}

func (r *Repository) Retrieve(ctx context.Context, p types.RetrieveParams) ([]*types.RetrieveResult, error) {
	if p.RetrieverType != types.VectorRetrieverType && p.RetrieverType != types.KeywordsRetrieverType {
		return nil, fmt.Errorf("unsupported Azure AI Search retriever type %q", p.RetrieverType)
	}
	if len(p.KnowledgeBaseIDs) == 0 || slices.Contains(p.KnowledgeBaseIDs, "") {
		return nil, fmt.Errorf("Azure AI Search retrieval requires an authorized knowledge base scope")
	}
	if p.TopK < 1 || p.TopK > 1000 {
		return nil, fmt.Errorf("Azure AI Search TopK must be between 1 and 1000")
	}
	if math.IsNaN(p.Threshold) || math.IsInf(p.Threshold, 0) || p.Threshold < 0 || p.RetrieverType == types.VectorRetrieverType && p.Threshold > 1 {
		return nil, fmt.Errorf("invalid Azure AI Search threshold")
	}
	result := &types.RetrieveResult{RetrieverEngineType: r.EngineType(), RetrieverType: p.RetrieverType, Results: []*types.IndexWithScore{}}
	body := map[string]any{"filter": r.retrievalFilter(p), "select": resultFields, "top": p.TopK}
	match := types.MatchTypeKeywords
	if p.RetrieverType == types.VectorRetrieverType {
		if err := r.validateVector(p.Embedding); err != nil {
			return nil, err
		}
		body["vectorFilterMode"] = "preFilter"
		body["vectorQueries"] = []any{map[string]any{"kind": "vector", "vector": p.Embedding, "fields": "embedding", "k": p.TopK}}
		match = types.MatchTypeEmbedding
	} else {
		if strings.TrimSpace(p.Query) == "" {
			return []*types.RetrieveResult{result}, nil
		}
		body["search"] = literalSearch(p.Query)
		body["searchFields"] = "content"
		body["queryType"] = "simple"
		body["searchMode"] = "any"
	}
	exists, err := r.ensureReady(ctx, false)
	if err != nil {
		return nil, err
	}
	if !exists {
		return []*types.RetrieveResult{result}, nil
	}
	// Search may page early because of response size. Continue using the fixed
	// original query and only Azure's numeric skip continuation, never a URL.
	for {
		page, err := r.search(ctx, body)
		if err != nil {
			return nil, err
		}
		for _, d := range page.Value {
			if d.TenantID != r.tenantID || d.StoreID != r.storeID || !d.IsEnabled || !slices.Contains(p.KnowledgeBaseIDs, d.KnowledgeBaseID) {
				return nil, fmt.Errorf("Azure AI Search returned a record outside the requested scope")
			}
			score := d.Score
			if math.IsNaN(score) || math.IsInf(score, 0) || score < 0 {
				return nil, fmt.Errorf("Azure AI Search returned an invalid score")
			}
			if p.RetrieverType == types.VectorRetrieverType {
				if score <= 0 {
					return nil, fmt.Errorf("Azure AI Search returned an invalid cosine score")
				}
				score = max(0, min(1, 2-1/score))
			}
			if score+1e-12 < p.Threshold {
				continue
			}
			result.Results = append(result.Results, &types.IndexWithScore{ID: d.SourceID, Content: d.Content, SourceID: d.SourceID, SourceType: types.SourceType(d.SourceType), ChunkID: d.ChunkID, KnowledgeID: d.KnowledgeID, KnowledgeBaseID: d.KnowledgeBaseID, TagID: d.TagID, Score: score, MatchType: match, IsEnabled: d.IsEnabled})
		}
		if len(page.Next) == 0 {
			break
		}
		// nextPageParameters is generated by Azure; accept only monotonic paging.
		nextSkip, ok := page.Next["skip"].(float64)
		previous, _ := body["skip"].(int)
		if !ok || nextSkip != math.Trunc(nextSkip) || nextSkip <= float64(previous) || nextSkip >= float64(p.TopK) {
			return nil, fmt.Errorf("invalid Azure AI Search continuation")
		}
		body["skip"] = int(nextSkip)
		body["top"] = p.TopK - int(nextSkip)
	}
	return []*types.RetrieveResult{result}, nil
}

// scan visits the whole scoped set using a totally ordered, filterable key.
// Even an underfilled page is followed by a range query until an empty page.
// This also consumes server-side partial pages without skip-window limits.
func (r *Repository) scan(ctx context.Context, filter string, withVectors bool, visit func([]document) error) error {
	exists, err := r.ensureReady(ctx, false)
	if err != nil || !exists {
		return err
	}
	selectFields := resultFields
	if withVectors {
		selectFields += ",embedding"
	}
	cursor := ""
	for {
		f := and(r.scope(), filter)
		if cursor != "" {
			f = and(f, "id gt "+quote(cursor))
		}
		page, err := r.search(ctx, map[string]any{"search": "*", "filter": f, "orderby": "id asc", "select": selectFields, "top": 200})
		if err != nil {
			return err
		}
		if len(page.Value) == 0 {
			return nil
		}
		for _, d := range page.Value {
			if d.TenantID != r.tenantID || d.StoreID != r.storeID || d.ID <= cursor {
				return fmt.Errorf("invalid Azure AI Search scan scope or cursor")
			}
			cursor = d.ID
		}
		if err := visit(page.Value); err != nil {
			return err
		}
	}
}
