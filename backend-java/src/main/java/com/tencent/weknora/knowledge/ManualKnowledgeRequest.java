package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.chunker.GoJsonFirstValue;
import com.tencent.weknora.docreader.GoUtf8;
import com.tencent.weknora.security.ApiException;
import java.io.*;
import java.util.*;

/** Gin's first JSON value and encoding/json's typed duplicate/null semantics. */
public record ManualKnowledgeRequest(
    String title,
    String content,
    String status,
    List<String> tags,
    String channel,
    JsonNode overrides) {
  static ManualKnowledgeRequest decode(byte[] bytes, ObjectMapper json) {
    try {
      var schema =
          json.createObjectNode()
              .put("kind", "struct")
              .put("name", "ManualKnowledgePayload")
              .put("type", "types.ManualKnowledgePayload");
      var fields = schema.putArray("fields");
      for (String name : List.of("title", "content", "status", "channel"))
        fields.add(
            json.createObjectNode()
                .put("name", name)
                .set(
                    "schema", json.createObjectNode().put("kind", "string").put("type", "string")));
      fields.add(
          json.createObjectNode()
              .put("name", "tag_ids")
              .set(
                  "schema",
                  json.createObjectNode()
                      .put("kind", "slice")
                      .put("type", "[]string")
                      .set(
                          "elem",
                          json.createObjectNode().put("kind", "string").put("type", "string"))));
      try (var in =
          ManualKnowledgeRequest.class.getResourceAsStream("/knowledge-upload-schema.json")) {
        fields.add(
            json.createObjectNode()
                .put("name", "process_config")
                .set(
                    "schema",
                    json.createObjectNode()
                        .put("kind", "pointer")
                        .put("type", "*types.KnowledgeProcessOverrides")
                        .set("elem", json.readTree(in))));
      }
      int length = GoJsonFirstValue.length(bytes);
      var value =
          new GoUploadJson(json).decode(GoUtf8.decode(Arrays.copyOf(bytes, length)), schema);
      var tags = new ArrayList<String>();
      value.path("tag_ids").forEach(v -> tags.add(v.asText("")));
      return new ManualKnowledgeRequest(
          value.path("title").asText(""),
          value.path("content").asText(""),
          value.path("status").asText(""),
          List.copyOf(tags),
          value.path("channel").asText(""),
          value.get("process_config"));
    } catch (IOException error) {
      throw bad(error.getMessage());
    }
  }

  static ApiException bad(String message) {
    return new ApiException(400, "BAD_REQUEST", message).withAppCode(1000);
  }
}
