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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Chunk/index/state writes share one owner-, source- and lease-fenced transaction. */
public final class DocumentProcessingRepository {
  public record Snapshot(
      ObjectNode knowledge, ObjectNode knowledgeBase, Map<String, Object> tenant) {}

  public record Replacement(List<String> chunkIds, List<String> indexSources) {}

  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final KnowledgeRepository knowledge;
  private final TransactionTemplate transactions;
  private final Clock clock;
  private Boolean postgres;

  public DocumentProcessingRepository(
      JdbcTemplate jdbc,
      ObjectMapper json,
      KnowledgeRepository knowledge,
      PlatformTransactionManager manager,
      Clock clock) {
    this.jdbc = jdbc;
    this.json = json;
    this.knowledge = knowledge;
    transactions = new TransactionTemplate(manager);
    this.clock = clock;
  }

  public Optional<Snapshot> load(TaskRecord job) throws TaskFailure {
    JsonNode payload = job.payload();
    long owner = payload.path("tenant_id").asLong();
    String id = payload.path("knowledge_id").asText(),
        kb = payload.path("knowledge_base_id").asText();
    if (owner <= 0 || owner != job.tenantId() || id.isEmpty() || kb.isEmpty())
      throw new TaskFailure("invalid processing task binding", false);
    var tenants =
        jdbc.queryForList("SELECT * FROM tenants WHERE id=? AND deleted_at IS NULL", owner);
    if (tenants.isEmpty()) throw new TaskFailure("processing tenant is unavailable", true);
    var stored = knowledge.get(owner, id);
    if (stored.isEmpty()) return Optional.empty();
    ObjectNode row = stored.get();
    if (!row.path("knowledge_base_id").asText().equals(kb))
      throw new TaskFailure("processing task knowledge binding changed", false);
    ObjectNode base = knowledge.knowledgeBase(owner, kb);
    if (base.path("tenant_id").asLong() != owner)
      throw new TaskFailure("processing task KB owner changed", false);
    return Optional.of(new Snapshot(row, base, tenants.getFirst()));
  }

  public boolean begin(TaskRecord job, Snapshot snapshot) {
    return Boolean.TRUE.equals(
        transactions.execute(
            status -> {
              fenceJob(job);
              if (!liveSource(job, snapshot)) return false;
              return jdbc.update(
                      "UPDATE knowledges SET"
                          + " parse_status='processing',error_message='',updated_at=? WHERE"
                          + " tenant_id=? AND id=? AND deleted_at IS NULL AND parse_status NOT IN"
                          + " ('cancelled','deleting','completed')",
                      timestamp(clock.instant()),
                      job.tenantId(),
                      snapshot.knowledge.path("id").asText())
                  == 1;
            }));
  }

  /** Go clears the old document before embedding; later table tasks must survive the commit. */
  public Replacement captureReplacement(TaskRecord job, Snapshot snapshot, IndexWriter writer) {
    return transactions.execute(status -> {
      fenceJob(job);
      if (!liveSource(job, snapshot))
        throw new CancellationException("processing source changed before embedding");
      String id = snapshot.knowledge.path("id").asText();
      List<String> chunks = jdbc.queryForList(
          "SELECT id FROM chunks WHERE tenant_id=? AND knowledge_id=?", String.class,
          job.tenantId(), id);
      List<String> sources = jdbc.execute((ConnectionCallback<List<String>>) connection ->
          writer.sourceIdsByKnowledge(connection, List.of(id)));
      return new Replacement(List.copyOf(chunks), List.copyOf(sources));
    });
  }

  public boolean save(
      TaskRecord job,
      Snapshot snapshot,
      Replacement replacement,
      List<DocumentChunks.Stored> chunks,
      IndexWriter writer,
      List<IndexEntry> entries,
      Map<String, float[]> embeddings,
      KnowledgeFileUpload.Enqueuer tasks)
      throws Exception {
    long storageBytes = entries.isEmpty() ? 0 : writer.estimateStorageSize(entries, embeddings);
    try {
      return Boolean.TRUE.equals(
          transactions.execute(
              status -> {
                fenceJob(job);
                if (!liveSource(job, snapshot)) return false;
                var tenant =
                    jdbc.queryForList(
                            "SELECT storage_quota,storage_used FROM tenants WHERE id=?"
                                + (isPostgres() ? " FOR UPDATE" : ""),
                            job.tenantId())
                        .getFirst();
                long quota = number(tenant.get("storage_quota")),
                    used = number(tenant.get("storage_used"));
                if (quota > 0 && storageBytes > quota - used) {
                  failCurrent(job, snapshot, "存储空间不足");
                  return false;
                }
                jdbc.execute(
                    (ConnectionCallback<Void>)
                        connection -> {
                          writer.deleteBySource(connection, replacement.indexSources());
                          for (String id : replacement.chunkIds())
                            jdbc.update(
                                "DELETE FROM chunks WHERE tenant_id=? AND knowledge_id=? AND id=?",
                                job.tenantId(), snapshot.knowledge.path("id").asText(), id);
                          for (var chunk : chunks) insert(job, snapshot, chunk);
                          if (!entries.isEmpty()) writer.batchSave(connection, entries, embeddings);
                          return null;
                        });
                fenceJob(job);
                if (!liveSource(job, snapshot))
                  throw new CancellationException("processing source changed during index write");
                Instant now = clock.instant();
                long oldSize =
                    knowledge
                        .get(job.tenantId(), snapshot.knowledge.path("id").asText())
                        .orElseThrow()
                        .path("storage_size")
                        .asLong();
                long textCount = chunks.stream().filter(c -> c.type().equals("text")).count();
                jdbc.update(
                    "UPDATE knowledges SET"
                        + " parse_status=?,summary_status='none',enable_status='enabled',storage_size=?,processed_at=?,updated_at=?,error_message=''"
                        + " WHERE tenant_id=? AND id=? AND deleted_at IS NULL AND"
                        + " parse_status='processing'",
                    textCount == 0 ? "completed" : "processing",
                    storageBytes,
                    timestamp(now),
                    timestamp(now),
                    job.tenantId(),
                    snapshot.knowledge.path("id").asText());
                ObjectNode payload =
                    json.createObjectNode()
                        .put("tenant_id", job.tenantId())
                        .put("knowledge_id", snapshot.knowledge.path("id").asText())
                        .put(
                            "knowledge_base_id",
                            snapshot.knowledge.path("knowledge_base_id").asText())
                        .put("language", job.payload().path("language").asText("zh-CN"));
                if (job.payload().path("attempt").asInt() > 0)
                  payload.put("attempt", job.payload().path("attempt").asInt());
                for (String key :
                    List.of(
                        "langfuse_trace_id",
                        "langfuse_observation_id",
                        "langfuse_session_id",
                        "langfuse_user_id"))
                  if (job.payload().hasNonNull(key) && !job.payload().path(key).asText().isEmpty())
                    payload.set(key, job.payload().get(key));
                try {
                  tasks.enqueue(
                      job.tenantId(),
                      "knowledge:post_process",
                      payload,
                      new KnowledgeFileUpload.TaskOptions(
                          "postprocess", 3, Duration.ofMinutes(30)));
                } catch (Exception error) {
                  throw new EnqueueFailure(error);
                }
                jdbc.update(
                    "UPDATE tenants SET storage_used=storage_used+? WHERE id=?",
                    storageBytes - oldSize,
                    job.tenantId());
                fenceJob(job);
                return true;
              }));
    } catch (EnqueueFailure error) {
      throw error.getCause();
    }
  }

  public void failed(TaskRecord job, Snapshot snapshot, String safeMessage) {
    transactions.executeWithoutResult(
        status -> {
          fenceJob(job);
          if (liveSource(job, snapshot)) failCurrent(job, snapshot, safeMessage);
        });
  }

  public boolean complete(TaskRecord job, Snapshot snapshot) {
    return Boolean.TRUE.equals(
        transactions.execute(
            status -> {
              fenceJob(job);
              if (!liveSource(job, snapshot)) return false;
              Instant now = clock.instant();
              return jdbc.update(
                      "UPDATE knowledges SET"
                          + " parse_status='completed',summary_status='none',pending_subtasks_count=0,error_message='',processed_at=?,updated_at=?"
                          + " WHERE tenant_id=? AND id=? AND knowledge_base_id=? AND"
                          + " parse_status='processing' AND deleted_at IS NULL",
                      timestamp(now),
                      timestamp(now),
                      job.tenantId(),
                      snapshot.knowledge.path("id").asText(),
                      snapshot.knowledge.path("knowledge_base_id").asText())
                  == 1;
            }));
  }

  private void insert(TaskRecord job, Snapshot snapshot, DocumentChunks.Stored chunk) {
    String locators;
    try {
      locators = chunk.locations().isEmpty() ? null : json.writeValueAsString(chunk.locations());
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
    jdbc.update(
        "INSERT INTO"
            + " chunks(id,tenant_id,knowledge_base_id,knowledge_id,content,context_header,chunk_index,is_enabled,start_at,end_at,pre_chunk_id,next_chunk_id,chunk_type,parent_chunk_id,source_locators,status,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,?,?,true,?,?,?,?,?,?,"
            + (isPostgres() ? "?::jsonb" : "?")
            + ",0,?,?)",
        chunk.id(),
        job.tenantId(),
        snapshot.knowledge.path("knowledge_base_id").asText(),
        snapshot.knowledge.path("id").asText(),
        chunk.content(),
        chunk.contextHeader(),
        chunk.index(),
        chunk.start(),
        chunk.end(),
        chunk.previousId(),
        chunk.nextId(),
        chunk.type(),
        chunk.parentId(),
        locators,
        timestamp(clock.instant()),
        timestamp(clock.instant()));
  }

  private boolean liveSource(TaskRecord job, Snapshot snapshot) {
    String id = snapshot.knowledge.path("id").asText();
    if (isPostgres())
      jdbc.queryForList(
          "SELECT id FROM knowledges WHERE tenant_id=? AND id=? FOR UPDATE", job.tenantId(), id);
    var current = knowledge.get(job.tenantId(), id);
    if (current.isEmpty()) return false;
    ObjectNode row = current.get();
    if (Set.of("cancelled", "deleting", "completed").contains(row.path("parse_status").asText()))
      return false;
    for (String field :
        List.of("knowledge_base_id", "file_path", "file_hash", "file_name", "file_type"))
      if (!row.path(field).equals(snapshot.knowledge.path(field))) return false;
    String path = job.payload().path("file_path").asText();
    if (!path.isEmpty()
        && !row.path("file_path").asText().isEmpty()
        && !path.equals(row.path("file_path").asText())) return false;
    int attempt = job.payload().path("attempt").asInt();
    if (attempt > 0) {
      Integer latest =
          jdbc.queryForObject(
              "SELECT COALESCE(MAX(attempt),0) FROM knowledge_processing_spans WHERE"
                  + " knowledge_id=?",
              Integer.class,
              id);
      if (latest != null && latest > attempt) return false;
    }
    return true;
  }

  private void fenceJob(TaskRecord job) {
    var rows =
        jdbc.queryForList(
            "SELECT attempts,lease_owner,lease_until,cancel_requested,status FROM java_task_jobs"
                + " WHERE id=? AND tenant_id=?"
                + (isPostgres() ? " FOR UPDATE" : ""),
            job.id(),
            job.tenantId());
    if (rows.isEmpty()) throw new CancellationException("processing job no longer exists");
    var row = rows.getFirst();
    if (!Objects.toString(row.get("status"), "").equals("running")
        || number(row.get("attempts")) != job.attempts()
        || !Objects.toString(row.get("lease_owner"), "").equals(job.leaseOwner())
        || number(row.get("lease_until")) <= clock.millis()
        || Boolean.TRUE.equals(row.get("cancel_requested"))
        || number(row.get("cancel_requested")) != 0)
      throw new CancellationException("processing job lease lost or canceled");
  }

  private void failCurrent(TaskRecord job, Snapshot snapshot, String message) {
    jdbc.update(
        "UPDATE knowledges SET parse_status='failed',error_message=?,updated_at=? WHERE tenant_id=?"
            + " AND id=? AND deleted_at IS NULL AND parse_status='processing'",
        message,
        timestamp(clock.instant()),
        job.tenantId(),
        snapshot.knowledge.path("id").asText());
  }

  private boolean isPostgres() {
    if (postgres == null)
      postgres =
          jdbc.execute(
              (ConnectionCallback<Boolean>)
                  c -> c.getMetaData().getDatabaseProductName().equals("PostgreSQL"));
    return Boolean.TRUE.equals(postgres);
  }

  private Object timestamp(Instant time) {
    return isPostgres() ? Timestamp.from(time) : time.toString();
  }

  private static long number(Object value) {
    return value instanceof Number n ? n.longValue() : 0;
  }

  private static final class EnqueueFailure extends RuntimeException {
    private final Exception original;

    EnqueueFailure(Exception original) {
      super(original);
      this.original = original;
    }

    @Override
    public synchronized Exception getCause() {
      return original;
    }
  }
}
