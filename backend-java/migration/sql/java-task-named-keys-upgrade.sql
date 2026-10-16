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
