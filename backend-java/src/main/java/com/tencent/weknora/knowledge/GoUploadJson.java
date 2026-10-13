package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.chunker.GoJsonFirstValue;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Go encoding/json unmarshal subset generated from the actual upload structs. */
final class GoUploadJson {
  private final ObjectMapper json;
  private String firstTypeError;

  GoUploadJson(ObjectMapper json) {
    this.json = json;
  }

  static void valid(byte[] bytes) throws IOException {
    int length;
    try {
      length = GoJsonFirstValue.length(bytes);
    } catch (IOException error) {
      if (Set.of("EOF", "unexpected EOF").contains(error.getMessage()))
        throw new IOException("unexpected end of JSON input");
      throw error;
    }
    for (int i = length; i < bytes.length; i++)
      if (!space(bytes[i] & 255))
        throw new IOException(
            "invalid character " + quoteByte(bytes[i] & 255) + " after top-level value");
  }

  JsonNode decode(String raw, JsonNode schema) throws IOException {
    valid(raw.getBytes(StandardCharsets.UTF_8));
    firstTypeError = null;
    try (JsonParser parser = json.createParser(raw)) {
      parser.nextToken();
      JsonNode result = read(parser, schema, null, "", "");
      if (firstTypeError != null) throw new IOException(firstTypeError);
      return result;
    }
  }

  private JsonNode read(
      JsonParser parser, JsonNode schema, JsonNode previous, String struct, String path)
      throws IOException {
    String kind = schema.path("kind").asText();
    JsonToken token = parser.currentToken();
    if (kind.equals("pointer")) {
      if (token == JsonToken.VALUE_NULL) return NullNode.instance;
      return read(parser, schema.get("elem"), previous, struct, path);
    }
    if (token == JsonToken.VALUE_NULL)
      return Set.of("map", "slice").contains(kind)
          ? NullNode.instance
          : previous == null ? NullNode.instance : previous;
    if (kind.equals("struct") && token == JsonToken.START_OBJECT) {
      ObjectNode out =
          previous != null && previous.isObject()
              ? (ObjectNode) previous.deepCopy()
              : json.createObjectNode();
      while (parser.nextToken() != JsonToken.END_OBJECT) {
        String supplied = parser.currentName();
        JsonNode selected = null;
        for (JsonNode field : schema.path("fields"))
          if (field.path("name").asText().equals(supplied)) {
            selected = field;
            break;
          }
        if (selected == null)
          for (JsonNode field : schema.path("fields"))
            if (field.path("name").asText().equalsIgnoreCase(supplied)) {
              selected = field;
              break;
            }
        parser.nextToken();
        if (selected == null) {
          parser.skipChildren();
          continue;
        }
        String name = selected.path("name").asText();
        out.set(
            name,
            read(
                parser,
                selected.get("schema"),
                out.get(name),
                schema.path("name").asText(),
                path.isEmpty() ? name : path + "." + name));
      }
      return out;
    }
    if (kind.equals("map") && token == JsonToken.START_OBJECT) {
      ObjectNode out =
          previous != null && previous.isObject()
              ? (ObjectNode) previous.deepCopy()
              : json.createObjectNode();
      while (parser.nextToken() != JsonToken.END_OBJECT) {
        String key = unicode(parser.currentName());
        parser.nextToken();
        JsonNode value = read(parser, schema.get("elem"), null, struct, path);
        if (value.isNull() && schema.path("elem").path("kind").asText().equals("string"))
          value = TextNode.valueOf("");
        out.set(key, value);
      }
      return out;
    }
    if (kind.equals("slice") && token == JsonToken.START_ARRAY) {
      ArrayNode out = json.createArrayNode();
      while (parser.nextToken() != JsonToken.END_ARRAY)
        out.add(read(parser, schema.get("elem"), null, struct, path));
      return out;
    }
    if (kind.equals("string") && token == JsonToken.VALUE_STRING)
      return TextNode.valueOf(unicode(parser.getText()));
    if (kind.equals("bool") && (token == JsonToken.VALUE_TRUE || token == JsonToken.VALUE_FALSE))
      return BooleanNode.valueOf(token == JsonToken.VALUE_TRUE);
    if ((kind.startsWith("int") || kind.startsWith("uint")) && token.isNumeric()) {
      String number = parser.getText();
      try {
        if (!number.matches("-?[0-9]+")) throw new NumberFormatException();
        long value = Long.parseLong(number);
        if (kind.startsWith("uint") && value < 0) throw new NumberFormatException();
        return LongNode.valueOf(value);
      } catch (NumberFormatException error) {
        typeError("number " + number, schema, struct, path);
        return previous == null ? NullNode.instance : previous;
      }
    }
    String value =
        token == JsonToken.START_OBJECT
            ? "object"
            : token == JsonToken.START_ARRAY
                ? "array"
                : token == JsonToken.VALUE_STRING
                    ? "string"
                    : token.isNumeric() ? "number" : "bool";
    typeError(value, schema, struct, path);
    parser.skipChildren();
    return previous == null ? NullNode.instance : previous;
  }

  private void typeError(String value, JsonNode schema, String struct, String path) {
    if (firstTypeError != null) return;
    firstTypeError =
        "json: cannot unmarshal "
            + value
            + (struct.isEmpty() ? " into Go value" : " into Go struct field " + struct + "." + path)
            + " of type "
            + schema.path("type").asText();
  }

  static String unicode(String text) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < text.length(); i++) {
      char ch = text.charAt(i);
      if (Character.isHighSurrogate(ch)
          && i + 1 < text.length()
          && Character.isLowSurrogate(text.charAt(i + 1))) {
        out.append(ch).append(text.charAt(++i));
      } else out.append(Character.isSurrogate(ch) ? '\uFFFD' : ch);
    }
    return out.toString();
  }

  private static boolean space(int value) {
    return value == 32 || value == 9 || value == 10 || value == 13;
  }

  private static String quoteByte(int value) {
    return switch (value) {
      case 39 -> "'\\''";
      case 34 -> "'\"'";
      case 92 -> "'\\\\'";
      case 9 -> "'\\t'";
      case 10 -> "'\\n'";
      case 13 -> "'\\r'";
      default -> value < 32 ? String.format("'\\x%02x'", value) : "'" + (char) value + "'";
    };
  }
}
