package com.tencent.weknora.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Adds public chunk transactions to the actual upload/summary/authentication pipeline. */
class ChunkEditPipelineHttpIntegrationTest extends KnowledgeUpdatePipelineHttpIntegrationTest {
  String bodyChunk(String doc){return s.f.jdbc.queryForObject("SELECT id FROM chunks WHERE knowledge_id=? AND chunk_type='text' ORDER BY chunk_index LIMIT 1",String.class,doc);}
  JsonNode change(String doc,String chunk,String credential,boolean machine,boolean revert,int status,String body)throws Exception {
    var request=revert?MockMvcRequestBuilders.post("/api/v1/chunks/"+doc+"/"+chunk+"/revert"):MockMvcRequestBuilders.put("/api/v1/chunks/"+doc+"/"+chunk);
    var response=s.f.mvc.perform(request.contentType("application/json").content(body).header(machine?"X-API-Key":"Authorization",machine?credential:"Bearer "+credential)).andReturn().getResponse();assertEquals(status,response.getStatus(),response.getContentAsString());return json.readTree(response.getContentAsByteArray());
  }
  List<Object> source(String doc)throws Exception{return retained(doc).subList(0,4);}
  void refresh()throws Exception{var task=s.f.claim("summary");assertTrue(task.payload().path("refresh").asBoolean());assertEquals(8,task.payload().path("tenant_id").asLong());assertEquals(1,task.payload().path("attempt").asLong());assertEquals(4,task.maxAttempts());assertEquals(1800000,task.timeoutMillis());assertEquals("completed",s.f.summary.execute(task,s.f.cancellation(task)).path("status").asText());assertTrue(s.f.tasks.complete(task,json.createObjectNode()));}
  @Test void sharedEditPreservesQuestionsAndRunsOwnerSummaryRefresh()throws Exception {
    String doc=prepared(),chunk=bodyChunk(doc);var before=source(doc);String oldSummary=s.f.jdbc.queryForObject("SELECT id FROM chunks WHERE knowledge_id=? AND chunk_type='summary'",String.class,doc);
    s.f.jdbc.update("UPDATE chunks SET metadata=? WHERE id=?","{\"generated_questions\":[{\"id\":\"old\",\"question\":\"Existing retrieval alias 中文\",\"content_revision\":0}]}",chunk);s.f.jdbc.update("UPDATE tenant_members SET role='contributor' WHERE user_id='caller'");
    int calls=s.embeddings.get();var result=change(doc,chunk,s.f.callerToken,false,false,200,"{\"content\":\"Edited source evidence 中文\",\"expected_revision\":0}");
    assertEquals(1,result.path("data").path("content_revision").asInt());assertEquals("caller",result.path("data").path("last_editor_id").asText());assertEquals("ready",result.path("data").path("index_status").asText());assertEquals("pending",result.path("summary_status").asText());assertFalse(result.path("data").has("source_locators"));assertEquals(0,result.path("data").path("metadata").path("generated_questions").get(0).path("content_revision").asInt());assertEquals(calls+1,s.embeddings.get());assertEquals(before,source(doc));
    assertTrue(s.f.jdbc.queryForObject("SELECT content FROM lite_embeddings WHERE source_id=?",String.class,chunk+"-old").contains("Existing retrieval alias 中文"));var bodyIndexes=s.f.jdbc.queryForList("SELECT source_id,content FROM lite_embeddings WHERE chunk_id=? ORDER BY source_id",chunk);refresh();assertEquals(bodyIndexes,s.f.jdbc.queryForList("SELECT source_id,content FROM lite_embeddings WHERE chunk_id=? ORDER BY source_id",chunk));assertEquals(before,source(doc));assertEquals(oldSummary,s.f.jdbc.queryForObject("SELECT id FROM chunks WHERE knowledge_id=? AND chunk_type='summary'",String.class,doc));
  }
  @Test void creatorViewerRevertSnapshotsBothEditsAndRetainsOriginalFile()throws Exception {
    String doc=prepared(),chunk=bodyChunk(doc);var before=source(doc);String original=s.f.jdbc.queryForObject("SELECT content FROM chunks WHERE id=?",String.class,chunk);s.f.jdbc.update("UPDATE tenant_members SET role='viewer' WHERE user_id='owner' AND tenant_id=8");String token=s.f.token("owner",8);
    change(doc,chunk,token,false,false,200,"{\"content\":\"A revised passage\",\"expected_revision\":0}");refresh();var result=change(doc,chunk,token,false,true,200,"{\"revision\":0,\"expected_revision\":1}");assertEquals(original,result.path("data").path("content").asText());assertEquals(2,result.path("data").path("content_revision").asInt());assertFalse(result.path("data").has("source_locators"));refresh();
    assertEquals(List.of(original,"A revised passage"),s.f.jdbc.queryForList("SELECT content FROM chunk_revisions WHERE chunk_id=? ORDER BY revision",String.class,chunk));assertEquals(before,source(doc));assertEquals(original,s.f.jdbc.queryForObject("SELECT source_content FROM chunks WHERE id=?",String.class,chunk));
  }
  @Test void scopePrecedesBinderAndIndexWriteFailureKeepsAcceptedVersion()throws Exception {
    String doc=prepared(),chunk=bodyChunk(doc);var before=source(doc);String ingest=key("ingest",false,"trace-kb"),retrieve=key("retrieve",false,"trace-kb"),wrong=key("ingest",true,"other-kb");
    for(boolean revert:List.of(false,true)){change(doc,chunk,retrieve,true,revert,403,"invalid");change(doc,chunk,wrong,true,revert,403,"invalid");change(doc,chunk,s.f.foreignToken,false,revert,403,"invalid");change(doc,chunk,ingest,true,revert,400,"invalid");}assertEquals(0,s.f.jdbc.queryForObject("SELECT COUNT(*) FROM chunk_revisions WHERE chunk_id=?",Integer.class,chunk));assertEquals(before,source(doc));
    s.f.jdbc.execute("CREATE TRIGGER reject_chunk_edit_index BEFORE INSERT ON lite_embeddings BEGIN SELECT RAISE(ABORT,'controlled accepted edit failure'); END");
    try{var result=change(doc,chunk,ingest,true,false,200,"{\"content\":\"Saved despite physical indexing error\",\"expected_revision\":0}");assertEquals("failed",result.path("data").path("index_status").asText());assertEquals(1,result.path("data").path("content_revision").asInt());assertEquals(0,s.f.jdbc.queryForObject("SELECT COUNT(*) FROM lite_embeddings WHERE chunk_id=?",Integer.class,chunk));assertEquals(1,s.f.jdbc.queryForObject("SELECT COUNT(*) FROM chunk_revisions WHERE chunk_id=?",Integer.class,chunk));assertEquals(before,source(doc));}finally{s.f.jdbc.execute("DROP TRIGGER reject_chunk_edit_index");}
    var result=change(doc,chunk,ingest,true,false,200,"{}");assertEquals("ready",result.path("data").path("index_status").asText());assertEquals(1,result.path("data").path("content_revision").asInt());assertEquals(1,s.f.jdbc.queryForObject("SELECT COUNT(*) FROM lite_embeddings WHERE chunk_id=?",Integer.class,chunk));assertEquals(1,s.f.jdbc.queryForObject("SELECT COUNT(*) FROM chunk_revisions WHERE chunk_id=?",Integer.class,chunk));refresh();
  }
}
