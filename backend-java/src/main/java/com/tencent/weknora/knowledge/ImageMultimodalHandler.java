package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.model.client.*;
import com.tencent.weknora.retrieval.*;
import com.tencent.weknora.task.*;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Native image:multimodal processing: images and chunks belong to a fenced parent generation. */
public final class ImageMultimodalHandler implements TaskHandler {
  @FunctionalInterface
  public interface Vision {
    VisionModel resolve(long owner, JsonNode config);
  }

  @FunctionalInterface
  public interface Embeddings {
    EmbeddingModel resolve(long owner, String id);
  }

  @FunctionalInterface
  public interface Images {
    byte[] read(TaskRecord job, ImageMultimodalRepository.Source source, CallContext call)
        throws Exception;
  }

  private final ImageMultimodalRepository repository;
  private final ObjectMapper json;
  private final Vision vision;
  private final Embeddings embeddings;
  private final Images images;
  private final DocumentProcessingHandler.Indexes indexes;
  private final KnowledgeFileUpload.Enqueuer tasks;
  private final KnowledgeProcessingSpanTracker tracker;
  private final ImageMultimodalText text;
  private final ManualEmbeddingPool pool;
  private final int batchSize;

  public ImageMultimodalHandler(
      ImageMultimodalRepository repository,
      ObjectMapper json,
      Vision vision,
      Embeddings embeddings,
      Images images,
      DocumentProcessingHandler.Indexes indexes,
      KnowledgeFileUpload.Enqueuer tasks,
      KnowledgeProcessingSpanTracker tracker,
      ManualEmbeddingPool pool,
      int batchSize) {
    this.repository = repository;
    this.json = json;
    this.vision = vision;
    this.embeddings = embeddings;
    this.images = images;
    this.indexes = indexes;
    this.tasks = tasks;
    this.tracker = tracker;
    this.pool = pool;
    this.batchSize = batchSize;
    text = new ImageMultimodalText(json);
  }

  public String type() {
    return "image:multimodal";
  }

  public Duration timeout() {
    return Duration.ofMinutes(30);
  }

  public JsonNode execute(TaskRecord received, TaskCancellation cancellation) throws Exception {
    ObjectNode payload;
    try {
      payload = decode(repository.raw(received));
    } catch (IOException e) {
      throw new TaskFailure("unmarshal image multimodal payload: " + e.getMessage(), true);
    }
    var job =
        new TaskRecord(
            received.id(),
            received.tenantId(),
            received.type(),
            received.idempotencyKey(),
            payload,
            received.status(),
            received.attempts(),
            received.maxAttempts(),
            received.availableAt(),
            received.leaseOwner(),
            received.leaseUntil(),
            received.cancelRequested(),
            received.result(),
            received.lastError(),
            received.createdAt(),
            received.updatedAt(),
            received.queue(),
            received.timeoutMillis());
    cancellation.check();
    repository.fence(job);
    var out = json.createObjectNode();
    if (repository.superseded(job)) return out.put("skipped", "attempt_superseded");
    var source = repository.load(job);
    if (source == null
        || Set.of("cancelled", "deleting")
            .contains(source.knowledge().path("parse_status").asText())) {
      repository.drain(job, tasks);
      return out.put("skipped", "parent_unavailable");
    }
    Exception failure = null;
    boolean changed = false;
    KnowledgeProcessingSpanTracker.Span span = null;
    var watcher =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              var t = new Thread(r, "native-image-cancellation");
              t.setDaemon(true);
              return t;
            });
    try (var call = CallContext.timeout(timeout())) {
      var monitor =
          watcher.scheduleWithFixedDelay(
              () -> {
                if (cancellation.isCanceled()) call.cancel();
              },
              25,
              25,
              TimeUnit.MILLISECONDS);
      try {
        Runnable guard =
            () -> {
              cancellation.check();
              repository.check(job, source);
            };
        guard.run();
        var config =
            new KnowledgeProcessConfig(json)
                .resolve(
                    source.base(), source.knowledge().path("metadata").get("process_overrides"))
                .path("vlm_config");
        VisionModel model;
        try {
          if (!enabled(config))
            throw new IllegalStateException(
                "VLM is not enabled for knowledge base "
                    + payload.path("knowledge_base_id").asText());
          model = vision.resolve(job.tenantId(), config);
        } catch (RuntimeException e) {
          throw new TaskFailure("resolve VLM: " + modelError(e), true);
        }
        out.put(
            "vlm_model_id",
            ImageMultimodalText.trim(config.path("model_id").asText()).isEmpty()
                ? "legacy_inline"
                : ImageMultimodalText.trim(config.path("model_id").asText()));
        if (tracker != null && payload.path("attempt").asLong() > 0) {
          var parent =
              tracker.lookupStage(
                  job.tenantId(),
                  payload.path("knowledge_id").asText(),
                  payload.path("attempt").asLong(),
                  "multimodal");
          span =
              tracker.beginSubspan(
                  parent,
                  "multimodal.image[" + payload.path("image_index").asInt() + "]",
                  "generation",
                  json.createObjectNode()
                      .put("image_url", payload.path("image_url").asText())
                      .put("image_source_type", payload.path("image_source_type").asText())
                      .put("parent_chunk_id", payload.path("chunk_id").asText())
                      .put(
                          "pipeline",
                          payload.path("image_attrs_enabled").asBoolean()
                              ? "observation_driven"
                              : "caption_ocr"));
        }
        byte[] bytes;
        try {
          bytes = images.read(job, source, call);
        } catch (CancellationException e) {
          throw e;
        } catch (Exception e) {
          out.put("skipped", "unreadable_image").put("read_error", e.getMessage());
          throw new TaskFailure("read source image: " + e.getMessage(), true);
        }
        guard.run();
        var result = text.process(payload, config, bytes, model, call, guard, out);
        if (result.children().isEmpty()) return out;
        var children = repository.create(job, source, result);
        try {
          if (!needsEmbedding(source.base()))
            repository.indexedWithoutEngine(job, source, children);
          else {
            guard.run();
            var modelEmbedding =
                embeddings.resolve(
                    job.tenantId(), source.base().path("embedding_model_id").asText());
            var writer = indexes.resolve(job.tenantId(), source.base());
            var entries =
                children.stream()
                    .map(
                        c ->
                            new IndexEntry(
                                c.id(),
                                0,
                                c.id(),
                                payload.path("knowledge_id").asText(),
                                payload.path("knowledge_base_id").asText(),
                                "",
                                c.content(),
                                true))
                    .sorted(
                        (a, b) -> {
                          int n = utf8(a.content(), b.content());
                          return n != 0 ? n : utf8(a.sourceId(), b.sourceId());
                        })
                    .toList();
            float[][] vectors = embed(modelEmbedding, call, entries, guard);
            guard.run();
            var mapped = new LinkedHashMap<String, float[]>();
            for (int i = 0; i < entries.size(); i++)
              mapped.put(entries.get(i).sourceId(), vectors[i]);
            if (vectors.length > 0) writer.prepare(Set.of(vectors[0].length));
            repository.index(job, source, writer, entries, mapped);
          }
        } catch (CancellationException e) {
          throw e;
        } catch (Exception indexError) {
          guard
              .run(); /* Go persists child chunks and records indexed=true even when inner indexing fails. */
        }
        out.put("indexed", true);
        return out;
      } catch (ImageMultimodalRepository.Changed e) {
        changed = true;
        if (tracker != null)
          tracker.abort(
              job.tenantId(),
              payload.path("knowledge_id").asText(),
              payload.path("attempt").asLong(),
              "TASK_SUPERSEDED",
              "image multimodal source changed",
              "image multimodal source changed");
        if (cancellation.isCanceled() || Thread.currentThread().isInterrupted()) throw e;
        return json.createObjectNode().put("skipped", "source_changed");
      } catch (Exception e) {
        failure = e;
        throw e;
      } finally {
        monitor.cancel(false);
        if (tracker != null && span != null && !changed) {
          if (failure == null) tracker.end(span, out);
          else if (job.attempts() >= job.maxAttempts())
            tracker.fail(
                span, "MULTIMODAL_VLM_FAILED", Objects.toString(failure.getMessage(), ""), failure);
        }
        if (!changed
            && !cancellation.isCanceled()
            && !Thread.currentThread().isInterrupted()
            && (failure == null || job.attempts() >= job.maxAttempts()))
          repository.drain(job, tasks);
      }
    } finally {
      watcher.shutdownNow();
    }
  }

  private float[][] embed(
      EmbeddingModel model, CallContext call, List<IndexEntry> entries, Runnable guard)
      throws Exception {
    Exception last = null;
    var texts = entries.stream().map(e -> IndexText.embeddingInput(e.content())).toList();
    for (int retry = 0; retry < 5; retry++) {
      guard.run();
      try {
        var vectors =
            pool == null
                ? model.embed(call, texts, false)
                : pool.embed(model, call, texts, batchSize);
        guard.run();
        return vectors;
      } catch (CancellationException error) {
        throw error;
      } catch (Exception error) {
        last = error;
        guard.run();
        if (retry < 4) call.pause(Duration.ofMillis(200L << retry));
      }
    }
    throw last;
  }

  private static boolean needsEmbedding(JsonNode kb) {
    return kb.path("indexing_strategy").path("vector_enabled").asBoolean()
        || kb.path("indexing_strategy").path("keyword_enabled").asBoolean();
  }

  private static int utf8(String a, String b) {
    return Arrays.compareUnsigned(
        a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
        b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  static boolean enabled(JsonNode c) {
    return c.path("enabled").asBoolean() && !c.path("model_id").asText().isEmpty()
        || !c.path("model_name").asText().isEmpty() && !c.path("base_url").asText().isEmpty();
  }

  private static String modelError(RuntimeException e) {
    String m = Objects.toString(e.getMessage(), "");
    return m.toLowerCase(Locale.ROOT).contains("model not found") ? "model not found" : m;
  }

  private ObjectNode decode(String raw) throws IOException {
    JsonNode schema;
    try (var input = getClass().getResourceAsStream("/image-multimodal-payload-schema.json")) {
      schema = json.readTree(Objects.requireNonNull(input));
    }
    JsonNode decoded = new GoUploadJson(json).decode(raw, schema);
    ObjectNode payload = decoded.isObject() ? (ObjectNode) decoded : json.createObjectNode();
    for (var field : schema.path("fields"))
      if (field.path("name").asText().equals("source_locators") && payload.has("source_locators"))
        payload.set(
            "source_locators", locatorJson(payload.get("source_locators"), field.get("schema")));
    return payload;
  }

  private JsonNode locatorJson(JsonNode value, JsonNode schema) {
    String kind = schema.path("kind").asText();
    boolean nil = value == null || value.isNull();
    if (kind.equals("struct")) {
      var result = json.createObjectNode();
      for (var field : schema.path("fields")) {
        String name = field.path("name").asText();
        JsonNode child = locatorJson(nil ? null : value.get(name), field.get("schema"));
        boolean empty =
            child.isNull()
                || child.isArray() && child.isEmpty()
                || child.isTextual() && child.asText().isEmpty()
                || child.isBoolean() && !child.asBoolean()
                || child.isNumber() && child.asDouble() == 0;
        if (!field.path("omitempty").asBoolean() || !empty) result.set(name, child);
      }
      return result;
    }
    if (kind.equals("slice")) {
      if (nil) return NullNode.instance;
      var result = json.createArrayNode();
      for (var item : value) result.add(locatorJson(item, schema.path("elem")));
      return result;
    }
    if (!nil) return value;
    if (kind.equals("string")) return TextNode.valueOf("");
    if (kind.equals("bool")) return BooleanNode.FALSE;
    if (kind.startsWith("float")) return DoubleNode.valueOf(0);
    return LongNode.valueOf(0);
  }
}
