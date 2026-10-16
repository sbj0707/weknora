package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.task.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Go's partial cleanup checkpoint: all cleanup branches finish before file/accounting finalization. */
public final class KnowledgeListDeleteHandler implements TaskHandler {
  @FunctionalInterface public interface Models {int dimensions(long owner,String id)throws Exception;}
  public interface Effects {
    void prepare(long owner,KnowledgeListDeleteRepository.Plan plan)throws Exception;
    List<KnowledgeListDeleteRepository.IndexDeletion> indexes(long owner,KnowledgeListDeleteRepository.Plan plan)throws Exception;
    void graph(TaskRecord job,KnowledgeListDeleteRepository.Plan plan,Runnable guard)throws Exception;
    void file(long owner,KnowledgeListDeleteRepository.Plan plan,String reference)throws Exception;
    default boolean release(long owner,String reference,List<String> ids)throws Exception{return true;}
    default void dequeue(long owner,String id){}
    default void profile(long owner,JsonNode base,JsonNode source){}
  }
  private final KnowledgeListDeleteRepository repository;private final ObjectMapper json;private final Models models;private final Effects effects;
  public KnowledgeListDeleteHandler(KnowledgeListDeleteRepository repository,ObjectMapper json,Models models,Effects effects){this.repository=repository;this.json=json;this.models=models;this.effects=effects;}
  public String type(){return "knowledge:list_delete";}public Duration timeout(){return Duration.ofHours(2);}
  public JsonNode execute(TaskRecord job,TaskCancellation cancellation)throws Exception{
    var payload=job.payload();validate(payload);long owner=payload.path("tenant_id").asLong();if(owner==0)throw new TaskFailure("invalid delete task tenant",false);if(owner!=job.tenantId())throw new TaskFailure("Document cleanup owner does not match task owner",false);
    var ids=new LinkedHashSet<String>();for(var id:payload.path("knowledge_ids")){String value=id.isNull()?"":id.asText();if(com.tencent.weknora.security.AuthPreferences.trimGoSpace(value).isEmpty())throw new TaskFailure("invalid delete task IDs",false);ids.add(value);}cancellation.check();
    var plan=repository.plan(job,List.copyOf(ids),payload.path("knowledge_base_id").asText());if(plan.ids().isEmpty())return json.createObjectNode().put("deleted",0);
    effects.prepare(owner,plan);cancellation.check();try{
      var inFlight=plan.documents().stream().filter(row->Set.of("pending","processing").contains(KnowledgeListDeleteRepository.text(row,"parse_status"))).map(row->KnowledgeListDeleteRepository.text(row,"id")).toList();repository.mark(job,plan);for(String id:inFlight)try{effects.dequeue(owner,id);}catch(RuntimeException ignored){}
      Exception failure=null;
      try{
        record Key(String model,String type){}var groups=new LinkedHashMap<Key,List<String>>();for(var row:plan.documents()){var key=new Key(KnowledgeListDeleteRepository.text(row,"embedding_model_id"),KnowledgeListDeleteRepository.text(row,"type"));groups.computeIfAbsent(key,k->new ArrayList<>()).add(KnowledgeListDeleteRepository.text(row,"id"));}
        for(var entry:groups.entrySet()){
          if(com.tencent.weknora.security.AuthPreferences.trimGoSpace(entry.getKey().model()).isEmpty())continue;
          var stores=effects.indexes(owner,plan);int dimension=models.dimensions(owner,entry.getKey().model());for(var store:stores){cancellation.check();repository.indexes(job,plan,entry.getValue(),dimension,entry.getKey().type(),store);}
        }
      }catch(CancellationException e){throw e;}catch(Exception e){failure=e;}
      try{cancellation.check();repository.chunks(job,plan);}catch(CancellationException e){throw e;}catch(Exception e){if(failure==null)failure=e;}
      try{cancellation.check();repository.check(job,plan);effects.graph(job,plan,()->{cancellation.check();repository.check(job,plan);});}catch(CancellationException e){throw e;}catch(Exception e){if(failure==null)failure=e;}
      if(failure!=null){if(failure instanceof TaskFailure e)throw e;throw new TaskFailure("Document cleanup failed",true);}
      cancellation.check();repository.deleted(job,plan);
      for(var row:plan.documents()){String path=KnowledgeListDeleteRepository.text(row,"file_path");if(!path.isEmpty()){cancellation.check();repository.afterDeletion(job,plan);try{effects.file(owner,plan,path);}catch(CancellationException e){throw e;}catch(Exception ignored){}}}
      for(String image:plan.images()){cancellation.check();repository.afterDeletion(job,plan);try{if(effects.release(owner,image,plan.ids()))effects.file(owner,plan,image);}catch(CancellationException e){throw e;}catch(Exception ignored){}}
      cancellation.check();repository.accounting(job,plan);try{effects.profile(owner,plan.base(),payload);}catch(RuntimeException ignored){}repository.audit(job,plan);
      return json.createObjectNode().put("deleted",plan.ids().size());
    }catch(KnowledgeListDeleteRepository.Failure e){throw new TaskFailure(e.getMessage(),e.retryable);}
  }
  private static void validate(JsonNode payload)throws TaskFailure{
    if(!payload.isNull()&&!payload.isObject())throw invalid();for(String key:List.of("knowledge_base_id","lf_trace_id","lf_parent_obs_id","lf_traceparent","lf_user_id","lf_session_id"))if(payload.hasNonNull(key)&&!payload.path(key).isTextual())throw invalid();
    if(payload.hasNonNull("tenant_id")&&(!payload.path("tenant_id").isIntegralNumber()||!payload.path("tenant_id").canConvertToLong()||payload.path("tenant_id").asLong()<0))throw invalid();
    if(payload.hasNonNull("knowledge_ids")){if(!payload.path("knowledge_ids").isArray())throw invalid();for(var id:payload.path("knowledge_ids"))if(!id.isNull()&&!id.isTextual())throw invalid();}
    var initiator=payload.path("initiator");if(!initiator.isMissingNode()&&!initiator.isNull()){if(!initiator.isObject())throw invalid();for(String key:List.of("user_id","role","api_key_name"))if(initiator.hasNonNull(key)&&!initiator.path(key).isTextual())throw invalid();if(initiator.hasNonNull("api_key_id")&&(!initiator.path("api_key_id").isIntegralNumber()||!initiator.path("api_key_id").canConvertToLong()||initiator.path("api_key_id").asLong()<0))throw invalid();}
  }
  private static TaskFailure invalid(){return new TaskFailure("Invalid knowledge list delete payload",true);}
}
