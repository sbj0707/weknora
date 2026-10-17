CREATE TABLE evaluation_plans (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    qa_generation_prompt TEXT NOT NULL,
    answer_comparison_prompt TEXT NOT NULL,
    qa_generation_model_id VARCHAR(64) NOT NULL,
    evaluation_model_id VARCHAR(64) NOT NULL,
    scoring_dimensions TEXT NOT NULL,
    pass_score DOUBLE PRECISION NOT NULL CHECK (pass_score >= 0 AND pass_score <= 100),
    qa_generation_rules TEXT NOT NULL,
    created_by VARCHAR(36) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at DATETIME
);
CREATE INDEX idx_evaluation_plans_tenant_updated ON evaluation_plans (tenant_id, updated_at DESC, id DESC) WHERE deleted_at IS NULL;
CREATE INDEX idx_evaluation_plans_tenant_name ON evaluation_plans (tenant_id, name) WHERE deleted_at IS NULL;
