package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.model.client.CallContext;
import com.tencent.weknora.task.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Native queue adapter; the deleted KB is intentionally never looked up as a live resource. */
public final class KnowledgeBaseDeleteHandler implements TaskHandler {
  private static final ScheduledExecutorService INTERRUPTS = Executors.newScheduledThreadPool(1,
      Thread.ofPlatform().daemon(true).name("kb-delete-interrupt-", 0).factory());
  private final KnowledgeBaseDeleteProcessor.Repository repository;
  private final KnowledgeBaseDeleteProcessor.Effects effects;
  private final ObjectMapper json;
  private final KnowledgeBaseDeleteSql sql;

  public KnowledgeBaseDeleteHandler(KnowledgeBaseDeleteProcessor.Repository repository,
      KnowledgeBaseDeleteProcessor.Effects effects, ObjectMapper json, JdbcTemplate jdbc) {
    this.repository = repository; this.effects = effects; this.json = json; sql = new KnowledgeBaseDeleteSql(jdbc);
  }
  public String type() { return "kb:delete"; }
  public Duration timeout() { return Duration.ofHours(2); }

  @Override public JsonNode execute(TaskRecord job, TaskCancellation cancellation)throws Exception {
    return execute(job, cancellation::check);
  }

  JsonNode execute(TaskRecord job, Runnable guard)throws Exception {
    try (var call = CallContext.timeout(job.timeoutMillis() > 0 ? Duration.ofMillis(job.timeoutMillis()) : timeout())) {
      Thread owner = Thread.currentThread();
      var interrupt = INTERRUPTS.scheduleWithFixedDelay(() -> { if (owner.isInterrupted()) call.cancel(); }, 0, 10, TimeUnit.MILLISECONDS);
      try {
        guard.run();
        var persisted = sql.query(call, "SELECT payload FROM java_task_jobs WHERE tenant_id=? AND id=?", job.tenantId(), job.id());
        String raw = persisted.isEmpty() ? job.payload().toString() : Objects.toString(persisted.getFirst().get("payload"), "null");
        final KnowledgeBaseDeleteProcessor.Payload payload;
        try { payload = KnowledgeBaseDeletePayload.decode(raw, json); }
        catch (java.io.IOException invalid) { throw new TaskFailure(invalid.getMessage(), true); }
        // SQL transport adds an authoritative owner column absent from Asynq. Do not allow a
        // corrupted queue payload to switch the execution tenant; this is not a live-KB check.
        if (payload.tenantId() != job.tenantId()) throw new TaskFailure("KB deletion payload owner does not match queue owner", false);
        var fencedRepository = new KnowledgeBaseDeleteProcessor.Repository() {
          public List<KnowledgeBaseDeleteProcessor.Document> documents(KnowledgeBaseDeleteProcessor.Payload p, CallContext c)throws Exception { guard.run(); return repository.documents(p,c); }
          public List<String> images(KnowledgeBaseDeleteProcessor.Payload p,List<String> ids,CallContext c)throws Exception { guard.run(); return repository.images(p,ids,c); }
          public void chunks(KnowledgeBaseDeleteProcessor.Payload p,String id,CallContext c)throws Exception { guard.run(); repository.chunks(p,id,c); }
          public void accounting(KnowledgeBaseDeleteProcessor.Payload p,long delta,CallContext c)throws Exception { guard.run(); repository.accounting(p,delta,c); }
          public void documentsDeleted(KnowledgeBaseDeleteProcessor.Payload p,List<String> ids,CallContext c)throws Exception { guard.run(); repository.documentsDeleted(p,ids,c); }
          // Go defers these cleanup passes under detached contexts, even after cancellation.
          public void wiki(KnowledgeBaseDeleteProcessor.Payload p,String table,CallContext c)throws Exception { repository.wiki(p,table,c); }
        };
        var fencedEffects = new KnowledgeBaseDeleteProcessor.Effects() {
          public KnowledgeBaseDeleteProcessor.Indexes resolve(KnowledgeBaseDeleteProcessor.Payload p,CallContext c)throws Exception {
            guard.run();var resolved=effects.resolve(p,c);
            return (ids,dimension,type,context)->{guard.run();resolved.delete(ids,dimension,type,context);};
          }
          public int dimensions(KnowledgeBaseDeleteProcessor.Payload p,String model,CallContext c)throws Exception { guard.run();return effects.dimensions(p,model,c); }
          public void file(KnowledgeBaseDeleteProcessor.Payload p,String reference,CallContext c)throws Exception { guard.run();effects.file(p,reference,c); }
          public boolean releaseImage(KnowledgeBaseDeleteProcessor.Payload p,String reference,List<String> ids,CallContext c)throws Exception { guard.run();return effects.releaseImage(p,reference,ids,c); }
          public void graph(KnowledgeBaseDeleteProcessor.Payload p,List<String> ids,CallContext c)throws Exception { guard.run();effects.graph(p,ids,c); }
          public void scrub(KnowledgeBaseDeleteProcessor.Payload p,List<String> ids,CallContext c)throws Exception { if(c==call)guard.run();effects.scrub(p,ids,c); }
        };
        try { new KnowledgeBaseDeleteProcessor(fencedRepository, fencedEffects).process(payload, call); }
        catch (KnowledgeBaseDeleteProcessor.Failure failure) { throw new TaskFailure(failure.getMessage(), failure.verdict() != KnowledgeBaseDeleteProcessor.Verdict.SKIP_RETRY); }
        return json.createObjectNode();
      } finally { interrupt.cancel(false); }
    }
  }
}
