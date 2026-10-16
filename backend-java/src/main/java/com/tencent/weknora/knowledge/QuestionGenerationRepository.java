package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.retrieval.*;
import com.tencent.weknora.task.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Independent Doc2Query metadata/index checkpoints; construction never changes the schema. */
public final class QuestionGenerationRepository {
  private final JdbcTemplate jdbc;private final ObjectMapper json;private final SummaryGenerationRepository shared;private final KnowledgeRepository knowledge;private final TransactionTemplate tx;private final Clock clock;private final boolean postgres;
  public QuestionGenerationRepository(JdbcTemplate jdbc,ObjectMapper json,KnowledgeRepository knowledge,PlatformTransactionManager manager,Clock clock) {
    this.jdbc=jdbc;this.json=json;this.knowledge=knowledge;this.clock=clock;tx=new TransactionTemplate(manager);shared=new SummaryGenerationRepository(jdbc,json,knowledge,manager,clock);
    postgres=Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>)c->c.getMetaData().getDatabaseProductName().equals("PostgreSQL")));
  }
  public void binding(TaskRecord job)throws TaskFailure {JsonNode p=job.payload();if(p.path("tenant_id").asLong()<=0||p.path("tenant_id").asLong()!=job.tenantId()||p.path("knowledge_id").asText().isEmpty()||p.path("knowledge_base_id").asText().isEmpty())throw new TaskFailure("invalid question task binding",false);var row=knowledge.get(job.tenantId(),p.path("knowledge_id").asText());if(row.isPresent()&&!row.get().path("knowledge_base_id").equals(p.path("knowledge_base_id")))throw new TaskFailure("question task knowledge binding changed",false);}
  public Optional<SummaryGenerationRepository.Snapshot> load(TaskRecord job)throws TaskFailure {try{return shared.load(job);}catch(com.tencent.weknora.security.ApiException e){if(e.status()==404)throw new MissingBase();throw e;}}
  public boolean superseded(TaskRecord job){return shared.superseded(job);}
  public boolean businessCanceled(TaskRecord job){return shared.businessCanceled(job);}
  public ObjectNode chunk(SummaryGenerationRepository.Snapshot snapshot,String id){return snapshot.all().stream().filter(c->c.path("id").asText().equals(id)).findFirst().orElse(null);}
  public List<ObjectNode> images(TaskRecord job,SummaryGenerationRepository.Snapshot snapshot,List<ObjectNode> chunks) {
    return shared.images(job,new SummaryGenerationRepository.Snapshot(snapshot.knowledge(),snapshot.knowledgeBase(),snapshot.all(),chunks,snapshot.metadata()));
  }
  /** Go overwrites document metadata; revision zero is omitted at the metadata level only. */
  public boolean metadata(TaskRecord job,SummaryGenerationRepository.Snapshot snapshot,ObjectNode chunk,ArrayNode questions) {
    return Boolean.TRUE.equals(tx.execute(s->{guard(job);var metadata=json.createObjectNode().set("generated_questions",questions);long revision=chunk.path("content_revision").asLong();if(revision!=0)((ObjectNode)metadata).put("generated_questions_revision",revision);
      return jdbc.update("UPDATE chunks SET metadata="+(postgres?"?::jsonb":"?")+",updated_at=? WHERE tenant_id=? AND knowledge_id=? AND knowledge_base_id=? AND id=? AND content_revision=? AND deleted_at IS NULL",metadata.toString(),now(),job.tenantId(),snapshot.knowledge().path("id").asText(),snapshot.knowledgeBase().path("id").asText(),chunk.path("id").asText(),revision)>0;}));
  }
  public boolean current(TaskRecord job,SummaryGenerationRepository.Snapshot snapshot,ObjectNode chunk) {
    guard(job);Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM chunks WHERE tenant_id=? AND knowledge_id=? AND knowledge_base_id=? AND id=? AND content_revision=? AND deleted_at IS NULL",Integer.class,job.tenantId(),snapshot.knowledge().path("id").asText(),snapshot.knowledgeBase().path("id").asText(),chunk.path("id").asText(),chunk.path("content_revision").asLong());return count!=null&&count==1;
  }
  public void index(TaskRecord job,SummaryGenerationRepository.Snapshot snapshot,List<IndexEntry> entries,Map<String,float[]> vectors,IndexWriter writer,Map<String,ObjectNode> sources) {
    tx.executeWithoutResult(s->{guard(job);var eligible=entries.stream().filter(e->current(job,snapshot,sources.get(e.chunkId()))).toList();if(eligible.isEmpty())return;
      var filtered=new LinkedHashMap<String,float[]>();eligible.forEach(e->filtered.put(e.sourceId(),vectors.get(e.sourceId())));jdbc.execute((ConnectionCallback<Void>)c->{writer.batchSave(c,eligible,filtered);return null;});guard(job);});
  }
  public void drain(TaskRecord job) {
    tx.executeWithoutResult(s->{if(superseded(job))return;var leases=jdbc.queryForList("SELECT status,attempts,lease_owner,lease_until FROM java_task_jobs WHERE id=? AND tenant_id=?"+(postgres?" FOR UPDATE":""),job.id(),job.tenantId());if(leases.isEmpty())return;var lease=leases.getFirst();if(!Objects.equals(lease.get("status"),"running")||number(lease.get("attempts"))!=job.attempts()||!Objects.equals(lease.get("lease_owner"),job.leaseOwner())||number(lease.get("lease_until"))<=clock.millis())return;
      String id=job.payload().path("knowledge_id").asText();var bound=knowledge.get(job.tenantId(),id);if(bound.isEmpty()||job.payload().path("tenant_id").asLong()!=job.tenantId()||!bound.get().path("knowledge_base_id").equals(job.payload().path("knowledge_base_id")))return;
      int inserted=jdbc.update("INSERT INTO java_question_task_drains(task_id,tenant_id,knowledge_id,attempt) VALUES(?,?,?,?) ON CONFLICT(task_id) DO NOTHING",job.id(),job.tenantId(),id,job.payload().path("attempt").asLong());if(inserted==0)return;
      jdbc.update("UPDATE knowledges SET pending_subtasks_count=pending_subtasks_count-1,updated_at=? WHERE tenant_id=? AND id=? AND deleted_at IS NULL AND pending_subtasks_count>0",now(),job.tenantId(),id);
      jdbc.update("UPDATE knowledges SET parse_status='completed',error_message='',processed_at=?,updated_at=? WHERE tenant_id=? AND id=? AND deleted_at IS NULL AND parse_status='finalizing' AND pending_subtasks_count=0",now(),now(),job.tenantId(),id);});
  }
  void check(TaskRecord job){guard(job);}
  private void guard(TaskRecord job){fence(job);if(superseded(job))throw new Stale();if(businessCanceled(job))throw new CancellationException("question knowledge canceled");}
  private void fence(TaskRecord job){var rows=jdbc.queryForList("SELECT status,attempts,lease_owner,lease_until,cancel_requested FROM java_task_jobs WHERE tenant_id=? AND id=?"+(postgres?" FOR UPDATE":""),job.tenantId(),job.id());if(rows.isEmpty())throw new CancellationException("question job unavailable");var r=rows.getFirst();if(!Objects.equals(r.get("status"),"running")||number(r.get("attempts"))!=job.attempts()||!Objects.equals(r.get("lease_owner"),job.leaseOwner())||number(r.get("lease_until"))<=clock.millis()||bool(r.get("cancel_requested")))throw new CancellationException("question job lease lost or canceled");}
  private Object now(){return postgres?Timestamp.from(clock.instant()):GoSqliteTimestamp.write(clock.instant());}
  private static boolean bool(Object v){return Boolean.TRUE.equals(v)||number(v)!=0;}
  private static long number(Object v){return v instanceof Number n?n.longValue():0;}
  static final class MissingBase extends RuntimeException {}
  static final class Stale extends RuntimeException {}
}
