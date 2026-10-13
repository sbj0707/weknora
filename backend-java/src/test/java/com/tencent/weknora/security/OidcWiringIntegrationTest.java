package com.tencent.weknora.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.weknora.model.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

class OidcWiringIntegrationTest {
  @org.junit.jupiter.api.Test
  void postgresJsonbPreferencesDriverAdapter() throws Exception {
    var postgres = new org.postgresql.util.PGobject();
    postgres.setType("jsonb");
    postgres.setValue("{\"oidc_only_login\":true,\"last_active_tenant_id\":2}");
    var scanner = new OidcUserPreferences();
    org.junit.jupiter.api.Assertions.assertEquals(
        Map.of("oidc_only_login", true, "last_active_tenant_id", java.math.BigInteger.valueOf(2)),
        scanner.scan(postgres));
    org.junit.jupiter.api.Assertions.assertEquals(
        "UserPreferences.Scan: unsupported type",
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> scanner.scan(2))
            .getMessage());
  }

  @org.junit.jupiter.api.Test
  void beanLifecycleAndPublicMethodBoundary() throws Exception {
    var file = Files.createTempFile("weknora-oidc-wiring-", ".yaml");
    try {
      Files.writeString(file, "oidc_auth:\n  enable: false\n");
      var env =
          new MockEnvironment()
              .withProperty("JAVA_GO_CONFIG", file.toString())
              .withProperty("JWT_SECRET", "oidc-wiring-secret");
      var db = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite::memory:"));
      var json = new ObjectMapper();
      var repository = new AuthRepository(db, json, new GoSecretCodec(env));
      var jwt = new JwtCodec(json, "oidc-wiring-secret");
      var security = new SecuritySettings(repository, env);
      try (var context = new AnnotationConfigApplicationContext()) {
        context.setEnvironment(env);
        context.getBeanFactory().registerSingleton("json", json);
        context.getBeanFactory().registerSingleton("jdbc", db);
        context.getBeanFactory().registerSingleton("repository", repository);
        context.getBeanFactory().registerSingleton("jwt", jwt);
        context.getBeanFactory().registerSingleton("security", security);
        context.getBeanFactory().registerSingleton("egress", new ModelEgressPolicy(db, json, env));
        context.getBeanFactory().registerSingleton("policy", new ModelURLPolicy(db, json, env));
        context.register(OidcConfiguration.class, OidcController.class);
        context.refresh();
        if (!context
            .getBean(OidcController.class)
            .config()
            .equals(Map.of("success", true, "enabled", false, "provider_display_name", "OIDC")))
          throw new AssertionError("Wrong OIDC bean/config");
        var filter = new AuthFilter(jwt, repository, json, security);
        for (String route : List.of("config", "url", "start", "callback")) {
          if (!filter.shouldNotFilter(
              new MockHttpServletRequest("GET", "/api/v1/auth/oidc/" + route)))
            throw new AssertionError("GET not public");
          if (filter.shouldNotFilter(
              new MockHttpServletRequest("POST", "/api/v1/auth/oidc/" + route)))
            throw new AssertionError("Unintended POST public");
        }
        if (filter.shouldNotFilter(new MockHttpServletRequest("GET", "/api/v1/auth/oidc/other")))
          throw new AssertionError("Unintended wildcard public");
        System.out.println("Spring OIDC bean lifecycle and exact GET/POST auth boundaries PASS");
      }
    } finally {
      Files.deleteIfExists(file);
    }
  }
}
