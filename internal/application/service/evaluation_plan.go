package service

import (
	"context"
	"errors"
	"github.com/Tencent/WeKnora/internal/application/repository"
	"github.com/Tencent/WeKnora/internal/config"
	apperrors "github.com/Tencent/WeKnora/internal/errors"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"github.com/google/uuid"
	"gorm.io/gorm"
	"math"
	"regexp"
	"strings"
	"unicode/utf8"
)

// These bounds match the form configuration in evaluationPlanConfig.ts.
const EvaluationPlanPromptLimit = 50000

var evaluationDimensionID = regexp.MustCompile(`^[a-zA-Z][a-zA-Z0-9_-]{0,63}$`)

type EvaluationPlanService struct {
	repo   *repository.EvaluationPlanRepository
	models interfaces.ModelRepository
	audit  interfaces.AuditLogService
	cfg    *config.Config
}

func NewEvaluationPlanService(repo *repository.EvaluationPlanRepository, models interfaces.ModelRepository, audit interfaces.AuditLogService, cfg *config.Config) *EvaluationPlanService {
	return &EvaluationPlanService{repo: repo, models: models, audit: audit, cfg: cfg}
}
func (s *EvaluationPlanService) identity(ctx context.Context) (types.Caller, error) {
	c := types.CallerFromContext(ctx).Normalize()
	tenant, ok := types.TenantIDFromContext(ctx)
	if !ok || tenant == 0 || c.TenantID != tenant || c.UserID == "" {
		return c, apperrors.NewUnauthorizedError("authenticated workspace user required")
	}
	if _, key := types.TenantAPIKeyScopeFromContext(ctx); key {
		return c, apperrors.NewForbiddenError("evaluation plans require a user session")
	}
	return c, nil
}
func (s *EvaluationPlanService) superuser(ctx context.Context) bool {
	u, ok := ctx.Value(types.UserContextKey).(*types.User)
	return ok && u != nil && u.CanAccessAllTenants && s.cfg != nil && s.cfg.Tenant != nil && s.cfg.Tenant.EnableCrossTenantAccess
}
func (s *EvaluationPlanService) canCreate(ctx context.Context, c types.Caller) bool {
	return c.Role.HasPermission(types.TenantRoleContributor) || s.superuser(ctx)
}
func (s *EvaluationPlanService) canManage(ctx context.Context, c types.Caller, p *types.EvaluationPlan) bool {
	return c.UserID == p.CreatedBy || c.Role.HasPermission(types.TenantRoleAdmin) || s.superuser(ctx)
}
func (s *EvaluationPlanService) validate(ctx context.Context, c types.Caller, p *types.EvaluationPlanInput) error {
	if err := ValidateEvaluationPlan(p); err != nil {
		return err
	}
	for _, id := range []string{p.QAGenerationModelID, p.EvaluationModelID} {
		m, err := s.models.GetByID(ctx, c.TenantID, id)
		if err != nil {
			return err
		}
		if m == nil || (!m.IsBuiltin && m.TenantID != c.TenantID) || m.Type != types.ModelTypeKnowledgeQA || m.Status != types.ModelStatusActive {
			return apperrors.NewBadRequestError("select an available text generation model in this workspace")
		}
	}
	return nil
}

// ValidateEvaluationPlan validates data without rendering prompts or calling a model.
func ValidateEvaluationPlan(p *types.EvaluationPlanInput) error {
	bad := func(field string) error {
		return apperrors.NewBadRequestError("invalid evaluation plan configuration").WithDetails(map[string]string{"field": field})
	}
	p.Name = strings.TrimSpace(p.Name)
	if n := utf8.RuneCountInString(p.Name); n < 1 || n > 100 {
		return bad("name")
	}
	if utf8.RuneCountInString(p.Description) > 1000 {
		return bad("description")
	}
	for field, v := range map[string]string{"qa_generation_prompt": p.QAGenerationPrompt, "answer_comparison_prompt": p.AnswerComparisonPrompt} {
		if strings.TrimSpace(v) == "" || utf8.RuneCountInString(v) > EvaluationPlanPromptLimit {
			return bad(field)
		}
	}
	if strings.TrimSpace(p.QAGenerationModelID) == "" || strings.TrimSpace(p.EvaluationModelID) == "" {
		return bad("model")
	}
	if math.IsNaN(p.PassScore) || math.IsInf(p.PassScore, 0) || p.PassScore < 0 || p.PassScore > 100 {
		return bad("pass_score")
	}
	if len(p.ScoringDimensions) < 1 || len(p.ScoringDimensions) > 20 {
		return bad("scoring_dimensions")
	}
	sum := 0
	seen := map[string]bool{}
	for _, d := range p.ScoringDimensions {
		if !evaluationDimensionID.MatchString(d.ID) || seen[d.ID] || strings.TrimSpace(d.Name) == "" || utf8.RuneCountInString(d.Name) > 100 || utf8.RuneCountInString(d.Description) > 2000 || d.Weight < 1 || d.Weight > 100 {
			return bad("scoring_dimensions")
		}
		seen[d.ID] = true
		sum += d.Weight
	}
	if sum != 100 {
		return bad("scoring_dimensions")
	}
	rules := p.QAGenerationRules
	if rules.QuestionsPerChunk < 1 || rules.QuestionsPerChunk > 20 {
		return bad("questions_per_chunk")
	}
	if rules.Difficulty != "easy" && rules.Difficulty != "medium" && rules.Difficulty != "hard" {
		return bad("difficulty")
	}
	if !map[string]bool{"zh-CN": true, "en-US": true, "ja-JP": true, "ko-KR": true, "ru-RU": true}[rules.Language] {
		return bad("language")
	}
	if len(rules.QuestionTypes) < 1 || len(rules.QuestionTypes) > 4 {
		return bad("question_types")
	}
	seen = map[string]bool{}
	for _, q := range rules.QuestionTypes {
		if !map[string]bool{"fact": true, "numeric": true, "explanation": true, "synthesis": true}[q] || seen[q] {
			return bad("question_types")
		}
		seen[q] = true
	}
	return nil
}
func (s *EvaluationPlanService) List(ctx context.Context, q types.EvaluationPlanQuery) (*types.EvaluationPlanPage, error) {
	c, err := s.identity(ctx)
	if err != nil {
		return nil, err
	}
	q.Keyword = strings.TrimSpace(q.Keyword)
	if q.Page == 0 {
		q.Page = 1
	}
	if q.PageSize == 0 {
		q.PageSize = 12
	}
	if q.Page < 1 || q.Page > 1000000 || q.PageSize < 1 || q.PageSize > 100 || utf8.RuneCountInString(q.Keyword) > 100 {
		return nil, apperrors.NewBadRequestError("invalid search or pagination")
	}
	page, err := s.repo.List(ctx, c.TenantID, q)
	if err != nil {
		return nil, err
	}
	for _, p := range page.Items {
		p.CanManage = s.canManage(ctx, c, p)
	}
	return page, nil
}
func (s *EvaluationPlanService) Get(ctx context.Context, id string) (*types.EvaluationPlan, error) {
	c, err := s.identity(ctx)
	if err != nil {
		return nil, err
	}
	parsed, err := uuid.Parse(id)
	if err != nil {
		return nil, apperrors.NewBadRequestError("invalid plan GUID")
	}
	p, err := s.repo.Get(ctx, c.TenantID, parsed.String())
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, apperrors.NewNotFoundError("evaluation plan not found")
	}
	if err != nil {
		return nil, err
	}
	p.CanManage = s.canManage(ctx, c, p)
	return p, nil
}
func (s *EvaluationPlanService) Create(ctx context.Context, input types.EvaluationPlanInput) (*types.EvaluationPlan, error) {
	return s.create(ctx, input, "evaluation_plan.created")
}
func (s *EvaluationPlanService) create(ctx context.Context, input types.EvaluationPlanInput, action types.AuditAction) (*types.EvaluationPlan, error) {
	c, err := s.identity(ctx)
	if err != nil {
		return nil, err
	}
	if !s.canCreate(ctx, c) {
		return nil, apperrors.NewForbiddenError("contributor role required")
	}
	if err = s.validate(ctx, c, &input); err != nil {
		return nil, err
	}
	p := &types.EvaluationPlan{TenantID: c.TenantID, CreatedBy: c.UserID, EvaluationPlanInput: input}
	if err = s.repo.Create(ctx, p); err != nil {
		return nil, err
	}
	s.log(ctx, c, p, action)
	return s.Get(ctx, p.ID)
}
func (s *EvaluationPlanService) Update(ctx context.Context, id string, input types.EvaluationPlanInput) (*types.EvaluationPlan, error) {
	p, err := s.Get(ctx, id)
	if err != nil {
		return nil, err
	}
	c, _ := s.identity(ctx)
	if !p.CanManage {
		return nil, apperrors.NewForbiddenError("only the creator or an administrator may edit this plan")
	}
	if err = s.validate(ctx, c, &input); err != nil {
		return nil, err
	}
	p.EvaluationPlanInput = input
	if err = s.repo.Update(ctx, p); err != nil {
		return nil, err
	}
	s.log(ctx, c, p, "evaluation_plan.updated")
	return s.Get(ctx, p.ID)
}
func (s *EvaluationPlanService) Delete(ctx context.Context, id string) error {
	p, err := s.Get(ctx, id)
	if err != nil {
		return err
	}
	if !p.CanManage {
		return apperrors.NewForbiddenError("only the creator or an administrator may delete this plan")
	}
	c, _ := s.identity(ctx)
	if err = s.repo.Delete(ctx, c.TenantID, p.ID); err != nil {
		return err
	}
	s.log(ctx, c, p, "evaluation_plan.deleted")
	return nil
}
func (s *EvaluationPlanService) Copy(ctx context.Context, id string) (*types.EvaluationPlan, error) {
	p, err := s.Get(ctx, id)
	if err != nil {
		return nil, err
	}
	input := p.EvaluationPlanInput
	name := []rune(input.Name)
	if len(name) > 96 {
		name = name[:96]
	}
	input.Name = string(name) + "（副本）"
	return s.create(ctx, input, "evaluation_plan.copied")
}
func (s *EvaluationPlanService) log(ctx context.Context, c types.Caller, p *types.EvaluationPlan, action types.AuditAction) {
	if s.audit != nil {
		_ = s.audit.Log(ctx, &types.AuditLog{TenantID: c.TenantID, ActorUserID: c.UserID, ActorRole: string(c.Role), Action: action, TargetType: "evaluation_plan", TargetID: p.ID, Outcome: types.AuditOutcomeSuccess})
	}
}
