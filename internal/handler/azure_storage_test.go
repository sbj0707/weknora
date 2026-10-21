package handler

import (
	"context"
	"encoding/json"
	"github.com/Tencent/WeKnora/internal/storageallowlist"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"github.com/gin-gonic/gin"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestAzureStorageStatusAndTypeAvailability(t *testing.T) {
	gin.SetMode(gin.TestMode)
	t.Setenv(storageallowlist.AllowListEnv, "azure")
	t.Setenv("STORAGE_TYPE", "local")
	recorder := httptest.NewRecorder()
	c, _ := gin.CreateTestContext(recorder)
	c.Request = httptest.NewRequest(http.MethodGet, "/api/v1/system/storage-engine-status", nil)
	c.Set(types.TenantInfoContextKey.String(), &types.Tenant{ID: 42})
	handler := &SystemHandler{storageBackendRepo: &fakeStorageBackendRepo{backends: []*types.StorageBackend{{Provider: "azure", Status: types.StorageBackendStatusActive}}}}
	handler.GetStorageEngineStatus(c)
	var response struct {
		Data GetStorageEngineStatusResponse `json:"data"`
	}
	if err := json.Unmarshal(recorder.Body.Bytes(), &response); err != nil {
		t.Fatal(err)
	}
	found := false
	for _, engine := range response.Data.Engines {
		if engine.Name == "azure" {
			found = engine.Allowed && engine.Available
		}
	}
	if !found {
		t.Fatal("configured Azure instance not available for KB selection")
	}
	if len(response.Data.AllowedProviders) != 1 || response.Data.AllowedProviders[0] != "azure" {
		t.Fatal("Azure allowlist not respected")
	}
}

type azureSavedConfigRepo struct {
	interfaces.StorageBackendRepository
	backend *types.StorageBackend
}

func (r azureSavedConfigRepo) GetByID(_ context.Context, tenant uint64, id string) (*types.StorageBackend, error) {
	if tenant == r.backend.TenantID && id == r.backend.ID {
		return r.backend, nil
	}
	return nil, nil
}
func TestAzureStoredCredentialCannotBeTestedOnAnotherEndpoint(t *testing.T) {
	gin.SetMode(gin.TestMode)
	repo := azureSavedConfigRepo{backend: &types.StorageBackend{ID: "instance", TenantID: 42, Provider: "azure", Config: types.StorageBackendConfigFromAzure(types.AzureEngineConfig{AccountName: "account123", ContainerName: "documents", AuthMode: types.AzureAuthAccountKey, AccountKey: "saved-private-key"})}}
	for _, id := range []string{"instance", "foreign-instance"} {
		recorder := httptest.NewRecorder()
		c, _ := gin.CreateTestContext(recorder)
		c.Set(types.TenantIDContextKey.String(), uint64(42))
		body := `{"id":"` + id + `","name":"Azure","provider":"azure","config":{"account_name":"account123","container_name":"documents","endpoint":"https://other.example.com","auth_mode":"account_key","account_key":"***"}}`
		c.Request = httptest.NewRequest(http.MethodPost, "/api/v1/storage-backends/test", strings.NewReader(body))
		c.Request.Header.Set("Content-Type", "application/json")
		// A nil service deliberately proves rejection happens before any network probe.
		handler := NewStorageBackendHandler(repo, nil)
		handler.TestRaw(c)
		if len(c.Errors) != 1 {
			t.Fatal("untrusted test target reached storage service")
		}
		if strings.Contains(c.Errors[0].Error(), "saved-private-key") {
			t.Fatal("stored credential leaked")
		}
	}
}
