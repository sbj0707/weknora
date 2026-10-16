package com.tencent.weknora.knowledge;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.task.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Exact production Go Asynq fan-out payload/order/counter, including partial image admission. */
class ImageMultimodalFanoutGoParityTest {
  @TempDir Path temp;
  final ObjectMapper json = new ObjectMapper();

  @TestFactory
  Stream<DynamicTest> actualGoFanout() throws Exception {
    JsonNode cases;
    try (var in = getClass().getResourceAsStream("/go-image-multimodal-fanout.json")) {
      cases = json.readTree(in);
    }
    assertThat(cases.size()).isEqualTo(9);
    var no = new AtomicInteger();
    return StreamSupport.stream(cases.spliterator(), false)
        .map(
            c ->
                DynamicTest.dynamicTest(
                    c.path("name").asText(), () -> compare(c, no.incrementAndGet())));
  }

  void compare(JsonNode oracle, int no) throws Exception {
    String name = oracle.path("name").asText();
    var f = database(no, name);
    var row =
        f.knowledge
            .blank()
            .put("id", "doc")
            .put("tenant_id", 1)
            .put("knowledge_base_id", "kb")
            .put("parse_status", "processing");
    f.knowledge.create(1, row);
    var base = (ObjectNode) oracle.path("kb").deepCopy();
    var source =
        new DocumentProcessingRepository.Snapshot(
            f.knowledge.get(1, "doc").orElseThrow(), base, Map.of());
    var payload =
        json.createObjectNode()
            .put("tenant_id", 1)
            .put("knowledge_id", "doc")
            .put("knowledge_base_id", "kb")
            .put("attempt", 2)
            .put("language", "fr-FR");
    f.tasks.enqueue(
        1,
        "manual:process",
        UUID.randomUUID().toString(),
        payload,
        4,
        Duration.ZERO,
        "default",
        Duration.ofMinutes(30));
    var parent =
        f.tasks.claim("fanout-worker", Duration.ofMinutes(5), List.of("default")).orElseThrow();
    var repository =
        new ImageMultimodalRepository(f.jdbc, json, f.knowledge, f.manager, Clock.systemUTC());
    var queued = json.createArrayNode();
    var seen = new AtomicInteger();
    KnowledgeFileUpload.Enqueuer tasks =
        (owner, type, p, options) -> {
          if (type.equals("image:multimodal")) {
            int n = seen.getAndIncrement();
            if ((n == 0
                    && Set.of("partial-first", "none-accepted", "post-unavailable").contains(name))
                || (n == 1
                    && Set.of("partial-last", "none-accepted", "post-unavailable").contains(name)))
              f.jdbc.update(
                  "INSERT INTO"
                      + " java_task_jobs(id,tenant_id,type,idempotency_key,payload,status,max_attempts,available_at,created_at,updated_at,queue)"
                      + " VALUES('bad',1,NULL,'bad','{}','pending',4,0,0,0,'multimodal')");
          } else if (name.equals("post-unavailable"))
            throw new java.io.IOException("controlled post queue unavailable");
          var t =
              f.tasks.enqueue(
                  owner,
                  type,
                  UUID.randomUUID().toString(),
                  p,
                  options.maxRetries() + 1,
                  Duration.ZERO,
                  options.queue(),
                  options.timeout());
          queued.add(
              json.createObjectNode()
                  .put("type", type)
                  .put("queue", options.queue())
                  .put("max_retry", options.maxRetries())
                  .put("timeout", options.timeout().toMillis())
                  .set("payload", p));
          return t.id();
        };
    var fanout =
        new ImageMultimodalFanout(
            repository,
            json,
            (owner, cfg) -> {
              throw new AssertionError(
                  "enqueue uses validated model capability without provider calls");
            },
            tasks);
    var images = new ArrayList<ManualImageResolver.Image>();
    for (var image : oracle.path("images"))
      images.add(
          new ManualImageResolver.Image(
              image.path("OriginalRef").asText(),
              image.path("ServingURL").asText(),
              image.path("MimeType").asText()));
    var chunks = new ArrayList<DocumentChunks.Stored>();
    for (var c : oracle.path("chunks"))
      chunks.add(
          new DocumentChunks.Stored(
              c.path("ChunkID").asText(),
              c.path("Content").asText(),
              "",
              0,
              0,
              0,
              "text",
              "",
              "",
              "",
              List.of()));
    Exception error = null;
    try {
      fanout.enqueue(
          parent, source, new KnowledgeProcessConfig(json).resolve(base, null), images, chunks);
    } catch (Exception e) {
      error = e;
    }
    assertThat(error == null ? "" : error.getMessage()).isEqualTo(oracle.path("error").asText());
    var canonical = new ManualProcessingGoParityTest();
    assertThat(canonical.canonical(queued)).isEqualTo(canonical.canonical(oracle.path("tasks")));
    assertThat(
            f.jdbc.queryForObject(
                "SELECT COALESCE(SUM(pending_count),0) FROM java_image_task_pending", Long.class))
        .isEqualTo(oracle.path("pending").asLong());
    assertThat(
            f.jdbc.queryForObject(
                "SELECT COUNT(*) FROM java_task_jobs WHERE type<>'manual:process'", Long.class))
        .isEqualTo(oracle.path("tasks").size());
    release(f);
  }

  DocumentProcessingCompatibilityTest database(int no, String name) throws Exception {
    var f = new DocumentProcessingCompatibilityTest();
    f.temp = temp;
    f.database(no, true);
    return f;
  }

  void release(DocumentProcessingCompatibilityTest f) {}
}
