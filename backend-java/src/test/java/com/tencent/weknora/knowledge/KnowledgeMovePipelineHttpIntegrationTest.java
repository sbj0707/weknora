package com.tencent.weknora.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.retrieval.SQLiteVectorExtension;
import com.tencent.weknora.security.*;
import com.tencent.weknora.task.*;
import com.tencent.weknora.testsupport.OwnedRedis;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.jdbc.core.ConnectionCallback;

/** Native authentication -> admission -> durable worker -> real SQL/vec/FTS/storage effects. */
@org.springframework.boot.test.context.SpringBootTest(
    properties = {
      "weknora.jwt-secret=isolated-native-pipeline-trace-secret",
      "weknora.bridge.enabled=false",
      "weknora.knowledge.processing.enabled=true",
      "weknora.knowledge.upload.enabled=true",
      "weknora.tasks.enabled=true",
      "weknora.tasks.poll-millis=3600000",
      "weknora.retrieve-driver=sqlite",
      "SSRF_WHITELIST=127.0.0.1",
      "SSRF_DNS_WHITELIST_ONLY=true",
      "NEO4J_ENABLE=false",
      "logging.level.root=WARN",
      "debug=false"
    })
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class KnowledgeMovePipelineHttpIntegrationTest {
  static final OwnedRedis REDIS;

  static {
    try {
      REDIS = new OwnedRedis();
    } catch (Exception failure) {
      throw new ExceptionInInitializerError(failure);
    }
  }

  @org.springframework.test.context.DynamicPropertySource
  static void properties(org.springframework.test.context.DynamicPropertyRegistry p) {
    IndexDeletePipelineHttpIntegrationTest.properties(p);
    p.add("REDIS_ADDR", REDIS::address);
    p.add("REDIS_DB", () -> "0");
    p.add("REDIS_PASSWORD", () -> "");
    p.add("REDIS_USERNAME", () -> "");
    p.add("REDIS_USE_TLS", () -> "false");
  }

  @AfterAll
  static void stopRedis() throws Exception {
    REDIS.close();
  }

  @Autowired AutowireCapableBeanFactory beans;
  @Autowired KnowledgeMoveHandler handler;
  final IndexDeletePipelineHttpIntegrationTest s = new IndexDeletePipelineHttpIntegrationTest();
  final ObjectMapper json = new ObjectMapper();
  final List<String> keys = new ArrayList<>();
  String target, owner;

  @BeforeEach
  void setup() throws Exception {
    beans.autowireBean(s);
    s.setup();
    s.f.jdbc.update(
        "UPDATE java_task_jobs SET status='dead' WHERE status IN ('pending','running','retry')");
    s.f.jdbc.update(
        "UPDATE tenant_members SET role='admin',status='active' WHERE user_id IN"
            + " ('caller','owner')");
    s.f.jdbc.update("UPDATE kb_shares SET permission='editor' WHERE id='trace-share'");
    s.f.jdbc.update("UPDATE tenants SET storage_engine_config='{}' WHERE id=8");
    s.f.jdbc.update(
        "UPDATE knowledge_bases SET creator_id='owner',vector_store_id='',summary_model_id='',storage_provider_config=NULL,cos_config='{}' WHERE"
            + " id='trace-kb'");
    target = "move-target-" + UUID.randomUUID();
    s.f.jdbc.update(
        "INSERT INTO"
            + " knowledge_bases(id,name,tenant_id,creator_id,type,embedding_model_id,summary_model_id,indexing_strategy,chunking_config,profile_config,auto_tag_config,question_generation_config)"
            + " SELECT"
            + " ?,?,8,'owner','document',embedding_model_id,'',indexing_strategy,chunking_config,'{}','{}','{}'"
            + " FROM knowledge_bases WHERE id='trace-kb'",
        target,
        target);
    owner =
        s.f.jwt.sign(
            Map.of(
                "user_id",
                "owner",
                "tenant_id",
                8,
                "type",
                "access",
                "exp",
                Instant.now().plusSeconds(3600).getEpochSecond(),
                "jti",
                UUID.randomUUID().toString()));
    s.f.jdbc.update(
        "INSERT INTO auth_tokens(id,user_id,token,token_type,expires_at,is_revoked)"
            + " VALUES(?,'owner',?,'access_token',?,false)",
        UUID.randomUUID().toString(),
        owner,
        Instant.now().plusSeconds(3600).toString());
  }

  @AfterEach
  void stop() {
    s.f.jdbc.update("UPDATE tenants SET storage_engine_config='{}' WHERE id=8");
    s.f.jdbc.execute("DROP TRIGGER IF EXISTS reject_move_chunk");
    s.f.jdbc.execute("DROP TRIGGER IF EXISTS reject_move_document_task");
    for (String key : keys)
      s.f.jdbc.update("DELETE FROM tenant_api_keys WHERE key_hash=?", AuthFilter.hashApiKey(key));
    s.release();
  }

  String state(String id, String column) {
    return s.f.jdbc.queryForObject(
        "SELECT " + column + " FROM knowledges WHERE id=?", String.class, id);
  }

  long quota() {
    return s.f.jdbc.queryForObject("SELECT storage_used FROM tenants WHERE id=8", Long.class);
  }

  byte[] file(String id) throws Exception {
    String path = state(id, "file_path");
    try (var in =
        s.f.storage.forStoredFile(8, state(id, "knowledge_base_id"), path).files().open(path)) {
      return in.readAllBytes();
    }
  }

  JsonNode request(List<String> ids, String mode, String credential, boolean machine, int status)
      throws Exception {
    var body =
        json.createObjectNode()
            .put("source_kb_id", "trace-kb")
            .put("target_kb_id", target)
            .put("mode", mode);
    body.set("knowledge_ids", json.valueToTree(ids));
    var response =
        s.f
            .mvc
            .perform(
                post("/api/v1/knowledge/move")
                    .contentType("application/json")
                    .content(body.toString())
                    .header(
                        machine ? "X-API-Key" : "Authorization",
                        machine ? credential : "Bearer " + credential))
            .andReturn()
            .getResponse();
    assertEquals(status, response.getStatus(), response.getContentAsString());
    return json.readTree(response.getContentAsByteArray());
  }

  JsonNode progress(String task, String credential, boolean machine, int status) throws Exception {
    var response =
        s.f
            .mvc
            .perform(
                get("/api/v1/knowledge/move/progress/" + task)
                    .header(
                        machine ? "X-API-Key" : "Authorization",
                        machine ? credential : "Bearer " + credential))
            .andReturn()
            .getResponse();
    assertEquals(status, response.getStatus(), response.getContentAsString());
    return json.readTree(response.getContentAsByteArray()).path("data");
  }

  String key(List<String> capabilities, List<String> bases, boolean full) throws Exception {
    String key = "move-key-" + UUID.randomUUID();
    s.f.jdbc.update(
        "INSERT INTO"
            + " tenant_api_keys(tenant_id,name,key_hash,api_key,full_access,capabilities,knowledge_base_ids)"
            + " VALUES(8,'Move pipeline',?,?,?,?,?)",
        AuthFilter.hashApiKey(key),
        key,
        full,
        json.writeValueAsString(capabilities),
        json.writeValueAsString(bases));
    keys.add(key);
    return key;
  }

  TaskRecord delivery(String task) {
    String id =
        s.f.jdbc.queryForObject(
            "SELECT task_id FROM java_task_named_keys WHERE queue='low' AND business_id=?",
            String.class,
            task);
    assertNotEquals(task, id);
    return s.f.tasks.get(8, id).orElseThrow();
  }

  TaskRecord run(String task) throws Exception {
    String id = delivery(task).id();
    try (var worker =
        new TaskWorker(s.f.tasks, List.of(handler), 1, Duration.ofSeconds(30), Map.of("low", 1))) {
      worker.poll();
      long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
      while (worker.activeCount() > 0 && System.nanoTime() < deadline) Thread.sleep(10);
      assertEquals(0, worker.activeCount(), "native worker did not settle");
      var result = s.f.tasks.get(8, id).orElseThrow();
      assertNotEquals("pending", result.status());
      assertNotEquals("running", result.status());
      return result;
    }
  }

  void retry(String task, boolean last) {
    s.f.jdbc.update(
        "UPDATE java_task_jobs SET available_at=0,attempts=? WHERE id=? AND status='retry'",
        last ? 3 : 1,
        delivery(task).id());
  }

  JsonNode physical(String id) {
    var result = json.createObjectNode();
    result.set(
        "rows",
        json.valueToTree(
            s.f.jdbc.queryForList(
                "SELECT"
                    + " id,created_at,source_id,source_type,chunk_id,knowledge_id,content,dimension,is_enabled"
                    + " FROM lite_embeddings WHERE knowledge_id=? ORDER BY id",
                id)));
    result.set(
        "vectors",
        json.valueToTree(
            s.f.jdbc.execute(
                (ConnectionCallback<List<Map<String, Object>>>)
                    connection -> {
                      new SQLiteVectorExtension(
                              Path.of(System.getProperty("weknora.sqlite.vec.extension")))
                          .load(connection);
                      try (var statement =
                          connection.prepareStatement(
                              "SELECT e.id,hex(v.embedding) FROM vec_embeddings_3 v JOIN"
                                  + " lite_embeddings e ON e.id=v.rowid WHERE e.knowledge_id=?"
                                  + " ORDER BY e.id")) {
                        statement.setString(1, id);
                        try (var rows = statement.executeQuery()) {
                          var values = new ArrayList<Map<String, Object>>();
                          while (rows.next())
                            values.add(Map.of("id", rows.getLong(1), "vector", rows.getString(2)));
                          return values;
                        }
                      }
                    })));
    // Contentless FTS returns null columns; inspect its actual persisted posting blocks instead.
    result.set(
        "fts",
        json.valueToTree(
            s.f.jdbc.queryForList(
                "SELECT id,hex(block) AS block FROM lite_embeddings_fts_data ORDER BY id")));
    return result;
  }

  void moved(String id, String task) throws Exception {
    assertEquals(target, state(id, "knowledge_base_id"));
    assertEquals("completed", state(id, "parse_status"));
    var marker = json.readTree(state(id, "metadata")).path("_knowledge_transfer");
    assertEquals(task, marker.path("task_id").asText());
    assertEquals("done", marker.path("phase").asText());
    assertEquals(
        0,
        s.f.jdbc.queryForObject(
            "SELECT COUNT(*) FROM chunks WHERE knowledge_id=? AND deleted_at IS NULL AND"
                + " (knowledge_base_id<>? OR tag_id<>'')",
            Long.class,
            id,
            target));
    assertEquals(
        0,
        s.f.jdbc.queryForObject(
            "SELECT COUNT(*) FROM lite_embeddings WHERE knowledge_id=? AND (knowledge_base_id<>? OR"
                + " tag_id<>'')",
            Long.class,
            id,
            target));
    assertEquals(
        0,
        s.f.jdbc.queryForObject(
            "SELECT COUNT(*) FROM knowledge_tag_relations WHERE knowledge_id=?", Long.class, id));
  }

  @Test
  void goPersistedTenantBlobRetainsDefaultProviderAcrossAdmissionAndWorker() throws Exception {
    String id = s.prepared();
    var before = physical(id);
    byte[] original = file(id);
    long used = quota();
    int calls = s.calls.get();
    s.f.jdbc.update(
        "UPDATE tenants SET storage_engine_config=? WHERE id=8",
        json.writeValueAsBytes(json.createObjectNode().put("default_provider", "cos")));
    s.f.jdbc.update(
        "UPDATE knowledge_bases SET storage_provider_config=? WHERE id=?",
        json.writeValueAsBytes(json.createObjectNode().put("provider", "COS")),
        target);
    String task =
        request(List.of(id), "reuse_vectors", owner, false, 200).path("data").path("task_id").asText();
    assertEquals("succeeded", run(task).status());
    moved(id, task);
    assertEquals("completed", progress(task, owner, false, 200).path("status").asText());
    assertEquals(before, physical(id));
    assertEquals(used, quota());
    assertEquals(calls, s.calls.get());
    assertArrayEquals(original, file(id));
  }

  @Test
  void ownerJwtMovesFreshNativeUploadWithoutReembeddingOrChangingFilesQuotaAndPhysicalIds()
      throws Exception {
    String id = s.prepared(), tag = "move-tag-" + UUID.randomUUID();
    // Use the upload/publication timestamps as written: no canonical seed or transfer-only rewrite.
    assertTrue(state(id, "updated_at").contains(" "));
    assertTrue(state(id, "updated_at").endsWith("+00:00"));
    s.f.jdbc.update(
        "INSERT INTO knowledge_tags(id,tenant_id,knowledge_base_id,name)"
            + " VALUES(?,8,'trace-kb','Move source tag')",
        tag);
    s.f.jdbc.update(
        "INSERT INTO knowledge_tag_relations(knowledge_id,tag_id) VALUES(?,?)", id, tag);
    s.f.jdbc.update("UPDATE chunks SET tag_id=? WHERE knowledge_id=?", tag, id);
    s.f.jdbc.update("UPDATE lite_embeddings SET tag_id=? WHERE knowledge_id=?", tag, id);
    var before = physical(id);
    byte[] original = file(id);
    long used = quota();
    int calls = s.calls.get();
    var accepted = request(List.of(id, id), "reuse_vectors", owner, false, 200).path("data");
    assertEquals(1, accepted.path("knowledge_count").asInt());
    String task = accepted.path("task_id").asText();
    assertEquals("pending", progress(task, owner, false, 200).path("status").asText());
    assertEquals("succeeded", run(task).status());
    moved(id, task);
    var completed = progress(task, owner, false, 200);
    assertEquals("completed", completed.path("status").asText());
    assertEquals(100, completed.path("progress").asInt());
    assertEquals(1, completed.path("processed").asInt());
    assertEquals(0, completed.path("failed").asInt());
    assertEquals(before, physical(id));
    assertArrayEquals(original, file(id));
    assertEquals(used, quota());
    assertEquals(calls, s.calls.get());
    var audit =
        s.f.jdbc.queryForList(
            "SELECT actor_user_id,actor_role,scope_id,action,outcome FROM audit_logs WHERE"
                + " target_id=? ORDER BY id",
            task);
    assertEquals(4, audit.size());
    for (var row : audit) {
      assertEquals("owner", row.get("actor_user_id"));
      assertEquals("admin", row.get("actor_role"));
    }
    assertEquals(
        Set.of("trace-kb", target),
        new HashSet<>(audit.stream().map(r -> r.get("scope_id").toString()).toList()));
    progress(task, s.f.foreignToken, false, 404);
    progress("malformed", owner, false, 400);
    s.f.jdbc.update("UPDATE auth_tokens SET is_revoked=true WHERE token=?", owner);
    progress(task, owner, false, 401);
  }

  @Test
  void nativeApiKeyCapabilitiesBothBaseScopesAndCrossTenantRulesRunBeforeEnqueue()
      throws Exception {
    String id = s.prepared();
    request(List.of(id), "reuse_vectors", s.f.callerToken, false, 403);
    request(
        List.of(id),
        "reuse_vectors",
        key(List.of("retrieve"), List.of("trace-kb", target), false),
        true,
        403);
    request(
        List.of(id),
        "reuse_vectors",
        key(List.of("ingest"), List.of("trace-kb"), false),
        true,
        403);
    request(List.of(id), "reuse_vectors", key(List.of("ingest"), List.of(target), true), true, 403);
    assertEquals(
        0,
        s.f.jdbc.queryForObject(
            "SELECT COUNT(*) FROM java_task_jobs WHERE type='knowledge:move' AND payload LIKE ?",
            Long.class,
            "%" + target + "%"));
    String ingest = key(List.of("ingest"), List.of("trace-kb", target), false);
    String task =
        request(List.of(id), "reuse_vectors", ingest, true, 200)
            .path("data")
            .path("task_id")
            .asText();
    progress(task, ingest, true, 403);
    String retrieve = key(List.of("retrieve"), List.of("unrelated"), false);
    assertEquals("pending", progress(task, retrieve, true, 200).path("status").asText());
    assertEquals("succeeded", run(task).status());
    moved(id, task);
    assertEquals("completed", progress(task, retrieve, true, 200).path("status").asText());
    for (var audit :
        s.f.jdbc.queryForList(
            "SELECT actor_user_id,actor_role,details FROM audit_logs WHERE target_id=?", task)) {
      // Go tenant keys retain the oldest real user for display; key scope remains the authority.
      assertEquals("owner", audit.get("actor_user_id"));
      assertEquals("viewer", audit.get("actor_role"));
      var details = json.readTree(audit.get("details").toString());
      assertEquals("user", details.path("trigger").asText());
      assertEquals("Move pipeline", details.path("api_key_name").asText());
      assertTrue(details.path("api_key_id").asLong() > 0);
    }
  }

  @Test
  void reparseMovesBindingChargesCleanupOnceAndPublishesTargetIndexesFromTheOriginalFile()
      throws Exception {
    String id = s.prepared(), path = state(id, "file_path");
    byte[] original = file(id);
    long used = quota(), size = Long.parseLong(state(id, "storage_size"));
    int calls = s.calls.get();
    var oldChunks = s.ids(id);
    assertTrue(size > 0);
    String task =
        request(List.of(id), "reparse", owner, false, 200).path("data").path("task_id").asText();
    assertEquals("succeeded", run(task).status());
    assertEquals("completed", progress(task, owner, false, 200).path("status").asText());
    assertEquals(target, state(id, "knowledge_base_id"));
    assertEquals("pending", state(id, "parse_status"));
    assertEquals("disabled", state(id, "enable_status"));
    assertEquals("0", state(id, "storage_size"));
    assertEquals(used - size, quota());
    assertEquals(0, s.f.count("lite_embeddings", id));
    assertEquals(
        oldChunks.size(),
        s.f.jdbc.queryForObject(
            "SELECT COUNT(*) FROM chunks WHERE knowledge_id=? AND deleted_at IS NOT NULL",
            Integer.class,
            id));
    assertEquals(path, state(id, "file_path"));
    assertArrayEquals(original, file(id));
    assertEquals(calls, s.calls.get());
    var named =
        s.f.jdbc.queryForMap(
            "SELECT * FROM java_task_named_keys WHERE business_id=? AND queue='default'",
            "move-reparse:" + task + ":" + id);
    assertEquals(
        Duration.ofDays(7).toMillis(), ((Number) named.get("retention_millis")).longValue());
    var parse = s.f.claim("default");
    assertEquals(named.get("task_id"), parse.id());
    assertEquals(target, parse.payload().path("knowledge_base_id").asText());
    assertTrue(s.f.document.execute(parse, s.f.cancellation(parse)).path("indexed").asBoolean());
    assertTrue(s.f.tasks.complete(parse, json.createObjectNode()));
    var post = s.f.claim("postprocess");
    assertTrue(s.f.postprocess.execute(post, s.f.cancellation(post)).path("completed").asBoolean());
    assertTrue(s.f.tasks.complete(post, json.createObjectNode()));
    assertEquals("completed", state(id, "parse_status"));
    assertEquals("enabled", state(id, "enable_status"));
    assertEquals(used, quota());
    assertEquals(calls + 1, s.calls.get());
    assertArrayEquals(original, file(id));
    assertTrue(s.f.count("lite_embeddings", id) > 0);
    assertEquals(
        0,
        s.f.jdbc.queryForObject(
            "SELECT COUNT(*) FROM chunks WHERE knowledge_id=? AND deleted_at IS NULL AND"
                + " (tag_id IS NULL OR tag_id<>'')",
            Long.class,
            id));
    assertEquals(
        0,
        s.f.jdbc.queryForObject(
            "SELECT COUNT(*) FROM lite_embeddings WHERE knowledge_id=? AND knowledge_base_id<>?",
            Long.class,
            id,
            target));
  }

  @Test
  void wholeBatchPreflightRejectsInvalidSecondDocumentBeforeAnyClaimAndRecordsFinalFailure()
      throws Exception {
    String first = s.prepared(), second = s.prepared();
    s.f.jdbc.update(
        "UPDATE chunks SET parent_chunk_id='different-document-parent' WHERE knowledge_id=?",
        second);
    var before = physical(first);
    String metadata = state(first, "metadata");
    long used = quota();
    String task =
        request(List.of(first, second), "reuse_vectors", owner, false, 200)
            .path("data")
            .path("task_id")
            .asText();
    assertEquals("retry", run(task).status());
    var running = progress(task, owner, false, 200);
    assertEquals("processing", running.path("status").asText());
    assertEquals("Move preflight failed", running.path("message").asText());
    assertEquals("chunk parent belongs to another document", running.path("error").asText());
    retry(task, true);
    assertEquals("dead", run(task).status());
    assertEquals("failed", progress(task, owner, false, 200).path("status").asText());
    assertEquals("trace-kb", state(first, "knowledge_base_id"));
    assertEquals("trace-kb", state(second, "knowledge_base_id"));
    assertEquals("completed", state(first, "parse_status"));
    assertEquals(metadata, state(first, "metadata"));
    assertEquals(before, physical(first));
    assertEquals(used, quota());
    assertEquals(
        0,
        s.f.jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_logs WHERE target_id=?", Long.class, task));
  }

  @Test
  void partialSqlFailureResumesTheCheckpointAndDoesNotRepeatSuccessfulDocumentsOrReembed()
      throws Exception {
    String first = s.prepared(), second = s.prepared();
    var physical = physical(first);
    long used = quota();
    int calls = s.calls.get();
    s.f.jdbc.execute(
        "CREATE TRIGGER reject_move_chunk BEFORE UPDATE OF knowledge_base_id ON chunks WHEN"
            + " OLD.knowledge_id='"
            + first
            + "' BEGIN SELECT RAISE(ABORT,'controlled chunk move failure'); END");
    String task =
        request(List.of(first, second), "reuse_vectors", owner, false, 200)
            .path("data")
            .path("task_id")
            .asText();
    assertEquals("retry", run(task).status());
    moved(second, task);
    var pending = progress(task, owner, false, 200);
    assertEquals("processing", pending.path("status").asText());
    assertEquals(2, pending.path("processed").asInt());
    assertEquals(1, pending.path("failed").asInt());
    assertEquals("trace-kb", state(first, "knowledge_base_id"));
    assertEquals("processing", state(first, "parse_status"));
    assertEquals(
        "moving",
        json.readTree(state(first, "metadata")).path("_knowledge_transfer").path("phase").asText());
    String successfulTimestamp = state(second, "updated_at");
    s.f.jdbc.execute("DROP TRIGGER reject_move_chunk");
    retry(task, false);
    assertEquals("succeeded", run(task).status());
    moved(first, task);
    moved(second, task);
    assertEquals(successfulTimestamp, state(second, "updated_at"));
    assertEquals(physical, physical(first));
    assertEquals(used, quota());
    assertEquals(calls, s.calls.get());
    assertEquals(
        4,
        s.f.jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_logs WHERE target_id=?", Integer.class, task));
  }

  @Test
  void failedReparseEnqueueRecoversTargetCheckpointWithoutChargingQuotaTwice() throws Exception {
    String id = s.prepared();
    long used = quota(), size = Long.parseLong(state(id, "storage_size"));
    byte[] original = file(id);
    s.f.jdbc.execute(
        "CREATE TRIGGER reject_move_document_task BEFORE INSERT ON java_task_jobs WHEN"
            + " NEW.type='document:process' BEGIN SELECT RAISE(ABORT,'controlled reparse enqueue"
            + " failure'); END");
    String task =
        request(List.of(id), "reparse", owner, false, 200).path("data").path("task_id").asText();
    assertEquals("retry", run(task).status());
    assertEquals(target, state(id, "knowledge_base_id"));
    assertEquals("pending", state(id, "parse_status"));
    assertEquals(used - size, quota());
    assertEquals(
        "reparse_pending",
        json.readTree(state(id, "metadata")).path("_knowledge_transfer").path("phase").asText());
    assertEquals(
        0,
        s.f.jdbc.queryForObject(
            "SELECT COUNT(*) FROM java_task_named_keys WHERE business_id=?",
            Integer.class,
            "move-reparse:" + task + ":" + id));
    s.f.jdbc.execute("DROP TRIGGER reject_move_document_task");
    retry(task, false);
    assertEquals("succeeded", run(task).status());
    assertEquals(used - size, quota());
    assertArrayEquals(original, file(id));
    assertEquals(
        1,
        s.f.jdbc.queryForObject(
            "SELECT COUNT(*) FROM java_task_named_keys WHERE business_id=?",
            Integer.class,
            "move-reparse:" + task + ":" + id));
  }

  @Test
  void legacyIsoTimestampStillConflictsAsGoDoesWithoutRelaxingTheTransferFence() throws Exception {
    String id = s.prepared();
    String old = "2026-01-01T00:00:00.123456789Z";
    s.f.jdbc.update("UPDATE knowledges SET updated_at=? WHERE id=?", old, id);
    var before = physical(id);
    String metadata = state(id, "metadata");
    long used = quota();
    String task =
        request(List.of(id), "reuse_vectors", owner, false, 200)
            .path("data")
            .path("task_id")
            .asText();
    assertEquals("retry", run(task).status());
    assertTrue(
        progress(task, owner, false, 200)
            .path("error")
            .asText()
            .endsWith("knowledge changed during transfer"));
    assertEquals("trace-kb", state(id, "knowledge_base_id"));
    assertEquals("completed", state(id, "parse_status"));
    assertEquals(old, state(id, "updated_at"));
    assertEquals(metadata, state(id, "metadata"));
    assertEquals(before, physical(id));
    assertEquals(used, quota());
  }
}
