package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.*;

/** Existing Go rows; HTTP admission is intentionally not atomic with queue acceptance. */
public final class ManualKnowledgeRepository {
  private final JdbcTemplate jdbc;
  private final KnowledgeRepository knowledge;
  private final ObjectMapper json;
  private Boolean postgres;

  public ManualKnowledgeRepository(
      JdbcTemplate jdbc, KnowledgeRepository knowledge, ObjectMapper json) {
    this.jdbc = jdbc;
    this.knowledge = knowledge;
    this.json = json;
  }

  public ObjectNode lookup(String id) {
    var rows =
        jdbc.queryForList("SELECT tenant_id FROM knowledges WHERE id=? AND deleted_at IS NULL", id);
    if (rows.isEmpty())
      throw new ApiException(404, "NOT_FOUND", "Knowledge not found").withAppCode(1003);
    var result =
        knowledge.get(((Number) rows.getFirst().get("tenant_id")).longValue(), id).orElseThrow();
    for (String key : List.of("file_size", "storage_size", "pending_subtasks_count"))
      result.put(key, result.path(key).asLong(0));
    return result;
  }

  ObjectNode blank() {
    return knowledge.blank();
  }

  void create(long owner, ObjectNode row, List<String> tags) {
    knowledge.create(owner, row);
    knowledge.assignTags(owner, row.path("knowledge_base_id").asText(), row, tags);
  }

  void update(long owner, ObjectNode row) {
    var fields =
        List.of(
            "type",
            "title",
            "description",
            "source",
            "file_name",
            "file_type",
            "enable_status",
            "embedding_model_id",
            "parse_status",
            "metadata",
            "processed_at",
            "updated_at",
            "error_message");
    var values = new ArrayList<Object>();
    var sets = new ArrayList<String>();
    for (String key : fields) {
      JsonNode value = row.get(key);
      sets.add(key + "=" + (key.equals("metadata") && postgres() ? "?::jsonb" : "?"));
      values.add(
          value == null || value.isNull()
              ? null
              : key.equals("metadata")
                  ? value.toString()
                  : key.endsWith("_at") ? time(Instant.parse(value.asText())) : value.asText());
    }
    values.add(owner);
    values.add(row.path("id").asText());
    if (jdbc.update(
            "UPDATE knowledges SET "
                + String.join(",", sets)
                + " WHERE tenant_id=? AND id=? AND deleted_at IS NULL",
            values.toArray())
        != 1) throw new ApiException(404, "NOT_FOUND", "Knowledge not found").withAppCode(1003);
  }

  void failed(long owner, ObjectNode row, Instant now) {
    knowledge.markEnqueueFailed(owner, row, now);
  }

  void audit(
      AuthContext caller,
      ObjectNode row,
      boolean update,
      String status,
      String outcome,
      String task) {
    try {
      var details =
          json.createObjectNode().put("title", row.path("title").asText()).put("status", status);
      if (!update) details.put("source_type", "manual");
      if (outcome.equals("accepted"))
        details
            .put("processing_status", "pending")
            .put("task_id", task)
            .put(
                "trigger",
                caller.userId().isEmpty() || caller.userId().matches("system-[0-9]+")
                    ? "system"
                    : "user");
      if (outcome.equals("failed"))
        details.put("processing_status", "failed").put("failure_stage", "enqueue");
      if (caller.isApiKey() && caller.apiKeyId() != null && caller.apiKeyId() > 0) {
        details.put("api_key_id", caller.apiKeyId());
        var names =
            jdbc.queryForList("SELECT name FROM tenant_api_keys WHERE id=?", caller.apiKeyId());
        if (!names.isEmpty()
            && names.getFirst().get("name") != null
            && !names.getFirst().get("name").toString().isEmpty())
          details.put("api_key_name", names.getFirst().get("name").toString());
      }
      jdbc.update(
          "INSERT INTO"
              + " audit_logs(tenant_id,actor_user_id,actor_role,action,target_type,target_id,scope_type,scope_id,outcome,details)"
              + " VALUES(?,?,?,?,'knowledge',?,'knowledge_base',?,?,"
              + (postgres() ? "?::jsonb" : "?")
              + ")",
          row.path("tenant_id").asLong(),
          caller.userId(),
          caller.role(),
          update ? "knowledge.updated" : "knowledge.created",
          row.path("id").asText(),
          row.path("knowledge_base_id").asText(),
          outcome,
          details.toString());
    } catch (Exception error) {
      org.slf4j.LoggerFactory.getLogger(getClass())
          .warn("Manual knowledge activity failed after mutation");
    }
  }

  private boolean postgres() {
    if (postgres == null)
      postgres =
          jdbc.execute(
              (ConnectionCallback<Boolean>)
                  c -> c.getMetaData().getDatabaseProductName().equals("PostgreSQL"));
    return Boolean.TRUE.equals(postgres);
  }

  private Object time(Instant instant) {
    return postgres() ? Timestamp.from(instant) : GoSqliteTimestamp.write(instant);
  }
}
