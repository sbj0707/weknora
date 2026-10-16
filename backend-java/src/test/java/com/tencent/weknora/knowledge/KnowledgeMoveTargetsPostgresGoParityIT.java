package com.tencent.weknora.knowledge;

import static org.assertj.core.api.Assertions.*;

import com.tencent.weknora.task.TaskRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.zaxxer.hikari.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Independent Go PostgreSQL oracle using full migrated schema and one pooled connection. */
class KnowledgeMoveTargetsPostgresGoParityIT extends KnowledgeMoveTargetsGoHttpParityTest {
  private HikariDataSource pool;

  @Override JsonNode rows() throws Exception {
    return json.readTree(Files.readAllBytes(Path.of(Objects.requireNonNull(System.getProperty("weknora.move.targets.pg.fixture")))));
  }

  @Override
  DocumentProcessingCompatibilityTest database(int no) {
    String url = Objects.requireNonNull(System.getProperty("weknora.move.targets.pg.url"));
    if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/weknora_move_targets_parity"))
      throw new IllegalArgumentException("Owned loopback gallery database required");
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
            + " tenants,knowledge_bases,knowledges,chunks,users,user_kb_pins,vector_stores,organizations,organization_tenant_members,kb_shares,audit_logs,java_task_jobs,auth_tokens,tenant_members,tenant_api_keys"
            + " RESTART IDENTITY CASCADE");
    return f;
  }

  private void restore(DocumentProcessingCompatibilityTest f) {
    for (String table : List.of("knowledge_bases", "knowledges", "chunks", "user_kb_pins", "users", "vector_stores"))
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
