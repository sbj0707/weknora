package com.tencent.weknora.knowledge;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.security.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.*;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Real Go HTTP oracle plus exact relation changes, individual commits and unchanged other state.
 */
class KnowledgeBatchTagsGoHttpParityTest extends KnowledgeFolderGoHttpParityTest {
  @Override
  @TestFactory
  Stream<DynamicTest> folders() throws Exception {
    JsonNode rows;
    try (var in = getClass().getResourceAsStream("/go-knowledge-batch-tags-http.json")) {
      rows = json.readTree(in);
    }
    assertThat(rows.size()).isEqualTo(96);
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
      boolean pg =
          Boolean.TRUE.equals(
              f.jdbc.execute(
                  (org.springframework.jdbc.core.ConnectionCallback<Boolean>)
                      c -> c.getMetaData().getDatabaseProductName().equals("PostgreSQL")));
      if (!pg)
        try (var c = f.jdbc.getDataSource().getConnection()) {
          org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(
              c,
              new org.springframework.core.io.FileSystemResource(
                  "../migrations/sqlite/000004_memory.up.sql"));
        }
      for (String table : List.of("knowledges", "knowledge_bases", "tenants"))
        f.jdbc.update("DELETE FROM " + table);
      for (var statement : oracle.path("seed_statements")) f.jdbc.execute(statement.asText());
      var variant = oracle.path("variant").asText();
      var before = new LinkedHashMap<String, JsonNode>();
      for (String table : List.of("knowledges", "knowledge_bases", "tenants", "knowledge_tags")) {
        String actual = hidden(variant, table);
        before.put(
            actual,
            json.valueToTree(f.jdbc.queryForList("SELECT * FROM " + actual + " ORDER BY id")));
      }
      f.jdbc.execute("CREATE TABLE batch_tags_physical_index(id VARCHAR(32),content TEXT)");
      f.jdbc.update(
          "INSERT INTO batch_tags_physical_index VALUES('chunk','unchanged indexed source')");
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
      var scope = oracle.path("scope");
      var capabilities = new HashSet<String>();
      scope.path("Capabilities").forEach(x -> capabilities.add(x.asText()));
      var allowed = new HashSet<String>();
      scope.path("KnowledgeBaseIDs").forEach(x -> allowed.add(x.asText()));
      var caller =
          new AuthContext(
              oracle.path("user").asText(),
              oracle.path("caller_tenant").asLong(),
              oracle.path("effective_role").asText(),
              false,
              oracle.path("machine").asBoolean() ? 1L : null,
              scope.path("FullAccess").asBoolean(),
              capabilities,
              allowed);
      var controller =
          new KnowledgeBatchTagsController(
              new KnowledgeBatchTagsService(f.jdbc, writes, f.manager, json), json);
      var mvc =
          org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
              .addInterceptors(
                  (org.springframework.web.servlet.HandlerInterceptor) beans.getBean("guard"))
              .setControllerAdvice(new com.tencent.weknora.api.ApiErrorAdvice())
              .build();
      var response =
          mvc.perform(
                  MockMvcRequestBuilders.put("/api/v1/knowledge/tags")
                      .content(oracle.path("request_body").asText())
                      .contentType("application/json")
                      .requestAttr(CurrentAuth.ATTRIBUTE, caller))
              .andReturn()
              .getResponse();
      assertThat(response.getStatus())
          .as("actual Go HTTP status")
          .isEqualTo(oracle.path("status").asInt());
      assertThat(canonical(json.readTree(response.getContentAsByteArray())))
          .as("complete Go binder, permissions and error DTO")
          .isEqualTo(canonical(oracle.path("body")));
      var relations =
          f.jdbc.queryForList(
              "SELECT knowledge_id,tag_id,CASE WHEN created_at='2026-01-01 00:00:00+00:00' THEN 0"
                  + " ELSE 1 END AS replaced FROM "
                  + hidden(variant, "knowledge_tag_relations")
                  + " ORDER BY knowledge_id,tag_id");
      assertThat(canonical(json.valueToTree(relations.isEmpty() ? null : relations)))
          .as("Go per-document transaction boundaries, partial commits, rollback and timestamps")
          .isEqualTo(canonical(oracle.path("relations")));
      for (var entry : before.entrySet())
        assertThat(
                json.<JsonNode>valueToTree(
                    f.jdbc.queryForList("SELECT * FROM " + entry.getKey() + " ORDER BY id")))
            .as("unchanged " + entry.getKey())
            .isEqualTo(entry.getValue());
      assertThat(oracle.path("documents_unchanged").asBoolean()).isTrue();
      var audits =
          f.jdbc.queryForList(
              "SELECT"
                  + " tenant_id,actor_user_id,actor_role,action,request_path,request_method,outcome,details"
                  + " FROM audit_logs ORDER BY id");
      for (var a : audits) a.put("details", json.readTree(Objects.toString(a.get("details"))));
      assertThat(canonical(json.valueToTree(audits.isEmpty() ? null : audits)))
          .as("Go original-caller denial audit and no invented mutation audit")
          .isEqualTo(canonical(oracle.path("audits")));
      assertThat(f.jdbc.queryForList("SELECT * FROM batch_tags_physical_index"))
          .containsExactly(Map.of("id", "chunk", "content", "unchanged indexed source"));
      assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM java_task_jobs", Integer.class))
          .isZero();
    } finally {
      release(f);
    }
  }

  static String hidden(String variant, String table) {
    return ((variant.equals("knowledge-missing") || variant.equals("inferred-knowledge-missing"))
                && table.equals("knowledges")
            || variant.equals("tags-missing") && table.equals("knowledge_tags")
            || variant.equals("relations-missing") && table.equals("knowledge_tag_relations"))
        ? "hidden_" + table
        : table;
  }
}
