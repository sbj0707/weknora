package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.task.TaskRepository;
import java.time.Duration;
import java.util.*;

/** Best-effort producer for an already admitted mutation; never grants KB access. */
public final class IndexDeleteEnqueuer {
  private final TaskRepository tasks;
  private final ObjectMapper json;
  public IndexDeleteEnqueuer(TaskRepository tasks,ObjectMapper json){this.tasks=tasks;this.json=json;}
  public boolean enqueue(long owner,JsonNode base,List<String> deletedIds,JsonNode effectiveEngines,JsonNode trace){
    if(owner<=0||base==null||base.path("tenant_id").asLong()!=owner||base.path("id").asText().isEmpty()||deletedIds==null||deletedIds.isEmpty())return false;
    var payload=json.createObjectNode().put("tenant_id",owner).put("knowledge_base_id",base.path("id").asText()).put("embedding_model_id",base.path("embedding_model_id").asText()).put("kb_type",base.path("type").asText());
    payload.set("chunk_ids",json.valueToTree(deletedIds));
    payload.set("effective_engines",effectiveEngines==null?json.nullNode():effectiveEngines.deepCopy());
    if(base.hasNonNull("vector_store_id"))payload.set("vector_store_id",base.get("vector_store_id").deepCopy());
    if(trace!=null)for(String key:List.of("lf_trace_id","lf_parent_obs_id","lf_traceparent","lf_user_id","lf_session_id"))if(trace.path(key).isTextual()&&!trace.path(key).asText().isEmpty())payload.set(key,trace.get(key).deepCopy());
    try{tasks.enqueue(owner,"index:delete",null,payload,11,Duration.ZERO,"low",Duration.ofHours(1));return true;}
    catch(RuntimeException e){return false;}
  }
}
