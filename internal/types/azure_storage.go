package types

import (
	"fmt"
	"net/url"
	"path"
	"regexp"
	"strings"

	"github.com/google/uuid"
)

const (
	AzureAuthManagedIdentity = "managed_identity"
	AzureAuthAccountKey      = "account_key"
)

// AzureEngineConfig identifies one private Azure Blob container. Credentials
// never participate in object identity, so keys and identities can be rotated.
type AzureEngineConfig struct {
	AccountName             string `json:"account_name"`
	Endpoint                string `json:"endpoint"`
	ContainerName           string `json:"container_name"`
	PathPrefix              string `json:"path_prefix"`
	AuthMode                string `json:"auth_mode"`
	AccountKey              string `json:"account_key,omitempty"`
	ManagedIdentityClientID string `json:"managed_identity_client_id,omitempty"`
}

var (
	azureAccountNamePattern = regexp.MustCompile(`^[a-z0-9]{3,24}$`)
	azureContainerPattern   = regexp.MustCompile(`^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$`)
)

func (c AzureEngineConfig) Normalized() AzureEngineConfig {
	c.AccountName = strings.TrimSpace(c.AccountName)
	c.ContainerName = strings.TrimSpace(c.ContainerName)
	c.Endpoint = strings.TrimRight(strings.TrimSpace(c.Endpoint), "/")
	if c.Endpoint == "" && azureAccountNamePattern.MatchString(c.AccountName) {
		c.Endpoint = "https://" + c.AccountName + ".blob.core.windows.net"
	}
	c.AuthMode = strings.ToLower(strings.TrimSpace(c.AuthMode))
	if c.AuthMode == "" {
		c.AuthMode = AzureAuthManagedIdentity
	}
	c.ManagedIdentityClientID = strings.TrimSpace(c.ManagedIdentityClientID)
	c.AccountKey = strings.TrimSpace(c.AccountKey)
	c.PathPrefix = strings.TrimSpace(c.PathPrefix)
	if c.AuthMode == AzureAuthManagedIdentity {
		c.AccountKey = ""
	} else if c.AuthMode == AzureAuthAccountKey {
		c.ManagedIdentityClientID = ""
	}
	return c
}

// Validate is deliberately network-free. The driver additionally validates
// endpoint resolution and uses the shared SSRF-safe object-storage transport.
func (c AzureEngineConfig) Validate() error {
	c = c.Normalized()
	if !azureAccountNamePattern.MatchString(c.AccountName) {
		return fmt.Errorf("Azure account_name must contain 3–24 lowercase letters or digits")
	}
	if !azureContainerPattern.MatchString(c.ContainerName) || strings.Contains(c.ContainerName, "--") {
		return fmt.Errorf("Azure container_name must contain 3–63 lowercase letters, digits or single hyphens")
	}
	u, err := url.Parse(c.Endpoint)
	if err != nil || u.Scheme != "https" || u.Hostname() == "" || u.User != nil || u.RawQuery != "" || u.ForceQuery || strings.Contains(c.Endpoint, "#") || u.Fragment != "" || u.Path != "" || u.Opaque != "" {
		return fmt.Errorf("Azure endpoint must be an HTTPS service URL without credentials, path, query or fragment")
	}
	prefix := strings.ReplaceAll(c.PathPrefix, "\\", "/")
	if strings.Contains(c.PathPrefix, "\\") || strings.HasPrefix(prefix, "/") || strings.Contains(prefix, "..") || strings.ContainsAny(prefix, "\x00\r\n?#") || path.Clean(prefix) == ".." {
		return fmt.Errorf("Azure path_prefix must be a relative object prefix without parent traversal")
	}
	switch c.AuthMode {
	case AzureAuthAccountKey:
		if c.AccountKey == "" || c.AccountKey == RedactedSecretPlaceholder {
			return fmt.Errorf("Azure account_key is required for account_key authentication")
		}
	case AzureAuthManagedIdentity:
		if c.ManagedIdentityClientID != "" {
			if _, err := uuid.Parse(c.ManagedIdentityClientID); err != nil {
				return fmt.Errorf("Azure managed_identity_client_id must be a valid client ID")
			}
		}
	default:
		return fmt.Errorf("Azure auth_mode must be managed_identity or account_key")
	}
	return nil
}

func (c StorageBackendConfig) AzureConfig() AzureEngineConfig {
	return AzureEngineConfig{
		AccountName: c.AccountName, Endpoint: c.Endpoint, ContainerName: c.ContainerName,
		PathPrefix: c.PathPrefix, AuthMode: c.AuthMode, AccountKey: c.AccountKey,
		ManagedIdentityClientID: c.ManagedIdentityClientID,
	}
}

func StorageBackendConfigFromAzure(c AzureEngineConfig) StorageBackendConfig {
	c = c.Normalized()
	return StorageBackendConfig{
		AccountName: c.AccountName, Endpoint: c.Endpoint, ContainerName: c.ContainerName,
		PathPrefix: c.PathPrefix, AuthMode: c.AuthMode, AccountKey: c.AccountKey,
		ManagedIdentityClientID: c.ManagedIdentityClientID,
	}
}
