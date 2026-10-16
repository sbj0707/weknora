-- Explicit upgrade for an isolated database with an existing Java task schema.
-- New databases use java-tasks.sql instead. No application bean creates this table.
CREATE TABLE IF NOT EXISTS java_summary_task_drains (
    task_id VARCHAR(36) PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    knowledge_id VARCHAR(36) NOT NULL,
    attempt BIGINT NOT NULL
);
