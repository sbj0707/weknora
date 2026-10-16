package com.tencent.weknora.knowledge;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.security.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Actual Gin/service/SQL/Redis admission; generated UUID/time values are validated, not guessed.
 */
class ManualKnowledgeGoHttpParityTest {
  @TempDir Path temp;
  final ObjectMapper json = new ObjectMapper();

  static {
    try {
      com.tencent.weknora.retrieval.NativeDatabaseRuntime.initialize();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @TestFactory
  Stream<DynamicTest> actualGoManualKnowledge() throws Exception {
    JsonNode cases;
    try (var input = getClass().getResourceAsStream(resource())) {
      cases = json.readTree(input);
    }
    assertThat(cases.size()).isEqualTo(caseCount());
    var seq = new AtomicInteger();
    return StreamSupport.stream(cases.spliterator(), false)
        .map(
            c ->
                DynamicTest.dynamicTest(
                    c.path("name").asText(), () -> compare(c, seq.incrementAndGet())));
  }

  void compare(JsonNode oracle, int no) throws Exception {
    var f = database(no);
    try {
      String name = oracle.path("name").asText();
      f.jdbc.update(
          "UPDATE knowledge_bases SET"
              + " creator_id='caller',summary_model_id='',indexing_strategy='{}' WHERE id='kb'");
      f.jdbc.update(
          "INSERT INTO"
              + " knowledge_bases(id,tenant_id,creator_id,name,type,embedding_model_id,summary_model_id)"
              + " VALUES('other-kb',1,'other','Other','faq','embedding','')");
      f.jdbc.update(
          "INSERT INTO"
              + " knowledge_tags(id,tenant_id,knowledge_base_id,name,seq_id,color,sort_order,created_at,updated_at)"
              + " VALUES('tag',1,'kb','Tag',1,'#fff',0,?,?),('other-tag',1,'other-kb','Other',2,'',0,?,?)",
          seedTime(),
          seedTime(),
          seedTime(),
          seedTime());
      String meta =
          "{\"content\":\"Old manual"
              + " text\",\"format\":\"markdown\",\"status\":\"publish\",\"version\":2,\"updated_at\":\"2026-01-01T00:00:00Z\"}";
      if (name.endsWith("-metadata-null")) meta = "null";
      if (name.endsWith("-metadata-array")) meta = "[]";
      if (name.endsWith("-metadata-invalid-version"))
        meta = "{\"content\":\"Old\",\"version\":\"bad\"}";
      if (name.endsWith("-preserve-transfer"))
        meta =
            "{\"content\":\"Old\",\"version\":3,\"_knowledge_transfer\":{\"operation\":\"copy\",\"phase\":\"moving\"},\"custom\":\"discarded\"}";
      if (name.endsWith("-preserve-process"))
        meta =
            "{\"content\":\"Old\",\"version\":3,\"process_overrides\":{\"summary_enabled\":false},\"custom\":\"discarded\"}";
      f.jdbc.update(
          "INSERT INTO"
              + " knowledges(id,tenant_id,knowledge_base_id,type,source,title,parse_status,enable_status,description,file_name,file_type,embedding_model_id,metadata,created_at,updated_at)"
              + " VALUES('doc',1,'kb','manual','manual','Old title','completed','enabled','Old"
              + " description','Old title.md','manual','old-embedding',?,?,?)",
          meta,
          seedTime(),
          seedTime());
      if (name.endsWith("-not-manual"))
        f.jdbc.update("UPDATE knowledges SET type='file' WHERE id='doc'");
      if (name.endsWith("-missing-doc")) f.jdbc.update("DELETE FROM knowledges WHERE id='doc'");
      if (name.contains("-shared-")) {
        f.jdbc.update(
            "INSERT INTO organizations(id,name,owner_tenant_id,owner_id)"
                + " VALUES('org','Org',1,'caller')");
        if (!name.endsWith("-shared-no-source"))
          f.jdbc.update(
              "INSERT INTO organization_tenant_members(id,organization_id,tenant_id,role)"
                  + " VALUES('source','org',1,'admin')");
        f.jdbc.update(
            "INSERT INTO organization_tenant_members(id,organization_id,tenant_id,role)"
                + " VALUES('member','org',2,'editor')");
        f.jdbc.update(
            "INSERT INTO"
                + " kb_shares(id,knowledge_base_id,organization_id,source_tenant_id,permission,shared_by_user_id)"
                + " VALUES('share','kb','org',1,?,'caller')",
            name.endsWith("-shared-viewer") ? "viewer" : "editor");
        if (name.endsWith("-shared-revoked"))
          f.jdbc.update("UPDATE kb_shares SET deleted_at=CURRENT_TIMESTAMP WHERE id='share'");
      }
      var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
      beans.registerSingleton("jdbc", f.jdbc);
      beans.registerSingleton("json", json);
      beans.registerSingleton("tx", f.manager);
      beans.registerSingleton("authRepository", new AuthRepository(f.jdbc, json));
      beans.registerSingleton("codec", new KnowledgeBaseCodec(json));
      var audit =
          new org.springframework.beans.factory.support.RootBeanDefinition(
              Class.forName("com.tencent.weknora.security.DeniedRoleAuditService"));
      audit.setAutowireMode(
          org.springframework.beans.factory.support.AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);
      beans.registerBeanDefinition("audit", audit);
      var writes = beans.createBean(KnowledgeBaseWriteAuthorization.class);
      var tasks = json.createArrayNode();
      KnowledgeFileUpload.Enqueuer enqueue =
          (owner, type, payload, options) -> {
            if (name.endsWith("-queue-error"))
              throw new IllegalStateException("controlled enqueue failure");
            tasks.add(
                json.createObjectNode()
                    .put("type", type)
                    .put("queue", options.queue())
                    .put("max_retry", options.maxRetries())
                    .put("timeout", options.timeout().toMillis())
                    .set("payload", payload.deepCopy()));
            return UUID.randomUUID().toString();
          };
      var repository = new ManualKnowledgeRepository(f.jdbc, f.knowledge, json);
      var service =
          new ManualKnowledgeService(
              repository, json, enqueue, null, Clock.fixed(generatedTime(oracle), ZoneOffset.UTC));
      var scope = oracle.path("scope");
      var caps = new HashSet<String>();
      scope.path("Capabilities").forEach(n -> caps.add(n.asText()));
      var allowed = new HashSet<String>();
      scope.path("KnowledgeBaseIDs").forEach(n -> allowed.add(n.asText()));
      var caller =
          new AuthContext(
              oracle.path("user").asText(),
              oracle.path("caller_tenant").asLong(),
              oracle.path("role").asText(),
              false,
              oracle.path("machine").asBoolean() ? scope.path("KeyID").asLong() : null,
              scope.path("FullAccess").asBoolean(),
              caps,
              allowed);
      var mvc =
          org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                  new ManualKnowledgeController(repository, service, writes, json))
              .setControllerAdvice(new com.tencent.weknora.api.ApiErrorAdvice())
              .build();
      var response =
          mvc.perform(
                  MockMvcRequestBuilders.request(
                          org.springframework.http.HttpMethod.valueOf(
                              oracle.path("method").asText()),
                          oracle.path("url").asText())
                      .requestAttr(CurrentAuth.ATTRIBUTE, caller)
                      .contentType("application/json")
                      .content(oracle.path("request").asText()))
              .andReturn()
              .getResponse();
      assertThat(response.getStatus())
          .as("actual Go HTTP status")
          .isEqualTo(oracle.path("status").asInt());
      assertThat(canonical(json.readTree(response.getContentAsByteArray())))
          .as("complete actual Go manual DTO/binder/error")
          .isEqualTo(canonical(oracle.path("body")));
      var rows =
          json.valueToTree(
              f.jdbc.queryForList(
                  "SELECT"
                      + " id,tenant_id,knowledge_base_id,type,title,description,source,channel,parse_status,pending_subtasks_count,summary_status,enable_status,embedding_model_id,file_name,file_type,storage_size,CAST(metadata"
                      + " AS TEXT) AS metadata,CAST(custom_metadata AS TEXT) AS"
                      + " custom_metadata,error_message,processed_at FROM knowledges ORDER BY id"));
      assertThat(rowMap(rows))
          .as("complete SQL manual row state by generated identity")
          .isEqualTo(rowMap(oracle.path("knowledges")));
      assertThat(canonical(tasks))
          .as("actual Go Asynq type/payload/queue/retry/timeout")
          .isEqualTo(canonical(oracle.path("tasks")));
      var relations =
          json.valueToTree(
              f.jdbc.queryForList(
                  "SELECT knowledge_id,tag_id FROM knowledge_tag_relations ORDER BY"
                      + " knowledge_id,tag_id"));
      assertThat(canonical(relations))
          .as("tag relations and update nonmutation")
          .isEqualTo(
              canonical(
                  oracle.path("relations").isNull()
                      ? json.createArrayNode()
                      : oracle.path("relations")));
      var audits =
          json.valueToTree(
              f.jdbc.queryForList(
                  "SELECT"
                      + " tenant_id,actor_user_id,actor_role,action,target_type,target_id,scope_type,scope_id,outcome,CAST(details"
                      + " AS TEXT) AS details FROM audit_logs WHERE action IN"
                      + " ('knowledge.created','knowledge.updated') ORDER BY id"));
      assertThat(canonical(audits))
          .as("business audit original actor and execution owner")
          .isEqualTo(
              canonical(
                  oracle.path("audits").isNull() ? json.createArrayNode() : oracle.path("audits")));
    } finally {
      release(f);
    }
  }

  Instant generatedTime(JsonNode oracle) throws Exception {
    String created = oracle.path("body").path("data").path("created_at").asText();
    if (oracle.path("method").asText().equals("POST") && !created.isEmpty())
      return Instant.parse(created);
    for (var row : oracle.path("knowledges")) {
      JsonNode meta = row.path("metadata");
      if (meta.isTextual()) meta = json.readTree(meta.asText());
      String t = meta.path("updated_at").asText();
      if (!t.isEmpty() && !t.equals("2026-01-01T00:00:00Z")) return Instant.parse(t);
    }
    return Instant.parse("2026-10-15T00:00:00Z");
  }

  JsonNode rowMap(JsonNode rows) {
    var out = json.createObjectNode();
    for (var row : rows) {
      var n = canonical(row);
      out.set(n.path("id").asText(), n);
    }
    return out;
  }

  JsonNode canonical(JsonNode value) {
    if (value.isNumber()) return DecimalNode.valueOf(value.decimalValue().stripTrailingZeros());
    if (value.isArray()) {
      var a = json.createArrayNode();
      value.forEach(n -> a.add(canonical(n)));
      return a;
    }
    if (!value.isObject()) return value;
    var out = json.createObjectNode();
    value
        .fields()
        .forEachRemaining(
            e -> {
              String key = e.getKey();
              JsonNode node = e.getValue();
              if (Set.of("metadata", "custom_metadata", "details").contains(key)
                  && node.isTextual())
                try {
                  node = json.readTree(node.asText());
                } catch (Exception ex) {
                  throw new RuntimeException(ex);
                }
              if (Set.of("id", "knowledge_id", "target_id").contains(key)
                  && node.isTextual()
                  && !Set.of("doc", "tag", "other-tag").contains(node.asText())) {
                assertThat(UUID.fromString(node.asText()).version()).isEqualTo(4);
                out.put(key, "__created_doc__");
              } else if (key.equals("task_id") && node.isTextual()) {
                assertThat(UUID.fromString(node.asText()).version()).isEqualTo(4);
                out.put(key, "__task__");
              } else if (Set.of("created_at", "updated_at").contains(key)
                  && node.isTextual()
                  && !node.asText().equals("2026-01-01T00:00:00Z")) {
                assertThat(Instant.parse(node.asText())).isNotNull();
                out.put(key, "__generated_time__");
              } else out.set(key, canonical(node));
            });
    return out;
  }

  String resource() {
    return "/go-manual-public.json";
  }

  int caseCount() {
    return 98;
  }

  DocumentProcessingCompatibilityTest database(int n) throws Exception {
    var f = new DocumentProcessingCompatibilityTest();
    f.temp = temp;
    f.database(n, false);
    return f;
  }

  Object seedTime() {
    return "2026-01-01 00:00:00";
  }

  void release(DocumentProcessingCompatibilityTest f) {}
}
