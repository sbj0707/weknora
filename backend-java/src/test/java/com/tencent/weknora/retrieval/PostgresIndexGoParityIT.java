package com.tencent.weknora.retrieval;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.knowledge.KnowledgeProcessingConfiguration;
import com.zaxxer.hikari.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

/** Explicit live integration suite; requires the isolated weknora_parity database and a Go replay. */
class PostgresIndexGoParityIT {
  private static final ObjectMapper JSON=new ObjectMapper();

  private static HikariDataSource database() throws SQLException {
    String url=System.getProperty("weknora.parity.pg.url");
    if(url==null) throw new IllegalStateException("Run scripts/postgres-index-parity.py against an isolated ParadeDB database");
    var config=new HikariConfig();config.setJdbcUrl(url);config.setUsername("postgres");
    config.setPassword("local-parity-only");config.setMaximumPoolSize(1);config.setConnectionTimeout(1000);
    var ds=new HikariDataSource(config);
    try(Connection c=ds.getConnection();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT current_database()")) {
      assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo("weknora_parity");
    }
    return ds;
  }

  private static ArrayNode rows(Connection c) throws SQLException {
    var out=JSON.createArrayNode();
    try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT id,source_id,source_type,chunk_id,knowledge_id,knowledge_base_id,tag_id,content,dimension,embedding::text AS embedding,is_enabled FROM embeddings ORDER BY id")) {
      var meta=r.getMetaData();while(r.next()) {
        var row=JSON.createObjectNode();for(int i=1;i<=meta.getColumnCount();i++) row.set(meta.getColumnLabel(i),JSON.valueToTree(r.getObject(i)));out.add(row);
      }
    }
    return out;
  }

  @Test void actualGoHalfvecParadeDbMutationsAndRetrievalThroughOneConnectionPool() throws Exception {
    JsonNode fixture=JSON.readTree(Files.readString(Path.of(Objects.requireNonNull(System.getProperty("weknora.parity.pg.fixture"),"Go replay fixture required"))));
    int mutations=0,queries=0,queryErrors=0;
    try(var ds=database()) {
      try(Connection c=ds.getConnection();Statement s=c.createStatement()){s.execute("TRUNCATE embeddings RESTART IDENTITY");}
      var indexes=new KnowledgeProcessingConfiguration().documentIndexes(ds,new MockEnvironment());
      var writer=indexes.resolve(7,JSON.createObjectNode());assertThat(writer).isInstanceOf(PostgresIndexRepository.class);
      assertThat(indexes.resolve(7,JSON.createObjectNode())).isSameAs(writer);
      var repo=(PostgresIndexRepository)writer;
      for(JsonNode step:fixture.path("steps")) {
        List<IndexEntry> entries=new ArrayList<>();step.path("inputs").forEach(n->entries.add(SQLiteIndexGoParityTest.entry(n)));
        Map<String,float[]> vectors=new LinkedHashMap<>();step.path("embeddings").fields().forEachRemaining(e->vectors.put(e.getKey(),SQLiteIndexGoParityTest.floats(e.getValue())));
        List<String> ids=SQLiteIndexGoParityTest.strings(step.path("ids"));
        assertThat(repo.estimateStorageSize(entries,vectors)).as("estimate "+mutations).isEqualTo(step.path("estimate").asLong());
        SQLException failure=null;
        try(Connection c=ds.getConnection()) {
          c.setAutoCommit(false);
          try {
            switch(step.path("op").asText()) {
              case "save" -> repo.batchSave(c,entries,vectors);
              case "delete_chunk" -> repo.deleteByChunk(c,ids);
              case "delete_source" -> repo.deleteBySource(c,ids);
              case "delete_knowledge" -> repo.deleteByKnowledge(c,ids);
              case "disable" -> repo.updateEnabled(c,Map.of(ids.getFirst(),false));
              case "tag" -> repo.updateTags(c,Map.of(ids.getFirst(),ids.get(1)));
              case "move" -> repo.move(c,ids.get(0),ids.get(1),ids.get(2));
              case "copy" -> repo.copy(c,"kb",Map.of("doc","copied-doc"),Map.of("a","copied-a","b","copied-b"),"copied-kb");
              default -> throw new AssertionError("Unknown Go operation");
            }
            c.commit();
          } catch(SQLException e) {failure=e;c.rollback();}
        }
        if(step.has("error_state")) {assertThat((Object)failure).as("mutation "+mutations).isNotNull();assertThat(failure.getSQLState()).isEqualTo(step.path("error_state").asText());}
        else assertThat((Object)failure).as("mutation "+mutations).isNull();
        try(Connection c=ds.getConnection()) {SQLiteIndexGoParityTest.equal(rows(c),step.path("rows"),"mutation "+mutations);}
        for(JsonNode q:step.path("queries")) {
          RetrievalQuery query=SQLiteIndexGoParityTest.query(q.path("params"));
          if(q.has("error_state")) {
            assertThatThrownBy(()->repo.retrieve(query)).as("query "+mutations+":"+queries).isInstanceOfSatisfying(SQLException.class,e->assertThat(e.getSQLState()).isEqualTo(q.path("error_state").asText()));queryErrors++;
          } else SQLiteIndexGoParityTest.equal(SQLiteIndexGoParityTest.goResults(repo.retrieve(query)),q.path("result"),"query "+mutations+":"+queries);
          queries++;
        }
        mutations++;
      }
      assertThat(mutations).isEqualTo(16);assertThat(queries).isEqualTo(2368);assertThat(queryErrors).isPositive();
      try(Connection c=ds.getConnection()) {assertThatThrownBy(()->repo.deleteBySource(c,List.of("a"))).isInstanceOf(SQLException.class).hasMessageContaining("fenced transaction");}
    }
  }
}
