//go:build ignore

// Regenerate from the repository root with:
// go run backend-java/src/test/java/com/tencent/weknora/security/auth_golden.go > backend-java/src/test/java/com/tencent/weknora/security/auth-golden.json
package main

import (
	"encoding/json"
	"github.com/Tencent/WeKnora/internal/handler/dto"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/utils"
	"github.com/golang-jwt/jwt/v5"
	"os"
	"time"
)

func main() {
	prefs := types.UserPreferences{}
	_ = json.Unmarshal([]byte(`{"browser_search_instructions":null,"last_active_tenant_id":0,"oidc_only_login":false,"gallery":{"mode":"","status":{},"unknown":42},"unknown":"discard"}`), &prefs)
	user := &types.User{ID: "u", Username: "Go User", Email: "u@example.com", TenantID: 1, IsActive: true, Preferences: prefs, CreatedAt: time.Date(2026, 1, 2, 3, 4, 5, 0, time.UTC), UpdatedAt: time.Date(2026, 1, 2, 3, 4, 5, 0, time.UTC)}
	tenant := &types.Tenant{ID: 1, Name: "home", Status: "active", Business: "test", StorageQuota: 100, StorageUsed: 10, CreatedAt: user.CreatedAt, UpdatedAt: user.UpdatedAt}
	tenant.WebSearchConfig = &types.WebSearchConfig{APIKey: "secret", ProxyURL: "http://secret-proxy"}
	tenant.ParserEngineConfig = &types.ParserEngineConfig{MinerUAPIKey: "secret", MinerUServerAPIKey: "server-secret"}
	tenant.Credentials = &types.CredentialsConfig{WeKnoraCloud: &types.WeKnoraCloudCredentials{AppID: "app", AppSecret: "secret"}}
	tenant.StorageEngineConfig = &types.StorageEngineConfig{DefaultProvider: "local", Local: &types.LocalEngineConfig{PathPrefix: "/files"}}
	tokens := map[string]string{}
	for _, algorithm := range []*jwt.SigningMethodHMAC{jwt.SigningMethodHS256, jwt.SigningMethodHS384, jwt.SigningMethodHS512} {
		token, _ := jwt.NewWithClaims(algorithm, jwt.MapClaims{"user_id": "u", "exp": 4102444800, "tenant_id": 1}).SignedString([]byte("test-shared-go-java-jwt-secret"))
		tokens[algorithm.Alg()] = token
	}
	noExp, _ := jwt.NewWithClaims(jwt.SigningMethodHS256, jwt.MapClaims{"user_id": "u", "type": "legacy", "tenant_id": 1}).SignedString([]byte("test-shared-go-java-jwt-secret"))
	tokens["legacy_no_exp"] = noExp
	output := map[string]any{"preferences": prefs, "user_info": user.ToUserInfo(), "login_user": user, "tenant_viewer": dto.NewTenantResponseWithRole(tenant, types.TenantRoleViewer), "tenant_admin": dto.NewTenantResponseWithRole(tenant, types.TenantRoleAdmin), "login_failure": dto.NewAuthLoginResponse(&types.LoginResponse{Success: false, Message: "Invalid email or password"}), "browser_default": types.DefaultBrowserSearchInstructions, "tokens": tokens}
	encrypted, _ := utils.EncryptAESGCM("Go secret 密钥 🔐", []byte("0123456789abcdef0123456789abcdef"))
	output["encrypted_secret"] = encrypted
	storage := map[string]any{}
	for _, key := range []string{"ENDPOINT", "REGION", "ACCESS_KEY", "ACCESS_KEY_ID", "SECRET_ACCESS_KEY", "SECRET_KEY", "SECRET_ID", "BUCKET_NAME", "APP_ID", "PATH_PREFIX", "TEMP_BUCKET_NAME", "TEMP_REGION", "USE_SSL", "FORCE_PATH_STYLE"} {
		for _, provider := range []string{"MINIO", "COS", "TOS", "S3", "OSS", "OBS"} {
			_ = os.Setenv(provider+"_"+key, "test-"+key)
		}
	}
	_ = os.Setenv("S3_USE_SSL", "true")
	_ = os.Setenv("S3_FORCE_PATH_STYLE", "true")
	_ = os.Setenv("MINIO_USE_SSL", "true")
	_ = os.Setenv("OBS_USE_SSL", "false")
	_ = os.Setenv("LOCAL_STORAGE_PATH_PREFIX", " /fixture/path ")
	for _, provider := range []string{"local", "minio", "cos", "tos", "s3", "oss", "obs", "ks3"} {
		_ = os.Setenv("STORAGE_TYPE", provider)
		backend := types.StorageBackendFromEnvironment(1)
		if backend == nil {
			storage[provider] = nil
		} else {
			storage[provider] = backend.Config
		}
	}
	output["storage_configs"] = storage
	encoder := json.NewEncoder(os.Stdout)
	encoder.SetIndent("", "  ")
	_ = encoder.Encode(output)
}
