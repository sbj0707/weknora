package com.tencent.weknora.vectorstore;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.model.ModelEgressPolicy;
import com.tencent.weknora.model.client.*;
import com.tencent.weknora.retrieval.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;

/** Physical moves and repeated retrieval compared with the actual Go repository on owned ES8. */
class Elasticsearch8MoveGoParityIT {
  final ObjectMapper json = new ObjectMapper();

  @TestFactory
  Stream<DynamicTest> physicalMoves() throws Exception {
    String address = Objects.requireNonNull(System.getProperty("weknora.es.url"));
    if (!address.matches("http://127\\.0\\.0\\.1:[0-9]+"))
      throw new IllegalArgumentException("Owned loopback ES required");
    var cases =
        json.readTree(
            Files.readAllBytes(
                Path.of(Objects.requireNonNull(System.getProperty("weknora.es.move.fixture")))));
    assertEquals(22, cases.size());
    return StreamSupport.stream(cases.spliterator(), false)
        .map(
            c ->
                DynamicTest.dynamicTest(
                    c.path("mapping").asText() + "/" + c.path("name").asText(),
                    () -> replay(c, address)));
  }

  void replay(JsonNode oracle, String address) throws Exception {
    String index = "weknora-native-index-move-" + UUID.randomUUID();
    try (var http =
            new SafeModelHttp(
                new ModelEgressPolicy(
                    null,
                    json,
                    new MockEnvironment().withProperty("SSRF_WHITELIST", "127.0.0.1")));
        var call = new CallContext()) {
      try (var response =
          http.exchange(
              call,
              address + "/" + index,
              "PUT",
              Map.of("Content-Type", "application/json"),
              json.writeValueAsBytes(oracle.path("create")),
              Duration.ofSeconds(30))) {
        assertEquals(200, response.status(), response.text(100000));
      }
      try {
        var engine =
            new Elasticsearch8Store(
                new VectorStoreMetadata.Store(
                    "owned",
                    1,
                    "elasticsearch",
                    json.createObjectNode().put("addr", address),
                    json.createObjectNode().put("index_name", index)),
                http,
                json,
                call);
        var entries = new ArrayList<IndexEntry>();
        for (var e : oracle.path("entries"))
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
        var vectors = new HashMap<String, float[]>();
        oracle
            .path("vectors")
            .fields()
            .forEachRemaining(
                e -> vectors.put(e.getKey(), Elasticsearch8GoParityIT.floats(e.getValue())));
        engine.batchSave(entries, vectors, call);
        engine.request("POST", "/" + index + "/_refresh", null, call);
        JsonNode ids = null;
        for (int stage = 0; stage < 3; stage++) {
          if (stage > 0) {
            String error = "";
            try {
              engine.moveKnowledgeIndices(
                  oracle.path("source").asText(),
                  oracle.path("target").asText(),
                  oracle.path("knowledge").asText(),
                  Elasticsearch8GoParityIT.strings(oracle.path("chunks")),
                  oracle.path("dimension").asInt(),
                  oracle.path("kind").asText(),
                  call);
            } catch (RuntimeException failed) {
              error = failed.getMessage();
            }
            assertEquals(oracle.path("errors").get(stage - 1).asText(), error);
          }
          JsonNode wanted = oracle.path("stages").get(stage);
          JsonNode actual = snapshot(engine, index, wanted.path("queries"), call);
          JsonNode currentIds = ((ObjectNode) actual).remove("physical_ids");
          if (ids == null) ids = currentIds;
          else assertEquals(ids, currentIds, "Physical ES IDs must survive moves/retries");
          assertEquals(6, currentIds.size());
          assertTrue(
              currentIds.properties().stream().allMatch(e -> !e.getValue().asText().isEmpty()));
          assertEquals(
              oracle.path("stages").get(0).path("physical_ids"),
              wanted.path("physical_ids"),
              "Go physical IDs survive too");
          ObjectNode expected = wanted.deepCopy();
          expected.remove("physical_ids");
          assertEquals(
              numbers(expected),
              numbers(actual),
              "Full physical payloads/vectors and source/target/third-KB retrieval at stage "
                  + stage);
        }
      } finally {
        try (var response =
            http.exchange(
                call,
                address + "/" + index,
                "DELETE",
                Map.of(),
                new byte[0],
                Duration.ofSeconds(30))) {
          assertEquals(200, response.status(), response.text(100000));
        }
      }
    }
  }

  JsonNode snapshot(Elasticsearch8Store engine, String index, JsonNode queries, CallContext call)
      throws Exception {
    var body = json.createObjectNode().put("size", 100);
    body.putObject("query").putObject("match_all");
    var hits =
        engine.request("POST", "/" + index + "/_search", body, call).path("hits").path("hits");
    var rows = new TreeMap<String, JsonNode>();
    var ids = json.createObjectNode();
    for (var hit : hits) {
      var source = hit.path("_source");
      String id = source.path("source_id").asText();
      assertNull(rows.put(id, source));
      ids.put(id, hit.path("_id").asText());
    }
    var result = json.createObjectNode();
    var array = result.putArray("rows");
    rows.values().forEach(array::add);
    result.set("physical_ids", ids);
    var queryRows = result.putArray("queries");
    for (var q : queries) {
      var query =
          new RetrievalQuery(
              "refund",
              new float[] {1, .1f},
              List.of(q.path("kb").asText()),
              List.of(),
              List.of(),
              100,
              0,
              q.path("kind").asText());
      var sources = new ArrayList<String>();
      for (var block : engine.retrieve(query, call))
        for (var hit : block.results()) sources.add(hit.sourceId());
      Collections.sort(sources);
      var row =
          queryRows
              .addObject()
              .put("kb", q.path("kb").asText())
              .put("kind", q.path("kind").asText());
      var sourceRows = row.putArray("sources");
      sources.forEach(sourceRows::add);
    }
    // Normalize Jackson's Float/Double and Integer/Long node classes to wire JSON values.
    return json.readTree(json.writeValueAsBytes(result));
  }

  JsonNode numbers(JsonNode value) {
    if (value.isNumber()) return DecimalNode.valueOf(value.decimalValue());
    if (value.isArray()) {
      var result = json.createArrayNode();
      value.forEach(v -> result.add(numbers(v)));
      return result;
    }
    if (value.isObject()) {
      var result = json.createObjectNode();
      value.fields().forEachRemaining(e -> result.set(e.getKey(), numbers(e.getValue())));
      return result;
    }
    return value;
  }
}
