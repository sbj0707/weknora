package com.tencent.weknora.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import com.tencent.weknora.task.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;

/** Fresh shared-owner multipart upload through the production task beans and real model HTTP. */
@org.springframework.boot.test.context.SpringBootTest(properties={"weknora.jwt-secret=isolated-native-pipeline-trace-secret","weknora.bridge.enabled=false","weknora.knowledge.processing.enabled=true","weknora.knowledge.upload.enabled=true","weknora.tasks.enabled=true","weknora.tasks.poll-millis=3600000","weknora.retrieve-driver=sqlite","SSRF_WHITELIST=127.0.0.1","SSRF_DNS_WHITELIST_ONLY=true","logging.level.root=WARN","debug=false"})
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class SummaryGenerationPipelineHttpIntegrationTest {
  @org.springframework.test.context.DynamicPropertySource static void properties(org.springframework.test.context.DynamicPropertyRegistry p){KnowledgeProcessingSpanPipelineHttpIntegrationTest.properties(p);}
  @Autowired AutowireCapableBeanFactory beans;
  final KnowledgeProcessingSpanPipelineHttpIntegrationTest f=new KnowledgeProcessingSpanPipelineHttpIntegrationTest();
  HttpServer server;ObjectMapper json=new ObjectMapper();final AtomicInteger chats=new AtomicInteger(),embeddings=new AtomicInteger();final List<JsonNode> requests=Collections.synchronizedList(new ArrayList<>());
  boolean failure;Runnable during=()->{};
  @BeforeEach void setup()throws Exception {
    beans.autowireBean(f);f.setup();
    server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/v1/",exchange->{try{
      var body=json.readTree(exchange.getRequestBody());requests.add(body);ObjectNode response;int status=200;
      if(exchange.getRequestURI().getPath().endsWith("chat/completions")){chats.incrementAndGet();during.run();if(failure){status=503;response=json.createObjectNode().set("error",json.createObjectNode().put("message","PRIVATE_PROVIDER_DETAIL"));}
        else response=json.createObjectNode().set("choices",json.createArrayNode().add(json.createObjectNode().put("finish_reason","stop").set("message",json.createObjectNode().put("role","assistant").put("content","{\"summary\":\"Native objective summary 中文😀.\",\"gist\":\"Native headline\",\"topics\":[\"Access\",\"access.\",\"Delivery\"],\"doc_type\":\"policy\"}"))));
      }else{embeddings.incrementAndGet();var values=json.createArrayNode();for(int i=0;i<body.path("input").size();i++)values.add(json.createObjectNode().put("index",i).set("embedding",json.createArrayNode().add(1).add(0).add(0)));response=json.createObjectNode().set("data",values);}
      var bytes=json.writeValueAsBytes(response);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}
    }finally{exchange.close();}});server.start();
    ObjectNode p=json.createObjectNode().put("provider","generic").put("base_url","http://127.0.0.1:"+server.getAddress().getPort()+"/v1").put("api_key","fake");p.set("embedding_parameters",json.createObjectNode().put("dimension",3));
    f.jdbc.update("UPDATE models SET parameters=? WHERE id='trace-embedding'",p.toString());f.jdbc.update("INSERT INTO models(id,tenant_id,name,type,source,parameters,is_builtin,status) VALUES('trace-summary',8,'unknown','KnowledgeQA','remote',?,false,'active') ON CONFLICT(id) DO UPDATE SET parameters=excluded.parameters",p.toString());f.jdbc.update("UPDATE knowledge_bases SET summary_model_id='trace-summary' WHERE id='trace-kb'");
  }
  @AfterEach void stop(){server.stop(0);}
  TaskRecord runToSummary(String id)throws Exception {
    f.jdbc.update("UPDATE knowledges SET metadata=? WHERE id=?","{\"process_overrides\":{\"summary_enabled\":true}}",id);
    var doc=f.claim("default");assertTrue(f.document.execute(doc,f.cancellation(doc)).path("indexed").asBoolean());f.tasks.complete(doc,json.createObjectNode());
    var post=f.claim("postprocess");assertTrue(f.postprocess.execute(post,f.cancellation(post)).path("enqueued_summary").asBoolean());f.tasks.complete(post,json.createObjectNode());
    assertEquals("finalizing",state(id,"parse_status"));assertEquals(1,pending(id));var job=f.claim("summary");assertEquals("summary:generation",job.type());assertEquals(8,job.tenantId());assertEquals(1,job.payload().path("attempt").asLong());assertEquals(4,job.maxAttempts());assertEquals(1800000,job.timeoutMillis());return job;
  }
  String state(String id,String field){return f.jdbc.queryForObject("SELECT "+field+" FROM knowledges WHERE tenant_id=8 AND id=?",String.class,id);}
  long pending(String id){return f.jdbc.queryForObject("SELECT pending_subtasks_count FROM knowledges WHERE tenant_id=8 AND id=?",Long.class,id);}
  @Test void nativeUploadSummaryProfilePhysicalIndexesAndTraceUseOwner()throws Exception {
    String id=f.upload("A native source documents the access controls and delivery policy.");var job=runToSummary(id);assertEquals("completed",f.summary.execute(job,f.cancellation(job)).path("status").asText());f.tasks.complete(job,json.createObjectNode());
    assertEquals("completed",state(id,"parse_status"));assertEquals("completed",state(id,"summary_status"));assertEquals(0,pending(id));assertEquals("Native objective summary 中文😀.",state(id,"description"));assertEquals(1,chats.get());
    JsonNode profile=json.readTree(state(id,"profile"));assertEquals(json.createArrayNode().add("Access").add("Delivery"),profile.path("topics"));
    assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM chunks WHERE tenant_id=8 AND knowledge_id=? AND chunk_type='summary'",Integer.class,id));assertEquals(f.count("chunks",id),f.count("lite_embeddings",id));
    for(String alias:List.of("spans","stages")){var trace=f.get(id,alias,f.callerToken,200).path("trace");assertEquals("done",trace.path("status").asText());assertTrue(trace.toString().contains("postprocess.summary"));assertFalse(trace.toString().contains("PRIVATE_PROVIDER_DETAIL"));f.get(id,alias,f.foreignToken,403);}
    assertTrue(requests.stream().filter(r->r.has("messages")).allMatch(r->r.path("messages").get(0).path("content").asText().contains("Chinese (Simplified)")));
    var summaryId=f.jdbc.queryForObject("SELECT id FROM chunks WHERE knowledge_id=? AND chunk_type='summary'",String.class,id);assertTrue(f.get(id,"chunks/"+summaryId+"/details",f.callerToken,200).toString().contains("Native headline"));
  }
  @Test void nativeRetryRetainsDescriptionThenFinalFallbackDrainsAndDoesNotPoisonRoot()throws Exception {
    failure=true;String id=f.upload("A source with meaningful text for deterministic final retry fallback.");var job=runToSummary(id);f.jdbc.update("UPDATE knowledges SET description='old visible summary' WHERE id=?",id);
    assertThrows(TaskFailure.class,()->f.summary.execute(job,f.cancellation(job)));assertEquals("pending",state(id,"summary_status"));assertEquals("old visible summary",state(id,"description"));assertEquals(1,pending(id));
    f.jdbc.update("UPDATE java_task_jobs SET attempts=4 WHERE id=?",job.id());var last=f.tasks.get(8,job.id()).orElseThrow();assertThrows(TaskFailure.class,()->f.summary.execute(last,f.cancellation(last)));assertEquals("failed",state(id,"summary_status"));assertEquals("completed",state(id,"parse_status"));assertEquals(0,pending(id));assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM chunks WHERE knowledge_id=? AND chunk_type='summary'",Integer.class,id));
    assertEquals("done",f.get(id,"spans",f.callerToken,200).path("trace").path("status").asText());
    // Replaying a terminal handler cannot release another enrichment slot.
    f.jdbc.update("UPDATE knowledges SET parse_status='finalizing',pending_subtasks_count=1 WHERE id=?",id);assertThrows(TaskFailure.class,()->f.summary.execute(last,f.cancellation(last)));assertEquals(1,pending(id));
  }
  @Test void nativeAttemptSupersessionDoesNotDrainSuccessorOrCallModel()throws Exception {
    String id=f.upload("A source whose processing attempt will be replaced before summary starts.");var job=runToSummary(id);f.tracker.open(8,id,"");f.jdbc.update("UPDATE knowledges SET parse_status='finalizing',pending_subtasks_count=2 WHERE id=?",id);assertEquals("attempt_superseded",f.summary.execute(job,f.cancellation(job)).path("skipped").asText());assertEquals(0,chats.get());assertEquals(2,pending(id));assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM java_summary_task_drains WHERE task_id=?",Integer.class,job.id()));
  }
  @Test void nativeLeaseLossDuringModelDoesNotPublishOrDrain()throws Exception {
    String id=f.upload("A source whose summary worker lease will be replaced during model output.");var job=runToSummary(id);during=()->f.jdbc.update("UPDATE java_task_jobs SET lease_owner='successor' WHERE id=?",job.id());assertThrows(CancellationException.class,()->f.summary.execute(job,f.cancellation(job)));assertEquals(1,pending(id));assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM chunks WHERE knowledge_id=? AND chunk_type='summary'",Integer.class,id));assertNotEquals("Native objective summary 中文😀.",state(id,"description"));
  }
  @Test void foreignPayloadOwnerAndKbCannotMutateEvenOnFinalDelivery()throws Exception {
    String id=f.upload("A source to prove summary payload ownership is enforced before writes.");var job=runToSummary(id);f.jdbc.update("UPDATE java_task_jobs SET attempts=4,payload=? WHERE id=?",((ObjectNode)job.payload().deepCopy()).put("tenant_id",9).toString(),job.id());var invalid=f.tasks.get(8,job.id()).orElseThrow();assertThrows(TaskFailure.class,()->f.summary.execute(invalid,f.cancellation(invalid)));assertEquals(0,chats.get());assertEquals(1,pending(id));
    f.jdbc.update("UPDATE java_task_jobs SET payload=? WHERE id=?",((ObjectNode)job.payload().deepCopy()).put("knowledge_base_id","foreign-kb").toString(),job.id());TaskRecord foreign=f.tasks.get(8,job.id()).orElseThrow();assertThrows(TaskFailure.class,()->f.summary.execute(foreign,f.cancellation(foreign)));assertEquals(1,pending(id));assertEquals(0,chats.get());
  }
  @Test void summaryFanoutEnqueueFailureRollsBackHandoff()throws Exception {
    String id=f.upload("A source to prove durable summary fanout is a complete transaction.");f.jdbc.update("UPDATE knowledges SET metadata=? WHERE id=?","{\"process_overrides\":{\"summary_enabled\":true}}",id);var doc=f.claim("default");f.document.execute(doc,f.cancellation(doc));f.tasks.complete(doc,json.createObjectNode());var post=f.claim("postprocess");
    var repo=beans.getBean(SummaryGenerationRepository.class);var snapshot=f.processing.load(post).orElseThrow();assertThrows(IllegalStateException.class,()->repo.handoff(post,snapshot,(owner,type,p,options)->{throw new java.io.IOException("synthetic queue failure");}));assertEquals("processing",state(id,"parse_status"));assertEquals(0,pending(id));assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM java_task_jobs WHERE type='summary:generation' AND json_extract(payload,'$.knowledge_id')=?",Integer.class,id));
  }
}
