package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.weknora.security.AuthPreferences;
import com.tencent.weknora.task.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable cleanup of admitted document references. The payload is never a general write grant. */
public final class KnowledgeListDeleteRepository {
  public record Plan(List<Map<String,Object>> documents,ObjectNode base,List<String> images) {
    public List<String> ids(){return documents.stream().map(d->text(d,"id")).toList();}
  }
  @FunctionalInterface public interface IndexDeletion {void delete(Connection connection,List<String> ids,int dimension,String type)throws Exception;}
  private final JdbcTemplate jdbc;private final ObjectMapper json;private final TransactionTemplate tx;private final Clock clock;private final boolean postgres;
  public KnowledgeListDeleteRepository(JdbcTemplate jdbc,ObjectMapper json,PlatformTransactionManager manager,Clock clock){this.jdbc=jdbc;this.json=json;this.tx=new TransactionTemplate(manager);this.clock=clock;postgres=Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>)c->c.getMetaData().getDatabaseProductName().equals("PostgreSQL")));}
  public Plan plan(TaskRecord job,List<String> ids,String expectedKB)throws TaskFailure {
    if(ids.isEmpty())return new Plan(List.of(),json.createObjectNode(),List.of());
    try{return tx.execute(status->{
      fence(job);var args=new ArrayList<Object>();args.add(job.tenantId());args.addAll(ids);
      var rows=jdbc.queryForList("SELECT * FROM knowledges WHERE tenant_id=? AND id IN ("+marks(ids.size())+") AND deleted_at IS NULL"+lock(),args.toArray());
      var byId=new HashMap<String,Map<String,Object>>();for(var row:rows)byId.put(text(row,"id"),row);
      String kb=expectedKB;var ordered=new ArrayList<Map<String,Object>>();
      if(kb.isEmpty())for(var row:rows){String candidate=text(row,"knowledge_base_id");if(candidate.isEmpty()||!kb.isEmpty()&&!kb.equals(candidate))throw terminal("ambiguous legacy delete scope");kb=candidate;}
      for(String id:ids){var row=byId.get(id);if(row==null)continue;if(!kb.equals(text(row,"knowledge_base_id")))throw terminal("invalid delete task scope: cleanup resource binding changed");moving(row);ordered.add(row);}
      if(ordered.isEmpty())return new Plan(List.of(),json.createObjectNode(),List.of());
      var bases=jdbc.queryForList("SELECT * FROM knowledge_bases WHERE id=? AND deleted_at IS NULL"+lock(),kb);if(bases.isEmpty())throw retry("knowledge base not found");var raw=bases.getFirst();if(number(raw.get("tenant_id"))!=job.tenantId())throw terminal("invalid delete task scope: knowledge does not belong to its knowledge base");
      if(jdbc.queryForList("SELECT id FROM tenants WHERE id=? AND deleted_at IS NULL",job.tenantId()).isEmpty())throw retry("tenant not found");
      ObjectNode base=json.createObjectNode();for(String key:List.of("id","type","embedding_model_id","vector_store_id"))base.put(key,text(raw,key));base.put("tenant_id",job.tenantId());base.put("is_temporary",truth(raw.get("is_temporary")));
      for(String key:List.of("indexing_strategy","profile_config"))base.set(key,decode(raw.get(key)));
      if(base.path("indexing_strategy").path("wiki_enabled").asBoolean())throw terminal("Native Wiki document deletion is not migrated");
      var imageArgs=new ArrayList<Object>();imageArgs.add(job.tenantId());imageArgs.addAll(ordered.stream().map(d->text(d,"id")).toList());var images=new LinkedHashSet<String>();
      for(var row:jdbc.queryForList("SELECT image_info FROM chunks WHERE tenant_id=? AND knowledge_id IN ("+marks(ordered.size())+") AND deleted_at IS NULL",imageArgs.toArray()))try{for(var image:decode(row.get("image_info"))){String url=image.path("url").asText();if(!url.isEmpty())images.add(url);}}catch(RuntimeException ignored){/* Go logs malformed image metadata and continues. */}
      return new Plan(List.copyOf(ordered),base,List.copyOf(images));
    });}catch(Failure e){throw new TaskFailure(e.getMessage(),e.retryable);}
  }
  public void mark(TaskRecord job,Plan plan){tx.executeWithoutResult(s->{check(job,plan);for(var row:plan.documents()){
    int changed=jdbc.update("UPDATE knowledges SET parse_status='deleting',updated_at=? WHERE tenant_id=? AND id=? AND knowledge_base_id=? AND parse_status=? AND updated_at=? AND deleted_at IS NULL",time(),job.tenantId(),text(row,"id"),plan.base().path("id").asText(),text(row,"parse_status"),row.get("updated_at"));
    if(changed!=1)throw retry("knowledge changed during transfer");
    row.put("updated_at",jdbc.queryForObject("SELECT updated_at FROM knowledges WHERE tenant_id=? AND id=?",Object.class,job.tenantId(),text(row,"id")));row.put("parse_status","deleting");
  }});}
  public void indexes(TaskRecord job,Plan plan,List<String> ids,int dimension,String type,IndexDeletion deletion){tx.executeWithoutResult(s->{check(job,plan);jdbc.execute((ConnectionCallback<Void>)c->{try{deletion.delete(c,ids,dimension,type);}catch(CancellationException e){throw e;}catch(Exception e){throw retry("Document index cleanup failed");}return null;});check(job,plan);});}
  public void chunks(TaskRecord job,Plan plan){tx.executeWithoutResult(s->{check(job,plan);var args=new ArrayList<Object>();args.add(time());args.add(job.tenantId());args.add(plan.base().path("id").asText());args.addAll(plan.ids());jdbc.update("UPDATE chunks SET deleted_at=? WHERE tenant_id=? AND knowledge_base_id=? AND knowledge_id IN ("+marks(plan.ids().size())+") AND deleted_at IS NULL",args.toArray());});}
  public void deleted(TaskRecord job,Plan plan){
    for(String id:plan.ids())try{tx.executeWithoutResult(s->{check(job,plan);jdbc.update("DELETE FROM knowledge_tag_relations WHERE knowledge_id=?",id);});}catch(CancellationException e){throw e;}catch(RuntimeException ignored){}
    tx.executeWithoutResult(s->{check(job,plan);var args=new ArrayList<Object>();args.add(time());args.add(job.tenantId());args.add(plan.base().path("id").asText());args.addAll(plan.ids());jdbc.update("UPDATE knowledges SET deleted_at=? WHERE tenant_id=? AND knowledge_base_id=? AND id IN ("+marks(plan.ids().size())+") AND deleted_at IS NULL",args.toArray());});
  }
  public void accounting(TaskRecord job,Plan plan){try{tx.executeWithoutResult(s->{fence(job);baseOwner(job,plan);long delta=0;for(var row:plan.documents())delta=Math.addExact(delta,number(row.get("storage_size")));jdbc.update("UPDATE tenants SET storage_used=CASE WHEN storage_used-? < 0 THEN 0 ELSE storage_used-? END WHERE id=? AND deleted_at IS NULL",delta,delta,job.tenantId());});}catch(CancellationException e){throw e;}catch(RuntimeException ignored){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Document deletion storage accounting failed");}}
  public void audit(TaskRecord job,Plan plan){try{
    var initiator=job.payload().path("initiator");String actor=initiator.path("user_id").asText(),role=initiator.path("role").asText();boolean synthetic=actor.matches("system-[0-9]+")||actor.isEmpty();if(actor.isEmpty())role="";if(!Set.of("viewer","contributor","admin","owner").contains(role))role="";
    var details=json.createObjectNode().put("count",plan.ids().size()).put("trigger",synthetic?"system":"user").put("processing_status","completed").put("task_id",job.id());if(plan.ids().size()<=20)details.set("knowledge_ids",json.valueToTree(plan.ids()));var titles=new LinkedHashSet<String>();for(var row:plan.documents()){String title=AuthPreferences.trimGoSpace(text(row,"title"));if(!title.isEmpty()&&titles.size()<5)titles.add(title);}if(!titles.isEmpty()){details.put("title",titles.getFirst());if(titles.size()>1)details.set("titles",json.valueToTree(titles));}if(initiator.path("api_key_id").asLong()>0)details.set("api_key_id",initiator.get("api_key_id"));if(!initiator.path("api_key_name").asText().isEmpty())details.set("api_key_name",initiator.get("api_key_name"));
    jdbc.update("INSERT INTO audit_logs(tenant_id,actor_user_id,actor_role,action,target_type,target_id,scope_type,scope_id,outcome,details) VALUES(?,?,?,'knowledge.batch_deleted','knowledge','','knowledge_base',?,'success',"+(postgres?"CAST(? AS JSONB)":"?")+")",job.tenantId(),actor,role,plan.base().path("id").asText(),details.toString());
  }catch(RuntimeException ignored){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Document deletion activity audit failed");}}
  public void check(TaskRecord job,Plan plan){fence(job);baseOwner(job,plan);for(var expected:plan.documents()){
    var rows=jdbc.queryForList("SELECT tenant_id,knowledge_base_id,metadata,deleted_at,parse_status,updated_at FROM knowledges WHERE id=?"+lock(),text(expected,"id"));
    if(rows.isEmpty()||number(rows.getFirst().get("tenant_id"))!=job.tenantId()||!text(rows.getFirst(),"knowledge_base_id").equals(plan.base().path("id").asText())||rows.getFirst().get("deleted_at")!=null||!Objects.equals(rows.getFirst().get("updated_at"),expected.get("updated_at"))||!text(rows.getFirst(),"parse_status").equals(text(expected,"parse_status")))throw new CancellationException("Document cleanup binding changed");moving(rows.getFirst());
  }}
  public void afterDeletion(TaskRecord job,Plan plan){fence(job);baseOwner(job,plan);for(var expected:plan.documents()){var rows=jdbc.queryForList("SELECT tenant_id,knowledge_base_id,deleted_at,file_path FROM knowledges WHERE id=?",text(expected,"id"));if(rows.isEmpty()||number(rows.getFirst().get("tenant_id"))!=job.tenantId()||!text(rows.getFirst(),"knowledge_base_id").equals(plan.base().path("id").asText())||rows.getFirst().get("deleted_at")==null||!text(rows.getFirst(),"file_path").equals(text(expected,"file_path")))throw new CancellationException("Deleted document cleanup binding changed");}}
  private void baseOwner(TaskRecord job,Plan plan){var rows=jdbc.queryForList("SELECT tenant_id FROM knowledge_bases WHERE id=? AND deleted_at IS NULL"+lock(),plan.base().path("id").asText());if(rows.isEmpty()||number(rows.getFirst().get("tenant_id"))!=job.tenantId())throw new CancellationException("Document cleanup KB owner changed");}
  private void fence(TaskRecord job){var rows=jdbc.queryForList("SELECT status,attempts,lease_owner,lease_until,cancel_requested FROM java_task_jobs WHERE tenant_id=? AND id=?"+lock(),job.tenantId(),job.id());if(rows.isEmpty())throw new CancellationException("Document cleanup lease unavailable");var row=rows.getFirst();if(!text(row,"status").equals("running")||number(row.get("attempts"))!=job.attempts()||!Objects.equals(row.get("lease_owner"),job.leaseOwner())||number(row.get("lease_until"))<=clock.millis()||truth(row.get("cancel_requested")))throw new CancellationException("Document cleanup lease lost or canceled");}
  private void moving(Map<String,Object> row){boolean moving;try{moving=new KnowledgeTransferState(json).moving(row.get("metadata"));}catch(IllegalArgumentException e){throw retry("invalid stored cleanup metadata");}if(moving)throw terminal("invalid delete task scope: knowledge has an unfinished move; retry the move first");}
  private JsonNode decode(Object value){if(value==null||value.toString().isEmpty())return json.createObjectNode();try{return value instanceof byte[] b?json.readTree(b):json.readTree(value.toString());}catch(Exception e){throw retry("invalid stored cleanup metadata");}}
  private Object time(){return postgres?Timestamp.from(clock.instant()):GoSqliteTimestamp.write(clock.instant());}
  private String lock(){return postgres?" FOR UPDATE":"";}
  private static String marks(int count){return String.join(",",Collections.nCopies(count,"?"));}
  static String text(Map<String,Object> row,String key){return Objects.toString(row.get(key),"");}
  private static long number(Object value){return value instanceof Number n?n.longValue():0;}
  private static boolean truth(Object value){return Boolean.TRUE.equals(value)||number(value)!=0;}
  private static Failure terminal(String message){return new Failure(message,false);}private static Failure retry(String message){return new Failure(message,true);}
  static final class Failure extends RuntimeException {final boolean retryable;Failure(String message,boolean retryable){super(message);this.retryable=retryable;}}
}
