package com.tencent.weknora.vectorstore;

import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.chunker.GoJsonFirstValue;
import com.tencent.weknora.docreader.GoUtf8;
import com.tencent.weknora.knowledge.GoUploadJson;
import com.tencent.weknora.security.ApiException;
import java.io.*;
import java.util.*;

/** Gin first-value binding against schemas reflected from the actual public Go request structs. */
public final class VectorStorePublicForms {
  private final ObjectMapper json;
  private final JsonNode schemas;

  public VectorStorePublicForms(ObjectMapper json) {
    this.json = json;
    try (var in = getClass().getResourceAsStream("/vector-store-public-schema.json")) {
      schemas = json.readTree(in);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  public JsonNode decode(byte[] bytes, String operation) {
    try {
      int length = GoJsonFirstValue.length(bytes);
      var schema = schemas.path(operation);
      var value =
          new GoUploadJson(json).decode(GoUtf8.decode(Arrays.copyOf(bytes, length)), schema);
      var errors = new ArrayList<String>();
      for (var field : schema.path("fields")) {
        if (!field.path("required").asBoolean()) continue;
        String name = field.path("name").asText();
        var v = value.path(name);
        if (field.path("schema").path("kind").asText().equals("string") && v.asText("").isEmpty())
          errors.add(
              "Key: '"
                  + schema.path("name").asText()
                  + "."
                  + field.path("go_name").asText()
                  + "' Error:Field validation for '"
                  + field.path("go_name").asText()
                  + "' failed on the 'required' tag");
      }
      if (!errors.isEmpty()) throw new IOException(String.join("\n", errors));
      return value;
    } catch (IOException e) {
      throw error(400, 1000, e.getMessage());
    }
  }

  static String version(byte[] bytes, ObjectMapper json) {
    try {
      var text = json.createObjectNode().put("kind", "string").put("type", "string");
      var nested = json.createObjectNode().put("kind", "struct").put("name", "Version");
      nested
          .putArray("fields")
          .add(json.createObjectNode().put("name", "number").set("schema", text));
      var schema = json.createObjectNode().put("kind", "struct").put("name", "VersionResponse");
      schema
          .putArray("fields")
          .add(json.createObjectNode().put("name", "version").set("schema", nested));
      return new GoUploadJson(json)
          .decode(GoUtf8.decode(bytes), schema)
          .path("version")
          .path("number")
          .asText("");
    } catch (IOException invalid) {
      return "";
    }
  }

  static ApiException error(int status, int code, String message) {
    return new ApiException(status, "VECTOR_STORE_ERROR", message).withAppCode(code);
  }

  static ApiException direct(int status, String message) {
    return new ApiException(status, "VECTOR_STORE_ERROR", message)
        .withResponse(Map.of("success", false, "error", message));
  }
}
