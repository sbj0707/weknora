-- Explicit native image fan-in upgrade. Application constructors never execute DDL.
CREATE TABLE IF NOT EXISTS java_image_task_pending (
    tenant_id BIGINT NOT NULL,
    knowledge_id VARCHAR(36) NOT NULL,
    attempt BIGINT NOT NULL,
    pending_count BIGINT NOT NULL,
    handoff_done BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY(tenant_id,knowledge_id)
);
CREATE TABLE IF NOT EXISTS java_image_task_drains (
    task_id VARCHAR(36) PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    knowledge_id VARCHAR(36) NOT NULL,
    attempt BIGINT NOT NULL
);
