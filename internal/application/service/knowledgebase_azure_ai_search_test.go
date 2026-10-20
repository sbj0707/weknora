package service

import (
	"context"
	"strconv"
	"testing"

	"github.com/Tencent/WeKnora/internal/application/service/retriever"
	"github.com/Tencent/WeKnora/internal/models/embedding"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type azureDimensionEngine struct {
	interfaces.RetrieveEngineService
	dimension int
}

func (e azureDimensionEngine) VectorDimensions() int { return e.dimension }
func (e azureDimensionEngine) EngineType() types.RetrieverEngineType {
	return types.AzureAISearchRetrieverEngineType
}
func (e azureDimensionEngine) Support() []types.RetrieverType {
	return []types.RetrieverType{types.VectorRetrieverType, types.KeywordsRetrieverType}
}

type azureDimensionRegistry struct {
	interfaces.RetrieveEngineRegistry
	engine interfaces.RetrieveEngineService
	calls  int
}

func (r *azureDimensionRegistry) GetOrLoadByStoreID(_ context.Context, tenant uint64, _ string) (interfaces.RetrieveEngineService, error) {
	r.calls++
	return r.engine, nil
}

type azureDimensionModelService struct {
	interfaces.ModelService
	dimension int
	tenant    uint64
	calls     int
}

func (r *azureDimensionRegistry) GetRetrieveEngineService(types.RetrieverEngineType) (interfaces.RetrieveEngineService, error) {
	return r.engine, nil
}

func TestAzureKeywordOnlyLifecycleUsesStoreDimension(t *testing.T) {
	registry := &azureDimensionRegistry{engine: azureDimensionEngine{dimension: 1536}}
	engine, err := retriever.NewCompositeRetrieveEngine(registry, []types.RetrieverEngineParams{{RetrieverEngineType: types.AzureAISearchRetrieverEngineType, RetrieverType: types.KeywordsRetrieverType}})
	require.NoError(t, err)
	// No model service or model ID is needed for keyword-only copy/move/delete.
	dimension, err := retrievalDimension(context.Background(), engine, nil, "")
	require.NoError(t, err)
	assert.Equal(t, 1536, dimension)
	// Cleanup also works after the KB's old model has been removed.
	dimension, err = retrievalDimension(context.Background(), engine, nil, "deleted-model")
	require.NoError(t, err)
	assert.Equal(t, 1536, dimension)
}

func (m *azureDimensionModelService) GetEmbeddingModelForTenant(_ context.Context, _ string, tenant uint64) (embedding.Embedder, error) {
	m.tenant = tenant
	m.calls++
	return dimensionTestEmbedder{dimensions: m.dimension}, nil
}

type azureCountKnowledgeRepo struct {
	interfaces.KnowledgeRepository
	count int64
}

func (r azureCountKnowledgeRepo) CountKnowledgeByKnowledgeBaseID(context.Context, uint64, string) (int64, error) {
	return r.count, nil
}

func TestAzureKnowledgeBaseDimensionBinding(t *testing.T) {
	for _, dimension := range []int{0, 768, 1536} {
		t.Run(strconv.Itoa(dimension), func(t *testing.T) {
			registry := &azureDimensionRegistry{engine: azureDimensionEngine{dimension: 1536}}
			models := &azureDimensionModelService{dimension: dimension}
			svc := &knowledgeBaseService{retrieveEngine: registry, ownership: &fakeOwnership{owned: map[string]uint64{validKBStoreUUID: 42}}, modelService: models}
			storeID := validKBStoreUUID
			kb := &types.KnowledgeBase{ID: "kb", TenantID: 42, EmbeddingModelID: "embedding", VectorStoreID: &storeID, IndexingStrategy: types.IndexingStrategy{VectorEnabled: true}}
			// A shared KB must resolve the owner's model, not the requester's model.
			ctx := context.WithValue(context.Background(), types.TenantIDContextKey, uint64(7))
			err := svc.validateBoundVectorDimensions(ctx, kb)
			if dimension == 1536 {
				require.NoError(t, err)
			} else {
				require.Error(t, err)
			}
			assert.Equal(t, uint64(42), models.tenant)
			registry.calls = 0
			models.calls = 0
			kb.IndexingStrategy.VectorEnabled = false
			require.NoError(t, svc.validateBoundVectorDimensions(ctx, kb))
			assert.Zero(t, registry.calls)
			assert.Zero(t, models.calls)
		})
	}
}
func TestAzureKnowledgeBaseModelChangeRejectsBeforeMutation(t *testing.T) {
	storeID := validKBStoreUUID
	kb := &types.KnowledgeBase{ID: "kb", TenantID: 42, EmbeddingModelID: "original", VectorStoreID: &storeID, IndexingStrategy: types.IndexingStrategy{VectorEnabled: true}}
	repo := newFakeKBRepo()
	repo.rows[kb.ID] = kb
	svc := &knowledgeBaseService{repo: repo, kgRepo: azureCountKnowledgeRepo{count: 1}, retrieveEngine: &azureDimensionRegistry{engine: azureDimensionEngine{dimension: 1536}}, ownership: &fakeOwnership{owned: map[string]uint64{storeID: 42}}, modelService: &azureDimensionModelService{dimension: 768}}
	require.Error(t, svc.SetEmbeddingModel(context.Background(), kb.ID, "different-dimension"))
	assert.Equal(t, "original", kb.EmbeddingModelID)
	svc.modelService = &azureDimensionModelService{dimension: 1536}
	require.Error(t, svc.SetEmbeddingModel(context.Background(), kb.ID, "same-dimension-different-model"))
	assert.Equal(t, "original", kb.EmbeddingModelID)
	svc.kgRepo = azureCountKnowledgeRepo{}
	require.NoError(t, svc.SetEmbeddingModel(context.Background(), kb.ID, "new-model"))
	assert.Equal(t, "new-model", kb.EmbeddingModelID)
}
func TestAzureDimensionMetadataPreservesOwnership(t *testing.T) {
	registry := &azureDimensionRegistry{engine: azureDimensionEngine{dimension: 1536}}
	_, err := retriever.BoundVectorDimensions(context.Background(), registry, &fakeOwnership{owned: map[string]uint64{validKBStoreUUID: 42}}, 7, validKBStoreUUID)
	require.ErrorIs(t, err, retriever.ErrVectorStoreForbidden)
	assert.Zero(t, registry.calls)
}
func TestAzureStoreRejectsUnencryptedCredentialPersistence(t *testing.T) {
	t.Setenv("SYSTEM_AES_KEY", "")
	svc := &vectorStoreService{}
	store := &types.VectorStore{Name: "Azure", TenantID: 42, EngineType: types.AzureAISearchRetrieverEngineType, ConnectionConfig: types.ConnectionConfig{Addr: "https://example.search.windows.net", APIKey: "secret"}, IndexConfig: types.IndexConfig{VectorDimensions: 1536}}
	err := svc.CreateStore(context.Background(), store)
	require.ErrorContains(t, err, "SYSTEM_AES_KEY")
}
