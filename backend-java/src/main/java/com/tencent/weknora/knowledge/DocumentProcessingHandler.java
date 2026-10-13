package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.chunker.AdaptiveChunker;
import com.tencent.weknora.docreader.*;
import com.tencent.weknora.model.client.*;
import com.tencent.weknora.retrieval.*;
import com.tencent.weknora.sourceloc.SourceLocatorIndex;
import com.tencent.weknora.task.*;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CancellationException;

/**
 * Executes real document reads, chunking, model calls, atomic index persistence and postprocess
 * enqueue.
 */
public final class DocumentProcessingHandler implements TaskHandler {
  static final class ParserEndpointRejected extends RuntimeException {}

  @FunctionalInterface
  public interface Files {
    InputStream open(long owner, String kb, String path) throws IOException;
  }

  @FunctionalInterface
  public interface Readers {
    DocumentReader select(String engine, String fileType) throws TaskFailure;
  }

  @FunctionalInterface
  public interface Embeddings {
    EmbeddingModel resolve(long owner, String modelId);
  }

  @FunctionalInterface
  public interface Indexes {
    IndexWriter resolve(long owner, JsonNode kb) throws TaskFailure;
  }

  @FunctionalInterface
  public interface Sources {
    SourceLocatorIndex build(
        DocumentReadResult read,
        String finalMarkdown,
        String fileType,
        String sourceHash,
        byte[] original)
        throws TaskFailure;
  }

  private final DocumentProcessingRepository repository;
  private final ObjectMapper json;
  private final KnowledgeProcessConfig process;
  private final Files files;
  private final Readers readers;
  private final Embeddings embeddings;
  private final Indexes indexes;
  private final Sources sources;
  private final KnowledgeFileUpload.Enqueuer tasks;
  private final java.util.function.Consumer<Map<String, String>> validateOptions;

  public DocumentProcessingHandler(
      DocumentProcessingRepository repository,
      ObjectMapper json,
      Files files,
      Readers readers,
      Embeddings embeddings,
      Indexes indexes,
      Sources sources,
      KnowledgeFileUpload.Enqueuer tasks) {
    this(repository, json, files, readers, embeddings, indexes, sources, tasks, options -> {});
  }

  public DocumentProcessingHandler(
      DocumentProcessingRepository repository,
      ObjectMapper json,
      Files files,
      Readers readers,
      Embeddings embeddings,
      Indexes indexes,
      Sources sources,
      KnowledgeFileUpload.Enqueuer tasks,
      java.util.function.Consumer<Map<String, String>> validateOptions) {
    this.validateOptions = validateOptions;
    this.repository = repository;
    this.json = json;
    this.process = new KnowledgeProcessConfig(json);
    this.files = files;
    this.readers = readers;
    this.embeddings = embeddings;
    this.indexes = indexes;
    this.sources = sources;
    this.tasks = tasks;
  }

  @Override
  public String type() {
    return "document:process";
  }

  @Override
  public Duration timeout() {
    return Duration.ofHours(2);
  }

  @Override
  public JsonNode execute(TaskRecord job, TaskCancellation cancellation) throws Exception {
    cancellation.check();
    var loaded = repository.load(job);
    if (loaded.isEmpty()) return skipped("knowledge_not_found");
    var snapshot = loaded.get();
    if (!repository.begin(job, snapshot)) return skipped("knowledge_no_longer_current");
    ObjectNode row = snapshot.knowledge(), kb = snapshot.knowledgeBase();
    JsonNode override = row.path("metadata").path("process_overrides");
    ObjectNode effective = process.resolve(kb, override.isMissingNode() ? null : override);
    String type = job.payload().path("file_type").asText(row.path("file_type").asText());
    try {
      if (!job.payload().path("url").asText().isEmpty()
          || !job.payload().path("file_url").asText().isEmpty()
          || !job.payload().path("content").asText().isEmpty())
        throw new TaskFailure("Native URL/manual document task processing is not migrated", false);
      if (SimpleFormatReader.isAudioFormat(type))
        throw new TaskFailure("Native ASR document processing is not migrated", false);
      if (SimpleFormatReader.isImageFormat(type))
        throw new TaskFailure("Native image document processing is not migrated", false);
      byte[] bytes;
      try (var input =
          files.open(
              job.tenantId(),
              row.path("knowledge_base_id").asText(),
              row.path("file_path").asText())) {
        bytes = input.readAllBytes();
      } catch (IOException error) {
        return fileFailure(job, snapshot, error);
      }
      cancellation.check();
      ParserConfiguration parser = new ParserConfiguration();
      List<ParserConfiguration.Rule> rules =
          ParserConfiguration.rules(effective.path("chunking_config").get("parser_engine_rules"));
      String engine = parser.resolveKnowledgeEngine(rules, type);
      Map<String, String> upload = new LinkedHashMap<>();
      override
          .path("parser_engine_overrides")
          .fields()
          .forEachRemaining(e -> upload.put(e.getKey(), e.getValue().asText()));
      JsonNode tenantConfig = parseTenantConfig(snapshot.tenant().get("parser_engine_config"));
      Map<String, String> options =
          ParserConfiguration.mergeOverrides(
              ParserConfiguration.tenantOverrides(tenantConfig), upload);
      parser.applyKnowledgeRuleOverrides(options, rules, type);
      try {
        validateOptions.accept(options);
      } catch (ParserEndpointRejected error) {
        repository.failed(job, snapshot, "Parser endpoint is not allowed for security reasons");
        return failed("parser_endpoint_rejected");
      }
      DocumentReader reader = readers.select(engine, type);
      DocumentReadRequest request =
          new DocumentReadRequest(
              bytes,
              job.payload().path("file_name").asText(row.path("file_name").asText()),
              type,
              "",
              row.path("title").asText(),
              engine,
              job.payload().path("request_id").asText(),
              options);
      DocumentReadResult read;
      try {
        read = reader.read(request);
      } catch (ParserEndpointRejected error) {
        repository.failed(job, snapshot, "Parser endpoint is not allowed for security reasons");
        return failed("parser_endpoint_rejected");
      } catch (RuntimeException error) {
        if (cancellation.isCanceled() || Thread.currentThread().isInterrupted())
          throw new CancellationException("Document parsing interrupted");
        String safe = readerFailure(error);
        if (job.attempts() >= job.maxAttempts()) repository.failed(job, snapshot, safe);
        throw new TaskFailure(safe, true);
      }
      cancellation.check();
      if (!read.error().isEmpty()) {
        repository.failed(job, snapshot, IndexText.clean(read.error()));
        return failed("parser_error");
      }
      if (read.isAudio() || !read.imageRefs().isEmpty())
        throw new TaskFailure("Native audio/image enrichment processing is not migrated", false);
      String markdown =
          HtmlTableNormalizer.normalize(
              AdaptiveChunker.normalizeLineEndings(IndexText.clean(read.markdownContent())));
      SourceLocatorIndex sourceIndex =
          sources.build(read, markdown, type, row.path("file_hash").asText(), bytes);
      var chunks =
          DocumentChunks.create(
              markdown,
              effective.path("chunking_config"),
              (start, end) -> sourceIndex.locators(start, end));
      List<DocumentChunks.Stored> text =
          chunks.stream().filter(chunk -> chunk.type().equals("text")).toList();
      requireSupportedEnrichment(kb, effective, !text.isEmpty());
      IndexWriter writer = indexes.resolve(job.tenantId(), kb);
      List<IndexEntry> entries = new ArrayList<>();
      Map<String, float[]> vectors = new LinkedHashMap<>();
      JsonNode strategy = kb.path("indexing_strategy");
      boolean needsEmbedding =
          strategy.path("vector_enabled").asBoolean()
              || strategy.path("keyword_enabled").asBoolean();
      DocumentProcessingRepository.Replacement replacement;
      if (needsEmbedding && !text.isEmpty()) {
        EmbeddingModel model;
        try {
          model = embeddings.resolve(job.tenantId(), kb.path("embedding_model_id").asText());
        } catch (RuntimeException error) {
          repository.failed(job, snapshot, "failed to get embedding model");
          return failed("embedding_model_unavailable");
        }
        for (var chunk : text)
          entries.add(
              new IndexEntry(
                  chunk.id(),
                  0,
                  chunk.id(),
                  row.path("id").asText(),
                  row.path("knowledge_base_id").asText(),
                  "",
                  DocumentChunks.indexContent(row.path("title").asText(), chunk),
                  true));
        replacement = repository.captureReplacement(job, snapshot, writer);
        float[][] generated;
        try (var call = new CallContext()) {
          try {
            generated =
                model.embed(
                    call,
                    entries.stream()
                        .map(entry -> IndexText.embeddingInput(entry.content()))
                        .toList(),
                    false);
          } catch (RuntimeException error) {
            if (cancellation.isCanceled() || Thread.currentThread().isInterrupted())
              throw new CancellationException("Embedding interrupted");
            repository.failed(job, snapshot, "Embedding/indexing failed");
            return failed("embedding_failed");
          }
        }
        cancellation.check();
        if (generated == null || generated.length != entries.size())
          throw new TaskFailure("Embedding response count does not match document chunks", false);
        int dimension = generated[0] == null ? 0 : generated[0].length;
        if (dimension <= 0 || model.dimensions() > 0 && model.dimensions() != dimension)
          throw new TaskFailure(
              "Embedding response dimensions do not match model configuration", false);
        for (int i = 0; i < generated.length; i++) {
          if (generated[i] == null || generated[i].length != dimension)
            throw new TaskFailure("Embedding response has inconsistent dimensions", false);
          for (float value : generated[i])
            if (!Float.isFinite(value))
              throw new TaskFailure("Embedding response contains a non-finite vector value", false);
          vectors.put(entries.get(i).sourceId(), generated[i]);
        }
        writer.prepare(Set.of(dimension));
      } else replacement = repository.captureReplacement(job, snapshot, writer);
      cancellation.check();
      try {
        boolean saved = repository.save(job, snapshot, replacement, chunks, writer, entries, vectors, tasks);
        return saved
            ? json.createObjectNode().put("indexed", true).put("chunks", text.size())
            : skipped("knowledge_no_longer_current_or_quota");
      } catch (CancellationException error) {
        throw error;
      } catch (Exception error) {
        if (cancellation.isCanceled() || Thread.currentThread().isInterrupted())
          throw new CancellationException("Indexing interrupted");
        if (job.attempts() >= job.maxAttempts())
          repository.failed(job, snapshot, "Document indexing or postprocess enqueue failed");
        throw new TaskFailure("Document indexing or postprocess enqueue failed", true);
      }
    } catch (TaskFailure error) {
      if (!error.retryable()) repository.failed(job, snapshot, error.getMessage());
      throw error;
    }
  }

  private JsonNode parseTenantConfig(Object value) throws IOException {
    return value == null ? NullNode.instance : json.readTree(value.toString());
  }

  private JsonNode fileFailure(
      TaskRecord job, DocumentProcessingRepository.Snapshot snapshot, IOException error)
      throws TaskFailure {
    String safe = "failed to get file";
    if (job.attempts() >= job.maxAttempts()) repository.failed(job, snapshot, safe);
    throw new TaskFailure(safe, true);
  }

  static void requireSupportedEnrichment(JsonNode kb, JsonNode effective, boolean text)
      throws TaskFailure {
    if (!text) return;
    if (effective.path("summary_enabled").asBoolean())
      throw new TaskFailure("Native summary generation is not migrated", false);
    if (effective.path("question_generation_config").path("enabled").asBoolean()
        && (kb.path("indexing_strategy").path("vector_enabled").asBoolean()
            || kb.path("indexing_strategy").path("keyword_enabled").asBoolean()))
      throw new TaskFailure("Native question generation is not migrated", false);
    if (effective.path("graph_enabled").asBoolean())
      throw new TaskFailure("Native graph extraction is not migrated", false);
    if (kb.path("indexing_strategy").path("wiki_enabled").asBoolean())
      throw new TaskFailure("Native wiki ingestion is not migrated", false);
    if (kb.path("auto_tag_config").path("enabled").asBoolean())
      throw new TaskFailure("Native automatic knowledge tagging is not migrated", false);
    if (kb.path("profile_config").path("enabled").asBoolean())
      throw new TaskFailure("Native knowledge base profile aggregation is not migrated", false);
  }

  private JsonNode skipped(String reason) {
    return json.createObjectNode().put("skipped", reason);
  }

  private JsonNode failed(String reason) {
    return json.createObjectNode().put("failed", reason);
  }

  private static String readerFailure(RuntimeException error) {
    io.grpc.Status.Code code = io.grpc.Status.fromThrowable(error).getCode();
    return code == io.grpc.Status.Code.DEADLINE_EXCEEDED
        ? "Document parsing service timed out. Check service health and load before retrying."
        : code == io.grpc.Status.Code.UNAVAILABLE
            ? "Cannot connect to the document parsing service, or the connection was interrupted."
                + " Check that DocReader is running and healthy, then retry."
            : "Document parsing failed. Check the file format and the DocReader logs for this"
                + " attempt.";
  }
}
