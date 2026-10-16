package com.tencent.weknora.knowledge;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import com.tencent.weknora.model.*;
import com.tencent.weknora.model.client.*;
import com.tencent.weknora.retrieval.*;
import com.tencent.weknora.security.*;
import com.tencent.weknora.task.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

/**
 * Actual Go worker/model HTTP/physical vec+FTS/SQL and stage contracts, including unsafe baseline
 * fixes.
 */
class ImageMultimodalGoParityTest {
  @TempDir Path temp;
  final ObjectMapper json = new ObjectMapper();

  static {
    try {
      NativeDatabaseRuntime.initialize();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @TestFactory
  Stream<DynamicTest> actualGo() throws Exception {
    JsonNode cases;
    try (var in = getClass().getResourceAsStream("/go-image-multimodal.json")) {
      cases = json.readTree(in);
    }
    assertThat(cases.size()).isEqualTo(66);
    var no = new AtomicInteger();
    return StreamSupport.stream(cases.spliterator(), false)
        .map(
            c ->
                DynamicTest.dynamicTest(
                    c.path("name").asText(), () -> compare(c, no.incrementAndGet())));
  }

  void compare(JsonNode oracle, int no) throws Exception {
    String name = oracle.path("name").asText();
    var helpers = new ManualProcessingGoParityTest();
    helpers.temp = temp;
    var f = database(no, name);
    var provider = json.createArrayNode();
    var queued = json.createArrayNode();
    var chatCalls = new AtomicInteger();
    var tracker =
        new KnowledgeProcessingSpanTracker(
            new KnowledgeProcessingSpanWriteRepository(
                f.jdbc, json, new KnowledgeProcessingSpanRepository(f.jdbc, json)),
            json);
    var row =
        f.knowledge
            .blank()
            .put("id", "doc")
            .put("tenant_id", 1)
            .put("knowledge_base_id", "kb")
            .put("type", "manual")
            .put("title", "Manual title")
            .put("file_name", "Manual title.md")
            .put("file_type", "manual")
            .put("embedding_model_id", "embedding")
            .put("parse_status", "processing")
            .put("enable_status", "enabled");
    row.set(
        "metadata",
        json.createObjectNode()
            .put("content", "Image body")
            .put("format", "markdown")
            .put("status", "publish")
            .put("version", 1));
    f.knowledge.create(1, row);
    f.jdbc.update(
        "INSERT INTO"
            + " chunks(id,tenant_id,knowledge_id,knowledge_base_id,content,chunk_type,is_enabled,seq_id,chunk_index,start_at,end_at)"
            + " VALUES('parent',1,'doc','kb','Image body','text',true,"
            + parentSequence()
            + ",0,0,0)");
    tracker.open(1, "doc", "");
    tracker.beginStage(1, "doc", 1, "multimodal", json.createObjectNode().put("image_count", 1));
    var config =
        json.createObjectNode().put("enabled", !name.equals("vlm-disabled")).put("model_id", "vlm");
    if (name.equals("custom-language"))
      config
          .put("description_language", "French")
          .put("custom_instructions", "Focus on network access.");
    f.jdbc.update("UPDATE knowledge_bases SET vlm_config=? WHERE id='kb'", config.toString());
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1",
        exchange -> {
          try {
            var req = json.readTree(exchange.getRequestBody());
            provider.add(
                json.createObjectNode()
                    .put("path", exchange.getRequestURI().getPath())
                    .set("body", req));
            int status = 200;
            JsonNode response;
            if (exchange.getRequestURI().getPath().endsWith("/embeddings")) {
              if (name.equals("embedding-error")) {
                status = 500;
                response =
                    json.readTree("{\"error\":{\"message\":\"controlled embedding failure\"}}");
              } else {
                var data = json.createArrayNode();
                for (int i = 0; i < req.path("input").size(); i++) {
                  byte[] text =
                      req.path("input")
                          .get(i)
                          .asText()
                          .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                  int sum = 0;
                  for (byte b : text) sum += b & 255;
                  data.add(
                      json.createObjectNode()
                          .put("index", i)
                          .set(
                              "embedding", json.createArrayNode().add(text.length).add(sum % 251)));
                }
                response = json.createObjectNode().set("data", data);
              }
            } else {
              if (chatCalls.incrementAndGet() == 1)
                switch (name) {
                  case "cancel-during-chat" ->
                      f.jdbc.update(
                          "UPDATE knowledges SET parse_status='cancelled' WHERE id='doc'");
                  case "edit-during-chat" ->
                      f.jdbc.update(
                          "UPDATE knowledges SET metadata='{}',title='New title' WHERE id='doc'");
                  case "owner-during-chat" ->
                      f.jdbc.update("UPDATE knowledges SET tenant_id=2 WHERE id='doc'");
                  case "kb-during-chat" ->
                      f.jdbc.update("UPDATE knowledge_bases SET tenant_id=2 WHERE id='kb'");
                  case "parent-during-chat" ->
                      f.jdbc.update("UPDATE chunks SET knowledge_id='other' WHERE id='parent'");
                  case "fresh-attempt-during-chat" -> tracker.open(1, "doc", "");
                }
              String prompt =
                  req.path("messages").get(0).path("content").get(0).path("text").asText();
              boolean ocr = prompt.contains("OCR ");
              if (name.equals("both-error")
                  || (name.equals("caption-error") && !ocr)
                  || (name.equals("ocr-error") && ocr)) {
                status = 500;
                response = json.readTree("{\"error\":{\"message\":\"controlled VLM failure\"}}");
              } else {
                String text = " A concise image description. ";
                if (prompt.startsWith("Observe")) {
                  text =
                      switch (name) {
                        case "attrs-negative" ->
                            "contain.text: sparse\n"
                                + "contain.data_visual: false\n"
                                + "DESCRIPTION: A logo.";
                        case "attrs-chart" ->
                            "contain.text: sparse\n"
                                + "contain.data_visual: true\n"
                                + "DESCRIPTION: A chart.";
                        case "attrs-prose" -> "A prose answer without attributes.";
                        case "attrs-invalid" ->
                            "contain.text: maybe\ncontain.data_visual: no\nDESCRIPTION: Uncertain.";
                        case "attrs-only" -> "contain.text: block\ncontain.data_visual: false";
                        case "attrs-incomplete-strict" ->
                            "contain.text: sparse\nDESCRIPTION: Incomplete.";
                        default ->
                            "contain.text: block\n"
                                + "contain.data_visual: false\n"
                                + "DESCRIPTION: A concise image description.";
                      };
                }
                if (ocr)
                  text =
                      switch (name) {
                        case "ocr-empty" -> "No text content.";
                        case "ocr-code" ->
                            "```markdown\n# Exact OCR\n\nActual body text here.\n```";
                        case "ocr-html" ->
                            "<h1>Access policy</h1><p>Actual body text and role permissions.</p>";
                        default ->
                            "# Extracted access\n\n"
                                + "Network entry is controlled by account permissions.";
                      };
                String reason = name.equals("truncated") ? "length" : "stop";
                if (name.equals("truncated")) text = "";
                response =
                    json.createObjectNode()
                        .set(
                            "choices",
                            json.createArrayNode()
                                .add(
                                    json.createObjectNode()
                                        .put("finish_reason", reason)
                                        .set(
                                            "message",
                                            json.createObjectNode()
                                                .put("role", "assistant")
                                                .put("content", text))));
              }
            }
            byte[] bytes = json.writeValueAsBytes(response);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
          } catch (Exception e) {
            e.printStackTrace();
            exchange.sendResponseHeaders(500, 0);
          } finally {
            exchange.close();
          }
        });
    server.start();
    var env =
        new MockEnvironment()
            .withProperty("SSRF_WHITELIST", "127.0.0.1")
            .withProperty("SSRF_DNS_WHITELIST_ONLY", "true");
    var models =
        new ModelClientFactory(
            f.jdbc,
            json,
            new GoSecretCodec(""),
            new ModelCatalogRuntime(f.jdbc, "", "/nonexistent"),
            new ModelEgressPolicy(f.jdbc, json, env),
            env);
    try {
      for (var model :
          List.of(
              List.of("vlm", "vision-model", "VLLM"),
              List.of("embedding", "image-embedding", "Embedding"))) {
        var params =
            json.createObjectNode()
                .put("provider", "generic")
                .put("base_url", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                .set("embedding_parameters", json.createObjectNode().put("dimension", 2));
        f.jdbc.update(
            "INSERT INTO models(id,tenant_id,name,type,source,parameters,status)"
                + " VALUES(?,1,?,?,'remote',?,'active')",
            model.get(0),
            model.get(1),
            model.get(2),
            params.toString());
      }
      switch (name) {
        case "missing-vlm" -> f.jdbc.update("DELETE FROM models WHERE id='vlm'");
        case "foreign-vlm" -> f.jdbc.update("UPDATE models SET tenant_id=2 WHERE id='vlm'");
        case "wrong-model-type" ->
            f.jdbc.update("UPDATE models SET type='Embedding' WHERE id='vlm'");
        case "missing-embedding" -> f.jdbc.update("DELETE FROM models WHERE id='embedding'");
        case "missing-document" -> f.jdbc.update("DELETE FROM knowledges");
        case "missing-base" -> f.jdbc.update("DELETE FROM knowledge_bases");
        case "cancelled", "deleting" ->
            f.jdbc.update("UPDATE knowledges SET parse_status=? WHERE id='doc'", name);
        case "foreign-document" -> f.jdbc.update("UPDATE knowledges SET tenant_id=2");
        case "foreign-base" -> f.jdbc.update("UPDATE knowledge_bases SET tenant_id=2");
        case "foreign-parent" -> f.jdbc.update("UPDATE chunks SET tenant_id=2 WHERE id='parent'");
        case "wrong-parent-document" ->
            f.jdbc.update("UPDATE chunks SET knowledge_id='other' WHERE id='parent'");
        case "missing-parent" -> f.jdbc.update("DELETE FROM chunks");
        case "stale-attempt" -> tracker.open(1, "doc", "");
      }
      ObjectNode payload = (ObjectNode) oracle.path("payload").deepCopy();
      String id = "image-" + no;
      f.tasks.enqueue(
          1,
          "image:multimodal",
          id,
          payload,
          4,
          Duration.ZERO,
          "multimodal",
          Duration.ofMinutes(30));
      f.jdbc.update(
          "UPDATE java_task_jobs SET payload=? WHERE type='image:multimodal'",
          oracle.path("raw_payload").asText());
      var job = f.tasks.claim("worker", Duration.ofMinutes(5), List.of("multimodal")).orElseThrow();
      if (name.equals("final-missing-image")) {
        f.jdbc.update("UPDATE java_task_jobs SET attempts=4 WHERE id=?", job.id());
        job =
            new TaskRecord(
                job.id(),
                job.tenantId(),
                job.type(),
                job.idempotencyKey(),
                job.payload(),
                job.status(),
                4,
                4,
                job.availableAt(),
                job.leaseOwner(),
                job.leaseUntil(),
                false,
                null,
                null,
                job.createdAt(),
                job.updatedAt(),
                job.queue(),
                job.timeoutMillis());
      }
      f.jdbc.update(
          "INSERT INTO java_image_task_pending(tenant_id,knowledge_id,attempt,pending_count)"
              + " VALUES(1,'doc',1,?)",
          name.equals("pending-two") ? 2 : 1);
      var repository =
          new ImageMultimodalRepository(f.jdbc, json, f.knowledge, f.manager, Clock.systemUTC());
      byte[] image = new byte[600];
      Arrays.fill(image, (byte) 'v');
      System.arraycopy(new byte[] {(byte) 137, 'P', 'N', 'G', 13, 10, 26, 10}, 0, image, 0, 8);
      Path physical = temp.resolve("image-" + no + ".png");
      Files.write(physical, image);
      KnowledgeFileUpload.Enqueuer queue =
          (owner, type, p, options) -> {
            if (name.equals("post-error"))
              throw new java.io.IOException("controlled queue unavailable");
            queued.add(
                json.createObjectNode()
                    .put("type", type)
                    .put("queue", options.queue())
                    .put("max_retry", options.maxRetries())
                    .put("timeout", options.timeout().toMillis())
                    .set("payload", p));
            return "accepted";
          };
      var handler =
          new ImageMultimodalHandler(
              repository,
              json,
              (owner, c) -> models.vision(auth(owner), c.path("model_id").asText()),
              (owner, model) -> models.embeddingForCleanup(auth(owner), model),
              (j, s, call) -> {
                if (name.equals("missing-image") || name.equals("final-missing-image"))
                  throw new java.io.IOException(
                      "file service get local://1/missing.png: failed to open file: open"
                          + " /owned/image-fixture/1/missing.png: no such file or directory");
                return Files.readAllBytes(physical);
              },
              (owner, kb) -> writer(f),
              queue,
              tracker,
              null,
              5);
      Exception error = null;
      try {
        handler.execute(job, f.cancellation(job));
      } catch (Exception e) {
        error = e;
      }
      assertThat(error == null ? "" : error.getMessage())
          .as("error")
          .isEqualTo(oracle.path("error").asText());
      assertThat(error != null && (!(error instanceof TaskFailure tf) || tf.retryable()))
          .as("retry decision")
          .isEqualTo(oracle.path("retry").asBoolean());
      var children = json.createArrayNode();
      var ids = new HashMap<String, String>();
      for (var c :
          f.jdbc.queryForList("SELECT * FROM chunks WHERE chunk_type <> 'text' ORDER BY seq_id")) {
        ids.put(c.get("id").toString(), c.get("chunk_type").toString());
        children.add(
            json.createObjectNode()
                .put("type", c.get("chunk_type").toString())
                .put("content", c.get("content").toString())
                .put("owner", ((Number) c.get("tenant_id")).longValue())
                .put("knowledge", c.get("knowledge_id").toString())
                .put("kb", c.get("knowledge_base_id").toString())
                .put("parent", c.get("parent_chunk_id").toString())
                .put("status", ((Number) c.get("status")).intValue())
                .put("flags", ((Number) c.get("flags")).intValue())
                .put(
                    "enabled",
                    Boolean.TRUE.equals(c.get("is_enabled"))
                        || (c.get("is_enabled") instanceof Number enabled
                            && enabled.intValue() != 0))
                .put("seq_id", ((Number) c.get("seq_id")).longValue())
                .set("image_info", json.readTree(c.get("image_info").toString())));
        ((ObjectNode) children.get(children.size() - 1))
            .set(
                "locators",
                c.get("source_locators") == null
                    ? NullNode.instance
                    : json.readTree(c.get("source_locators").toString()));
      }
      assertThat(helpers.canonical(children))
          .as("persisted image children")
          .isEqualTo(helpers.canonical(oracle.path("chunks")));
      var indexes =
          json.valueToTree(
              f.jdbc.queryForList(
                  "SELECT source_id,chunk_id,knowledge_id,knowledge_base_id,content,is_enabled FROM"
                      + " "
                      + indexTable()
                      + " ORDER BY source_id"));
      var indexRows = new ArrayList<ObjectNode>();
      for (var x : indexes) {
        var v = (ObjectNode) x;
        v.put("source_id", ids.get(v.path("source_id").asText()))
            .put("chunk_id", ids.get(v.path("chunk_id").asText()));
        indexRows.add(v);
      }
      indexRows.sort(Comparator.comparing(v -> v.path("source_id").asText()));
      var indexArray = json.valueToTree(indexRows);
      assertThat(helpers.canonical(indexArray))
          .as("real engine index rows")
          .isEqualTo(
              helpers.canonical(
                  oracle.path("indexes").isNull()
                      ? json.createArrayNode()
                      : oracle.path("indexes")));
      assertThat(helpers.canonical(vectors(f, ids)))
          .as("physical raw vector bits per child")
          .isEqualTo(helpers.canonical(oracle.path("vectors")));
      assertThat(helpers.canonical(provider))
          .as("actual provider HTTP request bodies/order")
          .isEqualTo(helpers.canonical(oracle.path("provider")));
      assertThat(helpers.canonical(queued))
          .as("Asynq task options/payload")
          .isEqualTo(helpers.canonical(oracle.path("tasks")));
      long pending =
          f.jdbc.queryForObject(
              "SELECT COALESCE(SUM(pending_count),0) FROM java_image_task_pending", Long.class);
      assertThat(pending).as("parent image slots").isEqualTo(oracle.path("pending").asLong());
      var spans =
          json.valueToTree(
              f.jdbc.queryForList(
                  "SELECT attempt,name,kind,status,CAST(input AS TEXT) AS input,CAST(output AS"
                      + " TEXT) AS output,error_code,error_message FROM knowledge_processing_spans"
                      + " ORDER BY attempt,id"));
      assertThat(helpers.canonical(spans))
          .as("actual stage spans")
          .isEqualTo(helpers.canonical(oracle.path("spans")));
    } finally {
      models.close();
      server.stop(0);
      release(f);
    }
  }

  DocumentProcessingCompatibilityTest database(int no, String name) throws Exception {
    var helper = new ManualProcessingGoParityTest();
    helper.temp = temp;
    return helper.database(no, name);
  }

  IndexWriter writer(DocumentProcessingCompatibilityTest f) {
    return f.index;
  }

  String indexTable() {
    return "lite_embeddings";
  }

  String parentSequence() {
    return "1";
  }

  JsonNode vectors(DocumentProcessingCompatibilityTest f, Map<String, String> ids)
      throws Exception {
    return new ManualProcessingGoParityTest().vectors(f, ids);
  }

  void release(DocumentProcessingCompatibilityTest f) {}

  static AuthContext auth(long owner) {
    return new AuthContext("system-task", owner, "viewer", false, null, false, Set.of(), Set.of());
  }
}
