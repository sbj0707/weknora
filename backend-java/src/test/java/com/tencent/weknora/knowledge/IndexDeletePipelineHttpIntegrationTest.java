package com.tencent.weknora.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import com.tencent.weknora.retrieval.*;
import com.tencent.weknora.task.*;
import java.net.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.jdbc.core.ConnectionCallback;

/** Production cleanup bean, real shared upload/model/indexes, pool one, and durable low queue. */
@org.springframework.boot.test.context.SpringBootTest(properties={"weknora.jwt-secret=isolated-native-pipeline-trace-secret","weknora.bridge.enabled=false","weknora.knowledge.processing.enabled=true","weknora.knowledge.upload.enabled=true","weknora.tasks.enabled=true","weknora.tasks.poll-millis=3600000","weknora.retrieve-driver=sqlite","SSRF_WHITELIST=127.0.0.1","SSRF_DNS_WHITELIST_ONLY=true","NEO4J_ENABLE=false","logging.level.root=WARN","debug=false"})
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class IndexDeletePipelineHttpIntegrationTest {
  @org.springframework.test.context.DynamicPropertySource static void properties(org.springframework.test.context.DynamicPropertyRegistry p){KnowledgeProcessingSpanPipelineHttpIntegrationTest.properties(p);p.add("spring.datasource.hikari.maximum-pool-size",()->1);}
  @Autowired AutowireCapableBeanFactory beans;
  @Autowired IndexDeleteHandler cleanup;
  final KnowledgeProcessingSpanPipelineHttpIntegrationTest f=new KnowledgeProcessingSpanPipelineHttpIntegrationTest();
  final ObjectMapper json=new ObjectMapper();
  final AtomicInteger calls=new AtomicInteger();
  HttpServer server;
  @BeforeEach void setup()throws Exception{
    beans.autowireBean(f);f.setup();
    f.jdbc.update("UPDATE knowledge_bases SET tenant_id=8,deleted_at=NULL,profile_config='{}',auto_tag_config='{}',question_generation_config='{}' WHERE id='trace-kb'");
    server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/v1/embeddings",exchange->{try{
      calls.incrementAndGet();var request=json.readTree(exchange.getRequestBody());var data=json.createArrayNode();for(int i=0;i<request.path("input").size();i++)data.add(json.createObjectNode().put("index",i).set("embedding",json.createArrayNode().add(1).add(0).add(0)));
      byte[] body=json.writeValueAsBytes(json.createObjectNode().set("data",data));exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);try(var out=exchange.getResponseBody()){out.write(body);}
    }finally{exchange.close();}});server.start();
    var params=json.createObjectNode().put("provider","generic").put("base_url","http://127.0.0.1:"+server.getAddress().getPort()+"/v1").set("embedding_parameters",json.createObjectNode().put("dimension",3));
    f.jdbc.update("UPDATE models SET parameters=?,type='Embedding' WHERE id='trace-embedding'",json.writeValueAsBytes(params));
  }
  @AfterEach void release(){if(server!=null)server.stop(0);f.jdbc.update("UPDATE knowledge_bases SET tenant_id=8,deleted_at=NULL WHERE id='trace-kb'");f.jdbc.update("UPDATE models SET type='Embedding' WHERE id='trace-embedding'");}
  String prepared()throws Exception{
    String id=f.upload("Shared document cleanup checkpoints "+UUID.randomUUID());
    var doc=f.claim("default");f.document.execute(doc,f.cancellation(doc));assertTrue(f.tasks.complete(doc,json.createObjectNode()));
    var post=f.claim("postprocess");f.postprocess.execute(post,f.cancellation(post));assertTrue(f.tasks.complete(post,json.createObjectNode()));return id;
  }
  ObjectNode payload(String kb,List<String> ids){return json.createObjectNode().put("tenant_id",8).put("knowledge_base_id",kb).put("embedding_model_id","trace-embedding").put("kb_type","document").set("chunk_ids",json.valueToTree(ids));}
  ObjectNode engines(ObjectNode payload){payload.set("effective_engines",json.createArrayNode().add(json.createObjectNode().put("retriever_engine_type","sqlite").put("retriever_type","vector")).add(json.createObjectNode().put("retriever_engine_type","sqlite").put("retriever_type","keywords")));return payload;}
  TaskRecord job(ObjectNode payload){f.tasks.enqueue(8,"index:delete",null,payload,11,Duration.ZERO,"low",Duration.ofHours(1));return f.claim("low");}
  List<String> ids(String knowledge){return f.jdbc.queryForList("SELECT id FROM chunks WHERE tenant_id=8 AND knowledge_id=? ORDER BY chunk_index",String.class,knowledge);}
  @Test void productionCleanupUsesSnapshotAndSoftDeletedBindingsWithoutChargingOrCallingEmbedding()throws Exception{
    String id=prepared();var ids=ids(id);assertFalse(ids.isEmpty());long before=f.count("lite_embeddings",id),storage=f.jdbc.queryForObject("SELECT storage_used FROM tenants WHERE id=8",Long.class);int modelCalls=calls.get();
    var payload=engines(payload("trace-kb",ids));
    f.jdbc.update("UPDATE chunks SET deleted_at=CURRENT_TIMESTAMP WHERE knowledge_id=?",id);f.jdbc.update("UPDATE knowledge_bases SET deleted_at=CURRENT_TIMESTAMP WHERE id='trace-kb'");
    // Cleanup resolves the captured model as Go does, even if its declared type has changed.
    f.jdbc.update("UPDATE models SET type='KnowledgeQA' WHERE id='trace-embedding'");
    var job=job(payload);assertEquals(ids.size(),cleanup.execute(job,f.cancellation(job)).path("chunk_ids").asInt());assertTrue(f.tasks.complete(job,json.createObjectNode()));
    assertEquals(0,f.count("lite_embeddings",id));assertEquals(modelCalls,calls.get());assertEquals(storage,f.jdbc.queryForObject("SELECT storage_used FROM tenants WHERE id=8",Long.class));
    assertEquals(0,f.jdbc.queryForObject("SELECT pending_subtasks_count FROM knowledges WHERE id=?",Integer.class,id));assertTrue(before>0);
    f.jdbc.execute((ConnectionCallback<Void>)connection->{new SQLiteVectorExtension(Path.of(System.getProperty("weknora.sqlite.vec.extension"))).load(connection);try(var statement=connection.prepareStatement("SELECT COUNT(*) FROM vec_embeddings_3 WHERE rowid NOT IN (SELECT id FROM lite_embeddings)")){try(var rs=statement.executeQuery()){rs.next();assertEquals(0,rs.getInt(1));}}return null;});
    assertEquals("caller",f.jdbc.queryForObject("SELECT actor_user_id FROM audit_logs WHERE action='knowledge.created' AND target_id=? ORDER BY created_at DESC LIMIT 1",String.class,id));
  }
  @Test void wrongKnowledgeBaseAndChangedOwnerNeverDeletePreviouslyPublishedIndexes()throws Exception{
    String id=prepared();long count=f.count("lite_embeddings",id);
    var wrong=job(engines(payload("ungranted",ids(id))));assertEquals(0,cleanup.execute(wrong,f.cancellation(wrong)).path("chunk_ids").asInt());assertTrue(f.tasks.complete(wrong,json.createObjectNode()));
    f.jdbc.update("UPDATE knowledge_bases SET tenant_id=9 WHERE id='trace-kb'");
    var changed=job(engines(payload("trace-kb",ids(id))));assertEquals(0,cleanup.execute(changed,f.cancellation(changed)).path("chunk_ids").asInt());assertTrue(f.tasks.complete(changed,json.createObjectNode()));
    assertEquals(count,f.count("lite_embeddings",id));assertEquals(1,calls.get());
  }
  @Test void emptyStoreSetAcknowledgesButStillChecksTheCapturedModelAndBoundStoresAreExplicit()throws Exception{
    String id=prepared();long count=f.count("lite_embeddings",id);
    var empty=job(payload("trace-kb",ids(id)));assertEquals(0,cleanup.execute(empty,f.cancellation(empty)).path("chunk_ids").asInt());assertTrue(f.tasks.complete(empty,json.createObjectNode()));
    var missing=job(payload("trace-kb",ids(id)).put("embedding_model_id","missing"));var failed=assertThrows(TaskFailure.class,()->cleanup.execute(missing,f.cancellation(missing)));assertTrue(failed.retryable());assertTrue(f.tasks.fail(missing,failed.getMessage(),false,Duration.ZERO));
    var bound=job(engines(payload("trace-kb",ids(id))).put("vector_store_id","not-migrated-store"));var unsupported=assertThrows(TaskFailure.class,()->cleanup.execute(bound,f.cancellation(bound)));assertFalse(unsupported.retryable());assertTrue(f.tasks.fail(bound,unsupported.getMessage(),false,Duration.ZERO));
    assertEquals(count,f.count("lite_embeddings",id));assertEquals(1,calls.get());
  }
}
