package com.tencent.weknora.knowledge;

import static org.assertj.core.api.Assertions.*;
import com.tencent.weknora.task.TaskRepository;
import com.zaxxer.hikari.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Independently exported PostgreSQL oracle, full migrated schema and a single pooled connection. */
class KnowledgeFilePostgresGoParityIT extends KnowledgeFileGoHttpParityTest {
  private HikariDataSource pool;
  @Override @TestFactory Stream<DynamicTest> folders()throws Exception {
    var rows=json.readTree(Files.readAllBytes(Path.of(Objects.requireNonNull(System.getProperty("weknora.file.pg.fixture")))));
    assertThat(rows.size()).isEqualTo(209);var sequence=new AtomicInteger();
    // Invalid JSON cannot be persisted in the production PostgreSQL jsonb column (two SQLite-only cases).
    return StreamSupport.stream(rows.spliterator(),false).filter(r->!r.path("pg_unsupported_invalid_json").asBoolean()).map(row->DynamicTest.dynamicTest("postgres/"+row.path("name").asText(),()->compare(row,sequence.incrementAndGet())));
  }
  @Override DocumentProcessingCompatibilityTest database(int no)throws Exception {
    String url=Objects.requireNonNull(System.getProperty("weknora.file.pg.url"));
    if(!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/weknora_file_parity"))throw new IllegalArgumentException("Owned loopback file database required");
    var config=new HikariConfig();config.setJdbcUrl(url);config.setUsername("postgres");config.setPassword("local-parity-only");config.setMaximumPoolSize(1);config.setMinimumIdle(1);config.setConnectionTimeout(2000);config.addDataSourceProperty("stringtype","unspecified");pool=new HikariDataSource(config);
    var f=new DocumentProcessingCompatibilityTest();f.temp=temp;f.jdbc=new JdbcTemplate(pool);f.manager=new DataSourceTransactionManager(pool);
    f.jdbc.execute("TRUNCATE tenants,knowledge_bases,knowledges,chunks,models,organizations,organization_tenant_members,kb_shares,agent_shares,custom_agents,audit_logs,java_task_jobs RESTART IDENTITY CASCADE");f.tasks=new TaskRepository(f.jdbc,json,f.manager);return f;
  }
  @Override void release(DocumentProcessingCompatibilityTest f){if(pool!=null)pool.close();}
}
