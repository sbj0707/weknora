package com.tencent.weknora.security;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.chunker.GoJsonFirstValue;
import com.tencent.weknora.docreader.GoUtf8;
import java.io.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Stored UserPreferences scanner; preserves pointer nulls and first typed error across duplicates.
 */
final class OidcUserPreferences {
  private final ObjectMapper json = new ObjectMapper();
  private String firstError;

  Map<String, Object> scan(Object value) {
    firstError = null;
    if (value == null) return new LinkedHashMap<>();
    String raw =
        value instanceof byte[] bytes
            ? GoUtf8.decode(bytes)
            : value instanceof String text
                ? text
                : value.getClass().getName().equals("org.postgresql.util.PGobject")
                    ? value.toString()
                    : null;
    if (raw == null) throw bad("UserPreferences.Scan: unsupported type");
    if (raw.isEmpty()) return new LinkedHashMap<>();
    byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
    int length;
    try {
      length = GoJsonFirstValue.length(bytes);
    } catch (IOException ex) {
      throw bad(
          Set.of("EOF", "unexpected EOF").contains(ex.getMessage())
              ? "unexpected end of JSON input"
              : ex.getMessage());
    }
    for (int i = length; i < bytes.length; i++)
      if (!Set.of(9, 10, 13, 32).contains(bytes[i] & 255))
        throw bad("invalid character '" + (char) bytes[i] + "' after top-level value");
    try (var parser = json.createParser(GoUtf8.decode(Arrays.copyOf(bytes, length)))) {
      parser.nextToken();
      Map<String, Object> result = read(parser, "UserPreferences", new LinkedHashMap<>());
      if (firstError != null) throw bad(firstError);
      return result;
    } catch (IOException ex) {
      throw bad(ex.getMessage());
    }
  }

  private Map<String, Object> read(JsonParser p, String owner, Map<String, Object> old)
      throws IOException {
    if (p.currentToken() == JsonToken.VALUE_NULL) return old;
    if (p.currentToken() != JsonToken.START_OBJECT) {
      error(
          p,
          owner,
          "",
          owner.equals("UserPreferences") ? "types.UserPreferences" : "types.GalleryUserPrefs",
          false);
      p.skipChildren();
      return old;
    }
    Map<String, Object> out = new LinkedHashMap<>(old);
    String raw;
    while ((raw = p.nextFieldName()) != null) {
      p.nextToken();
      String key = null;
      List<String> fields =
          owner.equals("UserPreferences")
              ? List.of(
                  "browser_search_instructions",
                  "last_active_tenant_id",
                  "oidc_only_login",
                  "gallery")
              : List.of("mode", "status");
      for (String field : fields)
        if (field.equalsIgnoreCase(raw)) {
          key = field;
          break;
        }
      if (key == null) {
        p.skipChildren();
        continue;
      }
      boolean root = owner.equals("UserPreferences");
      JsonToken token = p.currentToken();
      if (token == JsonToken.VALUE_NULL) {
        if (root) out.remove(key);
        continue;
      }
      if (key.equals("gallery")) {
        if (token != JsonToken.START_OBJECT) {
          error(p, "UserPreferences", "gallery", "types.GalleryUserPrefs", false);
          p.skipChildren();
          continue;
        }
        Map<String, Object> existing =
            out.get(key) instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : new LinkedHashMap<>();
        out.put(key, read(p, "GalleryUserPrefs", existing));
      } else if (key.equals("status")) {
        if (token != JsonToken.START_OBJECT) {
          error(p, "GalleryUserPrefs", "gallery.status", "map[string]string", false);
          p.skipChildren();
          continue;
        }
        Map<String, Object> status =
            out.get(key) instanceof Map<?, ?> map
                ? new LinkedHashMap<>((Map<String, Object>) map)
                : new LinkedHashMap<>();
        String name;
        while ((name = p.nextFieldName()) != null) {
          p.nextToken();
          if (p.currentToken() == JsonToken.VALUE_NULL) status.put(name, "");
          else if (p.currentToken() == JsonToken.VALUE_STRING) status.put(name, p.getText());
          else {
            error(p, "GalleryUserPrefs", "gallery.status", "string", false);
            p.skipChildren();
          }
        }
        if (!status.isEmpty()) out.put(key, status);
      } else if (key.equals("oidc_only_login")) {
        if (token == JsonToken.VALUE_TRUE || token == JsonToken.VALUE_FALSE)
          out.put(key, p.getBooleanValue());
        else {
          error(p, owner, key, "bool", false);
          p.skipChildren();
        }
      } else if (key.equals("last_active_tenant_id")) {
        if (token == JsonToken.VALUE_NUMBER_INT
            && p.getBigIntegerValue().signum() >= 0
            && p.getBigIntegerValue().bitLength() <= 64) out.put(key, p.getBigIntegerValue());
        else {
          error(p, owner, key, "uint64", token.isNumeric());
          p.skipChildren();
        }
      } else if (token == JsonToken.VALUE_STRING) {
        String text = p.getText();
        if (root || !text.isEmpty()) out.put(key, text);
        else out.remove(key);
      } else {
        error(p, owner, root ? key : "gallery." + key, "string", false);
        p.skipChildren();
      }
    }
    return out;
  }

  private void error(JsonParser p, String owner, String field, String type, boolean number)
      throws IOException {
    if (firstError != null) return;
    String token =
        p.currentToken() == JsonToken.START_ARRAY
            ? "array"
            : p.currentToken() == JsonToken.START_OBJECT
                ? "object"
                : p.currentToken() == JsonToken.VALUE_STRING
                    ? "string"
                    : p.currentToken() == JsonToken.VALUE_TRUE
                            || p.currentToken() == JsonToken.VALUE_FALSE
                        ? "bool"
                        : "number";
    if (number) token += " " + p.getText();
    firstError =
        "json: cannot unmarshal "
            + token
            + (field.isEmpty()
                ? " into Go value of type "
                : " into Go struct field " + owner + "." + field + " of type ")
            + type;
  }

  private static IllegalArgumentException bad(String message) {
    return new IllegalArgumentException(message);
  }
}
