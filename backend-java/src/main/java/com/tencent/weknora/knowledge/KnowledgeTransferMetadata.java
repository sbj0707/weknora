package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.docreader.GoUtf8;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Durable Go transfer checkpoints; unrelated RawMessage fields retain their numeric lexemes. */
final class KnowledgeTransferMetadata {
  static final String KEY = "_knowledge_transfer";
  private final ObjectMapper json;
  private final ObjectNode schema;

  KnowledgeTransferMetadata(ObjectMapper json) {
    this.json = json;
    schema =
        json.createObjectNode()
            .put("kind", "struct")
            .put("name", "knowledgeTransferState")
            .put("type", "service.knowledgeTransferState");
    var fields = schema.putArray("fields");
    for (String name :
        List.of(
            "task_id",
            "operation",
            "source_kb",
            "target_kb",
            "source_id",
            "mode",
            "wiki_chunk_ids",
            "wiki_summary",
            "phase")) {
      var type =
          json.createObjectNode()
              .put("kind", "string")
              .put("type", name.equals("operation") ? "access.KBTransferOperation" : "string");
      if (name.equals("wiki_chunk_ids"))
        type =
            json.createObjectNode()
                .put("kind", "slice")
                .put("type", "[]string")
                .set("elem", json.createObjectNode().put("kind", "string").put("type", "string"));
      fields.add(json.createObjectNode().put("name", name).set("schema", type));
    }
  }

  ObjectNode read(Object stored) throws IOException {
    String selected = fields(stored).get(KEY);
    if (selected == null) return null;
    JsonNode value = new GoUploadJson(json).decode(selected, schema);
    var result = json.createObjectNode();
    for (var field : schema.path("fields")) {
      String name = field.path("name").asText();
      if (name.equals("wiki_chunk_ids")) {
        if (value.path(name).isArray()) {
          var ids = result.putArray(name);
          for (var id : value.path(name)) ids.add(id.asText(""));
        } else result.set(name, NullNode.instance);
      } else result.put(name, value.path(name).asText(""));
    }
    return result;
  }

  String write(Object stored, ObjectNode state) throws IOException {
    var values = fields(stored);
    var ordered = json.createObjectNode();
    for (var field : schema.path("fields")) {
      String name = field.path("name").asText();
      var value = state.path(name);
      if (Set.of("mode", "wiki_summary").contains(name) && value.asText("").isEmpty()) continue;
      if (name.equals("wiki_chunk_ids")) {
        if (value.isArray() && !value.isEmpty()) ordered.set(name, value);
      } else ordered.put(name, value.asText(""));
    }
    values.put(KEY, json.writeValueAsString(ordered));
    var entries = new ArrayList<String>();
    for (var e : values.entrySet())
      entries.add(
          escape(json.writeValueAsString(e.getKey())) + ":" + escape(compact(e.getValue())));
    return "{" + String.join(",", entries) + "}";
  }

  /** FAQ container creation copies RawMessage fields while removing a source transfer marker. */
  String withoutTransfer(Object stored) throws IOException {
    String raw=KnowledgeCloneRepository.raw(stored);
    if(raw.isEmpty())return null;
    var values=fields(stored);
    if(raw.trim().equals("null"))return "null";
    values.remove(KEY);
    var entries=new ArrayList<String>();
    for(var e:values.entrySet())entries.add(escape(json.writeValueAsString(e.getKey()))+":"+escape(compact(e.getValue())));
    return "{"+String.join(",",entries)+"}";
  }

  static boolean matches(
      JsonNode state, String task, String source, String target, String id, String mode) {
    return matches(state,task,source,target,id,"move",mode);
  }

  static boolean matches(
      JsonNode state, String task, String source, String target, String id, String operation, String mode) {
    return state != null
        && !task.isEmpty()
        && state.path("task_id").asText().equals(task)
        && state.path("operation").asText().equals(operation)
        && state.path("source_kb").asText().equals(source)
        && state.path("target_kb").asText().equals(target)
        && state.path("source_id").asText().equals(id)
        && state.path("mode").asText().equals(mode);
  }

  private SortedMap<String, String> fields(Object stored) throws IOException {
    // UTF-8 byte sorting agrees with code-point order, including supplementary keys.
    var values =
        new TreeMap<String, String>(
            (left, right) ->
                Arrays.compareUnsigned(
                    left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8)));
    String raw =
        stored instanceof byte[] bytes ? GoUtf8.decode(bytes) : Objects.toString(stored, "");
    if (raw.isEmpty()) return values;
    GoUploadJson.valid(raw.getBytes(StandardCharsets.UTF_8));
    try (var p = json.createParser(raw)) {
      var token = p.nextToken();
      if (token == JsonToken.VALUE_NULL) return values;
      if (token != JsonToken.START_OBJECT) {
        String kind =
            token == JsonToken.START_ARRAY
                ? "array"
                : token == JsonToken.VALUE_STRING
                    ? "string"
                    : token.isNumeric() ? "number" : "bool";
        throw new IOException(
            "json: cannot unmarshal " + kind + " into Go value of type map[string]json.RawMessage");
      }
      String name;
      while ((name = p.nextFieldName()) != null) {
        p.nextToken();
        int start = Math.toIntExact(p.currentTokenLocation().getCharOffset());
        if (p.currentToken() == JsonToken.VALUE_STRING) p.getText();
        p.skipChildren();
        int end = Math.toIntExact(p.currentLocation().getCharOffset());
        values.put(GoUploadJson.unicode(name), raw.substring(start, end));
      }
    }
    return values;
  }

  private static String compact(String value) {
    var out = new StringBuilder();
    boolean string = false, escaped = false;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (string || !(c == ' ' || c == '\t' || c == '\r' || c == '\n')) out.append(c);
      if (escaped) escaped = false;
      else if (string && c == '\\') escaped = true;
      else if (c == '"') string = !string;
    }
    return out.toString();
  }

  private static String escape(String value) {
    return value
        .replace("<", "\\u003c")
        .replace(">", "\\u003e")
        .replace("&", "\\u0026")
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029");
  }
}
