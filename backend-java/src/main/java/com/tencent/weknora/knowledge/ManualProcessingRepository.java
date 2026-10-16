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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Manual processing deliberately persists chunks before embedding, as the Go worker does. */
public final class ManualProcessingRepository {
  static final class SourceChanged extends CancellationException {
    SourceChanged() {
      super("manual knowledge source changed");
    }
  }

  private final JdbcTemplate jdbc;
  private final KnowledgeRepository knowledge;
  private final ObjectMapper json;
  private final TransactionTemplate tx;
  private final Clock clock;
  private Boolean postgres;

  public ManualProcessingRepository(
      JdbcTemplate jdbc,
      KnowledgeRepository knowledge,
      ObjectMapper json,
      PlatformTransactionManager manager,
      Clock clock) {
    this.jdbc = jdbc;
    this.knowledge = knowledge;
    this.json = json;
    this.tx = new TransactionTemplate(manager);
    this.clock = clock;
  }

  String rawPayload(TaskRecord job) {
    var rows =
        jdbc.queryForList(
            "SELECT payload FROM java_task_jobs WHERE id=? AND tenant_id=?",
            job.id(),
            job.tenantId());
    return rows.isEmpty()
        ? job.payload().toString()
        : Objects.toString(rows.getFirst().get("payload"), "null");
  }

  Map<String, Object> tenant(long owner) throws TaskFailure {
    var rows = jdbc.queryForList("SELECT * FROM tenants WHERE id=? AND deleted_at IS NULL", owner);
    if (rows.isEmpty())
      throw new TaskFailure(
          "get tenant " + Long.toUnsignedString(owner) + ": tenant not found", true);
    return rows.getFirst();
  }

  Optional<ObjectNode> row(long owner, String id) {
    var result = knowledge.get(owner, id);
    result.ifPresent(
        row -> {
          for (String key : List.of("storage_size", "file_size", "pending_subtasks_count"))
            row.put(key, row.path(key).asLong());
        });
    return result;
  }

  ObjectNode base(String id) {
    var owners =
        jdbc.queryForList(
            "SELECT tenant_id FROM knowledge_bases WHERE id=? AND deleted_at IS NULL", id);
    return owners.isEmpty()
        ? null
        : knowledge.knowledgeBase(((Number) owners.getFirst().get("tenant_id")).longValue(), id);
  }

  void begin(TaskRecord job, DocumentProcessingRepository.Snapshot source) {
    write(
        job,
        source,
        () ->
            jdbc.update(
                "UPDATE knowledges SET parse_status='processing',error_message='',updated_at=?"
                    + " WHERE tenant_id=? AND id=?",
                time(),
                job.tenantId(),
                id(source)));
  }

  void missingBase(TaskRecord job, String id) {
    tx.executeWithoutResult(
        status -> {
          fence(job);
          jdbc.update(
              "UPDATE knowledges SET parse_status='failed',error_message='failed to get knowledge"
                  + " base: knowledge base not found',updated_at=? WHERE tenant_id=? AND id=?",
              time(),
              job.tenantId(),
              id);
        });
  }

  void check(TaskRecord job, DocumentProcessingRepository.Snapshot source) {
    write(job, source, () -> {});
  }

  /**
   * A known owner may close its orphaned attempt after deletion, never another owner's document.
   */
  void closeRemovedTrace(TaskRecord job, DocumentProcessingRepository.Snapshot source) {
    tx.executeWithoutResult(
        status -> {
          fence(job);
          if (!jdbc.queryForList("SELECT id FROM knowledges WHERE id=?", id(source)).isEmpty())
            return;
          long attempt = job.payload().path("attempt").asLong();
          if (attempt <= 0) return;
          jdbc.update(
              "UPDATE knowledge_processing_spans SET"
                  + " status='cancelled',error_code='TASK_SUPERSEDED',error_message='manual"
                  + " knowledge source changed',finished_at=?,updated_at=? WHERE knowledge_id=? AND"
                  + " attempt=? AND status IN ('pending','running')",
              time(),
              time(),
              id(source),
              attempt);
        });
  }

  void fail(TaskRecord job, DocumentProcessingRepository.Snapshot source, String message) {
    write(
        job,
        source,
        () ->
            jdbc.update(
                "UPDATE knowledges SET parse_status='failed',error_message=?,updated_at=? WHERE"
                    + " tenant_id=? AND id=?",
                message,
                time(),
                job.tenantId(),
                id(source)));
  }

  List<String> imageInfos(TaskRecord job, DocumentProcessingRepository.Snapshot source) {
    check(job,source);
    return jdbc.queryForList("SELECT image_info FROM chunks WHERE tenant_id=? AND knowledge_id=? AND deleted_at IS NULL AND image_info IS NOT NULL AND image_info<>''",String.class,job.tenantId(),id(source));
  }

  void cleanup(
      TaskRecord job,
      DocumentProcessingRepository.Snapshot source,
      IndexWriter writer,
      boolean indexes,
      boolean storage) {
    write(
        job,
        source,
        () -> {
          jdbc.execute(
              (ConnectionCallback<Void>)
                  c -> {
                    if (indexes) writer.deleteByKnowledge(c, List.of(id(source)));
                    return null;
                  });
          jdbc.update(
              "UPDATE chunks SET deleted_at=?,updated_at=? WHERE tenant_id=? AND knowledge_id=? AND"
                  + " deleted_at IS NULL",
              time(),
              time(),
              job.tenantId(),
              id(source));
          if (storage) {
            long old = source.knowledge().path("storage_size").asLong();
            jdbc.update(
                "UPDATE tenants SET storage_used=CASE WHEN storage_used>? THEN storage_used-? ELSE"
                    + " 0 END WHERE id=?",
                old,
                old,
                job.tenantId());
            source.knowledge().put("storage_size", 0);
          }
        });
  }

  void chunks(
      TaskRecord job,
      DocumentProcessingRepository.Snapshot source,
      List<DocumentChunks.Stored> chunks) {
    write(
        job,
        source,
        () -> {
          for (var chunk : chunks) {
            var args =
                new ArrayList<Object>(
                    List.of(
                        chunk.id(),
                        job.tenantId(),
                        source.knowledge().path("knowledge_base_id").asText(),
                        id(source),
                        chunk.content(),
                        chunk.contextHeader(),
                        chunk.index(),
                        chunk.start(),
                        chunk.end(),
                        chunk.previousId(),
                        chunk.nextId(),
                        chunk.type(),
                        chunk.parentId(),
                        time(),
                        time()));
            String seq = postgres() ? "" : "seq_id,";
            String seqValue = postgres() ? "" : "(SELECT COALESCE(MAX(seq_id),0)+1 FROM chunks),";
            jdbc.update(
                "INSERT INTO chunks("
                    + seq
                    + "id,tenant_id,knowledge_base_id,knowledge_id,content,context_header,chunk_index,start_at,end_at,pre_chunk_id,next_chunk_id,chunk_type,parent_chunk_id,created_at,updated_at,is_enabled,status,tag_id)"
                    + " VALUES("
                    + seqValue
                    + "?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,true,0,'')",
                args.toArray());
          }
        });
  }

  boolean quota(TaskRecord job, DocumentProcessingRepository.Snapshot source, long bytes) {
    return Boolean.TRUE.equals(
        tx.execute(
            status -> {
              fence(job);
              current(job, source);
              var tenant =
                  jdbc.queryForList(
                          "SELECT storage_used,storage_quota FROM tenants WHERE id=?"
                              + (postgres() ? " FOR UPDATE" : ""),
                          job.tenantId())
                      .getFirst();
              long quota = number(tenant.get("storage_quota")),
                  used = number(tenant.get("storage_used"));
              if (quota > 0 && bytes > quota - used) {
                jdbc.update(
                    "UPDATE knowledges SET"
                        + " parse_status='failed',error_message='存储空间不足',updated_at=? WHERE"
                        + " tenant_id=? AND id=?",
                    time(),
                    job.tenantId(),
                    id(source));
                return false;
              }
              return true;
            }));
  }

  void indexes(
      TaskRecord job,
      DocumentProcessingRepository.Snapshot source,
      IndexWriter writer,
      List<IndexEntry> entries,
      Map<String, float[]> vectors) {
    write(
        job,
        source,
        () ->
            jdbc.execute(
                (ConnectionCallback<Void>)
                    c -> {
                      writer.batchSave(c, entries, vectors);
                      return null;
                    }));
  }

  void finalized(
      TaskRecord job, DocumentProcessingRepository.Snapshot source, long bytes, int text) {
    finalized(job,source,bytes,text,false);
  }

  void finalized(
      TaskRecord job, DocumentProcessingRepository.Snapshot source, long bytes, int text,boolean pendingMultimodal) {
    write(
        job,
        source,
        () ->
            jdbc.update(
                "UPDATE knowledges SET"
                    + " parse_status=?,summary_status='none',enable_status='enabled',storage_size=?,processed_at=?,updated_at=?,error_message=''"
                    + " WHERE tenant_id=? AND id=?",
                text == 0 && !pendingMultimodal ? "completed" : "processing",
                bytes,
                time(),
                time(),
                job.tenantId(),
                id(source)));
  }

  void account(TaskRecord job, DocumentProcessingRepository.Snapshot source, long bytes) {
    write(
        job,
        source,
        () ->
            jdbc.update(
                "UPDATE tenants SET storage_used=storage_used+? WHERE id=?",
                bytes,
                job.tenantId()));
  }

  private void write(TaskRecord job, DocumentProcessingRepository.Snapshot source, Runnable work) {
    tx.executeWithoutResult(
        status -> {
          fence(job);
          current(job, source);
          work.run();
          fence(job);
        });
  }

  private void current(TaskRecord job, DocumentProcessingRepository.Snapshot source) {
    var current = row(job.tenantId(), id(source));
    if (current.isEmpty()) throw new SourceChanged();
    var row = current.get();
    for (String field : List.of("metadata", "type", "title", "file_name", "knowledge_base_id"))
      if (!Objects.equals(row.get(field), source.knowledge().get(field))) throw new SourceChanged();
    if (Set.of("cancelled", "deleting").contains(row.path("parse_status").asText()))
      throw new SourceChanged();
    var base = base(source.knowledge().path("knowledge_base_id").asText());
    if (base == null || base.path("tenant_id").asLong() != job.tenantId())
      throw new SourceChanged();
    long attempt = job.payload().path("attempt").asLong();
    if (attempt > 0
        && jdbc.queryForObject(
                "SELECT COALESCE(MAX(attempt),0) FROM knowledge_processing_spans WHERE"
                    + " knowledge_id=?",
                Long.class,
                id(source))
            > attempt) throw new SourceChanged();
  }

  private void fence(TaskRecord job) {
    var rows =
        jdbc.queryForList(
            "SELECT attempts,lease_owner,lease_until,cancel_requested,status FROM java_task_jobs"
                + " WHERE id=? AND tenant_id=?"
                + (postgres() ? " FOR UPDATE" : ""),
            job.id(),
            job.tenantId());
    if (rows.isEmpty()) throw new CancellationException("manual job no longer exists");
    var r = rows.getFirst();
    if (!"running".equals(r.get("status"))
        || number(r.get("attempts")) != job.attempts()
        || !job.leaseOwner().equals(r.get("lease_owner"))
        || number(r.get("lease_until")) <= clock.millis()
        || Boolean.TRUE.equals(r.get("cancel_requested"))
        || number(r.get("cancel_requested")) != 0)
      throw new CancellationException("manual job lease lost or canceled");
  }

  private String id(DocumentProcessingRepository.Snapshot source) {
    return source.knowledge().path("id").asText();
  }

  private boolean postgres() {
    if (postgres == null)
      postgres =
          jdbc.execute(
              (ConnectionCallback<Boolean>)
                  c -> c.getMetaData().getDatabaseProductName().equals("PostgreSQL"));
    return Boolean.TRUE.equals(postgres);
  }

  private Object time() {
    return postgres() ? Timestamp.from(clock.instant()) : GoSqliteTimestamp.write(clock.instant());
  }

  private static long number(Object n) {
    return n instanceof Number value ? value.longValue() : 0;
  }
}
