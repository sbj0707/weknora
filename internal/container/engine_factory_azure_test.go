package container

import (
	"context"
	"testing"

	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"github.com/Tencent/WeKnora/internal/utils"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestAzureEngineFactory(t *testing.T) {
	utils.SetSSRFWhitelistFromRaw("example-search.search.windows.net")
	// Restore the suite's TestMain policy, rather than the environment fallback.
	defer utils.SetSSRFWhitelistFromRaw("127.0.0.1,::1,localhost")
	store := types.VectorStore{ID: "0193b8a0-1111-7000-8000-000000000001", TenantID: 42, EngineType: types.AzureAISearchRetrieverEngineType, ConnectionConfig: types.ConnectionConfig{Addr: "https://example-search.search.windows.net", APIKey: "secret"}, IndexConfig: types.IndexConfig{VectorDimensions: 1536}}
	engine, err := createEngineServiceFromStore(context.Background(), store, nil, nil, nil)
	require.NoError(t, err)
	assert.Equal(t, types.AzureAISearchRetrieverEngineType, engine.EngineType())
	assert.ElementsMatch(t, []types.RetrieverType{types.VectorRetrieverType, types.KeywordsRetrieverType}, engine.Support())
	fixed, ok := engine.(interfaces.FixedVectorDimensions)
	require.True(t, ok)
	assert.Equal(t, 1536, fixed.VectorDimensions())
	store.IndexConfig.VectorDimensions = 0
	_, err = createEngineServiceFromStore(context.Background(), store, nil, nil, nil)
	require.Error(t, err)
	store.ConnectionConfig.Addr = "https://example-search.search.windows.net.attacker.example"
	_, err = createEngineServiceFromStore(context.Background(), store, nil, nil, nil)
	require.Error(t, err)
}
