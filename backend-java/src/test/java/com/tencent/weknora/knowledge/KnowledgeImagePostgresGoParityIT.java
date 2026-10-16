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

/** Independent Go PostgreSQL oracle using full migrated schema and one pooled connection. */
class KnowledgeImagePostgresGoParityIT extends KnowledgeImageGoHttpParityTest {
  private HikariDataSource pool;

  @Override
  @TestFactory
  Stream<DynamicTest> folders() throws Exception {
    var rows =
        json.readTree(
            Files.readAllBytes(
                Path.of(Objects.requireNonNull(System.getProperty("weknora.image.pg.fixture")))));
    assertThat(rows.size()).isEqualTo(92);
    var n = new AtomicInteger();
    return StreamSupport.stream(rows.spliterator(), false)
        .map(
            row ->
                DynamicTest.dynamicTest(
                    "postgres/" + row.path("name").asText(),
                    () -> compare(row, n.incrementAndGet())));
  }

  @Override
  DocumentProcessingCompatibilityTest database(int no) {
    String url = Objects.requireNonNull(System.getProperty("weknora.image.pg.url"));
    if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/weknora_image_parity"))
      throw new IllegalArgumentException("Owned loopback image database required");
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
    f.jdbc.execute(
        "TRUNCATE"
            + " tenants,knowledge_bases,knowledges,chunks,chunk_images,users,system_settings,organizations,organization_tenant_members,kb_shares,audit_logs,java_task_jobs"
            + " RESTART IDENTITY CASCADE");
    return f;
  }

  @Override
  boolean postgres() {
    return true;
  }

  @Override
  Object seedTime() {
    return java.sql.Timestamp.from(java.time.Instant.parse("2026-01-01T00:00:00Z"));
  }

  private void restore(DocumentProcessingCompatibilityTest f) {
    for (var entry :
        Map.of(
                "block_add",
                "chunks",
                "block_parent",
                "chunks",
                "block_child",
                "chunks",
                "block_hash",
                "knowledges")
            .entrySet())
      f.jdbc.execute("DROP TRIGGER IF EXISTS " + entry.getKey() + " ON " + entry.getValue());
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
