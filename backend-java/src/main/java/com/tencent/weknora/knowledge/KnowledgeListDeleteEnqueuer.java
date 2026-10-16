package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.security.AuthContext;
import com.tencent.weknora.task.*;
import java.time.Duration;
import java.util.*;

/** The producer only accepts IDs resolved from an authorized persisted relationship. */
public final class KnowledgeListDeleteEnqueuer {
  private final TaskRepository tasks;private final ObjectMapper json;
  private org.springframework.jdbc.core.JdbcTemplate jdbc;
  public KnowledgeListDeleteEnqueuer(TaskRepository tasks,ObjectMapper json){this.tasks=tasks;this.json=json;}
  public KnowledgeListDeleteEnqueuer withAuditKeys(org.springframework.jdbc.core.JdbcTemplate jdbc){this.jdbc=jdbc;return this;}
  public String enqueue(AuthContext caller,long owner,JsonNode base,List<String> ids,JsonNode trace){
    if(owner<=0||base.path("tenant_id").asLong()!=owner||base.path("id").asText().isEmpty()||ids.isEmpty())throw new IllegalArgumentException("Document deletion requires an admitted owner/KB binding");
    var payload=json.createObjectNode().put("tenant_id",owner).put("knowledge_base_id",base.path("id").asText());payload.set("knowledge_ids",json.valueToTree(ids));var initiator=json.createObjectNode();String user=caller.userId();if(user!=null&&!user.isEmpty()&&!user.matches("system-[0-9]+"))initiator.put("user_id",user).put("role",caller.role());if(caller.apiKeyId()!=null&&caller.apiKeyId()>0)initiator.put("api_key_id",caller.apiKeyId());payload.set("initiator",initiator);
    if(jdbc!=null&&caller.apiKeyId()!=null){var keys=jdbc.queryForList("SELECT name FROM tenant_api_keys WHERE id=? AND tenant_id=?",caller.apiKeyId(),caller.tenantId());if(!keys.isEmpty()&&keys.getFirst().get("name")!=null&&!keys.getFirst().get("name").toString().isEmpty())initiator.put("api_key_name",keys.getFirst().get("name").toString());}
    if(trace!=null)for(String key:List.of("lf_trace_id","lf_parent_obs_id","lf_traceparent","lf_user_id","lf_session_id"))if(trace.path(key).isTextual()&&!trace.path(key).asText().isEmpty())payload.set(key,trace.get(key));
    return tasks.enqueue(owner,"knowledge:list_delete",null,payload,4,Duration.ZERO,"low",Duration.ofHours(2)).id();
  }
}
