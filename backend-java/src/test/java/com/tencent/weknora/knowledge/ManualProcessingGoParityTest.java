package com.tencent.weknora.knowledge;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import com.tencent.weknora.model.*;
import com.tencent.weknora.model.client.CallContext;
import com.tencent.weknora.retrieval.*;
import com.tencent.weknora.security.*;
import com.tencent.weknora.task.*;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

/** Real Go worker/HTTP/SQL/vec/FTS oracle; graph here verifies calls, not Neo4j persistence. */
class ManualProcessingGoParityTest {
  @TempDir Path temp;
  final ObjectMapper json = new ObjectMapper();

  static {
    try {
      NativeDatabaseRuntime.initialize();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @TestFactory
  Stream<DynamicTest> actualGoManualWorker() throws Exception {
    JsonNode cases;
    try (var in = getClass().getResourceAsStream("/go-manual-processing.json")) {
      cases = json.readTree(in);
    }
    assertThat(cases.size()).isEqualTo(45);
    var seq = new AtomicInteger();
    return StreamSupport.stream(cases.spliterator(), false)
        .map(
            c ->
                DynamicTest.dynamicTest(
                    c.path("name").asText(), () -> compare(c, seq.incrementAndGet())));
  }

  void compare(JsonNode oracle, int no) throws Exception {
    String name = oracle.path("name").asText();
    var f = database(no, name);
    var writer = writer(f);
    var provider = json.createArrayNode();
    var queued = json.createArrayNode();
    var deletes = new AtomicInteger();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/embeddings",
        exchange -> {
          try {
            var input = json.readTree(exchange.getRequestBody());
            provider.add(input);
            if (name.equals("edit-during-embedding"))
              f.jdbc.update(
                  "UPDATE knowledges SET metadata=?,title='New title',parse_status='pending' WHERE"
                      + " id='doc'",
                  "{\"content\":\"New"
                      + " content\",\"format\":\"markdown\",\"status\":\"publish\",\"version\":4}");
            if (name.equals("cancel-during-embedding"))
              f.jdbc.update("UPDATE knowledges SET parse_status='cancelled' WHERE id='doc'");
            if (name.equals("delete-during-embedding"))
              f.jdbc.update("DELETE FROM knowledges WHERE id='doc'");
            if (name.equals("fresh-attempt-during-embedding")) {
              new KnowledgeProcessingSpanTracker(
                      new KnowledgeProcessingSpanWriteRepository(
                          f.jdbc, json, new KnowledgeProcessingSpanRepository(f.jdbc, json)),
                      json)
                  .open(1, "doc", "");
            }
            if (name.equals("kb-during-embedding"))
              f.jdbc.update("UPDATE knowledge_bases SET tenant_id=2 WHERE id='kb'");
            byte[] bytes;
            int status = 200;
            if (name.equals("embedding-error")) {
              status = 500;
              bytes = "{\"error\":{\"message\":\"controlled embedding failure\"}}".getBytes();
            } else {
              var data = json.createArrayNode();
              for (int i = 0; i < input.path("input").size(); i++) {
                byte[] text =
                    input
                        .path("input")
                        .get(i)
                        .asText()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                int sum = 0;
                for (byte b : text) sum += Byte.toUnsignedInt(b);
                data.add(
                    json.createObjectNode()
                        .put("index", i)
                        .set("embedding", json.createArrayNode().add(text.length).add(sum % 251)));
              }
              bytes = json.createObjectNode().set("data", data).toString().getBytes();
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) {
              out.write(bytes);
            }
          } catch (Exception e) {
            throw new RuntimeException(e);
          } finally {
            exchange.close();
          }
        });
    server.start();
    var env =
        new MockEnvironment()
            .withProperty("SSRF_WHITELIST", "127.0.0.1")
            .withProperty("SSRF_DNS_WHITELIST_ONLY", "true");
    var models =
        new ModelClientFactory(
            f.jdbc,
            json,
            new GoSecretCodec(""),
            new ModelCatalogRuntime(f.jdbc, "", "/nonexistent"),
            new ModelEgressPolicy(f.jdbc, json, env),
            env);
    try {
      var config =
          json.createObjectNode()
              .put("chunk_size", 64)
              .put("chunk_overlap", 10)
              .put("strategy", name.equals("heading") ? "heading" : "legacy")
              .put("enable_parent_child", name.equals("parent-child"))
              .put("parent_chunk_size", 128)
              .put("child_chunk_size", 32)
              .set("separators", json.createArrayNode().add("\n\n").add("\n").add("。"));
      if (name.equals("same-content")) {
        ((ObjectNode) config).put("chunk_size", 20).put("chunk_overlap", 0);
      }
      f.jdbc.update(
          "UPDATE knowledge_bases SET chunking_config=?,summary_model_id='' WHERE id='kb'",
          config.toString());
      var row =
          f.knowledge
              .blank()
              .put("id", "doc")
              .put("tenant_id", 1)
              .put("knowledge_base_id", "kb")
              .put("type", "manual")
              .put("source", "manual")
              .put("title", "Manual title")
              .put("file_name", "Manual title.md")
              .put("file_type", "manual")
              .put("embedding_model_id", "embedding")
              .put("parse_status", "pending")
              .put("enable_status", "disabled");
      var meta = oracle.path("knowledge").path("metadata").deepCopy();
      if (!meta.isObject() || name.equals("edit-during-embedding"))
        meta =
            json.createObjectNode()
                .put("content", oracle.path("content").asText())
                .put("format", "markdown")
                .put("status", "publish")
                .put("version", 3)
                .set("process_overrides", json.createObjectNode().put("summary_enabled", false));
      row.set("metadata", meta);
      f.knowledge.create(1, row);
      var parameters =
          json.createObjectNode()
              .put("provider", "generic")
              .put("base_url", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
              .set("embedding_parameters", json.createObjectNode().put("dimension", 2));
      f.jdbc.update(
          "INSERT INTO models(id,tenant_id,name,type,source,parameters,status)"
              + " VALUES('embedding',1,'manual-embedding','Embedding','remote',?,'active')",
          parameters.toString());
      if (name.equals("model-missing")) f.jdbc.update("DELETE FROM models");
      if (name.equals("model-foreign")) f.jdbc.update("UPDATE models SET tenant_id=2");
      if (name.equals("model-type")) f.jdbc.update("UPDATE models SET type='KnowledgeQA'");
      if (name.equals("cleanup")) {
        f.jdbc.update(
            "INSERT INTO"
                + " chunks(id,tenant_id,knowledge_base_id,knowledge_id,content,is_enabled,chunk_type,seq_id,chunk_index,status,start_at,end_at,tag_id)"
                + " VALUES('old',1,'kb','doc','Old indexed content',true,'text'," + oldSequence() + ",0,0,0,0,'')");
        writer.prepare(Set.of(2));
        new org.springframework.transaction.support.TransactionTemplate(f.manager)
            .executeWithoutResult(
                status ->
                    f.jdbc.execute(
                        (org.springframework.jdbc.core.ConnectionCallback<Void>)
                            c -> {
                              writer.batchSave(
                                  c,
                                  List.of(
                                      new IndexEntry(
                                          "old",
                                          0,
                                          "old",
                                          "doc",
                                          "kb",
                                          "",
                                          "Old indexed content",
                                          true)),
                                  Map.of("old", new float[] {1, 0}));
                              return null;
                            }));
        f.jdbc.update("UPDATE knowledges SET storage_size=20 WHERE id='doc'");
        f.jdbc.update("UPDATE tenants SET storage_used=20 WHERE id=1");
      }
      if (name.equals("wrong-type"))
        f.jdbc.update("UPDATE knowledges SET type='file' WHERE id='doc'");
      if (name.equals("quota")) f.jdbc.update("UPDATE tenants SET storage_quota=1 WHERE id=1");
      if (name.equals("missing-knowledge")) f.jdbc.update("DELETE FROM knowledges");
      if (name.equals("missing-base")) f.jdbc.update("DELETE FROM knowledge_bases");
      if (name.equals("missing-tenant")) f.jdbc.update("DELETE FROM tenants WHERE id=1");
      if (name.equals("foreign-document")) f.jdbc.update("UPDATE knowledges SET tenant_id=2");
      if (name.equals("foreign-base")) f.jdbc.update("UPDATE knowledge_bases SET tenant_id=2");
      if (Set.of("completed", "cancelled", "deleting", "draft", "failed").contains(name))
        f.jdbc.update("UPDATE knowledges SET parse_status=? WHERE id='doc'", name);
      var repository =
          new ManualProcessingRepository(f.jdbc, f.knowledge, json, f.manager, Clock.systemUTC());
      var spans =
          new KnowledgeProcessingSpanTracker(
              new KnowledgeProcessingSpanWriteRepository(
                  f.jdbc, json, new KnowledgeProcessingSpanRepository(f.jdbc, json)),
              json);
      if (name.equals("existing-attempt")) spans.open(1, "doc", "");
      var graph =
          new GraphExtractionStore() {
            public boolean enabled() {
              return true;
            }

            public void add(
                String kb, String doc, GraphExtractionText.Graph g, CallContext c, Runnable guard) {
              throw new AssertionError();
            }

            public void delete(String kb, String doc, CallContext call, Runnable guard) {
              guard.run();
              deletes.incrementAndGet();
            }

            public ObjectNode counts(String kb, String doc) {
              return json.createObjectNode();
            }

            public ObjectNode detail(String kb, String doc, String chunk) {
              return json.createObjectNode();
            }
          };
      KnowledgeFileUpload.Enqueuer enqueue =
          (owner, type, payload, options) -> {
            if (name.equals("queue-error"))
              throw new IllegalStateException("controlled queue unavailable");
            queued.add(
                json.createObjectNode()
                    .put("type", type)
                    .put("queue", options.queue())
                    .put("max_retry", options.maxRetries())
                    .put("timeout", options.timeout().toMillis())
                    .set("payload", payload.deepCopy()));
            return UUID.randomUUID().toString();
          };
      var handler =
          new ManualProcessingHandler(
              repository,
              json,
              (owner, id) ->
                  models.embeddingForCleanup(
                      new AuthContext(
                          "system-task", owner, "viewer", false, null, false, Set.of(), Set.of()),
                      id),
              (owner, kb) -> writer,
              enqueue,
              spans,
              graph,
              5);
      String raw = oracle.path("raw_payload").asText();
      JsonNode value;
      try {
        value = json.readTree(raw);
      } catch (Exception e) {
        value = json.createObjectNode();
      }
      f.tasks.enqueue(
          1,
          "manual:process",
          UUID.randomUUID().toString(),
          value,
          4,
          Duration.ZERO,
          "default",
          Duration.ofMinutes(30));
      var job = f.tasks.claim("worker", Duration.ofMinutes(5), List.of("default")).orElseThrow();
      f.jdbc.update("UPDATE java_task_jobs SET payload=? WHERE id=?", raw, job.id());
      String error = "";
      boolean retry = false;
      try {
        handler.execute(job, f.cancellation(job));
      } catch (TaskFailure e) {
        error = e.getMessage();
        retry = e.retryable();
      }
      assertThat(error)
          .as("Go ACK/retry/SkipRetry decision")
          .isEqualTo(oracle.path("error").asText());
      assertThat(retry).isEqualTo(oracle.path("retry").asBoolean());
      var current = f.knowledge.get(name.equals("foreign-document") ? 2 : 1, "doc");
      var state = json.createObjectNode();
      state.set("metadata", current.map(r -> r.get("metadata")).orElse(NullNode.instance));
      for (String key : List.of("title", "parse_status", "enable_status", "summary_status"))
        state.put(key, current.map(r -> r.path(key).asText()).orElse(""));
      state.put("error", current.map(r -> r.path("error_message").asText()).orElse(""));
      state.put("pending", current.map(r -> r.path("pending_subtasks_count").asInt()).orElse(0));
      state.put("storage_size", current.map(r -> r.path("storage_size").asLong()).orElse(0L));
      assertThat(canonical(state))
          .as("manual SQL row/revision/error/state")
          .isEqualTo(canonical(oracle.path("knowledge")));
      var sqlChunks =
          f.jdbc.queryForList(
              "SELECT * FROM chunks WHERE knowledge_id='doc' AND deleted_at IS NULL ORDER BY"
                  + " chunk_type,chunk_index");
      var ids = new HashMap<String, String>();
      ids.put("", "");
      ids.put("old", "old");
      for (var c : sqlChunks)
        ids.put(c.get("id").toString(), c.get("chunk_type") + "-" + c.get("chunk_index"));
      var chunkOutput = json.createArrayNode();
      for (var c : sqlChunks) {
        var o =
            json.createObjectNode()
                .put("id", ids.get(c.get("id").toString()))
                .put("content", Objects.toString(c.get("content"), ""))
                .put("context_header", Objects.toString(c.get("context_header"), ""))
                .put("index", ((Number) c.get("chunk_index")).intValue())
                .put("start", ((Number) c.get("start_at")).longValue())
                .put("end", ((Number) c.get("end_at")).longValue())
                .put("type", c.get("chunk_type").toString())
                .put(
                    "enabled",
                    Boolean.TRUE.equals(c.get("is_enabled"))
                        || c.get("is_enabled") instanceof Number number && number.intValue() != 0)
                .put("status", ((Number) c.get("status")).intValue())
                .put("seq_id", ((Number) c.get("seq_id")).longValue());
        for (String[] key :
            new String[][] {
              {"parent", "parent_chunk_id"}, {"previous", "pre_chunk_id"}, {"next", "next_chunk_id"}
            }) o.put(key[0], ids.get(Objects.toString(c.get(key[1]), "")));
        o.set("tag_id", json.valueToTree(c.get("tag_id")));
        chunkOutput.add(o);
      }
      assertThat(canonical(chunkOutput))
          .as("actual Go chunk text/links/offsets/sequence")
          .isEqualTo(canonical(oracle.path("chunks")));
      var indexOutput = json.createArrayNode();
      for (var c :
          f.jdbc.queryForList(
              "SELECT source_id,chunk_id,knowledge_id,knowledge_base_id,content,is_enabled FROM"
                  + " "
                  + indexTable())) {
        c.put("source_id", ids.get(c.get("source_id").toString()));
        c.put("chunk_id", ids.get(c.get("chunk_id").toString()));
        indexOutput.add(json.valueToTree(c));
      }
      var sorted = json.createArrayNode();
      StreamSupport.stream(indexOutput.spliterator(), false)
          .sorted(Comparator.comparing(n -> n.path("source_id").asText()))
          .forEach(sorted::add);
      assertThat(canonical(sorted))
          .as("real physical index entries")
          .isEqualTo(
              canonical(
                  oracle.path("indexes").isNull()
                      ? json.createArrayNode()
                      : oracle.path("indexes")));
      var vectorOutput = vectors(f, ids);
      assertThat(canonical(vectorOutput))
          .as("physical float32 raw bits mapped to each actual chunk source")
          .isEqualTo(canonical(oracle.path("vectors")));
      assertThat(
              f.jdbc.queryForObject(
                  "SELECT COALESCE(SUM(storage_used),0) FROM tenants", Long.class))
          .isEqualTo(oracle.path("storage_used").asLong());
      assertThat(canonical(queued)).isEqualTo(canonical(oracle.path("tasks")));
      assertThat(canonical(provider))
          .as("real provider batch requests/backoff")
          .isEqualTo(canonical(oracle.path("provider")));
      assertThat(deletes.get())
          .as("graph cleanup invocation only")
          .isEqualTo(oracle.path("graph_cleanup_calls").asInt());
      var spanRows =
          json.valueToTree(
              f.jdbc.queryForList(
                  "SELECT attempt,name,kind,status,CAST(input AS TEXT) AS input,CAST(output AS"
                      + " TEXT) AS output,error_code,error_message FROM knowledge_processing_spans"
                      + " ORDER BY attempt,id"));
      assertThat(canonical(spanRows))
          .as("real processing spans stage status/inputs/outputs")
          .isEqualTo(
              canonical(
                  oracle.path("spans").isNull() ? json.createArrayNode() : oracle.path("spans")));
    } finally {
      models.close();
      server.stop(0);
      release(f);
    }
  }

  DocumentProcessingCompatibilityTest database(int no, String name) throws Exception {
    var f = new DocumentProcessingCompatibilityTest();
    f.temp = temp;
    f.database(no, !name.equals("disabled-indexes"));
    return f;
  }

  String oldSequence(){return "1";}
  IndexWriter writer(DocumentProcessingCompatibilityTest f) {
    return f.index;
  }

  String indexTable() {
    return "lite_embeddings";
  }

  void release(DocumentProcessingCompatibilityTest f) {}

  JsonNode vectors(DocumentProcessingCompatibilityTest f, Map<String, String> ids)
      throws Exception {
    var vectorOutput = json.createArrayNode();
    if (f.jdbc.queryForObject(
            "SELECT COUNT(*) FROM sqlite_master WHERE name='vec_embeddings_2'", Long.class)
        > 0) {
      var rawVectors =
          f.jdbc.execute(
              (org.springframework.jdbc.core.ConnectionCallback<
                      java.util.List<java.util.Map<String, Object>>>)
                  c -> {
                    new SQLiteVectorExtension(
                            Path.of(System.getProperty("weknora.sqlite.vec.extension")))
                        .load(c);
                    var rows = new java.util.ArrayList<java.util.Map<String, Object>>();
                    try (var statement = c.createStatement();
                        var rs =
                            statement.executeQuery(
                                "SELECT e.source_id,v.embedding FROM lite_embeddings e JOIN"
                                    + " vec_embeddings_2 v ON v.rowid=e.id")) {
                      while (rs.next())
                        rows.add(
                            new java.util.HashMap<>(
                                java.util.Map.of(
                                    "source_id", rs.getString(1), "embedding", rs.getBytes(2))));
                    }
                    return rows;
                  });
      rawVectors.sort(Comparator.comparing(v -> ids.get(v.get("source_id").toString())));
      for (var v : rawVectors) {
        var bits = json.createArrayNode();
        var buffer =
            java.nio.ByteBuffer.wrap((byte[]) v.get("embedding"))
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        while (buffer.remaining() >= 4) bits.add(Integer.toUnsignedLong(buffer.getInt()));
        vectorOutput.add(
            json.createObjectNode()
                .put("source_id", ids.get(v.get("source_id").toString()))
                .set("bits", bits));
      }
    }
    return vectorOutput;
  }

  JsonNode canonical(JsonNode n) {
    if (n.isNumber()) return DecimalNode.valueOf(n.decimalValue().stripTrailingZeros());
    if (n.isArray()) {
      var a = json.createArrayNode();
      n.forEach(v -> a.add(canonical(v)));
      return a;
    }
    if (!n.isObject()) return n;
    var out = json.createObjectNode();
    n.fields()
        .forEachRemaining(
            e -> {
              var v = e.getValue();
              if (Set.of("input", "output", "metadata").contains(e.getKey()) && v.isTextual())
                try {
                  v = json.readTree(v.asText());
                } catch (Exception x) {
                  throw new RuntimeException(x);
                }
              if (e.getKey().equals("updated_at")) return;
              out.set(e.getKey(), canonical(v));
            });
    return out;
  }
}
