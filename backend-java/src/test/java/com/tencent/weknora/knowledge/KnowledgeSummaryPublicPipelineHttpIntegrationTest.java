package com.tencent.weknora.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Production JWT/API filters, shared owner, real model HTTP, SQL queue and physical SQLite indexes. */
class KnowledgeSummaryPublicPipelineHttpIntegrationTest extends KnowledgeUpdatePipelineHttpIntegrationTest {
  JsonNode regenerate(String doc,String credential,boolean machine,int expected)throws Exception{
    var request=MockMvcRequestBuilders.post("/api/v1/knowledge/"+doc+"/regenerate-summary")
        .header(machine?"X-API-Key":"Authorization",machine?credential:"Bearer "+credential)
        .header("Accept-Language","en-US").contentType("application/json").content("ignored invalid JSON");
    var response=s.f.mvc.perform(request).andReturn().getResponse();
    assertEquals(expected,response.getStatus(),response.getContentAsString());return json.readTree(response.getContentAsByteArray());
  }

  @Test void firstSummaryIsSynchronousAndCreatorViewerKeepsSourceAndTaskAccounting()throws Exception{
    String id=prepared();s.f.jdbc.update("UPDATE knowledges SET summary_status='none' WHERE id=?",id);
    var retained=retained(id);String summary=s.f.jdbc.queryForObject("SELECT id FROM chunks WHERE knowledge_id=? AND chunk_type='summary'",String.class,id);
    long tasks=s.f.jdbc.queryForObject("SELECT COUNT(*) FROM java_task_jobs",Long.class);int chats=s.chats.get(),embeddings=s.embeddings.get();
    s.f.jdbc.update("UPDATE tenant_members SET role='viewer' WHERE user_id='owner' AND tenant_id=8");
    var response=regenerate(id,s.f.token("owner",8),false,200);
    assertEquals("completed",response.path("data").path("summary_status").asText());assertEquals("Native objective summary 中文😀.",response.path("data").path("description").asText());
    assertEquals(retained,retained(id));assertEquals(summary,s.f.jdbc.queryForObject("SELECT id FROM chunks WHERE knowledge_id=? AND chunk_type='summary'",String.class,id));
    assertEquals(tasks,s.f.jdbc.queryForObject("SELECT COUNT(*) FROM java_task_jobs",Long.class));assertEquals(chats+1,s.chats.get());assertEquals(embeddings+1,s.embeddings.get());
    assertFalse(indexes(id).path("vectors").isEmpty());assertEquals(s.f.count("chunks",id),s.f.count("lite_embeddings",id));
    assertTrue(s.requests.stream().anyMatch(r->r.path("messages").toString().contains("English")));
  }

  @Test void sharedRefreshQueuesUnderOwnerAndRunsExistingWorkerWithoutReplacingChunk()throws Exception{
    String id=prepared();var retained=retained(id);String summary=s.f.jdbc.queryForObject("SELECT id FROM chunks WHERE knowledge_id=? AND chunk_type='summary'",String.class,id);
    int chats=s.chats.get();s.f.jdbc.update("UPDATE tenant_members SET role='contributor' WHERE user_id='caller'");
    var response=regenerate(id,s.f.callerToken,false,200);assertEquals(8,response.path("data").path("tenant_id").asLong());assertEquals("pending",response.path("data").path("summary_status").asText());assertEquals(chats,s.chats.get());
    var job=s.f.claim("summary");assertEquals(8,job.tenantId());assertEquals(8,job.payload().path("tenant_id").asLong());assertEquals(1,job.payload().path("attempt").asLong());assertEquals("en-US",job.payload().path("language").asText());assertTrue(job.payload().path("refresh").asBoolean());assertEquals(4,job.maxAttempts());assertEquals(1800000,job.timeoutMillis());
    assertEquals("completed",s.f.summary.execute(job,s.f.cancellation(job)).path("status").asText());assertTrue(s.f.tasks.complete(job,json.createObjectNode()));
    assertEquals(retained,retained(id));assertEquals(summary,s.f.jdbc.queryForObject("SELECT id FROM chunks WHERE knowledge_id=? AND chunk_type='summary'",String.class,id));assertEquals(chats+1,s.chats.get());
  }

  @Test void apiIngestGateAllowsRefreshAndRejectsOtherScopesBeforeAnyEffects()throws Exception{
    String id=prepared();var retained=retained(id);long count=s.f.jdbc.queryForObject("SELECT COUNT(*) FROM java_task_jobs",Long.class);
    String ingest=key("ingest",false,"trace-kb"),retrieve=key("retrieve",false,"trace-kb"),wrong=key("ingest",true,"other-kb");
    regenerate(id,retrieve,true,403);regenerate(id,wrong,true,403);regenerate(id,s.f.foreignToken,false,403);
    assertEquals(count,s.f.jdbc.queryForObject("SELECT COUNT(*) FROM java_task_jobs",Long.class));assertEquals(retained,retained(id));
    assertEquals("pending",regenerate(id,ingest,true,200).path("data").path("summary_status").asText());assertEquals(count+1,s.f.jdbc.queryForObject("SELECT COUNT(*) FROM java_task_jobs",Long.class));
  }

  @Test void explicitQueueFailureIsVisibleAndDoesNotMutateSourceOrIndexes()throws Exception{
    String id=prepared();var retained=retained(id);var physical=indexes(id);long count=s.f.jdbc.queryForObject("SELECT COUNT(*) FROM java_task_jobs",Long.class);
    s.f.jdbc.execute("CREATE TRIGGER reject_update_refresh BEFORE INSERT ON java_task_jobs WHEN NEW.type='summary:generation' BEGIN SELECT RAISE(ABORT,'controlled refresh rejection'); END");
    var response=regenerate(id,s.f.callerToken,false,400);assertEquals(1000,response.path("error").path("code").asInt());assertEquals("failed",s.state(id,"summary_status"));assertEquals(retained,retained(id));assertEquals(physical,indexes(id));assertEquals(count,s.f.jdbc.queryForObject("SELECT COUNT(*) FROM java_task_jobs",Long.class));
  }
}
