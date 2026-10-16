package com.tencent.weknora.vectorstore;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.chunker.GoJsonFirstValue;
import com.tencent.weknora.knowledge.GoUploadJson;
import java.io.IOException;
import java.util.*;

/** ES8 typed update-by-query response, including generated Go custom unmarshallers. */
final class Elasticsearch8MoveResponse {
  private final ObjectMapper json;
  private final JsonNode schema;

  Elasticsearch8MoveResponse(ObjectMapper json) {
    this.json = json;
    var fields = json.createArrayNode();
    for (String name :
        List.of(
            "batches",
            "deleted",
            "failures",
            "noops",
            "requests_per_second",
            "retries",
            "task",
            "throttled",
            "throttled_millis",
            "throttled_until",
            "throttled_until_millis",
            "timed_out",
            "took",
            "total",
            "updated",
            "version_conflicts")) {
      JsonNode type =
          switch (name) {
            case "failures" -> slice(custom("BulkIndexByScrollFailure"));
            case "retries" -> pointer(custom("Retries"));
            case "requests_per_second" -> pointer(scalar("float32", "float32"));
            case "timed_out" -> pointer(scalar("bool", "bool"));
            case "task" -> scalar("interface", "types.TaskId");
            case "throttled", "throttled_until" -> scalar("interface", "types.Duration");
            default -> pointer(scalar("int64", "int64"));
          };
      fields.addObject().put("name", name).set("schema", type);
    }
    var root =
        json.createObjectNode()
            .put("kind", "struct")
            .put("name", "Response")
            .put("type", "updatebyquery.Response");
    root.set("fields", fields);
    schema = root;
  }

  JsonNode schema() {
    return schema.deepCopy();
  }

  void check(int status, byte[] data) {
    try {
      String raw =
          com.tencent.weknora.docreader.GoUtf8.decode(
              Arrays.copyOf(data, GoJsonFirstValue.length(data)));
      if (status >= 299) throw new IllegalStateException(error(status, raw));
      JsonNode value = decoder().decode(raw, schema);
      if (!value.hasNonNull("total")
          || !value.hasNonNull("updated")
          || value.path("total").asLong() < 0
          || value.path("total").asLong() != value.path("updated").asLong()
          || value.path("timed_out").asBoolean()
          || value.path("version_conflicts").asLong() != 0
          || value.path("failures").size() != 0)
        throw new IllegalStateException("move indices was incomplete");
    } catch (IOException invalid) {
      throw new IllegalStateException(invalid.getMessage(), invalid);
    }
  }

  private GoUploadJson decoder() {
    return new GoUploadJson(json, this::readCustom);
  }

  private ObjectNode scalar(String kind, String type) {
    return json.createObjectNode().put("kind", kind).put("type", type);
  }

  private ObjectNode custom(String name) {
    return scalar("custom", "types." + name).put("custom", name);
  }

  private ObjectNode pointer(JsonNode elem) {
    var out = scalar("pointer", "*" + elem.path("type").asText());
    out.set("elem", elem);
    return out;
  }

  private ObjectNode slice(JsonNode elem) {
    var out = scalar("slice", "[]" + elem.path("type").asText());
    out.set("elem", elem);
    return out;
  }

  private JsonNode readCustom(JsonParser parser, JsonNode schema, JsonNode previous, String source)
      throws IOException {
    return generated(schema.path("custom").asText(), raw(parser, source), previous);
  }

  private String raw(JsonParser parser, String text) throws IOException {
    // Jackson's string tokens are lazy; force consumption before taking the end offset.
    long start = parser.currentTokenLocation().getCharOffset();
    if (parser.currentToken() == JsonToken.VALUE_STRING) parser.getText();
    parser.skipChildren();
    return text.substring((int) start, (int) parser.currentLocation().getCharOffset());
  }

  private String next(JsonParser p, String source) throws IOException {
    JsonToken token = p.nextToken();
    if (token == null) throw new IOException("EOF");
    if (token == JsonToken.FIELD_NAME
        || token == JsonToken.END_OBJECT
        || token == JsonToken.END_ARRAY) throw new IOException("not at beginning of value");
    return raw(p, source);
  }

  private JsonNode generated(String kind, String raw, JsonNode previous) throws IOException {
    var out =
        previous != null && previous.isObject()
            ? (ObjectNode) previous.deepCopy()
            : json.createObjectNode();
    if (kind.equals("ErrorCause") && raw.startsWith("\"")) return out.put("reason", raw);
    try (JsonParser p = json.createParser(raw)) {
      while (p.nextToken() != null) {
        if (p.currentToken().isNumeric() && !Double.isFinite(p.getDoubleValue()))
          throw new GoUploadJson.CustomTypeException(
              "json: cannot unmarshal number " + p.getText() + " into Go value of type float64");
        if (p.currentToken() != JsonToken.FIELD_NAME && p.currentToken() != JsonToken.VALUE_STRING)
          continue;
        String key = p.currentToken() == JsonToken.FIELD_NAME ? p.currentName() : p.getText();
        if (kind.equals("Retries") && Set.of("bulk", "search").contains(key)) {
          String value;
          try {
            value = next(p, raw);
          } catch (IOException ignoredDecodeError) {
            continue;
          }
          // Decode(any) errors are ignored by the generated Go client; only strings are parsed
          // strictly.
          if (value.startsWith("\"")) {
            String text = decoder().decode(value, scalar("string", "string")).asText();
            try {
              if (!text.matches("[+-]?[0-9]+")) throw new NumberFormatException();
              Long.parseLong(text);
            } catch (NumberFormatException invalid) {
              String reason = text.matches("[+-]?[0-9]+") ? "value out of range" : "invalid syntax";
              throw new IOException(
                  title(key) + " | strconv.ParseInt: parsing " + quote(text) + ": " + reason);
            }
          }
        } else if (kind.equals("BulkIndexByScrollFailure")) {
          switch (key) {
            case "cause" -> readWrapped(p, raw, out, key, custom("ErrorCause"), "Cause | ");
            case "id", "index" ->
                readWrapped(p, raw, out, key, scalar("string", "string"), title(key) + " | ");
            case "status" -> {
              String value;
              try {
                value = next(p, raw);
              } catch (IOException ignoredDecodeError) {
                continue;
              }
              if (value.startsWith("\"")) {
                String text = decoder().decode(value, scalar("string", "string")).asText();
                try {
                  if (!text.matches("[+-]?[0-9]+")) throw new NumberFormatException();
                  Long.parseLong(text);
                } catch (NumberFormatException invalid) {
                  throw new IOException(
                      "Status | strconv.Atoi: parsing "
                          + quote(text)
                          + ": "
                          + (text.matches("[+-]?[0-9]+")
                              ? "value out of range"
                              : "invalid syntax"));
                }
              }
            }
            default -> {
              /* The generated decoder walks unknown values as tokens. */
            }
          }
        } else if (kind.equals("ErrorCause")) {
          switch (key) {
            case "caused_by" ->
                readWrapped(p, raw, out, key, pointer(custom("ErrorCause")), "CausedBy | ");
            case "root_cause", "suppressed" ->
                readWrapped(
                    p,
                    raw,
                    out,
                    key,
                    slice(custom("ErrorCause")),
                    key.equals("root_cause") ? "RootCause | " : "Suppressed | ");
            case "type", "reason", "stack_trace" -> {
              try {
                String value = next(p, raw);
                JsonNode parsed = json.readTree(value);
                out.put(key, parsed.isTextual() ? unquote(value) : value);
              } catch (IOException error) {
                throw new IOException(title(key) + " | " + error.getMessage());
              }
            }
            default -> {
              try {
                next(p, raw);
              } catch (IOException error) {
                throw new IOException("Metadata | " + error.getMessage());
              }
            }
          }
        }
      }
    }
    return out;
  }

  private void readWrapped(
      JsonParser p, String raw, ObjectNode out, String key, JsonNode type, String prefix)
      throws IOException {
    try {
      String value = next(p, raw);
      // A non-pointer custom Go struct keeps prior fields on duplicate input.
      if (type.path("kind").asText().equals("custom"))
        out.set(key, generated(type.path("custom").asText(), value, out.get(key)));
      else out.set(key, decoder().decode(value, type));
    } catch (IOException error) {
      throw new IOException(prefix + error.getMessage(), error);
    }
  }

  private String error(int httpStatus, String raw) throws IOException {
    long status = 0;
    JsonNode cause = json.createObjectNode();
    try (JsonParser p = json.createParser(raw)) {
      if (p.nextToken() != JsonToken.START_OBJECT) throw new IOException("expected start object");
      while (p.nextToken() != JsonToken.END_OBJECT) {
        String key = p.currentName();
        if (key.equals("error")) {
          try {
            cause = generated("ErrorCause", next(p, raw), cause);
          } catch (IOException error) {
            throw new IOException("error decoding error: " + error.getMessage(), error);
          }
        } else if (key.equals("status")) {
          try {
            JsonNode value = decoder().decode(next(p, raw), scalar("int", "int"));
            if (!value.isNull()) status = value.asLong();
          } catch (IOException error) {
            throw new IOException("error decoding status: " + error.getMessage(), error);
          }
        } else next(p, raw);
      }
    }
    return "status: "
        + (status == 0 ? httpStatus : status)
        + ", failed: ["
        + cause.path("type").asText("")
        + "], reason: "
        + cause.path("reason").asText("");
  }

  private String unquote(String raw) throws IOException {
    // strconv.Unquote is a Go string-literal parser: JSON's escaped slash/surrogates are rejected.
    for (int i = 1; i < raw.length() - 1; i++)
      if (raw.charAt(i) == '\\') {
        char escape = raw.charAt(++i);
        if (escape == '/') return raw;
        if (escape == 'u') {
          int code = Integer.parseInt(raw.substring(i + 1, i + 5), 16);
          if (code >= 0xd800 && code <= 0xdfff) return raw;
          i += 4;
        }
      }
    return json.readTree(raw).asText();
  }

  private String quote(String text) {
    var out = new StringBuilder("\"");
    text.codePoints()
        .forEach(
            c -> {
              switch (c) {
                case 7 -> out.append("\\a");
                case 8 -> out.append("\\b");
                case 9 -> out.append("\\t");
                case 10 -> out.append("\\n");
                case 11 -> out.append("\\v");
                case 12 -> out.append("\\f");
                case 13 -> out.append("\\r");
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                  if (c < 32 || c == 127) out.append(String.format(Locale.ROOT, "\\x%02x", c));
                  else if (Character.getType(c) == Character.UNASSIGNED
                      || (c != 32 && Character.getType(c) >= 12 && Character.getType(c) <= 19))
                    out.append(String.format(Locale.ROOT, c <= 0xffff ? "\\u%04x" : "\\U%08x", c));
                  else out.appendCodePoint(c);
                }
              }
            });
    return out.append('"').toString();
  }

  private static String title(String value) {
    return switch (value) {
      case "root_cause" -> "RootCause";
      case "stack_trace" -> "StackTrace";
      default -> Character.toUpperCase(value.charAt(0)) + value.substring(1);
    };
  }
}
