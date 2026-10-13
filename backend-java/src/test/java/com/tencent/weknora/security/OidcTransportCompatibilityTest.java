package com.tencent.weknora.security;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;

class OidcTransportCompatibilityTest {
  @TestFactory
  Stream<DynamicTest> actualProductionGoCases() throws Exception {
    ObjectMapper json = new ObjectMapper();
    JsonNode fixtures =
        json.readTree(
            Files.readString(
                Path.of("src/test/java/com/tencent/weknora/security/oidc-transport-golden.json")));
    List<DynamicTest> tests = new ArrayList<>();
    for (JsonNode row : fixtures)
      tests.add(
          DynamicTest.dynamicTest(
              row.path("name").asText(),
              () -> OidcTransportParity.verify(json.createArrayNode().add(row))));
    return tests.stream();
  }
}
