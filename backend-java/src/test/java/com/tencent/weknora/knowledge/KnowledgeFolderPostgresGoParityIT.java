package com.tencent.weknora.knowledge;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.tencent.weknora.task.TaskRepository;
import com.zaxxer.hikari.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Actual separately exported Go PostgreSQL oracle on a runner-owned DB, full schema and pool one. */
class KnowledgeFolderPostgresGoParityIT extends KnowledgeFolderGoHttpParityTest {
 private HikariDataSource pool;
 @Override @TestFactory Stream<DynamicTest> folders()throws Exception{JsonNode cases=json.readTree(Files.readAllBytes(Path.of(Objects.requireNonNull(System.getProperty("weknora.folder.pg.fixture")))));assertThat(cases.size()).isEqualTo(151);var sequence=new AtomicInteger();return StreamSupport.stream(cases.spliterator(),false).map(row->DynamicTest.dynamicTest("postgres/"+row.path("name").asText(),()->compare(row,sequence.incrementAndGet())));}
 @Override DocumentProcessingCompatibilityTest database(int no)throws Exception {
  String url=Objects.requireNonNull(System.getProperty("weknora.folder.pg.url"));if(!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/weknora_folder_parity"))throw new IllegalArgumentException("Owned loopback folder parity database required");
  var config=new HikariConfig();config.setJdbcUrl(url);config.setUsername("postgres");config.setPassword("local-parity-only");config.setMaximumPoolSize(1);config.setMinimumIdle(1);config.setConnectionTimeout(2000);pool=new HikariDataSource(config);
  var f=new DocumentProcessingCompatibilityTest();f.temp=temp;f.jdbc=new JdbcTemplate(pool);f.manager=new DataSourceTransactionManager(pool);f.tasks=new TaskRepository(f.jdbc,json,f.manager);
  f.jdbc.execute("DROP TRIGGER IF EXISTS block_folder ON knowledges");f.jdbc.execute("DROP TABLE IF EXISTS folder_physical_fts");f.jdbc.execute("TRUNCATE tenants,knowledge_bases,knowledges,chunks,organizations,organization_tenant_members,kb_shares,audit_logs,java_task_jobs RESTART IDENTITY CASCADE");
  f.jdbc.update("INSERT INTO tenants(id,name,business) VALUES(1,'owner','isolated'),(2,'caller','isolated')");f.jdbc.update("INSERT INTO knowledge_bases(id,tenant_id,name,type,embedding_model_id,summary_model_id) VALUES('kb',1,'KB','document','','')");return f;
 }
 @Override Object seedTime(){return java.sql.Timestamp.from(java.time.Instant.parse("2026-01-01T00:00:00Z"));}
 @Override void metadata(DocumentProcessingCompatibilityTest f,String raw){f.jdbc.update("UPDATE knowledges SET metadata=CAST(? AS JSONB) WHERE id='d1'",raw);}
 @Override void seedPhysicalIndex(DocumentProcessingCompatibilityTest f){f.jdbc.execute("CREATE TABLE folder_physical_fts(chunk_id TEXT,content TEXT,search TSVECTOR GENERATED ALWAYS AS (to_tsvector('simple',content)) STORED)");f.jdbc.execute("CREATE INDEX folder_physical_fts_gin ON folder_physical_fts USING GIN(search)");f.jdbc.update("INSERT INTO folder_physical_fts(chunk_id,content) VALUES('physical','Unchanged source')");}
 @Override void writeFailure(DocumentProcessingCompatibilityTest f){f.jdbc.execute("CREATE OR REPLACE FUNCTION block_folder() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'controlled folder write failure'; END $$");f.jdbc.execute("CREATE TRIGGER block_folder BEFORE UPDATE OF folder_path ON knowledges FOR EACH ROW WHEN (OLD.id='d2') EXECUTE FUNCTION block_folder()");}
 @Override void release(DocumentProcessingCompatibilityTest f){if(pool!=null){try{if(Boolean.TRUE.equals(f.jdbc.queryForObject("SELECT to_regclass('hidden_knowledges') IS NOT NULL",Boolean.class)))f.jdbc.execute("ALTER TABLE hidden_knowledges RENAME TO knowledges");}finally{pool.close();pool=null;}}}
}
