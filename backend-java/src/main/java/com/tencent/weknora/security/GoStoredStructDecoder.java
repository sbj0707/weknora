package com.tencent.weknora.security;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.math.*;

/**
 * Typed persisted Go JSON decoding; field/omitempty schema is generated from production Go types.
 */
public final class GoStoredStructDecoder {
  private final ObjectMapper json;
  private final JsonNode schema;

  public GoStoredStructDecoder(ObjectMapper json, JsonNode schema) {
    this.json = json;
    this.schema = schema;
  }

  public JsonNode decode(String raw) {
    try (var parser = json.getFactory().createParser(raw)) {
      if (parser.nextToken() == null) throw new IOException("empty config");
      JsonNode value = read(parser, schema, null);
      if (parser.nextToken() != null) throw new IOException("trailing config data");
      return value;
    } catch (Exception ex) {
      throw new IllegalArgumentException("Invalid stored Go typed configuration", ex);
    }
  }

  public JsonNode project(String raw) {
    return render(decode(raw), schema);
  }

  private JsonNode render(JsonNode value, JsonNode type) {
    if (value == null || value.isNull()) return NullNode.instance;
    String kind = type.path("kind").asText();
    if (kind.equals("pointer")) return render(value, type.path("items"));
    if (kind.equals("struct")) {
      ObjectNode out = json.createObjectNode();
      var fields = type.path("fields").fields();
      while (fields.hasNext()) {
        var field = fields.next();
        JsonNode node = value.get(field.getKey());
        boolean omit = field.getValue().path("omit_empty").asBoolean();
        if (node == null) {
          if (!omit) out.set(field.getKey(), zero(field.getValue()));
          continue;
        }
        if (omit && empty(node, field.getValue())) continue;
        out.set(field.getKey(), render(node, field.getValue()));
      }
      return out;
    }
    if (kind.equals("slice") || kind.equals("array")) {
      ArrayNode out = json.createArrayNode();
      for (JsonNode item : value) out.add(render(item, type.path("items")));
      return out;
    }
    return value;
  }

  private JsonNode zero(JsonNode type) {
    return switch (type.path("kind").asText()) {
      case "string" -> TextNode.valueOf("");
      case "bool" -> BooleanNode.FALSE;
      case "struct" -> render(json.createObjectNode(), type);
      case "map", "slice", "pointer", "interface" -> NullNode.instance;
      default -> IntNode.valueOf(0);
    };
  }

  private static boolean empty(JsonNode node, JsonNode type) {
    String kind = type.path("kind").asText();
    if (kind.equals("pointer") || kind.equals("interface")) return node.isNull();
    if (kind.equals("struct")) return false;
    return node.isNull()
        || node.isTextual() && node.textValue().isEmpty()
        || node.isBoolean() && !node.booleanValue()
        || node.isNumber() && node.decimalValue().signum() == 0
        || node.isContainerNode() && node.isEmpty();
  }

  private JsonNode read(JsonParser p, JsonNode type, JsonNode previous) throws IOException {
    String kind = type.path("kind").asText();
    JsonToken token = p.currentToken();
    if (kind.equals("pointer"))
      return token == JsonToken.VALUE_NULL
          ? NullNode.instance
          : read(p, type.path("items"), previous);
    if (token == JsonToken.VALUE_NULL)
      return switch (kind) {
        case "struct" ->
            previous != null && previous.isObject() ? previous : json.createObjectNode();
        case "map", "slice", "array", "interface" -> NullNode.instance;
        case "string" -> previous == null ? TextNode.valueOf("") : previous;
        case "bool" -> previous == null ? BooleanNode.FALSE : previous;
        default -> previous == null ? IntNode.valueOf(0) : previous;
      };
    switch (kind) {
      case "struct":
        {
          if (token != JsonToken.START_OBJECT) throw invalid();
          ObjectNode out =
              previous != null && previous.isObject()
                  ? ((ObjectNode) previous).deepCopy()
                  : json.createObjectNode();
          JsonNode fields = type.path("fields");
          while (p.nextToken() != JsonToken.END_OBJECT) {
            if (p.currentToken() != JsonToken.FIELD_NAME) throw invalid();
            String rawName = p.currentName(), name = fields.has(rawName) ? rawName : null;
            if (name == null) {
              var names = fields.fieldNames();
              while (names.hasNext()) {
                String candidate = names.next();
                if (fold(candidate).equals(fold(rawName))) {
                  name = candidate;
                  break;
                }
              }
            }
            p.nextToken();
            if (name == null) p.skipChildren();
            else out.set(name, read(p, fields.get(name), out.get(name)));
          }
          return out;
        }
      case "slice", "array":
        {
          if (token != JsonToken.START_ARRAY) throw invalid();
          ArrayNode out = json.createArrayNode();
          while (p.nextToken() != JsonToken.END_ARRAY) out.add(read(p, type.path("items"), null));
          return out;
        }
      case "map":
        {
          if (token != JsonToken.START_OBJECT) throw invalid();
          ObjectNode out =
              previous != null && previous.isObject()
                  ? ((ObjectNode) previous).deepCopy()
                  : json.createObjectNode();
          while (p.nextToken() != JsonToken.END_OBJECT) {
            String key = p.currentName();
            p.nextToken();
            out.set(key, read(p, type.path("items"), null));
          }
          return out;
        }
      case "string":
        if (token != JsonToken.VALUE_STRING) throw invalid();
        return TextNode.valueOf(surrogates(p.getText()));
      case "bool":
        if (token != JsonToken.VALUE_TRUE && token != JsonToken.VALUE_FALSE) throw invalid();
        return BooleanNode.valueOf(p.getBooleanValue());
      case "int", "int8", "int16", "int32", "int64", "uint", "uint8", "uint16", "uint32", "uint64":
        {
          if (token != JsonToken.VALUE_NUMBER_INT) throw invalid();
          BigInteger value = p.getBigIntegerValue();
          int bits = type.path("bits").asInt(64);
          boolean signed = !kind.startsWith("u");
          if (signed
              ? (value.compareTo(BigInteger.ONE.shiftLeft(bits - 1).negate()) < 0
                  || value.compareTo(BigInteger.ONE.shiftLeft(bits - 1)) >= 0)
              : (value.signum() < 0 || value.bitLength() > bits)) throw invalid();
          return BigIntegerNode.valueOf(value);
        }
      case "float32", "float64":
        if (!token.isNumeric()) throw invalid();
        double value = p.getDoubleValue();
        if (!Double.isFinite(value) || kind.equals("float32") && !Float.isFinite((float) value))
          throw invalid();
        return DecimalNode.valueOf(BigDecimal.valueOf(value).stripTrailingZeros());
      case "interface":
        {
          JsonNode node = p.readValueAsTree();
          finiteAny(node);
          return node;
        }
      default:
        throw invalid();
    }
  }

  private static void finiteAny(JsonNode node) throws IOException {
    if (node.isNumber() && !Double.isFinite(node.asDouble())) throw invalid();
    if (node.isContainerNode()) for (JsonNode child : node) finiteAny(child);
  }

  private static String fold(String value) {
    StringBuilder out = new StringBuilder();
    value
        .codePoints()
        .forEach(cp -> out.appendCodePoint(cp == 0x17f ? 's' : Character.toLowerCase(cp)));
    return out.toString();
  }

  private static String surrogates(String value) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < value.length(); i++) {
      char ch = value.charAt(i);
      if (Character.isHighSurrogate(ch)
          && i + 1 < value.length()
          && Character.isLowSurrogate(value.charAt(i + 1)))
        out.append(ch).append(value.charAt(++i));
      else out.append(Character.isSurrogate(ch) ? '\ufffd' : ch);
    }
    return out.toString();
  }

  private static IOException invalid() {
    return new IOException("invalid Go agent config type");
  }
}
