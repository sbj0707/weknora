package com.tencent.weknora.knowledge;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Actual Go per-document execution, independent SQL commits, retry and parser/ack races. */
class KnowledgeMoveExecutionGoParityTest {
  @TempDir Path temp;
  final ObjectMapper json = new ObjectMapper();
  private final AtomicInteger number = new AtomicInteger();

  @TestFactory
  Stream<DynamicTest> execution() throws Exception {
    JsonNode rows;
    String path = System.getProperty("weknora.move.execution.fixture", "");
    if (!path.isEmpty()) rows = json.readTree(Files.readAllBytes(Path.of(path)));
    else
      try (var in = getClass().getResourceAsStream("/go-knowledge-move-execution.json")) {
        rows = json.readTree(Objects.requireNonNull(in));
      }
    var tests = new ArrayList<DynamicTest>();
    for (var row : rows)
      tests.add(DynamicTest.dynamicTest(row.path("name").asText(), () -> execute(row)));
    assertThat(tests).hasSize(64);
    return tests.stream();
  }

  private void execute(JsonNode c) throws Exception {
    var f = database();
    for (var sql : c.path("seed_statements")) f.jdbc.execute(sql.asText());
    String name = c.path("name").asText(), mode = c.path("mode").asText();
    var scope = new KnowledgeTransferRepository.Scope(7, "transfer-task", "kb", "other");
    var repository = new KnowledgeTransferRepository(f.jdbc, json, f.manager, Clock.systemUTC());
    var effects = new ControlledEffects(f, c);
    var execution =
        new KnowledgeMoveExecution(
            repository,
            json,
            effects,
            json.createObjectNode(),
            Duration.ofSeconds(c.path("timeout_seconds").asLong()));
    if (name.startsWith("failure-bookkeeping-")) {
      execution.markFailed(
          scope,
          c.path("source"),
          c.path("target"),
          "doc",
          mode,
          new IllegalStateException("controlled final move failure"));
      assertStage(f, effects, null, c.path("stages").get(0));
      return;
    }
    Exception first =
        attempt(
            () ->
                execution.move(
                    scope, c.path("source"), c.path("target"), c.path("tenant"), "doc", mode));
    assertStage(f, effects, first, c.path("stages").get(0));
    int stage = 1;
    if (first != null) {
      execution.markFailed(scope, c.path("source"), c.path("target"), "doc", mode, first);
      assertStage(f, effects, null, c.path("stages").get(stage++));
      for (var sql : c.path("reset_statements")) f.jdbc.execute(sql.asText());
      effects.retry = true;
    }
    Exception retry =
        attempt(
            () ->
                execution.move(
                    scope, c.path("source"), c.path("target"), c.path("tenant"), "doc", mode));
    assertStage(f, effects, retry, c.path("stages").get(stage));
  }

  private void assertStage(
      DocumentProcessingCompatibilityTest f,
      ControlledEffects effects,
      Exception error,
      JsonNode expected)
      throws Exception {
    var snapshot = new LinkedHashMap<String, Object>();
    snapshot.put("error", error == null ? "" : error.getMessage());
    snapshot.put(
        "documents",
        f.jdbc.queryForList(
            "SELECT id,tenant_id,knowledge_base_id,type,parse_status,CAST(metadata AS TEXT) AS"
                + " metadata,file_path,file_name,file_type,embedding_model_id,storage_size,title,description,enable_status,COALESCE(error_message,'')"
                + " AS error_message,pending_subtasks_count FROM knowledges ORDER BY id"));
    snapshot.put(
        "chunks",
        f.jdbc.queryForList(
            "SELECT"
                + " id,tenant_id,knowledge_id,knowledge_base_id,chunk_type,tag_id,parent_chunk_id,content,deleted_at"
                + " IS NOT NULL AS deleted FROM chunks ORDER BY id"));
    snapshot.put(
        "relations",
        f.jdbc.queryForList(
            "SELECT knowledge_id,tag_id FROM knowledge_tag_relations ORDER BY"
                + " knowledge_id,tag_id"));
    snapshot.put(
        "storage_used",
        f.jdbc.queryForObject("SELECT storage_used FROM tenants WHERE id=7", Long.class));
    snapshot.put("events", effects.events);
    assertThat(json.readTree(json.writeValueAsBytes(snapshot))).isEqualTo(expected);
  }

  DocumentProcessingCompatibilityTest database() throws Exception {
    var f = new DocumentProcessingCompatibilityTest();
    f.temp = temp;
    f.database(number.incrementAndGet(), true);
    for (String table :
        List.of("chunks", "knowledges", "knowledge_tags", "knowledge_tag_relations", "tenants"))
      f.jdbc.execute("DELETE FROM " + table);
    return f;
  }

  final class ControlledEffects implements KnowledgeMoveExecution.Effects {
    private final DocumentProcessingCompatibilityTest f;
    private final JsonNode scenario;
    final List<Object> events = new ArrayList<>();
    boolean retry;

    ControlledEffects(DocumentProcessingCompatibilityTest f, JsonNode scenario) {
      this.f = f;
      this.scenario = scenario;
    }

    @Override
    public void requireTransfer(
        KnowledgeTransferRepository.Scope scope, JsonNode source, JsonNode target) {}

    @Override
    public void moveIndices(
        Map<String, Object> row, JsonNode source, JsonNode target, List<String> ids)
        throws Exception {
      events.add(
          Map.of(
              "kind",
              "move_indices",
              "source",
              "kb",
              "target",
              "other",
              "id",
              "doc",
              "chunks",
              ids,
              "dimensions",
              1,
              "kb_type",
              "document"));
      if (failure("engine-failure")) throw new IllegalStateException("controlled index failure");
    }

    @Override
    public void deleteGraph(String source, String id) {
      if (scenario.path("name").asText().equals("reuse-no-graph")) return;
      events.add(Map.of("kind", "delete_graph", "source", source, "id", id));
      if (failure("graph-failure")) throw new IllegalStateException("controlled graph failure");
    }

    @Override
    public void cleanupResources(Map<String, Object> row, JsonNode source) throws Exception {
      assertThat(KnowledgeTransferRepository.number(row, "storage_size")).isZero();
      var failures = new ArrayList<String>();
      if (!KnowledgeTransferRepository.text(row, "embedding_model_id").isEmpty()) {
        events.add(
            Map.of(
                "kind",
                "delete_indices",
                "ids",
                List.of("doc"),
                "dimensions",
                1,
                "kb_type",
                KnowledgeTransferRepository.text(row, "type")));
        if (failure("engine-failure")) failures.add("controlled index failure");
      }
      if (failure("image-read-failure")) failures.add("controlled image read failure");
      try {
        f.jdbc.update(
            "UPDATE chunks SET deleted_at=?,updated_at=? WHERE tenant_id=7 AND knowledge_id='doc'"
                + " AND deleted_at IS NULL",
            Instant.now().toString(),
            Instant.now().toString());
      } catch (Exception e) {
        failures.add(ChunkEditService.diagnostic(e));
      }
      try {
        deleteGraph("kb", "doc");
      } catch (Exception e) {
        failures.add(e.getMessage());
      }
      if (!failures.isEmpty()) throw new IllegalStateException(String.join("\n", failures));
    }

    @Override
    public void cleanupWiki(Map<String, Object> old, Set<String> ids) {
      assertThat(KnowledgeTransferRepository.text(old, "description")).isEqualTo("source summary");
      assertThat(ids).containsExactly("chunk");
      events.add(
          Map.of(
              "kind",
              "wiki_lookup",
              "source",
              KnowledgeTransferRepository.text(old, "knowledge_base_id"),
              "id",
              KnowledgeTransferRepository.text(old, "id")));
      throw new IllegalStateException("controlled wiki lookup failure");
    }

    @Override
    public void ingestWiki(long tenant, String target, String id) {
      events.add(Map.of("kind", "wiki_ingest", "tenant", tenant, "target", target, "id", id));
      throw new IllegalStateException(
          "enqueue wiki ingest pending op: controlled wiki pending failure");
    }

    @Override
    public void enqueue(KnowledgeMoveExecution.Submission task) throws Exception {
      events.add(
          Map.of(
              "kind",
              "enqueue",
              "type",
              task.type(),
              "id",
              task.id(),
              "payload",
              task.payload(),
              "queue",
              task.queue(),
              "max_retries",
              task.maxRetries(),
              "timeout_seconds",
              task.timeout().toSeconds(),
              "retention_seconds",
              task.retention().toSeconds()));
      if (!retry) for (var sql : scenario.path("queue_mutations")) f.jdbc.execute(sql.asText());
      if (failure("queue-failure")) throw new IllegalStateException("controlled queue failure");
      if (failure("conflict") || failure("duplicate"))
        throw new KnowledgeMoveExecution.AlreadyEnqueued();
    }

    private boolean failure(String suffix) {
      return !retry && scenario.path("name").asText().endsWith(suffix);
    }
  }

  @FunctionalInterface
  interface Work {
    void run() throws Exception;
  }

  private static Exception attempt(Work work) {
    try {
      work.run();
      return null;
    } catch (Exception e) {
      return e;
    }
  }
}
