package com.tencent.weknora.knowledge;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.security.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.*;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Full public DTO and SQL access boundary compared with actual production Go HTTP. */
class KnowledgeCollectionReadGoHttpParityTest extends KnowledgeFolderGoHttpParityTest {
  @Override
  @TestFactory
  Stream<DynamicTest> folders() throws Exception {
    JsonNode cases;
    try (var in = getClass().getResourceAsStream("/go-knowledge-collection-read-http.json")) {
      cases = json.readTree(in);
    }
    assertThat(cases.size()).isEqualTo(203);
    var no = new AtomicInteger();
    return StreamSupport.stream(cases.spliterator(), false)
        .map(
            row ->
                DynamicTest.dynamicTest(
                    row.path("name").asText(), () -> compare(row, no.incrementAndGet())));
  }

  @Override
  void compare(JsonNode oracle, int no) throws Exception {
    var f = database(no);
    try {
      boolean postgres =
          Boolean.TRUE.equals(
              f.jdbc.execute(
                  (org.springframework.jdbc.core.ConnectionCallback<Boolean>)
                      c -> c.getMetaData().getDatabaseProductName().equals("PostgreSQL")));
      if (!postgres)
        try (var c = f.jdbc.getDataSource().getConnection()) {
          org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(
              c,
              new org.springframework.core.io.FileSystemResource(
                  "../migrations/sqlite/000004_memory.up.sql"));
        }
      for (String table : List.of("knowledges", "knowledge_bases", "tenants"))
        f.jdbc.update("DELETE FROM " + table);
      var orderedSeeds = new ArrayList<JsonNode>();
      oracle.path("seed").forEach(orderedSeeds::add);
      orderedSeeds.sort(
          Comparator.comparingInt(
              seed -> seed.path("table").asText().equals("agent_shares") ? 1 : 0));
      for (var seed : orderedSeeds) {
        var names = new ArrayList<String>();
        var values = new ArrayList<Object>();
        seed.path("row")
            .fields()
            .forEachRemaining(
                e -> {
                  names.add(e.getKey());
                  JsonNode value = e.getValue();
                  try {
                    values.add(
                        value.isObject() && value.has("base64")
                            ? Base64.getDecoder().decode(value.path("base64").asText())
                            : json.treeToValue(value, Object.class));
                  } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                  }
                });
        if (postgres)
          for (int i = 0; i < values.size(); i++)
            if (values.get(i) instanceof byte[] bytes)
              values.set(i, new String(bytes, StandardCharsets.UTF_8));
        f.jdbc.update(
            "INSERT INTO "
                + seed.path("table").asText()
                + "("
                + String.join(",", names)
                + ") VALUES("
                + String.join(",", Collections.nCopies(names.size(), "?"))
                + ")",
            values.toArray());
      }
      String hidden = oracle.path("hidden").asText();
      if (!hidden.isEmpty())
        f.jdbc.execute("ALTER TABLE " + hidden + " RENAME TO hidden_" + hidden);
      if (postgres && "1".equals(System.getenv("WEKNORA_COLLECTION_PARITY_ANALYZE")))
        f.jdbc.execute(
            "ANALYZE " + (hidden.equals("knowledges") ? "hidden_knowledges" : "knowledges"));
      var before = new TreeMap<String, JsonNode>();
      for (String table :
          List.of(
              "knowledges",
              "knowledge_bases",
              "tenants",
              "custom_agents",
              "agent_shares",
              "knowledge_processing_spans")) {
        String actual = table.equals(hidden) ? "hidden_" + table : table;
        before.put(
            actual,
            json.valueToTree(f.jdbc.queryForList("SELECT * FROM " + actual + " ORDER BY id")));
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
      var writes = beans.createBean(KnowledgeBaseWriteAuthorization.class);
      beans.registerSingleton("writes", writes);
      var guard =
          new RootBeanDefinition(Class.forName("com.tencent.weknora.security.NativeRoleGuard"));
      guard.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);
      beans.registerBeanDefinition("guard", guard);
      Set<String> builtin =
          Set.of("builtin-quick-answer", "builtin-smart-reasoning", "builtin-deep-research");
      var reads =
          new KnowledgeBaseReadAuthorization(f.jdbc, new KnowledgeBaseCodec(json), json, builtin);
      var service =
          new KnowledgeCollectionReadService(
              f.jdbc,
              new KnowledgeContentReadRepository(f.jdbc, json),
              reads,
              new SharedAgentCollectionScope(f.jdbc, json, builtin),
              new KnowledgeBaseCodec(json));
      var mvc =
          org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                  new KnowledgeCollectionReadController(service))
              .addInterceptors(
                  (org.springframework.web.servlet.HandlerInterceptor) beans.getBean("guard"))
              .setControllerAdvice(new com.tencent.weknora.api.ApiErrorAdvice())
              .build();
      var scope = oracle.path("scope");
      var caps = new HashSet<String>();
      scope.path("Capabilities").forEach(n -> caps.add(n.asText()));
      var allowed = new HashSet<String>();
      scope.path("KnowledgeBaseIDs").forEach(n -> allowed.add(n.asText()));
      var caller =
          new AuthContext(
              oracle.path("user").asText(),
              1,
              oracle.path("effective_role").asText(),
              false,
              oracle.path("machine").asBoolean() ? 1L : null,
              scope.path("FullAccess").asBoolean(),
              caps,
              allowed);
      var uri = java.net.URI.create(oracle.path("target").asText());
      var request = MockMvcRequestBuilders.get(uri.getRawPath());
      if (uri.getRawQuery() != null)
        for (String pair : uri.getRawQuery().split("&", -1)) {
          String[] kv = pair.split("=", 2);
          if (!kv[0].isEmpty())
            request.queryParam(
                java.net.URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                java.net.URLDecoder.decode(kv.length > 1 ? kv[1] : "", StandardCharsets.UTF_8));
        }
      if (postgres
          && System.getenv("WEKNORA_COLLECTION_PARITY_DEBUG") != null
          && List.of("viewer", "all").contains(oracle.path("variant").asText())
          && uri.getPath().endsWith("/batch")) {
        var debugArgs = new ArrayList<Object>();
        debugArgs.add(oracle.path("variant").asText().equals("all") ? 2L : 1L);
        debugArgs.addAll(
            Arrays.asList(
                request
                    .buildRequest(new org.springframework.mock.web.MockServletContext())
                    .getParameterValues("ids")));
        String sql =
            "SELECT * FROM knowledges WHERE tenant_id=? AND id IN ("
                + String.join(",", Collections.nCopies(debugArgs.size() - 1, "?"))
                + ") AND deleted_at IS NULL";
        System.err.println(
            "JAVA_PLAN "
                + oracle.path("name").asText()
                + " "
                + f.jdbc.queryForObject(
                    "EXPLAIN (FORMAT JSON) " + sql, String.class, debugArgs.toArray()));
      }
      var response =
          mvc.perform(request.requestAttr(CurrentAuth.ATTRIBUTE, caller)).andReturn().getResponse();
      assertThat(response.getStatus())
          .as("actual Go status; Java=%s", response.getContentAsString())
          .isEqualTo(oracle.path("status").asInt());
      assertThat(canonical(json.readTree(response.getContentAsByteArray())))
          .as("complete Go public DTO, scope/order/null/pagination/errors")
          .isEqualTo(canonical(oracle.path("body")));
      var audits =
          f.jdbc.queryForList(
              "SELECT"
                  + " tenant_id,actor_user_id,actor_role,action,request_path,request_method,outcome,details"
                  + " FROM audit_logs ORDER BY id");
      for (var entry : audits)
        entry.put("details", json.readTree(Objects.toString(entry.get("details"))));
      assertThat(canonical(json.valueToTree(audits.isEmpty() ? null : audits)))
          .as("actual role-denial audit")
          .isEqualTo(canonical(oracle.path("audits")));
      for (var entry : before.entrySet())
        assertThat(
                json.<JsonNode>valueToTree(
                    f.jdbc.queryForList("SELECT * FROM " + entry.getKey() + " ORDER BY id")))
            .as("no writes to %s", entry.getKey())
            .isEqualTo(entry.getValue());
      assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM java_task_jobs", Integer.class))
          .isZero();
    } finally {
      release(f);
    }
  }
}
