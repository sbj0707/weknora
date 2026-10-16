package com.tencent.weknora.task;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Business task IDs are scoped to their queue; SQL delivery keeps its existing UUID fencing token.
 */
public final class NamedTaskProducer {
  public static final class Conflict extends Exception {
    public Conflict() {
      super("task ID conflicts with another task");
    }
  }

  private final JdbcTemplate jdbc;
  private final TaskRepository tasks;
  private final TransactionTemplate transaction;
  private final Clock clock;
  private final boolean postgres;

  public NamedTaskProducer(
      JdbcTemplate jdbc, TaskRepository tasks, PlatformTransactionManager manager, Clock clock) {
    this.jdbc = jdbc;
    this.tasks = tasks;
    this.transaction = new TransactionTemplate(manager);
    this.clock = clock;
    postgres =
        Boolean.TRUE.equals(
            jdbc.execute(
                (ConnectionCallback<Boolean>)
                    c -> c.getMetaData().getDatabaseProductName().equals("PostgreSQL")));
  }

  public TaskRecord enqueue(
      long owner,
      String type,
      String businessId,
      JsonNode payload,
      String queue,
      int retries,
      Duration timeout,
      Duration retention)
      throws Conflict {
    if (businessId == null
        || businessId.isEmpty()
        || queue == null
        || queue.isEmpty()
        || retention.isNegative()
        || retries < 0) throw new IllegalArgumentException("Invalid named task options");
    try {
      return transaction.execute(
          status -> {
            // SQLite must acquire its writer before reading the key, avoiding a deferred lock
            // upgrade.
            if (!postgres) jdbc.update("UPDATE java_task_jobs SET updated_at=updated_at WHERE 1=0");
            // The unique key serializes first admission. A conflicting insert aborts this
            // transaction;
            // callers observe the same conflict for pending, active, retry and retained completed
            // jobs.
            var keys =
                jdbc.queryForList(
                    "SELECT n.task_id,n.retention_millis,j.status,j.updated_at FROM"
                        + " java_task_named_keys n JOIN java_task_jobs j ON j.id=n.task_id WHERE"
                        + " n.queue=? AND n.business_id=?"
                        + (postgres ? " FOR UPDATE OF n" : ""),
                    queue,
                    businessId);
            if (!keys.isEmpty()) {
              var old = keys.getFirst();
              long kept = ((Number) old.get("retention_millis")).longValue() / 1000;
              // Asynq retains the hash even after its score expires, until the janitor deletes it.
              // Retention zero uses immediate acknowledgement deletion instead.
              boolean expired = "succeeded".equals(old.get("status")) && kept == 0;
              if (!expired) throw new Existing();
              jdbc.update(
                  "DELETE FROM java_task_named_keys WHERE queue=? AND business_id=?",
                  queue,
                  businessId);
            }
            var accepted =
                tasks.enqueue(
                    owner,
                    type,
                    null,
                    payload,
                    Math.addExact(retries, 1),
                    Duration.ZERO,
                    queue,
                    timeout);
            jdbc.update(
                "INSERT INTO java_task_named_keys(queue,business_id,task_id,retention_millis)"
                    + " VALUES(?,?,?,?)",
                queue,
                businessId,
                accepted.id(),
                retention.toMillis());
            return accepted;
          });
    } catch (Existing | DuplicateKeyException conflict) {
      throw new Conflict();
    }
  }

  /** Release completed business IDs only after the retained deadline and a janitor pass. */
  public int purgeExpired() {
    // An idle janitor must not reserve SQLite's single writer. In particular, doing a no-op
    // UPDATE here can prevent an unrelated document/index transaction from upgrading its read
    // lock. A concurrent completion after this read is collected on the next scheduled pass.
    Long candidates =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM java_task_named_keys n JOIN java_task_jobs j ON j.id=n.task_id"
                + " WHERE j.status='succeeded' AND ? >= j.updated_at/1000+n.retention_millis/1000",
            Long.class,
            clock.instant().getEpochSecond());
    if (candidates == null || candidates == 0) return 0;
    return transaction.execute(
        status -> {
          if (!postgres) jdbc.update("UPDATE java_task_jobs SET updated_at=updated_at WHERE 1=0");
          int removed = 0;
          var expired =
              jdbc.queryForList(
                  "SELECT n.queue,n.business_id,n.task_id,n.retention_millis,j.updated_at FROM"
                      + " java_task_named_keys n JOIN java_task_jobs j ON j.id=n.task_id WHERE"
                      + " j.status='succeeded'"
                      + (postgres ? " FOR UPDATE OF n" : ""));
          for (var key : expired)
            if (clock.instant().getEpochSecond()
                >= ((Number) key.get("updated_at")).longValue() / 1000
                    + ((Number) key.get("retention_millis")).longValue() / 1000)
              removed +=
                  jdbc.update(
                      "DELETE FROM java_task_named_keys WHERE queue=? AND business_id=? AND"
                          + " task_id=?",
                      key.get("queue"),
                      key.get("business_id"),
                      key.get("task_id"));
          return removed;
        });
  }

  private static final class Existing extends RuntimeException {}
}
