package com.tencent.weknora.knowledge;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.weknora.task.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

/**
 * Actual Go ProcessKnowledgeMove replay: whole-batch preflight, SQL state and ordered external
 * events.
 */
class KnowledgeMoveWorkerGoParityTest {
  @TempDir Path temp;
  final ObjectMapper json = new ObjectMapper();
  int sequence;

  @TestFactory
  Stream<DynamicTest> worker() throws Exception {
    JsonNode rows;
    String path = System.getProperty("weknora.move.worker.fixture", "");
    if (!path.isEmpty()) rows = json.readTree(Files.readAllBytes(Path.of(path)));
    else
      try (var in = getClass().getResourceAsStream("/go-knowledge-move-worker.json")) {
        rows = json.readTree(in);
      }
    var tests = new ArrayList<DynamicTest>();
    for (var c : rows) tests.add(DynamicTest.dynamicTest(c.path("name").asText(), () -> replay(c)));
    assertEquals(46, tests.size());
    return tests.stream();
  }

  void replay(JsonNode c) throws Exception {
    var f = database();
    for (String table :
        List.of("chunks", "knowledges", "knowledge_tag_relations", "knowledge_tags", "tenants"))
      f.jdbc.update("DELETE FROM " + table);
    for (var sql : c.path("seed")) f.jdbc.execute(sql.asText());
    var events = json.createArrayNode();
    ObjectNode[] stored = {null};
    int[] checks = {0};
    String name = c.path("name").asText();
    var progress =
        new KnowledgeMoveProgress() {
          public void initialize(ObjectNode p) {
            fail("worker must use overwrite, never SETNX");
          }

          public void save(ObjectNode p) {
            var copy = p.deepCopy().put("created_at", 0).put("updated_at", 0);
            events.add(json.createObjectNode().put("kind", "progress").set("value", copy));
            if (name.equals("progress-failure"))
              throw new IllegalStateException("controlled Redis failure");
            stored[0] = copy;
          }

          public ObjectNode get(String id) {
            return stored[0];
          }
        };
    var runtime =
        new KnowledgeMoveHandler.Runtime() {
          public JsonNode base(String id) {
            if (name.equals("missing-source") && id.equals("kb")
                || name.equals("missing-target") && id.equals("other"))
              throw new IllegalStateException("knowledge base not found");
            return c.path(id.equals("kb") ? "source" : "target");
          }

          public JsonNode tenant(long owner) {
            if (name.equals("tenant-failure"))
              throw new IllegalStateException("controlled tenant failure");
            return c.path("tenant").isNull() ? null : c.path("tenant");
          }

          public void validateEngine(long owner, JsonNode source) {
            checks[0]++;
            if (name.equals("engine-preflight-failure"))
              throw new IllegalStateException("controlled engine validation failure");
          }

          public void activity(
              ObjectNode payload, String kb, String action, String outcome, int count) {
            var initiator = payload.path("initiator");
            String actor = initiator.path("user_id").asText(""),
                role = actor.isEmpty() ? "" : initiator.path("role").asText("");
            var details =
                json.createObjectNode()
                    .put("source_kb_id", "kb")
                    .put("target_kb_id", "other")
                    .put("task_id", "transfer-task")
                    .put("count", count)
                    .put("mode", payload.path("mode").asText())
                    .put("trigger", actor.isEmpty() ? "system" : "user")
                    .put(
                        "processing_status",
                        switch (outcome) {
                          case "accepted" -> "pending";
                          case "success" -> "completed";
                          case "partial" -> "partial";
                          default -> "failed";
                        });
            if (initiator.path("api_key_id").asLong() > 0)
              details.set("api_key_id", initiator.get("api_key_id"));
            if (!initiator.path("api_key_name").asText("").isEmpty())
              details.set("api_key_name", initiator.get("api_key_name"));
            events.add(
                json.createObjectNode()
                    .put("kind", "audit")
                    .put("tenant", 7)
                    .put("actor", actor)
                    .put("role", role)
                    .put("action", action)
                    .put("target_type", "knowledge_move")
                    .put("target_id", "transfer-task")
                    .put("scope_id", kb)
                    .put("outcome", outcome)
                    .set("details", details));
          }

          public void profile(long owner, JsonNode base, ObjectNode payload) {}

          public KnowledgeMoveExecution.Effects effects(
              TaskRecord job, ObjectNode payload, Runnable guard) {
            return new KnowledgeMoveExecution.Effects() {
              public void requireTransfer(
                  KnowledgeTransferRepository.Scope scope, JsonNode a, JsonNode b) {}

              public void moveIndices(
                  Map<String, Object> row, JsonNode source, JsonNode target, List<String> chunks) {
                fail("no physical index call expected in this orchestration corpus");
              }

              public void deleteGraph(String source, String id) {
                events.add(
                    json.createObjectNode()
                        .put("kind", "delete_graph")
                        .put("source", source)
                        .put("id", id));
                if (name.startsWith("all-fail-") || name.startsWith("partial-") && id.equals("doc"))
                  throw new IllegalStateException("controlled graph failure");
              }

              public void cleanupResources(Map<String, Object> row, JsonNode source) {
                assertEquals(0L, KnowledgeTransferRepository.number(row, "storage_size"));
                f.jdbc.update(
                    "UPDATE chunks SET deleted_at=?,updated_at=? WHERE tenant_id=7 AND"
                        + " knowledge_id=? AND deleted_at IS NULL",
                    Instant.now().toString(),
                    Instant.now().toString(),
                    row.get("id"));
                deleteGraph(source.path("id").asText(), row.get("id").toString());
              }

              public void cleanupWiki(Map<String, Object> row, Set<String> chunks) {
                fail("not a Wiki corpus");
              }

              public void enqueue(KnowledgeMoveExecution.Submission task) {
                events.add(
                    json.createObjectNode()
                        .put("kind", "enqueue")
                        .put("type", task.type())
                        .put("id", task.id())
                        .put("queue", task.queue())
                        .put("max_retries", task.maxRetries())
                        .put("timeout_seconds", task.timeout().toSeconds())
                        .put("retention_seconds", task.retention().toSeconds())
                        .set("payload", task.payload()));
              }

              public void ingestWiki(long owner, String target, String id) {
                fail("not a Wiki corpus");
              }
            };
          }
        };
    var handler =
        new KnowledgeMoveHandler(
            new KnowledgeTransferRepository(f.jdbc, json, f.manager, Clock.systemUTC()),
            progress,
            json,
            runtime,
            Clock.systemUTC(),
            Duration.ofHours(2),
            new KnowledgeProcessingLanguage(new MockEnvironment()));
    var job =
        new TaskRecord(
            "sql-delivery",
            7,
            "knowledge:move",
            "named",
            json.createObjectNode(),
            "running",
            c.path("retry").asInt() + 1,
            c.path("max_retry").asInt() + 1,
            0,
            "test",
            Long.MAX_VALUE,
            false,
            null,
            null,
            0,
            0,
            "low",
            7200000);
    Exception failure = null;
    try {
      handler.execute(job, c.path("raw").asText(), () -> {});
    } catch (Exception error) {
      failure = error;
    }
    assertEquals(
        c.path("error").asText(), failure == null ? "" : failure.getMessage(), "exact Go error");
    assertEquals(
        c.path("skip_retry").asBoolean(),
        failure instanceof TaskFailure tf && !tf.retryable(),
        "Asynq SkipRetry");
    assertEquals(
        c.path("progress"),
        stored[0] == null ? json.nullNode() : json.readTree(stored[0].toString()),
        "Redis record");
    assertEquals(
        c.path("events"), json.readTree(events.toString()), "ordered audit/progress/side effects");
    assertEquals(c.path("engine_checks").asInt(), checks[0]);
    var docs =
        json.valueToTree(
            f.jdbc.queryForList(
                "SELECT id,tenant_id,knowledge_base_id,parse_status,CAST(metadata AS TEXT) AS"
                    + " metadata,storage_size,enable_status,description,COALESCE(error_message,'')"
                    + " AS error_message FROM knowledges ORDER BY id"));
    var chunks =
        json.valueToTree(
            f.jdbc.queryForList(
                "SELECT id,knowledge_id,knowledge_base_id,tag_id,deleted_at IS NOT NULL AS deleted"
                    + " FROM chunks ORDER BY id"));
    assertEquals(
        c.path("documents"), json.readTree(docs.toString()), "full selected SQL document state");
    assertEquals(
        c.path("chunks"), json.readTree(chunks.toString()), "all active/deleted chunk bindings");
  }

  DocumentProcessingCompatibilityTest database() throws Exception {
    var f = new DocumentProcessingCompatibilityTest();
    f.temp = temp;
    f.database(++sequence, false);
    return f;
  }
}
