package service

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"testing"

	"github.com/Tencent/WeKnora/internal/application/repository"
	apperrors "github.com/Tencent/WeKnora/internal/errors"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"github.com/google/uuid"
	"github.com/stretchr/testify/require"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
)

type planTestModels struct {
	interfaces.ModelRepository
	rows map[string]*types.Model
}

func (m *planTestModels) GetByID(_ context.Context, tenant uint64, id string) (*types.Model, error) {
	row := m.rows[id]
	if row != nil && (row.TenantID == tenant || row.IsBuiltin) {
		return row, nil
	}
	return nil, nil
}
func planContext(tenant uint64, user string, role types.TenantRole) context.Context {
	ctx := context.WithValue(context.Background(), types.TenantIDContextKey, tenant)
	return types.WithCaller(ctx, types.Caller{TenantID: tenant, UserID: user, Role: role})
}
func planInput() types.EvaluationPlanInput {
	return types.EvaluationPlanInput{Name: "AIA_100%", Description: "Yearly evaluation", QAGenerationPrompt: "  source: {{content}}\n", AnswerComparisonPrompt: "{{expected_answer}} {{actual_answer}}", QAGenerationModelID: "chat", EvaluationModelID: "chat", PassScore: 80,
		ScoringDimensions: []types.EvaluationDimension{{ID: "accuracy", Name: "Accuracy", Description: "Grounded in source", Weight: 100}}, QAGenerationRules: types.QAGenerationRules{QuestionsPerChunk: 3, QuestionTypes: []string{"fact", "numeric"}, Difficulty: "medium", Language: "zh-CN"}}
}
func newPlanFixture(t *testing.T) (*EvaluationPlanService, *planTestModels, string) {
	t.Helper()
	path := filepath.Join(t.TempDir(), "plans.db")
	db, err := gorm.Open(sqlite.Open(path), &gorm.Config{})
	require.NoError(t, err)
	ddl, err := os.ReadFile("../../../migrations/sqlite/000034_evaluation_plans.up.sql")
	require.NoError(t, err)
	require.NoError(t, db.Exec(string(ddl)).Error)
	require.NoError(t, db.Exec("CREATE TABLE users (id TEXT PRIMARY KEY, username TEXT, deleted_at DATETIME)").Error)
	require.NoError(t, db.Exec("INSERT INTO users (id,username) VALUES ('alice','Alice'),('bob','Bob')").Error)
	models := &planTestModels{rows: map[string]*types.Model{"chat": {ID: "chat", TenantID: 1, Type: types.ModelTypeKnowledgeQA, Status: types.ModelStatusActive}}}
	sqlDB, err := db.DB()
	require.NoError(t, err)
	t.Cleanup(func() { _ = sqlDB.Close() })
	return NewEvaluationPlanService(repository.NewEvaluationPlanRepository(db), models, nil, nil), models, path
}
func requirePlanStatus(t *testing.T, err error, status int) {
	t.Helper()
	var app *apperrors.AppError
	require.True(t, errors.As(err, &app), "%v", err)
	require.Equal(t, status, app.HTTPCode)
}
func TestEvaluationPlanPersistentCRUDAndCopy(t *testing.T) {
	s, _, path := newPlanFixture(t)
	ctx := planContext(1, "alice", types.TenantRoleContributor)
	input := planInput()
	p, err := s.Create(ctx, input)
	require.NoError(t, err)
	_, err = uuid.Parse(p.ID)
	require.NoError(t, err)
	require.Equal(t, "Alice", p.CreatorName)
	require.True(t, p.CanManage)
	require.Equal(t, input.QAGenerationPrompt, p.QAGenerationPrompt)
	db, err := gorm.Open(sqlite.Open(path), &gorm.Config{})
	require.NoError(t, err)
	reopened := NewEvaluationPlanService(repository.NewEvaluationPlanRepository(db), s.models, nil, nil)
	sqlDB, err := db.DB()
	require.NoError(t, err)
	defer sqlDB.Close()
	persisted, err := reopened.Get(ctx, p.ID)
	require.NoError(t, err)
	require.Equal(t, p.ScoringDimensions, persisted.ScoringDimensions)
	require.Equal(t, p.QAGenerationRules, persisted.QAGenerationRules)
	input.Name = "Changed"
	input.PassScore = 0
	input.Description = ""
	updated, err := s.Update(ctx, p.ID, input)
	require.NoError(t, err)
	require.Zero(t, updated.PassScore)
	require.Empty(t, updated.Description)
	require.Equal(t, p.CreatedAt, updated.CreatedAt)
	require.Equal(t, p.CreatedBy, updated.CreatedBy)
	require.True(t, updated.UpdatedAt.After(p.UpdatedAt))
	copy, err := s.Copy(planContext(1, "bob", types.TenantRoleContributor), p.ID)
	require.NoError(t, err)
	require.NotEqual(t, p.ID, copy.ID)
	require.Equal(t, "bob", copy.CreatedBy)
	require.Equal(t, "Changed（副本）", copy.Name)
	input.Name = "Copy only"
	_, err = s.Update(planContext(1, "bob", types.TenantRoleContributor), copy.ID, input)
	require.NoError(t, err)
	original, err := s.Get(ctx, p.ID)
	require.NoError(t, err)
	require.Equal(t, "Changed", original.Name)
	require.NoError(t, s.Delete(ctx, p.ID))
	_, err = s.Get(ctx, p.ID)
	requirePlanStatus(t, err, 404)
	_, err = s.Copy(ctx, p.ID)
	requirePlanStatus(t, err, 404)
	page, err := s.List(ctx, types.EvaluationPlanQuery{})
	require.NoError(t, err)
	require.Equal(t, int64(1), page.Total)
	var count int64
	require.NoError(t, db.Unscoped().Model(&types.EvaluationPlan{}).Count(&count).Error)
	require.Equal(t, int64(2), count)
}
func TestEvaluationPlanAuthorizationAndSearch(t *testing.T) {
	s, _, _ := newPlanFixture(t)
	alice := planContext(1, "alice", types.TenantRoleContributor)
	p, err := s.Create(alice, planInput())
	require.NoError(t, err)
	bob := planContext(1, "bob", types.TenantRoleContributor)
	viewer := planContext(1, "bob", types.TenantRoleViewer)
	read, err := s.Get(bob, p.ID)
	require.NoError(t, err)
	require.False(t, read.CanManage)
	_, err = s.Update(bob, p.ID, planInput())
	requirePlanStatus(t, err, 403)
	requirePlanStatus(t, s.Delete(bob, p.ID), 403)
	_, err = s.Create(viewer, planInput())
	requirePlanStatus(t, err, 403)
	_, err = s.Copy(viewer, p.ID)
	requirePlanStatus(t, err, 403)
	admin := planContext(1, "bob", types.TenantRoleAdmin)
	_, err = s.Update(admin, p.ID, planInput())
	require.NoError(t, err)
	foreign := planContext(2, "alice", types.TenantRoleOwner)
	_, err = s.Get(foreign, p.ID)
	requirePlanStatus(t, err, 404)
	_, err = s.Update(foreign, p.ID, planInput())
	requirePlanStatus(t, err, 404)
	requirePlanStatus(t, s.Delete(foreign, p.ID), 404)
	_, err = s.Copy(foreign, p.ID)
	requirePlanStatus(t, err, 404)
	page, err := s.List(foreign, types.EvaluationPlanQuery{})
	require.NoError(t, err)
	require.Empty(t, page.Items)
	key := context.WithValue(alice, types.TenantAPIKeyScopeContextKey, types.TenantAPIKeyScope{})
	_, err = s.List(key, types.EvaluationPlanQuery{})
	requirePlanStatus(t, err, 403)
	for _, keyword := range []string{"AIA_100%", p.ID, "aia"} {
		page, err = s.List(alice, types.EvaluationPlanQuery{Keyword: keyword})
		require.NoError(t, err)
		require.Equal(t, int64(1), page.Total)
	}
	input := planInput()
	input.Name = "AIAx100something"
	_, err = s.Create(alice, input)
	require.NoError(t, err)
	page, err = s.List(alice, types.EvaluationPlanQuery{Keyword: "AIA_100%"})
	require.NoError(t, err)
	require.Equal(t, int64(1), page.Total)
	first, err := s.List(alice, types.EvaluationPlanQuery{Page: 1, PageSize: 1})
	require.NoError(t, err)
	second, err := s.List(alice, types.EvaluationPlanQuery{Page: 2, PageSize: 1})
	require.NoError(t, err)
	require.NotEqual(t, first.Items[0].ID, second.Items[0].ID)
	_, err = s.Get(alice, "not-guid")
	requirePlanStatus(t, err, 400)
	for _, q := range []types.EvaluationPlanQuery{{Page: -1}, {PageSize: 101}, {Page: 1000001}} {
		_, err = s.List(alice, q)
		requirePlanStatus(t, err, 400)
	}
}
func TestEvaluationPlanRejectsInvalidConfigurationAndModels(t *testing.T) {
	s, models, _ := newPlanFixture(t)
	ctx := planContext(1, "alice", types.TenantRoleContributor)
	cases := map[string]func(*types.EvaluationPlanInput){
		"name":    func(p *types.EvaluationPlanInput) { p.Name = "   " },
		"prompt":  func(p *types.EvaluationPlanInput) { p.QAGenerationPrompt = "\n" },
		"weights": func(p *types.EvaluationPlanInput) { p.ScoringDimensions[0].Weight = 99 },
		"duplicate dimensions": func(p *types.EvaluationPlanInput) {
			p.ScoringDimensions = append(p.ScoringDimensions, p.ScoringDimensions[0])
		},
		"dimension id":  func(p *types.EvaluationPlanInput) { p.ScoringDimensions[0].ID = "invalid id" },
		"score":         func(p *types.EvaluationPlanInput) { p.PassScore = 101 },
		"quantity":      func(p *types.EvaluationPlanInput) { p.QAGenerationRules.QuestionsPerChunk = 21 },
		"types":         func(p *types.EvaluationPlanInput) { p.QAGenerationRules.QuestionTypes = []string{"fact", "fact"} },
		"difficulty":    func(p *types.EvaluationPlanInput) { p.QAGenerationRules.Difficulty = "other" },
		"language":      func(p *types.EvaluationPlanInput) { p.QAGenerationRules.Language = "other" },
		"foreign model": func(p *types.EvaluationPlanInput) { p.EvaluationModelID = "foreign" },
	}
	models.rows["foreign"] = &types.Model{TenantID: 2, Type: types.ModelTypeKnowledgeQA, Status: types.ModelStatusActive}
	for name, mutate := range cases {
		t.Run(name, func(t *testing.T) {
			input := planInput()
			mutate(&input)
			_, err := s.Create(ctx, input)
			requirePlanStatus(t, err, 400)
		})
	}
	p, err := s.Create(ctx, planInput())
	require.NoError(t, err)
	delete(models.rows, "chat")
	_, err = s.Get(ctx, p.ID)
	require.NoError(t, err) // A missing model never hides stored configuration.
	_, err = s.Update(ctx, p.ID, planInput())
	requirePlanStatus(t, err, 400)
	_, err = s.Copy(ctx, p.ID)
	requirePlanStatus(t, err, 400)
	for _, model := range []*types.Model{{TenantID: 1, Type: types.ModelTypeEmbedding, Status: types.ModelStatusActive}, {TenantID: 1, Type: types.ModelTypeKnowledgeQA, Status: types.ModelStatusDownloading}} {
		models.rows["chat"] = model
		_, err = s.Create(ctx, planInput())
		requirePlanStatus(t, err, 400)
	}
	// The request DTO has no identity fields, even when the domain object has them.
	encoded, err := json.Marshal(planInput())
	require.NoError(t, err)
	require.NotContains(t, string(encoded), "created_by")
}
