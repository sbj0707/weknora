-- Manual upgrade for isolated databases using the previous Java task schema.
-- Do not run together with java-tasks.sql on a newly initialized database.
-- No existing Go tables or Redis queues are modified.
ALTER TABLE java_task_jobs ADD COLUMN queue VARCHAR(128) NOT NULL DEFAULT 'default';
ALTER TABLE java_task_jobs ADD COLUMN timeout_millis BIGINT NOT NULL DEFAULT 0 CHECK(timeout_millis>=0);
DROP INDEX java_task_jobs_claim;
CREATE INDEX java_task_jobs_claim ON java_task_jobs(queue,status,available_at,lease_until);
