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

/** Explicit owned PostgreSQL replay; does not run in the default unit test suite. */
class ManualKnowledgePostgresGoParityIT extends ManualKnowledgeGoHttpParityTest {
  private HikariDataSource pool;

  @Override
  @TestFactory
  Stream<DynamicTest> actualGoManualKnowledge() throws Exception {
    var cases =
        json.readTree(
            Files.readAllBytes(
                Path.of(Objects.requireNonNull(System.getProperty("weknora.manual.pg.fixture")))));
    assertThat(cases.size()).isEqualTo(caseCount());
    var seq = new AtomicInteger();
    return StreamSupport.stream(cases.spliterator(), false)
        .map(
            c ->
                DynamicTest.dynamicTest(
                    "postgres/" + c.path("name").asText(),
                    () -> compare(c, seq.incrementAndGet())));
  }

  @Override
  DocumentProcessingCompatibilityTest database(int no) throws Exception {
    String url = Objects.requireNonNull(System.getProperty("weknora.manual.pg.url"));
    if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/weknora_manual_parity"))
      throw new IllegalArgumentException("Owned loopback manual parity database required");
    var config = new HikariConfig();
    config.setJdbcUrl(url);
    config.setUsername("postgres");
    config.setPassword("local-parity-only");
    config.setMaximumPoolSize(1);
    config.setMinimumIdle(1);
    config.setConnectionTimeout(2000);
    config.addDataSourceProperty("stringtype", "unspecified");
    pool = new HikariDataSource(config);
    var f = new DocumentProcessingCompatibilityTest();
    f.temp = temp;
    f.jdbc = new JdbcTemplate(pool);
    f.manager = new DataSourceTransactionManager(pool);
    f.tasks = new TaskRepository(f.jdbc, json, f.manager);
    f.knowledge = new KnowledgeRepository(f.jdbc, json, f.manager);
    f.jdbc.execute(
        "TRUNCATE"
            + " tenants,knowledge_bases,knowledge_tags,knowledge_tag_relations,knowledges,tenant_api_keys,chunks,organizations,organization_tenant_members,kb_shares,audit_logs,java_task_jobs"
            + " RESTART IDENTITY CASCADE");
    f.jdbc.execute("ALTER SEQUENCE knowledge_tags_seq_id_seq RESTART WITH 10000000");
    f.jdbc.update(
        "INSERT INTO tenants(id,name,business)"
            + " VALUES(1,'owner','isolated'),(2,'caller','isolated')");
    f.jdbc.update(
        "INSERT INTO knowledge_bases(id,tenant_id,name,type,embedding_model_id,summary_model_id)"
            + " VALUES('kb',1,'KB','document','embedding','')");
    return f;
  }

  @Override
  Object seedTime() {
    return java.sql.Timestamp.from(java.time.Instant.parse("2026-01-01T00:00:00Z"));
  }

  @Override
  void release(DocumentProcessingCompatibilityTest f) {
    if (pool != null) {
      pool.close();
      pool = null;
    }
  }
}
