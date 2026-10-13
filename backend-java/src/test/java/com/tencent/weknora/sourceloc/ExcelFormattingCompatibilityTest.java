package com.tencent.weknora.sourceloc;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

class ExcelFormattingCompatibilityTest {
  final ObjectMapper json = new ObjectMapper();

  JsonNode fixture(String name) throws IOException {
    try (var in = getClass().getResourceAsStream("/" + name)) {
      if (in == null) throw new AssertionError(name);
      return json.readTree(name.endsWith(".gz") ? new GZIPInputStream(in) : in);
    }
  }

  @Test
  void actualGoParserTokenSectionsAndParts() throws Exception {
    int count = 0;
    for (JsonNode f : fixture("go-format-parser.json.gz")) {
      ArrayNode actual = json.createArrayNode();
      for (var section : ExcelNumberFormatParser.parse(f.path("format").asText())) {
        var s = json.createObjectNode().put("Type", section.type);
        ArrayNode items = json.createArrayNode();
        for (var token : section.items) items.add(token(token));
        s.set("Items", items);
        actual.add(s);
      }
      JsonNode expected = f.path("sections");
      if (expected.isNull() || expected.isMissingNode()) expected = json.createArrayNode();
      else {
        expected = expected.deepCopy();
        for (JsonNode s : expected)
          if (s.path("Items").isNull()) ((ObjectNode) s).set("Items", json.createArrayNode());
      }
      assertThat(actual).as("Go parser %s", f.path("format")).isEqualTo(expected);
      count++;
    }
    assertThat(count).isEqualTo(4520);
  }

  ObjectNode token(ExcelNumberFormatParser.Token t) {
    var n = json.createObjectNode().put("TValue", t.value()).put("TType", t.type());
    if (t.parts().isEmpty()) n.putNull("Parts");
    else {
      var p = json.createArrayNode();
      for (var part : t.parts()) p.add(json.createObjectNode().set("Token", token(part.token())));
      for (int i = 0; i < t.parts().size(); i++)
        ((ObjectNode) p.get(i)).put("Value", t.parts().get(i).value());
      n.set("Parts", p);
    }
    return n;
  }

  @Test
  void actualGoCustomBuiltInCurrencyLocaleEraAndTypedFormatting() throws Exception {
    int count = 0;
    for (JsonNode f : fixture("go-excel-format.json.gz")) {
      var o = f.path("options");
      var opts =
          o.isMissingNode()
              ? null
              : new ExcelNumberFormat.Options(
                  o.path("culture").asText(),
                  o.path("longDate").asText(),
                  o.path("longTime").asText());
      String actual =
          ExcelNumberFormat.format(
              f.path("value").asText(),
              f.path("format").asText(),
              f.path("date1904").asBoolean(),
              f.path("cell_type").asInt(),
              opts);
      assertThat(actual)
          .as(
              "Go format %s %s / type=%s date1904=%s",
              f.path("value"), f.path("format"), f.path("cell_type"), f.path("date1904"))
          .isEqualTo(f.path("expected").asText());
      count++;
    }
    assertThat(count).isEqualTo(59982);
    assertThat(ExcelNumberFormat.TABLES.path("languages").size()).isEqualTo(416);
  }

  @Test
  void actualGoRowsColumnsThroughRealOOXMLTypedCellsAndStyles() throws Exception {
    int documents = 0, units = 0;
    for (JsonNode f : fixture("go-excel-rows.json.gz")) {
      List<SourceAlignment.Unit> result = List.of();
      String error = "";
      try {
        result =
            OfficeStructure.units(
                f.path("file_type").asText(), Base64.getDecoder().decode(f.path("data").asText()));
      } catch (IOException e) {
        error = e.getMessage();
      }
      assertThat(error.isEmpty())
          .as("error presence %s", f.path("name"))
          .isEqualTo(f.path("error").asText().isEmpty());
      var actual = json.createArrayNode();
      for (var unit : result)
        actual.add(
            json.createObjectNode()
                .put("Text", unit.text())
                .set("Locator", json.valueToTree(unit.locator())));
      JsonNode expected = f.path("units");
      if (expected.isNull()) expected = json.createArrayNode();
      assertThat(actual).as("Go Rows.Columns %s", f.path("name")).isEqualTo(expected);
      documents++;
      units += expected.size();
    }
    assertThat(documents).isEqualTo(32);
    assertThat(units).isEqualTo(87439);
  }

  @Test
  void pathologicalGoFractionIsExplicitlyBoundedAndNotPassedOffAsSuccess() throws Exception {
    JsonNode f = fixture("go-excel-nontermination.json");
    assertThat(f.path("actual_go_timeout_seconds").asInt()).isEqualTo(3);
    assertThatExceptionOfType(OfficeStructure.UnsupportedCapability.class)
        .isThrownBy(
            () ->
                OfficeStructure.units("xlsx", Base64.getDecoder().decode(f.path("data").asText())))
        .withMessageContaining("does not terminate");
    try (var license = getClass().getResourceAsStream("/META-INF/licenses/NFP-LICENSE.txt")) {
      assertThat(license).isNotNull();
      assertThat(new String(license.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
          .contains("Redistribution and use", "THIS SOFTWARE");
    }
  }
}
