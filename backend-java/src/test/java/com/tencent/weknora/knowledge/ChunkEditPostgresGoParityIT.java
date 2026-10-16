package com.tencent.weknora.knowledge;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.retrieval.*;
import com.zaxxer.hikari.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Independently exported actual Go PostgreSQL rows, halfvec bits, HTTP and model requests. */
class ChunkEditPostgresGoParityIT extends ChunkEditGoHttpParityTest {
  private HikariDataSource pool;private PostgresIndexRepository index;
  @Override @TestFactory Stream<DynamicTest> goChunkEdit()throws Exception{var cases=json.readTree(Files.readAllBytes(Path.of(Objects.requireNonNull(System.getProperty("weknora.chunk.edit.pg.fixture")))));assertThat(cases.size()).isEqualTo(fixtureCount());var sequence=new AtomicInteger();return StreamSupport.stream(cases.spliterator(),false).map(row->DynamicTest.dynamicTest("postgres/"+row.path("name").asText(),()->compare(row,sequence.incrementAndGet())));}
  @Override DocumentProcessingCompatibilityTest database(int no)throws Exception {
    String url=Objects.requireNonNull(System.getProperty("weknora.chunk.edit.pg.url"));if(!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/weknora_chunk_edit_parity"))throw new IllegalArgumentException("Owned loopback chunk edit DB required");var config=new HikariConfig();config.setJdbcUrl(url);config.setUsername("postgres");config.setPassword("local-parity-only");config.setMaximumPoolSize(1);config.setMinimumIdle(1);config.setConnectionTimeout(2000);config.addDataSourceProperty("stringtype","unspecified");pool=new HikariDataSource(config);
    var f=new DocumentProcessingCompatibilityTest();f.temp=temp;f.jdbc=new JdbcTemplate(pool);f.manager=new DataSourceTransactionManager(pool);
    for(String trigger:List.of("block_chunk_delete","block_parent_edit"))f.jdbc.execute("DROP TRIGGER IF EXISTS "+trigger+" ON chunks");f.jdbc.execute("DROP TRIGGER IF EXISTS block_edit_revision ON chunk_revisions");f.jdbc.execute("DROP TRIGGER IF EXISTS block_edit_index ON embeddings");f.jdbc.execute("DROP TABLE IF EXISTS chunk_delete_fts");f.jdbc.execute("TRUNCATE tenants,knowledge_bases,knowledges,chunks,models,embeddings,chunk_revisions,organizations,organization_tenant_members,kb_shares,audit_logs,java_task_jobs RESTART IDENTITY CASCADE");f.jdbc.execute("ALTER SEQUENCE chunks_seq_id_seq RESTART WITH 100000000");
    f.jdbc.update("INSERT INTO tenants(id,name,business) VALUES(1,'Owner','test'),(2,'Caller','test')");f.jdbc.update("INSERT INTO knowledge_bases(id,tenant_id,name,type,embedding_model_id,summary_model_id) VALUES('kb',1,'KB','document','','')");index=new PostgresIndexRepository(pool);index.initialize();return f;
  }
  @Override IndexWriter writer(DocumentProcessingCompatibilityTest f){return index;}
  @Override Object seedTime(){return java.sql.Timestamp.from(java.time.Instant.parse("2026-01-01T00:00:00Z"));}
  @Override void metadata(DocumentProcessingCompatibilityTest f,String raw){f.jdbc.update("UPDATE knowledges SET metadata=CAST(? AS JSONB) WHERE id='doc'",raw);}
  @Override void seedPhysicalIndex(DocumentProcessingCompatibilityTest f){f.jdbc.execute("CREATE TABLE chunk_delete_fts(chunk_id TEXT,content TEXT,search TSVECTOR GENERATED ALWAYS AS(to_tsvector('simple',content)) STORED)");f.jdbc.execute("CREATE INDEX chunk_delete_fts_gin ON chunk_delete_fts USING GIN(search)");f.jdbc.update("INSERT INTO chunk_delete_fts(chunk_id,content) VALUES('chunk','Original physical content'),('child','Image content')");}
  @Override List<Map<String,Object>> sqlRows(DocumentProcessingCompatibilityTest f,String sql){var rows=f.jdbc.queryForList(sql.replace("FROM lite_embeddings ","FROM embeddings "));for(var row:rows)for(var entry:row.entrySet()){Object value=entry.getValue();if(value instanceof java.util.Date)entry.setValue(KnowledgeBaseRepository.timestamp(value));else if(value instanceof org.postgresql.util.PGobject)entry.setValue(value.toString());}return rows;}
  @Override List<Map<String,Object>> vecQuery(DocumentProcessingCompatibilityTest f,String sql)throws Exception {
    if(sql.startsWith("SELECT COUNT"))return f.jdbc.queryForList("SELECT COUNT(*) AS count FROM embeddings WHERE dimension=3");
    var rows=f.jdbc.queryForList("SELECT source_id,source_type,embedding::text AS vector FROM embeddings ORDER BY source_id,source_type");for(var row:rows){var values=json.readTree(row.get("vector").toString());var bytes=java.nio.ByteBuffer.allocate(values.size()*4).order(java.nio.ByteOrder.LITTLE_ENDIAN);for(var value:values)bytes.putInt(Float.floatToRawIntBits(value.floatValue()));row.put("vector",bytes.array());}return rows;
  }
  @Override int keywordCount(DocumentProcessingCompatibilityTest f){return f.jdbc.queryForObject("SELECT COUNT(*) FROM embeddings",Integer.class);}
  private void trigger(DocumentProcessingCompatibilityTest f,String name,String table,String event,String message,boolean parent){f.jdbc.execute("CREATE OR REPLACE FUNCTION "+name+"_fn() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION '"+message+"'; END $$");f.jdbc.execute("CREATE TRIGGER "+name+" BEFORE "+event+" ON "+table+" FOR EACH ROW"+(parent?" WHEN (OLD.id='parent')":"")+" EXECUTE FUNCTION "+name+"_fn()");}
  @Override void indexFailure(DocumentProcessingCompatibilityTest f){trigger(f,"block_edit_index","embeddings","INSERT","controlled edit index failure",false);}
  @Override void revisionFailure(DocumentProcessingCompatibilityTest f){trigger(f,"block_edit_revision","chunk_revisions","INSERT","controlled edit revision failure",false);}
  @Override void parentFailure(DocumentProcessingCompatibilityTest f){trigger(f,"block_parent_edit","chunks","UPDATE OF content","controlled parent edit failure",true);}
  @Override void writeFailure(DocumentProcessingCompatibilityTest f){trigger(f,"block_chunk_delete","chunks","UPDATE OF deleted_at","controlled chunk write failure",false);}
  @Override void extraVariants(DocumentProcessingCompatibilityTest f,String variant){
    // PostgreSQL JSONB always reaches the Go scanner as bytes, independent of fixture bind type.
    if(variant.endsWith("-blob")){String strategy=switch(variant){case "vector-only-blob"->"{\"vector_enabled\":true}";case "keyword-only-blob"->"{\"keyword_enabled\":true}";case "wiki-only-blob"->"{\"wiki_enabled\":true}";case "invalid-index-blob"->"{\"vector_enabled\":4}";default->"{\"vector_enabled\":false,\"keyword_enabled\":false,\"graph_enabled\":false,\"wiki_enabled\":false}";};super.extraVariants(f,"non-blob-seed");f.jdbc.update("UPDATE knowledge_bases SET indexing_strategy=CAST(? AS JSONB) WHERE id='kb'",strategy);}else super.extraVariants(f,variant);
  }
  @Override void release(DocumentProcessingCompatibilityTest f){if(pool!=null)pool.close();}
}
