package com.tencent.weknora.knowledge;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import com.tencent.weknora.security.JwtCodec;
import com.tencent.weknora.task.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Real full-schema PostgreSQL/Hikari1 manual image HTTP, native production beans and resources. */
@org.springframework.boot.test.context.SpringBootTest(
    properties = {
      "weknora.jwt-secret=image-postgres-native-secret",
      "weknora.bridge.enabled=false",
      "weknora.knowledge.processing.enabled=true",
      "weknora.knowledge.upload.enabled=false",
      "weknora.tasks.enabled=true",
      "weknora.tasks.poll-millis=3600000",
      "weknora.retrieve-driver=postgres",
      "RETRIEVE_DRIVER=postgres",
      "SSRF_WHITELIST=127.0.0.1",
      "SSRF_DNS_WHITELIST_ONLY=true",
      "NEO4J_ENABLE=false",
      "logging.level.root=WARN"
    })
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class ManualImagePostgresPipelineIT {
  static final ObjectMapper JSON = new ObjectMapper();
  static final Path DIRECTORY;
  static final HttpServer SERVER;
  static volatile Runnable duringVision = () -> {};
  static final AtomicInteger EMBEDDINGS = new AtomicInteger(),
      CHATS = new AtomicInteger(),
      VISIONS = new AtomicInteger();

  static {
    try {
      com.tencent.weknora.retrieval.NativeDatabaseRuntime.initialize();
      DIRECTORY = Files.createTempDirectory("weknora-pg-manual-image-");
      SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      SERVER.createContext(
          "/v1/",
          e -> {
            try {
              var body = JSON.readTree(e.getRequestBody());
              ObjectNode out;
              if (e.getRequestURI().getPath().endsWith("embeddings")) {
                EMBEDDINGS.incrementAndGet();
                var data = JSON.createArrayNode();
                for (int i = body.path("input").size() - 1; i >= 0; i--) {
                  byte[] text =
                      body.path("input")
                          .get(i)
                          .asText()
                          .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                  int sum = 0;
                  for (byte b : text) sum += b & 255;
                  data.add(
                      JSON.createObjectNode()
                          .put("index", i)
                          .set(
                              "embedding",
                              JSON.createArrayNode().add(text.length).add(sum % 251).add(1)));
                }
                out = JSON.createObjectNode().set("data", data);
              } else {
                CHATS.incrementAndGet();
                String text;
                if (body.path("messages").path(0).path("content").isArray()) {
                  VISIONS.incrementAndGet();
                  duringVision.run();
                  text =
                      body.path("messages")
                              .path(0)
                              .path("content")
                              .path(0)
                              .path("text")
                              .asText()
                              .contains("OCR ")
                          ? "PostgreSQL image OCR access policy."
                          : "PostgreSQL image caption controls.";
                } else if (body.path("messages").size() == 1)
                  text =
                      "What are the manual controls?\n"
                          + "How is access restricted?\n"
                          + "Why are revisions fenced?";
                else
                  text =
                      "{\"summary\":\"PostgreSQL manual summary\",\"gist\":\"Native manual"
                          + " controls\"}";
                out =
                    JSON.createObjectNode()
                        .set(
                            "choices",
                            JSON.createArrayNode()
                                .add(
                                    JSON.createObjectNode()
                                        .put("finish_reason", "stop")
                                        .set(
                                            "message",
                                            JSON.createObjectNode()
                                                .put("role", "assistant")
                                                .put("content", text))));
              }
              byte[] bytes = JSON.writeValueAsBytes(out);
              e.getResponseHeaders().set("Content-Type", "application/json");
              e.sendResponseHeaders(200, bytes.length);
              e.getResponseBody().write(bytes);
            } finally {
              e.close();
            }
          });
      SERVER.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @org.springframework.test.context.DynamicPropertySource
  static void properties(org.springframework.test.context.DynamicPropertyRegistry p) {
    String url = Objects.requireNonNull(System.getProperty("weknora.image.pg.url"));
    if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/weknora_manual_parity"))
      throw new IllegalArgumentException("Owned loopback image PG required");
    p.add("spring.datasource.url", () -> url);
    p.add("spring.datasource.username", () -> "postgres");
    p.add("spring.datasource.password", () -> "local-parity-only");
    p.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    p.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
    p.add("spring.datasource.hikari.connection-timeout", () -> 2000);
    p.add("spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
    p.add("LOCAL_STORAGE_BASE_DIR", () -> DIRECTORY.toString());
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired JwtCodec jwt;
  @Autowired TaskRepository tasks;
  @Autowired ManualProcessingHandler manual;
  @Autowired ImageMultimodalHandler image;
  @Autowired KnowledgePostProcessHandler postprocess;
  @Autowired SummaryGenerationHandler summary;
  @Autowired QuestionGenerationHandler questions;
  @Autowired com.tencent.weknora.storage.StorageResolver storage;

  @AfterAll
  static void stop() {
    SERVER.stop(0);
  }

  String token(String user, long owner) {
    String token =
        jwt.sign(
            Map.of(
                "user_id",
                user,
                "tenant_id",
                owner,
                "type",
                "access",
                "exp",
                Instant.now().plusSeconds(3600).getEpochSecond()));
    jdbc.update(
        "INSERT INTO auth_tokens(id,user_id,token,token_type,expires_at,is_revoked)"
            + " VALUES(?,?,?,'access_token',CURRENT_TIMESTAMP+INTERVAL '1 hour',false)",
        UUID.randomUUID().toString(),
        user,
        token);
    return token;
  }

  JsonNode http(String method, String path, String token, JsonNode body) throws Exception {
    var request = method.equals("GET") ? get(path) : post(path);
    request.header("Authorization", "Bearer " + token);
    if (body != null) request.contentType("application/json").content(body.toString());
    var response = mvc.perform(request).andReturn().getResponse();
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
    return JSON.readTree(response.getContentAsByteArray());
  }

  TaskRecord claim(String queue) {
    return tasks.claim("pg-image-worker", Duration.ofMinutes(5), List.of(queue)).orElseThrow();
  }

  TaskCancellation cancellation(TaskRecord job) throws Exception {
    var c = TaskCancellation.class.getDeclaredConstructor(TaskRepository.class, TaskRecord.class);
    c.setAccessible(true);
    return c.newInstance(tasks, job);
  }

  void run(String queue, TaskHandler handler) throws Exception {
    var task = claim(queue);
    handler.execute(task, cancellation(task));
    assertThat(tasks.complete(task, JSON.createObjectNode())).isTrue();
  }

  long count(String table, String doc) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM " + table + " WHERE knowledge_id=?", Long.class, doc);
  }

  @Test
  void ownSharedManualVisionAndEnrichmentPersistRealHalfvecAndCancelledVisionCannotPublish()
      throws Exception {
    assertThat(jdbc.queryForObject("SELECT current_database()", String.class))
        .isEqualTo("weknora_manual_parity");
    jdbc.execute(
        "TRUNCATE"
            + " tenants,users,knowledge_bases,knowledges,chunks,models,embeddings,knowledge_processing_spans,java_task_jobs,java_image_task_pending,java_image_task_drains,java_summary_task_drains,java_question_task_drains"
            + " RESTART IDENTITY CASCADE");
    jdbc.update(
        "INSERT INTO tenants(id,name,business,storage_engine_config)"
            + " VALUES(7,'Caller','isolated','{}'::jsonb),(8,'Owner','isolated','{}'::jsonb)");
    for (var user : Map.of("caller", 7L, "owner", 8L).entrySet()) {
      jdbc.update(
          "INSERT INTO users(id,username,email,password_hash,tenant_id) VALUES(?,?,?,'unused',?)",
          user.getKey(),
          user.getKey(),
          user.getKey() + "@image.test.invalid",
          user.getValue());
      jdbc.update(
          "INSERT INTO tenant_members(user_id,tenant_id,role,status) VALUES(?,?,?,'active')",
          user.getKey(),
          user.getValue(),
          user.getKey().equals("owner") ? "admin" : "contributor");
    }
    jdbc.update(
        "INSERT INTO"
            + " knowledge_bases(id,tenant_id,name,type,creator_id,embedding_model_id,summary_model_id,indexing_strategy,chunking_config,vlm_config,question_generation_config)"
            + " VALUES('kb',8,'Manual"
            + " images','document','owner','embedding','summary',?::jsonb,?::jsonb,?::jsonb,?::jsonb)",
        "{\"vector_enabled\":true,\"keyword_enabled\":true}",
        "{\"chunk_size\":200,\"chunk_overlap\":10}",
        "{\"enabled\":true,\"model_id\":\"vision\"}",
        "{\"enabled\":true,\"question_count\":3}");
    String params =
        JSON.createObjectNode()
            .put("provider", "generic")
            .put("base_url", "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/v1")
            .set("embedding_parameters", JSON.createObjectNode().put("dimension", 3))
            .toString();
    for (var model :
        Map.of("embedding", "Embedding", "summary", "KnowledgeQA", "vision", "VLLM").entrySet())
      jdbc.update(
          "INSERT INTO models(id,tenant_id,name,type,source,parameters,is_builtin,status)"
              + " VALUES(?,8,?,?,'remote',?::jsonb,false,'active')",
          model.getKey(),
          model.getKey(),
          model.getValue(),
          params);
    jdbc.update(
        "INSERT INTO organizations(id,name,owner_tenant_id,owner_id)"
            + " VALUES('org','Org',8,'owner')");
    jdbc.update(
        "INSERT INTO organization_tenant_members(id,organization_id,tenant_id,role)"
            + " VALUES('source','org',8,'admin'),('target','org',7,'editor')");
    jdbc.update(
        "INSERT INTO"
            + " kb_shares(id,knowledge_base_id,organization_id,source_tenant_id,permission,shared_by_user_id)"
            + " VALUES('share','kb','org',8,'editor','owner')");
    String owner = token("owner", 8), caller = token("caller", 7);
    byte[] bytes = new byte[600];
    Arrays.fill(bytes, (byte) 'v');
    String content =
        "# PostgreSQL native manual image controls\n\n![source](data:image/png;base64,"
            + Base64.getEncoder().encodeToString(bytes)
            + ")";
    var config =
        JSON.createObjectNode()
            .put("summary_enabled", true)
            .set(
                "question_generation_config",
                JSON.createObjectNode().put("enabled", true).put("question_count", 3));
    var ids = new ArrayList<String>();
    for (String actor : List.of(owner, caller)) {
      var body =
          JSON.createObjectNode()
              .put("title", "PG manual " + UUID.randomUUID())
              .put("content", content)
              .put("status", "publish");
      body.set("process_config", config);
      String id =
          http("POST", "/api/v1/knowledge-bases/kb/knowledge/manual", actor, body)
              .path("data")
              .path("id")
              .asText();
      ids.add(id);
      run("default", manual);
      assertThat(
              jdbc.queryForObject(
                  "SELECT pending_count FROM java_image_task_pending WHERE knowledge_id=?",
                  Long.class,
                  id))
          .isEqualTo(1);
      run("multimodal", image);
      assertThat(count("java_image_task_drains", id)).isEqualTo(1);
      run("postprocess", postprocess);
      assertThat(
              jdbc.queryForObject(
                  "SELECT pending_subtasks_count FROM knowledges WHERE id=?", Long.class, id))
          .isEqualTo(2);
      run("question", questions);
      run("summary", summary);
      assertThat(
              jdbc.queryForObject(
                  "SELECT parse_status FROM knowledges WHERE id=?", String.class, id))
          .isEqualTo("completed");
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM chunks WHERE knowledge_id=? AND chunk_type IN"
                      + " ('image_ocr','image_caption') AND status=2 AND tenant_id=8",
                  Long.class,
                  id))
          .isEqualTo(2);
      assertThat(count("embeddings", id)).isGreaterThan(2);
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM embeddings WHERE knowledge_id=? AND dimension=3 AND"
                      + " embedding IS NOT NULL",
                  Long.class,
                  id))
          .isEqualTo(count("embeddings", id));
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM chunks WHERE knowledge_id=? AND"
                      + " jsonb_typeof(metadata::jsonb)='object'",
                  Long.class,
                  id))
          .isGreaterThan(0);
      var spans = http("GET", "/api/v1/knowledge/" + id + "/spans", caller, null).path("data");
      assertThat(spans.path("trace").path("status").asText()).isEqualTo("done");
      assertThat(
              jdbc.queryForObject(
                  "SELECT status FROM knowledge_processing_spans WHERE knowledge_id=? AND"
                      + " name='multimodal' AND kind='stage'",
                  String.class,
                  id))
          .isEqualTo("done");
      http("GET", "/api/v1/knowledge/" + id + "/index-status", caller, null);
      String ref =
          "resource://"
              + jdbc.queryForObject(
                  "SELECT r.handle FROM resources r JOIN resource_bindings b ON b.resource_id=r.id"
                      + " WHERE b.owner_id=? AND b.relation='attachment'",
                  String.class,
                  id);
      try (var stream = storage.forKnowledgeBase(8, "kb").files().open(ref)) {
        assertThat(stream.readAllBytes()).isEqualTo(bytes);
      }
      var child =
          jdbc.queryForObject(
              "SELECT id FROM chunks WHERE knowledge_id=? AND chunk_type='image_ocr'",
              String.class,
              id);
      http("GET", "/api/v1/knowledge/" + id + "/chunks/" + child + "/details", caller, null);
    }
    var body =
        JSON.createObjectNode()
            .put("title", "Cancelled image")
            .put("content", content)
            .put("status", "publish");
    body.set("process_config", config);
    String cancelled =
        http("POST", "/api/v1/knowledge-bases/kb/knowledge/manual", caller, body)
            .path("data")
            .path("id")
            .asText();
    run("default", manual);
    var task = claim("multimodal");
    var once = new AtomicInteger();
    duringVision =
        () -> {
          if (once.getAndIncrement() == 0)
            try {
              http("POST", "/api/v1/knowledge/" + cancelled + "/cancel-parse", caller, null);
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
        };
    assertThatThrownBy(() -> image.execute(task, cancellation(task)))
        .isInstanceOf(CancellationException.class);
    assertThat(tasks.acknowledgeCancellation(task)).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM chunks WHERE knowledge_id=? AND chunk_type IN"
                    + " ('image_ocr','image_caption')",
                Long.class,
                cancelled))
        .isZero();
    assertThat(count("embeddings", cancelled)).isGreaterThan(0);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM java_task_jobs WHERE type='knowledge:post_process' AND"
                    + " payload::jsonb->>'knowledge_id'=?",
                Long.class,
                cancelled))
        .isZero();
    assertThat(
            http("GET", "/api/v1/knowledge/" + cancelled + "/spans", caller, null)
                .path("data")
                .path("trace")
                .path("status")
                .asText())
        .isEqualTo("cancelled");
    assertThat(VISIONS.get()).isEqualTo(5);
    System.out.println(
        "MANUAL_IMAGE_PG "
            + JSON.writeValueAsString(
                Map.of(
                    "documents",
                    3,
                    "successful_images",
                    2,
                    "cancelled_images",
                    1,
                    "jobs",
                    jdbc.queryForObject("SELECT COUNT(*) FROM java_task_jobs", Long.class),
                    "embedding_http",
                    EMBEDDINGS.get(),
                    "chat_http",
                    CHATS.get(),
                    "vision_http",
                    VISIONS.get(),
                    "pool_size",
                    1,
                    "files",
                    DIRECTORY.toString())));
  }
}
