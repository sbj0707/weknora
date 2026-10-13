package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.security.ApiException;
import java.io.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads existing Go document/chunk tables using an independently authorized KB execution tenant.
 */
@Repository
public class KnowledgeContentReadRepository {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final ObjectNode knowledgeDefaults, chunkDefaults;
  private final com.tencent.weknora.security.GoStoredStructDecoder profiles, locators;

  public KnowledgeContentReadRepository(JdbcTemplate jdbc, ObjectMapper json) throws IOException {
    this.jdbc = jdbc;
    this.json = json;
    try (var in = getClass().getResourceAsStream("/knowledge-default.json")) {
      knowledgeDefaults = (ObjectNode) json.readTree(in);
    }
    try (var in = getClass().getResourceAsStream("/chunk-default.json")) {
      chunkDefaults = (ObjectNode) json.readTree(in);
    }
    try (var in = getClass().getResourceAsStream("/knowledge-read-schema.json")) {
      var schema = json.readTree(in);
      profiles =
          new com.tencent.weknora.security.GoStoredStructDecoder(json, schema.path("profile"));
      locators =
          new com.tencent.weknora.security.GoStoredStructDecoder(json, schema.path("locators"));
    }
  }

  /**
   * Projects a row already fetched under a parent KB grant; this method performs no authorization.
   */
  public ObjectNode projectKnowledge(Map<String, Object> authorizedRow) {
    return project(authorizedRow, knowledgeDefaults);
  }

  /**
   * Projects a row already fetched under a parent KB grant; this method performs no authorization.
   */
  public ObjectNode projectChunk(Map<String, Object> authorizedRow) {
    return project(authorizedRow, chunkDefaults);
  }

  public ObjectNode globalKnowledge(String id) {
    return one("knowledges", null, id, knowledgeDefaults, "Knowledge not found");
  }

  public ObjectNode globalChunk(String id) {
    return one("chunks", null, id, chunkDefaults, "Chunk not found");
  }

  public ObjectNode knowledge(long tenant, String id) {
    ObjectNode item = one("knowledges", tenant, id, knowledgeDefaults, "Knowledge not found");
    tags(List.of(item));
    return item;
  }

  private ObjectNode one(
      String table, Long tenant, String id, ObjectNode defaults, String missing) {
    String sql = "SELECT * FROM " + table + " WHERE id=? AND deleted_at IS NULL";
    List<Object> args = new ArrayList<>(List.of(id));
    if (tenant != null) {
      sql += " AND tenant_id=?";
      args.add(tenant);
    }
    var rows = jdbc.queryForList(sql, args.toArray());
    if (rows.isEmpty()) throw new ApiException(404, "NOT_FOUND", missing).withAppCode(1003);
    return project(rows.getFirst(), defaults);
  }

  public record Page(long page, long size) {}

  public record ListFilter(
      List<String> tagIds,
      String keyword,
      String fileType,
      String source,
      String parseStatus,
      String sortBy,
      String sortOrder,
      Instant from,
      Instant to,
      boolean folderPresent,
      String folder,
      boolean recursive) {}

  public Map<String, Object> knowledges(long tenant, String kb, Page page, ListFilter filter) {
    StringBuilder where =
        new StringBuilder("tenant_id=? AND knowledge_base_id=? AND deleted_at IS NULL");
    List<Object> args = new ArrayList<>(List.of(tenant, kb));
    if (!filter.tagIds().isEmpty()) {
      where
          .append(" AND id IN (SELECT knowledge_id FROM knowledge_tag_relations WHERE tag_id IN (")
          .append(placeholders(filter.tagIds().size()))
          .append("))");
      args.addAll(filter.tagIds());
    }
    if (!filter.keyword().isEmpty()) {
      where.append(" AND (LOWER(file_name) LIKE ? ESCAPE '\\' OR LOWER(title) LIKE ? ESCAPE '\\')");
      String keyword = "%" + escape(filter.keyword()).toLowerCase(Locale.ROOT) + "%";
      args.add(keyword);
      args.add(keyword);
    }
    if (!filter.fileType().isEmpty()) {
      where.append(
          Set.of("manual", "url").contains(filter.fileType()) ? " AND type=?" : " AND file_type=?");
      args.add(filter.fileType());
    }
    if (!filter.source().isEmpty()) {
      where.append(
          Set.of("manual", "url").contains(filter.source()) ? " AND type=?" : " AND channel=?");
      args.add(filter.source());
    }
    if (filter.parseStatus().isEmpty()) where.append(" AND parse_status<>'deleting'");
    else {
      where.append(" AND parse_status=?");
      args.add(filter.parseStatus());
    }
    boolean sqlite =
        Boolean.TRUE.equals(
            jdbc.execute(
                (org.springframework.jdbc.core.ConnectionCallback<Boolean>)
                    c -> c.getMetaData().getDatabaseProductName().equals("SQLite")));
    if (filter.from() != null) {
      where.append(sqlite ? " AND julianday(updated_at)>=julianday(?)" : " AND updated_at>=?");
      args.add(sqlite ? filter.from().toString() : Timestamp.from(filter.from()));
    }
    if (filter.to() != null) {
      where.append(sqlite ? " AND julianday(updated_at)<=julianday(?)" : " AND updated_at<=?");
      args.add(sqlite ? filter.to().toString() : Timestamp.from(filter.to()));
    }
    if (filter.folderPresent()) {
      if (filter.recursive() && !filter.folder().isEmpty()) {
        where.append(" AND (folder_path=? OR folder_path LIKE ? ESCAPE '\\')");
        args.add(filter.folder());
        args.add(escape(filter.folder()) + "/%");
      } else if (!filter.recursive()) {
        where.append(" AND folder_path=?");
        args.add(filter.folder());
      }
    }
    long total =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM knowledges WHERE " + where, Long.class, args.toArray());
    String sort =
        switch (filter.sortBy()) {
          case "updated_at" -> "updated_at";
          case "file_name" -> "LOWER(COALESCE(NULLIF(file_name,''),NULLIF(title,''),source))";
          default -> "created_at";
        };
    String sql =
        "SELECT * FROM knowledges WHERE "
            + where
            + " ORDER BY "
            + sort
            + (filter.sortOrder().equals("asc") ? " ASC" : " DESC")
            + ",id ASC LIMIT ? OFFSET ?";
    args.add(page.size());
    args.add((page.page() - 1) * page.size());
    List<ObjectNode> data = new ArrayList<>();
    for (var row : jdbc.queryForList(sql, args.toArray()))
      data.add(project(row, knowledgeDefaults));
    tags(data);
    return result(data, total, page);
  }

  public Map<String, Object> chunks(long tenant, String knowledge, Page page, List<String> types) {
    List<Object> args = new ArrayList<>(List.of(tenant, knowledge));
    args.addAll(types);
    String where =
        "tenant_id=? AND knowledge_id=? AND deleted_at IS NULL AND status IN (0,2) AND chunk_type"
            + " IN ("
            + placeholders(types.size())
            + ")";
    long total =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM chunks WHERE " + where, Long.class, args.toArray());
    args.add(page.size());
    args.add((page.page() - 1) * page.size());
    List<ObjectNode> data = new ArrayList<>();
    for (var row :
        jdbc.queryForList(
            "SELECT * FROM chunks WHERE " + where + " ORDER BY chunk_index ASC LIMIT ? OFFSET ?",
            args.toArray())) data.add(project(row, chunkDefaults));
    return result(data, total, page);
  }

  private Map<String, Object> result(List<ObjectNode> data, long total, Page page) {
    return Map.of(
        "success",
        true,
        "data",
        data,
        "total",
        total,
        "page",
        page.page(),
        "page_size",
        page.size());
  }

  private void tags(List<ObjectNode> items) {
    if (items.isEmpty()) return;
    List<Object> ids = new ArrayList<>();
    for (var item : items) ids.add(item.path("id").asText());
    try {
      var rows =
          jdbc.queryForList(
              "SELECT"
                  + " ktr.knowledge_id,kt.id,kt.seq_id,kt.tenant_id,kt.knowledge_base_id,kt.name,kt.color,kt.sort_order,kt.created_at,kt.updated_at"
                  + " FROM knowledge_tag_relations ktr JOIN knowledge_tags kt ON ktr.tag_id=kt.id"
                  + " WHERE ktr.knowledge_id IN ("
                  + placeholders(ids.size())
                  + ")",
              ids.toArray());
      Map<String, ArrayNode> byId = new HashMap<>();
      for (var row : rows) {
        ObjectNode tag = json.createObjectNode();
        for (String field :
            List.of(
                "id",
                "seq_id",
                "tenant_id",
                "knowledge_base_id",
                "name",
                "color",
                "sort_order",
                "created_at",
                "updated_at")) {
          Object value = row.get(field);
          if (field.endsWith("_at"))
            tag.put(
                field,
                value == null ? "0001-01-01T00:00:00Z" : KnowledgeBaseRepository.timestamp(value));
          else if (value instanceof Number n) tag.put(field, n.longValue());
          else tag.put(field, Objects.toString(value, ""));
        }
        byId.computeIfAbsent(row.get("knowledge_id").toString(), key -> json.createArrayNode())
            .add(tag);
      }
      for (var item : items) {
        var value = byId.get(item.path("id").asText());
        if (value != null) item.set("tags", value);
      }
    } catch (RuntimeException ignored) {
    }
  }

  private ObjectNode project(Map<String, Object> row, ObjectNode defaults) {
    ObjectNode out = defaults.deepCopy();
    for (var entry : row.entrySet()) {
      String key = entry.getKey().toLowerCase(Locale.ROOT);
      if (!defaults.has(key) && !Set.of("profile", "source_locators").contains(key)) continue;
      Object value = entry.getValue();
      if (Set.of(
              "metadata",
              "custom_metadata",
              "last_faq_import_result",
              "profile",
              "source_locators",
              "relation_chunks",
              "indirect_relation_chunks")
          .contains(key)) {
        if (value == null) {
          if (Set.of("profile", "source_locators").contains(key)) out.remove(key);
          else out.putNull(key);
        } else
          try {
            if (!(value instanceof String
                || value instanceof byte[]
                || value.getClass().getName().equals("org.postgresql.util.PGobject")))
              throw new IllegalStateException("type assertion to []byte or string failed");
            String raw =
                value instanceof byte[] bytes
                    ? new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
                    : value.toString();
            JsonNode parsed =
                key.equals("profile")
                    ? profiles.project(raw.isEmpty() ? "{}" : raw)
                    : key.equals("source_locators")
                        ? locators.project(raw.isEmpty() ? "null" : raw)
                        : storedJson(raw);
            if (key.equals("source_locators")
                && (parsed == null || parsed.isNull() || parsed.isEmpty())) out.remove(key);
            else out.set(key, parsed);
          } catch (IOException ex) {
            throw new IllegalStateException("Invalid stored " + key, ex);
          }
      } else if (Set.of("created_at", "updated_at", "processed_at", "deleted_at").contains(key)) {
        if (value == null) {
          if (key.equals("processed_at") || key.equals("deleted_at")) out.putNull(key);
          else out.put(key, "0001-01-01T00:00:00Z");
        } else out.put(key, KnowledgeBaseRepository.timestamp(value));
      } else if (defaults.path(key).isBoolean())
        out.put(
            key,
            value instanceof Boolean b
                ? b.booleanValue()
                : value instanceof Number n && n.intValue() != 0);
      else if (defaults.path(key).isNumber())
        out.put(key, value instanceof Number n ? n.longValue() : 0);
      else out.put(key, Objects.toString(value, ""));
    }
    return out;
  }

  private JsonNode storedJson(String raw) throws IOException {
    if (raw.isEmpty()) return NullNode.instance;
    JsonNode value =
        json.reader()
            .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .readTree(raw);
    if (value == null || value.isMissingNode()) throw new IOException("Invalid empty stored JSON");
    return value;
  }

  private static String escape(String s) {
    return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private static String placeholders(int count) {
    return String.join(",", Collections.nCopies(count, "?"));
  }
}
