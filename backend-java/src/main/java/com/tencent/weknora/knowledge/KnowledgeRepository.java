package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.security.ApiException;
import java.io.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Existing Go knowledge/tag tables; all public database operations require the authorized owner
 * tenant.
 */
public class KnowledgeRepository {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final KnowledgeBaseCodec kbCodec;
  private final TransactionTemplate transactions;
  private final ObjectNode defaults;
  private Boolean postgres;

  public KnowledgeRepository(
      JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager manager) throws IOException {
    this.jdbc = jdbc;
    this.json = json;
    kbCodec = new KnowledgeBaseCodec(json);
    transactions = new TransactionTemplate(manager);
    try (var input = KnowledgeRepository.class.getResourceAsStream("/knowledge-default.json")) {
      if (input == null) throw new IllegalStateException("Missing knowledge defaults");
      defaults = (ObjectNode) json.readTree(input);
    }
  }

  public ObjectNode blank() {
    return defaults.deepCopy();
  }

  public ObjectNode knowledgeBase(long tenant, String id) {
    return kbCodec.fromRow(
        jdbc
            .queryForList(
                "SELECT * FROM knowledge_bases WHERE tenant_id=? AND id=? AND deleted_at IS NULL",
                tenant,
                id)
            .stream()
            .findFirst()
            .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "knowledge base not found")));
  }

  public Optional<ObjectNode> get(long tenant, String id) {
    var rows =
        jdbc.queryForList(
            "SELECT * FROM knowledges WHERE tenant_id=? AND id=? AND deleted_at IS NULL",
            tenant,
            id);
    return rows.isEmpty() ? Optional.empty() : Optional.of(project(rows.getFirst()));
  }

  public record DuplicateCheck(
      String name, String type, long size, String hash, String datasource, String external) {}

  public Optional<ObjectNode> duplicateFile(long tenant, String kb, DuplicateCheck check) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT * FROM knowledges WHERE tenant_id=? AND knowledge_base_id=? AND deleted_at IS"
                + " NULL AND parse_status NOT IN ('failed','deleting') AND type='file'");
    List<Object> values = new ArrayList<>(List.of(tenant, kb));
    if (!check.datasource().isEmpty() && !check.external().isEmpty()) {
      sql.append(" AND metadata->>'datasource_id'=? AND metadata->>'external_id'=?");
      values.add(check.datasource());
      values.add(check.external());
    }
    if (!check.hash().isEmpty()) {
      sql.append(" AND file_hash=?");
      values.add(check.hash());
    } else if (!check.name().isEmpty() && check.size() > 0) {
      sql.append(" AND file_name=? AND file_size=?");
      values.add(check.name());
      values.add(check.size());
    } else return Optional.empty();
    if (!check.type().isEmpty()) {
      sql.append(" AND LOWER(file_type)=?");
      values.add(check.type().toLowerCase(Locale.ROOT));
    }
    sql.append(" ORDER BY id LIMIT 1");
    var rows = jdbc.queryForList(sql.toString(), values.toArray());
    return rows.isEmpty() ? Optional.empty() : Optional.of(project(rows.getFirst()));
  }

  public void touchDuplicate(long tenant, String id, Instant now) {
    if (jdbc.update(
            "UPDATE knowledges SET created_at=?,updated_at=? WHERE tenant_id=? AND id=? AND"
                + " deleted_at IS NULL",
            timestamp(now),
            timestamp(now),
            tenant,
            id)
        != 1) throw new IllegalStateException("knowledge no longer exists");
  }

  /** Persists only a prepared new domain row; resource writes happen before this call. */
  public void create(long tenant, ObjectNode record) {
    if (tenant <= 0
        || record.path("tenant_id").asLong() != tenant
        || record.path("id").asText().isEmpty())
      throw new IllegalArgumentException("knowledge owner required");
    if (record.path("custom_metadata").isNull() || record.path("custom_metadata").isMissingNode())
      record.set("custom_metadata", json.createObjectNode());
    if (record.path("summary_status").asText().isEmpty()) record.put("summary_status", "none");
    if (record.path("channel").asText().isEmpty()) record.put("channel", "web");
    List<String> fields =
        List.of(
            "id",
            "tenant_id",
            "knowledge_base_id",
            "type",
            "title",
            "description",
            "source",
            "channel",
            "parse_status",
            "pending_subtasks_count",
            "summary_status",
            "enable_status",
            "embedding_model_id",
            "file_name",
            "folder_path",
            "file_type",
            "file_size",
            "file_hash",
            "file_path",
            "storage_size",
            "metadata",
            "custom_metadata",
            "last_faq_import_result",
            "created_at",
            "updated_at",
            "processed_at",
            "error_message");
    List<Object> values = new ArrayList<>();
    List<String> placeholders = new ArrayList<>();
    for (String field : fields) {
      JsonNode value = record.path(field);
      boolean jsonField =
          Set.of("metadata", "custom_metadata", "last_faq_import_result").contains(field);
      placeholders.add(jsonField && isPostgres() ? "?::json" : "?");
      if (value.isNull() || value.isMissingNode()) values.add(null);
      else if (jsonField) values.add(value.toString());
      else if (field.endsWith("_at")) values.add(timestamp(Instant.parse(value.asText())));
      else if (value.isNumber()) values.add(value.longValue());
      else values.add(value.asText());
    }
    jdbc.update(
        "INSERT INTO knowledges("
            + String.join(",", fields)
            + ") VALUES("
            + String.join(",", placeholders)
            + ")",
        values.toArray());
  }

  public void markEnqueueFailed(long tenant, ObjectNode record, Instant now) {
    record
        .put("parse_status", "failed")
        .put("error_message", "Failed to enqueue processing task")
        .put("updated_at", now.toString());
    jdbc.update(
        "UPDATE knowledges SET parse_status='failed',error_message=?,updated_at=? WHERE tenant_id=?"
            + " AND id=? AND deleted_at IS NULL",
        record.path("error_message").asText(),
        timestamp(now),
        tenant,
        record.path("id").asText());
  }

  public void assignTags(long tenant, String kb, ObjectNode record, List<String> requested) {
    String id = record.path("id").asText();
    var authoritative =
        get(tenant, id)
            .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "knowledge not found"));
    if (!authoritative.path("knowledge_base_id").asText().equals(kb))
      throw new ApiException(403, "FORBIDDEN", "knowledge base mismatch");
    LinkedHashSet<String> unique = new LinkedHashSet<>();
    for (String tag : requested) if (!tag.isEmpty()) unique.add(tag);
    for (String tag : unique) {
      var rows =
          jdbc.queryForList(
              "SELECT knowledge_base_id FROM knowledge_tags WHERE tenant_id=? AND id=?",
              tenant,
              tag);
      if (rows.isEmpty()) throw bad("标签 " + tag + " 不存在");
      if (!rows.getFirst().get("knowledge_base_id").equals(kb)) throw bad("标签不属于当前知识库");
    }
    if (!requested.isEmpty())
      transactions.executeWithoutResult(
          status -> {
            jdbc.update("DELETE FROM knowledge_tag_relations WHERE knowledge_id=?", id);
            for (String tag : unique)
              jdbc.update(
                  "INSERT INTO knowledge_tag_relations(knowledge_id,tag_id,created_at)"
                      + " VALUES(?,?,?)",
                  id,
                  tag,
                  timestamp(Instant.now()));
          });
    List<Map<String, Object>> tags;
    try {
      tags = loadTags(tenant, kb, id);
    } catch (RuntimeException readFailure) {
      org.slf4j.LoggerFactory.getLogger(getClass())
          .warn("Failed to load tags for knowledge {}", id, readFailure);
      return;
    }
    if (!tags.isEmpty()) {
      ArrayNode result = json.createArrayNode();
      for (var row : tags) {
        ObjectNode tag = json.createObjectNode();
        for (String field :
            List.of(
                "id",
                "tenant_id",
                "knowledge_base_id",
                "name",
                "color",
                "sort_order",
                "seq_id",
                "created_at",
                "updated_at")) {
          Object value = row.get(field);
          if (field.endsWith("_at"))
            tag.put(
                field,
                value == null ? "0001-01-01T00:00:00Z" : KnowledgeBaseRepository.timestamp(value));
          else if (value instanceof Number number) tag.put(field, number.longValue());
          else tag.put(field, Objects.toString(value, ""));
        }
        result.add(tag);
      }
      record.set("tags", result);
    }
  }

  protected List<Map<String, Object>> loadTags(long tenant, String kb, String id) {
    return jdbc.queryForList(
        "SELECT kt.* FROM knowledge_tag_relations ktr JOIN knowledge_tags kt ON kt.id=ktr.tag_id"
            + " WHERE ktr.knowledge_id=? AND kt.tenant_id=? AND kt.knowledge_base_id=?",
        id,
        tenant,
        kb);
  }

  private ObjectNode project(Map<String, Object> row) {
    ObjectNode result = blank();
    for (var entry : row.entrySet()) {
      String key = entry.getKey().toLowerCase(Locale.ROOT);
      if (!defaults.has(key) && !key.equals("profile")) continue;
      Object value = entry.getValue();
      if (Set.of("metadata", "custom_metadata", "last_faq_import_result", "profile")
          .contains(key)) {
        if (value == null) {
          if (key.equals("profile")) result.remove(key);
          else result.putNull(key);
        } else
          try {
            result.set(
                key,
                json.readTree(
                    value instanceof byte[] bytes
                        ? new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
                        : value.toString()));
          } catch (IOException e) {
            throw new IllegalStateException("Invalid knowledge JSON", e);
          }
      } else if (key.endsWith("_at")) {
        if (value == null) result.putNull(key);
        else result.put(key, KnowledgeBaseRepository.timestamp(value));
      } else if (value instanceof Number number) result.put(key, number.longValue());
      else result.put(key, Objects.toString(value, ""));
    }
    return result;
  }

  private boolean isPostgres() {
    if (postgres == null)
      postgres =
          jdbc.execute(
              (ConnectionCallback<Boolean>)
                  c -> c.getMetaData().getDatabaseProductName().equals("PostgreSQL"));
    return Boolean.TRUE.equals(postgres);
  }

  private Object timestamp(Instant value) {
    return isPostgres() ? Timestamp.from(value) : value.toString();
  }

  private static ApiException bad(String text) {
    return new ApiException(400, "BAD_REQUEST", text).withAppCode(1000);
  }
}
