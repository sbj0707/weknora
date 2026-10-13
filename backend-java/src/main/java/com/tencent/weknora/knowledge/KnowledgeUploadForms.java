package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.security.ApiException;
import java.io.*;
import java.util.*;

/** Form binding before upload service execution; independent of multipart transport. */
public final class KnowledgeUploadForms {
  public record Bound(
      Map<String, String> metadata,
      Boolean multimodel,
      String customFilename,
      List<String> tags,
      String channel,
      ObjectNode overrides) {}

  private final ObjectMapper json;
  private final JsonNode schema;
  private final KnowledgeProcessConfig process;

  public KnowledgeUploadForms(ObjectMapper json) {
    this.json = json;
    process = new KnowledgeProcessConfig(json);
    try (var in = getClass().getResourceAsStream("/knowledge-upload-schema.json")) {
      if (in == null) throw new IllegalStateException("Missing upload binding schema");
      schema = json.readTree(in);
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  public Bound bind(Map<String, String> fields) {
    Map<String, String> metadata = null;
    String raw = fields.getOrDefault("metadata", "");
    if (!raw.isEmpty())
      try {
        ObjectNode mapSchema =
            json.createObjectNode().put("kind", "map").put("type", "map[string]string");
        mapSchema.set("elem", json.createObjectNode().put("kind", "string").put("type", "string"));
        JsonNode values = new GoUploadJson(json).decode(raw, mapSchema);
        if (!values.isNull()) {
          metadata = new LinkedHashMap<>();
          var entries = values.fields();
          while (entries.hasNext()) {
            var entry = entries.next();
            metadata.put(entry.getKey(), entry.getValue().asText());
          }
        }
      } catch (IOException error) {
        throw bad("Invalid metadata format", error.getMessage());
      }
    Boolean multimodel = null;
    raw = fields.getOrDefault("enable_multimodel", "");
    if (!raw.isEmpty())
      multimodel =
          switch (raw) {
            case "1", "t", "T", "TRUE", "true", "True" -> true;
            case "0", "f", "F", "FALSE", "false", "False" -> false;
            default ->
                throw bad(
                    "Invalid enable_multimodel format",
                    "strconv.ParseBool: parsing " + stringQuote(raw) + ": invalid syntax");
          };
    ObjectNode overrides = null;
    raw = fields.getOrDefault("process_config", "");
    if (!raw.isEmpty())
      try {
        JsonNode parsed = new GoUploadJson(json).decode(raw, schema);
        overrides = process.normalizeOverrides(parsed);
      } catch (IOException | IllegalArgumentException error) {
        throw bad("Invalid process_config format", error.getMessage());
      }
    if (multimodel != null && (overrides == null || !overrides.hasNonNull("enable_multimodel"))) {
      if (overrides == null) overrides = json.createObjectNode();
      overrides.put("enable_multimodel", multimodel);
    }
    List<String> tags = new ArrayList<>();
    for (String tag : fields.getOrDefault("tag_ids", "").split(",", -1)) {
      tag = goTrim(tag);
      if (!tag.isEmpty() && !tag.equals("__untagged__")) tags.add(tag);
    }
    return new Bound(
        metadata,
        multimodel,
        KnowledgeImportNames.sanitizeForLog(fields.getOrDefault("fileName", "")),
        List.copyOf(tags),
        fields.getOrDefault("channel", ""),
        overrides);
  }

  public static void validateJson(String filename, byte[] content) {
    if (!KnowledgeProcessConfig.fileType(filename).equalsIgnoreCase("json")) return;
    int offset =
        content.length >= 3
                && (content[0] & 255) == 239
                && (content[1] & 255) == 187
                && (content[2] & 255) == 191
            ? 3
            : 0;
    if (content.length == offset) throw bad("JSON 文件内容为空", null);
    try {
      GoUploadJson.valid(Arrays.copyOfRange(content, offset, content.length));
    } catch (IOException error) {
      throw bad("JSON 文件内容无效，请检查格式", null);
    }
  }

  static String goTrim(String text) {
    int from = 0, to = text.length();
    while (from < to && space(text.codePointAt(from)))
      from += Character.charCount(text.codePointAt(from));
    while (to > from && space(text.codePointBefore(to)))
      to -= Character.charCount(text.codePointBefore(to));
    return text.substring(from, to);
  }

  private static boolean space(int ch) {
    return ch >= 9 && ch <= 13
        || ch == 32
        || ch == 0x85
        || ch == 0xa0
        || ch == 0x1680
        || ch >= 0x2000 && ch <= 0x200a
        || ch == 0x2028
        || ch == 0x2029
        || ch == 0x202f
        || ch == 0x205f
        || ch == 0x3000;
  }

  private String stringQuote(String text) {
    try {
      return json.writeValueAsString(text);
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  static ApiException bad(String message, Object details) {
    return new ApiException(400, "BAD_REQUEST", message).withAppCode(1000).withDetails(details);
  }
}
