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
