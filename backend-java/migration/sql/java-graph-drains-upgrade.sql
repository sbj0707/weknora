-- Explicit, idempotent upgrade; never executed by repository constructors.
CREATE TABLE IF NOT EXISTS java_graph_task_drains (
    task_id VARCHAR(36) PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    knowledge_id VARCHAR(36) NOT NULL,
    attempt BIGINT NOT NULL
);
