package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.model.client.CallContext;
import com.tencent.weknora.security.*;
import com.tencent.weknora.task.TaskRepository;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.*;

/** Public deletion commits the KB fence first; subsequent cleanup/enqueue failures never undo it. */
public final class KnowledgeBaseDeleteAdmission {
  @FunctionalInterface public interface SourceSchedules { void remove(String source); }
  @FunctionalInterface interface Enqueue { void submit(long owner,ObjectNode payload); }
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final KnowledgeBaseCodec codec;
  private final KnowledgeBaseTaskScrubber scrubber;
  private final SourceSchedules schedules;
  private final Environment env;
  private final Clock clock;
  private final Enqueue enqueue;
  private final boolean sqlite;

  public KnowledgeBaseDeleteAdmission(JdbcTemplate jdbc,ObjectMapper json,KnowledgeBaseCodec codec,
      KnowledgeBaseTaskScrubber scrubber,TaskRepository tasks,SourceSchedules schedules,Environment env,Clock clock) {
    this(jdbc,json,codec,scrubber,(owner,payload)->tasks.enqueue(owner,"kb:delete",null,payload,4,Duration.ZERO,"low",Duration.ofHours(2)),schedules,env,clock);
  }
  KnowledgeBaseDeleteAdmission(JdbcTemplate jdbc,ObjectMapper json,KnowledgeBaseCodec codec,
      KnowledgeBaseTaskScrubber scrubber,Enqueue enqueue,SourceSchedules schedules,Environment env,Clock clock) {
    this.jdbc=jdbc;this.json=json;this.codec=codec;this.scrubber=scrubber;this.enqueue=enqueue;this.schedules=schedules;this.env=env;this.clock=clock;
    sqlite=Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>)c->c.getMetaData().getDatabaseProductName().equals("SQLite")));
  }
  public void delete(AuthContext caller,String id,KnowledgeBaseWriteAuthorization.WriteAccess access) {
    if(!access.ownTenant()||!access.permission().equals("admin"))throw new ApiException(403,"FORBIDDEN","Only knowledge base owner can delete").withAppCode(1002);
    if(id.isEmpty())throw internal("knowledge base ID cannot be empty");
    ObjectNode kb;
    try {
      var rows=jdbc.queryForList("SELECT * FROM knowledge_bases WHERE id=? AND deleted_at IS NULL",id);
      if(rows.isEmpty())throw internal("record not found");
      kb=codec.fromDatabaseRow(rows.getFirst(),sqlite);
    }catch(DataAccessException error){throw databaseFailure(error);}
    // The tenant snapshot must be captured before the soft delete, just as authenticated Go context.
    JsonNode engines=effectiveEngines(caller.tenantId());
    try {jdbc.update("UPDATE knowledge_bases SET deleted_at=? WHERE id=? AND deleted_at IS NULL",now(),id);}
    catch(DataAccessException error){throw databaseFailure(error);}
    audit(caller,id,kb.path("name").asText());
    detached(()->{try(var call=CallContext.timeout(Duration.ofSeconds(5))){scrubber.scrub(id,List.of(),List.of(),call);}});
    bestEffort(()->jdbc.update("UPDATE kb_shares SET deleted_at=? WHERE knowledge_base_id=? AND deleted_at IS NULL",now(),id),"shares");
    List<String> sources=deleteSources(id);
    if(!sources.isEmpty())detached(()->{try(var call=CallContext.timeout(Duration.ofSeconds(5))){scrubber.cancel(id,List.of(),sources,call);}});
    // No outer transaction: a broker failure leaves the public deletion successful, as in Go.
    bestEffort(()->{
      var payload=json.createObjectNode().put("tenant_id",caller.tenantId()).put("knowledge_base_id",id);
      if(!sources.isEmpty())payload.set("data_source_ids",json.valueToTree(sources));
      payload.set("effective_engines",engines);
      if(kb.hasNonNull("vector_store_id"))payload.set("vector_store_id",kb.get("vector_store_id"));
      enqueue.submit(caller.tenantId(),payload);
    },"enqueue");
  }
  private List<String> deleteSources(String id) {
    var ids=new ArrayList<String>();
    List<Map<String,Object>> rows;
    try {rows=jdbc.queryForList("SELECT * FROM data_sources WHERE knowledge_base_id=? AND deleted_at IS NULL ORDER BY created_at DESC",id);}
    catch(RuntimeException error){warn("source lookup",error);return ids;}
    for(var row:rows){
      String source=Objects.toString(row.get("id"),"");if(source.isEmpty())continue;ids.add(source);
      try {jdbc.update("UPDATE data_sources SET deleted_at=? WHERE id=? AND deleted_at IS NULL",now(),source);}
      catch(RuntimeException error){warn("source delete",error);continue;}
      if(schedules!=null)bestEffort(()->schedules.remove(source),"source schedule");
      bestEffort(()->{Object now=now();jdbc.update("UPDATE sync_logs SET status='canceled',finished_at=?,error_message='data source deleted',updated_at=? WHERE data_source_id=? AND status IN ('pending','running')",now,now,source);},"sync log");
    }
    return ids;
  }
  private JsonNode effectiveEngines(long owner) {
    var rows=jdbc.queryForList("SELECT retriever_engines FROM tenants WHERE id=?",owner);
    if(!rows.isEmpty()){
      Object raw=rows.getFirst().get("retriever_engines");
      if(raw!=null&&(!sqlite||raw instanceof byte[])){
        try {
          var configured=KnowledgeBaseDeletePayload.tenantEngines(raw instanceof byte[] b?new String(b,java.nio.charset.StandardCharsets.UTF_8):raw.toString(),json);
          if(!configured.isEmpty())return configured;
        }catch(Exception error){throw internal("cannot decode tenant retriever engines");}
      }
    }
    var out=json.createArrayNode();Set<String> seen=new HashSet<>();
    for(String raw:env.getProperty("RETRIEVE_DRIVER","").split(",")){
      String driver=KnowledgeUploadForms.goTrim(raw),engine=driver.startsWith("elasticsearch_v")?"elasticsearch":driver;
      List<String> kinds=switch(driver){case "elasticsearch_v7"->List.of("keywords");case "milvus"->List.of("vector","keywords");case "postgres","sqlite","elasticsearch_v8","qdrant","weaviate","doris","tencent_vectordb","opensearch"->List.of("keywords","vector");default->List.of();};
      for(String kind:kinds)if(seen.add(kind+":"+engine))out.add(json.createObjectNode().put("retriever_type",kind).put("retriever_engine_type",engine));
    }
    return out;
  }
  private void audit(AuthContext caller,String id,String name) {
    bestEffort(()->{
      var details=json.createObjectNode().put("name",name);
      if(caller.isApiKey()){
        if(caller.apiKeyId()>0)details.put("api_key_id",caller.apiKeyId());
        var keys=jdbc.queryForList("SELECT name FROM tenant_api_keys WHERE id=?",caller.apiKeyId());
        if(!keys.isEmpty()&&!Objects.toString(keys.getFirst().get("name"),"").isEmpty())details.put("api_key_name",keys.getFirst().get("name").toString());
      }
      String actor=Objects.toString(caller.userId(),"");
      jdbc.update("INSERT INTO audit_logs(tenant_id,actor_user_id,actor_role,action,scope_type,scope_id,target_type,target_id,outcome,details) VALUES(?,?,?,'kb.deleted','knowledge_base',?,'knowledge_base',?,'success',"+(sqlite?"?":"CAST(? AS JSONB)")+")",caller.tenantId(),actor,actor.isEmpty()?"":caller.role(),id,id,details.toString());
    },"audit");
  }
  private Object now(){return sqlite?GoSqliteTimestamp.write(clock.instant()):Timestamp.from(clock.instant());}
  private static void detached(Runnable work){boolean interrupted=Thread.interrupted();try{CompletableFuture.runAsync(work,command->Thread.ofVirtual().name("kb-delete-admission-scrub").start(command)).join();}catch(RuntimeException error){warn("queue cleanup",error);}finally{if(interrupted)Thread.currentThread().interrupt();}}
  private static void bestEffort(Runnable work,String stage){try{work.run();}catch(Exception error){warn(stage,error);}}
  private static void warn(String stage,Exception error){org.slf4j.LoggerFactory.getLogger(KnowledgeBaseDeleteAdmission.class).warn("Knowledge base deletion {} failed ({})",stage,error.getClass().getSimpleName());}
  private static ApiException internal(String message){return new ApiException(500,"INTERNAL_SERVER_ERROR",message).withAppCode(1007);}
  private static ApiException databaseFailure(DataAccessException error){
    Throwable cause=error.getMostSpecificCause();String message=Objects.toString(cause.getMessage(),"");
    if(cause instanceof java.sql.SQLException sql&&cause.getClass().getName().equals("org.postgresql.util.PSQLException"))message=message.split("\n",2)[0]+" (SQLSTATE "+sql.getSQLState()+")";
    else {var matcher=java.util.regex.Pattern.compile("^\\[SQLITE_[A-Z_]+\\] [^(]+ \\((.*)\\)$",java.util.regex.Pattern.DOTALL).matcher(message);if(matcher.matches())message=matcher.group(1);}
    return internal(message);
  }
}
