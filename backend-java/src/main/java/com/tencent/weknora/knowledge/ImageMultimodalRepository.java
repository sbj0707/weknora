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

/**
 * Existing Go rows; native image fan-in uses explicit task-schema tables, never constructor DDL.
 */
public final class ImageMultimodalRepository {
  record Source(ObjectNode knowledge, ObjectNode base, ObjectNode parent) {}

  record Stored(String id, String type, String content) {}

  static final class Changed extends CancellationException {
    Changed() {
      super("image multimodal source changed");
    }
  }

  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final KnowledgeRepository knowledge;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final boolean postgres;

  public ImageMultimodalRepository(
      JdbcTemplate jdbc,
      ObjectMapper json,
      KnowledgeRepository knowledge,
      PlatformTransactionManager manager,
      Clock clock) {
    this.jdbc = jdbc;
    this.json = json;
    this.knowledge = knowledge;
    this.clock = clock;
    tx = new TransactionTemplate(manager);
    postgres =
        Boolean.TRUE.equals(
            jdbc.execute(
                (ConnectionCallback<Boolean>)
                    c -> c.getMetaData().getDatabaseProductName().equals("PostgreSQL")));
  }

  String raw(TaskRecord job) {
    var rows =
        jdbc.queryForList(
            "SELECT payload FROM java_task_jobs WHERE id=? AND tenant_id=?",
            job.id(),
            job.tenantId());
    return rows.isEmpty()
        ? job.payload().toString()
        : Objects.toString(rows.getFirst().get("payload"), "null");
  }

  boolean superseded(TaskRecord job) {
    long attempt = job.payload().path("attempt").asLong();
    if (attempt <= 0) return false;
    Long n =
        jdbc.queryForObject(
            "SELECT COALESCE(MAX(attempt),0) FROM knowledge_processing_spans WHERE knowledge_id=?",
            Long.class,
            doc(job));
    return n != null && n > attempt;
  }

  Source load(TaskRecord job) throws TaskFailure {
    if (job.tenantId() <= 0 || job.payload().path("tenant_id").asLong() != job.tenantId())
      throw new TaskFailure(
          "image multimodal document binding mismatch: skip retry for the task", false);
    var owners =
        jdbc.queryForList(
            "SELECT tenant_id,knowledge_base_id FROM knowledges WHERE id=? AND deleted_at IS NULL",
            doc(job));
    if (owners.isEmpty()) return null;
    var binding = owners.getFirst();
    if (number(binding.get("tenant_id")) != job.tenantId()
        || !Objects.equals(binding.get("knowledge_base_id"), kb(job)))
      throw new TaskFailure(
          "image multimodal document binding mismatch: skip retry for the task", false);
    var row = knowledge.get(job.tenantId(), doc(job)).orElseThrow();
    var bases =
        jdbc.queryForList(
            "SELECT tenant_id FROM knowledge_bases WHERE id=? AND deleted_at IS NULL", kb(job));
    if (bases.isEmpty()) return null;
    if (number(bases.getFirst().get("tenant_id")) != job.tenantId())
      throw new TaskFailure(
          "image multimodal knowledge base binding mismatch: skip retry for the task", false);
    var base = knowledge.knowledgeBase(job.tenantId(), kb(job));
    if (Set.of("cancelled", "deleting").contains(row.path("parse_status").asText()))
      return new Source(row, base, null);
    String imageUrl = job.payload().path("image_url").asText();
    if (!imageUrl.startsWith("http://")
        && !imageUrl.startsWith("https://")
        && !imageUrl.startsWith("resource://")) {
      long imageOwner = com.tencent.weknora.storage.FileUrlSigner.tenantFromPath(imageUrl);
      if (imageOwner != 0 && imageOwner != job.tenantId())
        throw new TaskFailure(
            "image multimodal image resource binding mismatch: skip retry for the task", false);
    }
    ObjectNode parent = null;
    if (!job.payload().path("chunk_id").asText().isEmpty()) {
      parent = parent(job);
      if (parent == null || !bound(job, parent))
        throw new TaskFailure(
            "image multimodal parent chunk binding mismatch: skip retry for the task", false);
    }
    return new Source(row, base, parent);
  }

  private ObjectNode parent(TaskRecord job) {
    var rows =
        jdbc.queryForList(
            "SELECT"
                + " id,tenant_id,knowledge_id,knowledge_base_id,content,context_header,content_revision,source_locators"
                + " FROM chunks WHERE id=? AND deleted_at IS NULL",
            job.payload().path("chunk_id").asText());
    if (rows.isEmpty()) return null;
    var row = json.<ObjectNode>valueToTree(rows.getFirst());
    Object locators = rows.getFirst().get("source_locators");
    if (locators != null)
      try {
        row.set(
            "source_locators",
            json.readTree(
                locators instanceof byte[] b
                    ? new String(b, java.nio.charset.StandardCharsets.UTF_8)
                    : locators.toString()));
      } catch (java.io.IOException ignored) {
      }
    return row;
  }

  private boolean bound(TaskRecord job, ObjectNode parent) {
    return parent.path("tenant_id").asLong() == job.tenantId()
        && parent.path("knowledge_id").asText().equals(doc(job))
        && parent.path("knowledge_base_id").asText().equals(kb(job));
  }

  void check(TaskRecord job, Source original) {
    tx.executeWithoutResult(
        status -> {
          fence(job);
          current(job, original);
        });
  }

  private void current(TaskRecord job, Source source) {
    if (source == null || superseded(job)) throw new Changed();
    var row = knowledge.get(job.tenantId(), doc(job));
    if (row.isEmpty()) throw new Changed();
    for (String f : List.of("type", "title", "file_name", "metadata", "knowledge_base_id"))
      if (!Objects.equals(source.knowledge().get(f), row.get().get(f))) throw new Changed();
    if (Set.of("cancelled", "deleting").contains(row.get().path("parse_status").asText()))
      throw new Changed();
    var base =
        jdbc.queryForList(
            "SELECT tenant_id FROM knowledge_bases WHERE id=? AND deleted_at IS NULL", kb(job));
    if (base.isEmpty() || number(base.getFirst().get("tenant_id")) != job.tenantId())
      throw new Changed();
    if (source.parent() != null) {
      var p = parent(job);
      if (p == null || !bound(job, p)) throw new Changed();
      for (String f : List.of("content", "context_header", "source_locators", "content_revision"))
        if (!Objects.equals(p.get(f), source.parent().get(f))) throw new Changed();
    }
  }

  List<Stored> create(TaskRecord job, Source source, ImageMultimodalText.Result result) {
    var stored =
        result.children().stream()
            .map(c -> new Stored(UUID.randomUUID().toString(), c.type(), c.content()))
            .toList();
    tx.executeWithoutResult(
        status -> {
          fence(job);
          current(job, source);
          String info = json.createArrayNode().add(result.imageInfo()).toString();
          String locators =
              job.payload().path("source_locators").isArray()
                      && !job.payload().path("source_locators").isEmpty()
                  ? job.payload().get("source_locators").toString()
                  : null;
          for (var c : stored) {
            String seq = postgres ? "" : "seq_id,",
                seqValue = postgres ? "" : "(SELECT COALESCE(MAX(seq_id),0)+1 FROM chunks),";
            jdbc.update(
                "INSERT INTO chunks("
                    + seq
                    + "id,tenant_id,knowledge_id,knowledge_base_id,content,chunk_type,parent_chunk_id,is_enabled,flags,status,image_info,source_locators,created_at,updated_at,chunk_index,start_at,end_at)"
                    + " VALUES("
                    + seqValue
                    + "?,?,?,?,?,?,?,true,1,0,?,"
                    + (postgres ? "CAST(? AS JSONB)" : "?")
                    + ",?,?,0,0,0)",
                c.id(),
                job.tenantId(),
                doc(job),
                kb(job),
                c.content(),
                c.type(),
                job.payload().path("chunk_id").asText(),
                info,
                locators,
                time(),
                time());
          }
          fence(job);
        });
    return stored;
  }

  void index(
      TaskRecord job,
      Source source,
      IndexWriter writer,
      List<IndexEntry> entries,
      Map<String, float[]> vectors) {
    tx.executeWithoutResult(
        status -> {
          fence(job);
          current(job, source);
          jdbc.execute(
              (ConnectionCallback<Void>)
                  connection -> {
                    writer.batchSave(connection, entries, vectors);
                    return null;
                  });
          indexed(job, entries.stream().map(IndexEntry::sourceId).toList());
          fence(job);
        });
  }

  void indexedWithoutEngine(TaskRecord job, Source source, List<Stored> children) {
    tx.executeWithoutResult(
        status -> {
          fence(job);
          current(job, source);
          indexed(job, children.stream().map(Stored::id).toList());
        });
  }

  private void indexed(TaskRecord job, List<String> ids) {
    for (String id : ids)
      jdbc.update(
          "UPDATE chunks SET status=2,updated_at=? WHERE id=? AND tenant_id=? AND knowledge_id=?"
              + " AND knowledge_base_id=? AND deleted_at IS NULL",
          time(),
          id,
          job.tenantId(),
          doc(job),
          kb(job));
  }

  /** Seed before admissions; failed image admissions release their unowned slots like Go. */
  void seed(
      TaskRecord parent,
      DocumentProcessingRepository.Snapshot source,
      List<ObjectNode> payloads,
      KnowledgeFileUpload.Enqueuer tasks)
      throws Exception {
    try {
      tx.executeWithoutResult(
          status -> {
            fence(parent);
            ObjectNode row =
                knowledge
                    .get(parent.tenantId(), source.knowledge().path("id").asText())
                    .orElseThrow(Changed::new);
            for (String f : List.of("metadata", "type", "title", "file_name", "knowledge_base_id"))
              if (!Objects.equals(source.knowledge().get(f), row.get(f))) throw new Changed();
            if (Set.of("cancelled", "deleting").contains(row.path("parse_status").asText()))
              throw new Changed();
            long attempt = parent.payload().path("attempt").asLong();
            jdbc.update(
                "INSERT INTO java_image_task_pending(tenant_id,knowledge_id,attempt,pending_count)"
                    + " VALUES(?,?,?,?) ON CONFLICT(tenant_id,knowledge_id) DO UPDATE SET"
                    + " attempt=excluded.attempt,pending_count=excluded.pending_count,handoff_done=false",
                parent.tenantId(),
                source.knowledge().path("id").asText(),
                attempt,
                payloads.size());
            var nested = new TransactionTemplate(tx.getTransactionManager());
            nested.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_NESTED);
            int accepted = 0;
            for (var p : payloads) {
              fence(parent);
              try {
                nested.executeWithoutResult(
                    inner -> {
                      try {
                        tasks.enqueue(
                            parent.tenantId(),
                            "image:multimodal",
                            p,
                            new KnowledgeFileUpload.TaskOptions(
                                "multimodal", 3, Duration.ofMinutes(30)));
                      } catch (Exception e) {
                        throw new QueueFailure(e);
                      }
                    });
                accepted++;
              } catch (QueueFailure ignored) {
                /* Go continues siblings and releases slots without a queue owner. */
              }
            }
            jdbc.update(
                "UPDATE java_image_task_pending SET pending_count=? WHERE tenant_id=? AND"
                    + " knowledge_id=? AND attempt=?",
                accepted,
                parent.tenantId(),
                source.knowledge().path("id").asText(),
                attempt);
            if (accepted == 0) {
              var post = postPayload(parent);
              Exception last = null;
              for (int retry = 0; retry < 3; retry++)
                try {
                  nested.executeWithoutResult(
                      inner -> {
                        try {
                          tasks.enqueue(
                              parent.tenantId(),
                              "knowledge:post_process",
                              post,
                              new KnowledgeFileUpload.TaskOptions(
                                  "postprocess", 3, Duration.ofMinutes(30)));
                        } catch (Exception e) {
                          throw new QueueFailure(e);
                        }
                      });
                  last = null;
                  break;
                } catch (QueueFailure failure) {
                  last = failure.original;
                }
              if (last != null)
                throw new QueueFailure(
                    new TaskFailure(
                        "enqueue knowledge:post_process after 3 attempts: " + last.getMessage(),
                        true));
              jdbc.update(
                  "UPDATE java_image_task_pending SET handoff_done=true WHERE tenant_id=? AND"
                      + " knowledge_id=? AND attempt=?",
                  parent.tenantId(),
                  source.knowledge().path("id").asText(),
                  attempt);
            }
            fence(parent);
          });
    } catch (QueueFailure e) {
      throw e.original;
    }
  }

  private ObjectNode postPayload(TaskRecord job) {
    ObjectNode post =
        json.createObjectNode()
            .put("tenant_id", job.tenantId())
            .put("knowledge_id", doc(job))
            .put("knowledge_base_id", kb(job));
    for (String key :
        List.of(
            "language",
            "attempt",
            "lf_trace_id",
            "lf_parent_obs_id",
            "lf_traceparent",
            "lf_user_id",
            "lf_session_id"))
      if (job.payload().hasNonNull(key) && !job.payload().path(key).asText().isEmpty())
        post.set(key, job.payload().get(key));
    if (!post.has("language")) post.put("language", "zh-CN");
    return post;
  }

  /** Consume once before queue handoff, as Go decrements before enqueue. Retry a failed handoff. */
  void drain(TaskRecord job, KnowledgeFileUpload.Enqueuer tasks) throws Exception {
    Boolean ready =
        tx.execute(
            status -> {
              fence(job);
              if (superseded(job)) return false;
              var rows =
                  jdbc.queryForList(
                      "SELECT tenant_id,knowledge_base_id FROM knowledges WHERE id=? AND deleted_at"
                          + " IS NULL",
                      doc(job));
              if (!rows.isEmpty()
                  && (number(rows.getFirst().get("tenant_id")) != job.tenantId()
                      || !Objects.equals(rows.getFirst().get("knowledge_base_id"), kb(job))))
                return false;
              long attempt = job.payload().path("attempt").asLong();
              var slots =
                  jdbc.queryForList(
                      "SELECT attempt,pending_count,handoff_done FROM java_image_task_pending WHERE"
                          + " tenant_id=? AND knowledge_id=?"
                          + (postgres ? " FOR UPDATE" : ""),
                      job.tenantId(),
                      doc(job));
              if (!slots.isEmpty()
                  && (number(slots.getFirst().get("attempt")) != attempt
                      || bool(slots.getFirst().get("handoff_done")))) return false;
              int receipt =
                  jdbc.update(
                      "INSERT INTO java_image_task_drains(task_id,tenant_id,knowledge_id,attempt)"
                          + " VALUES(?,?,?,?) ON CONFLICT(task_id) DO NOTHING",
                      job.id(),
                      job.tenantId(),
                      doc(job),
                      attempt);
              long pending = slots.isEmpty() ? 0 : number(slots.getFirst().get("pending_count"));
              if (receipt != 0) pending--;
              jdbc.update(
                  "INSERT INTO"
                      + " java_image_task_pending(tenant_id,knowledge_id,attempt,pending_count,handoff_done)"
                      + " VALUES(?,?,?,?,false) ON CONFLICT(tenant_id,knowledge_id) DO UPDATE SET"
                      + " pending_count=excluded.pending_count",
                  job.tenantId(),
                  doc(job),
                  attempt,
                  pending);
              return pending <= 0;
            });
    if (!Boolean.TRUE.equals(ready)) return;
    Exception last = null;
    for (int retry = 0; retry < 3; retry++)
      try {
        tx.executeWithoutResult(
            status -> {
              fence(job);
              if (superseded(job)) return;
              long attempt = job.payload().path("attempt").asLong();
              var slots =
                  jdbc.queryForList(
                      "SELECT attempt,handoff_done FROM java_image_task_pending WHERE tenant_id=?"
                          + " AND knowledge_id=?"
                          + (postgres ? " FOR UPDATE" : ""),
                      job.tenantId(),
                      doc(job));
              if (slots.isEmpty()
                  || number(slots.getFirst().get("attempt")) != attempt
                  || bool(slots.getFirst().get("handoff_done"))) return;
              ObjectNode post =
                  json.createObjectNode()
                      .put("tenant_id", job.tenantId())
                      .put("knowledge_id", doc(job))
                      .put("knowledge_base_id", kb(job));
              for (String key :
                  List.of(
                      "language",
                      "attempt",
                      "lf_trace_id",
                      "lf_parent_obs_id",
                      "lf_traceparent",
                      "lf_user_id",
                      "lf_session_id"))
                if (job.payload().hasNonNull(key) && !job.payload().path(key).asText().isEmpty())
                  post.set(key, job.payload().get(key));
              try {
                tasks.enqueue(
                    job.tenantId(),
                    "knowledge:post_process",
                    post,
                    new KnowledgeFileUpload.TaskOptions("postprocess", 3, Duration.ofMinutes(30)));
              } catch (Exception error) {
                throw new QueueFailure(error);
              }
              jdbc.update(
                  "UPDATE java_image_task_pending SET pending_count=0,handoff_done=true WHERE"
                      + " tenant_id=? AND knowledge_id=? AND attempt=?",
                  job.tenantId(),
                  doc(job),
                  attempt);
              fence(job);
            });
        return;
      } catch (QueueFailure error) {
        last = error.original;
      }
    throw new TaskFailure(
        "enqueue knowledge:post_process after 3 attempts: " + last.getMessage(), true);
  }

  private static boolean bool(Object value) {
    return Boolean.TRUE.equals(value) || number(value) != 0;
  }

  void fence(TaskRecord job) {
    var rows =
        jdbc.queryForList(
            "SELECT status,attempts,lease_owner,lease_until,cancel_requested FROM java_task_jobs"
                + " WHERE tenant_id=? AND id=?",
            job.tenantId(),
            job.id());
    if (rows.isEmpty()) throw new CancellationException("image job lease lost or canceled");
    var row = rows.getFirst();
    if (!Objects.equals(row.get("status"), "running")
        || number(row.get("attempts")) != job.attempts()
        || !Objects.equals(row.get("lease_owner"), job.leaseOwner())
        || number(row.get("lease_until")) <= clock.millis()
        || Boolean.TRUE.equals(row.get("cancel_requested"))
        || number(row.get("cancel_requested")) != 0)
      throw new CancellationException("image job lease lost or canceled");
  }

  private static String doc(TaskRecord j) {
    return j.payload().path("knowledge_id").asText();
  }

  private static String kb(TaskRecord j) {
    return j.payload().path("knowledge_base_id").asText();
  }

  private static long number(Object v) {
    return v instanceof Number n ? n.longValue() : 0;
  }

  private Object time() {
    return postgres ? Timestamp.from(clock.instant()) : clock.instant().toString();
  }

  private static final class QueueFailure extends RuntimeException {
    final Exception original;

    QueueFailure(Exception e) {
      super(e);
      original = e;
    }
  }
}
