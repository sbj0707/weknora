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
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.FileSystemResource;

/** Runner-owned disposable PostgreSQL, all Go migrations, actual PG Go responses and pool one. */
class ChunkLifecyclePostgresGoParityIT extends ChunkLifecycleGoHttpParityTest {
 private HikariDataSource pool;
 @Override @TestFactory Stream<DynamicTest> goChunkLifecycle()throws Exception{JsonNode cases=json.readTree(Files.readAllBytes(Path.of(Objects.requireNonNull(System.getProperty("weknora.chunk.pg.fixture")))));assertThat(cases.size()).isEqualTo(117);var seq=new AtomicInteger();return StreamSupport.stream(cases.spliterator(),false).map(row->DynamicTest.dynamicTest("postgres/"+row.path("name").asText(),()->compare(row,seq.incrementAndGet())));}
 @Override DocumentProcessingCompatibilityTest database(int no)throws Exception{String url=Objects.requireNonNull(System.getProperty("weknora.chunk.pg.url"));if(!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/weknora_chunk_parity"))throw new IllegalArgumentException("Owned loopback chunk parity database required");var config=new HikariConfig();config.setJdbcUrl(url);config.setUsername("postgres");config.setPassword("local-parity-only");config.setMaximumPoolSize(1);config.setMinimumIdle(1);config.setConnectionTimeout(2000);pool=new HikariDataSource(config);var f=new DocumentProcessingCompatibilityTest();f.temp=temp;f.jdbc=new JdbcTemplate(pool);f.manager=new DataSourceTransactionManager(pool);f.tasks=new TaskRepository(f.jdbc,json,f.manager);
  try(var connection=pool.getConnection()){ScriptUtils.executeSqlScript(connection,new FileSystemResource("../migrations/versioned/000078_chunk_editing_and_custom_metadata.up.sql"));}
  f.jdbc.execute("DROP TRIGGER IF EXISTS block_chunk_delete ON chunks");f.jdbc.execute("DROP TABLE IF EXISTS chunk_delete_fts");f.jdbc.execute("TRUNCATE tenants,knowledge_bases,knowledges,chunks,chunk_revisions,organizations,organization_tenant_members,kb_shares,audit_logs,java_task_jobs RESTART IDENTITY CASCADE");
  f.jdbc.update("INSERT INTO tenants(id,name,business) VALUES(1,'owner','isolated'),(2,'caller','isolated')");f.jdbc.update("INSERT INTO knowledge_bases(id,tenant_id,name,type,embedding_model_id,summary_model_id) VALUES('kb',1,'KB','document','','')");return f;
 }
 @Override Object seedTime(){return java.sql.Timestamp.from(java.time.Instant.parse("2026-01-01T00:00:00Z"));}
 @Override void metadata(DocumentProcessingCompatibilityTest f,String raw){f.jdbc.update("UPDATE knowledges SET metadata=CAST(? AS JSONB) WHERE id='doc'",raw);}
 @Override void seedPhysicalIndex(DocumentProcessingCompatibilityTest f){f.jdbc.execute("CREATE TABLE chunk_delete_fts(chunk_id TEXT,content TEXT,search TSVECTOR GENERATED ALWAYS AS (to_tsvector('simple',content)) STORED)");f.jdbc.execute("CREATE INDEX chunk_delete_fts_gin ON chunk_delete_fts USING GIN(search)");f.jdbc.update("INSERT INTO chunk_delete_fts(chunk_id,content) VALUES('chunk','Original physical content'),('child','Image content')");}
 @Override void writeFailure(DocumentProcessingCompatibilityTest f){f.jdbc.execute("CREATE OR REPLACE FUNCTION block_chunk_delete() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'controlled chunk write failure'; END $$");f.jdbc.execute("CREATE TRIGGER block_chunk_delete BEFORE UPDATE OF deleted_at ON chunks FOR EACH ROW EXECUTE FUNCTION block_chunk_delete()");}
 @Override void release(DocumentProcessingCompatibilityTest f){if(pool!=null){pool.close();pool=null;}}
}
