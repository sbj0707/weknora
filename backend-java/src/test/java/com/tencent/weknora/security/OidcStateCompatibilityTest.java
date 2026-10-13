package com.tencent.weknora.security;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;

class OidcStateCompatibilityTest {
  @TestFactory
  Stream<DynamicTest> actualGoStateBytesAndValidation() throws Exception {
    var json = new ObjectMapper();
    var fixture =
        json.readTree(
            Files.readString(
                Path.of("src/test/java/com/tencent/weknora/security/oidc-state-golden.json")));
    List<DynamicTest> out = new ArrayList<>();
    int index = 0;
    for (var row : fixture.path("sign")) {
      out.add(
          DynamicTest.dynamicTest(
              "sign-" + (index++),
              () -> {
                var decoder =
                    new OidcState(
                        json,
                        "oidc-migration-state-secret",
                        Clock.fixed(
                            Instant.ofEpochSecond(row.path("clock").asLong()), ZoneOffset.UTC));
                var p = row.path("payload");
                if (row.has("error")) {
                  assertEquals(
                      row.path("error").asText(),
                      assertThrows(
                              IllegalArgumentException.class,
                              () ->
                                  decoder.sign(
                                      p.isNull()
                                          ? null
                                          : new OidcState.Payload(
                                              p.path("nonce").asText(),
                                              p.path("redirect_uri").asText(),
                                              p.path("iat").asLong())))
                          .getMessage());
                } else
                  assertEquals(
                      row.path("state").asText(),
                      decoder.sign(
                          new OidcState.Payload(
                              p.path("nonce").asText(),
                              p.path("redirect_uri").asText(),
                              p.path("iat").asLong())));
              }));
    }
    var decoder =
        new OidcState(
            json,
            "oidc-migration-state-secret",
            Clock.fixed(
                Instant.ofEpochSecond(
                    fixture.path("now_second").asLong(), fixture.path("now_nano").asLong()),
                ZoneOffset.UTC));
    for (var row : fixture.path("verify")) {
      out.add(
          DynamicTest.dynamicTest(
              "verify-" + (index++),
              () -> {
                if (row.has("error")) {
                  String error =
                      assertThrows(
                              IllegalArgumentException.class,
                              () -> decoder.verify(row.path("state").asText()))
                          .getMessage();
                  if (row.path("error").asText().startsWith("unmarshal oidc state:"))
                    assertTrue(error.startsWith("unmarshal oidc state:"));
                  else assertEquals(row.path("error").asText(), error);
                } else {
                  var p = decoder.verify(row.path("state").asText());
                  var actual =
                      json.createObjectNode()
                          .put("nonce", p.nonce())
                          .put("redirect_uri", p.redirectUri())
                          .put("iat", p.issuedAt());
                  assertEquals(row.path("payload"), json.readTree(json.writeValueAsBytes(actual)));
                }
              }));
    }
    return out.stream();
  }
}
