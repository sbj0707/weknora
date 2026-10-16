package com.tencent.weknora.vectorstore;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.security.GoSecretCodec;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Owner-scoped CRUD. Remote work never holds this SQL transaction or its Hikari connection. */
public final class VectorStorePublicRepository {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final VectorStoreMetadata metadata;
  private final GoSecretCodec secrets;
  private final ObjectMapper json;
  private final Clock clock;
  private final boolean postgres;

  public VectorStorePublicRepository(
      JdbcTemplate jdbc,
      PlatformTransactionManager manager,
      VectorStoreMetadata metadata,
      GoSecretCodec secrets,
      ObjectMapper json) {
    this(jdbc, manager, metadata, secrets, json, Clock.systemUTC());
  }

  public VectorStorePublicRepository(
      JdbcTemplate jdbc,
      PlatformTransactionManager manager,
      VectorStoreMetadata metadata,
      GoSecretCodec secrets,
      ObjectMapper json,
      Clock clock) {
    this.jdbc = jdbc;
    tx = new TransactionTemplate(manager);
    this.metadata = metadata;
    this.secrets = secrets;
    this.json = json;
    this.clock = clock;
    try (var c = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
      postgres = c.getMetaData().getDatabaseProductName().equals("PostgreSQL");
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  public ObjectNode get(long owner, String id) {
    var rows =
        jdbc.queryForList(
            "SELECT * FROM vector_stores WHERE tenant_id=? AND id=? AND deleted_at IS NULL",
            owner,
            id);
    return rows.isEmpty() ? null : render(rows.getFirst());
  }

  public List<ObjectNode> list(long owner) {
    return jdbc
        .queryForList(
            "SELECT * FROM vector_stores WHERE tenant_id=? AND deleted_at IS NULL ORDER BY"
                + " created_at DESC",
            owner)
        .stream()
        .map(this::render)
        .toList();
  }

  public List<ObjectNode> matchingEngine(long owner, String engine) {
    return jdbc
        .queryForList(
            "SELECT * FROM vector_stores WHERE tenant_id=? AND engine_type=? AND deleted_at IS"
                + " NULL",
            owner,
            engine)
        .stream()
        .map(this::render)
        .toList();
  }

  private ObjectNode render(Map<String, Object> row) {
    return render(row, true);
  }

  private ObjectNode render(Map<String, Object> row, boolean decrypt) {
    var data = metadata.decodeRow(row, decrypt);
    var out =
        json.createObjectNode()
            .put("id", data.id())
            .put("tenant_id", data.tenant())
            .put("name", Objects.toString(row.get("name"), ""))
            .put("engine_type", data.engine());
    out.set("connection_config", data.connection());
    out.set("index_config", data.index());
    out.put("created_at", timestamp(row.get("created_at")))
        .put("updated_at", timestamp(row.get("updated_at")));
    if (row.get("deleted_at") == null) out.putNull("deleted_at");
    else out.put("deleted_at", timestamp(row.get("deleted_at")));
    out.put("source", "user").put("readonly", false);
    return out;
  }

  public ObjectNode create(
      long owner, String name, String engine, JsonNode connection, JsonNode index) {
    String id = UUID.randomUUID().toString();
    Object now = time(clock.instant());
    jdbc.update(
        "INSERT INTO"
            + " vector_stores(id,tenant_id,name,engine_type,connection_config,index_config,created_at,updated_at)"
            + " VALUES(?,?,?,?,"
            + parameter()
            + ","
            + parameter()
            + ",?,?)",
        id,
        owner,
        name,
        engine,
        stored(connection),
        postgres
            ? index.toString()
            : index.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
        now,
        now);
    // Go returns the created in-memory value, without a second query or secret decryption.
    // This also preserves the response if another transaction changes the row after INSERT.
    var created = new LinkedHashMap<String, Object>();
    created.put("id", id);
    created.put("tenant_id", owner);
    created.put("name", name);
    created.put("engine_type", engine);
    created.put(
        "connection_config",
        connection.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    created.put("index_config", index.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    created.put("created_at", now);
    created.put("updated_at", now);
    created.put("deleted_at", null);
    return render(created, false);
  }

  public void rename(long owner, String id, String name) {
    jdbc.update(
        "UPDATE vector_stores SET name=? WHERE tenant_id=? AND id=? AND deleted_at IS NULL",
        name,
        owner,
        id);
  }

  public void version(long owner, String id, JsonNode cc) {
    jdbc.update(
        "UPDATE vector_stores SET connection_config="
            + parameter()
            + " WHERE tenant_id=? AND id=? AND deleted_at IS NULL",
        stored(cc),
        owner,
        id);
  }

  public void delete(long owner, String id) {
    tx.executeWithoutResult(
        status -> {
          if (!postgres) jdbc.update("UPDATE vector_stores SET name=name WHERE 1=0");
          var rows =
              jdbc.queryForList(
                  "SELECT id FROM vector_stores WHERE tenant_id=? AND id=? AND deleted_at IS NULL"
                      + (postgres ? " FOR UPDATE" : ""),
                  owner,
                  id);
          if (rows.isEmpty())
            throw VectorStorePublicForms.error(404, 1003, "vector store not found");
          long bound =
              Objects.requireNonNull(
                  jdbc.queryForObject(
                      "SELECT COUNT(*) FROM knowledge_bases WHERE tenant_id=? AND vector_store_id=?"
                          + " AND deleted_at IS NULL",
                      Long.class,
                      owner,
                      id));
          if (bound > 0)
            throw VectorStorePublicForms.error(
                400,
                1000,
                "vector store still has "
                    + bound
                    + " knowledge base(s) bound to it; unbind or delete them before removing the"
                    + " store");
          jdbc.update(
              "UPDATE vector_stores SET deleted_at=? WHERE tenant_id=? AND id=? AND deleted_at IS"
                  + " NULL",
              time(clock.instant()),
              owner,
              id);
        });
  }

  private String parameter() {
    return postgres ? "CAST(? AS JSONB)" : "?";
  }

  private Object stored(JsonNode input) {
    var cc = input.deepCopy();
    for (String field : List.of("password", "api_key"))
      if (cc.hasNonNull(field))
        ((ObjectNode) cc).put(field, secrets.encryptLegacyWhenConfigured(cc.path(field).asText()));
    String value = cc.toString();
    return postgres ? value : value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  private Object time(Instant instant) {
    return postgres ? Timestamp.from(instant) : instant.toString();
  }

  private String timestamp(Object value) {
    if (value == null) return "0001-01-01T00:00:00Z";
    if (value instanceof Number n) return Instant.ofEpochMilli(n.longValue()).toString();
    if (value instanceof Timestamp t) return t.toInstant().toString();
    if (value instanceof OffsetDateTime t) return t.toInstant().toString();
    String raw = value.toString().replace(' ', 'T');
    try {
      return Instant.parse(raw).toString();
    } catch (Exception e) {
      return LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC).toString();
    }
  }
}
