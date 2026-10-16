package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.chunker.AdaptiveChunker;
import com.tencent.weknora.docreader.HtmlTableNormalizer;
import com.tencent.weknora.model.client.*;
import com.tencent.weknora.retrieval.*;
import com.tencent.weknora.task.*;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;

/** The manual worker owns its body revision and preserves Go's partial-write semantics. */
public final class ManualProcessingHandler implements TaskHandler {
  private final ManualProcessingRepository repository;
  private final ObjectMapper json;
  private final DocumentProcessingHandler.Embeddings models;
  private final DocumentProcessingHandler.Indexes indexes;
  private final KnowledgeFileUpload.Enqueuer tasks;
  private final KnowledgeProcessingSpanTracker tracker;
  private final GraphExtractionStore graph;
  private final int batchSize;
  private ManualProcessingImages images;
  private ManualEmbeddingPool embeddingPool;
  private ImageMultimodalFanout multimodal;
  public ManualProcessingHandler withMultimodal(ImageMultimodalFanout multimodal){this.multimodal=multimodal;return this;}
  public ManualProcessingHandler withEmbeddingPool(ManualEmbeddingPool pool){this.embeddingPool=pool;return this;}
  public ManualProcessingHandler withImages(ManualProcessingImages images){this.images=images;return this;}

  public ManualProcessingHandler(
      ManualProcessingRepository repository,
      ObjectMapper json,
      DocumentProcessingHandler.Embeddings models,
      DocumentProcessingHandler.Indexes indexes,
      KnowledgeFileUpload.Enqueuer tasks,
      KnowledgeProcessingSpanTracker tracker,
      GraphExtractionStore graph,
      int batchSize) {
    this.repository = repository;
    this.json = json;
    this.models = models;
    this.indexes = indexes;
    this.tasks = tasks;
    this.tracker = tracker;
    this.graph = graph;
    this.batchSize = batchSize;
  }

  @Override
  public String type() {
    return "manual:process";
  }

  @Override
  public Duration timeout() {
    return Duration.ofMinutes(30);
  }

  @Override
  public JsonNode execute(TaskRecord original, TaskCancellation cancellation) throws Exception {
    cancellation.check();
    ObjectNode payload;
    try {
      payload = decode(repository.rawPayload(original));
    } catch (IOException error) {
      return result("invalid_payload");
    }
    long owner = payload.path("tenant_id").asLong();
    var tenant = repository.tenant(owner);
    String id = payload.path("knowledge_id").asText(""),
        kbId = payload.path("knowledge_base_id").asText("");
    var stored = repository.row(owner, id);
    if (stored.isEmpty()) return result("knowledge_not_found");
    var row = stored.get();
    if (owner != original.tenantId() || !row.path("knowledge_base_id").asText().equals(kbId))
      throw new TaskFailure("processing task binding changed: skip retry for the task", false);
    if (Set.of("completed", "cancelled", "deleting").contains(row.path("parse_status").asText()))
      return result("knowledge_no_longer_current");
    if (!row.path("type").asText().equals("manual"))
      throw new TaskFailure("processing task manual type changed: skip retry for the task", false);
    String content = payload.path("content").asText("");
    if (!row.path("metadata").isObject()
        || !row.path("metadata").path("content").asText("").equals(content))
      return result("manual_source_changed");
    var kb = repository.base(kbId);
    if (kb == null) {
      repository.missingBase(original, id);
      return result("knowledge_base_not_found");
    }
    if (kb.path("tenant_id").asLong() != owner)
      throw new TaskFailure("processing task KB owner changed: skip retry for the task", false);
    // Go allocates a fresh attempt per delivery; an unknown input attempt is ignored.
    payload.remove("attempt");
    for (String key : List.of("lf_trace_id", "lf_observation_id", "lf_session_id", "lf_user_id"))
      if (payload.hasNonNull(key)) payload.set("langfuse_" + key.substring(3), payload.get(key));
    var job = delivery(original, payload);
    var source = new DocumentProcessingRepository.Snapshot(row, kb, tenant);
    var trace = new KnowledgeProcessingSpanPipeline(tracker, json, job, false);
    try {
      repository.begin(job, source);
      trace.open();
      boolean needs =
          kb.path("indexing_strategy").path("vector_enabled").asBoolean()
              || kb.path("indexing_strategy").path("keyword_enabled").asBoolean();
      IndexWriter writer = null;
      EmbeddingModel model = null;
      if (payload.path("need_cleanup").asBoolean()) {
        try {
          model = models.resolve(owner, kb.path("embedding_model_id").asText());
          writer = indexes.resolve(owner, kb);
          var priorImages=images==null?List.<String>of():repository.imageInfos(job,source);
          repository.cleanup(job, source, writer, true, true);
          if(images!=null)images.cleanup(source,priorImages,()->{cancellation.check();repository.check(job,source);});
          cleanupGraph(job, source, cancellation, true);
        } catch (CancellationException error) {
          throw error;
        } catch (Exception error) {
          repository.fail(job, source, "failed to cleanup old resources: " + error.getMessage());
          return result("cleanup_failed");
        }
      }
      String markdown = KnowledgeUploadForms.goTrim(content);
      if (markdown.isEmpty()) {
        repository.fail(job, source, "manual knowledge content is empty");
        return result("empty_content");
      }
      List<ManualImageResolver.Image> resolvedImages=List.of();
      if(images!=null){var resolved=images.resolve(source,markdown,()->{cancellation.check();repository.check(job,source);},cancellation::isCanceled);markdown=resolved.markdown();resolvedImages=resolved.images();}
      else if(markdown.matches("(?s).*(!\\[[^]]*]\\((?:data:|https?://)[^)]*\\)|<img\\b).*"))
        throw new TaskFailure("Native manual image resolution is not configured",false);
      markdown =
          HtmlTableNormalizer.normalize(
              AdaptiveChunker.normalizeLineEndings(IndexText.clean(markdown)));
      var effective =
          new KnowledgeProcessConfig(json)
              .resolve(kb, row.path("metadata").get("process_overrides"));
      boolean pendingMultimodal=effective.path("enable_multimodel").asBoolean()&&!resolvedImages.isEmpty();
      if(pendingMultimodal){
        if(multimodal==null)throw new TaskFailure("Native image:multimodal processing is not migrated",false);
        multimodal.supported(owner,effective);
      }
      var chunks =
          DocumentChunks.create(
              markdown, effective.path("chunking_config"), (start, end) -> List.of());
      var text = chunks.stream().filter(c -> c.type().equals("text")).toList();
      DocumentProcessingHandler.requireSupportedEnrichment(
          kb, effective, !text.isEmpty(), true, true, true, true, true);
      if (needs) {
        try {
          if (model == null) model = models.resolve(owner, kb.path("embedding_model_id").asText());
        } catch (RuntimeException error) {
          String message = "failed to get embedding model: " + modelFailure(error);
          repository.fail(job, source, message);
          trace.begin(
              "embedding",
              json.createObjectNode().put("model_id", kb.path("embedding_model_id").asText()));
          trace.fail("embedding", "EMBEDDING_PROVIDER_FAIL", message, error);
          return result("embedding_model_unavailable");
        }
      }
      if (writer == null) writer = indexes.resolve(owner, kb);
      repository.cleanup(job, source, writer, needs, false);
      cleanupGraph(job, source, cancellation, false);
      trace.begin("chunking", json.createObjectNode().put("chunks_planned", chunks.size()));
      repository.chunks(job, source, chunks);
      trace.end(
          "chunking",
          json.createObjectNode()
              .put("chunks_written", chunks.size())
              .put(
                  "total_text_chars",
                  chunks.stream()
                      .mapToLong(
                          c -> c.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                      .sum()));
      var entries = new ArrayList<IndexEntry>();
      var vectors = new LinkedHashMap<String, float[]>();
      long bytes = 0;
      if (needs) {
        var input =
            json.createObjectNode()
                .put("chunks_to_embed", text.size())
                .put("model_id", kb.path("embedding_model_id").asText())
                .put(
                    "vector_enabled",
                    kb.path("indexing_strategy").path("vector_enabled").asBoolean())
                .put(
                    "keyword_enabled",
                    kb.path("indexing_strategy").path("keyword_enabled").asBoolean());
        if (model.dimensions() > 0) input.put("dim", model.dimensions());
        trace.begin("embedding", input);
        for (var c : text)
          entries.add(
              new IndexEntry(
                  c.id(),
                  0,
                  c.id(),
                  id,
                  kbId,
                  "",
                  DocumentChunks.indexContent(row.path("title").asText(), c),
                  true));
        // The Go manual context specifies content byte order after deduplication.
        entries.sort((a, b) -> {int contentOrder=HybridSearchFanoutUtf8.compare(a.content(),b.content());return contentOrder!=0?contentOrder:HybridSearchFanoutUtf8.compare(a.sourceId(),b.sourceId());});
        for (var entry : entries)
          vectors.put(entry.sourceId(), new float[Math.max(0, model.dimensions())]);
        bytes = writer.estimateStorageSize(entries, vectors);
        vectors.clear();
        if (!repository.quota(job, source, bytes)) return result("storage_quota_exceeded");
        try {
          float[][] generated = embed(job, source, cancellation, model, entries);
          for (int i = 0; i < generated.length; i++)
            vectors.put(entries.get(i).sourceId(), generated[i]);
          if (generated.length > 0) writer.prepare(Set.of(generated[0].length));
          repository.indexes(job, source, writer, entries, vectors);
        } catch (ManualProcessingRepository.SourceChanged error) {
          throw error;
        } catch (CancellationException error) {
          throw error;
        } catch (Exception error) {
          repository.check(job, source);
          repository.fail(job, source, error.getMessage());
          repository.cleanup(job, source, writer, true, false);
          trace.fail("embedding", "VECTORSTORE_WRITE_FAILED", "batch index failed", error);
          return result("embedding_failed");
        }
        trace.end(
            "embedding",
            json.createObjectNode()
                .put("vectors_written", entries.size())
                .put("storage_bytes", bytes));
      } else trace.skip("embedding", "skipped");
      repository.finalized(job, source, bytes, text.size(),pendingMultimodal);
      if(pendingMultimodal){
        trace.begin("multimodal",json.createObjectNode().put("image_count",resolvedImages.size()));
        multimodal.enqueue(job,source,effective,resolvedImages,chunks);
        repository.account(job,source,bytes);
        return result("indexed");
      }
      trace.skip("multimodal", "skipped");
      var post =
          json.createObjectNode()
              .put("tenant_id", owner)
              .put("knowledge_id", id)
              .put("knowledge_base_id", kbId)
              .put("language", "zh-CN");
      if (job.payload().path("attempt").asLong() > 0)
        post.put("attempt", job.payload().path("attempt").asLong());
      for (String key :
          List.of(
              "langfuse_trace_id",
              "langfuse_observation_id",
              "langfuse_session_id",
              "langfuse_user_id"))
        if (job.payload().hasNonNull(key) && !job.payload().path(key).asText().isEmpty())
          post.set(key, job.payload().get(key));
      Exception failed = null;
      for (int retry = 0; retry < 3; retry++) {
        cancellation.check();
        repository.check(job, source);
        try {
          tasks.enqueue(
              owner,
              "knowledge:post_process",
              post,
              new KnowledgeFileUpload.TaskOptions("postprocess", 3, Duration.ofMinutes(30)));
          failed = null;
          break;
        } catch (Exception error) {
          failed = error;
        }
      }
      if (failed != null)
        throw new TaskFailure(
            "enqueue knowledge:post_process after 3 attempts: " + failed.getMessage(), true);
      repository.account(job, source, bytes);
      return result("indexed");
    } catch (ManualProcessingRepository.SourceChanged | ImageMultimodalRepository.Changed error) {
      repository.closeRemovedTrace(job, source);
      trace.abort("TASK_SUPERSEDED", "manual knowledge source changed");
      if (cancellation.isCanceled() || Thread.currentThread().isInterrupted()) throw error;
      return result("manual_source_changed");
    } catch (CancellationException error) {
      if (cancellation.reason() != TaskCancellation.Reason.LEASE_LOST)
        trace.abort("TASK_CANCELLED", "manual processing cancelled");
      throw error;
    } catch (TaskFailure error) {
      if (!error.retryable()) repository.fail(job, source, error.getMessage());
      throw error;
    }
  }

  private float[][] embed(
      TaskRecord job,
      DocumentProcessingRepository.Snapshot source,
      TaskCancellation cancellation,
      EmbeddingModel model,
      List<IndexEntry> entries)
      throws Exception {
    if (batchSize <= 0) throw new IllegalArgumentException("invalid BATCH_EMBED_SIZE");
    Exception last = null;
    for (int retry = 0; retry < 5; retry++) {
      repository.check(job, source);
      cancellation.check();
      var watcher=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"manual-embedding-cancel");t.setDaemon(true);return t;});
      try (var call = new CallContext()) {
        watcher.scheduleWithFixedDelay(()->{if(cancellation.isCanceled())call.cancel();},25,25,java.util.concurrent.TimeUnit.MILLISECONDS);
        float[][] all = new float[entries.size()][];
        if(embeddingPool!=null)all=embeddingPool.embed(model,call,entries.stream().map(e->IndexText.embeddingInput(e.content())).toList(),batchSize);
        else for (int start = 0; start < entries.size(); start += batchSize) {
          var batch = entries.subList(start, Math.min(entries.size(), start + batchSize));
          var out =
              model.embed(
                  call,
                  batch.stream().map(e -> IndexText.embeddingInput(e.content())).toList(),
                  false);
          if (out == null || out.length != batch.size())
            throw new IllegalArgumentException("embedding response count does not match inputs");
          for (int i = 0; i < out.length; i++) all[start + i] = out[i];
        }
        repository.check(job, source);
        cancellation.check();
        return all;
      } catch (CancellationException error) {
        throw error;
      } catch (Exception error) {
        last = error;
        if (retry < 4) {long until=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(200L<<retry);while(System.nanoTime()<until){cancellation.check();Thread.sleep(25);}}
      } finally {watcher.shutdownNow();}
    }
    throw last;
  }

  private void cleanupGraph(
      TaskRecord job, DocumentProcessingRepository.Snapshot source, TaskCancellation cancellation, boolean strict) {
    if (graph == null || !graph.enabled()) return;
    Runnable guard =
        () -> {
          cancellation.check();
          repository.check(job, source);
        };
    guard.run();
    try (var call = CallContext.timeout(Duration.ofSeconds(30))) {
      try {
        graph.delete(
            source.knowledge().path("knowledge_base_id").asText(),
            source.knowledge().path("id").asText(),
            call,
            guard);
      } catch (CancellationException error) {
        throw error;
      } catch (RuntimeException ignored) {
        guard.run();
        if(strict)throw ignored;
      }
    }
  }

  private ObjectNode decode(String raw) throws IOException {
    var schema =
        json.createObjectNode()
            .put("kind", "struct")
            .put("name", "ManualProcessPayload")
            .put("type", "types.ManualProcessPayload");
    var fields = schema.putArray("fields");
    for (String key :
        List.of(
            "request_id",
            "knowledge_id",
            "knowledge_base_id",
            "content",
            "lf_trace_id",
            "lf_observation_id",
            "lf_session_id",
            "lf_user_id"))
      fields.add(
          json.createObjectNode()
              .put("name", key)
              .set("schema", json.createObjectNode().put("kind", "string").put("type", "string")));
    fields.add(
        json.createObjectNode()
            .put("name", "tenant_id")
            .set(
                "schema",
                json.createObjectNode().put("kind", "uint").put("type", "uint64").put("bits", 64)));
    fields.add(
        json.createObjectNode()
            .put("name", "need_cleanup")
            .set("schema", json.createObjectNode().put("kind", "bool").put("type", "bool")));
    var decoded = new GoUploadJson(json).decode(raw, schema);
    return decoded.isObject() ? (ObjectNode) decoded : json.createObjectNode();
  }

  private ObjectNode result(String value) {
    return json.createObjectNode().put("status", value);
  }

  private static String modelFailure(RuntimeException error) {
    String message = error.getMessage();
    return message != null && (message.contains("not found") || message.contains("unavailable"))
        ? "model not found"
        : Objects.toString(message, "model not found");
  }

  private static TaskRecord delivery(TaskRecord job, ObjectNode payload) {
    return new TaskRecord(
        job.id(),
        job.tenantId(),
        job.type(),
        job.idempotencyKey(),
        payload,
        job.status(),
        job.attempts(),
        job.maxAttempts(),
        job.availableAt(),
        job.leaseOwner(),
        job.leaseUntil(),
        job.cancelRequested(),
        job.result(),
        job.lastError(),
        job.createdAt(),
        job.updatedAt(),
        job.queue(),
        job.timeoutMillis());
  }

  private static final class HybridSearchFanoutUtf8 {
    static int compare(String a, String b) {
      return Arrays.compareUnsigned(
          a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
          b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
  }
}
