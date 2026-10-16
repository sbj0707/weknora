package com.tencent.weknora.knowledge;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.*;
import com.tencent.weknora.docreader.*;
import com.tencent.weknora.model.*;
import com.tencent.weknora.retrieval.*;
import com.tencent.weknora.security.*;
import com.tencent.weknora.task.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.env.MockEnvironment;

class DocumentProcessingCompatibilityTest {
  @TempDir Path temp;
  final ObjectMapper json = new ObjectMapper();
  DriverManagerDataSource source;
  JdbcTemplate jdbc;
  DataSourceTransactionManager manager;
  KnowledgeRepository knowledge;
  TaskRepository tasks;
  DocumentProcessingRepository processing;
  SQLiteIndexRepository index;
  final List<ObjectNode> enqueues = new ArrayList<>();

  void database(int caseNo, boolean vectors) throws Exception {
    source = new DriverManagerDataSource("jdbc:sqlite:" + temp.resolve("case-" + caseNo));
    source.setDriverClassName("org.sqlite.JDBC");
    jdbc = new JdbcTemplate(source);
    manager = new DataSourceTransactionManager(source);
    try (var c = source.getConnection()) {
      for (String migration :
          List.of(
              "000000_init",
              "000002_knowledge_folder_path",
              "000003_knowledge_base_auto_tag_config",
              "000007_system_admin_and_settings",
              "000008_processing_spans_and_pending_subtasks",
              "000010_knowledge_multi_tags",
              "000011_principal_model",
              "000021_knowledge_profiles",
              "000031_model_catalog_config",
              "000033_chunk_source_locators"))
        ScriptUtils.executeSqlScript(
            c, new FileSystemResource("../migrations/sqlite/" + migration + ".up.sql"));
      ScriptUtils.executeSqlScript(c, new FileSystemResource("migration/sql/java-tasks.sql"));
    }
    jdbc.update(
        "INSERT INTO tenants(id,name,business,storage_engine_config)"
            + " VALUES(1,'owner','test','{}'),(2,'foreign','test','{}')");
    jdbc.update(
        "INSERT INTO"
            + " knowledge_bases(id,tenant_id,name,type,embedding_model_id,summary_model_id,indexing_strategy)"
            + " VALUES('kb',1,'KB','document','embedding','summary',?)",
        "{\"vector_enabled\":"
            + vectors
            + ",\"keyword_enabled\":"
            + vectors
            + ",\"graph_enabled\":"
            + !vectors
            + ",\"wiki_enabled\":false}");
    knowledge = new KnowledgeRepository(jdbc, json, manager);
    tasks = new TaskRepository(jdbc, json, manager);
    processing =
        new DocumentProcessingRepository(jdbc, json, knowledge, manager, Clock.systemUTC());
    String path = System.getProperty("weknora.sqlite.vec.extension");
    assertThat(path).as("Explicit sqlite-vec0.1.6 test library").isNotEmpty();
    index = new SQLiteIndexRepository(source, new SQLiteVectorExtension(Path.of(path)));
    index.initialize();
    enqueues.clear();
  }

  ObjectNode seed(JsonNode input) throws Exception {
    ObjectNode row = knowledge.blank();
    row.put("id", "knowledge")
        .put("tenant_id", 1)
        .put("knowledge_base_id", "kb")
        .put("type", "file")
        .put("title", "  File title  ")
        .put("file_name", "document." + input.path("file_type").asText("txt"))
        .put("file_type", input.path("file_type").asText("txt"))
        .put("file_hash", "hash")
        .put("file_path", "stored")
        .put("parse_status", input.path("state").asText("pending"))
        .put("enable_status", "disabled")
        .put("created_at", Instant.now().toString())
        .put("updated_at", Instant.now().toString());
    if (input.has("state")) row.put("error_message", "old error");
    row.set(
        "metadata",
        json.createObjectNode()
            .set("process_overrides", json.createObjectNode().put("summary_enabled", false)));
    knowledge.create(1, row);
    ObjectNode config =
        json.createObjectNode()
            .put("chunk_size", 64)
            .put("chunk_overlap", 10)
            .put("strategy", input.path("strategy").asText())
            .put("enable_parent_child", input.path("parent").asBoolean())
            .put("parent_chunk_size", 128)
            .put("child_chunk_size", 32);
    config.set("separators", json.createArrayNode().add("\n\n").add("\n").add("。"));
    jdbc.update("UPDATE knowledge_bases SET chunking_config=? WHERE id='kb'", config.toString());
    return row;
  }

  TaskRecord job(ObjectNode row, String type) throws Exception {
    ObjectNode payload =
        json.createObjectNode()
            .put("tenant_id", 1)
            .put("knowledge_id", "knowledge")
            .put("knowledge_base_id", "kb")
            .put("file_path", row.path("file_path").asText())
            .put("file_name", row.path("file_name").asText())
            .put("file_type", row.path("file_type").asText())
            .put("language", "zh-CN");
    tasks.enqueue(
        1,
        type,
        UUID.randomUUID().toString(),
        payload,
        4,
        Duration.ZERO,
        type.equals("document:process") ? "default" : "postprocess",
        Duration.ofMinutes(30));
    return tasks
        .claim(
            "worker",
            Duration.ofMinutes(5),
            List.of(type.equals("document:process") ? "default" : "postprocess"))
        .orElseThrow();
  }

  TaskCancellation cancellation(TaskRecord job) throws Exception {
    var constructor =
        TaskCancellation.class.getDeclaredConstructor(TaskRepository.class, TaskRecord.class);
    constructor.setAccessible(true);
    return constructor.newInstance(tasks, job);
  }

  KnowledgeFileUpload.Enqueuer enqueuer() {
    return (owner, type, payload, options) -> {
      ObjectNode captured = json.createObjectNode().put("type", type);
      captured.set("payload", payload.deepCopy());
      captured.set(
          "options",
          json.createArrayNode()
              .add(json.createObjectNode().put("type", "queueOption").put("value", options.queue()))
              .add(
                  json.createObjectNode()
                      .put("type", "retryOption")
                      .put("value", options.maxRetries()))
              .add(
                  json.createObjectNode()
                      .put("type", "timeoutOption")
                      .put("value", options.timeout().toNanos())));
      enqueues.add(captured);
      return tasks
          .enqueue(
              owner,
              type,
              UUID.randomUUID().toString(),
              payload,
              options.maxRetries() + 1,
              Duration.ZERO,
              options.queue(),
              options.timeout())
          .id();
    };
  }

  DocumentProcessingHandler handler(
      DocumentProcessingHandler.Files files,
      DocumentProcessingHandler.Embeddings embeddings,
      IndexWriter writer) {
    return new DocumentProcessingHandler(
        processing,
        json,
        files,
        (engine, type) -> new SimpleFormatReader(),
        embeddings,
        (owner, kb) -> writer,
        NativeDocumentSources::build,
        enqueuer());
  }

  JsonNode snapshot() throws Exception {
    ObjectNode result = json.createObjectNode();
    ObjectNode row = knowledge.get(1, "knowledge").orElseThrow();
    for (String key : List.of("parse_status", "enable_status", "summary_status", "storage_size"))
      result.set(key, row.get(key));
    result
        .put("error", row.path("error_message").asText())
        .put("pending", row.path("pending_subtasks_count").asLong());
    List<Map<String, Object>> raw =
        jdbc.queryForList(
            "SELECT * FROM chunks WHERE knowledge_id='knowledge' ORDER BY chunk_type,chunk_index");
    Map<String, String> ids = new HashMap<>();
    ids.put("", "");
    for (var chunk : raw)
      ids.put(chunk.get("id").toString(), chunk.get("chunk_type") + "-" + chunk.get("chunk_index"));
    ArrayNode chunks = json.createArrayNode();
    for (var chunk : raw) {
      ObjectNode out =
          json.createObjectNode()
              .put("id", ids.get(chunk.get("id").toString()))
              .put("content", chunk.get("content").toString())
              .put("context_header", chunk.get("context_header").toString())
              .put("index", ((Number) chunk.get("chunk_index")).intValue())
              .put("start", ((Number) chunk.get("start_at")).intValue())
              .put("end", ((Number) chunk.get("end_at")).intValue())
              .put("type", chunk.get("chunk_type").toString())
              .put("parent", ids.get(Objects.toString(chunk.get("parent_chunk_id"), "")))
              .put("previous", ids.get(Objects.toString(chunk.get("pre_chunk_id"), "")))
              .put("next", ids.get(Objects.toString(chunk.get("next_chunk_id"), "")))
              .put("enabled", !chunk.get("is_enabled").toString().equals("0"))
              .put("status", ((Number) chunk.get("status")).intValue());
      out.set("tag_id", json.valueToTree(chunk.get("tag_id")));
      out.set(
          "source_locators",
          chunk.get("source_locators") == null
              ? NullNode.instance
              : json.readTree(chunk.get("source_locators").toString()));
      chunks.add(out);
    }
    result.set("chunks", chunks);
    return json.readTree(json.writeValueAsBytes(result));
  }

  @Test
  void actualGoDocumentAndPostprocessLifecyclePreservesChunksAndOriginalLocations()
      throws Exception {
    JsonNode fixtures =
        json.readTree(getClass().getResourceAsStream("/go-document-processing.json"));
    int count = 0;
    for (JsonNode fixture : fixtures) {
      JsonNode input = fixture.path("input");

      database(count++, false);
      ObjectNode row = seed(input);
      int[] reads = {0};
      var handler =
          handler(
              (owner, kb, path) -> {
                reads[0]++;
                if (input.path("read_fail").asBoolean()) throw new IOException("file unavailable");
                return new ByteArrayInputStream(
                    input.path("content").asText().getBytes(StandardCharsets.UTF_8));
              },
              (owner, id) -> {
                throw new AssertionError("disabled embedding must not resolve a model");
              },
              index);
      TaskRecord job = job(row, "document:process");
      jdbc.update("UPDATE java_task_jobs SET max_attempts=1 WHERE id=?", job.id());
      String currentId = job.id();
      job =
          tasks.list(1, 10).stream()
              .filter(t -> t.id().equals(currentId))
              .findFirst()
              .orElseThrow();
      if (input.path("stale_source").asBoolean()) {
        ObjectNode payload = (ObjectNode) job.payload();
        payload.put("file_path", "old-source");
        jdbc.update("UPDATE java_task_jobs SET payload=? WHERE id=?", payload.toString(), job.id());
        String jobId = job.id();
        job =
            tasks.list(1, 10).stream()
                .filter(task -> task.id().equals(jobId))
                .findFirst()
                .orElseThrow();
      }
      String error = "";
      try {
        handler.execute(job, cancellation(job));
      } catch (TaskFailure failure) {
        error = failure.getMessage();
      }
      ObjectNode expectedBefore = (ObjectNode) fixture.get("before_post").deepCopy(),
          expectedAfter = (ObjectNode) fixture.get("after_post").deepCopy();
      // Go stores the original file-service diagnostic. Native public state deliberately uses a
      // stable message to avoid persisting storage URLs or credentials.
      if (input.path("read_fail").asBoolean()) {
        expectedBefore.put("error", "failed to get file");
        expectedAfter.put("error", "failed to get file");
      }
      JsonNode before = snapshot();
      tasks.complete(job, json.createObjectNode());
      for (TaskRecord post : tasks.list(1, 10))
        if (post.type().equals("knowledge:post_process")) {
          TaskRecord lease =
              tasks.claim("post", Duration.ofMinutes(5), List.of("postprocess")).orElseThrow();
          new KnowledgePostProcessHandler(processing, json).execute(lease, cancellation(lease));
          tasks.complete(lease, json.createObjectNode());
        }
      assertThat(before).as("before post Go case %s", input).isEqualTo(expectedBefore);
      assertThat(snapshot()).as("after post Go case %s", input).isEqualTo(expectedAfter);
      assertThat(reads[0]).isEqualTo(fixture.path("read_calls").asInt());
      assertThat(json.readTree(json.writeValueAsBytes(enqueues.isEmpty() ? null : enqueues)))
          .isEqualTo(fixture.get("tasks"));
      assertThat(error)
          .isEqualTo(
              input.path("read_fail").asBoolean()
                  ? "failed to get file"
                  : fixture.path("error").asText());
    }
    assertThat(count).isEqualTo(24);
  }

  @Test
  void actualNetworkEmbeddingWritesGoVecAndFtsAndPostprocessCompletes() throws Exception {
    database(100, true);
    ObjectNode row =
        seed(json.createObjectNode().put("content", "Hello world").put("file_type", "txt"));
    List<JsonNode> requests = new CopyOnWriteArrayList<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/embeddings",
        exchange -> {
          JsonNode body = json.readTree(exchange.getRequestBody());
          requests.add(body);
          ArrayNode data = json.createArrayNode();
          for (int i = body.path("input").size() - 1; i >= 0; i--)
            data.add(
                json.createObjectNode()
                    .put("index", i)
                    .set("embedding", json.createArrayNode().add(1).add(0).add(0)));
          byte[] response =
              json.createObjectNode().set("data", data).toString().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, response.length);
          try (var out = exchange.getResponseBody()) {
            out.write(response);
          }
          exchange.close();
        });
    server.start();
    MockEnvironment env =
        new MockEnvironment()
            .withProperty("SSRF_WHITELIST", "127.0.0.1")
            .withProperty("SSRF_DNS_WHITELIST_ONLY", "true");
    ModelClientFactory factory =
        new ModelClientFactory(
            jdbc,
            json,
            new GoSecretCodec(""),
            new ModelCatalogRuntime(jdbc, "", "/nonexistent"),
            new ModelEgressPolicy(jdbc, json, env),
            env);
    try {
      ObjectNode params =
          json.createObjectNode()
              .put("provider", "generic")
              .put("base_url", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
      params.set("embedding_parameters", json.createObjectNode().put("dimension", 3));
      jdbc.update(
          "INSERT INTO models(id,tenant_id,name,type,source,parameters,is_builtin,status)"
              + " VALUES('embedding',1,'unknown','Embedding','remote',?,false,'active')",
          params.toString());
      var handler =
          handler(
              (owner, kb, path) ->
                  new ByteArrayInputStream("Hello world".getBytes(StandardCharsets.UTF_8)),
              (owner, id) ->
                  factory.embedding(
                      new AuthContext(
                          "system-task", owner, "viewer", false, null, false, Set.of(), Set.of()),
                      id),
              index);
      TaskRecord job = job(row, "document:process");
      handler.execute(job, cancellation(job));
      assertThat(knowledge.get(1, "knowledge").orElseThrow().path("parse_status").asText())
          .isEqualTo("processing");
      assertThat(requests).hasSize(1);
      assertThat(requests.getFirst().path("input").get(0).asText())
          .isEqualTo("File title\nHello world");
      assertThat(jdbc.queryForObject("SELECT content FROM lite_embeddings", String.class))
          .isEqualTo("File title\nHello world");
      assertThat(
              jdbc.execute(
                  (org.springframework.jdbc.core.ConnectionCallback<Integer>)
                      c -> {
                        new SQLiteVectorExtension(
                                Path.of(System.getProperty("weknora.sqlite.vec.extension")))
                            .load(c);
                        try (var statement = c.createStatement();
                            var result =
                                statement.executeQuery("SELECT COUNT(*) FROM vec_embeddings_3")) {
                          return result.getInt(1);
                        }
                      }))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM lite_embeddings_fts WHERE lite_embeddings_fts MATCH"
                      + " '\"hello\"'",
                  Integer.class))
          .isEqualTo(1);
      tasks.complete(job, json.createObjectNode());
      TaskRecord post =
          tasks.claim("post", Duration.ofMinutes(5), List.of("postprocess")).orElseThrow();
      new KnowledgePostProcessHandler(processing, json).execute(post, cancellation(post));
      assertThat(knowledge.get(1, "knowledge").orElseThrow().path("parse_status").asText())
          .isEqualTo("completed");
      assertThat(jdbc.queryForObject("SELECT storage_used FROM tenants WHERE id=1", Long.class))
          .isEqualTo(knowledge.get(1, "knowledge").orElseThrow().path("storage_size").asLong());
    } finally {
      factory.close();
      server.stop(0);
    }
  }

  @Test
  void documentAndDurablePostprocessEnqueueShareOnePhysicalConnection() throws Exception {
    database(160,true);
    try(var pool=new com.zaxxer.hikari.HikariDataSource()) {
      pool.setJdbcUrl(source.getUrl());pool.setMaximumPoolSize(1);pool.setConnectionTimeout(1000);
      jdbc=new JdbcTemplate(pool);manager=new DataSourceTransactionManager(pool);
      knowledge=new KnowledgeRepository(jdbc,json,manager);tasks=new TaskRepository(jdbc,json,manager);
      processing=new DocumentProcessingRepository(jdbc,json,knowledge,manager,Clock.systemUTC());
      index=new SQLiteIndexRepository(pool,new SQLiteVectorExtension(Path.of(System.getProperty("weknora.sqlite.vec.extension"))));index.initialize();
      var row=seed(json.createObjectNode().put("file_type","txt"));
      var job=job(row,"document:process");
      var model=new com.tencent.weknora.model.client.EmbeddingModel() {
        public String modelId(){return "embedding";}public String modelName(){return "test";}public int dimensions(){return 3;}
        public float[][] embed(com.tencent.weknora.model.client.CallContext call,List<String> texts,boolean query) {
          float[][] values=new float[texts.size()][];for(int i=0;i<values.length;i++)values[i]=new float[]{1,0,0};return values;
        }
      };
      var result=handler((owner,kb,path)->new ByteArrayInputStream("Hello world".getBytes(StandardCharsets.UTF_8)),(owner,id)->model,index).execute(job,cancellation(job));
      assertThat(result.path("indexed").asBoolean()).isTrue();
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM java_task_jobs WHERE type='knowledge:post_process'",Integer.class)).isEqualTo(1);
    }
  }

  @Test
  void sourceReplacementAndCanceledJobDuringEmbeddingCannotWriteChunksOrIndex() throws Exception {
    for (boolean replaced : List.of(false, true)) {
      database(replaced ? 201 : 200, true);
      ObjectNode row = seed(json.createObjectNode().put("file_type", "txt"));
      com.tencent.weknora.model.client.EmbeddingModel model =
          new com.tencent.weknora.model.client.EmbeddingModel() {
            public String modelId() {
              return "embedding";
            }

            public String modelName() {
              return "model";
            }

            public int dimensions() {
              return 3;
            }

            public float[][] embed(
                com.tencent.weknora.model.client.CallContext call,
                List<String> texts,
                boolean query) {
              if (replaced)
                jdbc.update("UPDATE knowledges SET file_path='new-source' WHERE id='knowledge'");
              else
                jdbc.update(
                    "UPDATE java_task_jobs SET cancel_requested=1 WHERE type='document:process'");
              return new float[][] {{1, 0, 0}};
            }
          };
      TaskRecord job = job(row, "document:process");
      var handler =
          handler(
              (owner, kb, path) ->
                  new ByteArrayInputStream("body".getBytes(StandardCharsets.UTF_8)),
              (owner, id) -> model,
              index);
      try {
        handler.execute(job, cancellation(job));
      } catch (CancellationException expected) {
        assertThat(replaced).isFalse();
      }
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chunks", Integer.class)).isZero();
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lite_embeddings", Integer.class))
          .isZero();
      assertThat(enqueues).isEmpty();
    }
  }

  @Test
  void indexWriteFailureRollsBackChunksIndexAndDurablePostprocessJob() throws Exception {
    database(300, false);
    ObjectNode row = seed(json.createObjectNode().put("file_type", "txt"));
    IndexWriter failing =
        new IndexWriter() {
          public void prepare(Set<Integer> dims) {}

          public long estimateStorageSize(List<IndexEntry> rows, Map<String, float[]> vectors) {
            return 0;
          }

          public void batchSave(
              java.sql.Connection c, List<IndexEntry> rows, Map<String, float[]> vectors) {}

          public List<String> sourceIdsByKnowledge(java.sql.Connection c, List<String> ids) {
            return List.of();
          }

          public void deleteByKnowledge(java.sql.Connection c, List<String> ids) {}

          public void deleteByChunk(java.sql.Connection c, List<String> ids) {}

          public void deleteBySource(java.sql.Connection c, List<String> ids)
              throws java.sql.SQLException {
            throw new java.sql.SQLException("index unavailable");
          }
        };
    TaskRecord job = job(row, "document:process");
    assertThatThrownBy(
            () ->
                handler(
                        (owner, kb, path) ->
                            new ByteArrayInputStream("body".getBytes(StandardCharsets.UTF_8)),
                        (owner, id) -> {
                          throw new AssertionError();
                        },
                        failing)
                    .execute(job, cancellation(job)))
        .isInstanceOf(TaskFailure.class)
        .hasMessage("Document indexing or postprocess enqueue failed");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chunks", Integer.class)).isZero();
    assertThat(tasks.list(1, 20)).hasSize(1);
    assertThat(knowledge.get(1, "knowledge").orElseThrow().path("parse_status").asText())
        .isEqualTo("processing");
  }

  @Test
  void unsupportedSummaryCannotClaimCompletion() throws Exception {
    database(400, false);
    ObjectNode row = seed(json.createObjectNode().put("file_type", "txt"));
    jdbc.update("UPDATE knowledges SET metadata='{}' WHERE id='knowledge'");
    TaskRecord job = job(row, "document:process");
    assertThatThrownBy(
            () ->
                handler(
                        (owner, kb, path) ->
                            new ByteArrayInputStream("body".getBytes(StandardCharsets.UTF_8)),
                        (owner, id) -> {
                          throw new AssertionError();
                        },
                        index)
                    .execute(job, cancellation(job)))
        .isInstanceOf(TaskFailure.class)
        .hasMessage("Native summary generation is not migrated");
    assertThat(knowledge.get(1, "knowledge").orElseThrow().path("parse_status").asText())
        .isEqualTo("failed");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chunks", Integer.class)).isZero();
    assertThat(enqueues).isEmpty();
  }

  @Test
  void sourceOrLeaseChangedInsideIndexWriteRollsBackEntireProcessingTransaction() throws Exception {
    for (boolean sourceChanged : List.of(false, true)) {
      database(sourceChanged ? 601 : 600, false);
      ObjectNode row = seed(json.createObjectNode().put("file_type", "txt"));
      TaskRecord job = job(row, "document:process");
      IndexWriter callback =
          new IndexWriter() {
            public void prepare(Set<Integer> dimensions) throws java.sql.SQLException {
              index.prepare(dimensions);
            }

            public long estimateStorageSize(List<IndexEntry> rows, Map<String, float[]> vectors) {
              return index.estimateStorageSize(rows, vectors);
            }

            public void batchSave(
                java.sql.Connection tx, List<IndexEntry> rows, Map<String, float[]> vectors)
                throws java.sql.SQLException {
              index.batchSave(tx, rows, vectors);
            }

            public List<String> sourceIdsByKnowledge(java.sql.Connection tx, List<String> ids)
                throws java.sql.SQLException {
              return index.sourceIdsByKnowledge(tx, ids);
            }

            public void deleteByKnowledge(java.sql.Connection tx, List<String> ids)
                throws java.sql.SQLException {
              index.deleteByKnowledge(tx, ids);
            }

            public void deleteBySource(java.sql.Connection tx, List<String> ids)
                throws java.sql.SQLException {
              index.deleteBySource(tx, ids);
              try (var statement =
                  tx.prepareStatement(
                      sourceChanged
                          ? "UPDATE knowledges SET file_path='changed' WHERE id='knowledge'"
                          : "UPDATE java_task_jobs SET lease_until=0 WHERE id=?")) {
                if (!sourceChanged) statement.setString(1, job.id());
                statement.executeUpdate();
              }
            }

            public void deleteByChunk(java.sql.Connection tx, List<String> ids)
                throws java.sql.SQLException {
              index.deleteByChunk(tx, ids);
            }

          };
      assertThatThrownBy(
              () ->
                  handler(
                          (o, k, path) ->
                              new ByteArrayInputStream("body".getBytes(StandardCharsets.UTF_8)),
                          (o, id) -> {
                            throw new AssertionError();
                          },
                          callback)
                      .execute(job, cancellation(job)))
          .isInstanceOf(CancellationException.class);
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chunks", Integer.class)).isZero();
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lite_embeddings", Integer.class))
          .isZero();
      assertThat(tasks.list(1, 10)).hasSize(1);
      assertThat(knowledge.get(1, "knowledge").orElseThrow().path("file_path").asText())
          .isEqualTo("stored");
      assertThat(tasks.get(1, job.id()).orElseThrow().leaseUntil()).isEqualTo(job.leaseUntil());
    }
  }

  @Test
  void tableMetadataPublishedDuringEmbeddingSurvivesDocumentReplacement() throws Exception {
    // Reparse clears the previous attempt before enqueue. Current table metadata
    // survives either worker order; source-only orphan indexes retain snapshot cleanup.
    for (boolean duringEmbedding : List.of(false, true)) {
      database(duringEmbedding ? 702 : 701, true);
      ObjectNode row = seed(json.createObjectNode().put("file_type", "csv"));
      index.prepare(Set.of(3));
      publishTableMetadata("old");
      new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(tx -> {
        jdbc.update("DELETE FROM chunks WHERE tenant_id=1 AND knowledge_id='knowledge'");
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) c -> {
          index.deleteBySource(c, List.of("old-summary", "old-column", "old-orphan"));
          return null;
        });
      });
      Runnable publish = () -> publishTableMetadata("parallel");
      var model = new com.tencent.weknora.model.client.EmbeddingModel() {
        public String modelId() { return "embedding"; }
        public String modelName() { return "model"; }
        public int dimensions() { return 3; }
        public float[][] embed(com.tencent.weknora.model.client.CallContext call,
            List<String> texts, boolean query) {
          if (duringEmbedding) publish.run();
          return new float[][] {{1, 0, 0}};
        }
      };
      TaskRecord job = job(row, "document:process");
      handler((owner, kb, path) -> {
        if (!duringEmbedding) publish.run();
        return new ByteArrayInputStream("body".getBytes(StandardCharsets.UTF_8));
      }, (owner, id) -> model, index).execute(job, cancellation(job));
      List<String> ids = jdbc.queryForList("SELECT id FROM chunks", String.class);
      assertThat(ids).doesNotContain("old-summary", "old-column");
      List<String> indexIds = jdbc.queryForList("SELECT source_id FROM lite_embeddings", String.class);
      assertThat(indexIds).doesNotContain("old-summary", "old-column", "old-orphan");
      assertThat(ids).contains("parallel-summary", "parallel-column").hasSize(3);
      assertThat(indexIds).contains("parallel-summary", "parallel-column")
          .hasSize(duringEmbedding ? 4 : 3);
      if (duringEmbedding) assertThat(indexIds).contains("parallel-orphan");
      else assertThat(indexIds).doesNotContain("parallel-orphan");
      assertThat(jdbc.queryForObject("SELECT parent_chunk_id FROM chunks WHERE id='parallel-column'",
          String.class)).isEqualTo("parallel-summary");
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lite_embeddings_fts WHERE lite_embeddings_fts"
          + " MATCH '\"metadata\"'", Integer.class)).isEqualTo(duringEmbedding ? 3 : 2);
      assertThat(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Integer>) c -> {
        new SQLiteVectorExtension(Path.of(System.getProperty("weknora.sqlite.vec.extension"))).load(c);
        try (var statement = c.createStatement();
            var result = statement.executeQuery("SELECT COUNT(*) FROM vec_embeddings_3")) {
          return result.getInt(1);
        }
      })).isEqualTo(duringEmbedding ? 4 : 3);
      assertThat(enqueues).hasSize(1);
    }
  }

  private void publishTableMetadata(String prefix) {
    new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(tx -> {
      for (String suffix : List.of("summary", "column")) {
        jdbc.update("INSERT INTO chunks(id,tenant_id,knowledge_base_id,knowledge_id,content,chunk_index,"
            + "is_enabled,chunk_type,parent_chunk_id,pre_chunk_id,next_chunk_id,status,start_at,end_at)"
            + " VALUES(?,1,'kb','knowledge',?, ?,true,?,?,?,?,2,0,0)",
            prefix + "-" + suffix, prefix + " metadata " + suffix,
            suffix.equals("summary") ? 0 : 1, "table_" + suffix,
            suffix.equals("column") ? prefix + "-summary" : "",
            suffix.equals("column") ? prefix + "-summary" : "",
            suffix.equals("summary") ? prefix + "-column" : "");
      }
      var entries = new ArrayList<IndexEntry>();
      var vectors = new LinkedHashMap<String, float[]>();
      for (String suffix : List.of("summary", "column", "orphan")) {
        String id = prefix + "-" + suffix;
        entries.add(new IndexEntry(id, 0, id, "knowledge", "kb", "", prefix + " metadata " + suffix, true));
        vectors.put(id, new float[] {0, 1, 0});
      }
      jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) c -> {
        index.batchSave(c, entries, vectors);
        return null;
      });
    });
  }

  @Test
  void rewrittenPlainReaderWithoutSourceBlocksDoesNotInventOriginalFileLocations()
      throws Exception {
    var rewritten =
        new DocumentReadResult("reader replacement", List.of(), "", Map.of(), "", List.of());
    var source =
        NativeDocumentSources.build(
            rewritten,
            rewritten.markdownContent(),
            "txt",
            "hash",
            "original".getBytes(StandardCharsets.UTF_8));
    assertThat(source.locators(0, 5)).isNull();
    var original = new DocumentReadResult("\uFEFForiginal", List.of(), "", Map.of(), "", List.of());
    source =
        NativeDocumentSources.build(
            original,
            original.markdownContent(),
            "txt",
            "hash",
            original.markdownContent().getBytes(StandardCharsets.UTF_8));
    assertThat(source.locators(1, 5)).isNotEmpty();
    // Production Go skips attachment for an empty original, including Office types.
    assertThat(
            NativeDocumentSources.build(
                    rewritten, rewritten.markdownContent(), "docx", "hash", new byte[0])
                .locators(0, 5))
        .isNull();
  }
}
