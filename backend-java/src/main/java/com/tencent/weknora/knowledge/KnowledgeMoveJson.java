package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** The public form, persisted worker payload and Redis record use distinct Go struct types. */
final class KnowledgeMoveJson {
  private KnowledgeMoveJson() {}

  static ObjectNode tenant(ObjectMapper json, Map<String, Object> row) {
    ObjectNode out = json.valueToTree(row);
    Object storage = row.get("storage_engine_config");
    if (storage != null) {
      try {
        // Go's driver.Valuer returns JSON bytes. Decode the original JDBC value before Jackson
        // turns a byte[] into Base64; PostgreSQL JSON objects and SQLite TEXT use their JSON text.
        out.set(
            "storage_engine_config",
            storage instanceof byte[] bytes
                ? json.readTree(bytes)
                : json.readTree(storage.toString()));
      } catch (IOException error) {
        throw new IllegalStateException("Invalid persisted tenant storage configuration", error);
      }
    }
    return out;
  }

  static ObjectNode schema(ObjectMapper json, String type) {
    var node =
        json.createObjectNode()
            .put("kind", "struct")
            .put("name", type)
            .put("type", type.equals("MoveKnowledgeRequest") ? "handler." + type : "types." + type);
    node.putArray("fields");
    return node;
  }

  static void field(ObjectMapper json, ObjectNode schema, String name, String kind, String type) {
    var value = json.createObjectNode().put("kind", kind).put("type", type);
    if (kind.equals("int") || kind.equals("uint")) value.put("bits", 64);
    add(json, schema, name, value);
  }

  static void add(ObjectMapper json, ObjectNode schema, String name, JsonNode value) {
    schema.withArray("fields").add(json.createObjectNode().put("name", name).set("schema", value));
  }

  static ObjectNode formSchema(ObjectMapper json, String type) {
    var s = schema(json, type);
    for (String name : List.of("source_kb_id", "target_kb_id", "mode"))
      field(json, s, name, "string", "string");
    add(
        json,
        s,
        "knowledge_ids",
        json.createObjectNode()
            .put("kind", "slice")
            .put("type", "[]string")
            .set("elem", json.createObjectNode().put("kind", "string").put("type", "string")));
    return s;
  }

  static ObjectNode payload(String raw, ObjectMapper json) throws IOException {
    var s = formSchema(json, "KnowledgeMovePayload");
    field(json, s, "tenant_id", "uint", "uint64");
    field(json, s, "task_id", "string", "string");
    for (String name :
        List.of(
            "lf_trace_id", "lf_parent_obs_id", "lf_traceparent", "lf_user_id", "lf_session_id")) {
      field(json, s, name, "string", "string");
      ((ObjectNode) s.withArray("fields").get(s.withArray("fields").size() - 1))
          .put("error_path", "TracingContext." + name);
    }
    var initiator = schema(json, "TaskInitiator");
    for (String name : List.of("user_id", "api_key_name"))
      field(json, initiator, name, "string", "string");
    field(json, initiator, "role", "string", "types.TenantRole");
    field(json, initiator, "api_key_id", "uint", "uint64");
    add(json, s, "initiator", initiator);
    JsonNode decoded = new GoUploadJson(json).decode(raw, s);
    return decoded.isObject() ? (ObjectNode) decoded : json.createObjectNode();
  }

  static ObjectNode progressSchema(ObjectMapper json) {
    var s = schema(json, "KnowledgeMoveProgress");
    for (String key :
        List.of("task_id", "source_kb_id", "target_kb_id", "status", "message", "error"))
      field(json, s, key, "string", key.equals("status") ? "types.KBCloneTaskStatus" : "string");
    for (String key :
        List.of("progress", "total", "processed", "failed", "created_at", "updated_at"))
      field(json, s, key, "int", key.endsWith("_at") ? "int64" : "int");
    return s;
  }

  static ObjectNode progress(ObjectMapper json, JsonNode value) {
    var out = json.createObjectNode();
    for (String key : List.of("task_id", "source_kb_id", "target_kb_id", "status"))
      out.put(key, value.path(key).asText(""));
    for (String key : List.of("progress", "total", "processed", "failed"))
      out.put(key, value.path(key).asLong());
    for (String key : List.of("message", "error")) out.put(key, value.path(key).asText(""));
    for (String key : List.of("created_at", "updated_at")) out.put(key, value.path(key).asLong());
    return out;
  }
}
