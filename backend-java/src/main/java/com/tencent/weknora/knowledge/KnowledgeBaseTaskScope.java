package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.*;

/** The full Go runtimeTaskPayloadProbe is decoded before any scope match is accepted. */
final class KnowledgeBaseTaskScope {
  private final ObjectMapper json;
  private final ObjectNode schema;

  KnowledgeBaseTaskScope(ObjectMapper json) {
    this.json = json;
    schema = KnowledgeMoveJson.schema(json, "runtimeTaskPayloadProbe");
    schema.put("type", "router.runtimeTaskPayloadProbe");
    KnowledgeMoveJson.field(json, schema, "tenant_id", "uint", "uint64");
    for (String field : List.of("knowledge_base_id", "kb_id", "knowledge_id", "task_id",
        "source_id", "target_id", "source_kb_id", "target_kb_id", "data_source_id", "sync_log_id"))
      KnowledgeMoveJson.field(json, schema, field, "string", "string");
    KnowledgeMoveJson.add(json, schema, "knowledge_ids", json.createObjectNode()
        .put("kind", "slice").put("type", "[]string")
        .set("elem", json.createObjectNode().put("kind", "string").put("type", "string")));
    for (String field : List.of("enqueued_at", "created_at"))
      KnowledgeMoveJson.field(json, schema, field, "int", "int64");
  }

  boolean matches(String type, String raw, String base, Set<String> documents, Set<String> sources) {
    if (type.equals("kb:delete") || type.equals("index:delete")) return false;
    final JsonNode probe;
    try { probe = new GoUploadJson(json).decode(raw, schema); }
    catch (IOException invalid) { return false; }
    if (base != null && !base.isEmpty()) {
      if (same(probe, "knowledge_base_id", base) || same(probe, "kb_id", base)) return true;
      if (type.equals("kb:clone") && (same(probe, "source_id", base) || same(probe, "target_id", base))) return true;
      if (type.equals("knowledge:move") && (same(probe, "source_kb_id", base) || same(probe, "target_kb_id", base))) return true;
    }
    if (contains(documents, probe.path("knowledge_id").asText(""))) return true;
    for (JsonNode id : probe.path("knowledge_ids")) if (contains(documents, id.asText(""))) return true;
    return contains(sources, probe.path("data_source_id").asText(""));
  }

  private static boolean same(JsonNode value, String field, String expected) {
    return value.path(field).asText("").equals(expected);
  }

  private static boolean contains(Set<String> values, String id) {
    return !id.isEmpty() && values != null && values.contains(id);
  }
}
