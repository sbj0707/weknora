package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.task.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;

/** index:delete batches retain Go's 100-ID boundary and per-batch failure checkpoint. */
public final class IndexDeleteHandler implements TaskHandler {
  @FunctionalInterface public interface Models {int dimensions(long owner,String model) throws Exception;}
  @FunctionalInterface public interface Stores {List<IndexDeleteRepository.Deletion> resolve(long owner,JsonNode payload) throws Exception;}
  private final IndexDeleteRepository repository;
  private final ObjectMapper json;
  private final Models models;
  private final Stores stores;
  public IndexDeleteHandler(IndexDeleteRepository repository,ObjectMapper json,Models models,Stores stores){this.repository=repository;this.json=json;this.models=models;this.stores=stores;}
  public String type(){return "index:delete";}
  public Duration timeout(){return Duration.ofHours(1);}
  public JsonNode execute(TaskRecord job,TaskCancellation cancellation)throws Exception{
    JsonNode payload=job.payload();validate(payload);
    cancellation.check();
    String model=payload.path("embedding_model_id").asText();
    if(model.isEmpty())throw new TaskFailure("model ID cannot be empty",true);
    if(payload.path("tenant_id").asLong()!=job.tenantId()||job.tenantId()<=0)throw new TaskFailure("Index cleanup owner does not match task owner",false);
    try{
      var selected=stores.resolve(job.tenantId(),payload);
      int dimension=models.dimensions(job.tenantId(),model);
      var ids=new ArrayList<String>();for(var id:payload.path("chunk_ids"))ids.add(id.isNull()?"":id.asText());
      int deleted=0;
      for(int i=0;i<ids.size();i+=100){
        cancellation.check();
        deleted+=repository.deleteBatch(job,payload.path("knowledge_base_id").asText(),ids.subList(i,Math.min(ids.size(),i+100)),dimension,payload.path("kb_type").asText(),selected);
      }
      return json.createObjectNode().put("chunk_ids",deleted);
    }catch(CancellationException|TaskFailure e){throw e;}
    catch(Exception e){var failure=new TaskFailure("Index cleanup failed",true);failure.initCause(e);throw failure;}
  }
  private static void validate(JsonNode value)throws TaskFailure{
    if(!value.isNull()&&!value.isObject())throw invalid();
    for(String field:List.of("knowledge_base_id","embedding_model_id","kb_type","vector_store_id"))if(value.hasNonNull(field)&&!value.path(field).isTextual())throw invalid();
    if(value.hasNonNull("tenant_id")&&(!value.path("tenant_id").isIntegralNumber()||!value.path("tenant_id").canConvertToLong()||value.path("tenant_id").asLong()<0))throw invalid();
    if(value.hasNonNull("chunk_ids")){if(!value.path("chunk_ids").isArray())throw invalid();for(var id:value.path("chunk_ids"))if(!id.isNull()&&!id.isTextual())throw invalid();}
    if(value.hasNonNull("effective_engines")){
      if(!value.path("effective_engines").isArray())throw invalid();
      for(var engine:value.path("effective_engines")){
        if(!engine.isNull()&&!engine.isObject())throw invalid();
        for(String field:List.of("retriever_engine_type","retriever_type"))if(engine.hasNonNull(field)&&!engine.path(field).isTextual())throw invalid();
      }
    }
  }
  private static TaskFailure invalid(){return new TaskFailure("Invalid index delete payload",true);}
}
