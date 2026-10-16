package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Read-only whole-batch preflight followed by separate durable, compare-and-swap checkpoints. */
final class KnowledgeTransferRepository {
  record Scope(long tenant, String task, String source, String target) {
    Scope {
      if (tenant <= 0
          || task == null
          || task.isEmpty()
          || source == null
          || source.isEmpty()
          || target == null
          || target.isEmpty()
          || source.equals(target)) throw new IllegalArgumentException("kb_access: forbidden");
    }
  }

  @FunctionalInterface
  interface EngineValidator {
    void validate() throws Exception;
  }

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final ObjectMapper json;
  private final KnowledgeTransferMetadata metadata;
  private final Clock clock;
  private final boolean postgres;

  KnowledgeTransferRepository(
      JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager manager, Clock clock) {
    this.jdbc = jdbc;
    this.json = json;
    this.transactions = new TransactionTemplate(manager);
    this.metadata = new KnowledgeTransferMetadata(json);
    this.clock = clock;
    postgres =
        Boolean.TRUE.equals(
            jdbc.execute(
                (ConnectionCallback<Boolean>)
                    c -> c.getMetaData().getDatabaseProductName().equals("PostgreSQL")));
  }

  List<Map<String, Object>> plan(
      Scope scope,
      JsonNode source,
      JsonNode target,
      JsonNode tenant,
      List<String> requested,
      String mode,
      EngineValidator engine)
      throws Exception {
    requirePair(scope, source, target);
    KnowledgeMoveCompatibility.validate(source, target, mode, tenant);
    var ids = new LinkedHashSet<String>();
    for (String id : requested == null ? List.<String>of() : requested) {
      if (id == null || KnowledgeUploadForms.goTrim(id).isEmpty())
        throw new IllegalArgumentException(
            "error code: 1000, error message: resource ID cannot be empty");
      ids.add(id);
    }
    if (ids.isEmpty()) throw new IllegalArgumentException("knowledge IDs cannot be empty");
    var args = new ArrayList<Object>();
    args.add(scope.tenant());
    args.addAll(ids);
    var rows =
        jdbc.queryForList(
            "SELECT * FROM knowledges WHERE tenant_id=? AND id IN ("
                + String.join(",", Collections.nCopies(ids.size(), "?"))
                + ") AND deleted_at IS NULL",
            args.toArray());
    var byId = new HashMap<String, Map<String, Object>>();
    for (var row : rows) byId.put(text(row, "id"), row);
    boolean engineChecked = false;
    var result = new ArrayList<Map<String, Object>>();
    for (String id : ids) {
      var row = byId.get(id);
      if (row == null || number(row, "tenant_id") != scope.tenant())
        throw new IllegalArgumentException("knowledge " + id + " not found in transfer tenant");
      validateItem(scope, row, mode);
      var state = metadata.read(row.get("metadata"));
      var allowed =
          matches(scope, state, id, mode) && state.path("phase").asText().equals("moving")
              ? Set.of(scope.source(), scope.target())
              : Set.of(text(row, "knowledge_base_id"));
      chunks(scope.tenant(), id, allowed);
      if (mode.equals("reuse_vectors") && !text(row, "embedding_model_id").isEmpty()) {
        if (!text(row, "embedding_model_id").equals(source.path("embedding_model_id").asText("")))
          throw new IllegalArgumentException(
              "knowledge " + id + " uses a different embedding model");
        if (!engineChecked) {
          engine.validate();
          engineChecked = true;
        }
      }
      result.add(new LinkedHashMap<>(row));
    }
    return result;
  }

  void validateItem(Scope scope, Map<String, Object> row, String mode) throws IOException {
    if (row == null || number(row, "tenant_id") != scope.tenant())
      throw new IllegalArgumentException("kb_access: forbidden");
    String id = text(row, "id"),
        kb = text(row, "knowledge_base_id"),
        status = text(row, "parse_status");
    var state = metadata.read(row.get("metadata"));
    if (matches(scope, state, id, mode)) {
      String phase = state.path("phase").asText();
      if (phase.equals("moving")
          && kb.equals(scope.source())
          && Set.of("processing", "failed").contains(status)) return;
      if (Set.of("reparse_pending", "done").contains(phase) && kb.equals(scope.target())) return;
      throw new IllegalArgumentException("knowledge " + id + " changed during move");
    }
    if (!kb.equals(scope.source()) || !status.equals("completed"))
      throw new IllegalArgumentException(
          "knowledge " + id + " must be completed in the source knowledge base");
    if (state != null && !state.path("phase").asText().equals("done"))
      throw new IllegalArgumentException("knowledge " + id + " has an unfinished transfer");
    if (mode.equals("reparse")) {
      if (text(row, "type").equals("manual")) {
        if (!manualContent(row.get("metadata")))
          throw new IllegalArgumentException(
              "knowledge " + id + " has no manual content to reparse");
      } else if (text(row, "file_path").isEmpty())
        throw new IllegalArgumentException("knowledge " + id + " has no stored file to reparse");
    }
  }

  List<Map<String, Object>> chunks(long tenant, String id, Set<String> allowed) {
    var rows =
        KnowledgeTransferRows.query(jdbc,
            "SELECT * FROM chunks WHERE tenant_id=? AND knowledge_id=?"
                + " AND deleted_at IS NULL ORDER BY id ASC",
            tenant,
            id);
    var seen = new HashSet<String>();
    var tags = new HashMap<String, String>();
    for (var chunk : rows) {
      String chunkId = text(chunk, "id"),
          kb = text(chunk, "knowledge_base_id"),
          tag = text(chunk, "tag_id");
      if (chunkId.isEmpty()
          || !seen.add(chunkId)
          || number(chunk, "tenant_id") != tenant
          || !text(chunk, "knowledge_id").equals(id)
          || !allowed.contains(kb))
        throw new IllegalArgumentException("chunk binding does not match the transfer scope");
      if (!tag.isEmpty() && !kb.equals(tags.get(tag))) {
        var selected =
            jdbc.queryForList(
                "SELECT * FROM knowledge_tags WHERE tenant_id=? AND id=?", tenant, tag);
        if (selected.isEmpty()) throw new IllegalArgumentException("record not found");
        var value = selected.getFirst();
        if (!text(value, "id").equals(tag)
            || number(value, "tenant_id") != tenant
            || !text(value, "knowledge_base_id").equals(kb))
          throw new IllegalArgumentException("chunk tag belongs to another knowledge base");
        tags.put(tag, kb);
      }
    }
    for (var chunk : rows) {
      String parent = text(chunk, "parent_chunk_id");
      if (!parent.isEmpty() && !seen.contains(parent))
        throw new IllegalArgumentException("chunk parent belongs to another document");
    }
    return rows;
  }

  void checkpoint(Map<String, Object> before, Map<String, Object> after) {
    if (before == null
        || after == null
        || text(before, "id").isEmpty()
        || !text(before, "id").equals(text(after, "id"))
        || number(before, "tenant_id") <= 0
        || number(before, "tenant_id") != number(after, "tenant_id"))
      throw new IllegalArgumentException("invalid knowledge transfer checkpoint");
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    after.put("updated_at", time(now));
    Object stored;
    try {
      stored =
          transactions.execute(
              status -> {
                int changed =
                    jdbc.update(
                        "UPDATE knowledges SET knowledge_base_id=?,parse_status=?,metadata="
                            + (postgres ? "?::jsonb" : "?")
                            + ",storage_size=?,updated_at=?,enable_status=?,embedding_model_id=?,description=?,processed_at=?,error_message=?"
                            + " WHERE id=? AND tenant_id=? AND knowledge_base_id=? AND"
                            + " parse_status=? AND updated_at=? AND deleted_at IS NULL",
                        text(after, "knowledge_base_id"),
                        text(after, "parse_status"),
                        jsonValue(after.get("metadata")),
                        number(after, "storage_size"),
                        after.get("updated_at"),
                        text(after, "enable_status"),
                        text(after, "embedding_model_id"),
                        text(after, "description"),
                        after.get("processed_at"),
                        text(after, "error_message"),
                        text(before, "id"),
                        number(before, "tenant_id"),
                        text(before, "knowledge_base_id"),
                        text(before, "parse_status"),
                        postgres
                            ? before.get("updated_at")
                            : sqliteTimestamp(before.get("updated_at")));
                if (changed != 1)
                  throw new IllegalStateException("knowledge changed during transfer");
                long delta = number(after, "storage_size") - number(before, "storage_size");
                if (delta != 0
                    && jdbc.update(
                            "UPDATE tenants SET storage_used=CASE WHEN storage_used+? < 0 THEN 0"
                                + " ELSE storage_used+? END WHERE id=?",
                            delta,
                            delta,
                            number(before, "tenant_id"))
                        != 1) throw new IllegalStateException("transfer tenant not found");
                return jdbc.queryForObject(
                    "SELECT updated_at FROM knowledges WHERE id=? AND tenant_id=? AND deleted_at IS"
                        + " NULL",
                    Object.class,
                    text(after, "id"),
                    number(after, "tenant_id"));
              });
    } catch (org.springframework.dao.DataAccessException error) {
      throw new IllegalStateException(ChunkEditService.diagnostic(error), error);
    }
    after.put("updated_at", stored);
  }

  Map<String, Object> get(long tenant, String id) {
    var rows =
        jdbc.queryForList(
            "SELECT * FROM knowledges WHERE tenant_id=? AND id=? AND deleted_at IS NULL",
            tenant,
            id);
    if (rows.isEmpty()) throw new IllegalArgumentException("record not found");
    return new LinkedHashMap<>(rows.getFirst());
  }

  /** Go types.JSON.Value binds raw JSON as []byte on SQLite and JSONB text on PostgreSQL. */
  private Object jsonValue(Object value) {
    if (value == null) return null;
    String raw = value instanceof byte[] bytes
        ? new String(bytes, java.nio.charset.StandardCharsets.UTF_8) : value.toString();
    if (raw.isEmpty()) return null;
    return postgres ? raw : raw.getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  /** These commits deliberately precede the document checkpoint, as in the Go repositories. */
  void moveChunks(Scope scope, String id) {
    sql(
        () ->
            jdbc.update(
                "UPDATE chunks SET knowledge_base_id=?,tag_id='',updated_at=?"
                    + " WHERE tenant_id=? AND knowledge_id=? AND deleted_at IS NULL",
                scope.target(),
                time(clock.instant()),
                scope.tenant(),
                id));
  }

  void clearTags(String id) {
    sql(() -> jdbc.update("DELETE FROM knowledge_tag_relations WHERE knowledge_id=?", id));
  }

  private void sql(Runnable update) {
    try {
      transactions.executeWithoutResult(status -> update.run());
    } catch (org.springframework.dao.DataAccessException error) {
      throw new IllegalStateException(ChunkEditService.diagnostic(error), error);
    }
  }

  private boolean manualContent(Object stored) {
    if (stored == null || stored.toString().isEmpty()) return false;
    var shape =
        json.createObjectNode()
            .put("kind", "struct")
            .put("name", "ManualKnowledgeMetadata")
            .put("type", "types.ManualKnowledgeMetadata");
    var fields = shape.putArray("fields");
    for (String field : List.of("content", "format", "status", "updated_at", "version"))
      fields.add(
          json.createObjectNode()
              .put("name", field)
              .set(
                  "schema",
                  json.createObjectNode()
                      .put("kind", field.equals("version") ? "int64" : "string")
                      .put("type", field.equals("version") ? "int" : "string")));
    try {
      String raw =
          stored instanceof byte[] bytes
              ? com.tencent.weknora.docreader.GoUtf8.decode(bytes)
              : stored.toString();
      return !new GoUploadJson(json).decode(raw, shape).path("content").asText("").isEmpty();
    } catch (IOException invalid) {
      return false;
    }
  }

  static void requirePair(Scope scope, JsonNode source, JsonNode target) {
    if (source == null
        || target == null
        || !source.path("id").asText().equals(scope.source())
        || !target.path("id").asText().equals(scope.target())
        || source.path("tenant_id").asLong() != scope.tenant()
        || target.path("tenant_id").asLong() != scope.tenant())
      throw new IllegalArgumentException("kb_access: forbidden");
  }

  private static boolean matches(Scope scope, JsonNode state, String id, String mode) {
    return KnowledgeTransferMetadata.matches(
        state, scope.task(), scope.source(), scope.target(), id, mode);
  }

  private Object time(Instant value) {
    return postgres
        ? Timestamp.from(value)
        : sqliteTime(LocalDateTime.ofInstant(value, ZoneOffset.UTC), 0);
  }

  /** go-sqlite3 reads DATETIME as time.Time and binds it using SQLiteTimestampFormats[0]. */
  private static String sqliteTimestamp(Object value) {
    String zero = "0001-01-01 00:00:00+00:00";
    if (value == null || value instanceof Number) return zero;
    String text = value.toString();
    if (text.endsWith("Z")) text = text.substring(0, text.length() - 1);
    var matcher =
        java.util.regex.Pattern.compile(
                "([0-9]{4})-([0-9]{2})-([0-9]{2})(?:(?:"
                    + " +|T)([0-9]{1,2}):([0-9]{2})(?::([0-9]{2})(?:[.,]([0-9]+))?)?)?([+-][0-9]{2}:[0-9]{2})?")
            .matcher(text);
    if (!matcher.matches()) return zero;
    try {
      int hour = matcher.group(4) == null ? 0 : Integer.parseInt(matcher.group(4));
      int minute = matcher.group(5) == null ? 0 : Integer.parseInt(matcher.group(5));
      int second = matcher.group(6) == null ? 0 : Integer.parseInt(matcher.group(6));
      String fraction = matcher.group(7), offset = matcher.group(8);
      int nanos = fraction == null ? 0 : Integer.parseInt((fraction + "000000000").substring(0, 9));
      var date =
          LocalDateTime.of(
              Integer.parseInt(matcher.group(1)),
              Integer.parseInt(matcher.group(2)),
              Integer.parseInt(matcher.group(3)),
              hour,
              minute,
              second,
              nanos);
      int seconds = 0;
      if (offset != null) {
        if (matcher.group(6) == null) return zero;
        int hours = Integer.parseInt(offset.substring(1, 3)),
            minutes = Integer.parseInt(offset.substring(4, 6));
        if (hours > 24 || minutes > 60) return zero;
        seconds = (hours * 60 + minutes) * 60 * (offset.charAt(0) == '-' ? -1 : 1);
      }
      return sqliteTime(date, seconds);
    } catch (DateTimeException | NumberFormatException invalid) {
      return zero;
    }
  }

  private static String sqliteTime(LocalDateTime value, int offset) {
    String result =
        value.format(
            java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.ROOT));
    if (value.getNano() != 0)
      result += "." + String.format(Locale.ROOT, "%09d", value.getNano()).replaceFirst("0+$", "");
    int absolute = Math.abs(offset);
    return result
        + (offset < 0 ? "-" : "+")
        + String.format(Locale.ROOT, "%02d:%02d", absolute / 3600, absolute % 3600 / 60);
  }

  static String text(Map<String, Object> row, String name) {
    return Objects.toString(row.get(name), "");
  }

  static long number(Map<String, Object> row, String name) {
    Object value = row.get(name);
    return value == null
        ? 0
        : value instanceof Number n ? n.longValue() : Long.parseLong(value.toString());
  }
}
