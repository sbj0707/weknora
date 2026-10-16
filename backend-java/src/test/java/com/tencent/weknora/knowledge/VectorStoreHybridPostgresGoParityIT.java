package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.retrieval.*;
import com.zaxxer.hikari.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Actual full Go PG metadata and Hikari1, retaining a separate actual ES8 physical index. */
class VectorStoreHybridPostgresGoParityIT extends VectorStoreHybridGoParityIT {
  @Override javax.sql.DataSource database(int sequence){String url=Objects.requireNonNull(System.getProperty("weknora.hybrid.pg.url"));if(!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/weknora_hybrid_parity"))throw new IllegalArgumentException("Owned PG required");var config=new HikariConfig();config.setJdbcUrl(url);config.setUsername("postgres");config.setPassword("local-parity-only");config.addDataSourceProperty("stringtype","unspecified");config.setMaximumPoolSize(1);config.setMinimumIdle(1);config.setConnectionTimeout(2000);return new HikariDataSource(config);}
  @Override void schema(javax.sql.DataSource source){new JdbcTemplate(source).execute("TRUNCATE vector_stores,chunk_images,embeddings,tenants,knowledge_bases,knowledges,chunks,models,organizations,organization_tenant_members,kb_shares,custom_agents,agent_shares RESTART IDENTITY CASCADE");}
  @Override Object seedValue(String key,JsonNode value,boolean blob){if(Set.of("created_at","updated_at","deleted_at","scheduled_delete_at","last_profile_refresh_at").contains(key)&&value.isTextual()){try{return java.sql.Timestamp.from(OffsetDateTime.parse(value.asText()).toInstant());}catch(Exception ignored){}}return value.isNull()?null:value.isBoolean()?value.booleanValue():value.isNumber()?value.numberValue():value.isContainerNode()?value.toString():value.asText();}
  @Override Object storedConfig(JsonNode config){return config.toString();}
  @Override String driver(){return "postgres";}
  @Override void release(javax.sql.DataSource source){((HikariDataSource)source).close();}
}
