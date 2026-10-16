package com.tencent.weknora.task;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Actual Asynq RDB enqueue/active/completion/retention/collection sequence, plus native SQL races.
 */
class NamedTaskProducerGoParityTest {
  TaskRepositoryTest f;
  NamedTaskProducer named;
  final ObjectMapper json = new ObjectMapper();

  @BeforeEach
  void setup() throws Exception {
    f = database();
    named = new NamedTaskProducer(f.jdbc, f.tasks, f.manager, f.clock);
  }

  @AfterEach
  void cleanup() throws Exception {
    f.cleanup();
  }

  TaskRepositoryTest database() throws Exception {
    var fixture = new TaskRepositoryTest();
    fixture.initialize();
    return fixture;
  }

  void rejectKeyInsertion() {
    f.jdbc.execute(
        "CREATE TRIGGER reject_named_key BEFORE INSERT ON java_task_named_keys BEGIN SELECT"
            + " RAISE(ABORT,'controlled named-key insert failure'); END");
  }

  @Test
  void productionAsynqNineStepRetentionSequence() throws Exception {
    JsonNode steps;
    String fixture = System.getProperty("weknora.move.named.fixture", "");
    if (!fixture.isEmpty())
      steps = json.readTree(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(fixture)));
    else
      try (var in = getClass().getResourceAsStream("/go-knowledge-move-named-queue.json")) {
        steps = json.readTree(in);
      }
    assertEquals(9, steps.size());
    TaskRecord active = null;
    for (var step : steps) {
      String name = step.path("step").asText(), queue = step.path("queue").asText();
      if (name.equals("active-conflict"))
        active =
            f.tasks.claim("move-worker", Duration.ofMinutes(5), List.of("default")).orElseThrow();
      if (name.equals("completed-conflict"))
        assertTrue(f.tasks.complete(active, json.createObjectNode()));
      if (name.equals("expired-before-janitor-conflict"))
        f.clock.advance(Duration.ofDays(7).plusSeconds(1));
      if (name.equals("expired-after-janitor")) assertEquals(1, named.purgeExpired());
      if (name.equals("zero-completed-reusable")) {
        var zero =
            f.tasks.claim("zero-worker", Duration.ofMinutes(5), List.of("zero")).orElseThrow();
        assertTrue(f.tasks.complete(zero, json.createObjectNode()));
      }
      boolean conflict = false;
      try {
        named.enqueue(
            7,
            "manual:process",
            step.path("id").asText(),
            json.createObjectNode().put("tenant_id", 7),
            queue,
            3,
            Duration.ofMinutes(30),
            queue.equals("zero") ? Duration.ZERO : Duration.ofDays(7));
      } catch (NamedTaskProducer.Conflict duplicate) {
        conflict = true;
      }
      assertEquals(step.path("conflict").asBoolean(), conflict, name);
    }
    assertEquals(5, f.jdbc.queryForObject("SELECT COUNT(*) FROM java_task_jobs", Integer.class));
    assertEquals(
        3, f.jdbc.queryForObject("SELECT COUNT(*) FROM java_task_named_keys", Integer.class));
  }

  @Test
  void concurrentAdmissionCreatesOneJobAndNoOrphanJobs() throws Exception {
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(8);
    var results = new ArrayList<Future<Boolean>>();
    try {
      for (int i = 0; i < 8; i++)
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  try {
                    named.enqueue(
                        7,
                        "manual:process",
                        "same",
                        json.createObjectNode(),
                        "default",
                        3,
                        Duration.ofMinutes(30),
                        Duration.ofDays(7));
                    return true;
                  } catch (NamedTaskProducer.Conflict expected) {
                    return false;
                  }
                }));
      start.countDown();
      int accepted = 0;
      for (var result : results) if (result.get(10, TimeUnit.SECONDS)) accepted++;
      assertEquals(1, accepted);
      assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM java_task_jobs", Integer.class));
      assertEquals(
          1, f.jdbc.queryForObject("SELECT COUNT(*) FROM java_task_named_keys", Integer.class));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void idleJanitorDoesNotRequestTheWriterHeldByAnUnrelatedBusinessTransaction() throws Exception {
    var job =
        named.enqueue(
            7,
            "manual:process",
            "pending",
            json.createObjectNode(),
            "default",
            3,
            Duration.ofMinutes(30),
            Duration.ofDays(7));
    var thread = Executors.newSingleThreadExecutor();
    try {
      new TransactionTemplate(f.manager)
          .executeWithoutResult(
              status -> {
                f.jdbc.update(
                    "UPDATE java_task_jobs SET updated_at=updated_at WHERE id=?", job.id());
                var collection = thread.submit(named::purgeExpired);
                try {
                  assertEquals(0, collection.get(1, TimeUnit.SECONDS));
                } catch (Exception failure) {
                  throw new AssertionError(
                      "Idle collection competed for the business writer", failure);
                }
              });
    } finally {
      thread.shutdownNow();
      assertTrue(thread.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void failedOrRolledBackKeyAdmissionDoesNotLeaveAJob() {
    var tx = new TransactionTemplate(f.manager);
    assertThrows(
        IllegalStateException.class,
        () ->
            tx.executeWithoutResult(
                status -> {
                  try {
                    named.enqueue(
                        7,
                        "manual:process",
                        "rolled-back",
                        json.createObjectNode(),
                        "default",
                        3,
                        Duration.ofMinutes(30),
                        Duration.ofDays(7));
                  } catch (Exception failure) {
                    throw new AssertionError(failure);
                  }
                  throw new IllegalStateException("rollback");
                }));
    assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM java_task_jobs", Integer.class));
    assertEquals(
        0, f.jdbc.queryForObject("SELECT COUNT(*) FROM java_task_named_keys", Integer.class));
    rejectKeyInsertion();
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            named.enqueue(
                7,
                "manual:process",
                "failed",
                json.createObjectNode(),
                "default",
                3,
                Duration.ofMinutes(30),
                Duration.ofDays(7)));
    assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM java_task_jobs", Integer.class));
  }
}
