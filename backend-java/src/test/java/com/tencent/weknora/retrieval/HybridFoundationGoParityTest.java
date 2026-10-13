package com.tencent.weknora.retrieval;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.model.client.*;
import java.nio.file.*;
import java.util.*;

class HybridFoundationGoParityTest {
  static final ObjectMapper JSON = new ObjectMapper();

  @org.junit.jupiter.api.Test
  void actualGoFusionPassagesJiebaTokensAndRerankAlgorithms() throws Exception {
    int count = 0;
    JsonNode fixture =
        JSON.readTree(
            Files.readString(
                Path.of(
                    System.getProperty(
                        "weknora.hybrid.fixture",
                        "src/test/java/com/tencent/weknora/retrieval/go-hybrid-foundation.json"))));
    try (var jieba =
        new GoJiebaSegmenter(
            Path.of(System.getProperty("weknora.jieba.library")),
            Path.of(System.getProperty("weknora.jieba.dict")))) {
      for (JsonNode c : fixture.path("passage")) {
        ObjectNode row = (ObjectNode) c.path("row");
        equal(RerankPassage.clean(row.path("content").asText()), c.path("clean"));
        equal(RerankPassage.enriched(row), c.path("enriched"));
        equal(RerankPassage.model(row), c.path("model"));
        count++;
      }
      for (JsonNode c : fixture.path("tokens")) {
        List<String> words = new ArrayList<>(HybridText.tokens(c.path("text").asText(), jieba));
        words.sort(
            (a, b) -> {
              var x = a.codePoints().iterator();
              var y = b.codePoints().iterator();
              while (x.hasNext() && y.hasNext()) {
                int d = Integer.compare(x.nextInt(), y.nextInt());
                if (d != 0) return d;
              }
              return x.hasNext() ? 1 : y.hasNext() ? -1 : 0;
            });
        equal(words, c.path("tokens"));
        count++;
      }
      for (JsonNode c : fixture.path("fusion")) {
        JsonNode input = c.path("input"), config = input.path("config");
        HybridFusion.Config conf =
            config.isNull()
                ? null
                : new HybridFusion.Config(
                    config.path("rrf_k").asInt(),
                    config.path("rrf_vector_weight").asDouble(),
                    config.path("rrf_keyword_weight").asDouble());
        List<RetrievalHit> actual =
            HybridFusion.fuse(lists(input.path("vectors")), lists(input.path("keywords")), conf);
        if (actual.size() != c.path("result").size()) throw new AssertionError("fusion size");
        for (int i = 0; i < actual.size(); i++) {
          var a = actual.get(i);
          var b = c.path("result").get(i);
          if (!a.chunkId().equals(b.path("ChunkID").asText())
              || Math.abs(a.score() - b.path("Score").asDouble()) > 1e-12
              || !a.content().equals(b.path("Content").asText()))
            throw new AssertionError("fusion " + input + "\n" + actual + "\n" + c.path("result"));
        }
        count++;
      }
      for (JsonNode c : fixture.path("rerank")) {
        List<ObjectNode> rows = new ArrayList<>();
        for (JsonNode row : c.path("rows")) rows.add((ObjectNode) row);
        JsonNode o = c.path("options");
        RerankStage.Options options =
            new RerankStage.Options(
                o.path("Threshold").asDouble(),
                o.path("TopK").asInt(),
                o.path("FallbackMinScore").asDouble(),
                o.path("MaxCandidates").asInt(),
                o.path("FAQScoreBoost").asDouble());
        RerankModel model =
            new RerankModel() {
              public String modelId() {
                return "oracle";
              }

              public String modelName() {
                return "oracle";
              }

              public int maxPassageRunes(String query) {
                return c.path("limit").asInt();
              }

              public List<Result> rerank(CallContext call, String query, List<String> passages) {
                if (!c.path("error").asText().isEmpty())
                  throw new ModelClientException(c.path("error").asText());
                List<Result> out = new ArrayList<>();
                for (JsonNode score : c.path("scores"))
                  out.add(
                      new Result(
                          score.path("index").asInt(),
                          "",
                          score.path("relevance_score").asDouble()));
                return out;
              }
            };
        try (var call = new CallContext()) {
          var actual = new RerankStage(jieba).run(call, model, "query", rows, options);
          JsonNode expected = c.path("result");
          equal(actual.results(), expected.path("Results"));
          equal(actual.indices(), expected.path("Indices"));
          equal(actual.diagnostics(), expected.path("Diagnostics"));
          equal(actual.passages(), expected.path("Passages"));
        }
        count++;
      }
    }
    org.assertj.core.api.Assertions.assertThat(count).isEqualTo(428);
  }

  @org.junit.jupiter.api.Test
  void nativePathsAndLifecycleFailExplicitly() throws Exception {
    var library = Path.of(System.getProperty("weknora.jieba.library"));
    var dictionaries = Path.of(System.getProperty("weknora.jieba.dict"));
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new GoJiebaSegmenter(Path.of("relative.so"), dictionaries))
        .isInstanceOf(IllegalArgumentException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new GoJiebaSegmenter(library, Path.of("relative")))
        .isInstanceOf(IllegalArgumentException.class);
    try (var segmenter = new GoJiebaSegmenter(library, dictionaries)) {
      org.assertj.core.api.Assertions.assertThat(segmenter.cutForSearch("😀南京市"))
          .contains("南京", "京市", "南京市");
      segmenter.close();
      org.assertj.core.api.Assertions.assertThatThrownBy(() -> segmenter.cutForSearch("closed"))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  static List<List<RetrievalHit>> lists(JsonNode n) {
    List<List<RetrievalHit>> out = new ArrayList<>();
    for (JsonNode list : n) {
      List<RetrievalHit> rows = new ArrayList<>();
      for (JsonNode row : list)
        rows.add(
            new RetrievalHit(
                row.path("ID").asText(),
                row.path("Content").asText(),
                row.path("SourceID").asText(),
                row.path("SourceType").asInt(),
                row.path("ChunkID").asText(),
                row.path("KnowledgeID").asText(),
                row.path("KnowledgeBaseID").asText(),
                row.path("TagID").asText(),
                row.path("Score").asDouble(),
                row.path("MatchType").asInt()));
      out.add(rows);
    }
    return out;
  }

  static void equal(Object actual, JsonNode expected) {
    JsonNode node = JSON.valueToTree(actual);
    if (node.isArray() && node.isEmpty() && expected.isNull()) return;
    if (!same(node, expected))
      throw new AssertionError("EXPECTED " + expected + "\nACTUAL " + node);
  }

  static boolean same(JsonNode a, JsonNode b) {
    if (a == null || b == null) return a == b;
    if (a.isNumber() && b.isNumber()) return Math.abs(a.asDouble() - b.asDouble()) < 1e-12;
    if (a.isArray() && b.isArray()) {
      if (a.size() != b.size()) return false;
      for (int i = 0; i < a.size(); i++) if (!same(a.get(i), b.get(i))) return false;
      return true;
    }
    if (a.isObject() && b.isObject()) {
      if (a.size() != b.size()) return false;
      for (Iterator<String> it = a.fieldNames(); it.hasNext(); ) {
        String key = it.next();
        if (!same(a.get(key), b.get(key))) return false;
      }
      return true;
    }
    return a.equals(b);
  }
}
