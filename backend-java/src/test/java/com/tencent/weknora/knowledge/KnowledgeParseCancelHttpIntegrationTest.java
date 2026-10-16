package com.tencent.weknora.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.security.AuthFilter;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;

@org.springframework.boot.test.context.SpringBootTest(properties={"weknora.jwt-secret=isolated-native-pipeline-trace-secret","weknora.bridge.enabled=false","weknora.knowledge.processing.enabled=true","weknora.knowledge.upload.enabled=true","weknora.tasks.enabled=true","weknora.tasks.poll-millis=3600000","weknora.retrieve-driver=sqlite","SSRF_WHITELIST=127.0.0.1","SSRF_DNS_WHITELIST_ONLY=true","logging.level.root=WARN","debug=false"})
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class KnowledgeParseCancelHttpIntegrationTest {
  @org.springframework.test.context.DynamicPropertySource static void properties(org.springframework.test.context.DynamicPropertyRegistry p){KnowledgeProcessingSpanPipelineHttpIntegrationTest.properties(p);p.add("spring.datasource.hikari.maximum-pool-size",()->1);}
  @Autowired AutowireCapableBeanFactory beans;
  final KnowledgeProcessingSpanPipelineHttpIntegrationTest f=new KnowledgeProcessingSpanPipelineHttpIntegrationTest();
  com.sun.net.httpserver.HttpServer server;
  @BeforeEach void setup()throws Exception{
    beans.autowireBean(f);f.setup();
    // Own the model server lifecycle: the reused trace fixture's server may already
    // have been closed by its own test class in a whole-suite run.
    server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/v1/embeddings",exchange->{try{
      KnowledgeProcessingSpanPipelineHttpIntegrationTest.MODEL_CALLS.incrementAndGet();
      var request=f.json.readTree(exchange.getRequestBody());KnowledgeProcessingSpanPipelineHttpIntegrationTest.duringModel.run();
      var rows=f.json.createArrayNode();for(int i=0;i<request.path("input").size();i++)rows.add(f.json.createObjectNode().put("index",i).set("embedding",f.json.createArrayNode().add(1).add(0).add(0)));
      byte[] response=f.json.createObjectNode().set("data",rows).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,response.length);try(var out=exchange.getResponseBody()){out.write(response);}
    }finally{exchange.close();}});server.start();
    var params=f.json.createObjectNode().put("provider","generic").put("base_url","http://127.0.0.1:"+server.getAddress().getPort()+"/v1");params.set("embedding_parameters",f.json.createObjectNode().put("dimension",3));
    f.jdbc.update("UPDATE models SET parameters=? WHERE id='trace-embedding'",params.toString());
  }
  @AfterEach void stop(){if(server!=null)server.stop(0);KnowledgeProcessingSpanPipelineHttpIntegrationTest.duringModel=()->{};}
  JsonNode cancel(String id,String token,int expected)throws Exception {
    var response=f.mvc.perform(post("/api/v1/knowledge/"+id+"/cancel-parse").header("Authorization","Bearer "+token)).andReturn().getResponse();
    assertEquals(expected,response.getStatus(),response.getContentAsString());return f.json.readTree(response.getContentAsByteArray());
  }
  String state(String id){return f.jdbc.queryForObject("SELECT parse_status FROM knowledges WHERE id=?",String.class,id);}
  long audits(String id){return f.jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE target_id=? AND action='knowledge.parse_canceled'",Long.class,id);}
  @Test void pendingSharedUploadCancelsQueueAndRetainsOriginalActor()throws Exception{
    String id=f.upload("Cancel before the document task starts.");var reply=cancel(id,f.callerToken,200);
    assertEquals("Knowledge parse cancelled",reply.path("message").asText());assertEquals("cancelled",reply.path("data").path("parse_status").asText());assertEquals(8,reply.path("data").path("tenant_id").asLong());
    assertEquals(0,reply.path("data").path("pending_subtasks_count").asLong());assertEquals("用户已取消解析",reply.path("data").path("error_message").asText());
    assertTrue(f.tasks.claim("after-cancel",Duration.ofMinutes(5),List.of("default")).isEmpty());assertEquals(0,KnowledgeProcessingSpanPipelineHttpIntegrationTest.MODEL_CALLS.get());
    var audit=f.jdbc.queryForMap("SELECT * FROM audit_logs WHERE target_id=? AND action='knowledge.parse_canceled'",id);assertEquals("caller",audit.get("actor_user_id"));assertEquals(8,((Number)audit.get("tenant_id")).longValue());assertEquals("canceled",audit.get("outcome"));
  }
  @Test void finalizingCancellationRetainsPublishedChunksIndexesAndClosesAttempt()throws Exception{
    String id=f.upload("Published content remains available after cancelling downstream enrichment.");var doc=f.claim("default");f.document.execute(doc,f.cancellation(doc));f.tasks.complete(doc,f.json.createObjectNode());
    f.jdbc.update("UPDATE knowledges SET parse_status='finalizing',summary_status='processing',pending_subtasks_count=2 WHERE id=?",id);
    var payload=f.json.createObjectNode().put("knowledge_id",id).put("tenant_id",8);
    var q=f.tasks.enqueue(8,"question:generation",null,payload,4,Duration.ZERO,"question",Duration.ofMinutes(30));
    var table=f.tasks.enqueue(8,"datatable:summary",null,payload,4,Duration.ZERO,"summary",Duration.ofMinutes(30));
    long chunks=f.count("chunks",id),indexes=f.count("lite_embeddings",id);assertTrue(chunks>0);
    cancel(id,f.callerToken,200);assertEquals(chunks,f.count("chunks",id));assertEquals(indexes,f.count("lite_embeddings",id));
    assertEquals("none",f.jdbc.queryForObject("SELECT summary_status FROM knowledges WHERE id=?",String.class,id));
    assertEquals("canceled",f.tasks.get(8,q.id()).orElseThrow().status());assertEquals("pending",f.tasks.get(8,table.id()).orElseThrow().status(),"Go inspector excludes table tasks; their business checkpoint handles cancellation");
    var trace=f.get(id,"spans",f.callerToken,200).path("trace");assertEquals("cancelled",trace.path("status").asText());assertEquals("USER_CANCELLED",trace.path("error_code").asText());
    assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_processing_spans WHERE knowledge_id=? AND status IN ('running','pending')",Integer.class,id));
  }
  @Test void cancellationDuringEmbeddingStopsPublicationAndActiveLease()throws Exception{
    String id=f.upload("Cancellation during embedding must prevent publishing this attempt.");var doc=f.claim("default");
    KnowledgeProcessingSpanPipelineHttpIntegrationTest.duringModel=()->{try{cancel(id,f.callerToken,200);}catch(Exception e){throw new RuntimeException(e);}};
    assertThrows(java.util.concurrent.CancellationException.class,()->f.document.execute(doc,f.cancellation(doc)));
    assertEquals("cancelled",state(id));assertEquals(0,f.count("chunks",id));assertEquals(0,f.count("lite_embeddings",id));assertTrue(f.tasks.get(8,doc.id()).orElseThrow().cancelRequested());
    assertEquals("cancelled",f.get(id,"spans",f.callerToken,200).path("trace").path("status").asText());
  }
  @Test void repeatDoesNotAuditAgainButDequeuesWorkEnqueuedAfterFirstCall()throws Exception{
    String id=f.upload("Repeated cancellation is idempotent.");cancel(id,f.callerToken,200);long count=audits(id);var late=f.tasks.enqueue(8,"summary:generation",null,f.json.createObjectNode().put("knowledge_id",id),4,Duration.ZERO);
    cancel(id,f.callerToken,200);assertEquals(count,audits(id));assertEquals("canceled",f.tasks.get(8,late.id()).orElseThrow().status());
  }
  @Test void completedFailedDeletingStatusesReturnGoErrorsWithoutMutation()throws Exception{
    for(String status:List.of("completed","failed","deleting")){
      String id=f.upload("Cannot cancel terminal status "+status);f.jdbc.update("UPDATE knowledges SET parse_status=? WHERE id=?",status,id);var response=cancel(id,f.callerToken,400);
      assertEquals(1000,response.path("error").path("code").asInt());assertEquals(status,state(id));assertEquals(0,audits(id));
    }
  }
  @Test void foreignAndSharedViewerCannotCancelAndOwnerCreatorContributorCan()throws Exception{
    String id=f.upload("Permission checks must precede cancellation.");cancel(id,f.foreignToken,403);assertEquals("pending",state(id));
    f.jdbc.update("UPDATE organization_tenant_members SET role='viewer' WHERE id='trace-target'");try{cancel(id,f.callerToken,403);assertEquals("pending",state(id));}finally{f.jdbc.update("UPDATE organization_tenant_members SET role='editor' WHERE id='trace-target'");}
    f.jdbc.update("UPDATE tenant_members SET role='contributor' WHERE user_id='owner' AND tenant_id=8");try{cancel(id,f.token("owner",8),200);}finally{f.jdbc.update("UPDATE tenant_members SET role='admin' WHERE user_id='owner' AND tenant_id=8");}
  }
  @Test void apiKeyRequiresIngestAndParentKbAllowListBeforeWriting()throws Exception{
    String id=f.upload("API key cancellation uses ingestion permission.");String key="sk-cancel-"+UUID.randomUUID();
    f.jdbc.update("INSERT INTO tenant_api_keys(tenant_id,name,key_hash,api_key,full_access,capabilities,knowledge_base_ids) VALUES(7,'Cancel operator',?,?,false,'[\"retrieve\"]','[\"trace-kb\"]')",AuthFilter.hashApiKey(key),key);
    try{
      assertEquals(403,keyCancel("missing-document",key));assertEquals("pending",state(id));
      f.jdbc.update("UPDATE tenant_api_keys SET capabilities='[\"ingest\"]',knowledge_base_ids='[\"other\"]' WHERE key_hash=?",AuthFilter.hashApiKey(key));assertEquals(403,keyCancel(id,key));assertEquals("pending",state(id));
      f.jdbc.update("UPDATE tenant_api_keys SET knowledge_base_ids='[\"trace-kb\"]' WHERE key_hash=?",AuthFilter.hashApiKey(key));assertEquals(403,keyCancel(id,key)); // Scoped keys retain Go's shared-viewer cap.
      f.jdbc.update("UPDATE tenant_api_keys SET tenant_id=8 WHERE key_hash=?",AuthFilter.hashApiKey(key));assertEquals(200,keyCancel(id,key));
      var row=f.jdbc.queryForMap("SELECT actor_user_id,details FROM audit_logs WHERE target_id=? AND action='knowledge.parse_canceled'",id);assertEquals("owner",row.get("actor_user_id"));assertTrue(f.json.readTree(row.get("details").toString()).has("api_key_id"));
    }finally{f.jdbc.update("DELETE FROM tenant_api_keys WHERE key_hash=?",AuthFilter.hashApiKey(key));}
  }
  int keyCancel(String id,String key)throws Exception{return f.mvc.perform(post("/api/v1/knowledge/"+id+"/cancel-parse").header("X-API-Key",key)).andReturn().getResponse().getStatus();}
  @Test void nativeQueueCancellationPaginatesAndIgnoresForeignAndMalformedPayloads()throws Exception{
    String id=f.upload("Cancel more than one queue page.");var payload=f.json.createObjectNode().put("knowledge_id",id);
    for(int i=0;i<507;i++)f.tasks.enqueue(8,"question:generation",UUID.randomUUID().toString(),payload,4,Duration.ZERO);
    var foreign=f.tasks.enqueue(9,"question:generation",null,payload,4,Duration.ZERO);var other=f.tasks.enqueue(8,"question:generation",null,f.json.createObjectNode().put("knowledge_id","other-document"),4,Duration.ZERO);
    var malformed=f.tasks.enqueue(8,"question:generation",null,payload,4,Duration.ZERO);f.jdbc.update("UPDATE java_task_jobs SET payload='{' WHERE id=?",malformed.id());
    cancel(id,f.callerToken,200);assertEquals(507,f.jdbc.queryForObject("SELECT COUNT(*) FROM java_task_jobs WHERE tenant_id=8 AND type='question:generation' AND status='canceled' AND json_valid(payload) AND json_extract(payload,'$.knowledge_id')=?",Integer.class,id));
    assertEquals("pending",f.tasks.get(9,foreign.id()).orElseThrow().status());assertEquals("pending",f.tasks.get(8,other.id()).orElseThrow().status());assertEquals("pending",f.jdbc.queryForObject("SELECT status FROM java_task_jobs WHERE id=?",String.class,malformed.id()));
  }
}
