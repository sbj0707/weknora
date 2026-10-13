package com.tencent.weknora.sourceloc;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.docreader.DocumentReadResult;
import com.tencent.weknora.knowledge.NativeDocumentSources;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;

class OfficeStructureCompatibilityTest {
  final ObjectMapper json = new ObjectMapper();

  JsonNode fixtures(String resource) throws IOException {
    return json.readTree(getClass().getResourceAsStream(resource));
  }

  byte[] data(JsonNode fixture) {
    return Base64.getDecoder().decode(fixture.path("data").asText());
  }

  JsonNode array(JsonNode value) {
    return value.isNull() || value.isMissingNode() ? json.createArrayNode() : value;
  }

  @Test
  void actualGoOfficeUnitsAndMarkdownAlignment() throws Exception {
    int count = 0;
    for (var fixture : fixtures("/go-office-source.json")) {
      if (fixture.has("unit_count") || fixture.path("java_pending").asBoolean()) continue;
      List<SourceAlignment.Unit> units = List.of();
      String error = "";
      try {
        units = OfficeStructure.units(fixture.path("file_type").asText(), data(fixture));
      } catch (IOException failure) {
        error = failure.getMessage();
      }
      assertThat(error.isEmpty())
          .as("error presence %s", fixture.path("name"))
          .isEqualTo(fixture.path("error").asText().isEmpty());
      var actual = json.createArrayNode();
      for (var unit : units)
        actual.add(
            json.createObjectNode()
                .put("Text", unit.text())
                .set("Locator", json.valueToTree(unit.locator())));
      assertThat(actual)
          .as("Go units %s", fixture.path("name"))
          .isEqualTo(array(fixture.path("units")));
      var blocks = json.createArrayNode();
      if (error.isEmpty())
        for (var block :
            OfficeStructure.align(
                fixture.path("file_type").asText(),
                data(fixture),
                fixture.path("markdown").asText()))
          blocks.add(
              json.createObjectNode()
                  .put("Start", block.start())
                  .put("End", block.end())
                  .set("Locator", json.valueToTree(block.locator())));
      assertThat(json.readTree(blocks.toString()))
          .as("Go align %s", fixture.path("name"))
          .isEqualTo(array(fixture.path("blocks")));
      count++;
    }
    assertThat(count).isEqualTo(235);
  }

  @Test
  void actualGoWholeOutputDigestsVerifyUnitLimitsIncludingPerSlideOvershoot() throws Exception {
    int count = 0;
    for (var fixture : fixtures("/go-office-source.json")) {
      if (!fixture.has("unit_count")) continue;
      var units = OfficeStructure.units(fixture.path("file_type").asText(), data(fixture));
      assertThat(units)
          .as("Go bounded count %s", fixture.path("name"))
          .hasSize(fixture.path("unit_count").asInt());
      var digest = MessageDigest.getInstance("SHA-256");
      for (var unit : units) {
        digest.update((unit.text() + "\n").getBytes(StandardCharsets.UTF_8));
        digest.update(json.writeValueAsBytes(new TreeMap<>(unit.locator())));
        digest.update((byte) '\n');
      }
      assertThat(HexFormat.of().formatHex(digest.digest()))
          .as("all bounded units %s", fixture.path("name"))
          .isEqualTo(fixture.path("digest").asText());
      count++;
    }
    assertThat(count).isEqualTo(4);
  }

  @Test
  void actualGoAttachmentRetainsExistingBlocksAndDegradesDamagedSupportedStructure()
      throws Exception {
    int count = 0;
    for (var fixture : fixtures("/go-office-attachment.json")) {
      String parsed = fixture.path("parsed").asText(),
          finalMarkdown = fixture.path("final").asText();
      List<DocumentReadResult.SourceBlock> supplied =
          fixture.path("provided").asBoolean()
              ? List.of(
                  new DocumentReadResult.SourceBlock(
                      0,
                      parsed.codePointCount(0, parsed.length()),
                      Map.of("type", "pdf", "page", 7)))
              : List.of();
      var read = new DocumentReadResult(parsed, List.of(), "", Map.of(), "", supplied);
      var index =
          NativeDocumentSources.build(
              read,
              finalMarkdown,
              fixture.path("file_type").asText(),
              "original-file-hash",
              data(fixture));
      assertThat(
              (JsonNode)
                  json.valueToTree(
                      index.locators(0, finalMarkdown.codePointCount(0, finalMarkdown.length()))))
          .as(
              "production Go attachment %s / provided=%s",
              fixture.path("name"), fixture.path("provided"))
          .isEqualTo(fixture.path("locators"));
      count++;
    }
    assertThat(count).isEqualTo(20);
  }

  @Test
  void customExcelFormattingPreservesActualGoOriginalCoordinates() throws Exception {
    JsonNode fixture = null;
    for (var candidate : fixtures("/go-office-source.json"))
      if (candidate.path("name").asText().equals("xlsx-custom-format")) fixture = candidate;
    assertThat(fixture).isNotNull();
    assertThat(fixture.path("units").get(0).path("Text").asText()).isEqualTo("3.1400");
    final JsonNode selected = fixture;
    assertThat(OfficeStructure.units("xlsx", data(selected))).hasSize(1);
    assertThat(OfficeStructure.units("xlsx", data(selected)).getFirst().text()).isEqualTo("3.1400");
    var read = new DocumentReadResult("3.1400", List.of(), "", Map.of(), "", List.of());
    var mapped =
        NativeDocumentSources.build(read, "3.1400", "xlsx", "hash", data(selected)).locators(0, 6);
    assertThat(mapped)
        .containsExactly(
            Map.of(
                "type",
                "sheet",
                "sheet",
                "Sheet1",
                "row_start",
                1,
                "row_end",
                1,
                "mapping",
                "exact",
                "source_hash",
                "hash",
                "quote",
                "3.1400"));
    var alreadyMapped =
        new DocumentReadResult(
            "3.1400",
            List.of(),
            "",
            Map.of(),
            "",
            List.of(
                new DocumentReadResult.SourceBlock(
                    0,
                    6,
                    Map.of("type", "sheet", "sheet", "Sheet1", "row_start", 1, "row_end", 1))));
    assertThat(
            NativeDocumentSources.build(alreadyMapped, "3.1400", "xlsx", "hash", data(selected))
                .locators(0, 6))
        .hasSize(1);
  }
}
