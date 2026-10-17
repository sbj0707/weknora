package types

import (
	"github.com/google/uuid"
	"gorm.io/gorm"
	"time"
)

// EvaluationPlanInput is the complete editable configuration. Identity and audit
// fields are deliberately absent: clients cannot change workspace or ownership.
type EvaluationPlanInput struct {
	Name                   string                `json:"name" gorm:"type:varchar(100);not null"`
	Description            string                `json:"description" gorm:"type:text;not null"`
	QAGenerationPrompt     string                `json:"qa_generation_prompt" gorm:"type:text;not null"`
	AnswerComparisonPrompt string                `json:"answer_comparison_prompt" gorm:"type:text;not null"`
	QAGenerationModelID    string                `json:"qa_generation_model_id" gorm:"type:varchar(64);not null"`
	EvaluationModelID      string                `json:"evaluation_model_id" gorm:"type:varchar(64);not null"`
	ScoringDimensions      []EvaluationDimension `json:"scoring_dimensions" gorm:"serializer:json;type:jsonb;not null"`
	PassScore              float64               `json:"pass_score" gorm:"not null"`
	QAGenerationRules      QAGenerationRules     `json:"qa_generation_rules" gorm:"serializer:json;type:jsonb;not null"`
}

type EvaluationDimension struct {
	ID          string `json:"id"`
	Name        string `json:"name"`
	Description string `json:"description"`
	Weight      int    `json:"weight"`
}

type QAGenerationRules struct {
	QuestionsPerChunk int      `json:"questions_per_chunk"`
	QuestionTypes     []string `json:"question_types"`
	Difficulty        string   `json:"difficulty"`
	Language          string   `json:"language"`
}

type EvaluationPlan struct {
	ID                  string `json:"id" gorm:"type:varchar(36);primaryKey"`
	TenantID            uint64 `json:"-" gorm:"not null;index:idx_evaluation_plans_tenant_updated,priority:1"`
	EvaluationPlanInput `gorm:"embedded"`
	CreatedBy           string         `json:"created_by" gorm:"type:varchar(36);not null"`
	CreatorName         string         `json:"creator_name" gorm:"->;-:migration"`
	CanManage           bool           `json:"can_manage" gorm:"-"`
	CreatedAt           time.Time      `json:"created_at"`
	UpdatedAt           time.Time      `json:"updated_at" gorm:"index:idx_evaluation_plans_tenant_updated,priority:2,sort:desc"`
	DeletedAt           gorm.DeletedAt `json:"-" gorm:"index"`
}

func (EvaluationPlan) TableName() string { return "evaluation_plans" }
func (p *EvaluationPlan) BeforeCreate(_ *gorm.DB) error {
	if p.ID == "" {
		p.ID = uuid.NewString()
	}
	return nil
}

type EvaluationPlanQuery struct {
	Keyword  string `form:"keyword"`
	Page     int    `form:"page"`
	PageSize int    `form:"page_size"`
}

type EvaluationPlanPage struct {
	Items    []*EvaluationPlan `json:"items"`
	Total    int64             `json:"total"`
	Page     int               `json:"page"`
	PageSize int               `json:"page_size"`
}
