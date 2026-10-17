package service

import (
	"net/url"
	"os"
	"strings"
	"testing"

	"github.com/Tencent/WeKnora/internal/application/repository"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/google/uuid"
	"github.com/stretchr/testify/require"
	"gorm.io/driver/postgres"
	"gorm.io/gorm"
)

// Optional integration check against an isolated, disposable PostgreSQL schema.
// No existing application table or record is changed.
func TestEvaluationPlanPostgresPersistence(t *testing.T) {
	dsn := os.Getenv("WEKNORA_TEST_PLAN_POSTGRES")
	if dsn == "" {
		t.Skip("WEKNORA_TEST_PLAN_POSTGRES is not set")
	}
	admin, err := gorm.Open(postgres.Open(dsn), &gorm.Config{})
	require.NoError(t, err)
	schema := "test_evaluation_plan_" + strings.ReplaceAll(uuid.NewString(), "-", "")
	require.NoError(t, admin.Exec("CREATE SCHEMA "+schema).Error)
	t.Cleanup(func() {
		_ = admin.Exec("DROP SCHEMA " + schema + " CASCADE").Error
		sqlDB, _ := admin.DB()
		_ = sqlDB.Close()
	})
	uri, err := url.Parse(dsn)
	require.NoError(t, err)
	query := uri.Query()
	query.Set("search_path", schema)
	uri.RawQuery = query.Encode()
	db, err := gorm.Open(postgres.Open(uri.String()), &gorm.Config{})
	require.NoError(t, err)
	sqlDB, err := db.DB()
	require.NoError(t, err)
	t.Cleanup(func() { _ = sqlDB.Close() })
	ddl, err := os.ReadFile("../../../migrations/versioned/000115_evaluation_plans.up.sql")
	require.NoError(t, err)
	require.NoError(t, db.Exec(string(ddl)).Error)
	require.NoError(t, db.Exec("CREATE TABLE users (id VARCHAR(36) PRIMARY KEY, username TEXT, deleted_at TIMESTAMPTZ)").Error)
	models := &planTestModels{rows: map[string]*types.Model{"chat": {ID: "chat", TenantID: 1, Type: types.ModelTypeKnowledgeQA, Status: types.ModelStatusActive}}}
	svc := NewEvaluationPlanService(repository.NewEvaluationPlanRepository(db), models, nil, nil)
	ctx := planContext(1, "alice", types.TenantRoleContributor)
	p, err := svc.Create(ctx, planInput())
	require.NoError(t, err)
	input := planInput()
	input.PassScore = 0
	input.ScoringDimensions[0].Description = "Updated JSONB content"
	_, err = svc.Update(ctx, p.ID, input)
	require.NoError(t, err)
	reopened, err := gorm.Open(postgres.Open(uri.String()), &gorm.Config{})
	require.NoError(t, err)
	reopenedSQL, err := reopened.DB()
	require.NoError(t, err)
	defer reopenedSQL.Close()
	other := NewEvaluationPlanService(repository.NewEvaluationPlanRepository(reopened), models, nil, nil)
	got, err := other.Get(ctx, p.ID)
	require.NoError(t, err)
	require.Zero(t, got.PassScore)
	require.Equal(t, input.ScoringDimensions, got.ScoringDimensions)
	require.Equal(t, input.QAGenerationPrompt, got.QAGenerationPrompt)
	page, err := other.List(ctx, types.EvaluationPlanQuery{Keyword: "AIA_100%"})
	require.NoError(t, err)
	require.Equal(t, int64(1), page.Total)
	copy, err := other.Copy(ctx, p.ID)
	require.NoError(t, err)
	require.NotEqual(t, p.ID, copy.ID)
	require.NoError(t, other.Delete(ctx, p.ID))
	_, err = other.Get(ctx, p.ID)
	requirePlanStatus(t, err, 404)
	down, err := os.ReadFile("../../../migrations/versioned/000115_evaluation_plans.down.sql")
	require.NoError(t, err)
	require.NoError(t, db.Exec(string(down)).Error)
	require.NoError(t, db.Exec(string(ddl)).Error) // Reversible migration without hidden schema dependencies.
}
