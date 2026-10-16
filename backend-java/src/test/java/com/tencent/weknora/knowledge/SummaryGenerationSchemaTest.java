package com.tencent.weknora.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Clock;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;

class SummaryGenerationSchemaTest {
  @TempDir Path directory;
  @Test void repositoryConstructionDoesNotCreateReceiptTable()throws Exception {
    var source=new DriverManagerDataSource("jdbc:sqlite:"+directory.resolve("schema.db"));source.setDriverClassName("org.sqlite.JDBC");var jdbc=new JdbcTemplate(source);var tx=new DataSourceTransactionManager(source);
    String before=jdbc.queryForObject("SELECT GROUP_CONCAT(name) FROM sqlite_master",String.class);
    new SummaryGenerationRepository(jdbc,new ObjectMapper(),new KnowledgeRepository(jdbc,new ObjectMapper(),tx),tx,Clock.systemUTC());
    assertEquals(before,jdbc.queryForObject("SELECT GROUP_CONCAT(name) FROM sqlite_master",String.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE name='java_summary_task_drains'",Integer.class));
  }
  @Test void explicitUpgradeIsIdempotentAndMatchesFreshSchema()throws Exception {
    var source=new DriverManagerDataSource("jdbc:sqlite:"+directory.resolve("upgrade.db"));source.setDriverClassName("org.sqlite.JDBC");var jdbc=new JdbcTemplate(source);String full=Files.readString(Path.of("migration/sql/java-tasks.sql"));String upgrade=Files.readString(Path.of("migration/sql/java-summary-drains-upgrade.sql"));
    try(var connection=source.getConnection()){org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,new org.springframework.core.io.ByteArrayResource(full.substring(0,full.indexOf("-- Terminal summary deliveries")).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
    for(int i=0;i<2;i++)jdbc.execute(upgrade);
    var columns=jdbc.queryForList("PRAGMA table_info(java_summary_task_drains)");assertEquals(java.util.List.of("task_id","tenant_id","knowledge_id","attempt"),columns.stream().map(r->r.get("name")).toList());
    jdbc.update("INSERT INTO java_summary_task_drains(task_id,tenant_id,knowledge_id,attempt) VALUES('task',8,'doc',1)");jdbc.execute(upgrade);assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM java_summary_task_drains",Integer.class));
  }
}
