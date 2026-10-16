package com.tencent.weknora.knowledge;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.model.client.*;
import com.tencent.weknora.retrieval.*;
import com.tencent.weknora.security.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.*;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Production Go handler/service/repository oracle, including failures after independently committed
 * phases.
 */
class KnowledgeImageGoHttpParityTest extends KnowledgeFolderGoHttpParityTest {
  @Override
  @TestFactory
  Stream<DynamicTest> folders() throws Exception {
    JsonNode rows;
    try (var in = getClass().getResourceAsStream("/go-knowledge-image-update-http.json")) {
      rows = json.readTree(in);
    }
    assertThat(rows.size()).isEqualTo(92);
    var no = new AtomicInteger();
    return StreamSupport.stream(rows.spliterator(), false)
        .map(
            row ->
                DynamicTest.dynamicTest(
                    row.path("name").asText(), () -> compare(row, no.incrementAndGet())));
  }

  @Override
  void compare(JsonNode oracle, int no) throws Exception {
    var f = database(no);
    try {
      if (!postgres())
        try (var c = f.source.getConnection()) {
          org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(
              c,
              new org.springframework.core.io.FileSystemResource(
                  "../migrations/sqlite/000004_memory.up.sql"));
          try (var st = c.createStatement()) {
            st.executeUpdate(
                java.nio.file.Files.readString(
                    java.nio.file.Path.of("../migrations/sqlite/000032_chunk_images.up.sql")));
          }
        }
      for (String table : List.of("chunks", "knowledges", "knowledge_bases", "tenants"))
        f.jdbc.update("DELETE FROM " + table);
      for (var statement : oracle.path("seed_statements")) f.jdbc.execute(statement.asText());
      var retained = new LinkedHashMap<String, JsonNode>();
      for (String table :
          List.of("tenants", "knowledge_bases", "java_task_jobs", "chunk_revisions"))
        retained.put(table, json.valueToTree(f.jdbc.queryForList("SELECT * FROM " + table)));
      var beans = new DefaultListableBeanFactory();
      beans.registerSingleton("jdbc", f.jdbc);
      beans.registerSingleton("json", json);
      beans.registerSingleton("tx", f.manager);
      beans.registerSingleton("authRepository", new AuthRepository(f.jdbc, json));
      beans.registerSingleton("codec", new KnowledgeBaseCodec(json));
      var audit =
          new RootBeanDefinition(
              Class.forName("com.tencent.weknora.security.DeniedRoleAuditService"));
      audit.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);
      beans.registerBeanDefinition("audit", audit);
      var writes = beans.createBean(KnowledgeBaseWriteAuthorization.class);
      beans.registerSingleton("writes", writes);
      var guard =
          new RootBeanDefinition(Class.forName("com.tencent.weknora.security.NativeRoleGuard"));
      guard.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);
      beans.registerBeanDefinition("guard", guard);
      var calls = new ArrayList<Object>();
      String variant = oracle.path("variant").asText();
      ChunkEditService.Embeddings embeddings =
          (owner, id) -> {
            calls.add(Map.of("op", "model"));
            if (variant.equals("model-failure"))
              throw new IllegalStateException("controlled model failure");
            return new EmbeddingModel() {
              public String modelId() {
                return "embedding";
              }

              public String modelName() {
                return "image-test";
              }

              public int dimensions() {
                return 3;
              }

              public float[][] embed(CallContext context, List<String> texts, boolean query) {
                return texts.stream().map(t -> new float[] {1, 0, 0}).toArray(float[][]::new);
              }
            };
          };
      IndexWriter writer =
          new IndexWriter() {
            public void prepare(Set<Integer> dimensions) {}

            public void batchSave(
                java.sql.Connection c, List<IndexEntry> entries, Map<String, float[]> vectors) {
              var rows = new ArrayList<Object>();
              for (var entry : entries)
                rows.add(
                    Map.of(
                        "source_id",
                        entry.sourceId(),
                        "chunk_id",
                        entry.chunkId(),
                        "knowledge_id",
                        entry.knowledgeId(),
                        "knowledge_base_id",
                        entry.knowledgeBaseId(),
                        "content",
                        entry.content(),
                        "enabled",
                        entry.enabled(),
                        "tag_id",
                        entry.tagId()));
              calls.add(Map.of("op", "save", "entries", rows));
              if (variant.equals("save-index-failure"))
                throw new IllegalStateException("controlled index save failure");
            }

            public List<String> sourceIdsByKnowledge(java.sql.Connection c, List<String> ids) {
              throw new AssertionError("unexpected document-wide lookup");
            }

            public void deleteByKnowledge(java.sql.Connection c, List<String> ids) {
              throw new AssertionError("unexpected document-wide delete");
            }

            public void deleteBySource(java.sql.Connection c, List<String> ids) {
              throw new AssertionError("unexpected source delete");
            }

            public void deleteByChunk(java.sql.Connection c, List<String> ids) {
              calls.add(Map.of("op", "delete", "ids", ids));
              if (variant.equals("delete-index-failure"))
                throw new IllegalStateException("controlled index delete failure");
            }

            public long estimateStorageSize(
                List<IndexEntry> entries, Map<String, float[]> vectors) {
              throw new AssertionError("image edits never adjust quota");
            }
          };
      var scope = oracle.path("scope");
      var caps = new HashSet<String>();
      scope.path("Capabilities").forEach(n -> caps.add(n.asText()));
      var ids = new HashSet<String>();
      scope.path("KnowledgeBaseIDs").forEach(n -> ids.add(n.asText()));
      var caller =
          new AuthContext(
              oracle.path("user").asText(),
              oracle.path("caller_tenant").asLong(),
              oracle.path("effective_role").asText(),
              false,
              oracle.path("machine").asBoolean() ? 1L : null,
              scope.path("FullAccess").asBoolean(),
              caps,
              ids);
      var service =
          new KnowledgeImageService(
              f.jdbc, json, writes, embeddings, (owner, kb) -> writer, f.manager);
      var mvc =
          org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                  new KnowledgeImageController(service, json))
              .addInterceptors(
                  (org.springframework.web.servlet.HandlerInterceptor) beans.getBean("guard"))
              .setControllerAdvice(new com.tencent.weknora.api.ApiErrorAdvice())
              .build();
      var response =
          mvc.perform(
                  MockMvcRequestBuilders.put(oracle.path("target").asText())
                      .contentType("application/json")
                      .content(oracle.path("request_body").asText())
                      .requestAttr(CurrentAuth.ATTRIBUTE, caller))
              .andReturn()
              .getResponse();
      assertThat(response.getStatus())
          .as("actual Go HTTP status")
          .isEqualTo(oracle.path("status").asInt());
      assertThat(canonical(json.readTree(response.getContentAsByteArray())))
          .as("complete actual Go JSON response")
          .isEqualTo(canonical(oracle.path("body")));
      var rows =
          f.jdbc.queryForList(
              "SELECT"
                  + " id,seq_id,tenant_id,knowledge_id,knowledge_base_id,content,chunk_type,parent_chunk_id,image_info,is_enabled,flags,status,chunk_index,start_at,end_at,content_revision,index_status,last_editor_id,CASE"
                  + " WHEN updated_at=? THEN 0 ELSE 1 END AS updated FROM chunks ORDER BY seq_id",
              seedTime());
      var replacements = new LinkedHashMap<String, String>();
      for (var row : rows) {
        String id = Objects.toString(row.get("id"));
        if (id.length() == 36) {
          assertThat(UUID.fromString(id).toString()).isEqualTo(id);
          replacements.put(id, "<new-" + row.get("chunk_type") + ">");
        }
        Object enabled = row.get("is_enabled");
        row.put(
            "is_enabled", enabled instanceof Boolean b ? b : ((Number) enabled).intValue() != 0);
      }
      assertThat(canonical(normalize(json.valueToTree(rows), replacements)))
          .as("exact partial commits, raw image data, default flags and revisions")
          .isEqualTo(canonical(oracle.path("chunks")));
      assertThat(canonical(indexCalls(normalize(json.valueToTree(calls), replacements))))
          .as(
              "Go model resolution/delete/save order and complete batch (Go dedup map has no entry"
                  + " ordering)")
          .isEqualTo(canonical(indexCalls(oracle.path("calls"))));
      assertThat(
              canonical(
                  json.valueToTree(
                      f.jdbc.queryForList(
                          "SELECT id,file_hash,CASE WHEN updated_at=? THEN 0 ELSE 1 END AS updated"
                              + " FROM knowledges ORDER BY id",
                          seedTime()))))
          .as("Go chained MD5 and best effort hash update")
          .isEqualTo(canonical(oracle.path("documents")));
      var audits =
          f.jdbc.queryForList(
              "SELECT"
                  + " tenant_id,actor_user_id,actor_role,action,request_path,request_method,outcome,details"
                  + " FROM audit_logs ORDER BY id");
      for (var row : audits)
        row.put("details", json.readTree(Objects.toString(row.get("details"))));
      assertThat(canonical(json.valueToTree(audits.isEmpty() ? null : audits)))
          .isEqualTo(canonical(oracle.path("audits")));
      for (var entry : retained.entrySet())
        assertThat(
                json.<JsonNode>valueToTree(f.jdbc.queryForList("SELECT * FROM " + entry.getKey())))
            .as(entry.getKey() + " unchanged")
            .isEqualTo(entry.getValue());
    } finally {
      release(f);
    }
  }

  JsonNode normalize(JsonNode value, Map<String, String> replacements) throws Exception {
    String raw = json.writeValueAsString(value);
    for (var entry : replacements.entrySet()) raw = raw.replace(entry.getKey(), entry.getValue());
    return json.readTree(raw);
  }

  boolean postgres() {
    return false;
  }

  JsonNode indexCalls(JsonNode value) {
    var out = value.deepCopy();
    for (var call : out)
      if (call.path("entries").isArray()) {
        var sorted = new ArrayList<JsonNode>();
        call.path("entries").forEach(sorted::add);
        sorted.sort(Comparator.comparing(n -> n.path("source_id").asText()));
        var rows = json.createArrayNode();
        sorted.forEach(rows::add);
        ((ObjectNode) call).set("entries", rows);
      }
    return out;
  }
}
