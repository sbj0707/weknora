package types

import (
	"fmt"
	"net/url"
	"regexp"
	"strings"

	"github.com/google/uuid"
)

var azureSearchHost = regexp.MustCompile(`^[a-z0-9](?:[a-z0-9-]{0,58}[a-z0-9])?\.search\.windows\.net$`)
var azureSearchPrefix = regexp.MustCompile(`^[a-z][a-z0-9-]{0,91}[a-z0-9]$|^[a-z]$`)

// NormalizeAzureAISearch removes credentials belonging to the inactive mode.
func (c ConnectionConfig) NormalizeAzureAISearch() ConnectionConfig {
	c.Addr = strings.TrimRight(strings.ToLower(strings.TrimSpace(c.Addr)), "/")
	if c.AuthMode == "" {
		c.AuthMode = "api_key"
	}
	if c.AuthMode == "managed_identity" {
		c.APIKey = ""
	} else if c.AuthMode == "api_key" {
		c.ManagedIdentityClientID = ""
	}
	c.Username, c.Password = "", ""
	return c
}

// ValidateAzureAISearchConnection is pure; DNS/SSRF policy is applied separately
// at input, factory and transport boundaries.
func ValidateAzureAISearchConnection(c ConnectionConfig) error {
	u, err := url.Parse(c.Addr)
	if err != nil || u.Scheme != "https" || u.User != nil || u.RawQuery != "" || u.ForceQuery ||
		u.Fragment != "" || (u.Path != "" && u.Path != "/") || u.RawPath != "" ||
		!azureSearchHost.MatchString(u.Host) {
		return fmt.Errorf("Azure AI Search addr must be https://<service>.search.windows.net without port, credentials, query or path")
	}
	if c.InsecureSkipVerify {
		return fmt.Errorf("Azure AI Search requires TLS certificate verification")
	}
	switch c.AuthMode {
	case "", "api_key":
		if strings.TrimSpace(c.APIKey) == "" || c.APIKey == RedactedSecretPlaceholder {
			return fmt.Errorf("Azure AI Search admin api_key is required in api_key mode")
		}
	case "managed_identity":
		if c.ManagedIdentityClientID != "" {
			if _, err := uuid.Parse(c.ManagedIdentityClientID); err != nil {
				return fmt.Errorf("managed_identity_client_id must be a UUID")
			}
		}
	default:
		return fmt.Errorf("Azure AI Search auth_mode must be api_key or managed_identity")
	}
	return nil
}

func ValidateAzureAISearchIndex(c IndexConfig) error {
	prefix := c.GetIndexNameOrDefault(AzureAISearchRetrieverEngineType)
	// Azure index names are <=128 bytes. Reserve 36 bytes for -UUID-v1.
	if !azureSearchPrefix.MatchString(prefix) || strings.Contains(prefix, "--") || len(prefix) > 92 {
		return fmt.Errorf("Azure AI Search index prefix must be 1-92 lowercase letters, digits or single hyphens, starting with a letter and ending with a letter or digit")
	}
	if c.VectorDimensions < 1 || c.VectorDimensions > 4096 {
		return fmt.Errorf("Azure AI Search vector_dimensions must be between 1 and 4096")
	}
	switch c.TextAnalyzer {
	case "", "standard.lucene", "zh-Hans.microsoft", "zh-Hans.lucene", "en.microsoft":
	default:
		return fmt.Errorf("unsupported Azure AI Search text_analyzer")
	}
	return nil
}

func AzureAISearchIndexName(storeID string, cfg IndexConfig) (string, error) {
	id, err := uuid.Parse(storeID)
	if err != nil {
		return "", fmt.Errorf("Azure AI Search requires a persistent store UUID")
	}
	if err := ValidateAzureAISearchIndex(cfg); err != nil {
		return "", err
	}
	return cfg.GetIndexNameOrDefault(AzureAISearchRetrieverEngineType) + "-" + strings.ReplaceAll(id.String(), "-", "") + "-v1", nil
}

func azureAISearchDisplayIndexName(store *VectorStore) string {
	if store.EngineType != AzureAISearchRetrieverEngineType {
		return ""
	}
	name, _ := AzureAISearchIndexName(store.ID, store.IndexConfig)
	return name
}
