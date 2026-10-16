package com.tencent.weknora.knowledge;

import com.zaxxer.hikari.*;
import java.util.Objects;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * Independently execute Go and Java against all production PostgreSQL migrations, pool size one.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KnowledgeMoveExecutionPostgresGoParityIT extends KnowledgeMoveExecutionGoParityTest {
  private HikariDataSource pool;

  @Override
  DocumentProcessingCompatibilityTest database() {
    if (pool == null) {
      String url = Objects.requireNonNull(System.getProperty("weknora.move.execution.pg.url"));
      if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/weknora_move_plan_parity"))
        throw new IllegalArgumentException("Owned loopback move execution database required");
      var config = new HikariConfig();
      config.setJdbcUrl(url);
      config.setUsername("postgres");
      config.setPassword("local-parity-only");
      config.setMaximumPoolSize(1);
      config.setMinimumIdle(1);
      config.setConnectionTimeout(2000);
      config.addDataSourceProperty("stringtype", "unspecified");
      pool = new HikariDataSource(config);
    }
    var f = new DocumentProcessingCompatibilityTest();
    f.temp = temp;
    f.jdbc = new JdbcTemplate(pool);
    f.manager = new DataSourceTransactionManager(pool);
    for (String table :
        java.util.List.of("tenants", "knowledges", "chunks", "knowledge_tag_relations"))
      f.jdbc.execute("DROP TRIGGER IF EXISTS java_move_fault ON " + table);
    f.jdbc.execute(
        "TRUNCATE tenants,knowledges,chunks,knowledge_tags,knowledge_tag_relations,java_task_jobs"
            + " RESTART IDENTITY CASCADE");
    return f;
  }

  @AfterAll
  void close() {
    if (pool != null) pool.close();
  }
}
