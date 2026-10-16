package com.tencent.weknora.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.zaxxer.hikari.*;
import com.tencent.weknora.retrieval.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.*;
import org.junit.jupiter.api.*;

/** Explicit owned full-schema PG + Hikari1 replay of the same actual public Go HTTP chain. */
class HybridSearchPostgresGoParityIT extends HybridSearchGoHttpParityTest {
  @Override @TestFactory Stream<DynamicTest> actualGoPublicSearch()throws Exception{JsonNode cases=json.readTree(Files.readAllBytes(Path.of(Objects.requireNonNull(System.getProperty("weknora.hybrid.pg.fixture")))));assertEquals(143,cases.size());var sequence=new AtomicInteger();return StreamSupport.stream(cases.spliterator(),false).map(row->DynamicTest.dynamicTest("postgres/"+row.path("name").asText(),()->compare(row,sequence.incrementAndGet())));}
  @Override javax.sql.DataSource database(int sequence){String url=Objects.requireNonNull(System.getProperty("weknora.hybrid.pg.url"));if(!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/weknora_hybrid_parity"))throw new IllegalArgumentException("Owned loopback hybrid parity database required");var config=new HikariConfig();config.setJdbcUrl(url);config.setUsername("postgres");config.setPassword("local-parity-only");config.addDataSourceProperty("stringtype","unspecified");config.setMaximumPoolSize(1);config.setMinimumIdle(1);config.setConnectionTimeout(2000);return new HikariDataSource(config);}
  @Override void schema(javax.sql.DataSource source){var jdbc=new org.springframework.jdbc.core.JdbcTemplate(source);jdbc.execute("TRUNCATE chunk_images,embeddings,tenants,knowledge_bases,knowledges,chunks,models,organizations,organization_tenant_members,kb_shares,custom_agents,agent_shares RESTART IDENTITY CASCADE");}
  @Override IndexWriter index(javax.sql.DataSource source)throws Exception{var index=new PostgresIndexRepository(source);index.initialize();return index;}
  @Override Object seedValue(String key,JsonNode value,boolean blob){if(Set.of("created_at","updated_at","deleted_at","scheduled_delete_at","last_profile_refresh_at").contains(key)&&value.isTextual()){try{return java.sql.Timestamp.from(OffsetDateTime.parse(value.asText()).toInstant());}catch(Exception ignored){}}return value.isNull()?null:value.isBoolean()?value.booleanValue():value.isNumber()?value.numberValue():value.isContainerNode()?value.toString():value.asText();}
  @Override String driver(){return "postgres";}
  @Override void release(javax.sql.DataSource source){((HikariDataSource)source).close();}
}
