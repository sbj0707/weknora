package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;

/** Persisted evidence, rather than feature flags or parse completion, proves index completion. */
final class KnowledgeIndexStates {
  private KnowledgeIndexStates() {}

  static boolean inFlight(String status) {
    return Set.of("pending", "processing", "finalizing").contains(status);
  }

  static void document(ObjectNode row, String parseStatus, List<ObjectNode> spans) {
    Map<String, ObjectNode> latest = new HashMap<>();
    for (var span : spans) {
      String name = span.path("name").asText();
      var old = latest.get(name);
      int time = old == null ? 1 : time(span.path("updated_at")).compareTo(time(old.path("updated_at")));
      if (old == null || time > 0 || time == 0 && span.path("id").asLong() > old.path("id").asLong())
        latest.put(name, span);
    }
    String kind = row.path("kind").asText();
    boolean rag = kind.equals("vector") || kind.equals("keywords");
    var post = latest.get("postprocess");
    JsonNode postInput = field(post, "input"), postOutput = field(post, "output");
    JsonNode embedding = latest.get("embedding");
    JsonNode config = rag ? field(embedding, "input") : postInput;
    String configKey = kind.equals("keywords") ? "keyword_enabled" : kind.equals("questions") ? "question_enabled" : kind + "_enabled";
    List<ObjectNode> selected = new ArrayList<>();
    for (var entry : latest.entrySet()) {
      String name = entry.getKey();
      boolean matches = switch (kind) {
        case "vector", "keywords" -> name.equals("embedding") || name.equals("multimodal");
        case "graph" -> name.startsWith("postprocess.graph.chunk[");
        case "wiki" -> name.equals("postprocess.wiki");
        case "questions" -> name.startsWith("postprocess.question.batch[") || name.equals("postprocess.question") && !entry.getValue().path("input").has("batch_count");
        default -> false;
      };
      if (matches) selected.add(entry.getValue());
    }
    selected.sort(Comparator.comparing(s -> s.path("name").asText()));
    long done = 0, failed = 0, active = 0, cancelled = 0, skipped = 0, expected = 0;
    for (var span : selected) {
      switch (span.path("status").asText()) {
        case "done" -> {
          done++;
          if (span.hasNonNull("finished_at") && (!row.hasNonNull("last_built_at") || time(span.get("finished_at")).isAfter(time(row.get("last_built_at"))))) row.set("last_built_at", span.get("finished_at"));
          if (span.path("name").asText().equals("embedding")) expected = number(span.path("output").path("vectors_written"));
          if (number(span.path("output").path("llm_failed")) > 0 || number(span.path("output").path("pages_dropped")) > 0) failed++;
        }
        case "failed" -> { failed++; if (!row.hasNonNull("error_message") && !span.path("error_message").asText().isEmpty()) row.set("error_message", span.get("error_message")); }
        case "pending", "running" -> active++;
        case "cancelled" -> cancelled++;
        case "skipped" -> skipped++;
      }
    }
    String fanout = kind.equals("questions") ? "question" : kind;
    long planned = number(postOutput.path("planned_" + fanout + "_count"));
    long enqueued = kind.equals("wiki") ? (postOutput.path("enqueued_wiki").isBoolean() && postOutput.path("enqueued_wiki").asBoolean() ? 1 : 0) : number(postOutput.path("enqueued_" + fanout + "_count"));
    boolean enabled = row.path("enabled").asBoolean(), countKnown = row.hasNonNull("count"), inflight = inFlight(parseStatus);
    long count = row.path("count").asLong();
    String status = "unknown", reason = "";
    if (!enabled && (!countKnown || count == 0)) status = "disabled";
    else if (active > 0 && inflight) status = "building";
    else if (failed > 0 || planned > enqueued && !inflight) {
      status = done > 0 || countKnown && count > 0 ? "partial_failed" : "failed";
      if (planned > enqueued) reason = "enqueue_failed";
    } else if (cancelled > 0 || active > 0 && parseStatus.equals("cancelled")) status = done > 0 ? "partial_failed" : "cancelled";
    else if (active > 0) reason = "history_incomplete";
    else if (done > 0 && (enqueued == 0 || done >= enqueued)) status = "completed";
    else if (done > 0 && enqueued > done && inflight) status = "building";
    else if (inflight && enabled) status = "waiting";
    else if (enabled && countKnown && count == 0) { status = "stale"; reason = "not_built"; }
    else reason = "history_missing";
    if (status.equals("completed") && countKnown && expected > 0 && count < expected) { status = "stale"; reason = "missing_index"; }
    if (kind.equals("questions") && status.equals("completed") && countKnown && row.hasNonNull("indexed_count") && row.path("indexed_count").asLong() < count) { status = "partial_failed"; reason = "missing_index"; }
    if (config.path(configKey).isBoolean() && config.path(configKey).asBoolean() != enabled && !Set.of("building", "waiting").contains(status)) { status = "stale"; reason = "configuration_changed"; }
    if (skipped > 0 && done == 0 && enabled && !inflight) { status = "stale"; reason = "not_built"; }
    if (!countKnown && !Set.of("disabled", "building", "waiting").contains(status)) { status = "unknown"; reason = "statistics_unavailable"; }
    if (kind.equals("questions") && status.equals("completed") && !row.hasNonNull("indexed_count")) { status = "unknown"; reason = "statistics_unavailable"; }
    if (rag && status.equals("completed") && !field(embedding, "status").asText().equals("done") && (!countKnown || count == 0)) { status = "stale"; reason = "not_built"; }
    row.put("status", status);
    if (!reason.isEmpty()) row.put("reason", reason);
  }

  static void chunk(ObjectNode row, JsonNode chunk, boolean refreshing) {
    boolean enabled = row.path("enabled").asBoolean(), available = row.path("available").asBoolean();
    boolean data = !row.path("records").isEmpty() || !row.path("pages").isEmpty() || !row.path("questions").isEmpty() || !row.path("graph").path("nodes").isEmpty();
    String status, reason = "", kind = row.path("kind").asText();
    if (!available) { status = "unknown"; reason = "statistics_unavailable"; }
    else if (data) status = "completed";
    else if (!enabled) status = "disabled";
    else if (refreshing) status = "waiting";
    else { status = "stale"; reason = "not_built"; }
    if (Set.of("vector", "keywords").contains(kind) && enabled) {
      if (chunk.path("chunk_type").asText().equals("parent_text")) { status = "disabled"; reason = "context_only"; }
      else {
        if (chunk.path("index_status").asText().equals("processing")) status = "building";
        else if (chunk.path("index_status").asText().equals("failed")) status = "failed";
        Boolean primary = sourceIndexed(row, chunk.path("id").asText());
        if (available && chunk.path("index_status").asText().equals("ready") && Boolean.FALSE.equals(primary)) { status = "stale"; reason = "missing_index"; }
      }
    }
    if (kind.equals("questions") && status.equals("completed")) {
      for (var question : row.path("questions")) {
        if (!question.path("current").asBoolean()) { status = "stale"; reason = "content_changed"; break; }
        for (String key : List.of("vector_indexed", "keyword_indexed")) if (question.path(key).isBoolean() && !question.path(key).asBoolean()) { status = "partial_failed"; reason = "missing_index"; }
      }
    }
    row.put("status", status);
    if (!reason.isEmpty()) row.put("reason", reason);
  }

  static Boolean sourceIndexed(JsonNode row, String source) {
    if (!row.path("available").asBoolean()) return null;
    Map<String, Boolean> stores = new HashMap<>();
    for (var store : row.path("stores")) stores.put(store.asText(), false);
    for (var record : row.path("records")) {
      String store = record.path("store_type").asText();
      stores.putIfAbsent(store, false);
      if (record.path("source_id").asText().equals(source)) stores.put(store, true);
    }
    return !stores.isEmpty() && stores.values().stream().allMatch(Boolean::booleanValue);
  }

  private static long number(JsonNode value) {
    if(!value.isNumber())return 0;
    double number=value.asDouble();return number>=0x1.0p63||number< -0x1.0p63?Long.MIN_VALUE:(long)number;
  }

  static Instant time(JsonNode value) {
    return value == null || value.isNull() || value.isMissingNode() || value.asText().isEmpty() ? Instant.parse("0001-01-01T00:00:00Z") : Instant.parse(value.asText());
  }

  private static JsonNode field(JsonNode node, String field) {
    return node == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : node.path(field);
  }
}
