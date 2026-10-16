package com.tencent.weknora.retrieval;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Production Go copy oracle: raw vectors, FTS visibility, retries and partial commits. */
class KnowledgeIndexCopyGoParityTest {
  final ObjectMapper json = new ObjectMapper();
  record Database(DataSource source, IndexWriter writer, AutoCloseable cleanup)
      implements AutoCloseable {
    public void close() throws Exception { cleanup.close(); }
  }

  Database database() throws Exception {
    var db = new SQLiteIndexGoParityTest.Database();
    return new Database(db.ds, db.repo, db);
  }

  @TestFactory Stream<DynamicTest> actualGoIndexCopies() throws Exception {
    String file = System.getProperty("weknora.index.copy.fixture", "");
    JsonNode rows;
    if (!file.isEmpty()) rows = json.readTree(Files.readAllBytes(Path.of(file)));
    else try (var in = getClass().getResourceAsStream("/go-sqlite-index-copy.json")) {
      rows = json.readTree(Objects.requireNonNull(in));
    }
    assertThat(rows.size()).isEqualTo(21);
    var tests = new ArrayList<DynamicTest>();
    for (var row : rows)
      tests.add(DynamicTest.dynamicTest(row.path("name").asText(), () -> replay(row)));
    return tests.stream();
  }

  void replay(JsonNode row) throws Exception {
    try (var db = database()) {
      var jdbc = new JdbcTemplate(db.source());
      boolean pg = row.path("engine").asText().equals("postgres");
      assertThat(db.writer() instanceof PostgresIndexRepository).isEqualTo(pg);
      db.writer().prepare(Set.of(2));
      var entries = new ArrayList<IndexEntry>();
      for (var e : row.path("entries")) entries.add(new IndexEntry(
          e.path("SourceID").asText(),e.path("SourceType").asInt(),
          e.path("ChunkID").asText(),e.path("KnowledgeID").asText(),
          e.path("KnowledgeBaseID").asText(),e.path("TagID").asText(),
          e.path("Content").asText(),e.path("IsEnabled").asBoolean()));
      var vectors = new LinkedHashMap<String,float[]>();
      row.path("vectors").fields().forEachRemaining(e ->
          vectors.put(e.getKey(), SQLiteIndexGoParityTest.floats(e.getValue())));
      try (var c = db.source().getConnection()) {
        c.setAutoCommit(false); db.writer().batchSave(c,entries,vectors); c.commit();
      }
      String table = pg ? "embeddings" : "lite_embeddings";
      jdbc.execute("UPDATE "+table+" SET created_at='2026-01-01 00:00:00+00:00',updated_at='2026-01-01 00:00:00+00:00'");
      for (var sql : row.path("seed_statements")) execute(db,sql.asText(),pg);
      boolean hidden = row.path("name").asText().equals("source-query-fault");
      compare(snapshot(db,row.path("before").path("queries"),pg,hidden),row.path("before"),"before");
      for (int attempt=0;attempt<2;attempt++) {
        Object error = null;
        try {
          ((IndexCopier)db.writer()).copyIndices(row.path("source").asText(),
              mapping(row.path("documents")),mapping(row.path("chunks")),
              row.path("target").asText(),row.path("dimension").asInt(),row.path("knowledge_type").asText());
        } catch (SQLException e) { error = pg ? e.getSQLState() : e.getErrorCode(); }
        JsonNode expected = row.path("steps").get(attempt);
        assertThat((JsonNode)json.valueToTree(error)).as("copy error "+attempt).isEqualTo(expected.path("error_code"));
        compare(snapshot(db,expected.path("state").path("queries"),pg,hidden&&attempt==0),
            expected.path("state"),"attempt "+attempt);
        if (attempt==0) for (var sql : row.path("reset_statements")) execute(db,sql.asText(),pg);
      }
    }
  }

  void execute(Database db,String sql,boolean pg)throws Exception {
    try(var c=db.source().getConnection()) {
      if(!pg)new SQLiteVectorExtension(Path.of(System.getProperty("weknora.sqlite.vec.extension"))).load(c);
      try(var s=c.createStatement()){s.execute(sql);}
    }
  }

  Map<String,String> mapping(JsonNode value) {
    var result = new LinkedHashMap<String,String>();
    value.fields().forEachRemaining(e -> result.put(e.getKey(),e.getValue().asText()));
    return result;
  }

  JsonNode snapshot(Database db, JsonNode samples, boolean pg, boolean hidden) throws Exception {
    var jdbc = new JdbcTemplate(db.source());
    String table = hidden ? "hidden_copy_index" : pg ? "embeddings" : "lite_embeddings";
    var result = json.createObjectNode();
    ArrayNode metadata = json.valueToTree(jdbc.queryForList(
        "SELECT id,source_id,source_type,chunk_id,knowledge_id,knowledge_base_id,tag_id,content,dimension,is_enabled,created_at='2026-01-01 00:00:00+00:00' AS original_created,updated_at='2026-01-01 00:00:00+00:00' AS unchanged FROM "+table+" ORDER BY id"));
    for (var row : metadata) ((ObjectNode)row).put("source_id",normalizeId(row.path("source_id").asText()));
    result.set("metadata",metadata);
    ArrayNode physical = json.createArrayNode(); Object physicalError = null;
    try (var c=db.source().getConnection()) {
      if (!pg) new SQLiteVectorExtension(Path.of(System.getProperty("weknora.sqlite.vec.extension"))).load(c);
      try (var s=c.createStatement();var r=s.executeQuery(pg
          ? "SELECT id,embedding::text AS embedding FROM "+table+" ORDER BY id"
          : "SELECT rowid AS id,hex(embedding) AS embedding FROM vec_embeddings_2 ORDER BY rowid")) {
        while (r.next()) physical.addObject().put("id",r.getLong(1)).put("embedding",r.getString(2));
      } catch (SQLException e) { physicalError=pg?e.getSQLState():e.getErrorCode(); }
    }
    result.set("physical",physical); result.set("physical_error",json.valueToTree(physicalError));
    var queries=result.putArray("queries");
    for (var sample : samples) {
      var query=new RetrievalQuery("refund",new float[]{1,0},List.of(sample.path("kb").asText()),null,null,1200,0,sample.path("kind").asText());
      var hits=new ArrayList<ObjectNode>(); Object error=null;
      try {
        var blocks=pg?((PostgresIndexRepository)db.writer()).retrieve(query):((SQLiteIndexRepository)db.writer()).retrieve(query);
        for (var block:blocks) for (var h:block.results()) hits.add(json.createObjectNode()
            .put("id",h.id()).put("source",normalizeId(h.sourceId())).put("chunk",h.chunkId())
            .put("document",h.knowledgeId()).put("kb",h.knowledgeBaseId()).put("tag",h.tagId()).put("content",h.content()));
        hits.sort(Comparator.comparing(h -> h.path("id").asText()));
      } catch (SQLException e) { error=pg?e.getSQLState():e.getErrorCode(); }
      var out=queries.addObject().put("kb",sample.path("kb").asText()).put("kind",sample.path("kind").asText());
      out.set("hits",json.valueToTree(hits));out.set("error_code",json.valueToTree(error));
    }
    return result;
  }

  String normalizeId(String value) {
    if (value.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")) return "<uuid>";
    return value;
  }

  void compare(JsonNode actual,JsonNode expected,String context) {
    // SQLite represents booleans as integer columns; PG returns native booleans.
    JsonNode a=actual.deepCopy(),e=expected.deepCopy();booleans(a);booleans(e);
    SQLiteIndexGoParityTest.equal(a,e,context);
  }
  void booleans(JsonNode node) {
    if(node.isObject()) {
      for(String key:List.of("is_enabled","original_created","unchanged"))
        if(node.hasNonNull(key)&&node.path(key).isNumber())((ObjectNode)node).put(key,node.path(key).asInt()!=0);
      node.forEach(this::booleans);
    } else if(node.isArray())node.forEach(this::booleans);
  }
}
