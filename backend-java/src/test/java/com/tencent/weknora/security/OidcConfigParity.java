package com.tencent.weknora.security;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;

final class OidcConfigParity {
  static void verify(JsonNode cases) throws Exception {
    ObjectMapper json = new ObjectMapper();
    int failed = 0;
    for (JsonNode item : cases) {
      Object actual;
      try {
        Map<String, Object> document = json.convertValue(item.get("input"), Map.class);
        Map<String, String> env = json.convertValue(item.get("env"), Map.class);
        var file = Files.createTempFile("weknora-oidc-config-", ".yaml");
        OidcProviderClient.Configuration cfg;
        try {
          Files.writeString(file, json.writeValueAsString(document));
          var environment =
              new org.springframework.mock.env.MockEnvironment()
                  .withProperty("JAVA_GO_CONFIG", file.toString());
          env.forEach(environment::withProperty);
          cfg = new OidcSettings(environment).configuration();
        } finally {
          Files.deleteIfExists(file);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enable", cfg.enabled());
        body.put("provider_display_name", cfg.displayName());
        body.put("issuer_url", cfg.issuer());
        body.put("discovery_url", cfg.discovery());
        body.put("client_id", cfg.clientId());
        body.put("client_secret_fixture", cfg.clientSecret());
        body.put("authorization_endpoint", cfg.authorization());
        body.put("token_endpoint", cfg.token());
        body.put("user_info_endpoint", cfg.userinfo());
        body.put("jwks_uri", cfg.jwks());
        body.put("scopes", cfg.scopes());
        body.put(
            "user_info_mapping",
            Map.of("username", cfg.usernameClaim(), "email", cfg.emailClaim()));
        actual = json.valueToTree(body);
      } catch (IllegalArgumentException ex) {
        actual = ex.getMessage();
      }
      Object expected = item.has("error") ? item.get("error").asText() : item.get("result");
      if (!Objects.equals(actual, expected)) {
        failed++;
        System.out.println(
            "FAIL " + item.get("name") + " expected=" + expected + " actual=" + actual);
      }
    }
    System.out.println("TOTAL " + cases.size() + " FAILURES " + failed);
    org.junit.jupiter.api.Assertions.assertEquals(0, failed);
  }
}
