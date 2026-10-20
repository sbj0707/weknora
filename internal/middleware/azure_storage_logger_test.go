package middleware

import (
	"strings"
	"testing"
)

func TestAzureStorageCredentialsAreNotLogged(t *testing.T) {
	raw := `{"provider":"azure","config":{"account_key":"base64-secret=="},"connectionString":"AccountName=account;AccountKey=hidden;"}`
	sanitized := sanitizeBody(raw)
	for _, secret := range []string{"base64-secret", "AccountName=", "hidden"} {
		if strings.Contains(sanitized, secret) {
			t.Fatal("Azure credentials leaked to request log")
		}
	}
	if !strings.Contains(sanitized, `"provider":"azure"`) {
		t.Fatal("non-sensitive context lost")
	}
}
