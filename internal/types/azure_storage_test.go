package types

import (
	"encoding/json"
	"strings"
	"testing"

	"github.com/Tencent/WeKnora/internal/utils"
)

func azureTestConfig() StorageBackendConfig {
	return StorageBackendConfigFromAzure(AzureEngineConfig{AccountName: "account123", ContainerName: "documents", AuthMode: AzureAuthAccountKey, AccountKey: "test-secret-key", PathPrefix: "prod/"})
}

func TestAzureCredentialPersistenceAndRotation(t *testing.T) {
	t.Setenv("SYSTEM_AES_KEY", "0123456789abcdef0123456789abcdef")
	config := azureTestConfig()
	value, err := config.Value()
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(value.([]byte)), config.AccountKey) {
		t.Fatal("plaintext key persisted")
	}
	var restored StorageBackendConfig
	if err := restored.Scan(value); err != nil {
		t.Fatal(err)
	}
	if restored.AccountKey != config.AccountKey {
		t.Fatal("credential did not round trip")
	}
	masked := restored.MaskSensitiveFields()
	if masked.AccountKey != RedactedSecretPlaceholder {
		t.Fatal("credential not masked")
	}
	masked = masked.MergeSecrets(restored)
	if masked.AccountKey != config.AccountKey {
		t.Fatal("masked update lost credential")
	}
	masked.AccountKey = "rotated"
	if masked.MergeSecrets(restored).AccountKey != "rotated" {
		t.Fatal("rotation lost new key")
	}
	if masked.LocationKey("azure") != config.LocationKey("azure") {
		t.Fatal("credentials changed physical identity")
	}
	masked.AccountKey = ""
	if masked.MergeSecrets(restored).ValidateForProvider("azure") == nil {
		t.Fatal("explicitly cleared key must not restore old credential")
	}
	masked.AuthMode = AzureAuthManagedIdentity
	masked.AccountKey = RedactedSecretPlaceholder
	changed := StorageBackendConfigFromAzure(masked.MergeSecrets(restored).AzureConfig())
	if changed.AccountKey != "" || changed.ValidateForProvider("azure") != nil {
		t.Fatal("identity mode retained account key")
	}
	if changed.LocationKey("azure") != config.LocationKey("azure") {
		t.Fatal("auth switch changed identity")
	}
}
func TestAzureLegacyConfigurationEncryptsAndMasksWithoutMutation(t *testing.T) {
	t.Setenv("SYSTEM_AES_KEY", "0123456789abcdef0123456789abcdef")
	c := azureTestConfig().AzureConfig()
	original := StorageEngineConfig{DefaultProvider: "azure", Azure: &c}
	value, err := original.Value()
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(value.([]byte)), c.AccountKey) {
		t.Fatal("legacy config leaked key")
	}
	if original.Azure.AccountKey != c.AccountKey {
		t.Fatal("encryption mutated input")
	}
	var restored StorageEngineConfig
	if err := restored.Scan(value); err != nil {
		t.Fatal(err)
	}
	if restored.Azure.AccountKey != c.AccountKey {
		t.Fatal("legacy decrypt failed")
	}
	response := StorageEngineConfigForResponse(&restored, true)
	bytes, _ := json.Marshal(response)
	if strings.Contains(string(bytes), c.AccountKey) {
		t.Fatal("response leaked key")
	}
}
func TestAzureEncryptionFailsClosed(t *testing.T) {
	t.Setenv("SYSTEM_AES_KEY", "")
	if _, err := azureTestConfig().Value(); err == nil {
		t.Fatal("missing encryption key accepted")
	}
	t.Setenv("SYSTEM_AES_KEY", "0123456789abcdef0123456789abcdef")
	value, err := azureTestConfig().Value()
	if err != nil {
		t.Fatal(err)
	}
	t.Setenv("SYSTEM_AES_KEY", "abcdef0123456789abcdef0123456789")
	var config StorageBackendConfig
	if config.Scan(value) == nil {
		t.Fatal("wrong encryption key accepted")
	}
}
func TestAzureConfigValidationAndPaths(t *testing.T) {
	valid := azureTestConfig().AzureConfig()
	for _, mutate := range []func(*AzureEngineConfig){
		func(c *AzureEngineConfig) { c.AccountName = "UPPER" }, func(c *AzureEngineConfig) { c.ContainerName = "bad--name" },
		func(c *AzureEngineConfig) { c.Endpoint = "http://account.blob.core.windows.net" },
		func(c *AzureEngineConfig) { c.Endpoint = "https://user:secret@account.blob.core.windows.net" },
		func(c *AzureEngineConfig) { c.Endpoint = "https://account.blob.core.windows.net?sig=secret" },
		func(c *AzureEngineConfig) { c.Endpoint = "https://account.blob.core.windows.net/container" },
		func(c *AzureEngineConfig) { c.PathPrefix = "../other" }, func(c *AzureEngineConfig) { c.PathPrefix = "/outside" },
		func(c *AzureEngineConfig) { c.AuthMode = "ambient" }, func(c *AzureEngineConfig) { c.AccountKey = "" },
		func(c *AzureEngineConfig) {
			c.AuthMode = AzureAuthManagedIdentity
			c.ManagedIdentityClientID = "invalid"
		},
	} {
		bad := valid
		mutate(&bad)
		if bad.Validate() == nil {
			t.Error("invalid config accepted")
		}
	}
	if valid.Validate() != nil {
		t.Fatal("valid config rejected")
	}
	if utils.ParseTenantIDFromStoragePath("storage://instance/azure://123/2026/tenants/42/assets/test.pdf") != 42 {
		t.Fatal("ambiguous tenant extraction")
	}
	if ParseProviderScheme("azure://documents/key") != "azure" {
		t.Fatal("scheme not registered")
	}
}

func TestAzureEnvironmentAndLegacyAdapters(t *testing.T) {
	t.Setenv("STORAGE_TYPE", "azure")
	t.Setenv("AZURE_STORAGE_ACCOUNT_NAME", "account123")
	t.Setenv("AZURE_STORAGE_CONTAINER_NAME", "documents")
	t.Setenv("AZURE_STORAGE_BLOB_ENDPOINT", "")
	t.Setenv("AZURE_STORAGE_AUTH_MODE", "managed_identity")
	t.Setenv("AZURE_STORAGE_ACCOUNT_KEY", "unused-key")
	backend := StorageBackendFromEnvironment(42)
	if err := backend.Validate(); err != nil {
		t.Fatal(err)
	}
	if backend.Provider != "azure" || backend.Config.AccountKey != "" || backend.Config.Endpoint != "https://account123.blob.core.windows.net" {
		t.Fatal("environment config did not normalize")
	}
	restored := StorageBackendFromLegacy(42, "azure", backend.ToStorageEngineConfig())
	if restored == nil || restored.Config.LocationKey("azure") != backend.Config.LocationKey("azure") {
		t.Fatal("legacy adapter changed location")
	}
}
