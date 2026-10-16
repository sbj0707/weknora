package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.weknora.model.ModelClientFactory;
import com.tencent.weknora.model.client.*;
import com.tencent.weknora.retrieval.*;
import com.tencent.weknora.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Go's public entry point: existing summaries queue; first summaries execute synchronously. */
@Service
@ConditionalOnProperty(name="weknora.knowledge.processing.enabled",havingValue="true")
public final class KnowledgeSummaryRegenerationService {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final KnowledgeBaseWriteAuthorization writes;
  private final KnowledgeRepository documents;
  private final SummaryGenerationRepository repository;
  private final KnowledgeSummaryRefresh refresh;
  private final ModelClientFactory models;
  private final SummaryGenerationText text;
  private final DocumentProcessingHandler.Indexes indexes;
  private final KnowledgeBaseProfileHandler profiles;
  private final TransactionTemplate tx;
  private final boolean postgres;

  public KnowledgeSummaryRegenerationService(JdbcTemplate jdbc,ObjectMapper json,KnowledgeBaseWriteAuthorization writes,KnowledgeRepository documents,SummaryGenerationRepository repository,KnowledgeSummaryRefresh refresh,ModelClientFactory models,SummaryGenerationText text,DocumentProcessingHandler.Indexes indexes,KnowledgeBaseProfileHandler profiles,PlatformTransactionManager manager){
    this.jdbc=jdbc;this.json=json;this.writes=writes;this.documents=documents;this.repository=repository;this.refresh=refresh;this.models=models;this.text=text;this.indexes=indexes;this.profiles=profiles;this.tx=new TransactionTemplate(manager);
    postgres=Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>)c->c.getMetaData().getDatabaseProductName().equals("PostgreSQL")));
  }

  public KnowledgeBaseWriteAuthorization.WriteAccess authorize(AuthContext caller,String id,String raw){
    ChunkLifecycleService.capability(caller,"ingest");
    List<Map<String,Object>> rows;
    try{rows=jdbc.queryForList("SELECT tenant_id,knowledge_base_id FROM knowledges WHERE id=? AND deleted_at IS NULL",id);}
    catch(org.springframework.dao.DataAccessException failure){throw new ApiException(503,"SERVICE_UNAVAILABLE","cannot verify KB access right now").withAppCode(1008);}
    if(rows.isEmpty())throw api(404,1003,"Knowledge not found");
    var row=rows.getFirst();var grant=writes.authorizeSummaryRegeneration(caller,Objects.toString(row.get("knowledge_base_id"),""),raw);
    if(((Number)row.get("tenant_id")).longValue()!=grant.effectiveTenantId())throw api(403,1002,"Permission denied to access this knowledge");
    return grant;
  }

  public ObjectNode regenerate(KnowledgeBaseWriteAuthorization.WriteAccess grant,String id,String language){
    long owner=grant.effectiveTenantId();ObjectNode current;
    try{current=documents.get(owner,id).orElseThrow(()->new IllegalStateException("knowledge not found"));}
    catch(RuntimeException failure){throw api(500,1007,ChunkEditService.diagnostic(failure));}
    try{
      String status=current.path("summary_status").asText();
      if(!status.isEmpty()&&!status.equals("none")){
        refresh.enqueueRequired(owner,current.path("knowledge_base_id").asText(),id,status,language);
        var result=documents.get(owner,id).orElseThrow(()->new IllegalStateException("knowledge not found"));
        documents.assignTags(owner,current.path("knowledge_base_id").asText(),result,List.of());return result;
      }
      return synchronous(owner,id,language);
    }catch(ApiException typed){if(typed.appCode()!=null)throw typed;throw api(400,1000,ChunkEditService.modelDiagnostic(typed));}
    catch(Exception failure){throw api(400,1000,diagnostic(failure));}
  }

  private ObjectNode synchronous(long owner,String id,String language)throws Exception{
    var snapshot=repository.loadRefresh(owner,id);var kb=snapshot.knowledgeBase();var current=snapshot.knowledge();
    if(kb.path("summary_model_id").asText().isEmpty())throw new IllegalStateException("summary model is not configured");
    if(snapshot.text().isEmpty()){
      save(owner,current,"failed","",null,true);profile(owner,kb,language);throw new SummaryGenerationText.InsufficientContent();
    }
    save(owner,current,"processing",current.path("description").asText(),current.get("profile"),false);
    SummaryGenerationText.Result result;
    try{
      ChatModel chat;
      try{chat=models.chat(auth(owner),kb.path("summary_model_id").asText());}
      catch(RuntimeException failure){throw new IllegalStateException("get chat model: "+ChunkEditService.modelDiagnostic(failure),failure);}
      String input=text.input(snapshot.text(),current.path("custom_metadata"),repository.images(snapshot));
      try(var call=new CallContext()){
        JsonNode response;
        try{response=chat.chat(call,List.of(json.createObjectNode().put("role","system").put("content",text.prompt(language)),json.createObjectNode().put("role","user").put("content",input)),text.options());}
        catch(RuntimeException failure){throw new IllegalStateException((chat instanceof OpenAIChat?"create chat completion: ":"")+failure.getMessage(),failure);}
        result=text.parse(response);
      }
    }catch(Exception failure){
      if(failure instanceof SummaryGenerationText.InsufficientContent)save(owner,current,"failed","",null,true);
      else{
        if(changed(owner,id,snapshot))throw new IllegalStateException("summary refresh superseded");
        save(owner,current,"failed",SummaryGenerationText.fallback(snapshot.text()),null,true);
      }
      throw failure;
    }
    if(changed(owner,id,snapshot))throw new IllegalStateException("summary refresh superseded");
    save(owner,current,"completed",result.summary(),result.profile(),true);
    try{
      if(kb.path("indexing_strategy").path("vector_enabled").asBoolean()||kb.path("indexing_strategy").path("keyword_enabled").asBoolean()){
        var summaries=saveChunks(owner,snapshot,SummaryGenerationText.summaryChunk(result));
        replaceIndexes(owner,current,kb,summaries);
      }
    }finally{profile(owner,kb,language);}
    return current;
  }

  private void save(long owner,ObjectNode current,String status,String description,JsonNode profile,boolean updateTime){
    if(updateTime)current.put("updated_at",Instant.now().toString());
    jdbc.update("UPDATE knowledges SET description=?,profile="+(postgres?"?::jsonb":"?")+",summary_status=?,updated_at=? WHERE tenant_id=? AND id=? AND deleted_at IS NULL",description,profile==null||profile.isNull()?null:profile.toString(),status,postgres?Timestamp.from(Instant.parse(current.path("updated_at").asText())):current.path("updated_at").asText(),owner,current.path("id").asText());
    current.put("description",description).put("summary_status",status);
    if(profile==null||profile.isNull())current.remove("profile");else current.set("profile",profile);
  }

  private boolean changed(long owner,String id,SummaryGenerationRepository.Snapshot snapshot){
    var current=documents.get(owner,id);if(current.isEmpty()||Set.of("cancelled","deleting").contains(current.get().path("parse_status").asText()))return true;
    String metadata=jdbc.queryForObject("SELECT custom_metadata FROM knowledges WHERE tenant_id=? AND id=?",(rs,n)->Objects.toString(rs.getString(1),""),owner,id);
    if(!Objects.equals(metadata,snapshot.metadata()))return true;
    for(var chunk:snapshot.text()){
      var rows=jdbc.queryForList("SELECT content_revision,is_enabled FROM chunks WHERE tenant_id=? AND id=? AND deleted_at IS NULL",owner,chunk.path("id").asText());
      if(rows.isEmpty())throw new IllegalStateException("chunk not found");
      var c=rows.getFirst();Object enabled=c.get("is_enabled");boolean active=enabled instanceof Boolean b?b:enabled instanceof Number n&&n.intValue()!=0;
      if(((Number)c.get("content_revision")).longValue()!=chunk.path("content_revision").asLong()||active!=chunk.path("is_enabled").asBoolean())return true;
    }
    return false;
  }

  private List<String> saveChunks(long owner,SummaryGenerationRepository.Snapshot snapshot,String content){
    String id=snapshot.knowledge().path("id").asText(),kb=snapshot.knowledgeBase().path("id").asText();var ids=new ArrayList<String>();
    // Match Go checkpoints: persisted description and updated chunks survive later index failures.
    for(var chunk:snapshot.all())if(chunk.path("chunk_type").asText().equals("summary")){
      String chunkId=chunk.path("id").asText();jdbc.update("UPDATE chunks SET content=?,source_content=?,is_enabled=true,updated_at=? WHERE tenant_id=? AND id=? AND deleted_at IS NULL",content,content,now(),owner,chunkId);ids.add(chunkId);
    }
    if(ids.isEmpty()){
      String chunkId=UUID.randomUUID().toString();long position=Math.max(0,snapshot.all().stream().filter(c->c.path("chunk_type").asText().equals("text")).mapToLong(c->c.path("chunk_index").asLong()).max().orElse(0))+1;
      jdbc.update("INSERT INTO chunks(id,tenant_id,knowledge_base_id,knowledge_id,content,chunk_index,is_enabled,start_at,end_at,chunk_type,parent_chunk_id,status,created_at,updated_at"+(postgres?"":",seq_id")+") VALUES(?,?,?,?,?,?,true,0,0,'summary',?,0,?,?"+(postgres?"":",(SELECT COALESCE(MAX(seq_id),0)+1 FROM chunks)")+")",chunkId,owner,kb,id,content,position,snapshot.text().getFirst().path("id").asText(),now(),now());ids.add(chunkId);
    }
    return ids;
  }

  private void replaceIndexes(long owner,ObjectNode current,ObjectNode kb,List<String> chunks)throws Exception{
    var model=models.embeddingForCleanup(auth(owner),kb.path("embedding_model_id").asText());var writer=indexes.resolve(owner,kb);writer.prepare(Set.of(model.dimensions()));
    var entries=new ArrayList<IndexEntry>();String title=SummaryGenerationText.trim(current.path("title").asText());
    for(String id:chunks){var row=jdbc.queryForMap("SELECT * FROM chunks WHERE tenant_id=? AND id=? AND deleted_at IS NULL",owner,id);String content=SummaryGenerationText.trim(Objects.toString(row.get("content"),""));String header=Objects.toString(row.get("context_header"),"");if(!header.isEmpty())content=header+"\n\n"+content;
      entries.add(entry(current,kb,id,id,title,content));String raw=Objects.toString(row.get("metadata"),"");if(!raw.isEmpty())for(var q:json.readTree(raw).path("generated_questions"))if(!SummaryGenerationText.trim(q.path("question").asText()).isEmpty())entries.add(entry(current,kb,id,QuestionGenerationText.sourceId(id,q.path("id").asText()),title,q.path("question").asText()));
    }
    transact(c->writer.deleteByChunk(c,chunks));
    float[][] values=null;
    try(var call=new CallContext()){
      for(int attempt=0;attempt<5;attempt++)try{values=model.embed(call,entries.stream().map(e->IndexText.embeddingInput(e.content())).toList(),false);break;}
      catch(RuntimeException failure){if(attempt==4)throw failure;call.pause(Duration.ofMillis(200L<<attempt));}
    }
    if(values==null||values.length!=entries.size())throw new IllegalStateException("embedding count mismatch");
    var vectors=new LinkedHashMap<String,float[]>();for(int i=0;i<values.length;i++){FloatVectors.validate(values[i],model.dimensions());vectors.put(entries.get(i).sourceId(),values[i]);}
    transact(c->writer.batchSave(c,entries,vectors));
  }
  private static IndexEntry entry(ObjectNode doc,ObjectNode kb,String chunk,String source,String title,String content){return new IndexEntry(source,0,chunk,doc.path("id").asText(),kb.path("id").asText(),"",title.isEmpty()?content:title+"\n"+content,true);}
  @FunctionalInterface private interface Mutation{void apply(java.sql.Connection c)throws Exception;}
  private void transact(Mutation action){tx.executeWithoutResult(s->jdbc.execute((ConnectionCallback<Void>)c->{try{action.apply(c);return null;}catch(java.sql.SQLException error){throw error;}catch(RuntimeException error){throw error;}catch(Exception error){throw new java.sql.SQLException(error);}}));}
  private void profile(long owner,ObjectNode kb,String language){if(profiles!=null)profiles.enqueue(owner,kb,false,json.createObjectNode().put("language",language));}
  private Object now(){return postgres?Timestamp.from(Instant.now()):GoSqliteTimestamp.write(Instant.now());}
  private static AuthContext auth(long owner){return new AuthContext("system-summary",owner,"viewer",false,null,false,Set.of(),Set.of());}
  private static String diagnostic(Exception failure){return failure instanceof org.springframework.dao.DataAccessException?ChunkEditService.diagnostic(failure):Objects.toString(failure.getMessage(),"Internal server error");}
  private static ApiException api(int status,int code,String message){return new ApiException(status,status==400?"BAD_REQUEST":status==403?"FORBIDDEN":status==404?"NOT_FOUND":"INTERNAL_SERVER_ERROR",message).withAppCode(code);}
}
