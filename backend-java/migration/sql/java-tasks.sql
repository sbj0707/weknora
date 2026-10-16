-- Explicit, opt-in Java task schema. Run only on an isolated copy until ownership cutover.
-- Compatible with PostgreSQL and SQLite; all timestamps are UTC epoch milliseconds.
-- This is a new task backend, not an Asynq wire-format implementation.
CREATE TABLE java_task_jobs (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    type VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(256) NOT NULL,
    queue VARCHAR(128) NOT NULL DEFAULT 'default',
    timeout_millis BIGINT NOT NULL DEFAULT 0 CHECK(timeout_millis>=0),
    payload TEXT NOT NULL,
    status VARCHAR(16) NOT NULL CHECK(status IN ('pending','running','retry','succeeded','canceled','dead')),
    attempts INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL,
    available_at BIGINT NOT NULL,
    lease_owner VARCHAR(128),
    lease_until BIGINT,
    cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    result TEXT,
    last_error TEXT,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    UNIQUE(tenant_id,type,idempotency_key)
);
CREATE INDEX java_task_jobs_claim ON java_task_jobs(queue,status,available_at,lease_until);
CREATE INDEX java_task_jobs_tenant ON java_task_jobs(tenant_id,created_at);
CREATE TABLE java_task_attempts (
    task_id VARCHAR(36) NOT NULL,
    attempt INTEGER NOT NULL,
    lease_owner VARCHAR(128) NOT NULL,
    started_at BIGINT NOT NULL,
    finished_at BIGINT,
    outcome VARCHAR(16),
    error TEXT,
    PRIMARY KEY(task_id,attempt),
    FOREIGN KEY(task_id) REFERENCES java_task_jobs(id)
);
CREATE TABLE java_task_dead_letters (
    task_id VARCHAR(36) PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    type VARCHAR(128) NOT NULL,
    payload TEXT NOT NULL,
    error TEXT NOT NULL,
    failed_at BIGINT NOT NULL,
    FOREIGN KEY(task_id) REFERENCES java_task_jobs(id)
);

-- Terminal summary deliveries release their parent slot at most once.
CREATE TABLE java_summary_task_drains (
    task_id VARCHAR(36) PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    knowledge_id VARCHAR(36) NOT NULL,
    attempt BIGINT NOT NULL
);
-- Explicit upgrade for existing Java task databases. Never executed by a repository constructor.
CREATE TABLE IF NOT EXISTS java_question_task_drains (
    task_id VARCHAR(36) PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    knowledge_id VARCHAR(36) NOT NULL,
    attempt BIGINT NOT NULL
);
-- Explicit, idempotent upgrade; never executed by repository constructors.
CREATE TABLE IF NOT EXISTS java_graph_task_drains (
    task_id VARCHAR(36) PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    knowledge_id VARCHAR(36) NOT NULL,
    attempt BIGINT NOT NULL
);
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

-- Explicit upgrade for named, queue-scoped task admission and completed-task retention.
-- No application constructor runs this DDL. Existing UUID queue keys are unchanged.
CREATE TABLE IF NOT EXISTS java_task_named_keys (
    queue VARCHAR(128) NOT NULL,
    business_id TEXT NOT NULL,
    task_id VARCHAR(36) NOT NULL,
    retention_millis BIGINT NOT NULL CHECK(retention_millis>=0),
    PRIMARY KEY(queue,business_id),
    FOREIGN KEY(task_id) REFERENCES java_task_jobs(id) ON DELETE CASCADE
);
