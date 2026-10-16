package com.tencent.weknora.knowledge;

import com.zaxxer.hikari.*;
import java.util.Objects;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Independent Go export, full production schema, single-connection chunk creation/rollback. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KnowledgeCloneChunksPostgresGoParityIT extends KnowledgeCloneChunksGoParityTest {
  HikariDataSource pool;
  @Override boolean postgres(){return true;}
  @Override DocumentProcessingCompatibilityTest database() {
    if(pool==null){
      String url=Objects.requireNonNull(System.getProperty("weknora.clone.chunks.pg.url"));
      if(!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/weknora_clone_chunks_parity"))
        throw new IllegalArgumentException("Owned loopback clone chunks database required");
      var config=new HikariConfig();config.setJdbcUrl(url);config.setUsername("postgres");
      config.setPassword("local-parity-only");config.setMaximumPoolSize(1);config.setMinimumIdle(1);config.setConnectionTimeout(2000);
      config.addDataSourceProperty("stringtype","unspecified");pool=new HikariDataSource(config);
    }
    var f=new DocumentProcessingCompatibilityTest();f.temp=temp;f.jdbc=new JdbcTemplate(pool);f.manager=new DataSourceTransactionManager(pool);
    f.jdbc.execute("TRUNCATE tenants,knowledges,chunks,knowledge_tags,knowledge_tag_relations,java_task_jobs RESTART IDENTITY CASCADE");
    return f;
  }
  @AfterAll void close(){if(pool!=null)pool.close();}
}
