package com.tencent.weknora.knowledge;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.security.ApiException;
import com.tencent.weknora.storage.FileStore;
import com.tencent.weknora.storage.StorageResolver;
import com.tencent.weknora.task.TaskRepository;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;

class KnowledgeUploadCompatibilityTest {
  @TempDir Path temp;
  final ObjectMapper json = new ObjectMapper();
  JdbcTemplate jdbc;
  DriverManagerDataSource source;
  DataSourceTransactionManager manager;

  void database(int caseNo) throws Exception {
    source = new DriverManagerDataSource("jdbc:sqlite:" + temp.resolve("db-" + caseNo));
    source.setDriverClassName("org.sqlite.JDBC");
    jdbc = new JdbcTemplate(source);
    manager = new DataSourceTransactionManager(source);
    try (var connection = source.getConnection()) {
      for (String name :
          List.of(
              "000000_init",
              "000002_knowledge_folder_path",
              "000003_knowledge_base_auto_tag_config",
              "000008_processing_spans_and_pending_subtasks",
              "000010_knowledge_multi_tags",
              "000021_knowledge_profiles"))
        ScriptUtils.executeSqlScript(
            connection, new FileSystemResource("../migrations/sqlite/" + name + ".up.sql"));
    }
  }

  JsonNode fixture() throws Exception {
    try (var input = getClass().getResourceAsStream("/go-knowledge-upload.json")) {
      return json.readTree(input);
    }
  }

  @Test
  void actualGoServiceAndSQLiteRepositoryUploadLifecycleMatches() throws Exception {
    int index = 0;
    for (var row : fixture()) {
      database(index++);
      JsonNode input = row.path("input");
      boolean configured = input.path("configured").asBoolean();
      String type = input.path("kb_type").asText("document");
      jdbc.update(
          "INSERT INTO"
              + " knowledge_bases(id,tenant_id,name,type,embedding_model_id,summary_model_id,vlm_config,asr_config,question_generation_config)"
              + " VALUES('kb',1,'kb',?,'embedding','summary',?,?,?)",
          type,
          configured ? "{\"enabled\":true,\"model_id\":\"vlm\"}" : "{}",
          configured ? "{\"enabled\":true,\"model_id\":\"asr\"}" : "{}",
          "{\"enabled\":true,\"question_count\":4}");
      jdbc.update(
          "INSERT INTO"
              + " knowledge_tags(id,tenant_id,knowledge_base_id,name,seq_id,color,sort_order,created_at,updated_at)"
              + " VALUES('tag',1,'kb','Tag',1,'',0,?,?)",
          Instant.EPOCH.toString(),
          Instant.EPOCH.toString());
      jdbc.update(
          "INSERT INTO"
              + " knowledge_tags(id,tenant_id,knowledge_base_id,name,seq_id,color,sort_order,created_at,updated_at)"
              + " VALUES('other',1,'other-kb','Other',2,'',0,?,?)",
          Instant.EPOCH.toString(),
          Instant.EPOCH.toString());
      KnowledgeRepository repo =
          input.path("create_fail").asBoolean()
              ? new KnowledgeRepository(jdbc, json, manager) {
                @Override
                public void create(long tenant, ObjectNode value) {
                  throw new IllegalStateException("create unavailable");
                }
              }
              : new KnowledgeRepository(jdbc, json, manager) {
                @Override
                protected List<Map<String, Object>> loadTags(long tenant, String kb, String id) {
                  if (input.path("tag_read_fail").asBoolean())
                    throw new IllegalStateException("tag read unavailable");
                  return super.loadTags(tenant, kb, id);
                }
              };
      if (input.path("duplicate").asBoolean()) {
        ObjectNode existing = repo.blank();
        String hash =
            java.util.HexFormat.of()
                .formatHex(
                    java.security.MessageDigest.getInstance("MD5")
                        .digest(input.path("content").asText().getBytes(StandardCharsets.UTF_8)));
        long owner = input.path("existing_tenant").asLong(1);
        existing
            .put("id", "existing")
            .put("tenant_id", owner)
            .put("knowledge_base_id", "kb")
            .put("type", "file")
            .put("file_name", input.path("existing_name").asText("older.txt"))
            .put("file_type", input.path("existing_type").asText("TXT"))
            .put("file_size", input.path("existing_size").asLong())
            .put("file_hash", input.path("existing_hash").asText(hash))
            .put("parse_status", input.path("existing_status").asText("pending"))
            .put("created_at", Instant.ofEpochSecond(1).toString())
            .put("updated_at", Instant.ofEpochSecond(1).toString());
        if (input.has("existing_metadata"))
          existing.set("metadata", input.get("existing_metadata"));
        repo.create(owner, existing);
        if (input.path("existing_deleted").asBoolean())
          jdbc.update(
              "UPDATE knowledges SET deleted_at=? WHERE id='existing'",
              Instant.ofEpochSecond(1).toString());
      }
      var physical = new Physical(input.path("save_fail").asBoolean());
      List<ObjectNode> tasks = new ArrayList<>();
      var service =
          new KnowledgeFileUpload(
              repo,
              json,
              (tenant, kb) -> physical,
              (tenant, task, payload, options) -> {
                ObjectNode result = json.createObjectNode().put("type", task);
                result.set("payload", payload.deepCopy());
                ArrayNode opts = json.createArrayNode();
                opts.add(
                    json.createObjectNode()
                        .put("type", "queueOption")
                        .put("value", options.queue()));
                if (task.equals("document:process")) {
                  opts.add(
                      json.createObjectNode()
                          .put("type", "timeoutOption")
                          .put("value", options.timeout().toNanos()));
                  opts.add(
                      json.createObjectNode()
                          .put("type", "retryOption")
                          .put("value", options.maxRetries()));
                  opts.add(
                      json.createObjectNode()
                          .put("type", "retryOption")
                          .put("value", options.maxRetries()));
                } else {
                  opts.add(
                      json.createObjectNode()
                          .put("type", "retryOption")
                          .put("value", options.maxRetries()));
                  opts.add(
                      json.createObjectNode()
                          .put("type", "timeoutOption")
                          .put("value", options.timeout().toNanos()));
                }
                result.set("options", opts);
                tasks.add(result);
                if (input.path("enqueue_fail").asBoolean()
                    || input.path("summary_enqueue_fail").asBoolean() && tasks.size() == 2)
                  throw new IOException("enqueue unavailable");
                return "task";
              },
              Clock.fixed(Instant.parse("2026-10-13T01:00:00Z"), ZoneOffset.UTC));
      byte[] content = input.path("content").asText().getBytes(StandardCharsets.UTF_8);
      Map<String, String> metadata = null;
      if (input.has("metadata")) {
        metadata = new LinkedHashMap<>();
        var entries = input.get("metadata").fields();
        while (entries.hasNext()) {
          var e = entries.next();
          metadata.put(e.getKey(), e.getValue().asText());
        }
      }
      List<String> tags = new ArrayList<>();
      for (var tag : input.path("tag_ids")) tags.add(tag.asText());
      ObjectNode overrides =
          input.path("override").isObject() ? (ObjectNode) input.get("override") : null;
      var command =
          new KnowledgeFileUpload.Command(
              1,
              "kb",
              input.path("name").asText(),
              content.length,
              () -> new ByteArrayInputStream(content),
              metadata,
              null,
              input.path("custom").asText(),
              tags,
              input.path("channel").asText(),
              overrides,
              "",
              "");
      ObjectNode knowledge = null;
      String error = "";
      int appcode = 0, status = 0;
      try {
        knowledge =
            service.create(
                command,
                input.path("quota").asBoolean() || input.path("quota_below").asBoolean() ? 1 : 0,
                input.path("quota").asBoolean() ? 1 : 0);
      } catch (ApiException e) {
        error = e.getMessage();
        if (e.status() == 409 && e.response() != null)
          knowledge = (ObjectNode) e.response().get("data");
        else {
          appcode = e.appCode() == null ? 0 : e.appCode();
          status = e.status();
        }
      } catch (Exception e) {
        error = e.getMessage();
      }
      String id = knowledge == null ? physical.id : knowledge.path("id").asText();
      ObjectNode actual = json.createObjectNode();
      actual.set("input", input);
      actual.set("knowledge", knowledge == null ? NullNode.instance : knowledge);
      actual
          .put("error", error)
          .put("appcode", appcode)
          .put("status", status)
          .put("saved", physical.saved)
          .put("save_name", physical.name)
          .put("deleted", physical.deleted)
          .put(
              "count",
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM knowledges WHERE deleted_at IS NULL", Long.class));
      actual.set("tasks", tasks.isEmpty() ? NullNode.instance : json.valueToTree(tasks));
      ArrayNode persisted = json.createArrayNode();
      for (var record :
          jdbc.queryForList(
              "SELECT id,tenant_id FROM knowledges WHERE deleted_at IS NULL ORDER BY"
                  + " created_at,id"))
        persisted.add(
            repo.get(((Number) record.get("tenant_id")).longValue(), record.get("id").toString())
                .orElseThrow());
      actual.set("persisted", persisted);
      JsonNode normalized = json.readTree(json.writeValueAsBytes(clean(actual, id)));
      assertThat(normalized).as("Go upload case %s %s", index, input).isEqualTo(row);
    }
  }

  StorageResolver nativeStorage(boolean queue) throws Exception {
    database(999);
    jdbc.update(
        "INSERT INTO tenants(id,name,business,storage_engine_config)"
            + " VALUES(1,'owner','test','{}')");
    jdbc.update(
        "INSERT INTO knowledge_bases(id,tenant_id,name,type,embedding_model_id,summary_model_id)"
            + " VALUES('kb',1,'kb','document','embedding','summary')");
    if (queue)
      try (var connection = source.getConnection()) {
        ScriptUtils.executeSqlScript(
            connection, new FileSystemResource("migration/sql/java-tasks.sql"));
      }
    return new StorageResolver(
        jdbc,
        json,
        new MockEnvironment()
            .withProperty("LOCAL_STORAGE_BASE_DIR", temp.resolve("files").toString())
            .withProperty("STORAGE_TYPE", "local"));
  }

  KnowledgeFileUpload nativeService(KnowledgeRepository repo, StorageResolver storage) {
    var tasks = new TaskRepository(jdbc, json, manager);
    return new KnowledgeFileUpload(
        repo,
        json,
        (tenant, kb) -> storage.forKnowledgeBase(tenant, kb).files(),
        (tenant, type, payload, options) ->
            tasks
                .enqueue(
                    tenant,
                    type,
                    payload.path("knowledge_id").asText(),
                    payload,
                    options.maxRetries() + 1,
                    Duration.ZERO)
                .id(),
        Clock.fixed(Instant.parse("2026-10-13T01:00:00Z"), ZoneOffset.UTC));
  }

  KnowledgeFileUpload.Command command(String filename, String custom, ObjectNode overrides) {
    byte[] bytes = "hello,图".getBytes(StandardCharsets.UTF_8);
    return new KnowledgeFileUpload.Command(
        1,
        "kb",
        filename,
        bytes.length,
        () -> new ByteArrayInputStream(bytes),
        null,
        null,
        custom,
        List.of(),
        "",
        overrides,
        "",
        "");
  }

  long fileCount() throws Exception {
    Path files = temp.resolve("files");
    if (!Files.exists(files)) return 0;
    try (var paths = Files.walk(files)) {
      return paths.filter(Files::isRegularFile).count();
    }
  }

  @Test
  void nativeStorageRegistryKnowledgeAndSqlTaskPreserveSourceVsDisplayName() throws Exception {
    var storage = nativeStorage(true);
    var repo = new KnowledgeRepository(jdbc, json, manager);
    var record =
        nativeService(repo, storage)
            .create(
                command(
                    "original.txt",
                    "docs/spec/design.md",
                    json.createObjectNode().put("summary_enabled", false)),
                0,
                0);
    String id = record.path("id").asText(), ref = record.path("file_path").asText();
    assertThat(UUID.fromString(id)).isNotNull();
    assertThat(ref).startsWith("resource://");
    assertThat(record.path("file_name").asText()).isEqualTo("design.md");
    assertThat(record.path("folder_path").asText()).isEqualTo("docs/spec");
    assertThat(record.path("file_type").asText()).isEqualTo("md");
    var resource = storage.catalog().resolve(1, ref);
    assertThat(resource.originalName()).isEqualTo("original.txt");
    assertThat(resource.mimeType()).isEqualTo("text/plain; charset=utf-8");
    assertThat(resource.physicalPath()).endsWith(".txt");
    assertThat(resource.size()).isEqualTo(9);
    assertThat(storage.catalog().referencedByKnowledgeBase(1, "kb", ref)).isTrue();
    try (var stream = storage.forKnowledgeBase(1, "kb").files().open(ref)) {
      assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hello,图");
    }
    var jobs = new TaskRepository(jdbc, json, manager).list(1, 20);
    assertThat(jobs).hasSize(1);
    assertThat(jobs.getFirst().type()).isEqualTo("document:process");
    assertThat(jobs.getFirst().status()).isEqualTo("pending");
    assertThat(jobs.getFirst().maxAttempts()).isEqualTo(4);
    assertThat(jobs.getFirst().payload().path("file_path").asText()).isEqualTo(ref);
    assertThat(jobs.getFirst().payload().path("file_name").asText()).isEqualTo("design.md");
    assertThat(repo.get(2, id)).isEmpty();
    assertThat(fileCount()).isEqualTo(1);
  }

  @Test
  void databaseCreateFailureCompensatesRealFileAndRegisteredResource() throws Exception {
    var storage = nativeStorage(true);
    var repo =
        new KnowledgeRepository(jdbc, json, manager) {
          @Override
          public void create(long owner, ObjectNode value) {
            throw new IllegalStateException("create unavailable");
          }
        };
    assertThatThrownBy(
            () -> nativeService(repo, storage).create(command("note.txt", "", null), 0, 0))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("create unavailable");
    assertThat(fileCount()).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledges", Long.class)).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM resources WHERE deleted_at IS NULL", Long.class))
        .isZero();
    assertThat(new TaskRepository(jdbc, json, manager).list(1, 20)).isEmpty();
  }

  @Test
  void enclosingBusinessRollbackRemovesFileRegistryKnowledgeAndSqlJob() throws Exception {
    var storage = nativeStorage(true);
    var repo = new KnowledgeRepository(jdbc, json, manager);
    assertThatThrownBy(
            () ->
                new TransactionTemplate(manager)
                    .executeWithoutResult(
                        status -> {
                          try {
                            nativeService(repo, storage)
                                .create(command("note.txt", "", null), 0, 0);
                          } catch (IOException error) {
                            throw new UncheckedIOException(error);
                          }
                          throw new IllegalStateException("business rollback");
                        }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("business rollback");
    assertThat(fileCount()).isZero();
    for (String table : List.of("resources", "resource_bindings", "knowledges", "java_task_jobs"))
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class))
          .as(table)
          .isZero();
  }

  @Test
  void missingQueueSchemaKeepsReadableUploadedFileAndFailedKnowledge() throws Exception {
    var storage = nativeStorage(false);
    var repo = new KnowledgeRepository(jdbc, json, manager);
    var record = nativeService(repo, storage).create(command("note.txt", "", null), 0, 0);
    assertThat(record.path("parse_status").asText()).isEqualTo("failed");
    assertThat(record.path("error_message").asText())
        .isEqualTo("Failed to enqueue processing task");
    assertThat(repo.get(1, record.path("id").asText()).orElseThrow().path("parse_status").asText())
        .isEqualTo("failed");
    assertThat(fileCount()).isEqualTo(1);
    assertThat(storage.catalog().resolve(1, record.path("file_path").asText()).state())
        .isEqualTo("active");
  }

  @Test
  void sameBytesOnlyDeduplicateWithinTheSameFileTypeAndKeepExistingObject() throws Exception {
    var storage = nativeStorage(true);
    var repo = new KnowledgeRepository(jdbc, json, manager);
    var service = nativeService(repo, storage);
    var first = service.create(command("one.txt", "", null), 0, 0);
    assertThatThrownBy(() -> service.create(command("two.TXT", "", null), 0, 0))
        .isInstanceOf(ApiException.class)
        .hasMessage("File already exists: one.txt");
    var other = service.create(command("another.md", "", null), 0, 0);
    assertThat(other.path("id").asText()).isNotEqualTo(first.path("id").asText());
    assertThat(fileCount()).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledges", Long.class)).isEqualTo(2);
    assertThat(new TaskRepository(jdbc, json, manager).list(1, 20)).hasSize(2);
  }

  JsonNode clean(JsonNode node, String id) {
    if (node.isObject()) {
      ObjectNode out = (ObjectNode) node.deepCopy();
      List<String> keys = new ArrayList<>();
      out.fieldNames().forEachRemaining(keys::add);
      for (String key : keys)
        out.set(
            key,
            key.equals("created_at") || key.equals("updated_at")
                ? TextNode.valueOf("<time>")
                : clean(out.get(key), id));
      return out;
    }
    if (node.isArray()) {
      ArrayNode out = json.createArrayNode();
      for (var value : node) out.add(clean(value, id));
      return out;
    }
    if (node.isTextual()) {
      if (!id.isEmpty() && node.asText().equals(id)) return TextNode.valueOf("<id>");
      if (!id.isEmpty() && node.asText().equals("stored/" + id))
        return TextNode.valueOf("stored/<id>");
    }
    return node;
  }

  static final class Physical implements FileStore {
    final boolean fail;
    int saved, deleted;
    String name = "", id = "";

    Physical(boolean fail) {
      this.fail = fail;
    }

    public void checkConnectivity() {}

    public String saveFile(InputStream input, String filename, long tenant, String knowledge)
        throws IOException {
      saved++;
      name = filename;
      id = knowledge;
      if (fail) throw new IOException("storage unavailable");
      input.transferTo(OutputStream.nullOutputStream());
      return "stored/" + knowledge;
    }

    public String saveBytes(byte[] bytes, long tenant, String filename, boolean temporary) {
      throw new UnsupportedOperationException();
    }

    public InputStream open(String path) {
      throw new UnsupportedOperationException();
    }

    public String url(String path) {
      throw new UnsupportedOperationException();
    }

    public void delete(String path) {
      deleted++;
    }

    public String copy(String path, long tenant, String knowledge) {
      throw new UnsupportedOperationException();
    }
  }
}
