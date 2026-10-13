package com.tencent.weknora.model;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.model.client.*;
import com.tencent.weknora.retrieval.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;

class HybridSQLiteModelGoParityTest {
  static final ObjectMapper JSON = new ObjectMapper();

  @org.junit.jupiter.api.Test
  void actualGoSQLiteEmbeddingAndRerankOverRealHTTP() throws Exception {
    JsonNode fixture =
        JSON.readTree(
            Files.readString(
                Path.of(
                    System.getProperty(
                        "weknora.hybrid.wire.fixture",
                        "src/test/java/com/tencent/weknora/retrieval/go-hybrid-wire.json"))));
    try (var h = new NativeModelClientsTest.Harness();
        var jieba =
            new GoJiebaSegmenter(
                Path.of(System.getProperty("weknora.jieba.library")),
                Path.of(System.getProperty("weknora.jieba.dict")))) {
      h.server.removeContext("/");
      h.server.createContext(
          "/",
          x -> {
            ObjectNode body = (ObjectNode) JSON.readTree(x.getRequestBody());
            h.requests.add(
                JSON.createObjectNode()
                    .put("path", x.getRequestURI().getPath())
                    .put("authorization", x.getRequestHeaders().getFirst("Authorization"))
                    .set("body", body));
            ObjectNode reply = JSON.createObjectNode();
            ArrayNode list = JSON.createArrayNode();
            if (x.getRequestURI().getPath().endsWith("embeddings")) {
              list.add(
                  JSON.createObjectNode()
                      .put("index", 0)
                      .set("embedding", JSON.createArrayNode().add(1).add(.125)));
              reply.set("data", list);
            } else {
              JsonNode docs = body.path("documents");
              for (int i = docs.size() - 1; i >= 0; i--) {
                String text = docs.get(i).asText();
                double score = text.contains("beta") ? .88 : text.contains("alpha") ? .74 : .63;
                list.add(JSON.createObjectNode().put("index", i).put("relevance_score", score));
              }
              reply.set("results", list);
            }
            byte[] bytes = reply.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            x.getResponseHeaders().set("Content-Type", "application/json");
            x.sendResponseHeaders(200, bytes.length);
            try (var out = x.getResponseBody()) {
              out.write(bytes);
            }
            x.close();
          });
      ObjectNode params =
          JSON.createObjectNode()
              .put("provider", "generic")
              .put("base_url", h.url() + "/v1")
              .put("api_key", h.secrets.encryptLegacyWhenConfigured("synthetic-api-key"));
      h.seed("embed", 1, false, "active", "unknown", "Embedding", params);
      h.seed("rerank", 1, false, "active", "unknown", "Rerank", params);
      var repo =
          new SQLiteIndexRepository(
              h.jdbc.getDataSource(),
              new SQLiteVectorExtension(
                  Path.of(System.getProperty("weknora.sqlite.vec.extension"))));
      repo.initialize();
      repo.prepare(Set.of(2));
      List<IndexEntry> entries = new ArrayList<>();
      for (JsonNode e : fixture.path("entries"))
        entries.add(
            new IndexEntry(
                e.path("SourceID").asText(),
                e.path("SourceType").asInt(),
                e.path("ChunkID").asText(),
                e.path("KnowledgeID").asText(),
                e.path("KnowledgeBaseID").asText(),
                e.path("TagID").asText(),
                e.path("Content").asText(),
                e.path("IsEnabled").asBoolean()));
      Map<String, float[]> vectors = new LinkedHashMap<>();
      fixture
          .path("vectors")
          .fields()
          .forEachRemaining(e -> vectors.put(e.getKey(), floats(e.getValue())));
      try (Connection tx = h.jdbc.getDataSource().getConnection()) {
        tx.setAutoCommit(false);
        repo.batchSave(tx, entries, vectors);
        tx.commit();
      }
      int count = 0, requests = 0;
      for (JsonNode c : fixture.path("cases")) {
        h.requests.clear();
        JsonNode p = c.path("params");
        String query = p.path("Query").asText();
        try (var call = CallContext.timeout(Duration.ofSeconds(8))) {
          float[] embedding =
              c.path("mode").asInt() == 2
                  ? new float[0]
                  : h.factory.embedding(h.viewer, "embed").embed(call, query, true);
          if (embedding.length > 0) equal(embedding, p.path("Embedding"));
          var retrieved =
              repo.retrieve(
                  new RetrievalQuery(
                      query,
                      embedding,
                      strings(p.path("KnowledgeBaseIDs")),
                      strings(p.path("KnowledgeIDs")),
                      strings(p.path("TagIDs")),
                      p.path("TopK").asInt(),
                      p.path("Threshold").asDouble(),
                      p.path("RetrieverType").asText()));
          equal(retrievedGo(retrieved), c.path("retrieved"));
          List<List<RetrievalHit>> vl = new ArrayList<>(), kl = new ArrayList<>();
          for (var r : retrieved) (r.kind().equals("vector") ? vl : kl).add(r.results());
          var fused = HybridFusion.fuse(vl, kl, null);
          equal(hitsGo(fused), c.path("fused"));
          List<ObjectNode> rows = new ArrayList<>();
          for (var hit : fused) rows.add(row(hit));
          equal(rows, c.path("rows"));
          JsonNode opts = c.path("options");
          var outcome =
              new RerankStage(jieba)
                  .run(
                      call,
                      h.factory.rerank(h.viewer, "rerank"),
                      query,
                      rows,
                      new RerankStage.Options(
                          opts.path("Threshold").asDouble(),
                          opts.path("TopK").asInt(),
                          opts.path("FallbackMinScore").asDouble(),
                          opts.path("MaxCandidates").asInt(),
                          opts.path("FAQScoreBoost").asDouble()));
          JsonNode expected = c.path("result");
          equal(outcome.results(), expected.path("Results"));
          equal(outcome.indices(), expected.path("Indices"));
          equal(outcome.diagnostics(), expected.path("Diagnostics"));
          equal(outcome.passages(), expected.path("Passages"));
          equal(h.requests, c.path("requests"));
          requests += h.requests.size();
          count++;
        }
      }
      org.assertj.core.api.Assertions.assertThat(count).isEqualTo(12);
      org.assertj.core.api.Assertions.assertThat(requests).isEqualTo(20);
    }
  }

  static float[] floats(JsonNode n) {
    float[] out = new float[n.size()];
    for (int i = 0; i < out.length; i++) out[i] = (float) n.get(i).asDouble();
    return out;
  }

  static List<String> strings(JsonNode n) {
    List<String> out = new ArrayList<>();
    n.forEach(x -> out.add(x.asText()));
    return out;
  }

  static ObjectNode row(RetrievalHit h) {
    ObjectNode out = JSON.createObjectNode();
    for (String k :
        List.of(
            "id",
            "content",
            "knowledge_id",
            "knowledge_title",
            "chunk_type",
            "parent_chunk_id",
            "image_info",
            "knowledge_filename",
            "knowledge_source",
            "knowledge_channel")) out.put(k, "");
    for (String k : List.of("chunk_index", "start_at", "end_at", "seq", "match_type"))
      out.put(k, 0);
    out.putNull("sub_chunk_id");
    out.putNull("metadata");
    out.put("id", h.chunkId())
        .put("content", h.content())
        .put("knowledge_id", h.knowledgeId())
        .put("knowledge_base_id", h.knowledgeBaseId())
        .put("knowledge_title", "Document " + h.knowledgeId())
        .put("score", h.score())
        .put("match_type", h.matchType())
        .put("chunk_type", "text");
    return out;
  }

  static ArrayNode hitsGo(List<RetrievalHit> hits) {
    ArrayNode out = JSON.createArrayNode();
    for (var h : hits)
      out.add(
          JSON.createObjectNode()
              .put("ID", h.id())
              .put("Content", h.content())
              .put("SourceID", h.sourceId())
              .put("SourceType", h.sourceType())
              .put("ChunkID", h.chunkId())
              .put("KnowledgeID", h.knowledgeId())
              .put("KnowledgeBaseID", h.knowledgeBaseId())
              .put("TagID", h.tagId())
              .put("Score", h.score())
              .put("MatchType", h.matchType())
              .put("IsEnabled", false));
    return out;
  }

  static ArrayNode retrievedGo(List<RetrievalResult> results) {
    ArrayNode out = JSON.createArrayNode();
    for (var r : results)
      out.add(
          JSON.createObjectNode()
              .put("RetrieverEngineType", r.engine())
              .put("RetrieverType", r.kind())
              .set("Results", hitsGo(r.results())));
    for (JsonNode r : out) ((ObjectNode) r).putNull("Error");
    return out;
  }

  static void equal(Object a, JsonNode b) {
    JsonNode n = JSON.valueToTree(a);
    if (!same(n, b)) throw new AssertionError("EXPECTED " + b + "\nACTUAL " + n);
  }

  static boolean same(JsonNode a, JsonNode b) {
    if (a == null || b == null) return a == b;
    if (a.isNumber() && b.isNumber())
      return Math.abs(a.asDouble() - b.asDouble()) < Math.max(1e-12, 4 * Math.ulp(b.asDouble()));
    if (a.isArray() && b.isArray()) {
      if (a.size() != b.size()) return false;
      for (int i = 0; i < a.size(); i++) if (!same(a.get(i), b.get(i))) return false;
      return true;
    }
    if (a.isObject() && b.isObject()) {
      if (a.size() != b.size()) return false;
      for (var it = a.fieldNames(); it.hasNext(); ) {
        String k = it.next();
        if (!same(a.get(k), b.get(k))) return false;
      }
      return true;
    }
    return a.equals(b);
  }
}
