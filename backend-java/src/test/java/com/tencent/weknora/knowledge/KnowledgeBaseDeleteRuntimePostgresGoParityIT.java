package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.tencent.weknora.model.client.CallContext;
import com.tencent.weknora.task.TaskRepository;
import com.zaxxer.hikari.*;
import java.nio.file.*;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;

/** Independent actual Go physical oracle, every PostgreSQL migration, one worker connection. */
class KnowledgeBaseDeleteRuntimePostgresGoParityIT extends KnowledgeBaseDeleteRuntimeGoParityTest {
  private HikariDataSource pool;
  @Override JsonNode rows()throws Exception{return json.readTree(Files.readAllBytes(Path.of(Objects.requireNonNull(System.getProperty("weknora.kb-delete.pg.physical-fixture")))));}
  @Override DocumentProcessingCompatibilityTest database(int no) {
    String url=Objects.requireNonNull(System.getProperty("weknora.kb-delete.pg.url"));
    if(!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/weknora_kb_delete_parity"))throw new IllegalArgumentException("Owned loopback KB deletion database required");
    var config=new HikariConfig();config.setJdbcUrl(url);config.setUsername("postgres");config.setPassword("local-parity-only");config.setMaximumPoolSize(1);config.setMinimumIdle(1);config.setConnectionTimeout(2000);config.addDataSourceProperty("stringtype","unspecified");pool=new HikariDataSource(config);
    var f=new DocumentProcessingCompatibilityTest();f.temp=temp;f.jdbc=new JdbcTemplate(pool);f.manager=new DataSourceTransactionManager(pool);f.tasks=new TaskRepository(f.jdbc,json,f.manager);
    for(String table:java.util.List.of("embeddings","knowledges","resource_bindings"))f.jdbc.execute("DROP TRIGGER IF EXISTS kb_physical_fault ON "+table);
    f.jdbc.execute("TRUNCATE tenants,knowledge_bases,knowledges,chunks,chunk_images,users,wiki_pages,wiki_folders,wiki_page_revisions,wiki_page_issues,task_pending_ops,resources,resource_bindings,embeddings,java_task_jobs RESTART IDENTITY CASCADE");return f;
  }
  @Override void blockSql(DocumentProcessingCompatibilityTest f,CallContext call){new KnowledgeBaseDeleteSql(f.jdbc).query(call,"SELECT pg_sleep(30)");}
  @Override boolean requestCancel(DocumentProcessingCompatibilityTest f,String id) {
    // A separate client issues the cancel request while the single worker connection executes
    // pg_sleep. The worker pool itself remains size one throughout the cancellation/deadline test.
    var source=new DriverManagerDataSource(pool.getJdbcUrl(),"postgres","local-parity-only");
    var jdbc=new JdbcTemplate(source);var tasks=new TaskRepository(jdbc,json,new DataSourceTransactionManager(source));
    return tasks.cancel(1,id);
  }
  @Override void release(DocumentProcessingCompatibilityTest f){if(pool!=null){pool.close();pool=null;}}
}
