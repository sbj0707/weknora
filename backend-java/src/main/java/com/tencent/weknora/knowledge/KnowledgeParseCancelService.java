package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.weknora.security.*;
import com.tencent.weknora.task.TaskRepository;
import java.time.Clock;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Cancels a native processing attempt without removing its published content. */
@Service
@ConditionalOnProperty(name="weknora.knowledge.processing.enabled",havingValue="true")
public final class KnowledgeParseCancelService {
  private record Transition(ObjectNode knowledge,boolean changed,long attempt) {}
  private final JdbcTemplate jdbc;
  private final KnowledgeContentReadRepository reads;
  private final KnowledgeProcessingSpanTracker tracker;
  private final TaskRepository tasks;
  private final ObjectMapper json;
  private final TransactionTemplate transaction;
  private final Clock clock;
  private final boolean postgres;

  @org.springframework.beans.factory.annotation.Autowired
  public KnowledgeParseCancelService(JdbcTemplate jdbc,KnowledgeContentReadRepository reads,
      KnowledgeProcessingSpanTracker tracker,TaskRepository tasks,ObjectMapper json,
      PlatformTransactionManager manager) {
    this(jdbc,reads,tracker,tasks,json,manager,Clock.systemUTC());
  }
  KnowledgeParseCancelService(JdbcTemplate jdbc,KnowledgeContentReadRepository reads,
      KnowledgeProcessingSpanTracker tracker,TaskRepository tasks,ObjectMapper json,
      PlatformTransactionManager manager,Clock clock) {
    this.jdbc=jdbc;this.reads=reads;this.tracker=tracker;this.tasks=tasks;this.json=json;
    this.transaction=new TransactionTemplate(manager);this.clock=clock;
    postgres=Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>)c->c.getMetaData().getDatabaseProductName().equals("PostgreSQL")));
  }

  public ObjectNode cancel(AuthContext caller,long owner,String kb,String knowledge) {
    Transition transition=transaction.execute(status->{
      var rows=jdbc.queryForList("SELECT * FROM knowledges WHERE tenant_id=? AND knowledge_base_id=? AND id=? AND deleted_at IS NULL"+(postgres?" FOR UPDATE":""),owner,kb,knowledge);
      if(rows.isEmpty())throw new ApiException(404,"NOT_FOUND","knowledge not found").withAppCode(1003);
      ObjectNode current=reads.projectKnowledge(rows.getFirst());
      checkStatus(current.path("parse_status").asText());
      if(current.path("parse_status").asText().equals("cancelled"))return new Transition(current,false,0);
      String now=clock.instant().toString();
      int updated=jdbc.update("UPDATE knowledges SET parse_status='cancelled',error_message='用户已取消解析',pending_subtasks_count=0,summary_status=CASE WHEN summary_status IN ('pending','processing') THEN 'none' ELSE summary_status END,updated_at=? WHERE tenant_id=? AND knowledge_base_id=? AND id=? AND deleted_at IS NULL AND parse_status NOT IN ('completed','failed','deleting','cancelled')",postgres?java.sql.Timestamp.from(clock.instant()):GoSqliteTimestamp.write(clock.instant()),owner,kb,knowledge);
      if(updated!=1){
        ObjectNode latest=reads.knowledge(owner,knowledge);checkStatus(latest.path("parse_status").asText());
        if(latest.path("parse_status").asText().equals("cancelled"))return new Transition(latest,false,0);
        throw new ApiException(409,"CONFLICT","Knowledge parse state changed; retry cancellation").withAppCode(1005);
      }
      current.put("parse_status","cancelled").put("error_message","用户已取消解析").put("pending_subtasks_count",0).put("updated_at",now);
      if(Set.of("pending","processing").contains(current.path("summary_status").asText()))current.put("summary_status","none");
      return new Transition(current,true,tracker.latest(owner,knowledge));
    });
    if(transition.changed())tracker.abort(owner,knowledge,transition.attempt(),"USER_CANCELLED","用户已取消解析","用户已取消解析");
    bestEffort(()->tasks.cancelKnowledge(owner,knowledge),"Native document task cancellation failed");
    if(transition.changed()){
      bestEffort(()->jdbc.update("DELETE FROM task_pending_ops WHERE tenant_id=? AND task_type='wiki:ingest' AND scope='knowledge_base' AND scope_id=? AND dedup_key=? AND op='ingest'",owner,kb,knowledge),"Pending Wiki ingest cleanup failed");
      audit(caller,owner,kb,transition.knowledge());
    }
    return transition.knowledge();
  }
  private static void checkStatus(String status){
    if(Set.of("completed","failed").contains(status))throw new ApiException(400,"BAD_REQUEST","解析已结束，无法取消").withAppCode(1000);
    if(status.equals("deleting"))throw new ApiException(400,"BAD_REQUEST","知识正在删除中，无法取消解析").withAppCode(1000);
  }
  private void audit(AuthContext caller,long owner,String kb,ObjectNode knowledge){
    bestEffort(()->{
      var details=json.createObjectNode().put("title",knowledge.path("title").asText()).put("type",knowledge.path("type").asText());
      if(caller.isApiKey()){
        if(caller.apiKeyId()!=null&&caller.apiKeyId()>0)details.put("api_key_id",caller.apiKeyId());
        var keys=jdbc.queryForList("SELECT name FROM tenant_api_keys WHERE id=?",caller.apiKeyId());
        if(!keys.isEmpty()&&keys.getFirst().get("name")!=null&&!keys.getFirst().get("name").toString().isEmpty())details.put("api_key_name",keys.getFirst().get("name").toString());
      }
      String actor=caller.userId();boolean synthetic=actor.isEmpty()||actor.matches("system-[0-9]+");
      return jdbc.update("INSERT INTO audit_logs(tenant_id,actor_user_id,actor_role,action,target_type,target_id,scope_type,scope_id,outcome,details) VALUES(?,?,?,'knowledge.parse_canceled','knowledge',?,'knowledge_base',?,'canceled',"+(postgres?"CAST(? AS JSONB)":"?")+")",owner,synthetic?"":actor,synthetic?"":caller.role(),knowledge.path("id").asText(),kb,details.toString());
    },"Knowledge parse cancellation audit failed");
  }
  private void bestEffort(java.util.function.IntSupplier operation,String message){try{operation.getAsInt();}catch(RuntimeException error){org.slf4j.LoggerFactory.getLogger(getClass()).warn(message);}}
}
