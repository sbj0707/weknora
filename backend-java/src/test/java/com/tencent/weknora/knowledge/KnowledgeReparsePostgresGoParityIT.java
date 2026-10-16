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

/** Separately exported actual Go/PostgreSQL oracle; full schema, native halfvec/FTS, one pooled connection. */
class KnowledgeReparsePostgresGoParityIT extends KnowledgeReparseGoHttpParityTest {
  private HikariDataSource pool;
  private PostgresIndexRepository index;
  @Override @TestFactory Stream<DynamicTest> folders()throws Exception{
    var cases=json.readTree(Files.readAllBytes(Path.of(Objects.requireNonNull(System.getProperty("weknora.reparse.pg.fixture")))));
    assertThat(cases.size()).isEqualTo(100);var sequence=new AtomicInteger();
    return StreamSupport.stream(cases.spliterator(),false).map(row->DynamicTest.dynamicTest("postgres/"+row.path("name").asText(),()->compare(row,sequence.incrementAndGet())));
  }
  @Override DocumentProcessingCompatibilityTest database(int no)throws Exception{
    String url=Objects.requireNonNull(System.getProperty("weknora.reparse.pg.url"));
    if(!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/weknora_reparse_parity"))throw new IllegalArgumentException("Owned loopback reparse database required");
    var config=new HikariConfig();config.setJdbcUrl(url);config.setUsername("postgres");config.setPassword("local-parity-only");config.setMaximumPoolSize(1);config.setMinimumIdle(1);config.setConnectionTimeout(2000);config.addDataSourceProperty("stringtype","unspecified");pool=new HikariDataSource(config);
    var f=new DocumentProcessingCompatibilityTest();f.temp=temp;f.jdbc=new JdbcTemplate(pool);f.manager=new DataSourceTransactionManager(pool);f.tasks=new TaskRepository(f.jdbc,json,f.manager);f.knowledge=new KnowledgeRepository(f.jdbc,json,f.manager);
    f.jdbc.execute("DROP TRIGGER IF EXISTS fail_chunk ON chunks");f.jdbc.execute("DROP TRIGGER IF EXISTS fail_index ON embeddings");f.jdbc.execute("DROP TRIGGER IF EXISTS fail_counter ON knowledges");
    f.jdbc.execute("TRUNCATE tenants,knowledge_bases,knowledges,chunks,models,embeddings,organizations,organization_tenant_members,kb_shares,audit_logs,knowledge_processing_spans,task_pending_ops,java_task_jobs RESTART IDENTITY CASCADE");
    f.jdbc.execute("ALTER SEQUENCE chunks_seq_id_seq RESTART WITH 100000000");
    index=new PostgresIndexRepository(pool);index.initialize();return f;
  }
  @Override void migratePending(DocumentProcessingCompatibilityTest f){}
  @Override IndexWriter writer(DocumentProcessingCompatibilityTest f){return index;}
  @Override List<Map<String,Object>> indexRows(DocumentProcessingCompatibilityTest f){return f.jdbc.queryForList("SELECT source_id,source_type,chunk_id,knowledge_id,knowledge_base_id,content,dimension,is_enabled FROM embeddings ORDER BY source_id");}
  @Override void executeSeed(DocumentProcessingCompatibilityTest f,String sql,Object[] args){
    if(sql.startsWith("CREATE TRIGGER fail_chunk ")||sql.startsWith("CREATE TRIGGER fail_counter ")){
      boolean counter=sql.startsWith("CREATE TRIGGER fail_counter ");String name=counter?"fail_counter":"fail_chunk";
      f.jdbc.execute("CREATE OR REPLACE FUNCTION "+name+"_fn() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic "+(counter?"counter":"chunk")+" failure'; END $$");
      f.jdbc.execute("CREATE TRIGGER "+name+" BEFORE UPDATE "+(counter?"OF pending_subtasks_count ON knowledges":"ON chunks")+" FOR EACH ROW EXECUTE FUNCTION "+name+"_fn()");return;
    }
    f.jdbc.update(sql,args);
  }
  @Override void release(DocumentProcessingCompatibilityTest f){if(pool!=null)pool.close();}
}
