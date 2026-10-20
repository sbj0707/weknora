package types

import (
	"encoding/json"
	"strings"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestAzureAISearchConnectionValidation(t *testing.T) {
	valid := ConnectionConfig{Addr: "https://example-search.search.windows.net", APIKey: "admin-secret"}
	require.NoError(t, ValidateAzureAISearchConnection(valid))
	for _, addr := range []string{"http://example-search.search.windows.net", "https://example-search.search.windows.net.attacker.com", "https://example-search.search.windows.net:443", "https://user:pass@example-search.search.windows.net", "https://example-search.search.windows.net/indexes", "https://example-search.search.windows.net?api-key=secret", "https://example-search.search.windows.net#fragment", "https://example-search.search.windows.net?", "https://127.0.0.1", "https://a.b.search.windows.net"} {
		t.Run(addr, func(t *testing.T) { c := valid; c.Addr = addr; require.Error(t, ValidateAzureAISearchConnection(c)) })
	}
	c := valid
	c.APIKey = ""
	require.Error(t, ValidateAzureAISearchConnection(c))
	c.APIKey = RedactedSecretPlaceholder
	require.Error(t, ValidateAzureAISearchConnection(c))
	c = valid
	c.InsecureSkipVerify = true
	require.Error(t, ValidateAzureAISearchConnection(c))
	c = valid
	c.AuthMode = "managed_identity"
	c.ManagedIdentityClientID = "bad"
	require.Error(t, ValidateAzureAISearchConnection(c))
	c.ManagedIdentityClientID = "0193b8a0-1111-7000-8000-000000000001"
	require.NoError(t, ValidateAzureAISearchConnection(c))
	assert.Empty(t, c.NormalizeAzureAISearch().APIKey)
	c.AuthMode = "api_key"
	assert.Empty(t, c.NormalizeAzureAISearch().ManagedIdentityClientID)
	c.AuthMode = "default_credential"
	require.Error(t, ValidateAzureAISearchConnection(c))
}
func TestAzureAISearchIndexConfigurationAndMasking(t *testing.T) {
	cfg := IndexConfig{VectorDimensions: 1536, TextAnalyzer: "zh-Hans.microsoft"}
	name, err := AzureAISearchIndexName("0193b8a0-1111-7000-8000-000000000001", cfg)
	require.NoError(t, err)
	assert.Equal(t, "weknora-0193b8a0111170008000000000000001-v1", name)
	for _, prefix := range []string{"Uppercase", "under_score", "a--b", "-a", "a-", strings.Repeat("a", 93)} {
		c := cfg
		c.IndexName = prefix
		require.Error(t, ValidateAzureAISearchIndex(c))
	}
	cfg.IndexName = strings.Repeat("a", 92)
	name, err = AzureAISearchIndexName("0193b8a0-1111-7000-8000-000000000001", cfg)
	require.NoError(t, err)
	assert.Len(t, name, 128)
	cfg.VectorDimensions = 0
	require.Error(t, ValidateAzureAISearchIndex(cfg))
	cfg.VectorDimensions = 4097
	require.Error(t, ValidateAzureAISearchIndex(cfg))
	cfg.VectorDimensions = 1536
	cfg.TextAnalyzer = "arbitrary"
	require.Error(t, ValidateAzureAISearchIndex(cfg))
	cfg.TextAnalyzer = "standard.lucene"
	store := &VectorStore{ID: "0193b8a0-1111-7000-8000-000000000001", TenantID: 1, Name: "Azure", EngineType: AzureAISearchRetrieverEngineType, ConnectionConfig: ConnectionConfig{APIKey: "secret"}, IndexConfig: cfg}
	require.NoError(t, store.Validate())
	response := NewVectorStoreResponse(store, StoreSourceUser, false)
	assert.NotEmpty(t, response.PhysicalIndexName)
	encoded, err := json.Marshal(response)
	require.NoError(t, err)
	assert.NotContains(t, string(encoded), "secret")
	assert.Equal(t, "secret", store.ConnectionConfig.APIKey, "response masking must not mutate credentials")
}
