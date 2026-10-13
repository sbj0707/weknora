package com.tencent.weknora.knowledge;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class DataTableCompatibilityTest {
  @TempDir Path temp;
  final ObjectMapper json = new ObjectMapper();

  JsonNode fixtures(String name) throws Exception {
    try (var input = getClass().getResourceAsStream("/" + name)) {
      assertThat(input).isNotNull();
      return json.readTree(input);
    }
  }

  @TestFactory
  Stream<DynamicTest> actualGoDuckDbTableSchemaSampleAndCleanup() throws Exception {
    JsonNode cases = fixtures("go-datatable-loader.json");
    assertThat(cases.size()).isEqualTo(10);
    return StreamSupport.stream(cases.spliterator(), false).map(row ->
        DynamicTest.dynamicTest(row.path("name").asText(), () -> {
          Path file = temp.resolve("data'file-" + row.path("name").asText() + "."
              + row.path("file_type").asText());
          Files.write(file, Base64.getDecoder().decode(row.path("input_base64").asText()));
          try (var db = DriverManager.getConnection("jdbc:duckdb:")) {
            try (var statement = db.createStatement()) {
              try (var version = statement.executeQuery("SELECT version(), source_id FROM pragma_version()")) {
                assertThat(version.next()).isTrue();
                assertThat(version.getString(1)).isEqualTo("v1.5.2");
                assertThat(version.getString(2)).isEqualTo("8a5851971f");
              }
              statement.execute("LOAD spatial");
              statement.execute("LOAD excel");
            }
            try (var session = new DuckDbTableSession(db, "table_summary_table-123", () -> {})) {
              JsonNode schema = session.load("table-123", row.path("file_type").asText(), file);
              assertThat(json.readTree(json.writeValueAsBytes(schema))).isEqualTo(row.path("schema"));
              assertThat(DuckDbTableSession.description(schema)).isEqualTo(row.path("description").asText());
              var sample = session.sample(schema.path("table_name").asText());
              assertThat(sample).isEqualTo(row.path("rows"));
              assertThat(session.sampleDescription(sample)).isEqualTo(row.path("sample").asText());
            }
            try (var statement = db.createStatement(); var result = statement.executeQuery(
                "SELECT count(*) FROM duckdb_tables() WHERE table_name='k_table_123'")) {
              assertThat(result.next()).isTrue();
              assertThat(result.getInt(1)).isEqualTo(row.path("tables_after_cleanup").asInt());
            }
            assertThat(Files.exists(file)).isEqualTo(row.path("source_preserved").asBoolean());
          }
        }));
  }

  @TestFactory
  Stream<DynamicTest> promptsOptionsAndOriginalOutputMatchActualGoSummaryHandler() throws Exception {
    JsonNode cases = fixtures("go-datatable-summary.json");
    JsonNode loaders = fixtures("go-datatable-loader.json");
    assertThat(cases.size()).isEqualTo(37);
    var text = new DataTableSummaryText(json);
    return StreamSupport.stream(cases.spliterator(), false)
        .filter(row -> row.path("requests").isArray() && !row.path("requests").isEmpty())
        .map(row -> DynamicTest.dynamicTest(row.path("name").asText(), () -> {
          JsonNode load = loaders.get(row.path("load_case").asInt());
          String instructions = switch (row.path("name").asText()) {
            case "custom" -> "  KB business <>& 中文  ";
            case "override" -> " Override instruction \n test ";
            default -> "";
          };
          int chat = 0;
          for (JsonNode request : row.path("requests")) {
            if (!request.path("path").asText().equals("/v1/chat/completions")) continue;
            boolean column = chat++ > 0;
            JsonNode body = request.path("body");
            assertThat(body.path("messages").size()).isEqualTo(1);
            assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("user");
            assertThat(text.prompt(column, load.path("description").asText(),
                load.path("sample").asText(), instructions))
                .isEqualTo(body.path("messages").get(0).path("content").asText());
            var options = text.options(column);
            assertThat(options.path("temperature")).isEqualTo(body.path("temperature"));
            assertThat(options.path("max_tokens")).isEqualTo(body.path("max_tokens"));
            assertThat(options.path("thinking").asBoolean()).isFalse();
            assertThat(body.path("chat_template_kwargs").path("enable_thinking").asBoolean()).isFalse();
          }
          assertThat(chat).isBetween(1, 2);
          for (JsonNode chunk : row.path("chunks")) {
            String type = chunk.path("type").asText();
            if (!type.startsWith("table_")) continue;
            boolean column = type.equals("table_column");
            String response = column ? "\n Column metadata 中文 \t" : "  Table metadata 中文 <>&\n ";
            assertThat(text.content(column, json.createObjectNode().put("content", response)))
                .isEqualTo(chunk.path("content").asText());
          }
          if (row.path("name").asText().endsWith("-empty")) {
            boolean column = row.path("name").asText().startsWith("column");
            assertThatThrownBy(() -> text.content(column,
                json.createObjectNode().put("content", " \n\t\u00a0")))
                .hasMessage("summary model returned empty output");
          }
        }));
  }
}
