package com.tencent.weknora.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Actual JWT/API gates and physical alias updates after native upload and summary completion. */
class ChunkQuestionPipelineHttpIntegrationTest extends KnowledgeUpdatePipelineHttpIntegrationTest {
  String chunk(String doc){return s.f.jdbc.queryForObject("SELECT id FROM chunks WHERE knowledge_id=? AND chunk_type='text' ORDER BY chunk_index LIMIT 1",String.class,doc);}
  JsonNode question(String chunk,String credential,boolean machine,boolean delete,int status,String body)throws Exception{
    var request=delete?MockMvcRequestBuilders.delete("/api/v1/chunks/by-id/"+chunk+"/questions"):MockMvcRequestBuilders.put("/api/v1/chunks/by-id/"+chunk+"/questions");
    var response=s.f.mvc.perform(request.contentType("application/json").content(body).header(machine?"X-API-Key":"Authorization",machine?credential:"Bearer "+credential)).andReturn().getResponse();assertEquals(status,response.getStatus(),response.getContentAsString());return json.readTree(response.getContentAsByteArray());
  }
  @Test void creatorViewerCreatesUpdatesAndDeletesOnlyOneQuestionAlias()throws Exception{
    String doc=prepared(),chunk=chunk(doc);var source=retained(doc).subList(0,4);long jobs=s.f.jdbc.queryForObject("SELECT COUNT(*) FROM java_task_jobs",Long.class);int chats=s.chats.get();
    s.f.jdbc.update("UPDATE tenant_members SET role='viewer' WHERE user_id='owner' AND tenant_id=8");String owner=s.f.token("owner",8);
    var created=question(chunk,owner,false,false,200,"{\"question\":\"  Question alias 中文  \"}").path("data");String id=created.path("id").asText();UUID.fromString(id);assertEquals("Question alias 中文",created.path("question").asText());assertEquals(0,created.path("content_revision").asInt());
    String alias=chunk+"-q"+HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(id.getBytes(java.nio.charset.StandardCharsets.UTF_8)),0,12);
    var updated=question(chunk,owner,false,false,200,json.writeValueAsString(Map.of("question_id",id,"question","updatedaliasmarker 中文"))).path("data");assertEquals(id,updated.path("id").asText());assertTrue(s.f.jdbc.queryForObject("SELECT content FROM lite_embeddings WHERE source_id=?",String.class,alias).contains("updatedaliasmarker"));
    var body=s.f.jdbc.queryForList("SELECT * FROM lite_embeddings WHERE source_id=?",chunk);int embeddings=s.embeddings.get();question(chunk,owner,false,true,200,json.writeValueAsString(Map.of("question_id",id)));
    assertEquals(body,s.f.jdbc.queryForList("SELECT * FROM lite_embeddings WHERE source_id=?",chunk));assertEquals(0,s.f.jdbc.queryForObject("SELECT COUNT(*) FROM lite_embeddings WHERE source_id=?",Integer.class,alias));assertEquals(0,json.readTree(s.f.jdbc.queryForObject("SELECT metadata FROM chunks WHERE id=?",String.class,chunk)).path("generated_questions").size());assertEquals(0,s.f.jdbc.queryForObject("SELECT COUNT(*) FROM chunk_revisions WHERE chunk_id=?",Integer.class,chunk));assertEquals(embeddings,s.embeddings.get());assertEquals(chats,s.chats.get());assertEquals(jobs,s.f.jdbc.queryForObject("SELECT COUNT(*) FROM java_task_jobs",Long.class));assertEquals(source,retained(doc).subList(0,4));
  }
  @Test void sharedActorAndApiScopesKeepCommittedQuestionWhenIndexPublicationFails()throws Exception{
    String doc=prepared(),chunk=chunk(doc);var source=retained(doc).subList(0,4);long jobs=s.f.jdbc.queryForObject("SELECT COUNT(*) FROM java_task_jobs",Long.class);s.f.jdbc.update("UPDATE tenant_members SET role='contributor' WHERE user_id='caller'");
    String retrieve=key("retrieve",false,"trace-kb"),ingest=key("ingest",false,"trace-kb"),wrong=key("ingest",true,"other-kb");
    for(boolean delete:List.of(false,true)){question(chunk,retrieve,true,delete,403,"invalid");question(chunk,wrong,true,delete,403,"invalid");question(chunk,s.f.foreignToken,false,delete,403,"invalid");question(chunk,ingest,true,delete,400,"invalid");}
    var created=question(chunk,s.f.callerToken,false,false,200,"{\"question\":\"Original shared alias\"}");String id=created.path("data").path("id").asText();
    s.f.jdbc.execute("CREATE TRIGGER reject_question_index BEFORE INSERT ON lite_embeddings BEGIN SELECT RAISE(ABORT,'controlled question publication failure'); END");
    try{var failure=question(chunk,ingest,true,false,400,json.writeValueAsString(Map.of("question_id",id,"question","Persisted updated alias")));assertTrue(failure.path("error").path("message").asText().contains("controlled question publication failure"));}finally{s.f.jdbc.execute("DROP TRIGGER reject_question_index");}
    var metadata=json.readTree(s.f.jdbc.queryForObject("SELECT metadata FROM chunks WHERE id=?",String.class,chunk));assertEquals("Persisted updated alias",metadata.path("generated_questions").get(0).path("question").asText());assertEquals(0,s.f.jdbc.queryForObject("SELECT COUNT(*) FROM lite_embeddings WHERE chunk_id=?",Integer.class,chunk));assertEquals(0,s.f.jdbc.queryForObject("SELECT content_revision FROM chunks WHERE id=?",Integer.class,chunk));
    question(chunk,ingest,true,false,200,json.writeValueAsString(Map.of("question_id",id,"question","Persisted updated alias")));assertEquals(2,s.f.jdbc.queryForObject("SELECT COUNT(*) FROM lite_embeddings WHERE chunk_id=?",Integer.class,chunk));assertEquals(jobs,s.f.jdbc.queryForObject("SELECT COUNT(*) FROM java_task_jobs",Long.class));assertEquals(source,retained(doc).subList(0,4));
  }
}
