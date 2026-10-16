package com.tencent.weknora.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import com.tencent.weknora.retrieval.*;
import com.tencent.weknora.security.*;
import com.tencent.weknora.task.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.web.servlet.MockMvc;

/** Fresh native shared-KB multipart upload -> real model HTTP -> atomic indexes -> trace HTTP. */
@org.springframework.boot.test.context.SpringBootTest(properties={"weknora.jwt-secret=isolated-native-pipeline-trace-secret","weknora.bridge.enabled=false","weknora.knowledge.processing.enabled=true","weknora.knowledge.upload.enabled=true","weknora.tasks.enabled=true","weknora.tasks.poll-millis=3600000","weknora.retrieve-driver=sqlite","SSRF_WHITELIST=127.0.0.1","SSRF_DNS_WHITELIST_ONLY=true","logging.level.root=WARN","debug=false"})
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class KnowledgeProcessingSpanPipelineHttpIntegrationTest {
  static final Path DIRECTORY;
  static final Path DATABASE;
  static final HttpServer SERVER;
  static final ObjectMapper JSON=new ObjectMapper();
  static final AtomicInteger MODEL_CALLS=new AtomicInteger();
  static volatile boolean modelFailure;
  static volatile int modelFailureStatus=500;
  static volatile Runnable duringModel=()->{};
  static {
    try {
      NativeDatabaseRuntime.initialize();
      DIRECTORY=Files.createTempDirectory("weknora-native-pipeline-trace-");DATABASE=DIRECTORY.resolve("db.sqlite");
      try(var connection=java.sql.DriverManager.getConnection("jdbc:sqlite:"+DATABASE)) {
        for(String migration:List.of("000000_init","000002_knowledge_folder_path","000003_knowledge_base_auto_tag_config","000007_system_admin_and_settings","000008_processing_spans_and_pending_subtasks","000010_knowledge_multi_tags","000011_principal_model","000021_knowledge_profiles","000031_model_catalog_config","000033_chunk_source_locators"))
          ScriptUtils.executeSqlScript(connection,new FileSystemResource("../migrations/sqlite/"+migration+".up.sql"));
        ScriptUtils.executeSqlScript(connection,new FileSystemResource("migration/sql/java-tasks.sql"));
      }
      SERVER=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
      SERVER.createContext("/v1/embeddings",exchange->{
        try {
          MODEL_CALLS.incrementAndGet();var body=JSON.readTree(exchange.getRequestBody());duringModel.run();
          var data=JSON.createArrayNode();for(int i=body.path("input").size()-1;i>=0;i--)data.add(JSON.createObjectNode().put("index",i).set("embedding",JSON.createArrayNode().add(1).add(0).add(0)));
          byte[] bytes=(modelFailure?JSON.createObjectNode().put("error","PRIVATE_MODEL_DETAIL"):JSON.createObjectNode().set("data",data)).toString().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(modelFailure?modelFailureStatus:200,bytes.length);
          try(var out=exchange.getResponseBody()){out.write(bytes);}
        }finally{exchange.close();}
      });SERVER.start();
    }catch(Exception error){throw new ExceptionInInitializerError(error);}
  }
  @org.springframework.test.context.DynamicPropertySource static void properties(org.springframework.test.context.DynamicPropertyRegistry p) {
    p.add("spring.datasource.url",()->"jdbc:sqlite:"+DATABASE);p.add("spring.datasource.driver-class-name",()->"org.sqlite.JDBC");p.add("spring.datasource.hikari.maximum-pool-size",()->2);
    p.add("LOCAL_STORAGE_BASE_DIR",()->DIRECTORY.resolve("files").toString());
    p.add("weknora.sqlite.vec.extension",()->System.getProperty("weknora.sqlite.vec.extension"));
  }
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired javax.sql.DataSource datasource;
  @Autowired JwtCodec jwt;
  @Autowired TaskRepository tasks;
  @Autowired DocumentProcessingHandler document;
  @Autowired KnowledgePostProcessHandler postprocess;
  @Autowired KnowledgeProcessingSpanRepository spans;
  @Autowired KnowledgeProcessingSpanTracker tracker;
  @Autowired DocumentProcessingRepository processing;
  @Autowired KnowledgeRepository knowledge;
  @Autowired com.tencent.weknora.storage.StorageResolver storage;
  @Autowired com.tencent.weknora.model.ModelClientFactory models;
  @Autowired DocumentProcessingHandler.Indexes indexes;
  String callerToken,foreignToken;

  @BeforeEach void setup() {
    jdbc.update("UPDATE knowledge_bases SET summary_model_id='' WHERE id='trace-kb'");
    jdbc.update("UPDATE java_task_jobs SET status='dead' WHERE status IN ('pending','running')");
    for(long owner:List.of(7L,8L,9L))jdbc.update("INSERT INTO tenants(id,name,business,retriever_engines,storage_engine_config) VALUES(?,?,'test',?,'{}') ON CONFLICT(id) DO NOTHING",owner,"Tenant"+owner,"{\"engines\":[{\"retriever_type\":\"vector\",\"retriever_engine_type\":\"sqlite\"},{\"retriever_type\":\"keywords\",\"retriever_engine_type\":\"sqlite\"}]}".getBytes(StandardCharsets.UTF_8));
    for(var entry:Map.of("caller",7L,"owner",8L,"foreign",9L).entrySet()) {
      jdbc.update("INSERT INTO users(id,username,email,password_hash,tenant_id) VALUES(?,?,?,'unused',?) ON CONFLICT(id) DO NOTHING",entry.getKey(),entry.getKey(),entry.getKey()+"@trace.example",entry.getValue());
      jdbc.update("INSERT INTO tenant_members(user_id,tenant_id,role,status) VALUES(?,?,'admin','active') ON CONFLICT(user_id,tenant_id) DO NOTHING",entry.getKey(),entry.getValue());
    }
    jdbc.update("INSERT INTO knowledge_bases(id,name,tenant_id,creator_id,type,embedding_model_id,summary_model_id,indexing_strategy,chunking_config) VALUES('trace-kb','Trace KB',8,'owner','document','trace-embedding','',?,?) ON CONFLICT(id) DO UPDATE SET indexing_strategy=excluded.indexing_strategy",strategy(true),"{\"chunk_size\":64,\"chunk_overlap\":10,\"separators\":[\"\\n\\n\",\"\\n\"]}");
    jdbc.update("INSERT INTO organizations(id,name,owner_tenant_id,owner_id,invite_code) VALUES('trace-org','Trace Org',8,'owner','trace-code') ON CONFLICT(id) DO NOTHING");
    jdbc.update("INSERT INTO organization_tenant_members(id,organization_id,tenant_id,role) VALUES('trace-source','trace-org',8,'admin'),('trace-target','trace-org',7,'editor') ON CONFLICT(id) DO NOTHING");
    jdbc.update("INSERT INTO kb_shares(id,knowledge_base_id,organization_id,source_tenant_id,permission,shared_by_user_id) VALUES('trace-share','trace-kb','trace-org',8,'editor','owner') ON CONFLICT(id) DO NOTHING");
    var params=json.createObjectNode().put("provider","generic").put("base_url","http://127.0.0.1:"+SERVER.getAddress().getPort()+"/v1");params.set("embedding_parameters",json.createObjectNode().put("dimension",3));
    jdbc.update("INSERT INTO models(id,tenant_id,name,type,source,parameters,is_builtin,status) VALUES('trace-embedding',8,'trace-embedding','Embedding','remote',?,false,'active') ON CONFLICT(id) DO UPDATE SET parameters=excluded.parameters",params.toString());
    callerToken=token("caller",7);foreignToken=token("foreign",9);MODEL_CALLS.set(0);modelFailure=false;modelFailureStatus=500;duringModel=()->{};
  }
  @AfterAll static void closeServer(){SERVER.stop(0);}
  String strategy(boolean enabled){return "{\"vector_enabled\":"+enabled+",\"keyword_enabled\":"+enabled+",\"graph_enabled\":"+!enabled+",\"wiki_enabled\":false}";}
  String token(String user,long tenant) {
    String token=jwt.sign(Map.of("user_id",user,"tenant_id",tenant,"type","access","exp",Instant.now().plusSeconds(3600).getEpochSecond()));
    jdbc.update("INSERT INTO auth_tokens(id,user_id,token,token_type,expires_at,is_revoked) VALUES(?,?,?,'access_token',?,false)",UUID.randomUUID().toString(),user,token,Instant.now().plusSeconds(3600).toString());return token;
  }
  byte[] multipart(String content) throws IOException {
    return ("--trace-boundary\r\nContent-Disposition: form-data; name=\"process_config\"\r\n\r\n{\"summary_enabled\":false}\r\n--trace-boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+UUID.randomUUID()+".txt\"\r\nContent-Type: text/plain\r\n\r\n"+content+"\r\n--trace-boundary--\r\n").getBytes(StandardCharsets.UTF_8);
  }
  String upload(String content) throws Exception {
    var response=mvc.perform(post("/api/v1/knowledge-bases/trace-kb/knowledge/file").header("Authorization","Bearer "+callerToken).contentType("multipart/form-data; boundary=trace-boundary").content(multipart(content))).andReturn().getResponse();
    assertEquals(200,response.getStatus(),response.getContentAsString());var row=json.readTree(response.getContentAsByteArray()).path("data");assertEquals(8,row.path("tenant_id").asLong());
    String id=row.path("id").asText();assertEquals(0,count("knowledge_processing_spans",id));return id;
  }
  long count(String table,String id){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE knowledge_id=?",Long.class,id);}
  TaskCancellation cancellation(TaskRecord job) throws Exception {var constructor=TaskCancellation.class.getDeclaredConstructor(TaskRepository.class,TaskRecord.class);constructor.setAccessible(true);return constructor.newInstance(tasks,job);}
  TaskRecord claim(String queue){return tasks.claim("native-trace-worker",Duration.ofMinutes(5),List.of(queue)).orElseThrow();}
  JsonNode get(String id,String suffix,String token,int status) throws Exception {
    var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/knowledge/"+id+"/"+suffix).header("Authorization","Bearer "+token)).andReturn().getResponse();assertEquals(status,response.getStatus(),response.getContentAsString());return json.readTree(response.getContentAsByteArray()).path("data");
  }
  JsonNode node(JsonNode trace,String name) {if(trace.path("name").asText().equals(name))return trace;for(var child:trace.path("children")){var result=node(child,name);if(result!=null)return result;}return null;}
  void assertNativeTrace(String id,String rootStatus) throws Exception {
    for(String alias:List.of("spans","stages")) {
      var data=get(id,alias,callerToken,200);assertEquals(1,data.path("attempt").asLong());assertEquals(1,data.path("latest_attempt").asLong());assertEquals(rootStatus,data.path("trace").path("status").asText());
      assertFalse(data.toString().contains("error_detail"));assertFalse(data.toString().contains("PRIVATE_MODEL_DETAIL"));assertFalse(data.path("trace").path("span_id").asText().isEmpty());
      for(String stage:List.of("docreader","chunking","embedding","multimodal","postprocess"))assertFalse(node(data.path("trace"),stage).path("span_id").asText().isEmpty(),stage);
      get(id,alias,foreignToken,403);
    }
    assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM knowledges WHERE id=? AND tenant_id<>8",Long.class,id));
    assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM chunks WHERE knowledge_id=? AND tenant_id<>8",Long.class,id));
  }
  @Test void sharedUploadNativeModelAndPostprocessProduceQueryableOwnerTraceAndPhysicalIndexes() throws Exception {
    String id=upload("First native paragraph with 中文.\n\nSecond native paragraph validates complete indexing.");
    duringModel=()->{
      assertEquals(0,count("chunks",id));
      var rows=spans.list(id,1);for(String name:List.of("chunking","embedding"))assertEquals("running",rows.stream().filter(row->row.path("name").asText().equals(name)).findFirst().orElseThrow().path("status").asText());
    };
    TaskRecord doc=claim("default");assertEquals(8,doc.tenantId());assertEquals(0,doc.payload().path("attempt").asLong());
    assertTrue(document.execute(doc,cancellation(doc)).path("indexed").asBoolean());assertEquals(1,doc.payload().path("attempt").asLong());
    assertEquals(0,tasks.get(8,doc.id()).orElseThrow().payload().path("attempt").asLong(),"worker fallback must not rewrite initial queue payload");tasks.complete(doc,json.createObjectNode());
    long chunks=count("chunks",id);assertTrue(chunks>0);assertEquals(chunks,count("lite_embeddings",id));assertEquals(1,MODEL_CALLS.get());
    var index=get(id,"index-status",callerToken,200);for(String kind:List.of("vector","keywords")){var row=index.path("indexes");boolean found=false;for(var item:row)if(item.path("kind").asText().equals(kind)){assertEquals(chunks,item.path("count").asLong());found=true;}assertTrue(found);}
    TaskRecord post=claim("postprocess");assertEquals(1,post.payload().path("attempt").asLong());assertTrue(postprocess.execute(post,cancellation(post)).path("completed").asBoolean());tasks.complete(post,json.createObjectNode());
    assertNativeTrace(id,"done");var trace=get(id,"spans",callerToken,200).path("trace");assertEquals(chunks,node(trace,"chunking").path("output").path("chunks_written").asLong());assertEquals(chunks,node(trace,"embedding").path("output").path("vectors_written").asLong());assertEquals("done",node(trace,"postprocess").path("status").asText());
    long physical=jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Long>)connection->{new SQLiteVectorExtension(Path.of(System.getProperty("weknora.sqlite.vec.extension"))).load(connection);try(var statement=connection.prepareStatement("SELECT COUNT(*) FROM vec_embeddings_3 v JOIN lite_embeddings e ON e.id=v.rowid WHERE e.knowledge_id=?")){statement.setString(1,id);try(var rows=statement.executeQuery()){return rows.getLong(1);}}});assertEquals(chunks,physical);
  }
  @Test void emptyUploadClosesRootImmediatelyAndQueuedPostprocessDoesNotReopenIt() throws Exception {
    String id=upload("  \n\n  ");TaskRecord doc=claim("default");assertTrue(document.execute(doc,cancellation(doc)).path("indexed").asBoolean());tasks.complete(doc,json.createObjectNode());
    assertEquals(0,count("chunks",id));assertEquals(0,MODEL_CALLS.get());assertNativeTrace(id,"done");
    TaskRecord post=claim("postprocess");assertEquals("non_processing_status",postprocess.execute(post,cancellation(post)).path("skipped").asText());assertNativeTrace(id,"done");
  }
  @Test void disabledIndexesSkipEmbeddingAndStillRecordPersistedChunksAndPostprocess() throws Exception {
    jdbc.update("UPDATE knowledge_bases SET indexing_strategy=? WHERE id='trace-kb'",strategy(false));String id=upload("Native graph-free document without model calls.");
    TaskRecord doc=claim("default");document.execute(doc,cancellation(doc));tasks.complete(doc,json.createObjectNode());TaskRecord post=claim("postprocess");postprocess.execute(post,cancellation(post));assertNativeTrace(id,"done");
    assertEquals(0,MODEL_CALLS.get());assertTrue(count("chunks",id)>0);assertEquals(0,count("lite_embeddings",id));assertEquals("skipped",node(get(id,"spans",callerToken,200).path("trace"),"embedding").path("status").asText());
  }
  @Test void embeddingFailureLeavesNoPublishedChunksAndClosesPlannedStagesWithoutPublicPrivateDetail() throws Exception {
    modelFailure=true;String id=upload("Embedding failure must leave the atomic publication empty.");TaskRecord doc=claim("default");assertTrue(document.execute(doc,cancellation(doc)).has("failed"));
    assertEquals(0,count("chunks",id));assertEquals(0,count("lite_embeddings",id));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM java_task_jobs WHERE type='knowledge:post_process' AND json_extract(payload,'$.knowledge_id')=?",Long.class,id));
    var trace=get(id,"spans",callerToken,200).path("trace");assertEquals("failed",trace.path("status").asText());assertEquals("failed",node(trace,"embedding").path("status").asText());assertEquals("cancelled",node(trace,"chunking").path("status").asText());assertFalse(trace.toString().contains("PRIVATE_MODEL_DETAIL"));
  }
  DocumentProcessingHandler faultHandler(DocumentProcessingHandler.Files files,KnowledgeFileUpload.Enqueuer producer) {
    return new DocumentProcessingHandler(processing,json,files,(engine,type)->new com.tencent.weknora.docreader.SimpleFormatReader(),
        (owner,id)->models.embedding(new AuthContext("system-task",owner,"viewer",false,null,false,Set.of(),Set.of()),id),
        indexes,NativeDocumentSources::build,producer,options->{},tracker);
  }
  @Test void cancellationDuringRealEmbeddingClosesOpenTraceWithoutPublishingChunks() throws Exception {
    String id=upload("Cancellation checkpoint during real model call.");TaskRecord doc=claim("default");
    duringModel=()->{jdbc.update("UPDATE knowledges SET parse_status='cancelled' WHERE tenant_id=8 AND id=?",id);assertTrue(tasks.cancel(8,doc.id()));};
    assertThrows(java.util.concurrent.CancellationException.class,()->document.execute(doc,cancellation(doc)));
    assertEquals(0,count("chunks",id));assertEquals(0,count("lite_embeddings",id));
    var trace=get(id,"spans",callerToken,200).path("trace");assertEquals("cancelled",trace.path("status").asText());
    for(String stage:List.of("chunking","embedding"))assertEquals("cancelled",node(trace,stage).path("status").asText());
    assertEquals("USER_CANCELLED",trace.path("error_code").asText());
  }
  @Test void durableEnqueueFailureRollsBackPublicationAndKeepsStageCountsHonest() throws Exception {
    String id=upload("Rollback after vector staging when durable enqueue fails.");TaskRecord doc=claim("default");
    var handler=faultHandler((owner,kb,path)->storage.forKnowledgeBase(owner,kb).files().open(path),(owner,type,payload,options)->{throw new IOException("PRIVATE_ENQUEUE_DETAIL");});
    var error=assertThrows(TaskFailure.class,()->handler.execute(doc,cancellation(doc)));assertTrue(error.retryable());
    assertEquals(0,count("chunks",id));assertEquals(0,count("lite_embeddings",id));
    var trace=get(id,"stages",callerToken,200).path("trace");assertEquals("failed",trace.path("status").asText());
    assertFalse(node(trace,"chunking").has("output"));assertFalse(node(trace,"embedding").has("output"));assertFalse(trace.toString().contains("PRIVATE_ENQUEUE_DETAIL"));
    assertEquals("VECTORSTORE_WRITE_FAILED",node(trace,"embedding").path("error_code").asText());
  }
  @Test void initialQueueRetryUsesFreshWorkerFallbackAttemptAndPreservesFailedHistory() throws Exception {
    String id=upload("A real initial upload is retried after temporary storage failure.");TaskRecord first=claim("default");
    var handler=faultHandler((owner,kb,path)->{throw new IOException("PRIVATE_STORAGE_ENDPOINT");},(owner,type,payload,options)->{throw new AssertionError("storage failure must not enqueue postprocessing");});
    var error=assertThrows(TaskFailure.class,()->handler.execute(first,cancellation(first)));assertTrue(error.retryable());assertTrue(tasks.fail(first,error.getMessage(),true,Duration.ZERO));
    assertEquals(1,spans.latest(id));assertEquals("failed",get(id,"spans?attempt=1",callerToken,200).path("trace").path("status").asText());
    TaskRecord retry=claim("default");assertEquals(2,retry.attempts());assertEquals(0,retry.payload().path("attempt").asLong());assertTrue(document.execute(retry,cancellation(retry)).path("indexed").asBoolean());tasks.complete(retry,json.createObjectNode());
    TaskRecord post=claim("postprocess");assertEquals(2,post.payload().path("attempt").asLong());assertTrue(postprocess.execute(post,cancellation(post)).path("completed").asBoolean());
    var data=get(id,"spans",callerToken,200);assertEquals(2,data.path("attempt").asLong());assertEquals("done",data.path("trace").path("status").asText());assertEquals("failed",get(id,"spans?attempt=1",callerToken,200).path("trace").path("status").asText());
  }
  @Test void modelRateLimitUsesGoPublicCodeAndDoesNotPublishPlannedChunks() throws Exception {
    modelFailure=true;modelFailureStatus=429;String id=upload("A rate-limited native embedding run.");TaskRecord doc=claim("default");assertTrue(document.execute(doc,cancellation(doc)).has("failed"));
    assertEquals(0,count("chunks",id));var data=get(id,"spans",callerToken,200);assertEquals("EMBEDDING_RATE_LIMIT",data.path("last_error").path("code").asText());assertEquals("failed",data.path("trace").path("status").asText());
  }
  @Test void alreadyCanceledDeliveryClosesExistingRootBeforeAnyParsing() throws Exception {
    String id=upload("Cancellation before delivery starts.");tracker.open(8,id,"");TaskRecord doc=claim("default");jdbc.update("UPDATE knowledges SET parse_status='cancelled' WHERE tenant_id=8 AND id=?",id);tasks.cancel(8,doc.id());
    assertThrows(java.util.concurrent.CancellationException.class,()->document.execute(doc,cancellation(doc)));assertEquals(0,MODEL_CALLS.get());assertEquals("cancelled",get(id,"spans",callerToken,200).path("trace").path("status").asText());
  }
  @Test void lostLeaseDoesNotCancelSuccessorTraceAndStaleSourceDoesNotAllocateOrAbortNewestAttempt() throws Exception {
    String id=upload("Existing attempt continues in a successor worker.");tracker.open(8,id,"");TaskRecord doc=claim("default");
    jdbc.update("UPDATE java_task_jobs SET lease_owner='successor-worker' WHERE id=?",doc.id());assertThrows(java.util.concurrent.CancellationException.class,()->document.execute(doc,cancellation(doc)));
    assertEquals("running",get(id,"spans",callerToken,200).path("trace").path("status").asText());
    jdbc.update("UPDATE java_task_jobs SET lease_owner=? WHERE id=?",doc.leaseOwner(),doc.id());jdbc.update("UPDATE knowledges SET file_path='new-source',parse_status='processing' WHERE tenant_id=8 AND id=?",id);
    assertEquals("knowledge_no_longer_current",document.execute(doc,cancellation(doc)).path("skipped").asText());assertEquals(1,spans.latest(id));assertEquals("running",get(id,"spans",callerToken,200).path("trace").path("status").asText());assertEquals(0,MODEL_CALLS.get());
  }
  @Autowired SummaryGenerationHandler summary;
  @Test void summaryWithoutModelClosesItsNativeSubtaskAndKeepsSuccessfulParsingTruthful() throws Exception {
    String id=upload("Native parsing succeeds and missing summary model is handled by its own task.");
    jdbc.update("UPDATE knowledges SET metadata=? WHERE tenant_id=8 AND id=?","{\"process_overrides\":{\"summary_enabled\":true}}",id);
    TaskRecord doc=claim("default");assertTrue(document.execute(doc,cancellation(doc)).path("indexed").asBoolean());tasks.complete(doc,json.createObjectNode());
    TaskRecord post=claim("postprocess");assertTrue(postprocess.execute(post,cancellation(post)).path("enqueued_summary").asBoolean());tasks.complete(post,json.createObjectNode());
    TaskRecord job=claim("summary");assertEquals("no_summary_model",summary.execute(job,cancellation(job)).path("skipped").asText());tasks.complete(job,json.createObjectNode());
    var trace=get(id,"stages",callerToken,200).path("trace");assertEquals("done",trace.path("status").asText());assertEquals("done",node(trace,"docreader").path("status").asText());assertEquals("done",node(trace,"chunking").path("status").asText());assertEquals("done",node(trace,"postprocess").path("status").asText());assertTrue(count("chunks",id)>0);
    assertEquals("failed",jdbc.queryForObject("SELECT summary_status FROM knowledges WHERE tenant_id=8 AND id=?",String.class,id));assertEquals("completed",jdbc.queryForObject("SELECT parse_status FROM knowledges WHERE tenant_id=8 AND id=?",String.class,id));
  }
}
