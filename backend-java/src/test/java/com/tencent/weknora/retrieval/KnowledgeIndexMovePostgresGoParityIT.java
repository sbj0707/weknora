package com.tencent.weknora.retrieval;

import com.zaxxer.hikari.*;
import java.util.Objects;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Native halfvec/ParadeDB metadata moves using all production migrations and a single connection.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KnowledgeIndexMovePostgresGoParityIT extends KnowledgeIndexMoveGoParityTest {
  private HikariDataSource pool;

  @Override
  Database database() throws Exception {
    if (pool == null) {
      String url = Objects.requireNonNull(System.getProperty("weknora.index.move.pg.url"));
      if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/weknora_index_move_parity"))
        throw new IllegalArgumentException("Owned loopback index move database required");
      var config = new HikariConfig();
      config.setJdbcUrl(url);
      config.setUsername("postgres");
      config.setPassword("local-parity-only");
      config.setMaximumPoolSize(1);
      config.setMinimumIdle(1);
      config.setConnectionTimeout(2000);
      pool = new HikariDataSource(config);
    }
    var jdbc = new JdbcTemplate(pool);
    jdbc.execute("DROP TRIGGER IF EXISTS java_index_move_fault ON embeddings");
    jdbc.execute("TRUNCATE embeddings RESTART IDENTITY");
    var repo = new PostgresIndexRepository(pool);
    repo.initialize();
    return new Database(pool, repo, () -> {});
  }

  @AfterAll
  void close() {
    if (pool != null) pool.close();
  }
}
