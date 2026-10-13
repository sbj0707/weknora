package com.tencent.weknora.security;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import com.tencent.weknora.model.*;
import com.tencent.weknora.model.client.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

final class OidcHttpParity {
  static final ObjectMapper json = new ObjectMapper();
  static final String secret = "oidc-migration-state-secret";
  static final List<String> wireCookies = new ArrayList<>();
  static final String placeholder = "http://127.0.0.1:<PROVIDER_PORT>";

  static void verify(JsonNode cases) throws Exception {
    int failed = 0, providerCalls = 0, handlers = 0;
    for (var c : cases) {
      String name = c.path("name").asText();
      List<Map<String, Object>> seen = Collections.synchronizedList(new ArrayList<>());
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      var workers = Executors.newVirtualThreadPerTaskExecutor();
      server.setExecutor(workers);
      String base = "http://127.0.0.1:" + server.getAddress().getPort();
      server.createContext(
          "/",
          exchange -> {
            try {
              String path = exchange.getRequestURI().getPath();
              var fixture = c.path("provider_responses").path(path);
              var row = new LinkedHashMap<String, Object>();
              row.put("method", exchange.getRequestMethod());
              row.put("path", exchange.getRequestURI().toString());
              row.put(
                  "body",
                  new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
              row.put(
                  "authorization",
                  Objects.toString(exchange.getRequestHeaders().getFirst("Authorization"), ""));
              row.put(
                  "accept", Objects.toString(exchange.getRequestHeaders().getFirst("Accept"), ""));
              row.put(
                  "content_type",
                  Objects.toString(exchange.getRequestHeaders().getFirst("Content-Type"), ""));
              seen.add(row);
              String raw = fixture.path("body").asText("{}").replace(placeholder, base);
              byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
              exchange.getResponseHeaders().add("Content-Type", "application/json");
              exchange.sendResponseHeaders(fixture.path("status").asInt(404), bytes.length);
              exchange.getResponseBody().write(bytes);
            } finally {
              exchange.close();
            }
          });
      server.start();
      var file = Files.createTempFile("weknora-oidc-parity-", ".db");
      var datasource = new DriverManagerDataSource("jdbc:sqlite:" + file);
      var jdbc = new JdbcTemplate(datasource);
      try {
        for (String migration :
            List.of(
                "000000_init",
                "000007_system_admin_and_settings",
                "000011_principal_model",
                "000004_memory")) {
          String sql = Files.readString(Path.of("../migrations/sqlite/" + migration + ".up.sql"));
          jdbc.execute(
              (org.springframework.jdbc.core.ConnectionCallback<Void>)
                  connection -> {
                    connection.createStatement().executeUpdate(sql);
                    return null;
                  });
        }
        setup(jdbc, name);
        Clock clock = Clock.fixed(Instant.ofEpochSecond(c.path("clock").asLong()), ZoneOffset.UTC);
        var env =
            new MockEnvironment()
                .withProperty("JWT_SECRET", secret)
                .withProperty("SSRF_WHITELIST", "127.0.0.1")
                .withProperty("SSRF_DNS_WHITELIST_ONLY", "true")
                .withProperty("STORAGE_TYPE", "local");
        var repository = new AuthRepository(jdbc, json, new GoSecretCodec(env));
        var jwt = new JwtCodec(json, secret, clock);
        var settings = new SecuritySettings(repository, env);
        var states = new OidcState(json, secret, clock);
        var egress = new ModelEgressPolicy(jdbc, json, env);
        var policy = new ModelURLPolicy(jdbc, json, env);
        var rawConfig = c.path("config").get("oidc_auth");
        var cfg = configuration(rawConfig, c.path("client_secret_fixture").asText(), base);
        try (var provider = new OidcProviderClient(egress, policy::validate, clock, states)) {
          var identity = new OidcIdentityGateway(jdbc, repository, jwt, settings, clock);
          String mode =
              c.path("config").path("auth").path("default_tenant_mode").asText("create_personal");
          var controller = new OidcController(() -> cfg, provider, states, identity, () -> mode);
          var mvc =
              MockMvcBuilders.standaloneSetup(controller)
                  .addFilters(
                      new AuthFilter(jwt, repository, json, settings),
                      (jakarta.servlet.Filter)
                          (request, response, chain) -> {
                            var wrapped =
                                new jakarta.servlet.http.HttpServletResponseWrapper(
                                    (jakarta.servlet.http.HttpServletResponse) response) {
                                  @Override
                                  public void addHeader(String name, String value) {
                                    if (name.equalsIgnoreCase("Set-Cookie")) wireCookies.add(value);
                                    super.addHeader(name, value);
                                  }

                                  @Override
                                  public void setHeader(String name, String value) {
                                    if (name.equalsIgnoreCase("Set-Cookie")) {
                                      wireCookies.clear();
                                      wireCookies.add(value);
                                    }
                                    super.setHeader(name, value);
                                  }
                                };
                            chain.doFilter(request, wrapped);
                          })
                  .build();
          JsonNode replay = json.createObjectNode();
          if (!c.path("replay").isEmpty()) {
            var first = perform(mvc, c, true);
            handlers++;
            var rr = (ObjectNode) replay;
            rr.put("first_status", first.getStatus());
            rr.put("first_success", first.getHeader("Location").contains("#oidc_result="));
            rr.set("first_set_cookie", json.valueToTree(new ArrayList<>(wireCookies)));
            rr.put(
                "tokens_before_second",
                jdbc.queryForObject("SELECT count(*) FROM auth_tokens", Long.class));
          }
          var response = perform(mvc, c, false);
          handlers++;
          var result = result(response, c, states, jwt);
          var snapshot = snapshot(jdbc);
          var actual = json.createObjectNode();
          actual.set("result", json.valueToTree(result));
          actual.set("database", snapshot);
          actual.set("provider_calls", json.valueToTree(seen));
          actual.set("replay", replay);
          var ids = new HashSet<String>();
          try {
            for (var row : jdbc.queryForList("SELECT id FROM users")) {
              String id = row.get("id").toString();
              if (!Set.of("existing", "collision").contains(id)) ids.add(id);
            }
          } catch (Exception ignored) {
          }
          normalize(actual, base, ids);
          actual = (ObjectNode) json.readTree(json.writeValueAsBytes(actual));
          var expected = json.createObjectNode();
          for (String field : List.of("result", "database", "provider_calls", "replay"))
            expected.set(field, c.get(field));
          if (!actual.equals(expected)) {
            failed++;
            System.out.println("FAIL " + name + "\nEXPECTED " + expected + "\nACTUAL " + actual);
          }
          providerCalls += seen.size();
        }
      } catch (Exception error) {
        failed++;
        System.out.println("ERROR " + name + " " + error);
        error.printStackTrace(System.out);
      } finally {
        server.stop(0);
        workers.shutdownNow();
        Files.deleteIfExists(file);
      }
    }
    System.out.println(
        "TOTAL "
            + cases.size()
            + " HANDLER_CALLS "
            + handlers
            + " PROVIDER_CALLS "
            + providerCalls
            + " FAILURES "
            + failed);
    org.junit.jupiter.api.Assertions.assertEquals(0, failed);
  }

  static OidcProviderClient.Configuration configuration(JsonNode node, String secret, String base) {
    if (node == null || node.isNull()) return null;
    List<String> scopes = new ArrayList<>();
    node.path("scopes").forEach(n -> scopes.add(n.asText()));
    var mapping = node.path("user_info_mapping");
    return new OidcProviderClient.Configuration(
        node.path("enable").asBoolean(),
        node.path("provider_display_name").asText(),
        value(node, "issuer_url", base),
        value(node, "discovery_url", base),
        node.path("client_id").asText(),
        secret,
        value(node, "authorization_endpoint", base),
        value(node, "token_endpoint", base),
        value(node, "user_info_endpoint", base),
        value(node, "jwks_uri", base),
        scopes,
        mapping.isNull() || mapping.isMissingNode() ? null : mapping.path("username").asText(),
        mapping.isNull() || mapping.isMissingNode() ? null : mapping.path("email").asText());
  }

  static String value(JsonNode node, String key, String base) {
    return node.path(key).asText().replace(placeholder, base);
  }

  static MockHttpServletResponse perform(MockMvc mvc, JsonNode c, boolean first) throws Exception {
    wireCookies.clear();
    String name = c.path("name").asText();
    var req =
        MockMvcRequestBuilders.get("/api/v1/auth/oidc/" + c.path("endpoint").asText())
            .header("Host", "backend.example.test");
    c.path("query")
        .fields()
        .forEachRemaining(
            e -> {
              List<String> values = new ArrayList<>();
              e.getValue().forEach(n -> values.add(n.asText()));
              req.param(e.getKey(), values.toArray(String[]::new));
            });
    String cookie = c.path("cookie").asText();
    if (first || !name.equals("callback-no-cookie-replay") && !cookie.equals("<MISSING>"))
      req.cookie(new jakarta.servlet.http.Cookie("weknora_oidc_nonce", cookie));
    if (name.contains("secure-xfp")) req.header("X-Forwarded-Proto", "HTTPS");
    if (name.equals("url-spaced-xfp")) req.header("X-Forwarded-Proto", " https ");
    if (name.endsWith("-tls")) req.secure(true);
    return mvc.perform(req).andReturn().getResponse();
  }

  static Map<String, Object> result(
      MockHttpServletResponse response, JsonNode c, OidcState states, JwtCodec jwt)
      throws Exception {
    String endpoint = c.path("endpoint").asText();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("status", response.getStatus());
    String location = Objects.toString(response.getHeader("Location"), "");
    result.put("location", location);
    var cookies = new ArrayList<>(wireCookies);
    result.put("set_cookie", cookies.isEmpty() ? null : cookies);
    if (endpoint.equals("config")
        || endpoint.equals("url")
        || endpoint.equals("start") && response.getStatus() != 302) {
      Map<String, Object> body = json.readValue(response.getContentAsByteArray(), Map.class);
      if (body.get("state") != null) {
        String state = body.get("state").toString();
        var payload = states.verify(state);
        body.put("state", "<STATE>");
        body.put(
            "state_payload",
            Map.of(
                "nonce",
                "<NONCE>",
                "redirect_uri",
                payload.redirectUri(),
                "iat_present",
                payload.issuedAt() > 0));
        body.put(
            "authorization_url",
            body.get("authorization_url")
                .toString()
                .replaceAll("state=[^&]+", "state=%3CSTATE%3E"));
        result.put("nonce_length", payload.nonce().length());
        for (int i = 0; i < cookies.size(); i++)
          cookies.set(i, cookies.get(i).replace(payload.nonce(), "<NONCE>"));
      }
      result.put("body", body);
    } else if (endpoint.equals("start")) {
      String rawState =
          URLDecoder.decode(
              location.split("state=", 2)[1].split("&", 2)[0], StandardCharsets.UTF_8);
      var payload = states.verify(rawState);
      result.put(
          "state_payload",
          Map.of(
              "nonce",
              "<NONCE>",
              "redirect_uri",
              payload.redirectUri(),
              "iat_present",
              payload.issuedAt() > 0));
      result.put("nonce_length", payload.nonce().length());
      result.put("location", location.replaceAll("state=[^&]+", "state=%3CSTATE%3E"));
      for (int i = 0; i < cookies.size(); i++)
        cookies.set(i, cookies.get(i).replace(payload.nonce(), "<NONCE>"));
    } else if (location.contains("#oidc_result=")) {
      String encoded = location.split("#oidc_result=", 2)[1];
      Map<String, Object> payload =
          json.readValue(Base64.getUrlDecoder().decode(encoded), Map.class);
      for (String type : List.of("token", "refresh_token")) {
        var claims = new LinkedHashMap<>(jwt.verify(payload.get(type).toString()));
        claims.remove("iat");
        claims.remove("exp");
        claims.remove("jti");
        payload.put(type, "<LOCAL_TOKEN>");
        payload.put(type + "_claims", claims);
      }
      result.put("payload", payload);
      result.put("location", "/#oidc_result=<PAYLOAD>");
    }
    return result;
  }

  static ObjectNode snapshot(JdbcTemplate jdbc) {
    var out = json.createObjectNode();
    var tables =
        Map.of(
            "users",
            "id,username,email,tenant_id,is_active,preferences,deleted_at",
            "tenants",
            "id,name,status,default_storage_backend_id,deleted_at",
            "tenant_members",
            "user_id,tenant_id,role,status",
            "storage_backends",
            "tenant_id,provider,legacy_alias,deleted_at",
            "auth_tokens",
            "token_type,is_revoked");
    tables.forEach(
        (table, selected) -> {
          try {
            var rows = jdbc.queryForList("SELECT " + selected + " FROM " + table);
            for (var row : rows)
              for (String field : List.of("is_active", "legacy_alias", "is_revoked"))
                if (row.containsKey(field)) row.put(field, AuthRepository.bool(row, field));
            out.set(table, json.valueToTree(rows));
          } catch (Exception error) {
            out.putObject(table).put("lookup_error", true);
          }
        });
    return out;
  }

  static void normalize(JsonNode node, String base, Set<String> ids) {
    if (node.isObject()) {
      var obj = (ObjectNode) node;
      var keys = new ArrayList<String>();
      obj.fieldNames().forEachRemaining(keys::add);
      for (String key : keys) {
        var value = obj.get(key);
        if (value.isTextual()) {
          String text = value.asText();
          if (ids.contains(text)) obj.put(key, "<USER>");
          else if (List.of("created_at", "updated_at", "expires_at", "deleted_at").contains(key))
            obj.put(key, "<TIME>");
          else if (key.equals("default_storage_backend_id") || key.equals("jti"))
            obj.put(key, "<RANDOM_ID>");
          else obj.put(key, text.replace(base, placeholder));
        } else normalize(value, base, ids);
      }
    } else if (node.isArray()) node.forEach(n -> normalize(n, base, ids));
  }

  static void setup(JdbcTemplate db, String name) {
    if (Set.of(
                "callback-existing",
                "callback-disabled-user",
                "callback-existing-tenantless-member",
                "callback-preferred-tenant",
                "callback-inactive-tenant",
                "callback-blank-home-name",
                "callback-stale-home",
                "callback-stale-home-write-failure",
                "callback-first-home-write-failure",
                "callback-stale-preference",
                "callback-stale-preference-write-failure",
                "callback-missing-home-tenant",
                "callback-member-list-failure")
            .contains(name)
        || name.startsWith("callback-prefs-")) {
      db.update(
          "INSERT INTO tenants(id,name,business)"
              + " VALUES(1,'Home','fixture'),(2,'Preferred','fixture')");
      db.update(
          "INSERT INTO users(id,username,email,password_hash,tenant_id,is_active)"
              + " VALUES('existing','Existing','person@example.test','unused',?,true)",
          name.equals("callback-existing-tenantless-member")
                  || name.equals("callback-first-home-write-failure")
              ? 0
              : 1);
      db.update(
          "INSERT INTO tenant_members(user_id,tenant_id,role,status)"
              + " VALUES('existing',1,'viewer','active'),('existing',2,'admin','active')");
      if (name.equals("callback-disabled-user")) db.update("UPDATE users SET is_active=false");
      if (name.equals("callback-preferred-tenant"))
        db.update("UPDATE users SET preferences='{\"last_active_tenant_id\":2}'");
    }
    if (name.equals("callback-username-collision"))
      db.update(
          "INSERT INTO users(id,username,email,password_hash)"
              + " VALUES('collision','provider-user','different@example.test','unused')");

    if (name.equals("callback-inactive-tenant"))
      db.update("UPDATE tenants SET status='inactive' WHERE id=1");
    if (name.equals("callback-blank-home-name"))
      db.update("UPDATE tenants SET name='   ' WHERE id=1");
    if (name.contains("stale-home")) {
      db.update("UPDATE tenant_members SET deleted_at=CURRENT_TIMESTAMP WHERE tenant_id=1");
      db.update("UPDATE users SET preferences='{\"last_active_tenant_id\":1}'");
    }
    if (name.contains("stale-preference"))
      db.update("UPDATE users SET preferences='{\"last_active_tenant_id\":99}'");
    if (name.equals("callback-missing-home-tenant"))
      db.update("UPDATE tenants SET deleted_at=CURRENT_TIMESTAMP WHERE id=1");
    if (name.equals("callback-member-list-failure"))
      db.update("ALTER TABLE tenant_members RENAME TO tenant_members_missing");
    var prefs =
        Map.ofEntries(
            Map.entry("callback-prefs-malformed", "{"),
            Map.entry("callback-prefs-trailing", "{} {}"),
            Map.entry("callback-prefs-bool-wrong", "{\"oidc_only_login\":1}"),
            Map.entry(
                "callback-prefs-duplicate-wrong",
                "{\"oidc_only_login\":1,\"oidc_only_login\":true}"),
            Map.entry(
                "callback-prefs-casefold",
                "{\"OIDC_ONLY_LOGIN\":false,\"LAST_ACTIVE_TENANT_ID\":2}"),
            Map.entry(
                "callback-prefs-map-null", "{\"gallery\":{\"status\":{\"a\":null,\"b\":\"on\"}}}"),
            Map.entry(
                "callback-prefs-gallery-duplicate",
                "{\"gallery\":{\"mode\":\"custom\",\"status\":{\"a\":\"on\"}},\"gallery\":{\"status\":{\"b\":\"off\"}}}"),
            Map.entry("callback-prefs-empty", ""),
            Map.entry("callback-prefs-null", "null"),
            Map.entry("callback-prefs-gallery-wrong", "{\"gallery\":true}"),
            Map.entry("callback-prefs-uint-wrong", "{\"last_active_tenant_id\":-1}"),
            Map.entry("callback-prefs-uint-fraction", "{\"last_active_tenant_id\":1.0}"),
            Map.entry("callback-prefs-root-array", "[]"),
            Map.entry(
                "callback-prefs-empty-pointer",
                "{\"browser_search_instructions\":\"\",\"oidc_only_login\":false,\"last_active_tenant_id\":0}"));
    if (prefs.containsKey(name)) db.update("UPDATE users SET preferences=?", prefs.get(name));
    List<String> triggers = new ArrayList<>();
    switch (name) {
      case "callback-preference-failure" ->
          triggers.add(
              "CREATE TRIGGER fail_preferences BEFORE UPDATE ON users WHEN NEW.preferences LIKE"
                  + " '%oidc_only_login%' BEGIN SELECT RAISE(ABORT,'oidc preferences failure');"
                  + " END");
      case "callback-create-user-failure" ->
          triggers.add(
              "CREATE TRIGGER fail_user BEFORE INSERT ON users BEGIN SELECT RAISE(ABORT,'oidc user"
                  + " failure'); END");
      case "callback-owner-failure", "callback-owner-cleanup-failure" -> {
        triggers.add(
            "CREATE TRIGGER fail_owner BEFORE INSERT ON tenant_members BEGIN SELECT"
                + " RAISE(ABORT,'oidc owner failure'); END");
        if (name.endsWith("cleanup-failure")) {
          triggers.add(
              "CREATE TRIGGER fail_user_cleanup BEFORE UPDATE ON users WHEN NEW.deleted_at IS NOT"
                  + " NULL BEGIN SELECT RAISE(ABORT,'oidc user cleanup failure'); END");
          triggers.add(
              "CREATE TRIGGER fail_tenant_cleanup BEFORE UPDATE ON tenants WHEN NEW.deleted_at IS"
                  + " NOT NULL BEGIN SELECT RAISE(ABORT,'oidc tenant cleanup failure'); END");
        }
      }
      case "callback-tenant-failure" ->
          triggers.add(
              "CREATE TRIGGER fail_tenant BEFORE INSERT ON tenants BEGIN SELECT RAISE(ABORT,'oidc"
                  + " tenant failure'); END");
      case "callback-stale-home-write-failure",
          "callback-first-home-write-failure",
          "callback-stale-preference-write-failure" ->
          triggers.add(
              "CREATE TRIGGER fail_user_update BEFORE UPDATE ON users BEGIN SELECT"
                  + " RAISE(ABORT,'oidc user update failure'); END");
      case "callback-storage-link-failure",
          "callback-storage-link-cleanup-failure",
          "callback-storage-link-tenant-cleanup-failure" -> {
        triggers.add(
            "CREATE TRIGGER fail_storage_link BEFORE UPDATE ON tenants WHEN"
                + " NEW.default_storage_backend_id IS NOT NULL BEGIN SELECT RAISE(ABORT,'oidc"
                + " storage link failure'); END");
        if (name.equals("callback-storage-link-cleanup-failure"))
          triggers.add(
              "CREATE TRIGGER fail_storage_cleanup BEFORE UPDATE ON storage_backends WHEN"
                  + " NEW.deleted_at IS NOT NULL BEGIN SELECT RAISE(ABORT,'oidc storage cleanup"
                  + " failure'); END");
        if (name.equals("callback-storage-link-tenant-cleanup-failure"))
          triggers.add(
              "CREATE TRIGGER fail_tenant_cleanup BEFORE UPDATE ON tenants WHEN NEW.deleted_at IS"
                  + " NOT NULL BEGIN SELECT RAISE(ABORT,'oidc tenant cleanup failure'); END");
      }
      case "callback-storage-failure" ->
          triggers.add(
              "CREATE TRIGGER fail_storage BEFORE INSERT ON storage_backends BEGIN SELECT"
                  + " RAISE(ABORT,'oidc storage failure'); END");
      case "callback-access-token-failure", "callback-refresh-token-failure" -> {
        String kind = name.contains("refresh") ? "refresh_token" : "access_token";
        triggers.add(
            "CREATE TRIGGER fail_token BEFORE INSERT ON auth_tokens WHEN NEW.token_type='"
                + kind
                + "' BEGIN SELECT RAISE(ABORT,'oidc token failure'); END");
      }
      case "callback-email-lookup-failure" ->
          db.update("ALTER TABLE users RENAME TO users_missing");
    }
    triggers.forEach(db::execute);
  }
}
