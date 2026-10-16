package com.tencent.weknora.knowledge;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Production Go pair gate, full typed checkpoints, read-only preflight and real SQL CAS oracle. */
class KnowledgeMovePlanGoParityTest {
  @TempDir Path temp;
  final ObjectMapper json = new ObjectMapper();
  private final AtomicInteger databaseNumber = new AtomicInteger();

  @TestFactory
  Stream<DynamicTest> moveFoundation() throws Exception {
    JsonNode oracle;
    String external = System.getProperty("weknora.move.plan.fixture", "");
    if (!external.isEmpty()) oracle = json.readTree(Files.readAllBytes(Path.of(external)));
    else
      try (var in = getClass().getResourceAsStream("/go-knowledge-move-plan.json")) {
        oracle = json.readTree(Objects.requireNonNull(in));
      }
    var tests = new ArrayList<DynamicTest>();
    for (var c : oracle.path("compatibility"))
      tests.add(
          DynamicTest.dynamicTest(
              "compatibility/" + c.path("name").asText(),
              () ->
                  assertThat(
                          error(
                              () ->
                                  KnowledgeMoveCompatibility.validate(
                                      c.get("source"),
                                      c.get("target"),
                                      c.path("mode").asText(),
                                      c.get("tenant"))))
                      .isEqualTo(c.path("error").asText())));
    for (var c : oracle.path("metadata"))
      tests.add(DynamicTest.dynamicTest("metadata/" + c.path("raw").asText(), () -> metadata(c)));
    for (var c : oracle.path("plans"))
      tests.add(DynamicTest.dynamicTest("plan/" + c.path("name").asText(), () -> plan(c)));
    for (var c : oracle.path("checkpoints"))
      tests.add(
          DynamicTest.dynamicTest("checkpoint/" + c.path("name").asText(), () -> checkpoint(c)));
    assertThat(tests).hasSize(252);
    return tests.stream();
  }

  private void metadata(JsonNode c) throws Exception {
    var codec = new KnowledgeTransferMetadata(json);
    String raw = c.path("raw").asText();
    String readError = "";
    ObjectNode state = null;
    try {
      state = codec.read(raw);
    } catch (Exception e) {
      readError = e.getMessage();
    }
    assertThat(readError).isEqualTo(c.path("read_error").asText());
    if (readError.isEmpty()) {
      if (state != null) {
        for (String name : List.of("mode", "wiki_summary"))
          if (state.path(name).asText("").isEmpty()) state.remove(name);
        if (!state.path("wiki_chunk_ids").isArray() || state.path("wiki_chunk_ids").isEmpty())
          state.remove("wiki_chunk_ids");
      }
      assertThat((JsonNode) json.valueToTree(state)).isEqualTo(c.path("state"));
    }
    String writeError = "", written = "";
    try {
      written = codec.write(raw, (ObjectNode) c.path("new_state"));
    } catch (Exception e) {
      writeError = e.getMessage();
    }
    assertThat(writeError).isEqualTo(c.path("write_error").asText());
    assertThat(written).isEqualTo(c.path("written").asText());
  }

  private void plan(JsonNode c) throws Exception {
    var f = database();
    for (var statement : c.path("seed_statements")) f.jdbc.execute(statement.asText());
    var unchanged = snapshot(f);
    var repo = new KnowledgeTransferRepository(f.jdbc, json, f.manager, Clock.systemUTC());
    var calls = new AtomicInteger();
    var result = new ArrayList<String>();
    List<String> ids = c.path("ids").isNull() ? null : new ArrayList<>();
    for (var id : c.path("ids")) ids.add(id.asText());
    String message =
        error(
            () -> {
              var rows =
                  repo.plan(
                      new KnowledgeTransferRepository.Scope(7, "transfer-task", "kb", "other"),
                      c.path("source"),
                      c.path("target"),
                      c.path("tenant"),
                      ids,
                      c.path("mode").asText(),
                      () -> {
                        calls.incrementAndGet();
                        if (c.path("name").asText().equals("engine-failure"))
                          throw new IllegalArgumentException(
                              "controlled engine validation failure");
                      });
              for (var row : rows) result.add(row.get("id").toString());
            });
    assertThat(message).isEqualTo(c.path("error").asText());
    assertThat((JsonNode) json.valueToTree(result)).isEqualTo(c.path("result_ids"));
    assertThat(calls.get()).isEqualTo(c.path("engine_calls").asInt());
    assertThat(snapshot(f))
        .as("preflight must not mutate any table, including on rejection")
        .isEqualTo(unchanged);
  }

  private void checkpoint(JsonNode c) throws Exception {
    var f = database();
    f.jdbc.update("INSERT INTO tenants(id,name,business,storage_used) VALUES(7,'owner','test',9)");
    f.jdbc.update(
        "INSERT INTO"
            + " knowledges(id,tenant_id,knowledge_base_id,type,parse_status,metadata,description,storage_size,updated_at,title,source,error_message)"
            + " VALUES('doc',7,'kb','manual','completed','{\"content\":\"keep\"}','old',5,?,'original','','')",
        c.path("seed_updated_at").isNull() ? null : c.path("seed_updated_at").asText());
    var repo = new KnowledgeTransferRepository(f.jdbc, json, f.manager, Clock.systemUTC());
    var before = repo.get(7, "doc");
    var after = new LinkedHashMap<>(before);
    after.put("parse_status", "processing");
    after.put("knowledge_base_id", c.path("target").asText());
    after.put("storage_size", 5 + c.path("delta").asLong());
    for (var mutation : c.path("mutations")) f.jdbc.execute(mutation.asText());
    Instant started = Instant.now();
    String message = error(() -> repo.checkpoint(before, after));
    assertThat(message).isEqualTo(c.path("error").asText());
    boolean roundTrip = false;
    if (message.isEmpty()) {
      var current = repo.get(7, "doc");
      roundTrip = Objects.equals(current.get("updated_at"), after.get("updated_at"));
      // Only dynamically generated valid timestamps are excluded from the byte-level comparison.
      Instant updated = Instant.parse(KnowledgeBaseRepository.timestamp(current.get("updated_at")));
      assertThat(updated).isBetween(started.minusSeconds(1), Instant.now().plusSeconds(1));
      assertThat(updated.getNano() % 1000).isZero();
      var done = new LinkedHashMap<>(after);
      done.put("parse_status", "completed");
      repo.checkpoint(after, done);
    }
    assertThat(roundTrip).isEqualTo(c.path("returned_timestamp_equals_stored").asBoolean());
    var row =
        f.jdbc.queryForMap(
            "SELECT knowledge_base_id,parse_status,storage_size,description,CAST(metadata AS TEXT)"
                + " AS metadata,error_message,title,CAST(custom_metadata AS TEXT) AS"
                + " custom_metadata,COALESCE(file_path,'') AS file_path,pending_subtasks_count FROM"
                + " knowledges WHERE id='doc'");
    // An uninitialized Go string scans NULL as its empty value on a subsequent checkpoint.
    if (row.get("error_message") == null) row.put("error_message", "");
    assertThat(normal(row)).isEqualTo(c.path("row"));
    var used = f.jdbc.queryForList("SELECT storage_used FROM tenants WHERE id=7");
    assertThat(normal(used.isEmpty() ? null : used.getFirst().get("storage_used")))
        .isEqualTo(c.path("storage_used"));
  }

  DocumentProcessingCompatibilityTest database() throws Exception {
    var f = new DocumentProcessingCompatibilityTest();
    f.temp = temp;
    f.database(databaseNumber.incrementAndGet(), true);
    for (String table : List.of("chunks", "knowledges", "knowledge_tags", "tenants"))
      f.jdbc.execute("DELETE FROM " + table);
    return f;
  }

  private JsonNode snapshot(DocumentProcessingCompatibilityTest f) throws Exception {
    var result = new TreeMap<String, Object>();
    for (String table :
        List.of(
            "knowledges",
            "chunks",
            "knowledge_tags",
            "knowledge_tag_relations",
            "tenants",
            "java_task_jobs")) result.put(table, f.jdbc.queryForList("SELECT * FROM " + table));
    return normal(result);
  }

  JsonNode normal(Object value) throws Exception {
    return json.readTree(json.writeValueAsBytes(value));
  }

  @FunctionalInterface
  interface Run {
    void run() throws Exception;
  }

  private static String error(Run run) {
    try {
      run.run();
      return "";
    } catch (Exception e) {
      return Objects.toString(e.getMessage(), "");
    }
  }
}
