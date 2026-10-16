package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.tencent.weknora.task.TaskRepository;
import com.zaxxer.hikari.*;
import java.nio.file.*;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Independently exported Go worker over every PostgreSQL migration, one pooled connection. */
class KnowledgeBaseDeleteWorkerPostgresGoParityIT extends KnowledgeBaseDeleteWorkerGoParityTest {
  private HikariDataSource pool;
  @Override JsonNode rows()throws Exception{return json.readTree(Files.readAllBytes(Path.of(Objects.requireNonNull(System.getProperty("weknora.kb-delete.pg.fixture")))));}
  @Override DocumentProcessingCompatibilityTest database(int no) {
    String url=Objects.requireNonNull(System.getProperty("weknora.kb-delete.pg.url"));
    if(!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/weknora_kb_delete_parity"))throw new IllegalArgumentException("Owned loopback KB deletion database required");
    var config=new HikariConfig();config.setJdbcUrl(url);config.setUsername("postgres");config.setPassword("local-parity-only");config.setMaximumPoolSize(1);config.setMinimumIdle(1);config.setConnectionTimeout(2000);config.addDataSourceProperty("stringtype","unspecified");pool=new HikariDataSource(config);
    var f=new DocumentProcessingCompatibilityTest();f.temp=temp;f.jdbc=new JdbcTemplate(pool);f.manager=new DataSourceTransactionManager(pool);f.tasks=new TaskRepository(f.jdbc,json,f.manager);
    f.jdbc.execute("TRUNCATE tenants,knowledge_bases,knowledges,chunks,chunk_images,users,wiki_pages,wiki_folders,wiki_page_revisions,wiki_page_issues,task_pending_ops RESTART IDENTITY CASCADE");return f;
  }
  @Override void release(DocumentProcessingCompatibilityTest f){if(pool!=null){pool.close();pool=null;}}
}
