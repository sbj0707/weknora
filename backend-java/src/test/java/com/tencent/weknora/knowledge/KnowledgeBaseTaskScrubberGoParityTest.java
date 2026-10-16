package com.tencent.weknora.knowledge;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.model.client.CallContext;
import com.tencent.weknora.task.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class KnowledgeBaseTaskScrubberGoParityTest {
  @TempDir Path temp;
  final ObjectMapper json = new ObjectMapper();
  JdbcTemplate jdbc;
  TaskRepository tasks;
  KnowledgeBaseTaskScrubber scrubber;

  @BeforeEach void database() throws Exception {
    var source = new DriverManagerDataSource("jdbc:sqlite:" + temp.resolve("tasks.db"));
    source.setDriverClassName("org.sqlite.JDBC");
    jdbc = new JdbcTemplate(source);
    tasks = new TaskRepository(jdbc, json, new DataSourceTransactionManager(source));
    scrubber = new KnowledgeBaseTaskScrubber(jdbc, json, Clock.systemUTC());
    try (var connection = source.getConnection()) {
      ScriptUtils.executeSqlScript(connection, new FileSystemResource("migration/sql/java-tasks.sql"));
      ScriptUtils.executeSqlScript(connection, new FileSystemResource("../migrations/sqlite/000006_task_queue_and_dead_letters.up.sql"));
    }
  }

  @TestFactory Stream<DynamicTest> actualGoScopeDecisions() throws Exception {
    JsonNode rows;
    try (var input = getClass().getResourceAsStream("/go-knowledge-base-task-scope.json")) { rows = json.readTree(input); }
    assertEquals(342, rows.size());
    var scopes = new KnowledgeBaseTaskScope(json);
    return java.util.stream.StreamSupport.stream(rows.spliterator(), false).map(row -> DynamicTest.dynamicTest(row.path("name").asText(), () ->
        assertEquals(row.path("matches").asBoolean(), scopes.matches(row.path("type").asText(), row.path("raw").asText(), row.path("kb").asText(), ids(row.path("documents")), ids(row.path("sources"))))));
  }

  private Set<String> ids(JsonNode rows) { var ids = new HashSet<String>(); for (var value : rows) ids.add(value.asText()); return ids; }
  private TaskRecord job(long owner, String type, String queue, String raw) throws Exception {
    var record = tasks.enqueue(owner, type, null, json.createObjectNode(), 4, Duration.ZERO, queue, Duration.ofHours(2));
    jdbc.update("UPDATE java_task_jobs SET payload=? WHERE id=?", raw, record.id());
    return record;
  }
  private TaskRecord job(String type, String raw) throws Exception { return job(1, type, "default", raw); }
  private String state(TaskRecord record) { return jdbc.queryForObject("SELECT status FROM java_task_jobs WHERE id=?", String.class, record.id()); }
  private void pending(String scope, String id) { jdbc.update("INSERT INTO task_pending_ops(tenant_id,task_type,scope,scope_id,op) VALUES(1,'wiki:ingest',?,?,'upsert')", scope, id); }
  private void cancel() { try (var call = CallContext.timeout(Duration.ofSeconds(5))) { scrubber.scrub("kb", List.of("doc"), List.of("source"), call); } }

  @Test void scansMultiplePagesAcrossQueueStatesAndPreservesCleanupHistoryAndOtherQueues() throws Exception {
    for (int i = 0; i < 1207; i++) {
      var row = job(i % 2 + 1, "document:process", i % 2 == 0 ? "default" : "multimodal", "{\"knowledge_base_id\":\"kb\"}");
      if (i % 3 == 0) jdbc.update("UPDATE java_task_jobs SET status='retry',available_at=? WHERE id=?", System.currentTimeMillis()+3600000, row.id());
      if (i % 3 == 1) jdbc.update("UPDATE java_task_jobs SET status='running',attempts=1,lease_owner='active',lease_until=? WHERE id=?", System.currentTimeMillis()+3600000, row.id());
    }
    var survivors = new ArrayList<TaskRecord>();
    for (String type : List.of("kb:delete", "index:delete")) survivors.add(job(type, "{\"knowledge_base_id\":\"kb\",\"knowledge_id\":\"doc\"}"));
    survivors.add(job(1, "document:process", "user-defined", "{\"knowledge_base_id\":\"kb\"}"));
    survivors.add(job("document:process", "{\"knowledge_base_id\":\"other\"}"));
    survivors.add(job("document:process", "{\"knowledge_base_id\":\"kb\",\"task_id\":7}"));
    for (String status : List.of("dead", "succeeded", "canceled")) {
      var row = job("document:process", "{\"knowledge_base_id\":\"kb\"}");
      jdbc.update("UPDATE java_task_jobs SET status=? WHERE id=?", status, row.id());
    }
    var clone = job("kb:clone", "{\"target_id\":\"kb\"}");
    var move = job("knowledge:move", "{\"source_kb_id\":\"kb\"}");
    var document = job("chunk:extract", "{\"knowledge_id\":\"doc\"}");
    var source = job("datasource:sync", "{\"data_source_id\":\"source\"}");
    try (var call = CallContext.timeout(Duration.ofSeconds(10))) { assertEquals(1211, scrubber.cancel("kb", List.of("doc", ""), List.of("source", ""), call)); }
    assertEquals(1211, jdbc.queryForObject("SELECT COUNT(*) FROM java_task_jobs WHERE cancel_requested", Integer.class));
    for (var row : survivors) assertEquals("pending", state(row));
    for (var row : List.of(clone, move, document, source)) assertEquals("canceled", state(row));
    try (var call = new CallContext()) { assertEquals(0, scrubber.cancel("kb", List.of("doc"), List.of("source"), call)); }
  }

  @Test void durableOperationsCommitBeforeQueueSweepAndOnlyForMatchingScope() throws Exception {
    pending("knowledge_base", "kb"); pending("knowledge_base", "other"); pending("knowledge", "kb");
    var row = job("document:process", "{\"knowledge_base_id\":\"kb\"}");
    jdbc.execute("CREATE TRIGGER pending_fence BEFORE UPDATE ON java_task_jobs WHEN (SELECT COUNT(*) FROM task_pending_ops WHERE scope='knowledge_base' AND scope_id='kb')>0 BEGIN SELECT RAISE(ABORT,'pending fence was not committed'); END");
    cancel();
    assertEquals("canceled", state(row));
    assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM task_pending_ops", Integer.class));
  }

  @Test void durableDeletionFailureDoesNotPreventQueueCancellation() throws Exception {
    pending("knowledge_base", "kb");
    jdbc.execute("CREATE TRIGGER pending_failure BEFORE DELETE ON task_pending_ops BEGIN SELECT RAISE(ABORT,'controlled pending failure'); END");
    var row = job("document:process", "{\"knowledge_base_id\":\"kb\"}"); cancel();
    assertEquals("canceled", state(row));
    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM task_pending_ops", Integer.class));
  }

  @Test void queueFailureDoesNotRestoreDurableOperations() throws Exception {
    pending("knowledge_base", "kb"); job("document:process", "{\"knowledge_base_id\":\"kb\"}");
    jdbc.execute("CREATE TRIGGER queue_failure BEFORE UPDATE ON java_task_jobs BEGIN SELECT RAISE(ABORT,'controlled queue failure'); END");
    assertThrows(RuntimeException.class, this::cancel);
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM task_pending_ops", Integer.class));
  }

  @Test void activeCancellationIsStickyThroughSettlementAndExpiredLeaseRecovery() throws Exception {
    var queued = job("document:process", "{\"knowledge_base_id\":\"kb\"}");
    var active = tasks.claim("active", Duration.ofSeconds(10)).orElseThrow(); cancel();
    assertTrue(tasks.canceledOrLeaseLost(active)); assertFalse(tasks.renew(active, Duration.ofSeconds(10)));
    assertTrue(tasks.fail(active, "context canceled", true, Duration.ZERO));
    assertEquals("canceled", state(queued)); assertTrue(tasks.claim("replacement", Duration.ofSeconds(10)).isEmpty());
    var expired = job("document:process", "{\"knowledge_base_id\":\"kb\"}");
    tasks.claim("expired", Duration.ofSeconds(10)).orElseThrow(); cancel();
    jdbc.update("UPDATE java_task_jobs SET lease_until=0 WHERE id=?", expired.id());
    assertTrue(tasks.claim("replacement", Duration.ofSeconds(10)).isEmpty()); assertEquals("canceled", state(expired));
  }

  @Test void canceledAndExpiredContextsStopActualSweepWithoutChangingQueue() throws Exception {
    var row = job("document:process", "{\"knowledge_base_id\":\"kb\"}");
    try (var call = new CallContext()) {
      call.cancel(); assertThrows(CancellationException.class, () -> scrubber.cancel("kb", List.of(), List.of(), call));
    }
    try (var call = new CallContext(Instant.now().minusSeconds(1))) {
      assertThrows(com.tencent.weknora.model.client.ModelClientException.class, () -> scrubber.cancel("kb", List.of(), List.of(), call));
    }
    assertEquals("pending", state(row));
  }
}
