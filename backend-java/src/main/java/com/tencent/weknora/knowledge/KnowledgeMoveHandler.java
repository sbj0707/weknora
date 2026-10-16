package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.weknora.task.*;
import java.io.IOException;
import java.time.*;
import java.util.*;

/**
 * Whole-batch validation precedes every claim, including failure bookkeeping on the final retry.
 */
public final class KnowledgeMoveHandler implements TaskHandler {
  interface Runtime {
    default String raw(TaskRecord job) {
      return job.payload().toString();
    }

    JsonNode base(String id) throws Exception;

    JsonNode tenant(long id) throws Exception;

    void validateEngine(long tenant, JsonNode source) throws Exception;

    KnowledgeMoveExecution.Effects effects(TaskRecord job, ObjectNode payload, Runnable guard);

    void activity(ObjectNode payload, String kb, String action, String outcome, int count);

    void profile(long owner, JsonNode base, ObjectNode payload);
  }

  private final KnowledgeTransferRepository repository;
  private final KnowledgeMoveProgress progress;
  private final ObjectMapper json;
  private final Runtime runtime;
  private final Clock clock;
  private final Duration documentTimeout;
  private final KnowledgeProcessingLanguage language;

  KnowledgeMoveHandler(
      KnowledgeTransferRepository repository,
      KnowledgeMoveProgress progress,
      ObjectMapper json,
      Runtime runtime,
      Clock clock,
      Duration documentTimeout,
      KnowledgeProcessingLanguage language) {
    this.repository = repository;
    this.progress = progress;
    this.json = json;
    this.runtime = runtime;
    this.clock = clock;
    this.documentTimeout = documentTimeout;
    this.language = language;
  }

  public String type() {
    return "knowledge:move";
  }

  public Duration timeout() {
    return Duration.ofHours(2);
  }

  @Override
  public JsonNode execute(TaskRecord job, TaskCancellation cancellation) throws Exception {
    return execute(job, runtime.raw(job), cancellation::check);
  }

  /** Original persisted JSON is supplied by the native adapter when available. */
  JsonNode execute(TaskRecord job, String raw, Runnable guard) throws Exception {
    ObjectNode payload;
    try {
      payload = KnowledgeMoveJson.payload(raw, json);
    } catch (IOException error) {
      throw terminal("invalid move payload: " + error.getMessage());
    }
    long owner = payload.path("tenant_id").asLong();
    String task = payload.path("task_id").asText(""),
        sourceId = payload.path("source_kb_id").asText(""),
        targetId = payload.path("target_kb_id").asText(""),
        mode = payload.path("mode").asText("");
    if (owner <= 0 || task.isEmpty() || sourceId.isEmpty() || targetId.isEmpty())
      throw terminal("invalid move scope");
    if (owner != job.tenantId())
      throw terminal("invalid move scope: queue tenant does not match payload tenant");
    guard.run();
    JsonNode source = runtime.base(sourceId), target = runtime.base(targetId);
    if (source == null
        || target == null
        || !source.path("id").asText().equals(sourceId)
        || !target.path("id").asText().equals(targetId)) throw terminal("invalid move binding");
    if (sourceId.equals(targetId))
      throw terminal("invalid move scope: source and target knowledge bases must differ");
    if (source.path("tenant_id").asLong() != owner || target.path("tenant_id").asLong() != owner)
      throw terminal("invalid move scope: kb_access: forbidden");
    var scope = new KnowledgeTransferRepository.Scope(owner, task, sourceId, targetId);
    JsonNode tenant = runtime.tenant(owner);
    if (tenant == null || tenant.path("id").asLong() != owner)
      throw terminal("invalid move tenant");
    var ids = new ArrayList<String>();
    for (JsonNode value : payload.path("knowledge_ids")) ids.add(value.asText(""));
    boolean last = job.attempts() >= job.maxAttempts();
    List<Map<String, Object>> items;
    try {
      guard.run();
      items =
          repository.plan(
              scope,
              source,
              target,
              tenant,
              ids,
              mode,
              () -> runtime.validateEngine(owner, source));
    } catch (Exception failure) {
      save(
          record(task, sourceId, targetId)
              .put("status", last ? "failed" : "processing")
              .put("error", diagnostic(failure))
              .put("message", "Move preflight failed"));
      throw retry(failure);
    }
    var current =
        record(task, sourceId, targetId).put("status", "processing").put("total", items.size());
    if (job.attempts() <= 1)
      activity(payload, sourceId, targetId, "knowledge.move_started", "accepted", items.size());
    var context = payload.deepCopy().put("language", language.resolve((String) null));
    var executor =
        new KnowledgeMoveExecution(
            repository, json, runtime.effects(job, payload, guard), context, documentTimeout);
    var failures = new ArrayList<String>();
    int processed = 0, failed = 0;
    for (var item : items) {
      String id = KnowledgeTransferRepository.text(item, "id");
      try {
        guard.run();
        executor.move(scope, source, target, tenant, id, mode);
      } catch (Exception failure) {
        failures.add("knowledge " + id + ": " + diagnostic(failure));
        if (last) executor.markFailed(scope, source, target, id, mode, failure);
        failed++;
      }
      processed++;
      current
          .put("processed", processed)
          .put("failed", failed)
          .put("progress", processed * 100 / items.size())
          .put(
              "message", "Moved " + (processed - failed) + "/" + items.size() + " knowledge items");
      save(current);
    }
    if (!failures.isEmpty()) {
      current.put("error", String.join("\n", failures));
      if (last) {
        current.put("status", "failed");
        activity(
            payload,
            sourceId,
            targetId,
            "knowledge.move_failed",
            failed < items.size() ? "partial" : "failed",
            items.size());
      }
      save(current);
      throw new TaskFailure(String.join("\n", failures), true);
    }
    current.put("status", "completed").put("progress", 100).put("error", "");
    save(current);
    activity(payload, sourceId, targetId, "knowledge.move_completed", "success", items.size());
    for (JsonNode base : List.of(source, target))
      try {
        runtime.profile(owner, base, payload);
      } catch (RuntimeException ignored) {
      }
    return current;
  }

  private ObjectNode record(String task, String source, String target) {
    return KnowledgeMoveJson.progress(
        json,
        json.createObjectNode()
            .put("task_id", task)
            .put("source_kb_id", source)
            .put("target_kb_id", target)
            .put("updated_at", clock.instant().getEpochSecond()));
  }

  private void save(ObjectNode value) {
    value.put("updated_at", clock.instant().getEpochSecond());
    try {
      progress.save(value);
    } catch (RuntimeException ignored) {
    }
  }

  private void activity(
      ObjectNode payload, String source, String target, String action, String outcome, int count) {
    for (String kb : List.of(source, target))
      try {
        runtime.activity(payload, kb, action, outcome, count);
      } catch (RuntimeException ignored) {
      }
  }

  static String diagnostic(Exception error) {
    return error instanceof org.springframework.dao.DataAccessException
        ? ChunkEditService.diagnostic(error)
        : Objects.toString(error.getMessage(), "");
  }

  private static TaskFailure retry(Exception error) {
    return new TaskFailure(diagnostic(error), true);
  }

  private static TaskFailure terminal(String message) {
    return new TaskFailure(message + ": skip retry for the task", false);
  }
}
