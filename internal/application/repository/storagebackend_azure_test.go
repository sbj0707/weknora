package repository

import (
	"context"
	"github.com/Tencent/WeKnora/internal/types"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
	"strings"
	"testing"
)

func TestAzureStorageBackendPersistsEncryptedAndScopesInstances(t *testing.T) {
	t.Setenv("SYSTEM_AES_KEY", "0123456789abcdef0123456789abcdef")
	db, err := gorm.Open(sqlite.Open(":memory:"), &gorm.Config{})
	if err != nil {
		t.Fatal(err)
	}
	if err := db.AutoMigrate(&types.StorageBackend{}); err != nil {
		t.Fatal(err)
	}
	repo := NewStorageBackendRepository(db)
	ctx := context.Background()
	var ids []string
	for _, account := range []string{"accountone", "accounttwo"} {
		backend := &types.StorageBackend{TenantID: 42, Name: account, Provider: "azure", Config: types.StorageBackendConfigFromAzure(types.AzureEngineConfig{AccountName: account, ContainerName: "documents", AuthMode: types.AzureAuthAccountKey, AccountKey: "account-private-key"})}
		if err := backend.Validate(); err != nil {
			t.Fatal(err)
		}
		if err := repo.Create(ctx, backend); err != nil {
			t.Fatal(err)
		}
		ids = append(ids, backend.ID)
		var stored []byte
		if err := db.Raw("SELECT config FROM storage_backends WHERE id = ?", backend.ID).Row().Scan(&stored); err != nil {
			t.Fatal(err)
		}
		if strings.Contains(string(stored), "account-private-key") {
			t.Fatal("plaintext credential in SQLite")
		}
	}
	if ids[0] == ids[1] {
		t.Fatal("instances share ID")
	}
	one, err := repo.GetByID(ctx, 42, ids[0])
	if err != nil || one.Config.AccountName != "accountone" || one.Config.AccountKey != "account-private-key" {
		t.Fatal("wrong instance or credential", err)
	}
	other, err := repo.GetByID(ctx, 43, ids[0])
	if err != nil || other != nil {
		t.Fatal("instance crossed tenant boundary", err)
	}
}
