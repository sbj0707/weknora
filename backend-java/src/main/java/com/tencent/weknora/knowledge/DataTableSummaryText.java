package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.weknora.security.AuthPreferences;
import java.io.*;

/** The two original Go prompts and output envelopes; model output retains its whitespace. */
public final class DataTableSummaryText {
  private final ObjectMapper json;
  private final JsonNode prompts;

  public DataTableSummaryText(ObjectMapper json) {
    this.json = json;
    try (var input = getClass().getResourceAsStream("/datatable-prompts.json")) {
      if (input == null) throw new IllegalStateException("Missing data-table prompt templates");
      prompts = json.readTree(input);
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  public String prompt(boolean column, String schema, String sample, String instructions) {
    String prompt = prompts.path(column ? "column" : "table").asText()
        .formatted("dataset", schema, sample);
    String custom = AuthPreferences.trimGoSpace(instructions == null ? "" : instructions);
    if (custom.isEmpty()) return prompt;
    return AuthPreferences.trimGoSpace(prompt)
        + "\n\n<table_metadata_business_instructions>\n" + custom
        + "\n</table_metadata_business_instructions>\n"
        + "Apply these business instructions only when they do not conflict with the system-owned"
        + " output format, citation, safety, or factuality rules.";
  }

  public ObjectNode options(boolean column) {
    return json.createObjectNode().put("temperature", 0.3)
        .put("max_tokens", column ? 2048 : 512).put("thinking", false);
  }

  public String content(boolean column, JsonNode response) {
    String value = response == null ? "" : response.path("content").asText();
    if (AuthPreferences.trimGoSpace(value).isEmpty())
      throw new IllegalArgumentException("summary model returned empty output");
    return (column ? "# Table Column Information" : "# Table Summary")
        + "\n\nTable name: dataset\n\n" + value;
  }
}
