package com.tencent.weknora.retrieval;

import com.zaxxer.hikari.*;
import java.util.Objects;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** All production PostgreSQL migrations and a one-connection pool; independent Go export. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KnowledgeIndexCopyPostgresGoParityIT extends KnowledgeIndexCopyGoParityTest {
  HikariDataSource pool;
  @Override Database database() throws Exception {
    if(pool==null) {
      String url=Objects.requireNonNull(System.getProperty("weknora.index.copy.pg.url"));
      if(!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/weknora_index_copy_parity"))
        throw new IllegalArgumentException("Owned loopback index copy database required");
      var config=new HikariConfig();config.setJdbcUrl(url);config.setUsername("postgres");
      config.setPassword("local-parity-only");config.setMaximumPoolSize(1);config.setMinimumIdle(1);
      config.setConnectionTimeout(2000);pool=new HikariDataSource(config);
    }
    var jdbc=new JdbcTemplate(pool);
    jdbc.execute("DO $$ BEGIN IF to_regclass('hidden_copy_index') IS NOT NULL THEN ALTER TABLE hidden_copy_index RENAME TO embeddings; END IF; END $$");
    jdbc.execute("DROP TRIGGER IF EXISTS java_index_copy_fault ON embeddings");
    jdbc.execute("TRUNCATE embeddings RESTART IDENTITY");
    var repository=new PostgresIndexRepository(pool);repository.initialize();
    return new Database(pool,repository,()->{});
  }
  @AfterAll void close(){if(pool!=null)pool.close();}
}
