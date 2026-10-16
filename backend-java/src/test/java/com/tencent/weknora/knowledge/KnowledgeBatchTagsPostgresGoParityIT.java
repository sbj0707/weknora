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

/** Actual independent Go PostgreSQL HTTP oracle on all versioned migrations and pool one. */
class KnowledgeBatchTagsPostgresGoParityIT extends KnowledgeBatchTagsGoHttpParityTest {
  private HikariDataSource pool;

  @Override
  @TestFactory
  Stream<DynamicTest> folders() throws Exception {
    var cases =
        json.readTree(
            Files.readAllBytes(
                Path.of(
                    Objects.requireNonNull(System.getProperty("weknora.batch.tags.pg.fixture")))));
    assertThat(cases.size()).isEqualTo(96);
    var sequence = new AtomicInteger();
    return StreamSupport.stream(cases.spliterator(), false)
        .filter(row -> !row.path("pg_unsupported_invalid_json").asBoolean())
        .map(
            row ->
                DynamicTest.dynamicTest(
                    "postgres/" + row.path("name").asText(),
                    () -> compare(row, sequence.incrementAndGet())));
  }

  @Override
  DocumentProcessingCompatibilityTest database(int no) throws Exception {
    String url = Objects.requireNonNull(System.getProperty("weknora.batch.tags.pg.url"));
    if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/weknora_batch_tags_parity"))
      throw new IllegalArgumentException("Owned loopback batch tags database required");
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
    restore(f);
    f.jdbc.execute("DROP TRIGGER IF EXISTS block_batch_tags ON knowledge_tag_relations");
    f.jdbc.execute("DROP TABLE IF EXISTS batch_tags_physical_index");
    f.jdbc.execute(
        "TRUNCATE"
            + " tenants,knowledge_bases,knowledges,knowledge_tags,knowledge_tag_relations,organizations,organization_tenant_members,kb_shares,audit_logs,java_task_jobs"
            + " RESTART IDENTITY CASCADE");
    return f;
  }

  private void restore(DocumentProcessingCompatibilityTest f) {
    for (String table : List.of("knowledges", "knowledge_tags", "knowledge_tag_relations"))
      if (Boolean.TRUE.equals(
          f.jdbc.queryForObject(
              "SELECT to_regclass('hidden_" + table + "') IS NOT NULL", Boolean.class)))
        f.jdbc.execute("ALTER TABLE hidden_" + table + " RENAME TO " + table);
  }

  @Override
  void release(DocumentProcessingCompatibilityTest f) {
    if (pool != null)
      try {
        restore(f);
      } finally {
        pool.close();
        pool = null;
      }
  }
}
