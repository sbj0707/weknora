package azureaisearch

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"math"
	"strconv"
	"sync"

	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
)

type Repository struct {
	client     *Client
	index      string
	storeID    string
	tenantID   string
	dimensions int
	analyzer   string
	initMu     sync.Mutex
	ready      bool
}

var _ interfaces.RetrieveEngineRepository = (*Repository)(nil)
var _ interfaces.KnowledgeIndexMover = (*Repository)(nil)

func NewRepository(client *Client, store types.VectorStore) (*Repository, error) {
	if client == nil || store.TenantID == 0 {
		return nil, fmt.Errorf("Azure AI Search client and store tenant are required")
	}
	name, err := types.AzureAISearchIndexName(store.ID, store.IndexConfig)
	if err != nil {
		return nil, err
	}
	analyzer := store.IndexConfig.TextAnalyzer
	if analyzer == "" {
		analyzer = "standard.lucene"
	}
	return &Repository{client: client, index: name, storeID: store.ID, tenantID: strconv.FormatUint(store.TenantID, 10), dimensions: store.IndexConfig.VectorDimensions, analyzer: analyzer}, nil
}

func (*Repository) EngineType() types.RetrieverEngineType {
	return types.AzureAISearchRetrieverEngineType
}
func (*Repository) Support() []types.RetrieverType {
	return []types.RetrieverType{types.VectorRetrieverType, types.KeywordsRetrieverType}
}
func (r *Repository) VectorDimensions() int { return r.dimensions }
func (r *Repository) checkDimension(dim int) error {
	if dim != 0 && dim != r.dimensions {
		return fmt.Errorf("%w: got %d, expected %d", ErrDimension, dim, r.dimensions)
	}
	return nil
}
func (r *Repository) validateVector(vector []float32) error {
	if len(vector) != r.dimensions {
		return fmt.Errorf("%w: got %d, expected %d", ErrDimension, len(vector), r.dimensions)
	}
	var magnitude float64
	for _, v := range vector {
		if math.IsNaN(float64(v)) || math.IsInf(float64(v), 0) {
			return fmt.Errorf("Azure AI Search vector contains non-finite values")
		}
		magnitude += float64(v) * float64(v)
	}
	if magnitude == 0 {
		return fmt.Errorf("Azure AI Search cosine vector must be nonzero")
	}
	return nil
}

// Each generated question is its own source; all aliases retain the ChunkID.
func (r *Repository) documentID(sourceType int, sourceID string) string {
	canonical, _ := json.Marshal([]any{r.tenantID, r.storeID, sourceType, sourceID})
	digest := sha256.Sum256(canonical)
	return hex.EncodeToString(digest[:])
}

type document struct {
	WriteToken      string    `json:"write_token,omitempty"`
	ID              string    `json:"id"`
	TenantID        string    `json:"tenant_id"`
	StoreID         string    `json:"store_id"`
	Content         string    `json:"content"`
	Embedding       []float32 `json:"embedding,omitempty"`
	SourceID        string    `json:"source_id"`
	SourceType      int       `json:"source_type"`
	ChunkID         string    `json:"chunk_id"`
	KnowledgeID     string    `json:"knowledge_id"`
	KnowledgeBaseID string    `json:"knowledge_base_id"`
	KnowledgeType   string    `json:"knowledge_type"`
	TagID           string    `json:"tag_id"`
	IsEnabled       bool      `json:"is_enabled"`
	IsRecommended   bool      `json:"is_recommended"`
	Score           float64   `json:"@search.score,omitempty"`
}

// Empty knowledge type is the existing vector-query convention for documents.
func normalizedKnowledgeType(kind string) string {
	if kind == types.KnowledgeTypeFAQ {
		return kind
	}
	return types.KnowledgeBaseTypeDocument
}

func (r *Repository) EstimateStorageSize(_ context.Context, infos []*types.IndexInfo, params map[string]any) int64 {
	embeddings, _ := params["embedding"].(map[string][]float32)
	var size int64
	for _, info := range infos {
		if info != nil {
			size += int64(len(info.Content) + len(embeddings[info.SourceID])*4 + 512)
		}
	}
	return size
}
