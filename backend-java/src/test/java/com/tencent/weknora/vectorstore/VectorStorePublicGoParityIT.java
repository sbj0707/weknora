package com.tencent.weknora.vectorstore;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.model.*;
import com.tencent.weknora.model.client.*;
import com.tencent.weknora.security.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Actual public Go handler/service/SQL/ES/middleware; generated IDs and cipher nonces are verified
 * separately.
 */
class VectorStorePublicGoParityIT {
  Map<String, String> aliases = Map.of();
  final ObjectMapper json = new ObjectMapper();
  final GoSecretCodec secrets = new GoSecretCodec("0123456789abcdef0123456789abcdef");
  final Clock clock = Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC);

  @TestFactory
  Stream<DynamicTest> publicGo() throws Exception {
    JsonNode fixture;
    if (System.getProperty("weknora.vector.public.fixture") != null)
      fixture =
          json.readTree(
              Files.readAllBytes(Path.of(System.getProperty("weknora.vector.public.fixture"))));
    else
      try (var in = getClass().getResourceAsStream("/go-vector-store-public.json")) {
        fixture = json.readTree(in);
      }
    assertEquals(133, fixture.path("cases").size());
    return StreamSupport.stream(fixture.path("cases").spliterator(), false)
        .map(c -> DynamicTest.dynamicTest(c.path("scene").path("Name").asText(), () -> compare(c)));
  }

  void compare(JsonNode oracle) throws Exception {
    var originalSource = database();
    var reads = new AtomicInteger();
    var enabled = new AtomicBoolean();
    var source = observed(originalSource, reads, enabled);
    SafeModelHttp http = null;
    VectorStoreRegistry registry = null;
    com.sun.net.httpserver.HttpServer probe = null;
    var probeRequests = new java.util.concurrent.CopyOnWriteArrayList<JsonNode>();
    try {
      prepare(originalSource);
      var jdbc = new JdbcTemplate(source);
      jdbc.update(
          "INSERT INTO tenants(id,name,business)"
              + " VALUES(1,'Owner','fixture'),(2,'Foreign','fixture')");
      var scene = oracle.path("scene");
      String address = System.getProperty("weknora.es.url");
      assertNotNull(address);
      var env =
          new MockEnvironment()
              .withProperty("SSRF_WHITELIST", "127.0.0.1")
              .withProperty("RETRIEVE_DRIVER", scene.path("Env").asText());
      scene
          .path("Settings")
          .fields()
          .forEachRemaining(
              e ->
                  env.withProperty(
                      e.getKey(),
                      e.getValue().asText().replace("http://127.0.0.1:58599", address)));
      String seedAddress = address;
      aliases = new HashMap<>();
      aliases.put("http://127.0.0.1:58599", address);
      if (!scene.path("Probe").asText().isEmpty()) {
        String mode = scene.path("Probe").asText();
        probe =
            com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        probe.createContext(
            "/",
            exchange -> {
              try {
                String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                boolean basic = authorization != null && authorization.startsWith("Basic ");
                boolean correct =
                    basic
                        && new String(
                                Base64.getDecoder().decode(authorization.substring(6)),
                                StandardCharsets.UTF_8)
                            .equals("fixture:synthetic-secret");
                probeRequests.add(
                    json.createObjectNode()
                        .put("method", exchange.getRequestMethod())
                        .put("path", exchange.getRequestURI().getPath())
                        .put("has_basic", basic)
                        .put("basic_correct", correct));
                exchange.getResponseHeaders().set("X-Elastic-Product", "Elasticsearch");
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                int code = 200;
                String output = "";
                if (!exchange.getRequestURI().getPath().equals("/"))
                  output =
                      "{\"weknora-crud-created\":{\"mappings\":{\"properties\":{\"chunk_id\":{\"type\":\"keyword\"}}}}}";
                else
                  switch (mode) {
                    case "forbidden" -> code = 401;
                    case "redirect" -> {
                      code = 302;
                      exchange.getResponseHeaders().set("Location", "http://127.0.0.2:9200/");
                    }
                    case "invalid-json" -> output = "not-json";
                    case "number-version" -> output = "{\"version\":{\"number\":123}}";
                    case "trailing" -> output = "{\"version\":{\"number\":\"8.14.0\"}}{}";
                    case "large" ->
                        output = " ".repeat(5000) + "{\"version\":{\"number\":\"8.14.0\"}}";
                    case "no-version" -> output = "{}";
                    default -> output = "{\"version\":{\"number\":\"8.14.0\"}}";
                  }
                if (exchange.getRequestMethod().equals("HEAD")) {
                  exchange.sendResponseHeaders(code, -1);
                } else {
                  byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
                  exchange.sendResponseHeaders(code, bytes.length);
                  exchange.getResponseBody().write(bytes);
                }
              } finally {
                exchange.close();
              }
            });
        probe.start();
        seedAddress = "http://127.0.0.1:" + probe.getAddress().getPort();
        aliases.put(oracle.path("probe_address").asText(), seedAddress);
      }
      var metadata = new VectorStoreMetadata(jdbc, json, secrets);
      var repo =
          new VectorStorePublicRepository(
              jdbc, new DataSourceTransactionManager(source), metadata, secrets, json, clock);
      for (String id : List.of("seed", "foreign", "deleted")) {
        var cc =
            json.createObjectNode()
                .put("addr", seedAddress)
                .put("password", secrets.encryptLegacyWhenConfigured("seed-password"))
                .put("api_key", secrets.encryptLegacyWhenConfigured("seed-api"))
                .put("version", "old");
        jdbc.update(
            "INSERT INTO"
                + " vector_stores(id,tenant_id,name,engine_type,connection_config,index_config,created_at,updated_at,deleted_at)"
                + " VALUES(?,?,?,'elasticsearch',"
                + jsonParameter()
                + ","
                + jsonParameter()
                + ",?,?,?)",
            id,
            id.equals("foreign") ? 2 : 1,
            id.substring(0, 1).toUpperCase() + id.substring(1),
            stored(cc.toString()),
            stored("{\"index_name\":\"weknora-crud-seed\"}"),
            seedTime(),
            seedTime(),
            id.equals("deleted") ? seedTime() : null);
      }
      if (!scene.path("Corrupt").asText().isEmpty())
        jdbc.update(
            "UPDATE vector_stores SET connection_config=" + jsonParameter() + " WHERE id='seed'",
            stored(
                json.createObjectNode()
                    .put("addr", seedAddress)
                    .put("password", scene.path("Corrupt").asText())
                    .toString()));
      if (scene.path("RenameCorrupt").asBoolean()) damageRename(jdbc, seedAddress);
      if (scene.path("Bound").asBoolean())
        jdbc.update(
            "INSERT INTO"
                + " knowledge_bases(id,tenant_id,name,type,vector_store_id,embedding_model_id,summary_model_id)"
                + " VALUES('kb',1,'Bound','document','seed','','')");
      http = new SafeModelHttp(new ModelEgressPolicy(jdbc, json, env));
      var transport = http;
      var builds = new AtomicInteger();
      var registrations = new AtomicInteger();
      var removals = new AtomicInteger();
      var factory =
          (VectorStoreRegistry.Factory)
              (store, call) -> {
                builds.incrementAndGet();
                if (scene.path("FactoryFail").asBoolean())
                  throw new IllegalStateException("controlled failure");
                return new Elasticsearch8Store(store, transport, json, call);
              };
      registry = org.mockito.Mockito.spy(new VectorStoreRegistry(metadata, factory));
      var observedRegistry = registry;
      // Observe actual public registration without substituting the native lifecycle.
      var service =
          new VectorStorePublicService(
              repo,
              new VectorStoreEnvironment(json, env),
              registry,
              factory,
              http,
              json,
              new ModelURLPolicy(jdbc, json, env));
      var controller =
          new VectorStorePublicController(
              service, new VectorStoreEnvironment(json, env), new VectorStorePublicForms(json));
      var authRepo = new AuthRepository(jdbc, json);
      var auditType = Class.forName("com.tencent.weknora.security.DeniedRoleAuditService");
      var auditConstructor =
          auditType.getDeclaredConstructor(
              JdbcTemplate.class,
              AuthRepository.class,
              org.springframework.transaction.PlatformTransactionManager.class);
      auditConstructor.setAccessible(true);
      Object audit =
          auditConstructor.newInstance(jdbc, authRepo, new DataSourceTransactionManager(source));
      var roleType = Class.forName("com.tencent.weknora.security.NativeRoleGuard");
      var roleCtor = roleType.getDeclaredConstructor(auditType, ObjectMapper.class);
      roleCtor.setAccessible(true);
      var role = (HandlerInterceptor) roleCtor.newInstance(audit, json);
      String roleName = scene.path("Role").asText();
      if (roleName.isEmpty()) roleName = "admin";
      boolean machine = scene.path("Machine").asBoolean();
      var auth =
          new AuthContext(
              "fixture",
              scene.path("Tenant").asLong() == 99 ? 0 : 1,
              roleName,
              false,
              machine ? 1L : null,
              scene.path("Full").asBoolean(),
              scene.path("Capability").asBoolean() ? Set.of("manage_vector_stores") : Set.of(),
              Set.of());
      var authFilter = new AuthFilter(null, authRepo, json);
      var mvc =
          MockMvcBuilders.standaloneSetup(controller)
              .setControllerAdvice(new com.tencent.weknora.api.ApiErrorAdvice())
              .addInterceptors(role)
              .addFilters(
                  (request, response, chain) -> {
                    if (machine)
                      try {
                        org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                            authFilter, "authorizeKeyRoute", request, auth);
                      } catch (ApiException failure) {
                        var out = (jakarta.servlet.http.HttpServletResponse) response;
                        out.setStatus(failure.status());
                        out.setContentType("application/json");
                        json.writeValue(out.getOutputStream(), failure.response());
                        return;
                      }
                    chain.doFilter(request, response);
                  })
              .build();
      String oldAddress = "http://127.0.0.1:58599";
      String body = scene.path("Body").asText().replace(oldAddress, seedAddress);
      enabled.set(true);
      var response =
          mvc.perform(
                  MockMvcRequestBuilders.request(
                          org.springframework.http.HttpMethod.valueOf(
                              scene.path("Method").asText()),
                          "/api/v1/vector-stores" + scene.path("Path").asText())
                      .requestAttr(CurrentAuth.ATTRIBUTE, auth)
                      .contentType("application/json")
                      .content(body))
              .andReturn()
              .getResponse();
      enabled.set(false);
      assertEquals(
          oracle.path("store_reads").asInt(),
          reads.get(),
          "Actual Go store SQL read boundary: preserve create/update read timing");
      assertEquals(
          oracle.path("status").asInt(), response.getStatus(), response.getContentAsString());
      assertEquals(
          canonical(oracle.path("body")),
          canonical(json.readTree(response.getContentAsByteArray())),
          "actual Go complete response");
      var rows = json.createArrayNode();
      for (var raw : jdbc.queryForList("SELECT * FROM vector_stores ORDER BY id")) {
        var row = (ObjectNode) json.valueToTree(raw);
        for (String field : List.of("created_at", "updated_at", "deleted_at"))
          if (raw.get(field) != null) row.put(field, time(raw.get(field)));
        for (String field : List.of("connection_config", "index_config")) {
          Object v = raw.get(field);
          ((ObjectNode) row)
              .set(
                  field,
                  config(
                      v instanceof byte[] b ? new String(b, StandardCharsets.UTF_8) : v.toString(),
                      field.equals("connection_config")));
        }
        rows.add(row);
      }
      var expected = json.createArrayNode();
      for (var r : oracle.path("rows")) {
        var row = r.deepCopy();
        ((ObjectNode) row)
            .set("connection_config", config(row.path("connection_config").asText(), true));
        ((ObjectNode) row).set("index_config", config(row.path("index_config").asText(), false));
        expected.add(row);
      }
      assertEquals(
          sqlState(expected),
          sqlState(rows),
          "actual SQL mutations, encryption, owner and immutable configuration");
      assertEquals(oracle.path("builds").asInt(), builds.get());
      assertEquals(
          canonical(oracle.path("probe_requests")),
          canonical(json.valueToTree(probeRequests)),
          "Go health/factory HTTP calls including Basic auth and no redirect follow");
      org.mockito.Mockito.verify(
              registry, org.mockito.Mockito.times(oracle.path("registered").asInt()))
          .register(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
      org.mockito.Mockito.verify(
              registry, org.mockito.Mockito.times(oracle.path("unregistered").asInt()))
          .unregister(org.mockito.ArgumentMatchers.anyString());

    } finally {
      if (http != null) {
        try (var call = new CallContext();
            var response =
                http.exchange(
                    call,
                    System.getProperty("weknora.es.url") + "/weknora-crud-created",
                    "DELETE",
                    Map.of(),
                    new byte[0],
                    Duration.ofSeconds(10))) {
          response.bytes(100000);
        } finally {
          if (registry != null) registry.close();
          http.close();
        }
      }
      if (probe != null) probe.stop(0);
      if (oracle.path("scene").path("RenameCorrupt").asBoolean()) {
        var cleanup = new JdbcTemplate(originalSource);
        if (jsonParameter().equals("?"))
          cleanup.execute("DROP TRIGGER IF EXISTS public_rename_damage");
        else {
          cleanup.execute("DROP TRIGGER IF EXISTS public_rename_damage ON vector_stores");
          cleanup.execute("DROP FUNCTION IF EXISTS public_rename_damage()");
        }
      }
      release(originalSource);
    }
  }

  void damageRename(JdbcTemplate jdbc, String address) {
    String literal =
        json.createObjectNode()
            .put("addr", address)
            .put("password", "enc:v1:AA")
            .toString()
            .replace("'", "''");
    if (jsonParameter().equals("?"))
      jdbc.execute(
          "CREATE TRIGGER public_rename_damage AFTER UPDATE OF name ON vector_stores WHEN"
              + " NEW.id='seed' BEGIN UPDATE vector_stores SET connection_config=CAST('"
              + literal
              + "' AS BLOB) WHERE id='seed'; END");
    else {
      jdbc.execute(
          "CREATE FUNCTION public_rename_damage() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
              + " UPDATE vector_stores SET connection_config='"
              + literal
              + "'::jsonb WHERE id='seed'; RETURN NEW; END; $$");
      jdbc.execute(
          "CREATE TRIGGER public_rename_damage AFTER UPDATE OF name ON vector_stores FOR EACH ROW"
              + " WHEN (NEW.id='seed') EXECUTE FUNCTION public_rename_damage()");
    }
  }

  javax.sql.DataSource observed(
      javax.sql.DataSource delegate, AtomicInteger reads, AtomicBoolean enabled) {
    return new DelegatingDataSource(delegate) {
      @Override
      public java.sql.Connection getConnection() throws java.sql.SQLException {
        return wrap(super.getConnection());
      }

      @Override
      public java.sql.Connection getConnection(String u, String p) throws java.sql.SQLException {
        return wrap(super.getConnection(u, p));
      }

      java.sql.Connection wrap(java.sql.Connection c) {
        return (java.sql.Connection)
            java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {java.sql.Connection.class},
                (proxy, method, args) -> {
                  if (enabled.get()
                      && method.getName().equals("prepareStatement")
                      && args != null
                      && args[0] instanceof String sql
                      && sql.toLowerCase(Locale.ROOT)
                          .matches("(?s)^\\s*select\\b.*\\bfrom\\s+vector_stores\\b.*"))
                    reads.incrementAndGet();
                  try {
                    return method.invoke(c, args);
                  } catch (java.lang.reflect.InvocationTargetException e) {
                    throw e.getCause();
                  }
                });
      }
    };
  }

  javax.sql.DataSource database() {
    return new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
  }

  void prepare(javax.sql.DataSource source) throws Exception {
    try (var c = source.getConnection()) {
      ScriptUtils.executeSqlScript(
          c, new FileSystemResource("../migrations/sqlite/000000_init.up.sql"));
    }
  }

  void release(javax.sql.DataSource source) {
    ((SingleConnectionDataSource) source).destroy();
  }

  String jsonParameter() {
    return "?";
  }

  Object stored(String raw) {
    return raw.getBytes(StandardCharsets.UTF_8);
  }

  Object seedTime() {
    return clock.instant().toString();
  }

  String time(Object v) {
    if (v instanceof java.sql.Timestamp t) return t.toInstant().toString();
    if (v instanceof Number n) return Instant.ofEpochMilli(n.longValue()).toString();
    return v.toString();
  }

  JsonNode sqlState(JsonNode rows) {
    var result = json.createObjectNode();
    for (var row : rows) {
      var c = canonical(row);
      result.set(c.path("id").asText(), c);
    }
    return result;
  }

  JsonNode config(String raw, boolean credential) throws Exception {
    var value = (ObjectNode) json.readTree(raw);
    if (credential)
      for (String field : List.of("password", "api_key"))
        if (!value.path(field).asText().isEmpty()) {
          assertTrue(value.path(field).asText().startsWith("enc:v1:"), "secret encrypted at rest");
          if (!value.path(field).asText().equals("enc:v1:AA"))
            value.put(field, secrets.decryptStrict(value.path(field).asText()));
        }
    return value;
  }

  JsonNode canonical(JsonNode value) {
    if (value.isNumber()) return DecimalNode.valueOf(value.decimalValue().stripTrailingZeros());
    if (value.isArray()) {
      var out = json.createArrayNode();
      value.forEach(v -> out.add(canonical(v)));
      return out;
    }
    if (value.isObject()) {
      var out = json.createObjectNode();
      value
          .fields()
          .forEachRemaining(
              e -> {
                String key = e.getKey();
                JsonNode v = e.getValue();
                if (key.equals("id")
                    && v.isTextual()
                    && !Set.of("seed", "foreign", "deleted").contains(v.asText())
                    && !v.asText().startsWith("__env_")) {
                  assertEquals(4, UUID.fromString(v.asText()).version());
                  out.put(key, "__created_uuid__");
                } else out.set(key, canonical(v));
              });
      return out;
    }
    if (value.isTextual()) {
      String v = value.asText();
      for (var e : aliases.entrySet()) v = v.replace(e.getKey(), e.getValue());
      return TextNode.valueOf(v);
    }
    return value;
  }
}
