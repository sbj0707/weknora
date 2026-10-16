package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.weknora.security.*;
import com.tencent.weknora.task.NamedTaskProducer;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Public admission validates all IDs before queue submission, and leaves document state untouched.
 */
public final class KnowledgeMoveAdmission {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final KnowledgeBaseCodec bases;
  private final KnowledgeBaseWriteAuthorization writes;
  private final NamedTaskProducer tasks;
  private final KnowledgeMoveProgress progress;
  private final Clock clock;

  public KnowledgeMoveAdmission(
      JdbcTemplate jdbc,
      ObjectMapper json,
      KnowledgeBaseCodec bases,
      KnowledgeBaseWriteAuthorization writes,
      NamedTaskProducer tasks,
      KnowledgeMoveProgress progress,
      Clock clock) {
    this.jdbc = jdbc;
    this.json = json;
    this.bases = bases;
    this.writes = writes;
    this.tasks = tasks;
    this.progress = progress;
    this.clock = clock;
  }

  ObjectNode submit(AuthContext caller, KnowledgeMoveRequest form, String path) {
    if (form.source().equals(form.target()))
      throw KnowledgeMoveRequest.bad("Source and target knowledge base cannot be the same");
    if (caller.tenantId() == 0)
      throw new ApiException(401, "UNAUTHORIZED", "Unauthorized").withAppCode(1001);
    if (!caller.allowsKnowledgeBase(form.source()) || !caller.allowsKnowledgeBase(form.target()))
      throw forbidden("API key scope does not allow one or more knowledge bases");
    var source = base(caller, form.source(), "source");
    var target = base(caller, form.target(), "target");
    writes.authorizeMove(caller, form.source(), path);
    writes.authorizeMove(caller, form.target(), path);
    var tenants =
        jdbc.queryForList(
            "SELECT * FROM tenants WHERE id=? AND deleted_at IS NULL", caller.tenantId());
    JsonNode tenant =
        tenants.isEmpty() ? json.nullNode() : KnowledgeMoveJson.tenant(json, tenants.getFirst());
    try {
      KnowledgeMoveCompatibility.validate(source, target, form.mode(), tenant);
    } catch (IllegalArgumentException error) {
      throw KnowledgeMoveRequest.bad(error.getMessage());
    }
    var ids = new LinkedHashSet<String>();
    for (String id : form.ids()) {
      if (KnowledgeUploadForms.goTrim(id).isEmpty())
        throw KnowledgeMoveRequest.bad("Knowledge ID cannot be empty");
      ids.add(id);
    }
    for (String id : ids) {
      var rows =
          jdbc.queryForList(
              "SELECT * FROM knowledges WHERE tenant_id=? AND id=? AND deleted_at IS NULL",
              caller.tenantId(),
              id);
      if (rows.isEmpty()) throw KnowledgeMoveRequest.bad("Knowledge item " + id + " not found");
      var row = rows.getFirst();
      if (!Objects.toString(row.get("knowledge_base_id"), "").equals(form.source()))
        throw KnowledgeMoveRequest.bad(
            "Knowledge item " + id + " does not belong to the source knowledge base");
      String status = Objects.toString(row.get("parse_status"), "");
      if (!status.equals("completed"))
        throw KnowledgeMoveRequest.bad(
            "Knowledge item " + id + " is not in completed status (current: " + status + ")");
    }
    String task = KnowledgeMoveTaskIds.generate(caller.tenantId(), form.source(), clock);
    var payload =
        json.createObjectNode()
            .put("tenant_id", caller.tenantId())
            .put("task_id", task)
            .put("source_kb_id", form.source())
            .put("target_kb_id", form.target())
            .put("mode", form.mode());
    payload.set("knowledge_ids", json.valueToTree(ids));
    var initiator = payload.putObject("initiator");
    if (!caller.userId().isEmpty() && !caller.userId().matches("system-[0-9]+"))
      initiator.put("user_id", caller.userId()).put("role", caller.role());
    if (caller.apiKeyId() != null && caller.apiKeyId() > 0) {
      initiator.put("api_key_id", caller.apiKeyId());
      var keys =
          jdbc.queryForList("SELECT name FROM tenant_api_keys WHERE id=?", caller.apiKeyId());
      if (!keys.isEmpty() && !Objects.toString(keys.getFirst().get("name"), "").isEmpty())
        initiator.put("api_key_name", keys.getFirst().get("name").toString());
    }
    try {
      tasks.enqueue(
          caller.tenantId(),
          "knowledge:move",
          task,
          payload,
          "low",
          3,
          Duration.ofHours(2),
          Duration.ZERO);
    } catch (Exception error) {
      throw new ApiException(500, "INTERNAL_SERVER_ERROR", "Failed to enqueue task")
          .withAppCode(1007);
    }
    var initial =
        KnowledgeMoveJson.progress(
            json,
            json.createObjectNode()
                .put("task_id", task)
                .put("source_kb_id", form.source())
                .put("target_kb_id", form.target())
                .put("status", "pending")
                .put("total", ids.size())
                .put("message", "Task queued, waiting to start...")
                .put("created_at", clock.instant().getEpochSecond())
                .put("updated_at", clock.instant().getEpochSecond()));
    try {
      progress.initialize(initial);
    } catch (RuntimeException ignored) {
    }
    return json.createObjectNode()
        .put("task_id", task)
        .put("source_kb_id", form.source())
        .put("target_kb_id", form.target())
        .put("knowledge_count", ids.size())
        .put("message", "Knowledge move task started");
  }

  private ObjectNode base(AuthContext caller, String id, String label) {
    var rows =
        jdbc.queryForList("SELECT * FROM knowledge_bases WHERE id=? AND deleted_at IS NULL", id);
    if (rows.isEmpty())
      throw new ApiException(
              404,
              "NOT_FOUND",
              Character.toUpperCase(label.charAt(0))
                  + label.substring(1)
                  + " knowledge base not found")
          .withAppCode(1003);
    var row = rows.getFirst();
    if (((Number) row.get("tenant_id")).longValue() != caller.tenantId())
      throw forbidden("No permission to access " + label + " knowledge base");
    if (!caller.isApiKey()
        && !caller.isAtLeast("admin")
        && !Objects.toString(row.get("creator_id"), "").equals(caller.userId()))
      throw forbidden("No permission to operate on this knowledge base");
    return bases.fromRow(row);
  }

  private static ApiException forbidden(String message) {
    return new ApiException(403, "FORBIDDEN", message).withAppCode(1002);
  }
}
