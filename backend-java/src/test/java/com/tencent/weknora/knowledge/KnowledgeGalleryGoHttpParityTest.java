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

/** Actual Go HTTP responses including DB-trigger projection, policy tiers, dedup and paging. */
class KnowledgeGalleryGoHttpParityTest extends KnowledgeFolderGoHttpParityTest {
  @Override
  @TestFactory
  Stream<DynamicTest> folders() throws Exception {
    JsonNode rows;
    try (var in = getClass().getResourceAsStream("/go-knowledge-gallery-http.json")) {
      rows = json.readTree(in);
    }
    assertThat(rows.size()).isEqualTo(152);
    var n = new AtomicInteger();
    return StreamSupport.stream(rows.spliterator(), false)
        .map(
            row ->
                DynamicTest.dynamicTest(
                    row.path("name").asText(), () -> compare(row, n.incrementAndGet())));
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
          try (var st = c.createStatement()) {
            st.executeUpdate(
                java.nio.file.Files.readString(
                    java.nio.file.Path.of("../migrations/sqlite/000032_chunk_images.up.sql")));
          }
        }
      for (String table :
          List.of("chunks", "knowledges", "knowledge_bases", "tenants", "users", "system_settings"))
        f.jdbc.update("DELETE FROM " + table);
      for (var statement : oracle.path("seed_statements")) f.jdbc.execute(statement.asText());
      var before = new LinkedHashMap<String, JsonNode>();
      for (String table :
          List.of(
              "chunks",
              "chunk_images",
              "knowledges",
              "knowledge_bases",
              "tenants",
              "users",
              "system_settings")) {
        if (oracle
            .path("variant")
            .asText()
            .equals(
                switch (table) {
                  case "chunk_images" -> "images-missing";
                  case "knowledges" -> "documents-missing";
                  case "users" -> "users-missing";
                  case "system_settings" -> "settings-missing";
                  default -> "-";
                })) table = "hidden_" + table;
        before.put(table, json.valueToTree(f.jdbc.queryForList("SELECT * FROM " + table)));
      }
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
      beans.registerSingleton("writes", beans.createBean(KnowledgeBaseWriteAuthorization.class));
      var guard =
          new RootBeanDefinition(Class.forName("com.tencent.weknora.security.NativeRoleGuard"));
      guard.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);
      beans.registerBeanDefinition("guard", guard);
      var scope = oracle.path("scope");
      var caps = new HashSet<String>();
      scope.path("Capabilities").forEach(x -> caps.add(x.asText()));
      var ids = new HashSet<String>();
      scope.path("KnowledgeBaseIDs").forEach(x -> ids.add(x.asText()));
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
      var reads =
          new KnowledgeBaseReadAuthorization(f.jdbc, new KnowledgeBaseCodec(json), json, Set.of());
      var config = new GalleryConfigService(f.jdbc, json);
      var mvc =
          org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                  new KnowledgeGalleryController(
                      reads, config, new GalleryImageRepository(f.jdbc, json)))
              .addInterceptors(
                  (org.springframework.web.servlet.HandlerInterceptor) beans.getBean("guard"))
              .setControllerAdvice(new com.tencent.weknora.api.ApiErrorAdvice())
              .build();
      var uri = java.net.URI.create(oracle.path("target").asText());
      var builder =
          MockMvcRequestBuilders.get(uri.getRawPath()).requestAttr(CurrentAuth.ATTRIBUTE, caller);
      if (uri.getRawQuery() != null)
        for (String pair : uri.getRawQuery().split("&")) {
          String[] parts = pair.split("=", 2);
          builder.param(
              java.net.URLDecoder.decode(parts[0], java.nio.charset.StandardCharsets.UTF_8),
              java.net.URLDecoder.decode(
                  parts.length == 2 ? parts[1] : "", java.nio.charset.StandardCharsets.UTF_8));
        }
      var response = mvc.perform(builder).andReturn().getResponse();
      assertThat(response.getStatus())
          .as("Go HTTP status")
          .isEqualTo(oracle.path("status").asInt());
      assertThat(canonical(json.readTree(response.getContentAsByteArray())))
          .as("complete actual Go gallery response")
          .isEqualTo(canonical(oracle.path("body")));
      for (var e : before.entrySet())
        assertThat(json.<JsonNode>valueToTree(f.jdbc.queryForList("SELECT * FROM " + e.getKey())))
            .as("read leaves " + e.getKey() + " unchanged")
            .isEqualTo(e.getValue());
      assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM java_task_jobs", Integer.class))
          .isZero();
    } finally {
      release(f);
    }
  }
}
