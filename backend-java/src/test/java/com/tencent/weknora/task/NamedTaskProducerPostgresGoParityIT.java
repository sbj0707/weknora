package com.tencent.weknora.task;

import com.zaxxer.hikari.*;
import java.util.Objects;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Retention oracle plus simultaneous PostgreSQL unique-key races using eight connections. */
class NamedTaskProducerPostgresGoParityIT extends NamedTaskProducerGoParityTest {
  HikariDataSource pool;

  @Override
  TaskRepositoryTest database() {
    String url = Objects.requireNonNull(System.getProperty("weknora.move.worker.pg.url"));
    if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/weknora_move_plan_parity"))
      throw new IllegalArgumentException("Owned loopback named task database required");
    var config = new HikariConfig();
    config.setJdbcUrl(url);
    config.setUsername("postgres");
    config.setPassword("local-parity-only");
    config.setMaximumPoolSize(8);
    config.setMinimumIdle(8);
    config.setConnectionTimeout(5000);
    config.addDataSourceProperty("stringtype", "unspecified");
    pool = new HikariDataSource(config);
    var fixture = new TaskRepositoryTest();
    fixture.jdbc = new JdbcTemplate(pool);
    fixture.manager = new DataSourceTransactionManager(pool);
    fixture.tasks = new TaskRepository(fixture.jdbc, fixture.json, fixture.manager, fixture.clock);
    fixture.jdbc.execute("DROP TRIGGER IF EXISTS reject_named_key ON java_task_named_keys");
    fixture.jdbc.execute("TRUNCATE java_task_jobs RESTART IDENTITY CASCADE");
    return fixture;
  }

  @Override
  void rejectKeyInsertion() {
    f.jdbc.execute(
        "CREATE OR REPLACE FUNCTION java_reject_named_key() RETURNS TRIGGER LANGUAGE plpgsql AS $$"
            + " BEGIN RAISE EXCEPTION 'controlled named-key insert failure'; END $$");
    f.jdbc.execute(
        "CREATE TRIGGER reject_named_key BEFORE INSERT ON java_task_named_keys FOR EACH ROW EXECUTE"
            + " FUNCTION java_reject_named_key()");
  }

  @Override
  @AfterEach
  void cleanup() {
    if (pool != null) pool.close();
  }
}
