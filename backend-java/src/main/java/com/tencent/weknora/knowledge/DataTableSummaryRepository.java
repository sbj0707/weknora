package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.weknora.retrieval.IndexEntry;
import com.tencent.weknora.retrieval.IndexWriter;
import com.tencent.weknora.task.TaskRecord;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Go table-summary checkpoints: create, index, status, conditional best-effort cleanup. */
public final class DataTableSummaryRepository {
  public record Snapshot(ObjectNode knowledge, ObjectNode knowledgeBase, Map<String, Object> tenant,
                         Object updatedAt) {}
  private final JdbcTemplate jdbc;
  private final KnowledgeRepository knowledge;
  private final KnowledgeBaseCodec codec;
  private final TransactionTemplate tx;
  private final Clock clock;
  private Boolean postgres;

  public DataTableSummaryRepository(JdbcTemplate jdbc, ObjectMapper json, KnowledgeRepository knowledge,
      PlatformTransactionManager manager, Clock clock) throws java.io.IOException {
    this.jdbc=jdbc; this.knowledge=knowledge; codec=new KnowledgeBaseCodec(json);
    tx=new TransactionTemplate(manager); this.clock=clock;
  }

  public Optional<Snapshot> load(TaskRecord job) {
    JsonNode payload=job.payload(); long owner=payload.path("tenant_id").asLong();
    String id=payload.path("knowledge_id").asText();
    if(owner<=0 || owner!=job.tenantId() || id.isEmpty())
      throw new IllegalArgumentException("invalid table summary knowledge scope");
    var stored=knowledge.get(owner,id);
    if(stored.isEmpty()) return Optional.empty();
    ObjectNode row=stored.get();
    var bases=jdbc.queryForList("SELECT * FROM knowledge_bases WHERE id=? AND deleted_at IS NULL",
        row.path("knowledge_base_id").asText());
    if(bases.isEmpty()) throw new IllegalArgumentException("knowledge base not found");
    ObjectNode base=codec.fromRow(bases.getFirst());
    if(base.path("tenant_id").asLong()!=owner)
      throw new IllegalArgumentException("knowledge does not belong to its knowledge base");
    String type=row.path("file_type").asText().toLowerCase(Locale.ROOT);
    if(!Set.of("csv","xlsx","xls").contains(type))
      throw new IllegalArgumentException("unsupported file type: "+type);
    var tenants=jdbc.queryForList("SELECT * FROM tenants WHERE id=? AND deleted_at IS NULL",owner);
    if(tenants.isEmpty()) throw new IllegalArgumentException("tenant not found");
    Object updated=jdbc.queryForMap("SELECT updated_at FROM knowledges WHERE tenant_id=? AND id=?",
        owner,id).get("updated_at");
    return Optional.of(new Snapshot(row,base,tenants.getFirst(),updated));
  }

  public void create(TaskRecord job, Snapshot state, List<DocumentChunks.Stored> chunks) {
    tx.executeWithoutResult(status->{
      fence(job); requireOwner(state);
      long sequence=postgres()?0:jdbc.queryForObject("SELECT COALESCE(MAX(seq_id),0) FROM chunks",Long.class);
      for(var c:chunks) {
        var columns=new ArrayList<>(List.of("id","tenant_id","knowledge_base_id","knowledge_id",
            "content","context_header","chunk_index","is_enabled","start_at","end_at",
            "pre_chunk_id","next_chunk_id","chunk_type","parent_chunk_id","status","index_status",
            "created_at","updated_at"));
        var values=new ArrayList<Object>(List.of(c.id(),job.tenantId(),state.knowledgeBase().path("id").asText(),
            state.knowledge().path("id").asText(),c.content(),c.contextHeader(),c.index(),true,
            c.start(),c.end(),c.previousId(),c.nextId(),c.type(),c.parentId(),1,"ready",
            timestamp(),timestamp()));
        if(!postgres()) {columns.add("seq_id");values.add(++sequence);}
        jdbc.update("INSERT INTO chunks ("+String.join(",",columns)+") VALUES ("
            +String.join(",",Collections.nCopies(values.size(),"?"))+")",values.toArray());
      }
      fence(job);
    });
  }

  public void index(TaskRecord job, List<IndexEntry> entries, Map<String,float[]> vectors,
      IndexWriter writer) {
    tx.executeWithoutResult(status->{fence(job);
      jdbc.execute((ConnectionCallback<Void>) c->{writer.batchSave(c,entries,vectors);return null;});
      fence(job);
    });
  }

  public void indexed(TaskRecord job,List<DocumentChunks.Stored> chunks) {
    tx.executeWithoutResult(status->{fence(job);
      for(var c:chunks) jdbc.update("UPDATE chunks SET status=2,updated_at=? WHERE tenant_id=? AND id=? AND deleted_at IS NULL",
          timestamp(),job.tenantId(),c.id());
      fence(job);
    });
  }

  /** CAS failure stops cleanup, preserving a concurrently changed document and its new records. */
  public void cleanup(TaskRecord job, Snapshot state, List<DocumentChunks.Stored> chunks,
      IndexWriter writer, String message) {
    boolean current=Boolean.TRUE.equals(tx.execute(status->{
      fence(job);
      return jdbc.update("UPDATE knowledges SET parse_status='failed',error_message=?,updated_at=?"
          +" WHERE tenant_id=? AND id=? AND knowledge_base_id=? AND parse_status=? AND updated_at=?"
          +" AND deleted_at IS NULL",message,timestamp(),job.tenantId(),state.knowledge().path("id").asText(),
          state.knowledgeBase().path("id").asText(),state.knowledge().path("parse_status").asText(),state.updatedAt())==1;
    }));
    if(!current)return;
    // Go's cleanup steps are independent; failure of soft-delete must not suppress index cleanup.
    try {tx.executeWithoutResult(status->{fence(job);
      for(var c:chunks)jdbc.update("UPDATE chunks SET deleted_at=? WHERE tenant_id=? AND id=? AND deleted_at IS NULL",
          timestamp(),job.tenantId(),c.id());
    });} catch(CancellationException e){throw e;} catch(RuntimeException e){warn("chunk");}
    try {
      if(writer instanceof com.tencent.weknora.retrieval.SQLiteIndexRepository sqlite) {
        fence(job);
        sqlite.cleanupBySource(chunks.stream().map(DocumentChunks.Stored::id).toList(),
            connection->fence(job,new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection,true))));
      } else tx.executeWithoutResult(status->{fence(job);
        jdbc.execute((ConnectionCallback<Void>) c->{writer.deleteBySource(c,chunks.stream().map(DocumentChunks.Stored::id).toList());return null;});
      });
    } catch(CancellationException e){throw e;} catch(Exception e){warn("index");}
  }

  private void requireOwner(Snapshot state) {
    if(jdbc.queryForList("SELECT id FROM knowledge_bases WHERE id=? AND tenant_id=? AND deleted_at IS NULL",
        state.knowledgeBase().path("id").asText(),state.knowledge().path("tenant_id").asLong()).isEmpty())
      throw new CancellationException("table summary KB owner changed");
    if(knowledge.get(state.knowledge().path("tenant_id").asLong(),state.knowledge().path("id").asText())
        .filter(k->k.path("knowledge_base_id").equals(state.knowledgeBase().path("id"))).isEmpty())
      throw new CancellationException("table summary document deleted or moved");
  }
  private void fence(TaskRecord job) {
    fence(job,jdbc);
  }
  private void fence(TaskRecord job,JdbcTemplate jdbc) {
    var rows=jdbc.queryForList("SELECT status,attempts,lease_owner,lease_until,cancel_requested FROM java_task_jobs"
        +" WHERE tenant_id=? AND id=?"+(postgres()?" FOR UPDATE":""),job.tenantId(),job.id());
    if(rows.isEmpty())throw new CancellationException("table summary job unavailable");
    var row=rows.getFirst();
    if(!"running".equals(row.get("status")) || ((Number)row.get("attempts")).intValue()!=job.attempts()
        || !Objects.equals(row.get("lease_owner"),job.leaseOwner()) || row.get("lease_until")==null
        || ((Number)row.get("lease_until")).longValue()<=clock.millis()
        || Boolean.TRUE.equals(row.get("cancel_requested")) || "1".equals(Objects.toString(row.get("cancel_requested"))))
      throw new CancellationException("table summary job lease lost or canceled");
  }
  private boolean postgres(){if(postgres==null)postgres=jdbc.execute((ConnectionCallback<Boolean>)c->c.getMetaData().getDatabaseProductName().equals("PostgreSQL"));return Boolean.TRUE.equals(postgres);}
  private Object timestamp(){return postgres()?Timestamp.from(clock.instant()):GoSqliteTimestamp.write(clock.instant());}
  private static void warn(String step){org.slf4j.LoggerFactory.getLogger(DataTableSummaryRepository.class).warn("Table summary {} cleanup failed",step);}
}
