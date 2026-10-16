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

/** Explicit live PG test; runner owns the disposable DB and all115 Go migrations. */
class KnowledgeDeletePostgresGoParityIT extends KnowledgeDeleteGoHttpParityTest {
 private HikariDataSource pool;
 @Override @TestFactory Stream<DynamicTest> goDeleteAdmission()throws Exception{JsonNode cases=json.readTree(Files.readAllBytes(Path.of(Objects.requireNonNull(System.getProperty("weknora.delete.pg.fixture")))));assertThat(cases.size()).isEqualTo(caseCount());var seq=new AtomicInteger();return StreamSupport.stream(cases.spliterator(),false).map(row->DynamicTest.dynamicTest("postgres/"+row.path("name").asText(),()->compare(row,seq.incrementAndGet())));}
 @Override DocumentProcessingCompatibilityTest database(int no){String url=Objects.requireNonNull(System.getProperty("weknora.delete.pg.url"));if(!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/weknora_delete_parity"))throw new IllegalArgumentException("Owned loopback tag parity database required");var config=new HikariConfig();config.setJdbcUrl(url);config.setUsername("postgres");config.setPassword("local-parity-only");config.setMaximumPoolSize(1);config.setMinimumIdle(1);config.setConnectionTimeout(2000);pool=new HikariDataSource(config);var f=new DocumentProcessingCompatibilityTest();f.temp=temp;f.jdbc=new JdbcTemplate(pool);f.manager=new DataSourceTransactionManager(pool);f.tasks=new TaskRepository(f.jdbc,json,f.manager);
  f.jdbc.execute("DROP TRIGGER IF EXISTS fail_delete_queue ON java_task_jobs");f.jdbc.execute("TRUNCATE tenants,knowledge_bases,knowledge_tags,knowledge_tag_relations,knowledges,tenant_api_keys,chunks,organizations,organization_tenant_members,kb_shares,audit_logs,java_task_jobs RESTART IDENTITY CASCADE");f.jdbc.execute("ALTER SEQUENCE knowledge_tags_seq_id_seq RESTART WITH 10000000");
  f.jdbc.update("INSERT INTO tenants(id,name,business) VALUES(1,'owner','isolated'),(2,'caller','isolated')");f.jdbc.update("INSERT INTO knowledge_bases(id,tenant_id,name,type,embedding_model_id,summary_model_id) VALUES('kb',1,'KB','faq','embedding','')");return f;
 }
 @Override Object seedTime(int minute){return java.sql.Timestamp.from(java.time.Instant.parse(String.format("2026-01-01T00:0%d:00Z",minute)));}
 @Override void metadata(DocumentProcessingCompatibilityTest f,Object raw){String value=raw instanceof byte[] bytes?new String(bytes,java.nio.charset.StandardCharsets.UTF_8):raw.toString();f.jdbc.update("UPDATE knowledges SET metadata=CAST(? AS JSONB) WHERE id='doc'",value);}
 @Override void queueFailure(DocumentProcessingCompatibilityTest f){f.jdbc.execute("CREATE OR REPLACE FUNCTION fail_delete_queue() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'controlled enqueue failure'; END $$");f.jdbc.execute("CREATE TRIGGER fail_delete_queue BEFORE INSERT ON java_task_jobs FOR EACH ROW EXECUTE FUNCTION fail_delete_queue()");}
 @Override void release(DocumentProcessingCompatibilityTest f){if(pool!=null){pool.close();pool=null;}}
}
