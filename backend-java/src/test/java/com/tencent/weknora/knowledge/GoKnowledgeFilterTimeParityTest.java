package com.tencent.weknora.knowledge;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;

class GoKnowledgeFilterTimeParityTest {
  @TestFactory
  Stream<DynamicTest> matchesActualGoFilterLayouts() throws Exception {
    var fixture =
        new ObjectMapper()
            .readTree(getClass().getResourceAsStream("/go-knowledge-filter-time.json"));
    List<DynamicTest> tests = new ArrayList<>();
    for (var row : fixture)
      tests.add(
          DynamicTest.dynamicTest(
              row.path("zone").asText() + "/" + row.path("raw").asText(),
              () -> {
                TimeZone original = TimeZone.getDefault();
                try {
                  TimeZone.setDefault(TimeZone.getTimeZone(row.path("zone").asText()));
                  if (row.has("error")) {
                    var failure =
                        assertThrows(
                            com.tencent.weknora.security.ApiException.class,
                            () ->
                                GoKnowledgeFilterTime.parse(
                                    row.path("raw").asText(), "start_time"));
                    assertEquals(
                        "invalid start_time: " + row.path("error").asText(), failure.getMessage());
                  } else {
                    var parsed =
                        GoKnowledgeFilterTime.parse(row.path("raw").asText(), "start_time");
                    if (row.path("zero").asBoolean()) assertNull(parsed);
                    else
                      assertEquals(
                          Instant.ofEpochSecond(
                              row.path("second").asLong(), row.path("nano").asInt()),
                          parsed);
                  }
                } finally {
                  TimeZone.setDefault(original);
                }
              }));
    return tests.stream();
  }
}
