-- Explicit upgrade for existing Java task databases. Never executed by a repository constructor.
CREATE TABLE IF NOT EXISTS java_question_task_drains (
    task_id VARCHAR(36) PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    knowledge_id VARCHAR(36) NOT NULL,
    attempt BIGINT NOT NULL
);
