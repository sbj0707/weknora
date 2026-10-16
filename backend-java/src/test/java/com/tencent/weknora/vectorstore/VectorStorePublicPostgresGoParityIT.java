package com.tencent.weknora.vectorstore;

import com.zaxxer.hikari.*;
import java.sql.Timestamp;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Owned full-schema PostgreSQL, real ES8, Hikari1, actual Go HTTP/SQL replay. */
class VectorStorePublicPostgresGoParityIT extends VectorStorePublicGoParityIT {
  @Override
  DataSource database() {
    String url = Objects.requireNonNull(System.getProperty("weknora.vector.public.pg.url"));
    if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/weknora_vector_public_parity"))
      throw new IllegalArgumentException("Owned loopback vector public DB required");
    var config = new HikariConfig();
    config.setJdbcUrl(url);
    config.setUsername("postgres");
    config.setPassword("local-parity-only");
    config.setMaximumPoolSize(1);
    config.setMinimumIdle(1);
    config.setConnectionTimeout(2000);
    return new HikariDataSource(config);
  }

  @Override
  void prepare(DataSource source) {
    new JdbcTemplate(source)
        .execute("TRUNCATE vector_stores,knowledge_bases,tenants RESTART IDENTITY CASCADE");
  }

  @Override
  void release(DataSource source) {
    ((HikariDataSource) source).close();
  }

  @Override
  String jsonParameter() {
    return "CAST(? AS JSONB)";
  }

  @Override
  Object stored(String raw) {
    return raw;
  }

  @Override
  Object seedTime() {
    return Timestamp.from(clock.instant());
  }
}
