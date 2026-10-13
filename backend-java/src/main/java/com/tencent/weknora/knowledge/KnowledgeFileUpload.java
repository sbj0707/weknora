package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.security.ApiException;
import com.tencent.weknora.storage.FileStore;
import java.io.*;
import java.security.*;
import java.time.*;
import java.util.*;

/**
 * Native upload domain orchestration. HTTP authorization and JSON-content prevalidation belong to
 * its caller.
 */
public final class KnowledgeFileUpload {
  @FunctionalInterface
  public interface Observer {
    void enqueued(Command command, ObjectNode row, String taskId, boolean accepted);
  }

  /** Upload bodies must be reopenable: Go hashes before opening again for storage. */
  @FunctionalInterface
  public interface Content {
    InputStream open() throws IOException;
  }

  @FunctionalInterface
  public interface Storage {
    FileStore resolve(long ownerTenant, String kb) throws IOException;
  }

  /**
   * An adapter must preserve the queue, retry budget and per-task timeout. Enqueue returning
   * successfully means durable acceptance; this interface does not implement document processing.
   */
  @FunctionalInterface
  public interface Enqueuer {
    String enqueue(long ownerTenant, String type, ObjectNode payload, TaskOptions options)
        throws Exception;
  }

  /** maxRetries excludes the initial attempt, matching Asynq MaxRetry. */
  public record TaskOptions(String queue, int maxRetries, Duration timeout) {}

  public record Command(
      long tenant,
      String kb,
      String filename,
      long size,
      Content content,
      Map<String, String> metadata,
      Boolean multimodel,
      String customFilename,
      List<String> tags,
      String channel,
      ObjectNode processConfig,
      String language,
      String requestId) {
    public Command {
      if (tenant <= 0
          || kb == null
          || kb.isEmpty()
          || filename == null
          || size < 0
          || content == null)
        throw new IllegalArgumentException("authorized upload owner required");
      tags = tags == null ? List.of() : List.copyOf(tags);
      customFilename = Objects.toString(customFilename, "");
      channel = Objects.toString(channel, "");
      language = Objects.toString(language, "");
      requestId = Objects.toString(requestId, "");
    }
  }

  private final KnowledgeRepository repository;
  private final KnowledgeProcessConfig process;
  private final ObjectMapper json;
  private final Storage storage;
  private final Enqueuer tasks;
  private final Clock clock;
  private final Duration documentTimeout;

  public KnowledgeFileUpload(
      KnowledgeRepository repository,
      ObjectMapper json,
      Storage storage,
      Enqueuer tasks,
      Clock clock) {
    this(repository, json, storage, tasks, clock, Duration.ofHours(2));
  }

  public KnowledgeFileUpload(
      KnowledgeRepository repository,
      ObjectMapper json,
      Storage storage,
      Enqueuer tasks,
      Clock clock,
      Duration documentTimeout) {
    this.repository = repository;
    this.json = json;
    process = new KnowledgeProcessConfig(json);
    this.storage = storage;
    this.tasks = tasks;
    this.clock = clock;
    this.documentTimeout =
        documentTimeout == null || documentTimeout.isNegative() || documentTimeout.isZero()
            ? Duration.ofHours(2)
            : documentTimeout;
  }

  /**
   * Go saves the original multipart header name; a custom name changes only the
   * display/folder/parse identity.
   */
  public ObjectNode create(Command command, long storageQuota, long storageUsed)
      throws IOException {
    return create(command, storageQuota, storageUsed, (c, row, taskId, accepted) -> {});
  }

  public ObjectNode create(Command command, long storageQuota, long storageUsed, Observer observer)
      throws IOException {
    String name = command.filename(), folder = "";
    if (!command.customFilename().isEmpty()) {
      var relative = KnowledgeImportNames.split(command.customFilename());
      folder = relative.folder();
      if (!relative.filename().isEmpty()) name = relative.filename();
    }
    if (KnowledgeProcessConfig.video(KnowledgeProcessConfig.fileType(name)))
      throw bad("暂不支持上传视频文件");
    ObjectNode kb = repository.knowledgeBase(command.tenant(), command.kb());
    if (kb.path("type").asText().equals("faq")) throw bad("FAQ 知识库不支持文件上传，请使用 FAQ 导入功能");
    if (!KnowledgeProcessConfig.supported(KnowledgeProcessConfig.fileType(name)))
      throw new IOException("unsupported file type");
    String hash = md5(command.content());
    String datasource = "", external = "";
    if (Set.of("gitlab", "confluence").contains(command.channel()) && command.metadata() != null) {
      datasource = Objects.toString(command.metadata().get("datasource_id"), "");
      external = Objects.toString(command.metadata().get("external_id"), "");
    }
    var duplicate =
        repository.duplicateFile(
            command.tenant(),
            command.kb(),
            new KnowledgeRepository.DuplicateCheck(
                name,
                KnowledgeProcessConfig.fileType(name),
                command.size(),
                hash,
                datasource,
                external));
    if (duplicate.isPresent()) {
      ObjectNode existing = duplicate.get();
      repository.touchDuplicate(command.tenant(), existing.path("id").asText(), clock.instant());
      String message = "File already exists: " + existing.path("file_name").asText();
      throw new ApiException(409, "duplicate_file", message)
          .withResponse(
              Map.of(
                  "success",
                  false,
                  "message",
                  message,
                  "code",
                  "duplicate_file",
                  "data",
                  existing));
    }
    if (storageQuota > 0 && storageUsed >= storageQuota)
      throw new IOException("Storage quota exceeded");
    var safe = KnowledgeImportNames.validate(name);
    if (!safe.valid()) throw validation("文件名包含非法字符");
    name = safe.value();
    if (!folder.isEmpty()) {
      var safeFolder = KnowledgeImportNames.validate(folder);
      if (!safeFolder.valid()) throw validation("文件夹路径包含非法字符");
      folder = KnowledgeImportNames.normalizeFolder(safeFolder.value());
    }
    ObjectNode effective =
        process.resolveFileImport(
            kb,
            command.processConfig(),
            command.multimodel(),
            KnowledgeProcessConfig.fileType(name));
    ObjectNode record = repository.blank();
    String id = UUID.randomUUID().toString();
    Instant now = clock.instant();
    record
        .put("id", id)
        .put("tenant_id", command.tenant())
        .put("knowledge_base_id", command.kb())
        .put("type", "file")
        .put("channel", command.channel().isEmpty() ? "web" : command.channel())
        .put("title", name)
        .put("file_name", name)
        .put("folder_path", folder)
        .put("file_type", KnowledgeProcessConfig.fileType(name))
        .put("file_size", command.size())
        .put("file_hash", hash)
        .put("parse_status", "pending")
        .put("enable_status", "disabled")
        .put("created_at", now.toString())
        .put("updated_at", now.toString())
        .put("embedding_model_id", kb.path("embedding_model_id").asText());
    if (command.metadata() != null) record.set("metadata", json.valueToTree(command.metadata()));
    if (command.processConfig() != null) {
      ObjectNode metadata =
          record.path("metadata").isObject()
              ? (ObjectNode) record.get("metadata")
              : json.createObjectNode();
      metadata.set("process_overrides", process.normalizeOverrides(command.processConfig()));
      record.set("metadata", metadata);
    }
    FileStore files = storage.resolve(command.tenant(), command.kb());
    String path;
    try (var input = command.content().open()) {
      path = files.saveFile(input, command.filename(), command.tenant(), id);
    }
    record.put("file_path", path);
    try {
      repository.create(command.tenant(), record);
    } catch (RuntimeException error) {
      try {
        files.delete(path);
      } catch (IOException cleanup) {
        error.addSuppressed(cleanup);
      }
      throw error;
    }
    repository.assignTags(command.tenant(), command.kb(), record, command.tags());
    ObjectNode payload = json.createObjectNode();
    payload
        .put("request_id", command.requestId())
        .put("tenant_id", command.tenant())
        .put("knowledge_id", id)
        .put("knowledge_base_id", command.kb())
        .put("file_path", path)
        .put("file_name", name)
        .put("file_type", KnowledgeProcessConfig.fileType(name))
        .put("enable_multimodel", effective.path("enable_multimodel").asBoolean())
        .put(
            "enable_question_generation",
            effective.path("question_generation_config").path("enabled").asBoolean());
    long questionCount =
        effective.path("question_generation_config").path("question_count").asLong();
    payload
        .put("question_count", questionCount <= 0 ? 3 : questionCount)
        .put("language", command.language().isEmpty() ? "zh-CN" : command.language());
    String processingTask;
    try {
      processingTask =
          tasks.enqueue(
              command.tenant(),
              "document:process",
              payload,
              new TaskOptions("default", 3, documentTimeout));
    } catch (Exception error) {
      if (error instanceof InterruptedException) Thread.currentThread().interrupt();
      try {
        repository.markEnqueueFailed(command.tenant(), record, clock.instant());
      } catch (RuntimeException ignored) {
      }
      observer.enqueued(command, record, "", false);
      return record;
    }
    observer.enqueued(command, record, processingTask, true);
    if (KnowledgeProcessConfig.dataTable(KnowledgeProcessConfig.fileType(name))) {
      ObjectNode summary =
          json.createObjectNode()
              .put("tenant_id", command.tenant())
              .put("knowledge_id", id)
              .put("summary_model", kb.path("summary_model_id").asText())
              .put("embedding_model", kb.path("embedding_model_id").asText());
      try {
        tasks.enqueue(
            command.tenant(),
            "datatable:summary",
            summary,
            new TaskOptions("summary", 3, Duration.ofMinutes(30)));
      } catch (Exception ignored) {
      }
    }
    return record;
  }

  private static String md5(Content source) throws IOException {
    try {
      MessageDigest hash = MessageDigest.getInstance("MD5");
      try (var input = source.open()) {
        byte[] buffer = new byte[8192];
        int n;
        while ((n = input.read(buffer)) != -1) hash.update(buffer, 0, n);
      }
      return HexFormat.of().formatHex(hash.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static ApiException bad(String message) {
    return new ApiException(400, "BAD_REQUEST", message).withAppCode(1000);
  }

  private static ApiException validation(String message) {
    return new ApiException(400, "VALIDATION", message).withAppCode(1010);
  }
}
