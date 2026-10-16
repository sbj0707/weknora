package com.tencent.weknora.knowledge;

import static org.assertj.core.api.Assertions.*;
import com.tencent.weknora.retrieval.*;
import com.tencent.weknora.task.TaskRepository;
import com.zaxxer.hikari.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Actual Go PostgreSQL oracle, full Go schema, native halfvec and one pooled connection. */
class KnowledgeSummaryPublicPostgresGoParityIT extends KnowledgeSummaryPublicGoHttpParityTest {
  private HikariDataSource pool;
  private PostgresIndexRepository index;
  @Override @TestFactory Stream<DynamicTest> folders()throws Exception{
    var cases=json.readTree(Files.readAllBytes(Path.of(Objects.requireNonNull(System.getProperty("weknora.summary.public.pg.fixture")))));
    assertThat(cases.size()).isEqualTo(56);var sequence=new AtomicInteger();
    return StreamSupport.stream(cases.spliterator(),false).map(row->DynamicTest.dynamicTest("postgres/"+row.path("name").asText(),()->compare(row,sequence.incrementAndGet())));
  }
  @Override DocumentProcessingCompatibilityTest database(int no)throws Exception{
    String url=Objects.requireNonNull(System.getProperty("weknora.summary.public.pg.url"));
    if(!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/weknora_summary_public_parity"))throw new IllegalArgumentException("Owned loopback summary database required");
    var config=new HikariConfig();config.setJdbcUrl(url);config.setUsername("postgres");config.setPassword("local-parity-only");config.setMaximumPoolSize(1);config.setMinimumIdle(1);config.setConnectionTimeout(2000);config.addDataSourceProperty("stringtype","unspecified");pool=new HikariDataSource(config);
    var f=new DocumentProcessingCompatibilityTest();f.temp=temp;f.jdbc=new JdbcTemplate(pool);f.manager=new DataSourceTransactionManager(pool);f.tasks=new TaskRepository(f.jdbc,json,f.manager);
    f.jdbc.execute("DROP TRIGGER IF EXISTS fail_chunk ON chunks");f.jdbc.execute("DROP TRIGGER IF EXISTS fail_index ON embeddings");
    f.jdbc.execute("TRUNCATE tenants,knowledge_bases,knowledges,chunks,models,embeddings,organizations,organization_tenant_members,kb_shares,audit_logs,java_task_jobs RESTART IDENTITY CASCADE");
    f.jdbc.execute("ALTER SEQUENCE chunks_seq_id_seq RESTART WITH 100000000");
    f.jdbc.update("INSERT INTO tenants(id,name,business) VALUES(1,'Owner','test'),(2,'Caller','test')");f.jdbc.update("INSERT INTO knowledge_bases(id,tenant_id,name,type,embedding_model_id,summary_model_id) VALUES('kb',1,'KB','document','','')");
    index=new PostgresIndexRepository(pool);index.initialize();return f;
  }
  @Override IndexWriter writer(DocumentProcessingCompatibilityTest f){return index;}
  @Override Object seedTime(){return java.sql.Timestamp.from(java.time.Instant.parse("2026-01-01T00:00:00Z"));}
  @Override void metadata(DocumentProcessingCompatibilityTest f,String raw){f.jdbc.update("UPDATE knowledges SET metadata=?::jsonb WHERE id='d1'",raw);}
  @Override void custom(DocumentProcessingCompatibilityTest f,String raw){f.jdbc.update("UPDATE knowledges SET custom_metadata=?::jsonb WHERE id='d1'",raw);}
  @Override List<Map<String,Object>> indexRows(DocumentProcessingCompatibilityTest f){return f.jdbc.queryForList("SELECT source_id,source_type,chunk_id,knowledge_id,knowledge_base_id,content,dimension,is_enabled FROM embeddings ORDER BY source_id");}
  @Override void chunkFailure(DocumentProcessingCompatibilityTest f){trigger(f,"fail_chunk","chunks","synthetic chunk failure");}
  @Override void indexFailure(DocumentProcessingCompatibilityTest f){trigger(f,"fail_index","embeddings","synthetic index failure");}
  private void trigger(DocumentProcessingCompatibilityTest f,String name,String table,String message){f.jdbc.execute("CREATE OR REPLACE FUNCTION "+name+"_fn() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION '"+message+"'; END $$");f.jdbc.execute("CREATE TRIGGER "+name+" BEFORE INSERT ON "+table+" FOR EACH ROW EXECUTE FUNCTION "+name+"_fn()");}
  @Override void release(DocumentProcessingCompatibilityTest f){if(pool!=null)pool.close();}
}
