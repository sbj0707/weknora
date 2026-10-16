package com.tencent.weknora.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import com.tencent.weknora.model.ModelEgressPolicy;
import com.tencent.weknora.model.client.*;
import com.tencent.weknora.retrieval.IndexEntry;
import com.tencent.weknora.vectorstore.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.env.MockEnvironment;

/** Actual production Spring registration/AuthFilter/Viewer/owner model plus physical ES8. */
class VectorStoreHybridPipelineHttpIT extends HybridSearchPipelineHttpIntegrationTest {
  @Autowired VectorStoreRegistry registry;
  SafeModelHttp esHttp;Elasticsearch8Store es;String esIndex,storeId;
  @Override @BeforeEach void setup()throws Exception {
    super.setup();String address=Objects.requireNonNull(System.getProperty("weknora.es.url"));if(!address.matches("http://127\\.0\\.0\\.1:[0-9]+"))throw new IllegalArgumentException("Owned ES required");esIndex="weknora-spring-"+UUID.randomUUID();storeId="spring-store-"+UUID.randomUUID();esHttp=new SafeModelHttp(new ModelEgressPolicy(jdbc,json,new MockEnvironment().withProperty("SSRF_WHITELIST","127.0.0.1")));
    try(var call=new CallContext()){
      var create=json.createObjectNode();create.putObject("settings").put("number_of_shards",1).put("number_of_replicas",0);var props=create.putObject("mappings").putObject("properties");props.putObject("embedding").put("type","dense_vector").put("dims",2).put("index",true).put("similarity","cosine");props.putObject("content").put("type","text");for(var field:List.of("chunk_id","source_id","knowledge_id","knowledge_base_id","tag_id"))props.putObject(field).put("type","keyword");try(var response=esHttp.exchange(call,address+"/"+esIndex,"PUT",Map.of("Content-Type","application/json"),json.writeValueAsBytes(create),java.time.Duration.ofSeconds(30))){assertEquals(200,response.status(),response.text(100000));}
      var cc=json.createObjectNode().put("addr",address).put("version","8.14.0");var index=json.createObjectNode().put("index_name",esIndex);jdbc.update("INSERT INTO vector_stores(id,tenant_id,name,engine_type,connection_config,index_config) VALUES(?,2,?,'elasticsearch',?,?)",storeId,storeId,json.writeValueAsBytes(cc),json.writeValueAsBytes(index));jdbc.update("UPDATE knowledge_bases SET vector_store_id=? WHERE id=?",storeId,kb);
      es=new Elasticsearch8Store(new VectorStoreMetadata.Store(storeId,2,"elasticsearch",cc,index),esHttp,json,call);es.batchSave(List.of(new IndexEntry(chunk,0,chunk,doc,kb,"","refund policy details",true)),Map.of(chunk,new float[]{1,0}),call);es.request("POST","/"+esIndex+"/_refresh",null,call);
    }
  }
  @Test void physicalEngineDeleteCannotFallBackToExistingDefaultIndex()throws Exception {
    request("POST",callerToken,false,"{\"query_text\":\"refund\"}","",200);
    try(var call=new CallContext()){registry.resolve(2,storeId,call).delete("chunk_id",List.of(chunk),call);es.request("POST","/"+esIndex+"/_refresh",null,call);assertEquals(0,es.request("GET","/"+esIndex+"/_count",null,call).path("count").asInt());}
    assertTrue(request("POST",callerToken,false,"{\"query_text\":\"refund\"}","",200).path("data").isNull());
    jdbc.update("UPDATE knowledge_bases SET vector_store_id=NULL WHERE id=?",kb);
    assertEquals(chunk,request("POST",callerToken,false,"{\"query_text\":\"refund\"}","",200).path("data").get(0).path("id").asText());
  }
  @Override @AfterEach void cleanup(){try{if(es!=null)try(var call=new CallContext()){es.request("DELETE","/"+esIndex,null,call);}}finally{if(storeId!=null)registry.unregister(storeId);if(esHttp!=null)esHttp.close();super.cleanup();}}
}
