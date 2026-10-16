package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.google.re2j.Pattern;
import com.tencent.weknora.security.*;
import com.tencent.weknora.storage.ResourceCatalog;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Manual HTTP admission; drafts own referenced resources without indexing them. */
public final class ManualKnowledgeService {
  private static final Pattern REFERENCES = Pattern.compile("resource://[A-Za-z0-9_-]{22}");
  private final ManualKnowledgeRepository repository;
  private final ObjectMapper json;
  private final KnowledgeProcessConfig process;
  private final KnowledgeFileUpload.Enqueuer tasks;
  private final ResourceCatalog resources;
  private final Clock clock;

  public ManualKnowledgeService(
      ManualKnowledgeRepository repository,
      ObjectMapper json,
      KnowledgeFileUpload.Enqueuer tasks,
      ResourceCatalog resources) {
    this(repository, json, tasks, resources, Clock.systemUTC());
  }

  ManualKnowledgeService(
      ManualKnowledgeRepository repository,
      ObjectMapper json,
      KnowledgeFileUpload.Enqueuer tasks,
      ResourceCatalog resources,
      Clock clock) {
    this.repository = repository;
    this.json = json;
    this.process = new KnowledgeProcessConfig(json);
    this.tasks = tasks;
    this.resources = resources;
    this.clock = clock;
  }

  public ObjectNode create(
      AuthContext caller,
      KnowledgeBaseWriteAuthorization.WriteAccess grant,
      ManualKnowledgeRequest request,
      String requestId) {
    String content = ManualKnowledgeText.content(request.content()),
        title = ManualKnowledgeText.title(request.title()),
        status = ManualKnowledgeText.status(request.status());
    Instant now = clock.instant();
    if (title.isEmpty())
      title =
          "Knowledge-"
              + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                  .withZone(clock.getZone())
                  .format(now);
    var row = repository.blank();
    row.put("id", UUID.randomUUID().toString())
        .put("tenant_id", grant.effectiveTenantId())
        .put("knowledge_base_id", grant.knowledgeBaseId())
        .put("type", "manual")
        .put("channel", request.channel().isEmpty() ? "web" : request.channel())
        .put("title", title)
        .put("source", "manual")
        .put("parse_status", status.equals("publish") ? "pending" : "draft")
        .put("enable_status", "disabled")
        .put("embedding_model_id", grant.knowledgeBase().path("embedding_model_id").asText())
        .put("file_name", ManualKnowledgeText.filename(title))
        .put("file_type", "manual")
        .put("created_at", now.toString())
        .put("updated_at", now.toString());
    row.set("metadata", metadata(content, status, 1));
    applyOverrides(grant.knowledgeBase(), row, request, status);
    repository.create(grant.effectiveTenantId(), row, request.tags());
    bind(row, content);
    if (status.equals("publish")) enqueue(caller, row, content, false, requestId);
    else repository.audit(caller, row, false, status, "success", "");
    return row;
  }

  public ObjectNode update(
      AuthContext caller,
      KnowledgeBaseWriteAuthorization.WriteAccess grant,
      ObjectNode existing,
      ManualKnowledgeRequest request,
      String requestId) {
    String content = ManualKnowledgeText.content(request.content()),
        title = ManualKnowledgeText.title(request.title()),
        status = ManualKnowledgeText.status(request.status());
    if (!existing.path("type").asText().equals("manual"))
      throw ManualKnowledgeRequest.bad("仅支持手工知识的在线编辑");
    var old = existing.path("metadata");
    if (!old.isNull() && !old.isMissingNode() && !old.isObject())
      throw new ApiException(
              500,
              "INTERNAL_SERVER_ERROR",
              "json: cannot unmarshal "
                  + (old.isArray()
                      ? "array"
                      : old.isTextual() ? "string" : old.isBoolean() ? "bool" : "number")
                  + " into Go value of type map[string]json.RawMessage")
          .withAppCode(1007);
    long version = old.isNull() ? 2 : 1;
    if (old.isObject()) {
      boolean valid = true;
      for (String key : List.of("content", "format", "status", "updated_at"))
        if (old.hasNonNull(key) && !old.get(key).isTextual()) valid = false;
      JsonNode n = old.get("version");
      if (n != null && !n.isNull() && (!n.isIntegralNumber() || !n.canConvertToLong()))
        valid = false;
      if (valid) version = Math.max(old.path("version").asLong(0), 1) + 1;
    }
    var meta = metadata(content, status, version);
    if (old.has("_knowledge_transfer"))
      meta.set("_knowledge_transfer", old.get("_knowledge_transfer").deepCopy());
    existing.set("metadata", meta);
    if (!title.isEmpty()) existing.put("title", title);
    else if (existing.path("title").asText().isEmpty())
      existing.put(
          "title",
          "手工知识-"
              + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                  .withZone(clock.getZone())
                  .format(clock.instant()));
    existing
        .put("type", "manual")
        .put("source", "manual")
        .put("file_name", ManualKnowledgeText.filename(existing.path("title").asText()))
        .put("file_type", "manual")
        .put("enable_status", "disabled")
        .put("embedding_model_id", grant.knowledgeBase().path("embedding_model_id").asText())
        .put("parse_status", status.equals("publish") ? "pending" : "draft")
        .put("description", "")
        .put("updated_at", clock.instant().toString())
        .putNull("processed_at");
    applyOverrides(grant.knowledgeBase(), existing, request, status);
    repository.update(grant.effectiveTenantId(), existing);
    if (status.equals("publish")) enqueue(caller, existing, content, true, requestId);
    else {
      bind(existing, content);
      repository.audit(caller, existing, true, status, "success", "");
    }
    return existing;
  }

  private ObjectNode metadata(String content, String status, long version) {
    return json.createObjectNode()
        .put("content", content)
        .put("format", "markdown")
        .put("status", status)
        .put("version", version <= 0 ? 1 : version)
        .put(
            "updated_at",
            clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString());
  }

  private void applyOverrides(
      JsonNode kb, ObjectNode row, ManualKnowledgeRequest request, String status) {
    if (!status.equals("publish") || request.overrides() == null || request.overrides().isNull())
      return;
    process.validateProcessOverrides(kb, request.overrides(), List.of());
    ((ObjectNode) row.get("metadata"))
        .set("process_overrides", process.normalizeOverrides(request.overrides()));
  }

  private void enqueue(
      AuthContext caller, ObjectNode row, String content, boolean update, String requestId) {
    var payload =
        json.createObjectNode()
            .put("request_id", requestId)
            .put("tenant_id", row.path("tenant_id").asLong())
            .put("knowledge_id", row.path("id").asText())
            .put("knowledge_base_id", row.path("knowledge_base_id").asText())
            .put("content", content)
            .put("need_cleanup", update);
    String id;
    try {
      id =
          tasks.enqueue(
              row.path("tenant_id").asLong(),
              "manual:process",
              payload,
              new KnowledgeFileUpload.TaskOptions("default", 3, Duration.ofMinutes(30)));
    } catch (Exception failure) {
      repository.failed(row.path("tenant_id").asLong(), row, clock.instant());
      repository.audit(caller, row, update, "publish", "failed", "");
      if (update)
        throw new ApiException(500, "INTERNAL_SERVER_ERROR", "Failed to submit processing task")
            .withAppCode(1007);
      return;
    }
    repository.audit(caller, row, update, "publish", "accepted", id);
  }

  void bind(ObjectNode row, String content) {
    if (resources == null) return;
    var matcher = REFERENCES.matcher(content);
    var seen = new HashSet<String>();
    while (matcher.find())
      if ((matcher.end() == content.length() || !handleChar(content.charAt(matcher.end())))
          && seen.add(matcher.group()))
        try {
          resources.bind(
              row.path("tenant_id").asLong(),
              matcher.group(),
              "knowledge",
              row.path("id").asText(),
              "attachment");
        } catch (RuntimeException ignored) {
          org.slf4j.LoggerFactory.getLogger(getClass())
              .debug("Manual body contains unavailable resource");
        }
  }

  private static boolean handleChar(char c) {
    return c >= 'a' && c <= 'z'
        || c >= 'A' && c <= 'Z'
        || c >= '0' && c <= '9'
        || c == '_'
        || c == '-';
  }
}
