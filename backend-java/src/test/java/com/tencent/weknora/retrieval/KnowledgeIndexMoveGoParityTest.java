package com.tencent.weknora.retrieval;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Real Go metadata relocation, vector identity, independent FTS preservation and retrieval. */
class KnowledgeIndexMoveGoParityTest {
  final ObjectMapper json = new ObjectMapper();

  record Database(DataSource source, Object repository, AutoCloseable cleanup)
      implements AutoCloseable {
    @Override
    public void close() throws Exception {
      cleanup.close();
    }
  }

  Database database() throws Exception {
    var db = new SQLiteIndexGoParityTest.Database();
    return new Database(db.ds, db.repo, db);
  }

  @TestFactory
  Stream<DynamicTest> actualGoIndexMoves() throws Exception {
    JsonNode rows;
    String path = System.getProperty("weknora.index.move.fixture", "");
    if (!path.isEmpty()) rows = json.readTree(Files.readAllBytes(Path.of(path)));
    else
      try (var in = getClass().getResourceAsStream("/go-sqlite-index-move.json")) {
        rows = json.readTree(Objects.requireNonNull(in));
      }
    var tests = new ArrayList<DynamicTest>();
    for (var row : rows)
      tests.add(DynamicTest.dynamicTest(row.path("name").asText(), () -> replay(row)));
    assertThat(tests).hasSize(13);
    return tests.stream();
  }

  private void replay(JsonNode row) throws Exception {
    try (var db = database()) {
      var jdbc = new JdbcTemplate(db.source());
      boolean pg = row.path("engine").asText().equals("postgres");
      assertThat(db.repository() instanceof PostgresIndexRepository).isEqualTo(pg);
      var writer = (IndexWriter) db.repository();
      writer.prepare(Set.of(2));
      var entries = new ArrayList<IndexEntry>();
      for (var e : row.path("entries"))
        entries.add(
            new IndexEntry(
                e.path("SourceID").asText(),
                e.path("SourceType").asInt(),
                e.path("ChunkID").asText(),
                e.path("KnowledgeID").asText(),
                e.path("KnowledgeBaseID").asText(),
                e.path("TagID").asText(),
                e.path("Content").asText(),
                e.path("IsEnabled").asBoolean()));
      var vectors = new HashMap<String, float[]>();
      row.path("vectors")
          .fields()
          .forEachRemaining(
              e -> vectors.put(e.getKey(), SQLiteIndexGoParityTest.floats(e.getValue())));
      try (var c = db.source().getConnection()) {
        c.setAutoCommit(false);
        writer.batchSave(c, entries, vectors);
        c.commit();
      }
      String table = pg ? "embeddings" : "lite_embeddings";
      jdbc.execute(
          "UPDATE "
              + table
              + " SET created_at='2026-01-01 00:00:00+00:00',updated_at='2026-01-01"
              + " 00:00:00+00:00'");
      for (var sql : row.path("seed_statements")) jdbc.execute(sql.asText());
      JsonNode initial = snapshot(db, row.path("before").path("queries"), pg);
      compare(initial, row.path("before"), initial, row.path("before"));
      for (int i = 0; i < 2; i++) {
        Object error = null;
        try (var c = db.source().getConnection()) {
          c.setAutoCommit(false);
          try {
            move(db, c, row);
            if (row.path("name").asText().equals("rollback")) {
              c.rollback();
              error = "controlled rollback";
            } else c.commit();
          } catch (SQLException e) {
            error = pg ? e.getSQLState() : e.getErrorCode();
            c.rollback();
          }
        }
        var expected = row.path("steps").get(i);
        assertThat(json.readTree(json.writeValueAsBytes(error)))
            .isEqualTo(expected.path("error_code"));
        compare(
            snapshot(db, expected.path("state").path("queries"), pg),
            expected.path("state"),
            initial,
            row.path("before"));
        if (i == 0) for (var sql : row.path("reset_statements")) jdbc.execute(sql.asText());
      }
      // Production effects must explicitly own a transaction; never accidentally autocommit
      // metadata.
      try (var c = db.source().getConnection()) {
        assertThatThrownBy(() -> move(db, c, row))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("fenced transaction");
      }
    }
  }

  private static void move(Database db, Connection c, JsonNode row) throws SQLException {
    String source = row.path("source").asText(),
        target = row.path("target").asText(),
        id = row.path("document").asText();
    if (db.repository() instanceof PostgresIndexRepository pg) pg.move(c, source, target, id);
    else ((SQLiteIndexRepository) db.repository()).move(c, source, target, id);
  }

  private JsonNode snapshot(Database db, JsonNode samples, boolean pg) throws Exception {
    var jdbc = new JdbcTemplate(db.source());
    var result = new LinkedHashMap<String, Object>();
    String table = pg ? "embeddings" : "lite_embeddings";
    result.put(
        "metadata",
        jdbc.queryForList(
            "SELECT"
                + " id,source_id,source_type,chunk_id,knowledge_id,knowledge_base_id,tag_id,content,dimension,is_enabled,updated_at='2026-01-01"
                + " 00:00:00+00:00' AS unchanged,created_at='2026-01-01 00:00:00+00:00' AS"
                + " original_created FROM "
                + table
                + " ORDER BY id"));
    try (var c = db.source().getConnection()) {
      if (!pg)
        new SQLiteVectorExtension(Path.of(System.getProperty("weknora.sqlite.vec.extension")))
            .load(c);
      result.put(
          "physical",
          sqlRows(
              c,
              pg
                  ? "SELECT id,embedding::text AS embedding FROM embeddings ORDER BY id"
                  : "SELECT rowid AS id,hex(embedding) AS embedding FROM vec_embeddings_2 ORDER BY"
                        + " rowid"));
      result.put(
          "fts",
          pg
              ? List.of()
              : sqlRows(
                  c, "SELECT id,hex(block) AS block FROM lite_embeddings_fts_data ORDER BY id"));
    }
    var queries = new ArrayList<Object>();
    for (var q : samples) {
      var query =
          new RetrievalQuery(
              "refund",
              new float[] {1, 0},
              List.of(q.path("kb").asText()),
              null,
              null,
              20,
              0,
              q.path("kind").asText());
      Object error = null;
      var hits = new ArrayList<Map<String, Object>>();
      try {
        List<RetrievalResult> blocks =
            pg
                ? ((PostgresIndexRepository) db.repository()).retrieve(query)
                : ((SQLiteIndexRepository) db.repository()).retrieve(query);
        for (var block : blocks)
          for (var h : block.results())
            hits.add(
                Map.of(
                    "id",
                    h.id(),
                    "source",
                    h.sourceId(),
                    "chunk",
                    h.chunkId(),
                    "document",
                    h.knowledgeId(),
                    "kb",
                    h.knowledgeBaseId(),
                    "tag",
                    h.tagId(),
                    "content",
                    h.content()));
        hits.sort(Comparator.comparing(h -> (String) h.get("source")));
      } catch (SQLException e) {
        error = pg ? e.getSQLState() : e.getErrorCode();
      }
      var sample = new LinkedHashMap<String, Object>();
      sample.put("kb", q.path("kb").asText());
      sample.put("kind", q.path("kind").asText());
      sample.put("hits", hits);
      sample.put("error_code", error);
      queries.add(sample);
    }
    result.put("queries", queries);
    return json.readTree(json.writeValueAsBytes(result));
  }

  private static List<Map<String, Object>> sqlRows(Connection c, String sql) throws Exception {
    var rows = new ArrayList<Map<String, Object>>();
    try (var s = c.createStatement();
        var r = s.executeQuery(sql)) {
      var metadata = r.getMetaData();
      while (r.next()) {
        var row = new LinkedHashMap<String, Object>();
        for (int i = 1; i <= metadata.getColumnCount(); i++)
          row.put(metadata.getColumnLabel(i), r.getObject(i));
        rows.add(row);
      }
    }
    return rows;
  }

  private static void compare(
      JsonNode actual, JsonNode expected, JsonNode initial, JsonNode goInitial) {
    // FTS shadow-page bytes are private to the SQLite build. Both executions must preserve their
    // own original bytes exactly; metadata, vectors and logical retrieval are compared across
    // Go/Java.
    assertThat(actual.path("fts")).isEqualTo(initial.path("fts"));
    assertThat(expected.path("fts")).isEqualTo(goInitial.path("fts"));
    ObjectNode a = actual.deepCopy(), e = expected.deepCopy();
    a.remove("fts");
    e.remove("fts");
    assertThat(a).isEqualTo(e);
  }
}
