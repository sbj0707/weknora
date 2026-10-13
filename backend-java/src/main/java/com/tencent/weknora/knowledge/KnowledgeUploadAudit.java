package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.weknora.security.AuthContext;
import java.util.*;
import org.springframework.jdbc.core.*;

/** Owner-scoped business activity with the original caller retained for attribution. */
public final class KnowledgeUploadAudit {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public KnowledgeUploadAudit(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public void record(
      AuthContext caller,
      KnowledgeFileUpload.Command command,
      ObjectNode row,
      String taskId,
      boolean accepted) {
    try {
      var details =
          json.createObjectNode()
              .put("title", row.path("title").asText())
              .put("source_type", "file")
              .put("file_type", row.path("file_type").asText())
              .put("processing_status", accepted ? "pending" : "failed");
      String actor = caller.userId();
      boolean synthetic = actor.isEmpty() || actor.matches("system-[0-9]+");
      if (accepted) details.put("task_id", taskId).put("trigger", synthetic ? "system" : "user");
      else details.put("failure_stage", "enqueue");
      if (caller.isApiKey()) {
        if (caller.apiKeyId() != null) details.put("api_key_id", caller.apiKeyId());
        var names =
            jdbc.queryForList("SELECT name FROM tenant_api_keys WHERE id=?", caller.apiKeyId());
        if (!names.isEmpty()
            && names.getFirst().get("name") != null
            && !names.getFirst().get("name").toString().isEmpty())
          details.put("api_key_name", names.getFirst().get("name").toString());
      }
      boolean postgres =
          Boolean.TRUE.equals(
              jdbc.execute(
                  (ConnectionCallback<Boolean>)
                      c -> c.getMetaData().getDatabaseProductName().equals("PostgreSQL")));
      jdbc.update(
          "INSERT INTO"
              + " audit_logs(tenant_id,actor_user_id,actor_role,action,target_type,target_id,scope_type,scope_id,outcome,details)"
              + " VALUES(?,?,?,'knowledge.created','knowledge',?,'knowledge_base',?,?,"
              + (postgres ? "CAST(? AS JSONB)" : "?")
              + ")",
          command.tenant(),
          synthetic ? "" : actor,
          synthetic ? "" : caller.role(),
          row.path("id").asText(),
          command.kb(),
          accepted ? "accepted" : "failed",
          details.toString());
    } catch (Exception error) {
      org.slf4j.LoggerFactory.getLogger(getClass())
          .warn("Knowledge upload audit write failed after business mutation");
    }
  }
}
