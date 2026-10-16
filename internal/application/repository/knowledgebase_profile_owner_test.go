package repository

import (
	"context"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/stretchr/testify/require"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
	"path/filepath"
	"testing"
	"time"
)

func TestKnowledgeBaseProfilePersistRequiresCurrentOwner(t *testing.T) {
	db, err := gorm.Open(sqlite.Open(filepath.Join(t.TempDir(), "profile.db")), &gorm.Config{})
	require.NoError(t, err)
	sql, err := db.DB()
	require.NoError(t, err)
	t.Cleanup(func() { sql.Close() })
	require.NoError(t, db.AutoMigrate(&types.KnowledgeBase{}))
	r := NewKnowledgeBaseRepository(db)
	ctx := types.WithExecutionTenant(context.Background(), 7)
	kb := &types.KnowledgeBase{ID: "kb", TenantID: 7, Name: "Original", Type: "document"}
	require.NoError(t, r.CreateKnowledgeBase(ctx, kb))
	first := &types.KnowledgeBaseProfile{Gist: "Owned", Status: "ready"}
	require.NoError(t, r.UpdateKnowledgeBaseGeneratedProfile(ctx, "kb", first))
	require.NoError(t, db.Model(&types.KnowledgeBase{}).Where("id=?", "kb").Updates(map[string]any{"tenant_id": 8, "name": "Concurrent settings"}).Error)
	stale := &types.KnowledgeBaseProfile{Gist: "Stale original-owner response", Status: "ready"}
	require.ErrorIs(t, r.UpdateKnowledgeBaseGeneratedProfile(ctx, "kb", stale), ErrKnowledgeBaseNotFound)
	require.Error(t, r.UpdateKnowledgeBaseGeneratedProfile(context.Background(), "kb", stale))
	stored, err := r.GetKnowledgeBaseByIDAndTenant(context.Background(), "kb", 8)
	require.NoError(t, err)
	require.Equal(t, "Owned", stored.GeneratedProfile.Gist)
	require.Equal(t, "Concurrent settings", stored.Name)
	require.NoError(t, r.UpdateKnowledgeBaseGeneratedProfile(types.WithExecutionTenant(context.Background(), 8), "kb", stale))
	require.NoError(t, db.Model(&types.KnowledgeBase{}).Where("id=?", "kb").Update("deleted_at", time.Now()).Error)
	require.ErrorIs(t, r.UpdateKnowledgeBaseGeneratedProfile(types.WithExecutionTenant(context.Background(), 8), "kb", first), ErrKnowledgeBaseNotFound)
}
