package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.task.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Existing Go SQL ownership and task lease fences; graph storage is a separate transaction. */
public final class GraphExtractionRepository {
  private final JdbcTemplate jdbc;private final ObjectMapper json;private final KnowledgeRepository knowledge;private final Clock clock;private final TransactionTemplate tx;private final boolean postgres;
  public GraphExtractionRepository(JdbcTemplate jdbc,ObjectMapper json,KnowledgeRepository knowledge,PlatformTransactionManager manager,Clock clock){this.jdbc=jdbc;this.json=json;this.knowledge=knowledge;this.clock=clock;tx=new TransactionTemplate(manager);postgres=Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>)c->c.getMetaData().getDatabaseProductName().equals("PostgreSQL")));}
  public void payload(TaskRecord job)throws TaskFailure {if(job.payload().path("tenant_id").asLong()!=job.tenantId()||job.tenantId()<=0||job.payload().path("chunk_id").asText().isEmpty())throw new TaskFailure("invalid graph task binding",false);}
  public Optional<ObjectNode> knowledge(TaskRecord job,String doc){return knowledge.get(job.tenantId(),doc);}
  public ObjectNode base(TaskRecord job,String kb){Integer foreign=jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_bases WHERE id=? AND tenant_id<>? AND deleted_at IS NULL",Integer.class,kb,job.tenantId());if(foreign!=null&&foreign>0)throw new BindingChanged();return knowledge.knowledgeBase(job.tenantId(),kb);}
  public ObjectNode chunk(TaskRecord job){var rows=jdbc.queryForList("SELECT id,tenant_id,knowledge_id,knowledge_base_id,content,content_revision,chunk_type,parent_chunk_id FROM chunks WHERE tenant_id=? AND id=? AND deleted_at IS NULL",job.tenantId(),job.payload().path("chunk_id").asText());return rows.isEmpty()?null:json.valueToTree(rows.getFirst());}
  public boolean bound(TaskRecord job,ObjectNode doc,ObjectNode chunk){return doc!=null&&chunk!=null&&chunk.path("tenant_id").asLong()==job.tenantId()&&chunk.path("knowledge_id").equals(doc.path("id"))&&chunk.path("knowledge_base_id").equals(doc.path("knowledge_base_id"));}
  public boolean superseded(TaskRecord job){long attempt=job.payload().path("attempt").asLong();if(attempt<=0)return false;Long latest=jdbc.queryForObject("SELECT COALESCE(MAX(s.attempt),0) FROM knowledge_processing_spans s JOIN knowledges k ON k.id=s.knowledge_id WHERE k.tenant_id=? AND k.id=? AND k.deleted_at IS NULL",Long.class,job.tenantId(),job.payload().path("knowledge_id").asText());return latest!=null&&latest>attempt;}
  public String current(TaskRecord job,ObjectNode original,ObjectNode chunk){fence(job);if(superseded(job))return "attempt_superseded";var row=knowledge(job,original.path("id").asText());if(row.isEmpty())return "knowledge_not_found";String state=row.get().path("parse_status").asText();if(Set.of("cancelled","deleting").contains(state))return "knowledge_"+state;var current=chunk(job);if(current==null)return "chunk_disappeared";if(!bound(job,row.get(),current)||!row.get().path("knowledge_base_id").equals(original.path("knowledge_base_id")))throw new BindingChanged();if(current.path("content_revision").asLong()!=chunk.path("content_revision").asLong())return "chunk_changed";return "";}
  public void drain(TaskRecord job,ObjectNode original){if(original==null||job.payload().path("knowledge_id").asText().isEmpty())return;tx.executeWithoutResult(s->{if(superseded(job))return;var row=knowledge(job,original.path("id").asText());if(row.isEmpty()||!row.get().path("knowledge_base_id").equals(original.path("knowledge_base_id")))return;var leases=jdbc.queryForList("SELECT status,attempts,lease_owner,lease_until FROM java_task_jobs WHERE tenant_id=? AND id=?"+(postgres?" FOR UPDATE":""),job.tenantId(),job.id());if(leases.isEmpty()||!leased(job,leases.getFirst()))return;int inserted=jdbc.update("INSERT INTO java_graph_task_drains(task_id,tenant_id,knowledge_id,attempt) VALUES(?,?,?,?) ON CONFLICT(task_id) DO NOTHING",job.id(),job.tenantId(),original.path("id").asText(),job.payload().path("attempt").asLong());if(inserted==0)return;jdbc.update("UPDATE knowledges SET pending_subtasks_count=pending_subtasks_count-1,updated_at=? WHERE tenant_id=? AND id=? AND deleted_at IS NULL AND pending_subtasks_count>0",now(),job.tenantId(),original.path("id").asText());jdbc.update("UPDATE knowledges SET parse_status='completed',error_message='',processed_at=?,updated_at=? WHERE tenant_id=? AND id=? AND deleted_at IS NULL AND parse_status='finalizing' AND pending_subtasks_count=0",now(),now(),job.tenantId(),original.path("id").asText());});}
  public void fence(TaskRecord job){var rows=jdbc.queryForList("SELECT status,attempts,lease_owner,lease_until,cancel_requested FROM java_task_jobs WHERE tenant_id=? AND id=?",job.tenantId(),job.id());if(rows.isEmpty()||!leased(job,rows.getFirst())||bool(rows.getFirst().get("cancel_requested")))throw new CancellationException("graph job lease lost or canceled");}
  private boolean leased(TaskRecord job,Map<String,Object> row){return Objects.equals(row.get("status"),"running")&&number(row.get("attempts"))==job.attempts()&&Objects.equals(row.get("lease_owner"),job.leaseOwner())&&number(row.get("lease_until"))>clock.millis();}
  private static long number(Object v){return v instanceof Number n?n.longValue():0;}
  private static boolean bool(Object v){return Boolean.TRUE.equals(v)||number(v)!=0;}
  private Object now(){return postgres?Timestamp.from(clock.instant()):GoSqliteTimestamp.write(clock.instant());}
  static final class BindingChanged extends RuntimeException {}
  static final class Changed extends RuntimeException {final String reason;Changed(String reason){this.reason=reason;}}
}
