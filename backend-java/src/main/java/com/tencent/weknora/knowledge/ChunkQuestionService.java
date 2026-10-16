package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.security.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Public manual question mutations retain body revisions and replace the actual retrieval aliases. */
@Service
@ConditionalOnProperty(name="weknora.knowledge.processing.enabled",havingValue="true")
public final class ChunkQuestionService {
  private final JdbcTemplate jdbc;private final ObjectMapper json;private final KnowledgeBaseWriteAuthorization writes;private final ChunkEditService chunks;private final boolean pg;
  public ChunkQuestionService(JdbcTemplate jdbc,ObjectMapper json,KnowledgeBaseWriteAuthorization writes,ChunkEditService chunks){this.jdbc=jdbc;this.json=json;this.writes=writes;this.chunks=chunks;pg=Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)c->c.getMetaData().getDatabaseProductName().equals("PostgreSQL")));}
  public KnowledgeBaseWriteAuthorization.WriteAccess authorize(AuthContext caller,String id,String path,String method){
    ChunkLifecycleService.capability(caller,"ingest");var rows=jdbc.queryForList("SELECT * FROM chunks WHERE id=? AND deleted_at IS NULL",id);if(rows.isEmpty()||text(rows.getFirst(),"knowledge_base_id").isEmpty())throw new ApiException(404,"NOT_FOUND","Chunk not found").withAppCode(1003);var row=rows.getFirst();
    // Go's ownership lookup follows chunk -> document -> KB before the separate denormalized KB access guard.
    if(!caller.isApiKey()&&!caller.isAtLeast("admin")&&number(row,"tenant_id")==caller.tenantId()){
      var documents=jdbc.queryForList("SELECT * FROM knowledges WHERE id=? AND tenant_id=? AND deleted_at IS NULL",text(row,"knowledge_id"),caller.tenantId());
      if(!documents.isEmpty())writes.requireOwnership(caller,text(documents.getFirst(),"knowledge_base_id"),path,"/api/v1/chunks/by-id/:id/questions"+(method.equals("POST")?"/regenerate":""),method);
    }
    return writes.authorizeChunkQuestion(caller,text(row,"knowledge_base_id"),path,method);
  }
  public Map<String,Object> upsert(KnowledgeBaseWriteAuthorization.WriteAccess grant,String id,ChunkQuestionRequest request){
    try{
      String question=KnowledgeUploadForms.goTrim(request.question());if(question.isEmpty())throw new IllegalStateException("question cannot be empty");
      var row=chunks.writableQuestion(grant,id);var metadata=chunks.questionMetadata(row);var questions=metadata.path("generated_questions").isArray()?(ArrayNode)metadata.path("generated_questions").deepCopy():json.createArrayNode();String questionId=request.id();ObjectNode selected=null;
      if(questionId.isEmpty()){questionId=UUID.randomUUID().toString();selected=json.createObjectNode().put("id",questionId).put("question",question).put("content_revision",number(row,"content_revision"));questions.add(selected);}else{for(var item:questions)if(item.path("id").asText().equals(questionId)){selected=(ObjectNode)item;selected.put("question",question).put("content_revision",number(row,"content_revision"));break;}if(selected==null)throw new IllegalStateException("question not found");}
      save(row,serialize(metadata,questions));chunks.reindexQuestion(row);return Map.of("success",true,"data",selected);
    }catch(Exception failure){throw ChunkQuestionRequest.bad(message(failure));}
  }
  public Map<String,Object> delete(KnowledgeBaseWriteAuthorization.WriteAccess grant,String id,ChunkQuestionRequest request){
    Map<String,Object> row;try{row=chunks.writableQuestion(grant,id);}catch(Exception failure){throw ChunkQuestionRequest.bad("failed to get chunk: "+message(failure));}
    JsonNode metadata;try{metadata=chunks.questionMetadata(row);}catch(Exception failure){throw ChunkQuestionRequest.bad("failed to parse chunk metadata: "+message(failure));}
    var questions=metadata.path("generated_questions");if(!questions.isArray()||questions.isEmpty())throw ChunkQuestionRequest.bad("no generated questions found for chunk "+id);
    int found=-1;for(int i=0;i<questions.size();i++)if(questions.get(i).path("id").asText().equals(request.id())){found=i;break;}if(found<0)throw ChunkQuestionRequest.bad("question with ID "+request.id()+" not found in chunk "+id);
    try{chunks.deleteQuestionIndex(row,request.id());}catch(Exception failure){throw ChunkQuestionRequest.bad(message(failure));}
    var remaining=(ArrayNode)questions.deepCopy();remaining.remove(found);try{save(row,serialize(metadata,remaining));}catch(Exception failure){throw ChunkQuestionRequest.bad("failed to update chunk: "+message(failure));}
    return Map.of("success",true,"message","Generated question deleted");
  }
  private ObjectNode serialize(JsonNode metadata,ArrayNode questions){
    var out=json.createObjectNode();if(!questions.isEmpty()){var items=out.putArray("generated_questions");for(var item:questions){var question=json.createObjectNode().put("id",item.path("id").asText("")).put("question",item.path("question").asText(""));if(item.hasNonNull("content_revision"))question.set("content_revision",item.get("content_revision"));items.add(question);}}
    if(metadata.path("generated_questions_revision").asLong()!=0)out.set("generated_questions_revision",metadata.get("generated_questions_revision"));return out;
  }
  void save(Map<String,Object> row,ObjectNode metadata){save(row,metadata,false);}
  void save(Map<String,Object> row,ObjectNode metadata,boolean regenerate){
    String before=raw(row.get("metadata")),next=metadata.toString();Object now=pg?Timestamp.from(Instant.now()):Instant.now().toString();var args=new ArrayList<Object>(Arrays.asList(next,text(row,"source_content"),text(row,"parent_chunk_id"),now,number(row,"tenant_id"),text(row,"id"),number(row,"content_revision"),text(row,"content"),before));String fence="";
    if(regenerate){fence=" AND knowledge_id=? AND knowledge_base_id=? AND is_enabled=? AND EXISTS(SELECT 1 FROM knowledges k JOIN knowledge_bases b ON b.id=k.knowledge_base_id WHERE k.id=chunks.knowledge_id AND k.tenant_id=chunks.tenant_id AND b.tenant_id=chunks.tenant_id AND k.knowledge_base_id=chunks.knowledge_base_id AND k.deleted_at IS NULL AND b.deleted_at IS NULL)";args.add(text(row,"knowledge_id"));args.add(text(row,"knowledge_base_id"));args.add(row.get("is_enabled"));}
    int count=jdbc.update("UPDATE chunks SET metadata="+(pg?"?::jsonb":"?")+",source_content=?,parent_chunk_id=?,updated_at=? WHERE tenant_id=? AND id=? AND content_revision=? AND content=? AND COALESCE(CAST(metadata AS TEXT),'')=? AND deleted_at IS NULL"+fence,args.toArray());if(count!=1)throw new IllegalStateException("chunk revision conflict");row.put("metadata",next);row.put("source_content",text(row,"source_content"));row.put("parent_chunk_id",text(row,"parent_chunk_id"));row.put("updated_at",now);
  }
  static String message(Exception failure){if(failure instanceof ApiException api){if(api.status()==409&&api.getMessage().equals("Chunk was modified by another user; refresh and retry"))return "chunk revision conflict";if(api.appCode()==null)return ChunkEditService.modelDiagnostic(api);return "error code: "+api.appCode()+", error message: "+api.getMessage();}if(failure instanceof org.springframework.dao.DataAccessException)return ChunkEditService.diagnostic(failure);return Objects.toString(failure.getMessage(),"Internal server error");}
  private static long number(Map<String,Object> row,String key){return row.get(key) instanceof Number n?n.longValue():0;}
  private static String text(Map<String,Object> row,String key){return Objects.toString(row.get(key),"");}
  private static String raw(Object value){return value instanceof byte[] bytes?new String(bytes,StandardCharsets.UTF_8):Objects.toString(value,"");}
}
