package com.tencent.weknora.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.model.*;
import com.tencent.weknora.model.client.*;
import com.tencent.weknora.retrieval.*;
import com.tencent.weknora.security.*;
import com.tencent.weknora.vectorstore.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

/** Original Go public handler, source hydration, model HTTP and actual ES8 bound instance. */
class VectorStoreHybridGoParityIT extends HybridSearchGoHttpParityTest {
  SafeModelHttp transport;VectorStoreRegistry registry;Elasticsearch8Store seed;String index,address;JdbcTemplate jdbc;
  @Override @TestFactory Stream<DynamicTest> actualGoPublicSearch()throws Exception{var cases=json.readTree(Files.readAllBytes(Path.of(Objects.requireNonNull(System.getProperty("weknora.es.hybrid.fixture")))));assertEquals(52,cases.size());var sequence=new AtomicInteger();return StreamSupport.stream(cases.spliterator(),false).map(c->DynamicTest.dynamicTest("bound-ES/"+c.path("name").asText(),()->compare(c,sequence.incrementAndGet())));}
  @Override List<String> seedTables(){var tables=new ArrayList<>(super.seedTables());tables.add("vector_stores");return tables;}
  @Override void seedIndex(javax.sql.DataSource source,List<IndexEntry> entries,Map<String,float[]> vectors,JsonNode oracle)throws Exception {
    address=Objects.requireNonNull(System.getProperty("weknora.es.url"));if(!address.matches("http://127\\.0\\.0\\.1:[0-9]+"))throw new IllegalArgumentException("Owned ES URL required");index="weknora-public-java-"+UUID.randomUUID();jdbc=new JdbcTemplate(source);var env=new MockEnvironment().withProperty("SSRF_WHITELIST","127.0.0.1");transport=new SafeModelHttp(new ModelEgressPolicy(jdbc,json,env));
    try(var call=new CallContext()){
      var create=json.createObjectNode();create.putObject("settings").put("number_of_shards",1).put("number_of_replicas",0);var props=create.putObject("mappings").putObject("properties");props.putObject("content").put("type","text");props.putObject("embedding").put("type","dense_vector").put("dims",2).put("index",true).put("similarity","cosine");for(String field:List.of("chunk_id","source_id","knowledge_id","knowledge_base_id","tag_id"))props.putObject(field).put("type","keyword");try(var response=transport.exchange(call,address+"/"+index,"PUT",Map.of("Content-Type","application/json"),json.writeValueAsBytes(create),java.time.Duration.ofSeconds(30))){assertEquals(200,response.status(),response.text(100000));}
      seed=new Elasticsearch8Store(new VectorStoreMetadata.Store("seed",1,"elasticsearch",json.createObjectNode().put("addr",address),json.createObjectNode().put("index_name",index)),transport,json,call);seed.batchSave(entries,vectors,call);seed.request("POST","/"+index+"/_refresh",null,call);
      for(var row:jdbc.queryForList("SELECT * FROM vector_stores")){var cc=(ObjectNode)json.readTree(HybridSearchHydration.text(row,"connection_config"));cc.put("addr",address);var cfg=(ObjectNode)json.readTree(HybridSearchHydration.text(row,"index_config"));cfg.put("index_name",index);jdbc.update("UPDATE vector_stores SET connection_config=?,index_config=? WHERE id=?",storedConfig(cc),storedConfig(cfg),row.get("id"));}
      var metadata=new VectorStoreMetadata(jdbc,json,new GoSecretCodec(""));registry=new VectorStoreRegistry(metadata,(store,context)->{if(!store.engine().equals("elasticsearch"))throw new IllegalArgumentException("Native engine not migrated");return new Elasticsearch8Store(store,transport,json,context);});
    }
  }
  Object storedConfig(JsonNode config){return config.toString().getBytes(StandardCharsets.UTF_8);}
  @Override void prepareStore(HybridSearchStore store,JsonNode oracle){store.withRegistry(registry);}
  @AfterEach void cleanup(){try(var call=new CallContext()){if(seed!=null)seed.request("DELETE","/"+index,null,call);}finally{if(registry!=null)registry.close();if(transport!=null)transport.close();seed=null;registry=null;transport=null;}}
  @Override AutoCloseable boundary(javax.sql.DataSource source,JsonNode oracle){return this::cleanup;}
}
