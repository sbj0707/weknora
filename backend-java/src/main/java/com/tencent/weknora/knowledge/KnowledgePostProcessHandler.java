package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.task.*;
import java.time.Duration;

/** Go's zero-enrichment handoff, guarded against duplicate delivery and cancellation. */
public final class KnowledgePostProcessHandler implements TaskHandler {
  private final DocumentProcessingRepository repository;
  private final KnowledgeProcessConfig process;
  private final ObjectMapper json;

  public KnowledgePostProcessHandler(DocumentProcessingRepository repository, ObjectMapper json) {
    this.repository = repository;
    this.json = json;
    process = new KnowledgeProcessConfig(json);
  }

  @Override
  public String type() {
    return "knowledge:post_process";
  }

  @Override
  public Duration timeout() {
    return Duration.ofMinutes(30);
  }

  @Override
  public JsonNode execute(TaskRecord job, TaskCancellation cancellation) throws Exception {
    cancellation.check();
    var loaded = repository.load(job);
    if (loaded.isEmpty()) return json.createObjectNode().put("skipped", "knowledge_not_found");
    var snapshot = loaded.get();
    if (!snapshot.knowledge().path("parse_status").asText().equals("processing"))
      return json.createObjectNode().put("skipped", "non_processing_status");
    JsonNode override = snapshot.knowledge().path("metadata").path("process_overrides");
    var effective =
        process.resolve(snapshot.knowledgeBase(), override.isMissingNode() ? null : override);
    try {
      DocumentProcessingHandler.requireSupportedEnrichment(
          snapshot.knowledgeBase(), effective, true);
    } catch (TaskFailure failure) {
      if (!failure.retryable()) repository.failed(job, snapshot, failure.getMessage());
      throw failure;
    }
    return json.createObjectNode().put("completed", repository.complete(job, snapshot));
  }
}
