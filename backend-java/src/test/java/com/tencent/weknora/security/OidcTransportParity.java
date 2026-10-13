package com.tencent.weknora.security;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
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

/**
 * Real HTTP semantic transport comparison; engine-specific error suffixes are retained separately.
 */
final class OidcTransportParity {
  static final ObjectMapper json = new ObjectMapper();

  static void verify(JsonNode rows) throws Exception {
    int failures = 0, calls = 0;
    for (var row : rows) {
      String name = row.path("name").asText();
      List<Map<String, Object>> seen = Collections.synchronizedList(new ArrayList<>());
      var source = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      var target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      String base = "http://127.0.0.1:" + source.getAddress().getPort(),
          other = "http://127.0.0.1:" + target.getAddress().getPort();
      var executor = Executors.newVirtualThreadPerTaskExecutor();
      source.setExecutor(executor);
      target.setExecutor(executor);
      var latch = new CountDownLatch(1);
      var call =
          name.equals("token-deadline")
              ? CallContext.timeout(Duration.ofMillis(100))
              : new CallContext();
      if (name.equals("token-cancel-before")) call.cancel();
      for (var origin : List.of("source", "target")) {
        var server = origin.equals("source") ? source : target;
        server.createContext(
            "/",
            exchange -> {
              try {
                String path = exchange.getRequestURI().getPath();
                var recorded = new LinkedHashMap<String, Object>();
                recorded.put("origin", origin);
                recorded.put("method", exchange.getRequestMethod());
                recorded.put("path", path);
                recorded.put(
                    "body",
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                recorded.put(
                    "authorization",
                    Objects.toString(exchange.getRequestHeaders().getFirst("Authorization"), ""));
                recorded.put(
                    "accept",
                    Objects.toString(exchange.getRequestHeaders().getFirst("Accept"), ""));
                recorded.put(
                    "content_type",
                    Objects.toString(exchange.getRequestHeaders().getFirst("Content-Type"), ""));
                seen.add(recorded);
                if ((name.startsWith("token-") && path.equals("/token")
                        || name.equals("userinfo-cancel-during") && path.equals("/userinfo"))
                    && (name.contains("cancel-during") || name.equals("token-deadline"))) {
                  latch.countDown();
                  try {
                    new CountDownLatch(1).await(400, TimeUnit.MILLISECONDS);
                  } catch (InterruptedException ignored) {
                  }
                  return;
                }
                boolean redirect =
                    name.startsWith("token-")
                            && path.equals("/token")
                            && (name.contains("same-")
                                || name.contains("cross-")
                                || name.equals("token-redirect-loop"))
                        || path.equals("/userinfo")
                            && name.startsWith("userinfo-")
                            && !name.contains("cancel")
                        || path.equals("/discovery") && name.startsWith("discovery-");
                if (redirect) {
                  int status =
                      name.endsWith("303")
                          ? 303
                          : name.endsWith("307") ? 307 : name.endsWith("308") ? 308 : 302;
                  String location = (name.contains("cross-") ? other : base) + path + "-final";
                  if (name.equals("discovery-private-302"))
                    location = "http://169.254.169.254/private";
                  if (name.equals("token-redirect-loop")) location = base + "/token";
                  exchange.getResponseHeaders().add("Location", location);
                  exchange.sendResponseHeaders(status, -1);
                  return;
                }
                String body =
                    path.startsWith("/token")
                        ? "{\"access_token\":\"transport-access\"}"
                        : path.startsWith("/userinfo")
                            ? "{\"name\":\"No Email\"}"
                            : json.writeValueAsString(
                                Map.of(
                                    "authorization_endpoint",
                                    base + "/authorize",
                                    "token_endpoint",
                                    base + "/token",
                                    "userinfo_endpoint",
                                    base + "/userinfo",
                                    "issuer",
                                    "https://issuer.example.test"));
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
              } finally {
                exchange.close();
              }
            });
      }
      source.start();
      target.start();
      Future<?> cancellation = null;
      if (name.contains("cancel-during"))
        cancellation =
            executor.submit(
                () -> {
                  try {
                    if (!latch.await(5, TimeUnit.SECONDS))
                      throw new AssertionError("Provider request never began");
                    call.cancel();
                  } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                  }
                });
      var environment =
          new MockEnvironment()
              .withProperty("JWT_SECRET", "oidc-migration-state-secret")
              .withProperty("SSRF_WHITELIST", "127.0.0.1")
              .withProperty("SSRF_DNS_WHITELIST_ONLY", "true");
      var db = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite::memory:"));
      var egress = new ModelEgressPolicy(db, json, environment);
      var policy = new ModelURLPolicy(db, json, environment);
      String error = "";
      boolean success = false;
      try (var provider =
          new OidcProviderClient(
              egress,
              policy::validate,
              Clock.systemUTC(),
              new OidcState(json, "oidc-migration-state-secret", Clock.systemUTC()))) {
        boolean discovery = name.startsWith("discovery-");
        var cfg =
            new OidcProviderClient.Configuration(
                true,
                "OIDC",
                "https://issuer.example.test",
                discovery ? base + "/discovery" : "",
                "client",
                "SECRET_CLIENT",
                discovery ? "" : base + "/authorize",
                discovery ? "" : base + "/token",
                base + "/userinfo",
                "",
                List.of("openid", "profile", "email"),
                "name",
                "email");
        try {
          if (discovery) {
            provider.authorization(call, cfg, "https://frontend.test/callback");
            success = true;
          } else {
            var info = provider.userInfo(call, cfg, "code", "https://frontend.test/callback");
            if (info.email().isEmpty())
              throw new IllegalArgumentException("OIDC provider did not return email");
            success = true;
          }
        } catch (IllegalArgumentException ex) {
          error = ex.getMessage();
        }
        if (cancellation != null) cancellation.get(6, TimeUnit.SECONDS);
        var actual = json.valueToTree(seen);
        String expectedError = row.path("error").asText();
        if (success != row.path("success").asBoolean()
            || !actual.equals(row.path("calls"))
            || !category(error).equals(category(expectedError))) {
          failures++;
          System.out.println(
              "FAIL "
                  + name
                  + " EXPECTED "
                  + row
                  + " ACTUAL success="
                  + success
                  + " error="
                  + error
                  + " calls="
                  + actual);
        } else System.out.println("PASS " + name + " semantic=" + category(error));
        calls += seen.size();
      } finally {
        call.close();
        source.stop(0);
        target.stop(0);
        executor.shutdownNow();
      }
    }
    System.out.println(
        "TOTAL " + rows.size() + " PROVIDER_CALLS " + calls + " FAILURES " + failures);
    org.junit.jupiter.api.Assertions.assertEquals(0, failures);
  }

  static String category(String error) {
    if (error.isEmpty()) return "success";
    String phase =
        error.startsWith("failed to load")
            ? "discovery"
            : error.startsWith("failed to exchange")
                ? "token"
                : error.startsWith("failed to fetch") ? "userinfo" : "identity";
    if (error.contains("cross-origin")) return phase + ":crossorigin";
    if (error.contains("169.254.169.254") || error.contains("SSRF")) return phase + ":ssrf";
    if (error.contains("after 10 redirects")) return phase + ":redirectlimit";
    if (error.toLowerCase(Locale.ROOT).contains("deadline")) return phase + ":deadline";
    if (error.toLowerCase(Locale.ROOT).contains("cancel")) return phase + ":cancel";
    return error;
  }
}
