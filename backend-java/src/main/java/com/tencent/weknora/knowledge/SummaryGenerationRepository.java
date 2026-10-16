package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.retrieval.*;
import com.tencent.weknora.task.*;
import java.io.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Owner/attempt/revision/lease fences on the existing Go summary-owned columns. */
public final class SummaryGenerationRepository {
  public record Snapshot(ObjectNode knowledge,ObjectNode knowledgeBase,List<ObjectNode> all,List<ObjectNode> text,String metadata) {}
  private final JdbcTemplate jdbc;private final ObjectMapper json;private final KnowledgeRepository knowledge;private final TransactionTemplate tx;private final Clock clock;private final boolean postgres;
  public SummaryGenerationRepository(JdbcTemplate jdbc,ObjectMapper json,KnowledgeRepository knowledge,PlatformTransactionManager manager,Clock clock) {
    this.jdbc=jdbc;this.json=json;this.knowledge=knowledge;this.tx=new TransactionTemplate(manager);this.clock=clock;
    postgres=Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>)c->c.getMetaData().getDatabaseProductName().equals("PostgreSQL")));
  }
  public Optional<Snapshot> load(TaskRecord job)throws TaskFailure {
    JsonNode p=job.payload();long owner=p.path("tenant_id").asLong();String id=p.path("knowledge_id").asText(),kb=p.path("knowledge_base_id").asText();
    if(owner<=0||owner!=job.tenantId()||id.isEmpty()||kb.isEmpty())throw new TaskFailure("invalid summary task binding",false);
    var row=knowledge.get(owner,id);if(row.isEmpty())return Optional.empty();if(!row.get().path("knowledge_base_id").asText().equals(kb))throw new TaskFailure("summary task knowledge binding changed",false);
    if(jdbc.queryForList("SELECT id FROM tenants WHERE id=? AND deleted_at IS NULL",owner).isEmpty())throw new TaskFailure("summary tenant is unavailable",true);
    ObjectNode base=knowledge.knowledgeBase(owner,kb);var all=chunks(owner,id,kb);boolean refresh=p.path("refresh").asBoolean();var text=all.stream().filter(c->c.path("chunk_type").asText().equals("text")&&(!refresh||c.path("is_enabled").asBoolean())).sorted(Comparator.comparingLong(c->c.path("chunk_index").asLong())).toList();
    String raw=jdbc.queryForObject("SELECT custom_metadata FROM knowledges WHERE tenant_id=? AND id=? AND deleted_at IS NULL",(rs,n)->Objects.toString(rs.getString(1),""),owner,id);
    return Optional.of(new Snapshot(row.get(),base,all,text,raw));
  }
  private List<ObjectNode> chunks(long owner,String id,String kb) {
    return jdbc.query("SELECT * FROM chunks WHERE tenant_id=? AND knowledge_id=? AND knowledge_base_id=? AND deleted_at IS NULL ORDER BY chunk_index,id",(rs,n)->{ObjectNode out=json.createObjectNode();var md=rs.getMetaData();for(int i=1;i<=md.getColumnCount();i++){String key=md.getColumnLabel(i);Object v=rs.getObject(i);if(v==null)out.putNull(key);else if(key.equals("is_enabled"))out.put(key,rs.getBoolean(i));else if(v instanceof Number number)out.put(key,number.longValue());else out.put(key,v.toString());}return out;},owner,id,kb);
  }
  /** Public synchronous generation has no queue lease and must not alter task accounting. */
  public Snapshot loadRefresh(long owner,String id) {
    var row=knowledge.get(owner,id).orElseThrow(()->new IllegalStateException("knowledge not found"));
    var base=knowledge.knowledgeBase(owner,row.path("knowledge_base_id").asText());
    var all=chunks(owner,id,base.path("id").asText());
    var text=all.stream().filter(c->c.path("chunk_type").asText().equals("text")&&c.path("is_enabled").asBoolean()).sorted(Comparator.comparingLong(c->c.path("chunk_index").asLong())).toList();
    String metadata=jdbc.queryForObject("SELECT custom_metadata FROM knowledges WHERE tenant_id=? AND id=? AND deleted_at IS NULL",(rs,n)->Objects.toString(rs.getString(1),""),owner,id);
    return new Snapshot(row,base,all,text,metadata);
  }
  public List<ObjectNode> images(TaskRecord job,Snapshot snapshot) {
    return images(snapshot);
  }
  public List<ObjectNode> images(Snapshot snapshot) {
    var parents=new HashSet<String>();snapshot.text().forEach(c->parents.add(c.path("id").asText()));var nested=new HashSet<String>();for(var c:snapshot.all())if(c.path("is_enabled").asBoolean()&&c.path("chunk_type").asText().equals("text")&&parents.contains(c.path("parent_chunk_id").asText()))nested.add(c.path("id").asText());parents.addAll(nested);
    var merged=new LinkedHashMap<String,ObjectNode>();for(var c:snapshot.all())if(c.path("is_enabled").asBoolean()&&parents.contains(c.path("parent_chunk_id").asText())&&Set.of("image_ocr","image_caption").contains(c.path("chunk_type").asText()))try{JsonNode value=json.readTree(c.path("image_info").asText());if(value!=null&&value.isArray())for(var image:value){if(!image.isObject())continue;String key=image.path("url").asText(image.path("original_url").asText());if(key.isEmpty())key=image.path("original_url").asText();if(key.isEmpty())continue;ObjectNode old=merged.get(key);if(old==null)merged.put(key,(ObjectNode)image.deepCopy());else for(String f:List.of("ocr_text","caption"))if(!image.path(f).asText().isEmpty())old.set(f,image.get(f));}}catch(IOException ignored){}
    return List.copyOf(merged.values());
  }
  public boolean superseded(TaskRecord job){long attempt=job.payload().path("attempt").asLong();return attempt>0&&latest(job)>attempt;}
  private long latest(TaskRecord job){Long n=jdbc.queryForObject("SELECT COALESCE(MAX(s.attempt),0) FROM knowledge_processing_spans s JOIN knowledges k ON k.id=s.knowledge_id WHERE k.tenant_id=? AND k.id=? AND k.deleted_at IS NULL",Long.class,job.tenantId(),job.payload().path("knowledge_id").asText());return n==null?0:n;}
  public boolean sourceChanged(TaskRecord job,Snapshot snapshot) {
    var row=knowledge.get(job.tenantId(),snapshot.knowledge().path("id").asText());if(row.isEmpty()||!row.get().path("knowledge_base_id").equals(snapshot.knowledge().path("knowledge_base_id")))return true;
    String current=jdbc.queryForObject("SELECT custom_metadata FROM knowledges WHERE tenant_id=? AND id=?",(rs,n)->Objects.toString(rs.getString(1),""),job.tenantId(),snapshot.knowledge().path("id").asText());if(!Objects.equals(current,snapshot.metadata()))return true;
    for(var chunk:snapshot.text()){var rows=jdbc.queryForList("SELECT content_revision,is_enabled FROM chunks WHERE tenant_id=? AND knowledge_id=? AND knowledge_base_id=? AND id=? AND deleted_at IS NULL",job.tenantId(),snapshot.knowledge().path("id").asText(),snapshot.knowledgeBase().path("id").asText(),chunk.path("id").asText());if(rows.isEmpty())return true;var c=rows.getFirst();if(number(c.get("content_revision"))!=chunk.path("content_revision").asLong()||bool(c.get("is_enabled"))!=chunk.path("is_enabled").asBoolean())return true;}
    return superseded(job);
  }
  public void state(TaskRecord job,Snapshot snapshot,String status,String description,JsonNode profile,boolean freshness) {
    tx.executeWithoutResult(s->{fence(job);if(businessCanceled(job))throw new CancellationException("summary knowledge canceled");if(superseded(job)||(freshness&&sourceChanged(job,snapshot)))throw new Stale();
      jdbc.update("UPDATE knowledges SET description=?,profile="+(postgres?"?::jsonb":"?")+",summary_status=?,updated_at=? WHERE tenant_id=? AND id=? AND knowledge_base_id=? AND deleted_at IS NULL AND parse_status NOT IN ('cancelled','deleting')",description,encode(profile),status,now(),job.tenantId(),snapshot.knowledge().path("id").asText(),snapshot.knowledgeBase().path("id").asText());});
  }
  public List<IndexEntry> summaries(TaskRecord job,Snapshot snapshot,String content,boolean refresh) {
    return tx.execute(s->{fence(job);if(businessCanceled(job))throw new CancellationException("summary knowledge canceled");if(sourceChanged(job,snapshot))throw new Stale();var entries=new ArrayList<IndexEntry>();
      if(refresh)for(var c:snapshot.all())if(c.path("chunk_type").asText().equals("summary")){String id=c.path("id").asText();jdbc.update("UPDATE chunks SET content=?,source_content=?,is_enabled=true,updated_at=? WHERE tenant_id=? AND knowledge_id=? AND knowledge_base_id=? AND id=? AND deleted_at IS NULL",content,content,now(),job.tenantId(),snapshot.knowledge().path("id").asText(),snapshot.knowledgeBase().path("id").asText(),id);entries.add(entry(job,snapshot,id,content));}
      if(entries.isEmpty()){String id=UUID.randomUUID().toString();long index=snapshot.all().stream().filter(c->c.path("chunk_type").asText().equals("text")).mapToLong(c->c.path("chunk_index").asLong()).max().orElse(0)+1;index=Math.max(1,index);jdbc.update("INSERT INTO chunks(id,tenant_id,knowledge_base_id,knowledge_id,content,chunk_index,is_enabled,start_at,end_at,chunk_type,parent_chunk_id,status,created_at,updated_at"+(postgres?"":",seq_id")+") VALUES(?,?,?,?,?,?,true,0,0,'summary',?,0,?,?"+(postgres?"":",(SELECT COALESCE(MAX(seq_id),0)+1 FROM chunks)")+")",id,job.tenantId(),snapshot.knowledgeBase().path("id").asText(),snapshot.knowledge().path("id").asText(),content,index,snapshot.text().getFirst().path("id").asText(),now(),now());entries.add(entry(job,snapshot,id,content));}return List.copyOf(entries);});
  }
  private IndexEntry entry(TaskRecord job,Snapshot s,String id,String content){String title=job.payload().path("refresh").asBoolean()?s.knowledge().path("title").asText():"";if(!title.isEmpty())content=title+"\n"+content;return new IndexEntry(id,0,id,s.knowledge().path("id").asText(),s.knowledgeBase().path("id").asText(),"",content,true);}
  public boolean businessCanceled(TaskRecord job){var current=knowledge.get(job.tenantId(),job.payload().path("knowledge_id").asText());return current.isEmpty()||Set.of("cancelled","deleting").contains(current.get().path("parse_status").asText());}
  public void deleteRefreshIndexes(TaskRecord job,Snapshot snapshot,List<IndexEntry> entries,IndexWriter writer) {
    if(!job.payload().path("refresh").asBoolean())return;
    tx.executeWithoutResult(s->{fence(job);if(businessCanceled(job))throw new CancellationException("summary knowledge canceled");if(sourceChanged(job,snapshot))throw new Stale();jdbc.execute((ConnectionCallback<Void>)c->{writer.deleteByChunk(c,entries.stream().map(IndexEntry::chunkId).toList());return null;});});
  }
  public void index(TaskRecord job,Snapshot snapshot,List<IndexEntry> entries,Map<String,float[]> vectors,IndexWriter writer) {
    tx.executeWithoutResult(s->{fence(job);if(businessCanceled(job))throw new CancellationException("summary knowledge canceled");if(sourceChanged(job,snapshot))throw new Stale();jdbc.execute((ConnectionCallback<Void>)c->{writer.batchSave(c,entries,vectors);return null;});fence(job);});
  }
  /** Terminal releases are detached from task cancellation but never from the owner/attempt. */
  public void drain(TaskRecord job) {
    if(job.payload().path("refresh").asBoolean())return;
    tx.executeWithoutResult(s->{if(superseded(job))return;var leases=jdbc.queryForList("SELECT status,attempts,lease_owner,lease_until FROM java_task_jobs WHERE id=? AND tenant_id=?"+(postgres?" FOR UPDATE":""),job.id(),job.tenantId());if(leases.isEmpty())return;var lease=leases.getFirst();if(!Objects.equals(lease.get("status"),"running")||number(lease.get("attempts"))!=job.attempts()||!Objects.equals(lease.get("lease_owner"),job.leaseOwner())||number(lease.get("lease_until"))<=clock.millis())return;
      String id=job.payload().path("knowledge_id").asText();var bound=knowledge.get(job.tenantId(),id);if(bound.isEmpty()||job.payload().path("tenant_id").asLong()!=job.tenantId()||!bound.get().path("knowledge_base_id").equals(job.payload().path("knowledge_base_id")))return;
      int inserted=jdbc.update("INSERT INTO java_summary_task_drains(task_id,tenant_id,knowledge_id,attempt) VALUES(?,?,?,?) ON CONFLICT(task_id) DO NOTHING",job.id(),job.tenantId(),id,job.payload().path("attempt").asLong());if(inserted==0)return;
      jdbc.update("UPDATE knowledges SET pending_subtasks_count=pending_subtasks_count-1,updated_at=? WHERE tenant_id=? AND id=? AND deleted_at IS NULL AND pending_subtasks_count>0",now(),job.tenantId(),id);
      jdbc.update("UPDATE knowledges SET parse_status='completed',error_message='',processed_at=?,updated_at=? WHERE tenant_id=? AND id=? AND deleted_at IS NULL AND parse_status='finalizing' AND pending_subtasks_count=0",now(),now(),job.tenantId(),id);});
  }
  public boolean handoff(TaskRecord job,DocumentProcessingRepository.Snapshot snapshot,KnowledgeFileUpload.Enqueuer tasks) {
    return Boolean.TRUE.equals(tx.execute(s->{fence(job);if(superseded(job))return false;String id=snapshot.knowledge().path("id").asText();var current=knowledge.get(job.tenantId(),id);if(current.isEmpty())return false;for(String f:List.of("file_path","file_hash","file_name","file_type"))if(!current.get().path(f).equals(snapshot.knowledge().path(f)))return false;
      int changed=jdbc.update("UPDATE knowledges SET parse_status='finalizing',summary_status='pending',pending_subtasks_count=1,error_message='',updated_at=? WHERE tenant_id=? AND id=? AND knowledge_base_id=? AND deleted_at IS NULL AND parse_status='processing'",now(),job.tenantId(),id,snapshot.knowledgeBase().path("id").asText());if(changed==0)return false;
      ObjectNode p=(ObjectNode)job.payload().deepCopy();try{tasks.enqueue(job.tenantId(),"summary:generation",p,new KnowledgeFileUpload.TaskOptions("summary",3,Duration.ofMinutes(30)));}catch(Exception e){throw new IllegalStateException("summary handoff enqueue failed",e);}fence(job);return true;}));
  }
  private void fence(TaskRecord job) {var rows=jdbc.queryForList("SELECT status,attempts,lease_owner,lease_until,cancel_requested FROM java_task_jobs WHERE tenant_id=? AND id=?"+(postgres?" FOR UPDATE":""),job.tenantId(),job.id());if(rows.isEmpty())throw new CancellationException("summary job unavailable");var r=rows.getFirst();if(!Objects.equals(r.get("status"),"running")||number(r.get("attempts"))!=job.attempts()||!Objects.equals(r.get("lease_owner"),job.leaseOwner())||number(r.get("lease_until"))<=clock.millis()||bool(r.get("cancel_requested")))throw new CancellationException("summary job lease lost or canceled");}
  private String encode(JsonNode node){return node==null||node.isNull()?null:node.toString();}
  private Object now(){return postgres?Timestamp.from(clock.instant()):GoSqliteTimestamp.write(clock.instant());}
  private static boolean bool(Object v){return Boolean.TRUE.equals(v)||number(v)!=0;}
  private static long number(Object v){return v instanceof Number n?n.longValue():0;}
  static final class Stale extends RuntimeException {}
}
