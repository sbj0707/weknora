package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.model.ModelClientFactory;
import com.tencent.weknora.model.client.*;
import com.tencent.weknora.security.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Synchronous public Doc2Query, independently fenced around chat and embedding. */
@Service
@ConditionalOnProperty(name="weknora.knowledge.processing.enabled",havingValue="true")
public final class ChunkQuestionRegenerationService {
  private final JdbcTemplate jdbc;private final ObjectMapper json;private final ChunkEditService chunks;private final ChunkQuestionService questions;private final ModelClientFactory models;private final QuestionGenerationText text;private final KnowledgeBaseCodec codec;private final boolean pg;
  public ChunkQuestionRegenerationService(JdbcTemplate jdbc,ObjectMapper json,ChunkEditService chunks,ChunkQuestionService questions,ModelClientFactory models,QuestionGenerationText text)throws java.io.IOException{this.jdbc=jdbc;this.json=json;this.chunks=chunks;this.questions=questions;this.models=models;this.text=text;codec=new KnowledgeBaseCodec(json);pg=Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)c->c.getMetaData().getDatabaseProductName().equals("PostgreSQL")));}
  public Map<String,Object> regenerate(KnowledgeBaseWriteAuthorization.WriteAccess grant,String id,String language){
    try{
      var row=chunk(grant.effectiveTenantId(),id);if(!value(row,"chunk_type").equals("text"))throw new IllegalStateException("questions can only be generated for text chunks");
      // The original chain validates the type before resolving document write access.
      chunks.writableQuestion(grant,id);var doc=jdbc.queryForMap("SELECT * FROM knowledges WHERE tenant_id=? AND id=? AND deleted_at IS NULL",grant.effectiveTenantId(),value(row,"knowledge_id"));var base=base(row);var kb=baseConfig(base);
      String modelId=kb.path("summary_model_id").asText();if(modelId.isEmpty())throw new IllegalStateException("summary model is required for question generation");var caller=new AuthContext("system-chunk",grant.effectiveTenantId(),"viewer",false,null,false,Set.of(),Set.of());var model=models.chat(caller,modelId);
      JsonNode overrides=null;try{overrides=json.readTree(raw(doc.get("metadata"))).get("process_overrides");}catch(Exception ignored){}
      var config=new KnowledgeProcessConfig(json).resolve(kb,overrides).path("question_generation_config");int count=QuestionGenerationText.count(config.path("question_count").asInt());List<String> generated=List.of();
      if(!value(row,"content").isEmpty()){
        if(!text.configured())throw new IllegalStateException("generate questions prompt not configured");
        String prompt=text.prompt(value(row,"content"),neighbor(row,"pre_chunk_id"),neighbor(row,"next_chunk_id"),value(doc,"title"),language,count,config.path("custom_instructions").asText());
        try(var call=new CallContext()){try{generated=text.parse(model.chat(call,List.of(json.createObjectNode().put("role","user").put("content",prompt)),text.options()),count);}catch(RuntimeException failure){throw new IllegalStateException("failed to generate questions: "+(model instanceof OpenAIChat?"create chat completion: ":"")+ChunkQuestionService.message(failure),failure);}}
      }
      current(row,doc,base);var items=json.createArrayNode();for(String question:generated)items.add(json.createObjectNode().put("id",UUID.randomUUID().toString()).put("question",question).put("content_revision",number(row,"content_revision")));
      var metadata=json.createObjectNode();if(!items.isEmpty())metadata.set("generated_questions",items);if(number(row,"content_revision")!=0)metadata.put("generated_questions_revision",number(row,"content_revision"));questions.save(row,metadata,true);
      chunks.reindexRegeneratedQuestion(row,()->current(row,doc,base));return Map.of("success",true,"data",items);
    }catch(Exception failure){throw ChunkQuestionRequest.bad(ChunkQuestionService.message(failure));}
  }
  private String neighbor(Map<String,Object> row,String field){if(value(row,field).isEmpty())return "";var rows=jdbc.queryForList("SELECT * FROM chunks WHERE id=? AND tenant_id=? AND knowledge_id=? AND knowledge_base_id=? AND deleted_at IS NULL",value(row,field),number(row,"tenant_id"),value(row,"knowledge_id"),value(row,"knowledge_base_id"));return rows.isEmpty()?"":value(rows.getFirst(),"content");}
  private Map<String,Object> chunk(long tenant,String id){var rows=jdbc.queryForList("SELECT * FROM chunks WHERE tenant_id=? AND id=? AND deleted_at IS NULL",tenant,id);if(rows.isEmpty())throw new IllegalStateException("chunk not found");return new LinkedHashMap<>(rows.getFirst());}
  private Map<String,Object> base(Map<String,Object> row){return jdbc.queryForMap("SELECT * FROM knowledge_bases WHERE id=? AND tenant_id=? AND deleted_at IS NULL",value(row,"knowledge_base_id"),number(row,"tenant_id"));}
  private ObjectNode baseConfig(Map<String,Object> row){var config=new LinkedHashMap<>(row);config.remove("indexing_strategy");if(!pg&&config.get("question_generation_config") instanceof String)config.remove("question_generation_config");return codec.fromRow(config);}
  private void current(Map<String,Object> expected,Map<String,Object> doc,Map<String,Object> base){
    try{
      var now=chunk(number(expected,"tenant_id"),value(expected,"id"));for(String key:List.of("knowledge_id","knowledge_base_id","content","chunk_type","context_header","parent_chunk_id"))if(!value(now,key).equals(value(expected,key)))throw conflict();if(number(now,"content_revision")!=number(expected,"content_revision")||!Objects.equals(now.get("is_enabled"),expected.get("is_enabled"))||!sameJson(now.get("metadata"),expected.get("metadata")))throw conflict();
      var docs=jdbc.queryForList("SELECT * FROM knowledges WHERE tenant_id=? AND id=? AND knowledge_base_id=? AND deleted_at IS NULL",number(expected,"tenant_id"),value(expected,"knowledge_id"),value(expected,"knowledge_base_id"));if(docs.isEmpty())throw conflict();var actual=docs.getFirst();for(String key:List.of("title","parse_status"))if(!value(actual,key).equals(value(doc,key)))throw conflict();if(!sameJson(actual.get("metadata"),doc.get("metadata")))throw conflict();
      var kb=base(expected);for(String key:List.of("summary_model_id","embedding_model_id","type","vector_store_id"))if(!value(kb,key).equals(value(base,key)))throw conflict();if(!baseConfig(kb).path("question_generation_config").equals(baseConfig(base).path("question_generation_config")))throw conflict();
    }catch(Exception failure){throw conflict();}
  }
  private boolean sameJson(Object a,Object b){if(raw(a).equals(raw(b)))return true;try{return json.readTree(raw(a)).equals(json.readTree(raw(b)));}catch(Exception invalid){return false;}}
  private static IllegalStateException conflict(){return new IllegalStateException("chunk revision conflict");}
  private static String raw(Object value){return value instanceof byte[] bytes?new String(bytes,StandardCharsets.UTF_8):Objects.toString(value,"");}
  private static String value(Map<String,Object> row,String key){return Objects.toString(row.get(key),"");}
  private static long number(Map<String,Object> row,String key){return row.get(key) instanceof Number n?n.longValue():0;}
}
