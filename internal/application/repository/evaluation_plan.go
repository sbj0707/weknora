package repository

import (
	"context"
	"github.com/Tencent/WeKnora/internal/types"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
	"strings"
)

// EvaluationPlanRepository scopes every read and write to the active workspace.
type EvaluationPlanRepository struct{ db *gorm.DB }

func NewEvaluationPlanRepository(db *gorm.DB) *EvaluationPlanRepository {
	return &EvaluationPlanRepository{db: db}
}
func (r *EvaluationPlanRepository) Create(ctx context.Context, p *types.EvaluationPlan) error {
	return r.db.WithContext(ctx).Session(&gorm.Session{Logger: logger.Default.LogMode(logger.Silent)}).Create(p).Error
}
func (r *EvaluationPlanRepository) query(ctx context.Context, tenantID uint64) *gorm.DB {
	return r.db.WithContext(ctx).Model(&types.EvaluationPlan{}).
		Select("evaluation_plans.*, COALESCE(plan_creator.username, '') AS creator_name").
		Joins("LEFT JOIN users AS plan_creator ON plan_creator.id = evaluation_plans.created_by AND plan_creator.deleted_at IS NULL").
		Where("evaluation_plans.tenant_id = ?", tenantID)
}
func (r *EvaluationPlanRepository) Get(ctx context.Context, tenantID uint64, id string) (*types.EvaluationPlan, error) {
	var row types.EvaluationPlan
	err := r.query(ctx, tenantID).Where("evaluation_plans.id = ?", id).First(&row).Error
	return &row, err
}
func (r *EvaluationPlanRepository) List(ctx context.Context, tenantID uint64, q types.EvaluationPlanQuery) (*types.EvaluationPlanPage, error) {
	db := r.query(ctx, tenantID)
	if q.Keyword != "" {
		pattern := "%" + escapeLikeKeyword(strings.ToLower(q.Keyword)) + "%"
		db = db.Where("(LOWER(evaluation_plans.name) LIKE ? ESCAPE ? OR LOWER(evaluation_plans.id) LIKE ? ESCAPE ?)", pattern, likeEscapeChar, pattern, likeEscapeChar)
	}
	result := &types.EvaluationPlanPage{Items: make([]*types.EvaluationPlan, 0), Page: q.Page, PageSize: q.PageSize}
	if err := db.Count(&result.Total).Error; err != nil {
		return nil, err
	}
	err := db.Order("evaluation_plans.updated_at DESC, evaluation_plans.id DESC").Offset((q.Page - 1) * q.PageSize).Limit(q.PageSize).Find(&result.Items).Error
	return result, err
}
func (r *EvaluationPlanRepository) Update(ctx context.Context, p *types.EvaluationPlan) error {
	// Prompts may contain sensitive source text; never emit SQL with bound values.
	result := r.db.WithContext(ctx).Session(&gorm.Session{Logger: logger.Default.LogMode(logger.Silent)}).Model(&types.EvaluationPlan{}).Where("tenant_id = ? AND id = ?", p.TenantID, p.ID).
		Select("Name", "Description", "QAGenerationPrompt", "AnswerComparisonPrompt", "QAGenerationModelID", "EvaluationModelID", "ScoringDimensions", "PassScore", "QAGenerationRules", "UpdatedAt").Updates(p)
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return gorm.ErrRecordNotFound
	}
	return nil
}
func (r *EvaluationPlanRepository) Delete(ctx context.Context, tenantID uint64, id string) error {
	result := r.db.WithContext(ctx).Where("tenant_id = ? AND id = ?", tenantID, id).Delete(&types.EvaluationPlan{})
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return gorm.ErrRecordNotFound
	}
	return nil
}
