//go:build ignore

// go run backend-java/src/test/java/com/tencent/weknora/security/apikey_golden.go > backend-java/src/test/java/com/tencent/weknora/security/apikey-golden.json
package main

import (
	"encoding/json"
	"github.com/Tencent/WeKnora/internal/types"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
	"os"
	"time"
)

func main() {
	_ = os.Setenv("SYSTEM_AES_KEY", "0123456789abcdef0123456789abcdef")
	db, err := gorm.Open(sqlite.Open(":memory:"), &gorm.Config{Logger: logger.Default.LogMode(logger.Silent)})
	if err != nil {
		panic(err)
	}
	if err = db.AutoMigrate(&types.TenantAPIKey{}); err != nil {
		panic(err)
	}
	tenant := uint64(1)
	created := time.Date(2026, 1, 2, 3, 4, 5, 0, time.UTC)
	key := types.TenantAPIKey{TenantID: &tenant, ScopeType: types.APIKeyScopeTenant, Name: "Go fixture", KeyHash: "fixture", APIKey: "sk-GoFixture", FullAccess: true, KnowledgeBaseIDs: nil, Capabilities: nil, CreatedAt: created, UpdatedAt: created}
	if err = db.Create(&key).Error; err != nil {
		panic(err)
	}
	create := map[string]any{"api_key_encrypted": len(key.APIKey) > 7 && key.APIKey[:7] == "enc:v1:", "knowledge_base_ids": key.KnowledgeBaseIDs, "capabilities": types.NormalizeAPIKeyCapabilities(key.Capabilities)}
	var loaded types.TenantAPIKey
	if err = db.First(&loaded, key.ID).Error; err != nil {
		panic(err)
	}
	out := map[string]any{"create": create, "loaded_key": loaded, "capabilities": types.NormalizeAPIKeyCapabilities(types.StringArray{" Chat ", "retrieve", "chat", "bogus", "SYSTEM_TENANTS_READ", "", "\u0085MANAGE_KBS\u3000"})}
	enc := json.NewEncoder(os.Stdout)
	enc.SetIndent("", "  ")
	_ = enc.Encode(out)
}
