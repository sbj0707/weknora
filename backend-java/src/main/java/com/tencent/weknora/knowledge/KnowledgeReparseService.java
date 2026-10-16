package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.security.*;
import com.tencent.weknora.task.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.*;

/** Reparse deliberately retains Go's durable cleanup/reset/enqueue checkpoints. */
public final class KnowledgeReparseService {
  @FunctionalInterface public interface Cleanup {void run(long owner,ObjectNode base,ObjectNode document,Runnable guard)throws Exception;}
  public record WorkerContext(String taskId,JsonNode initiator,Runnable guard) {
    public WorkerContext { initiator=initiator.deepCopy(); }
  }
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final KnowledgeRepository documents;
  private final KnowledgeProcessingSpanTracker tracker;
  private final TaskRepository queue;
  private final KnowledgeFileUpload.Enqueuer producer;
  private final KnowledgeProcessConfig config;
  private final Cleanup cleanup;
  private final Duration documentTimeout;
  private final Clock clock;
  private final boolean postgres;

  public KnowledgeReparseService(JdbcTemplate jdbc,ObjectMapper json,KnowledgeRepository documents,
      KnowledgeProcessingSpanTracker tracker,TaskRepository queue,KnowledgeFileUpload.Enqueuer producer,
      Cleanup cleanup,Duration documentTimeout,Clock clock){
    this.jdbc=jdbc;this.json=json;this.documents=documents;this.tracker=tracker;this.queue=queue;this.producer=producer;this.cleanup=cleanup;this.documentTimeout=documentTimeout;this.clock=clock;config=new KnowledgeProcessConfig(json);
    postgres=Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>)c->c.getMetaData().getDatabaseProductName().equals("PostgreSQL")));
  }

  public ObjectNode reparse(AuthContext caller,long owner,String kbId,String id,JsonNode overrides,String language,String requestId){
    return reparse(caller,owner,kbId,id,overrides,language,requestId,null);
  }
  public ObjectNode reparseFromTask(long owner,String kbId,String id,JsonNode overrides,String language,WorkerContext worker){
    return reparse(null,owner,kbId,id,overrides,language,"",worker);
  }
  private ObjectNode reparse(AuthContext caller,long owner,String kbId,String id,JsonNode overrides,String language,String requestId,WorkerContext worker){
    Runnable guard=worker==null?()->{}:worker.guard();guard.run();
    ObjectNode row=documents.get(owner,id).orElseThrow(()->new ApiException(404,"NOT_FOUND","knowledge not found").withAppCode(1003));
    if(!row.path("knowledge_base_id").asText().equals(kbId))throw new ApiException(403,"FORBIDDEN","Permission denied to access this knowledge").withAppCode(1002);
    moving(row);ObjectNode kb=documents.knowledgeBase(owner,kbId);
    if(overrides!=null&&!overrides.isNull()){
      config.validateProcessOverrides(kb,overrides,fileTypes(row));
      var metadata=row.path("metadata");
      if(!metadata.isObject()&&!metadata.isNull()&&!metadata.isMissingNode())throw internal("invalid knowledge metadata");
      ObjectNode updated=metadata.isObject()?(ObjectNode)metadata.deepCopy():json.createObjectNode();updated.set("process_overrides",config.normalizeOverrides(overrides));
      guard.run();jdbc.update("UPDATE knowledges SET metadata="+(postgres?"?::jsonb":"?")+" WHERE tenant_id=? AND id=? AND deleted_at IS NULL",updated.toString(),owner,id);row.set("metadata",updated);
    }
    JsonNode stored=row.path("metadata").get("process_overrides");
    // Go ignores ProcessOverrides decode errors when reusing persisted metadata.
    if(stored!=null&&!stored.isNull())try{stored=config.normalizeOverrides(stored);}catch(RuntimeException invalid){stored=null;}
    ObjectNode effective=config.resolve(kb,stored);
    boolean manual=row.path("type").asText().equals("manual");String content="";
    if(manual){
      var metadata=row.path("metadata");boolean valid=metadata.isObject();
      // A JSON literal null unmarshals into an empty Go struct. SQL NULL/empty
      // metadata instead returns no ManualMetadata and must be rejected.
      if(metadata.isNull()){
        var raw=jdbc.queryForList("SELECT metadata FROM knowledges WHERE tenant_id=? AND id=? AND deleted_at IS NULL",owner,id);
        Object value=raw.isEmpty()?null:raw.getFirst().get("metadata");
        String text=value instanceof byte[] bytes?com.tencent.weknora.docreader.GoUtf8.decode(bytes):Objects.toString(value,"");
        valid=KnowledgeUploadForms.goTrim(text).equals("null");
      }
      for(String key:List.of("content","format","status","updated_at"))if(metadata.hasNonNull(key)&&!metadata.path(key).isTextual())valid=false;
      if(metadata.hasNonNull("version")&&(!metadata.path("version").isIntegralNumber()||!metadata.path("version").canConvertToLong()))valid=false;
      if(!valid)throw KnowledgeReparseRequest.bad("无法获取手工知识内容");content=metadata.path("content").asText("");
    }else try{cleanup.run(owner,kb,row,guard);}
      catch(java.util.concurrent.CancellationException canceled){throw canceled;}
      catch(Exception failure){if(worker!=null)throw new IllegalStateException(diagnostic(failure));throw internal(diagnostic(failure));}

    guard.run();
    if(Set.of("pending","processing","finalizing").contains(row.path("parse_status").asText()))bestEffort(()->queue.cancelKnowledge(owner,id));
    long attempt=0;try{var opened=tracker.open(owner,id,"");if(opened.root()!=null)attempt=opened.number();}catch(RuntimeException ignored){}
    guard.run();bestEffort(()->jdbc.update("DELETE FROM task_pending_ops WHERE task_type='wiki:ingest' AND scope='knowledge_base' AND scope_id=? AND dedup_key=? AND op='ingest'",kbId,id));

    row.put("parse_status","pending").put("enable_status","disabled").put("description","").put("error_message","").put("embedding_model_id",kb.path("embedding_model_id").asText()).put("pending_subtasks_count",0).putNull("processed_at");
    guard.run();saveReset(owner,row);
    guard.run();jdbc.update("UPDATE knowledges SET pending_subtasks_count=0 WHERE tenant_id=? AND id=? AND deleted_at IS NULL",owner,id);
    ObjectNode payload=json.createObjectNode().put("request_id",requestId).put("tenant_id",owner).put("knowledge_id",id).put("knowledge_base_id",kbId);
    String type="document:process";Duration timeout=documentTimeout;
    if(manual){type="manual:process";timeout=Duration.ofMinutes(30);payload.put("request_id",requestId).put("content",content).put("need_cleanup",true);}
    else{
      String path=row.path("file_path").asText(),source=row.path("source").asText(),sourceType=row.path("type").asText();
      if(!path.isEmpty())payload.put("file_path",path).put("file_name",row.path("file_name").asText()).put("file_type",KnowledgeProcessConfig.fileType(row.path("file_name").asText()));
      else if(sourceType.equals("file_url")&&!source.isEmpty())payload.put("file_url",source).put("file_name",row.path("file_name").asText()).put("file_type",row.path("file_type").asText());
      else if(sourceType.equals("url")&&!source.isEmpty())payload.put("url",source);
      else{row.put("parse_status","failed").put("error_message","Knowledge has no parseable content");try{saveReset(owner,row);}catch(RuntimeException ignored){}throw KnowledgeReparseRequest.bad("Knowledge has no parseable content");}
      long count=effective.path("question_generation_config").path("question_count").asLong();
      payload.put("enable_multimodel",effective.path("enable_multimodel").asBoolean()).put("enable_question_generation",effective.path("question_generation_config").path("enabled").asBoolean()).put("question_count",count<=0?3:count).put("language",language).put("attempt",attempt);
    }
    guard.run();try{producer.enqueue(owner,type,payload,new KnowledgeFileUpload.TaskOptions("default",3,timeout));}
    catch(Exception failure){row.put("parse_status","failed").put("error_message","Failed to enqueue processing task");try{saveReset(owner,row);}catch(RuntimeException ignored){}throw internal("Failed to submit processing task");}
    audit(caller,row,attempt,worker);
    if(!manual&&!payload.has("url"))tableSummary(owner,kb,row);
    return row;
  }

  public List<Map<String,Object>> validateBatch(long owner,String kb,List<String> ids){
    var args=new ArrayList<Object>();args.add(owner);args.addAll(ids);List<Map<String,Object>> rows;
    try{rows=jdbc.queryForList("SELECT * FROM knowledges WHERE tenant_id=? AND id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+") AND deleted_at IS NULL",args.toArray());}
    catch(RuntimeException error){throw internal("failed to get knowledge batch");}
    if(rows.size()!=ids.size())throw KnowledgeReparseRequest.bad("some knowledge entries were not found");
    for(var row:rows){moving(row.get("metadata"));if(!Objects.toString(row.get("knowledge_base_id"),"").equals(kb))throw KnowledgeReparseRequest.bad("Knowledge "+KnowledgeImportNames.sanitizeForLog(row.get("id").toString())+" does not belong to knowledge base "+KnowledgeImportNames.sanitizeForLog(kb));}
    return rows;
  }

  public String enqueueBatch(AuthContext caller,KnowledgeBaseWriteAuthorization.WriteAccess grant,KnowledgeReparseRequest request){
    var payload=json.createObjectNode().put("tenant_id",grant.effectiveTenantId()).put("knowledge_base_id",grant.knowledgeBaseId()).set("knowledge_ids",json.valueToTree(request.ids()));
    ObjectNode p=(ObjectNode)payload;
    if(request.processConfig()!=null&&!request.processConfig().isNull())p.set("process_config",config.normalizeOverrides(request.processConfig()));
    var initiator=p.putObject("initiator");String user=caller.userId();if(!user.isEmpty()&&!user.matches("system-[0-9]+"))initiator.put("user_id",user).put("role",caller.role());
    if(caller.apiKeyId()!=null&&caller.apiKeyId()>0){initiator.put("api_key_id",caller.apiKeyId());var names=jdbc.queryForList("SELECT name FROM tenant_api_keys WHERE id=?",caller.apiKeyId());if(!names.isEmpty()&&names.getFirst().get("name")!=null&&!names.getFirst().get("name").toString().isEmpty())initiator.put("api_key_name",names.getFirst().get("name").toString());}
    try{return producer.enqueue(grant.effectiveTenantId(),"knowledge:list_reparse",p,new KnowledgeFileUpload.TaskOptions("low",3,Duration.ofHours(1)));}
    catch(Exception error){throw internal("Failed to enqueue batch reparse task");}
  }

  private void saveReset(long owner,ObjectNode row){
    row.put("updated_at",clock.instant().toString());
    jdbc.update("UPDATE knowledges SET parse_status=?,enable_status=?,description=?,processed_at=NULL,error_message=?,embedding_model_id=?,storage_size=?,file_path=?,updated_at=? WHERE tenant_id=? AND id=? AND deleted_at IS NULL",row.path("parse_status").asText(),row.path("enable_status").asText(),row.path("description").asText(),row.path("error_message").asText(),row.path("embedding_model_id").asText(),row.path("storage_size").asLong(),row.path("file_path").asText(),time(),owner,row.path("id").asText());
  }
  private void tableSummary(long owner,ObjectNode kb,ObjectNode row){
    String type=KnowledgeUploadForms.goTrim(row.path("file_type").asText()).toLowerCase(Locale.ROOT);
    if(type.isEmpty())type=KnowledgeProcessConfig.fileType(row.path("file_name").asText());
    if(!KnowledgeProcessConfig.dataTable(type))return;
    var payload=json.createObjectNode().put("tenant_id",owner).put("knowledge_id",row.path("id").asText()).put("summary_model",kb.path("summary_model_id").asText()).put("embedding_model",kb.path("embedding_model_id").asText());
    try{producer.enqueue(owner,"datatable:summary",payload,new KnowledgeFileUpload.TaskOptions("summary",3,Duration.ofMinutes(30)));}catch(Exception ignored){}
  }
  private List<String> fileTypes(ObjectNode row){if(row.path("type").asText().equals("manual"))return List.of();if(row.path("type").asText().equals("url"))return List.of("html");String type=row.path("file_type").asText();if(type.isEmpty()&&!row.path("file_name").asText().isEmpty())type=KnowledgeProcessConfig.fileType(row.path("file_name").asText());return type.isEmpty()?List.of():List.of(type);}
  void moving(ObjectNode row){moving(row.path("metadata").isNull()?null:row.path("metadata").toString());}
  private void moving(Object raw){try{if(new KnowledgeTransferState(json).moving(raw))throw new ApiException(409,"CONFLICT","knowledge has an unfinished move; retry the move first").withAppCode(1005);}catch(IllegalArgumentException failure){throw internal(failure.getMessage());}}
  private void audit(AuthContext caller,ObjectNode row,long attempt,WorkerContext worker){bestEffort(()->{
    var details=json.createObjectNode().put("title",row.path("title").asText()).put("type",row.path("type").asText()).put("attempt",attempt);
    String actor,role;
    if(worker!=null){
      var initiator=worker.initiator();actor=initiator.path("user_id").asText();String supplied=initiator.path("role").asText();
      role=actor.isEmpty()?"":Set.of("owner","admin","contributor","viewer").contains(supplied)?supplied:"viewer";
      JsonNode key=initiator.get("api_key_id");if(key!=null&&key.isNumber()&&key.bigIntegerValue().signum()>0)details.set("api_key_id",key);
      if(!initiator.path("api_key_name").asText().isEmpty())details.put("api_key_name",initiator.path("api_key_name").asText());
      details.put("trigger",actor.isEmpty()||actor.matches("system-[0-9]+")?"system":"user").put("processing_status","pending");
      if(!worker.taskId().isEmpty())details.put("task_id",worker.taskId());
    }else{
      actor=caller.userId();role=actor.isEmpty()||actor.matches("system-[0-9]+")?"":caller.role();if(role.isEmpty())actor="";
      if(caller.apiKeyId()!=null&&caller.apiKeyId()>0){details.put("api_key_id",caller.apiKeyId());var names=jdbc.queryForList("SELECT name FROM tenant_api_keys WHERE id=?",caller.apiKeyId());if(!names.isEmpty()&&names.getFirst().get("name")!=null&&!names.getFirst().get("name").toString().isEmpty())details.put("api_key_name",names.getFirst().get("name").toString());}
    }
    return jdbc.update("INSERT INTO audit_logs(tenant_id,actor_user_id,actor_role,action,target_type,target_id,scope_type,scope_id,outcome,details) VALUES(?,?,?,'knowledge.reparse_started','knowledge',?,'knowledge_base',?,'accepted',"+(postgres?"?::jsonb":"?")+")",row.path("tenant_id").asLong(),actor,role,row.path("id").asText(),row.path("knowledge_base_id").asText(),details.toString());
  });}
  private Object time(){return postgres?Timestamp.from(clock.instant()):GoSqliteTimestamp.write(clock.instant());}
  private static void bestEffort(java.util.function.IntSupplier effect){try{effect.getAsInt();}catch(RuntimeException ignored){}}
  private static ApiException internal(String message){return new ApiException(500,"INTERNAL_SERVER_ERROR",message).withAppCode(1007);}
  private static String diagnostic(Exception error){return error instanceof org.springframework.dao.DataAccessException?ChunkEditService.diagnostic(error):Objects.toString(error.getMessage(),"Internal server error");}
}
