package com.tencent.weknora.knowledge;

import static com.tencent.weknora.knowledge.KnowledgeTransferRepository.number;
import static com.tencent.weknora.knowledge.KnowledgeTransferRepository.text;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.weknora.docreader.GoUtf8;
import java.time.Duration;
import java.util.*;

/** The Go per-document move state machine, with independently committed durable checkpoints. */
final class KnowledgeMoveExecution {
  record Submission(
      String type,
      String id,
      ObjectNode payload,
      String queue,
      int maxRetries,
      Duration timeout,
      Duration retention) {}

  /** An enqueue conflict or duplicate is successful admission, including after a lost ack. */
  static final class AlreadyEnqueued extends Exception {}

  interface Effects {
    void requireTransfer(KnowledgeTransferRepository.Scope scope, JsonNode source, JsonNode target)
        throws Exception;

    void moveIndices(
        Map<String, Object> document, JsonNode source, JsonNode target, List<String> chunkIds)
        throws Exception;

    void deleteGraph(String source, String id) throws Exception;

    void cleanupResources(Map<String, Object> document, JsonNode source) throws Exception;

    void cleanupWiki(Map<String, Object> oldDocument, Set<String> oldChunkIds) throws Exception;

    void enqueue(Submission task) throws Exception;

    void ingestWiki(long tenant, String target, String id) throws Exception;
  }

  private final KnowledgeTransferRepository repository;
  private final KnowledgeTransferMetadata metadata;
  private final ObjectMapper json;
  private final Effects effects;
  private final ObjectNode context;
  private final Duration documentTimeout;

  KnowledgeMoveExecution(
      KnowledgeTransferRepository repository,
      ObjectMapper json,
      Effects effects,
      ObjectNode context,
      Duration documentTimeout) {
    this.repository = repository;
    this.json = json;
    this.metadata = new KnowledgeTransferMetadata(json);
    this.effects = effects;
    this.context = context.deepCopy();
    this.documentTimeout =
        documentTimeout == null || documentTimeout.isZero() || documentTimeout.isNegative()
            ? Duration.ofHours(2)
            : documentTimeout;
  }

  void move(
      KnowledgeTransferRepository.Scope scope,
      JsonNode source,
      JsonNode target,
      JsonNode tenant,
      String id,
      String mode)
      throws Exception {
    require(scope, source, target);
    KnowledgeMoveCompatibility.validate(source, target, mode, tenant);
    var row = get(scope.tenant(), id);
    if (!text(row, "id").equals(id)) throw new IllegalArgumentException("kb_access: not found");
    repository.validateItem(scope, row, mode);
    var state = metadata.read(row.get("metadata"));
    if (matches(scope, state, id, mode)) {
      String phase = state.path("phase").asText();
      if (phase.equals("done") || phase.equals("reparse_pending")) {
        cleanupWiki(scope, row, source, target);
        if (phase.equals("reparse_pending")) enqueue(scope, row, source, target);
        else if (mode.equals("reuse_vectors") && wiki(target))
          effects.ingestWiki(scope.tenant(), scope.target(), id);
        return;
      }
    } else {
      state =
          json.createObjectNode()
              .put("task_id", scope.task())
              .put("operation", "move")
              .put("source_kb", scope.source())
              .put("target_kb", scope.target())
              .put("source_id", id)
              .put("mode", mode)
              .put("phase", "moving");
      if (wiki(source)) {
        var refs = state.putArray("wiki_chunk_ids");
        for (var chunk : repository.chunks(scope.tenant(), id, Set.of(scope.source())))
          refs.add(text(chunk, "id"));
        state.put("wiki_summary", text(row, "description"));
      }
      var before = new LinkedHashMap<>(row);
      row.put("parse_status", "processing");
      setState(row, state);
      repository.checkpoint(before, row);
    }
    if (mode.equals("reuse_vectors")) {
      reuse(scope, row, source, target);
      cleanupWiki(scope, row, source, target);
      if (wiki(target)) effects.ingestWiki(scope.tenant(), scope.target(), id);
    } else if (mode.equals("reparse")) reparse(scope, row, source, target);
    else throw new IllegalArgumentException("unknown move mode: " + mode);
  }

  private void reuse(
      KnowledgeTransferRepository.Scope scope,
      Map<String, Object> row,
      JsonNode source,
      JsonNode target)
      throws Exception {
    if (!source
        .path("vector_store_id")
        .asText("")
        .equals(target.path("vector_store_id").asText("")))
      throw new IllegalArgumentException(
          "reuse_vectors move across different vector stores is not"
              + " supported (source KB "
              + scope.source()
              + ", target KB "
              + scope.target()
              + "); use reparse mode");
    require(scope, source, target);
    String id = text(row, "id");
    var chunks = repository.chunks(scope.tenant(), id, Set.of(scope.source(), scope.target()));
    if (!text(row, "embedding_model_id").isEmpty())
      effects.moveIndices(row, source, target, chunks.stream().map(c -> text(c, "id")).toList());
    effects.deleteGraph(scope.source(), id);
    try {
      repository.moveChunks(scope, id);
    } catch (Exception e) {
      throw wrap("failed to move chunks: ", e);
    }
    try {
      repository.clearTags(id);
    } catch (Exception e) {
      throw wrap("failed to clear knowledge tag relations: ", e);
    }
    var before = new LinkedHashMap<>(row);
    row.put("knowledge_base_id", scope.target());
    row.put("parse_status", "completed");
    row.put("error_message", "");
    phase(row, "done");
    repository.checkpoint(before, row);
  }

  private void reparse(
      KnowledgeTransferRepository.Scope scope,
      Map<String, Object> row,
      JsonNode source,
      JsonNode target)
      throws Exception {
    require(scope, source, target);
    var before = new LinkedHashMap<>(row);
    var cleanup = new LinkedHashMap<>(row);
    // Quota is reduced only when the destination checkpoint commits, never during cleanup.
    cleanup.put("storage_size", 0L);
    try {
      effects.cleanupResources(cleanup, source);
    } catch (Exception e) {
      throw wrap("failed to clean up source: ", e);
    }
    repository.clearTags(text(row, "id"));
    row.put("knowledge_base_id", scope.target());
    row.put("embedding_model_id", target.path("embedding_model_id").asText(""));
    row.put("parse_status", "pending");
    row.put("error_message", "");
    row.put("enable_status", "disabled");
    row.put("description", "");
    row.put("processed_at", null);
    row.put("storage_size", 0L);
    phase(row, "reparse_pending");
    repository.checkpoint(before, row);
    cleanupWiki(scope, row, source, target);
    enqueue(scope, row, source, target);
  }

  private void enqueue(
      KnowledgeTransferRepository.Scope scope,
      Map<String, Object> row,
      JsonNode source,
      JsonNode target)
      throws Exception {
    require(scope, source, target);
    if (text(row, "parse_status").equals("failed")) {
      var before = new LinkedHashMap<>(row);
      row.put("parse_status", "pending");
      row.put("error_message", "");
      repository.checkpoint(before, row);
    }
    String id = text(row, "id"), taskId = "move-reparse:" + scope.task() + ":" + id;
    boolean manual = text(row, "type").equals("manual");
    if (manual || !text(row, "file_path").isEmpty()) {
      ObjectNode payload = json.createObjectNode();
      for (String key :
          List.of(
              "lf_trace_id", "lf_parent_obs_id", "lf_traceparent", "lf_session_id", "lf_user_id"))
        if (!context.path(key).asText("").isEmpty()) payload.set(key, context.get(key));
      payload
          .put("request_id", manual ? context.path("request_id").asText("") : "")
          .put("tenant_id", number(row, "tenant_id"))
          .put("knowledge_id", id)
          .put("knowledge_base_id", scope.target());
      if (manual) payload.put("content", manual(row.get("metadata"))).put("need_cleanup", false);
      else {
        payload.put("file_path", text(row, "file_path"));
        String filename = text(row, "file_name");
        if (!filename.isEmpty()) payload.put("file_name", filename);
        String type =
            filename.contains(".") ? filename.substring(filename.lastIndexOf('.') + 1) : "unknown";
        if (!type.isEmpty()) payload.put("file_type", type);
        var vlm = target.path("vlm_config");
        var questions = target.path("question_generation_config");
        int count =
            questions.path("enabled").asBoolean() && questions.path("question_count").asInt() > 0
                ? questions.path("question_count").asInt()
                : 3;
        String language = context.path("language").asText("");
        payload
            .put(
                "enable_multimodel",
                (vlm.path("enabled").asBoolean() && !vlm.path("model_id").asText("").isEmpty())
                    || (!vlm.path("model_name").asText("").isEmpty()
                        && !vlm.path("base_url").asText("").isEmpty()))
            .put("enable_question_generation", questions.path("enabled").asBoolean())
            .put("question_count", count)
            .put("language", language.isEmpty() ? "zh-CN" : language);
      }
      try {
        effects.enqueue(
            new Submission(
                manual ? "manual:process" : "document:process",
                taskId,
                payload,
                "default",
                3,
                manual ? Duration.ofMinutes(30) : documentTimeout,
                Duration.ofDays(7)));
      } catch (AlreadyEnqueued accepted) {
        // Admission already happened; the acknowledgement below must still be retried.
      } catch (Exception e) {
        throw wrap("failed to enqueue " + (manual ? "manual" : "document") + " process task: ", e);
      }
    }
    acknowledge(scope, row, source, target);
  }

  private String manual(Object stored) throws Exception {
    // ManualMetadata returns nil for empty bytes; Go's nil %w formatting is part of its error.
    String raw = stored instanceof byte[] b ? GoUtf8.decode(b) : Objects.toString(stored, "");
    if (raw.isEmpty())
      throw new IllegalArgumentException("failed to get manual metadata for reparse: %!w(<nil>)");
    var shape =
        json.createObjectNode()
            .put("kind", "struct")
            .put("name", "ManualKnowledgeMetadata")
            .put("type", "types.ManualKnowledgeMetadata");
    var fields = shape.putArray("fields");
    for (String field : List.of("content", "format", "status", "version", "updated_at"))
      fields.add(
          json.createObjectNode()
              .put("name", field)
              .set(
                  "schema",
                  json.createObjectNode()
                      .put("kind", field.equals("version") ? "int64" : "string")
                      .put("type", field.equals("version") ? "int" : "string")));
    try {
      return new GoUploadJson(json).decode(raw, shape).path("content").asText("");
    } catch (Exception e) {
      throw wrap("failed to get manual metadata for reparse: ", e);
    }
  }

  private void acknowledge(
      KnowledgeTransferRepository.Scope scope,
      Map<String, Object> queued,
      JsonNode source,
      JsonNode target)
      throws Exception {
    var current = get(scope.tenant(), text(queued, "id"));
    if (text(current, "id").isEmpty()
        || number(current, "tenant_id") != scope.tenant()
        || !text(current, "knowledge_base_id").equals(scope.target()))
      throw new IllegalArgumentException("knowledge binding does not match the transfer scope");
    var state = metadata.read(current.get("metadata"));
    if (!matches(scope, state, text(queued, "id"), "reparse"))
      throw new IllegalArgumentException("kb_access: forbidden");
    if (state.path("phase").asText().equals("done")) return;
    var after = new LinkedHashMap<>(current);
    state.put("phase", "done");
    setState(after, state);
    repository.checkpoint(current, after);
  }

  /** Final failure never overwrites completed transfers or a parser that already started. */
  void markFailed(
      KnowledgeTransferRepository.Scope scope,
      JsonNode source,
      JsonNode target,
      String id,
      String mode,
      Exception failure) {
    try {
      require(scope, source, target);
      var row = repository.get(scope.tenant(), id);
      if (!text(row, "id").equals(id) || number(row, "tenant_id") != scope.tenant()) return;
      var state = metadata.read(row.get("metadata"));
      if (!matches(scope, state, id, mode)) return;
      boolean moving =
          text(row, "knowledge_base_id").equals(scope.source())
              && state.path("phase").asText().equals("moving")
              && text(row, "parse_status").equals("processing");
      boolean pending =
          text(row, "knowledge_base_id").equals(scope.target())
              && state.path("phase").asText().equals("reparse_pending")
              && text(row, "parse_status").equals("pending");
      if (!moving && !pending) return;
      var after = new LinkedHashMap<>(row);
      after.put("parse_status", "failed");
      after.put("error_message", failure.getMessage());
      repository.checkpoint(row, after);
    } catch (Exception ignored) {
      // Go's final-failure bookkeeping is best effort and preserves the original move error.
    }
  }

  private void cleanupWiki(
      KnowledgeTransferRepository.Scope scope,
      Map<String, Object> row,
      JsonNode source,
      JsonNode target)
      throws Exception {
    if (!wiki(source)) return;
    if (!text(row, "knowledge_base_id").equals(scope.target()))
      throw new IllegalArgumentException("kb_access: forbidden");
    var state = metadata.read(row.get("metadata"));
    if (state == null
        || !state.path("source_kb").asText().equals(scope.source())
        || !state.path("target_kb").asText().equals(scope.target()))
      throw new IllegalArgumentException("kb_access: forbidden");
    var old = new LinkedHashMap<>(row);
    old.put("knowledge_base_id", scope.source());
    old.put("description", state.path("wiki_summary").asText(""));
    var refs = new LinkedHashSet<String>();
    for (var id : state.path("wiki_chunk_ids")) refs.add(id.asText());
    effects.cleanupWiki(old, refs);
  }

  private void require(KnowledgeTransferRepository.Scope scope, JsonNode source, JsonNode target)
      throws Exception {
    if (source == null
        || target == null
        || !source.path("id").asText().equals(scope.source())
        || !target.path("id").asText().equals(scope.target())
        || source.path("tenant_id").asLong() != scope.tenant()
        || target.path("tenant_id").asLong() != scope.tenant())
      throw new IllegalArgumentException("kb_access: forbidden");
    effects.requireTransfer(scope, source, target);
  }

  private void phase(Map<String, Object> row, String value) throws Exception {
    var state = metadata.read(row.get("metadata"));
    if (state == null) throw new IllegalArgumentException("move state unavailable");
    state.put("phase", value);
    setState(row, state);
  }

  private void setState(Map<String, Object> row, ObjectNode state) throws Exception {
    row.put("metadata", metadata.write(row.get("metadata"), state));
  }

  private Map<String, Object> get(long tenant, String id) {
    try {
      return repository.get(tenant, id);
    } catch (IllegalArgumentException e) {
      if ("record not found".equals(e.getMessage()))
        throw new IllegalArgumentException("knowledge not found", e);
      throw e;
    }
  }

  private static boolean matches(
      KnowledgeTransferRepository.Scope scope, JsonNode state, String id, String mode) {
    return KnowledgeTransferMetadata.matches(
        state, scope.task(), scope.source(), scope.target(), id, mode);
  }

  private static boolean wiki(JsonNode kb) {
    return kb.path("indexing_strategy").path("wiki_enabled").asBoolean();
  }

  private static Exception wrap(String prefix, Exception cause) {
    return new IllegalStateException(prefix + Objects.toString(cause.getMessage(), ""), cause);
  }
}
