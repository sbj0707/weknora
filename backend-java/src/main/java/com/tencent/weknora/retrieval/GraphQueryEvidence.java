package com.tencent.weknora.retrieval;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.IOException;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Shared Go Unicode deduplication and typed image decoder for graph hydration. */
public final class GraphQueryEvidence {
  private GraphQueryEvidence() {}
  public static List<ObjectNode> deduplicate(List<ObjectNode> input) {
    var ids = new HashSet<String>(); var contents = new HashSet<String>();
    var output = new ArrayList<ObjectNode>();
    for (var row : input) {
      String id = row.path("id").asText();
      String content = String.join(" ", HybridText.fields(HybridText.lower(HybridText.trim(row.path("content").asText()))));
      if (!content.isEmpty()) try { content = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(content.getBytes(StandardCharsets.UTF_8))); } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
      if (ids.contains(id) || !content.isEmpty() && contents.contains(content)) continue;
      ids.add(id); if (!content.isEmpty()) contents.add(content); output.add(row);
    }
    return List.copyOf(output);
  }
  public static List<ObjectNode> images(String raw, ObjectMapper json) {
    try {
      var values = RerankMetadata.images(raw); var output = new ArrayList<ObjectNode>();
      for (var value : values) {
        var image = json.createObjectNode();
        if (!value.path("sha256").asText().isEmpty()) image.put("sha256", value.path("sha256").asText());
        for (var key : List.of("url", "original_url")) image.put(key, value.path(key).asText());
        for (var key : List.of("start_pos", "end_pos")) image.put(key, value.path(key).asLong());
        for (var key : List.of("caption", "ocr_text")) image.put(key, value.path(key).asText());
        // Go emits the non-pointer attrs struct, including its non-pointer source struct.
        var attrs = json.createObjectNode().put("schema", value.path("attrs").path("schema").asText());
        var source = json.createObjectNode().put("schema", value.path("attrs").path("source").path("schema").asText())
            .put("prompt", value.path("attrs").path("source").path("prompt").asText());
        if (value.path("attrs").path("source").path("custom").asBoolean()) source.put("custom", true);
        attrs.set("source", source);
        if (value.path("attrs").path("attrs").isObject() && !value.path("attrs").path("attrs").isEmpty()) attrs.set("attrs", sortedMapValues(value.path("attrs").path("attrs"), json));
        image.set("attrs", attrs); output.add(image);
      }
      return output;
    } catch (IOException invalid) { return List.of(); }
  }
  // encoding/json sorts map keys by UTF-8 bytes; typed struct field order stays intact.
  private static JsonNode sortedMapValues(JsonNode value, ObjectMapper json) {
    if (value.isObject()) {
      var sorted = new TreeMap<String, JsonNode>((a, b) -> Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8)));
      value.fields().forEachRemaining(e -> sorted.put(e.getKey(), sortedMapValues(e.getValue(), json)));
      var out = json.createObjectNode(); sorted.forEach(out::set); return out;
    }
    if (value.isArray()) { var out = json.createArrayNode(); value.forEach(v -> out.add(sortedMapValues(v, json))); return out; }
    return value;
  }
}
