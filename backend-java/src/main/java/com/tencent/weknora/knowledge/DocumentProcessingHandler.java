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
    default DocumentReader selectURL(String engine, String fileType) throws TaskFailure {
      return select(engine, "url");
    }
  }

  @FunctionalInterface
  public interface DownloadedFiles {
    String save(long owner, String kb, byte[] bytes, String filename) throws IOException;
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
  private final KnowledgeProcessingSpanTracker tracker;
  private final boolean summaryNative;
  private final boolean questionNative;
  private final boolean graphNative;
  private final boolean autoTagNative;
  private GraphExtractionStore graphCleanup;
  private boolean profileNative;
  private RemoteDocumentDownloads downloads;
  private DownloadedFiles downloadedFiles;
  public DocumentProcessingHandler withRemoteSources(RemoteDocumentDownloads downloads, DownloadedFiles files) {
    this.downloads = downloads; this.downloadedFiles = files; return this;
  }
  public DocumentProcessingHandler withProfiles(KnowledgeBaseProfileHandler profiles){this.profileNative=profiles!=null;return this;}
  public DocumentProcessingHandler withGraphCleanup(GraphExtractionStore graph){this.graphCleanup=graph;return this;}
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
    this(repository,json,files,readers,embeddings,indexes,sources,tasks,validateOptions,null);
  }

  public DocumentProcessingHandler(
      DocumentProcessingRepository repository, ObjectMapper json, Files files, Readers readers,
      Embeddings embeddings, Indexes indexes, Sources sources, KnowledgeFileUpload.Enqueuer tasks,
      java.util.function.Consumer<Map<String,String>> validateOptions, KnowledgeProcessingSpanTracker tracker) {
    this(repository,json,files,readers,embeddings,indexes,sources,tasks,validateOptions,tracker,false);
  }
  public DocumentProcessingHandler(
      DocumentProcessingRepository repository, ObjectMapper json, Files files, Readers readers,
      Embeddings embeddings, Indexes indexes, Sources sources, KnowledgeFileUpload.Enqueuer tasks,
      java.util.function.Consumer<Map<String,String>> validateOptions, KnowledgeProcessingSpanTracker tracker,boolean summaryNative) {
    this(repository,json,files,readers,embeddings,indexes,sources,tasks,validateOptions,tracker,summaryNative,false);
  }
  public DocumentProcessingHandler(
      DocumentProcessingRepository repository,ObjectMapper json,Files files,Readers readers,Embeddings embeddings,Indexes indexes,Sources sources,KnowledgeFileUpload.Enqueuer tasks,java.util.function.Consumer<Map<String,String>> validateOptions,KnowledgeProcessingSpanTracker tracker,boolean summaryNative,boolean questionNative) {
    this(repository,json,files,readers,embeddings,indexes,sources,tasks,validateOptions,tracker,summaryNative,questionNative,false);
  }
  public DocumentProcessingHandler(DocumentProcessingRepository repository,ObjectMapper json,Files files,Readers readers,Embeddings embeddings,Indexes indexes,Sources sources,KnowledgeFileUpload.Enqueuer tasks,java.util.function.Consumer<Map<String,String>> validateOptions,KnowledgeProcessingSpanTracker tracker,boolean summaryNative,boolean questionNative,boolean graphNative) {
    this(repository,json,files,readers,embeddings,indexes,sources,tasks,validateOptions,tracker,summaryNative,questionNative,graphNative,false);
  }
  public DocumentProcessingHandler(DocumentProcessingRepository repository,ObjectMapper json,Files files,Readers readers,Embeddings embeddings,Indexes indexes,Sources sources,KnowledgeFileUpload.Enqueuer tasks,java.util.function.Consumer<Map<String,String>> validateOptions,KnowledgeProcessingSpanTracker tracker,boolean summaryNative,boolean questionNative,boolean graphNative,boolean autoTagNative) {
    this.autoTagNative=autoTagNative;
    this.graphNative=graphNative;
    this.questionNative=questionNative;
    this.summaryNative=summaryNative;
    this.tracker=tracker;
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
    var trace=new KnowledgeProcessingSpanPipeline(tracker,json,job,false);
    try {cancellation.check();}catch(CancellationException error){closeCanceledTrace(job,cancellation,trace);throw error;}
    var loaded = repository.load(job);
    if (loaded.isEmpty()) return skipped("knowledge_not_found");
    var snapshot = loaded.get();
    boolean began;
    try {began=repository.begin(job,snapshot);}catch(CancellationException error){closeCanceledTrace(job,cancellation,trace);throw error;}
    if (!began) {
      trace.finishStatus(snapshot.knowledge(),"knowledge_no_longer_current");
      return skipped("knowledge_no_longer_current");
    }
    trace.open();
    ObjectNode row = snapshot.knowledge(), kb = snapshot.knowledgeBase();
    JsonNode override = row.path("metadata").path("process_overrides");
    ObjectNode effective = process.resolve(kb, override.isMissingNode() ? null : override);
    String type = job.payload().path("file_type").asText(row.path("file_type").asText());
    try {
      String url = job.payload().path("url").asText();
      String fileURL = job.payload().path("file_url").asText();
      boolean isURL = !url.isEmpty();
      String filename = job.payload().path("file_name").asText(row.path("file_name").asText());
      String path = row.path("file_path").asText();
      Runnable guard = () -> { cancellation.check(); repository.checkCurrent(job, snapshot); };
      if (!fileURL.isEmpty()) {
        requireRemoteSources();
        try { downloads.validate(fileURL); }
        catch (com.tencent.weknora.security.ApiException error) {
          repository.failed(job, snapshot, "File URL is not allowed for security reasons");
          return failed("file_url_rejected");
        }
        RemoteDocumentDownloads.Download downloaded;
        try { downloaded = downloads.download(fileURL, filename, type, guard); }
        catch (IOException error) {
          if (job.attempts() >= job.maxAttempts()) repository.failed(job, snapshot, error.getMessage());
          throw new TaskFailure("failed to download file from URL: " + error.getMessage(), true);
        }
        filename = downloaded.filename(); type = downloaded.fileType();
        if (!type.isEmpty() && !KnowledgeProcessConfig.supported(type)) {
          repository.failed(job, snapshot, "unsupported file type: " + type);
          return failed("unsupported_file_type");
        }
        if (!filename.isEmpty() && row.path("file_name").asText().isEmpty()) row.put("file_name", filename);
        if (!type.isEmpty() && row.path("file_type").asText().isEmpty()) {
          row.put("file_type", type); repository.updateRemoteMetadata(job, snapshot);
        }
        guard.run();
        try { path = downloadedFiles.save(job.tenantId(), row.path("knowledge_base_id").asText(), downloaded.bytes(), filename); }
        catch (IOException error) {
          if (job.attempts() >= job.maxAttempts()) repository.failed(job, snapshot, error.getMessage());
          throw new TaskFailure("failed to save downloaded file: " + error.getMessage(), true);
        }
        guard.run();
      } else if (!isURL && !job.payload().path("content").asText().isEmpty())
        throw new TaskFailure("Native inline document task processing is not migrated", false);
      var docInput = json.createObjectNode().put("file_name", filename).put("file_type", type).put("is_url", isURL);
      if (isURL) docInput.put("url", url);
      trace.begin("docreader", docInput);
      if (!isURL && fileURL.isEmpty() && SimpleFormatReader.isAudioFormat(type))
        throw new TaskFailure("Native ASR document processing is not migrated", false);
      if (!isURL && fileURL.isEmpty() && SimpleFormatReader.isImageFormat(type))
        throw new TaskFailure("Native image document processing is not migrated", false);
      cancellation.check();
      ParserConfiguration parser = new ParserConfiguration();
      List<ParserConfiguration.Rule> rules =
          ParserConfiguration.rules(effective.path("chunking_config").get("parser_engine_rules"));
      String engine = parser.resolveKnowledgeEngine(rules, isURL ? "url" : type);
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
        trace.fail("docreader","DOCREADER_PARSE_FAILED","Parser endpoint is not allowed for security reasons",error);
        return failed("parser_endpoint_rejected");
      }
      if (isURL) {
        requireRemoteSources();
        try { downloads.validate(url); }
        catch (com.tencent.weknora.security.ApiException error) {
          repository.failed(job, snapshot, "URL is not allowed for security reasons");
          trace.fail("docreader", "DOCREADER_PARSE_FAILED", "URL rejected for security reasons", error);
          return failed("url_rejected");
        }
      }
      DocumentReader reader = isURL ? readers.selectURL(engine, type) : readers.select(engine, type);
      if (reader == null) {
        String message = "Document parsing service is not configured. Please use text/paragraph import or set DOCREADER_ADDR.";
        repository.failed(job, snapshot, message);
        trace.fail("docreader", "DOCREADER_UNAVAILABLE", message, null);
        return failed("docreader_unavailable");
      }
      byte[] bytes = new byte[0];
      if (!isURL) {
        guard.run();
        try (var input = files.open(job.tenantId(), row.path("knowledge_base_id").asText(), path)) {
          bytes = input.readAllBytes();
        } catch (IOException error) {
          trace.fail("docreader", "DOCREADER_PARSE_FAILED", "failed to get file", error);
          return fileFailure(job, snapshot, error);
        }
      }
      DocumentReadRequest request =
          new DocumentReadRequest(
              bytes,
              isURL ? "" : filename,
              isURL ? "" : type,
              url,
              row.path("title").asText(),
              engine,
              job.payload().path("request_id").asText(),
              options);
      DocumentReadResult read;
      try {
        read = reader.read(request);
      } catch (ParserEndpointRejected error) {
        repository.failed(job, snapshot, "Parser endpoint is not allowed for security reasons");
        trace.fail("docreader","DOCREADER_PARSE_FAILED","Parser endpoint is not allowed for security reasons",error);
        return failed("parser_endpoint_rejected");
      } catch (RuntimeException error) {
        if (cancellation.isCanceled() || Thread.currentThread().isInterrupted())
          throw new CancellationException("Document parsing interrupted");
        String safe = readerFailure(error);
        if (job.attempts() >= job.maxAttempts()) repository.failed(job, snapshot, safe);
        String code=io.grpc.Status.fromThrowable(error).getCode()==io.grpc.Status.Code.DEADLINE_EXCEEDED?"DOCREADER_TIMEOUT":io.grpc.Status.fromThrowable(error).getCode()==io.grpc.Status.Code.UNAVAILABLE?"DOCREADER_UNAVAILABLE":"DOCREADER_PARSE_FAILED";
        trace.fail("docreader",code,safe,error);
        throw new TaskFailure(safe, true);
      }
      cancellation.check();
      if (!read.error().isEmpty()) {
        repository.failed(job, snapshot, IndexText.clean(read.error()));
        trace.fail("docreader","DOCREADER_PARSE_FAILED",IndexText.clean(read.error()),null);
        return failed("parser_error");
      }
      var docOutput=json.createObjectNode().put("text_length",read.markdownContent().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).put("images_found",read.imageRefs().size()).put("is_audio",read.isAudio());
      if(!read.metadata().getOrDefault("pages","").isEmpty())docOutput.put("pages",read.metadata().get("pages"));
      trace.end("docreader",docOutput);
      if (isURL && (row.path("title").asText().isEmpty() || row.path("title").asText().equals(url))) {
        String title = read.metadata().getOrDefault("title", "");
        if (!title.isEmpty()) { row.put("title", title); repository.updateRemoteMetadata(job, snapshot); }
      }
      if (read.isAudio() || !read.imageRefs().isEmpty()) {
        trace.begin("multimodal",json.createObjectNode().put("image_count",read.imageRefs().size()));
        throw new TaskFailure("Native audio/image enrichment processing is not migrated", false);
      }
      trace.begin("chunking",null);
      String markdown =
          HtmlTableNormalizer.normalize(
              AdaptiveChunker.normalizeLineEndings(IndexText.clean(read.markdownContent())));
      SourceLocatorIndex sourceIndex =
          sources.build(read, markdown, isURL ? "url" : type, row.path("file_hash").asText(), bytes);
      var chunks =
          DocumentChunks.create(
              markdown,
              effective.path("chunking_config"),
              (start, end) -> sourceIndex.locators(start, end));
      trace.begin("chunking",json.createObjectNode().put("chunks_planned",chunks.size()));
      List<DocumentChunks.Stored> text =
          chunks.stream().filter(chunk -> chunk.type().equals("text")).toList();
      try {requireSupportedEnrichment(kb, effective, !text.isEmpty(),summaryNative,questionNative,graphNative,autoTagNative,profileNative);}
      catch(TaskFailure error) {trace.begin("postprocess",json.createObjectNode().put("summary_enabled",effective.path("summary_enabled").asBoolean()).put("graph_enabled",effective.path("graph_enabled").asBoolean()).put("wiki_enabled",kb.path("indexing_strategy").path("wiki_enabled").asBoolean()));throw error;}
      IndexWriter writer = indexes.resolve(job.tenantId(), kb);
      List<IndexEntry> entries = new ArrayList<>();
      Map<String, float[]> vectors = new LinkedHashMap<>();
      JsonNode strategy = kb.path("indexing_strategy");
      boolean needsEmbedding =
          strategy.path("vector_enabled").asBoolean()
              || strategy.path("keyword_enabled").asBoolean();
      trace.begin("embedding",json.createObjectNode().put("chunks_to_embed",text.size()).put("model_id",kb.path("embedding_model_id").asText()).put("vector_enabled",strategy.path("vector_enabled").asBoolean()).put("keyword_enabled",strategy.path("keyword_enabled").asBoolean()));
      DocumentProcessingRepository.Replacement replacement;
      if (needsEmbedding && !text.isEmpty()) {
        EmbeddingModel model;
        try {
          model = embeddings.resolve(job.tenantId(), kb.path("embedding_model_id").asText());
        } catch (RuntimeException error) {
          repository.failed(job, snapshot, "failed to get embedding model");
          trace.fail("embedding",embeddingFailureCode(error,"EMBEDDING_PROVIDER_FAIL"),"failed to get embedding model",error);
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
        cleanupGraph(job,snapshot,cancellation);
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
            trace.fail("embedding",embeddingFailureCode(error,"EMBEDDING_PROVIDER_FAIL"),"Embedding/indexing failed",error);
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
      } else {replacement = repository.captureReplacement(job, snapshot, writer);cleanupGraph(job,snapshot,cancellation);}
      cancellation.check();
      try {
        boolean saved = repository.save(job, snapshot, replacement, chunks, writer, entries, vectors, tasks);
        if(!saved) {
          var current=repository.load(job);
          if(current.isPresent()&&current.get().knowledge().path("parse_status").asText().equals("failed"))trace.failActive("STORAGE_QUOTA_EXCEEDED",current.get().knowledge().path("error_message").asText(),null);
          else trace.abort("TASK_SUPERSEDED","knowledge_no_longer_current_or_quota");
          return skipped("knowledge_no_longer_current_or_quota");
        }
        // These stages include publication: never assert chunks/indexes written before commit.
        trace.end("chunking",json.createObjectNode().put("chunks_written",chunks.size()).put("total_text_chars",chunks.stream().mapToLong(chunk->chunk.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).sum()));
        if(entries.isEmpty())trace.skip("embedding","skipped");
        else {
          var output=json.createObjectNode().put("vectors_written",entries.size());
          try {repository.load(job).ifPresent(current->output.put("storage_bytes",current.knowledge().path("storage_size").asLong()));}catch(Exception ignored){}
          trace.end("embedding",output);
        }
        trace.skip("multimodal","skipped");
        if(text.isEmpty()) {trace.skip("postprocess","no text chunks");trace.finish(json.createObjectNode().put("chunks",0));}
        return json.createObjectNode().put("indexed",true).put("chunks",text.size());
      } catch (CancellationException error) {
        throw error;
      } catch (Exception error) {
        Throwable diagnostic=error;
        while(diagnostic.getCause()!=null && diagnostic.getCause()!=diagnostic) diagnostic=diagnostic.getCause();
        org.slf4j.LoggerFactory.getLogger(DocumentProcessingHandler.class).warn(
            "Document publication failed: type={}, SQL state={}, vendor code={}, statement={}, call sites={}",
            diagnostic.getClass().getSimpleName(),
            diagnostic instanceof java.sql.SQLException sql?sql.getSQLState():"",
            diagnostic instanceof java.sql.SQLException sql?sql.getErrorCode():0,
            error instanceof org.springframework.jdbc.UncategorizedSQLException sql?sql.getSql():"",
            Arrays.stream(diagnostic.getStackTrace()).limit(24).map(frame->frame.getClassName()+"."+frame.getMethodName()+":"+frame.getLineNumber()).toList());
        if (cancellation.isCanceled() || Thread.currentThread().isInterrupted())
          throw new CancellationException("Indexing interrupted");
        if (job.attempts() >= job.maxAttempts())
          repository.failed(job, snapshot, "Document indexing or postprocess enqueue failed");
        trace.failActive(embeddingFailureCode(error,"VECTORSTORE_WRITE_FAILED"),"Document indexing or postprocess enqueue failed",error);
        throw new TaskFailure("Document indexing or postprocess enqueue failed", true);
      }
    } catch (CancellationException error) {
      // A lost/reclaimed lease may already have a successor using this attempt.
      // Only the explicit business cancellation or source supersession closes its trace.
      closeCanceledTrace(job,cancellation,trace);
      throw error;
    } catch (TaskFailure error) {
      if (!error.retryable()) repository.failed(job, snapshot, error.getMessage());
      String code=error.getMessage().contains("not migrated")?"UNKNOWN":switch(trace.active()){case "docreader"->"DOCREADER_UNAVAILABLE";case "chunking"->"CHUNKING_FAILED";case "embedding"->"EMBEDDING_PROVIDER_FAIL";case "multimodal"->"MULTIMODAL_VLM_FAILED";default->"UNKNOWN";};
      trace.failActive(code,error.getMessage(),error);
      throw error;
    } catch (Exception error) {
      trace.failActive("PROCESSING_FAILED","Document processing failed",error);
      throw error;
    }
  }

  private void requireRemoteSources() throws TaskFailure {
    if (downloads == null || downloadedFiles == null)
      throw new TaskFailure("Remote document sources are not configured", false);
  }

  private void cleanupGraph(TaskRecord job,DocumentProcessingRepository.Snapshot snapshot,TaskCancellation cancellation){if(graphCleanup==null||!graphCleanup.enabled())return;Runnable guard=()->{cancellation.check();repository.checkCurrent(job,snapshot);};guard.run();try(var call=CallContext.timeout(Duration.ofSeconds(30))){try{graphCleanup.delete(snapshot.knowledge().path("knowledge_base_id").asText(),snapshot.knowledge().path("id").asText(),call,guard);}catch(CancellationException error){throw error;}catch(RuntimeException error){guard.run();org.slf4j.LoggerFactory.getLogger(DocumentProcessingHandler.class).warn("Document graph cleanup failed; continuing document processing: {}",error.getClass().getSimpleName());}}}

  private void closeCanceledTrace(TaskRecord job,TaskCancellation cancellation,KnowledgeProcessingSpanPipeline trace) {
    try {
      var current=repository.load(job);
      if(current.isPresent()&&Set.of("cancelled","deleting").contains(current.get().knowledge().path("parse_status").asText()))trace.finishStatus(current.get().knowledge(),"user cancelled");
      else if(trace.superseded()||cancellation.reason()!=TaskCancellation.Reason.LEASE_LOST)trace.abort("TASK_CANCELLED","document processing cancelled");
    }catch(Exception ignored){}
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
    requireSupportedEnrichment(kb,effective,text,false);
  }
  static void requireSupportedEnrichment(JsonNode kb, JsonNode effective, boolean text,boolean summaryNative)
      throws TaskFailure {
    requireSupportedEnrichment(kb,effective,text,summaryNative,false);
  }
  static void requireSupportedEnrichment(JsonNode kb,JsonNode effective,boolean text,boolean summaryNative,boolean questionNative)throws TaskFailure {
    requireSupportedEnrichment(kb,effective,text,summaryNative,questionNative,false);
  }
  static void requireSupportedEnrichment(JsonNode kb,JsonNode effective,boolean text,boolean summaryNative,boolean questionNative,boolean graphNative)throws TaskFailure {
    requireSupportedEnrichment(kb,effective,text,summaryNative,questionNative,graphNative,false);
  }
  static void requireSupportedEnrichment(JsonNode kb,JsonNode effective,boolean text,boolean summaryNative,boolean questionNative,boolean graphNative,boolean autoTagNative)throws TaskFailure {
    requireSupportedEnrichment(kb,effective,text,summaryNative,questionNative,graphNative,autoTagNative,false);
  }
  static void requireSupportedEnrichment(JsonNode kb,JsonNode effective,boolean text,boolean summaryNative,boolean questionNative,boolean graphNative,boolean autoTagNative,boolean profileNative)throws TaskFailure {
    if (!text) return;
    if (effective.path("summary_enabled").asBoolean()&&!summaryNative)
      throw new TaskFailure("Native summary generation is not migrated", false);
    if (!questionNative&&effective.path("question_generation_config").path("enabled").asBoolean()
        && (kb.path("indexing_strategy").path("vector_enabled").asBoolean()
            || kb.path("indexing_strategy").path("keyword_enabled").asBoolean()))
      throw new TaskFailure("Native question generation is not migrated", false);
    if (!graphNative&&effective.path("graph_enabled").asBoolean())
      throw new TaskFailure("Native graph extraction is not migrated", false);
    if (kb.path("indexing_strategy").path("wiki_enabled").asBoolean())
      throw new TaskFailure("Native wiki ingestion is not migrated", false);
    if (!autoTagNative&&kb.path("auto_tag_config").path("enabled").asBoolean())
      throw new TaskFailure("Native automatic knowledge tagging is not migrated", false);
    if (kb.path("profile_config").path("enabled").asBoolean()&&!profileNative)
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

  private static String embeddingFailureCode(Throwable error,String fallback) {
    String message=Objects.toString(error.getMessage(),"").toLowerCase(Locale.ROOT);
    for(String needle:List.of("rate limit","ratelimit","429","too many requests","quota"))if(message.contains(needle))return "EMBEDDING_RATE_LIMIT";
    return fallback;
  }
}
