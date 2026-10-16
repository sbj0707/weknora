package com.tencent.weknora.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.tencent.weknora.model.client.CallContext;
import java.time.Duration;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;

/** Full actual Go node/relationship goldens, including more than two delete batches. */
class GraphRepositoryCapsGoParityIT {
  @TestFactory Stream<DynamicTest> actualGoCapsAndBatchedDeletion() throws Exception {
    var helper = new GraphRepositoryGoParityIT();
    JsonNode cases = helper.json.readTree(Objects.requireNonNull(
        getClass().getResourceAsStream("/go-graph-caps.json")));
    return StreamSupport.stream(cases.spliterator(), false).map(c ->
        DynamicTest.dynamicTest(c.path("name").asText(), () -> {
          var fixture = new GraphRepositoryGoParityIT();
          try {
            fixture.connect();
            fixture.kb = c.path("namespace").path("knowledge_base").asText();
            fixture.doc = c.path("namespace").path("knowledge").asText();
            fixture.peer = fixture.doc;
            fixture.cleanup();
            var nodes = new ArrayList<GraphExtractionText.Node>();
            for (var n : c.path("graph").path("node")) nodes.add(
                new GraphExtractionText.Node(n.path("name").asText(), strings(n.path("attributes")), strings(n.path("chunks"))));
            var relations = new ArrayList<GraphExtractionText.Relation>();
            for (var r : c.path("graph").path("relation")) relations.add(
                new GraphExtractionText.Relation(r.path("node1").asText(), r.path("node2").asText(), r.path("type").asText()));
            try (var call = CallContext.timeout(Duration.ofSeconds(60))) {
              fixture.graph.add(fixture.kb, fixture.doc, new GraphExtractionText.Graph(nodes, relations), call, () -> {});
            }
            assertEquals(c.path("counts"), fixture.graph.counts(fixture.kb, fixture.doc));
            assertEquals(c.path("search"), fixture.search(fixture.kb, fixture.doc, strings(c.path("names"))));
            fixture.remove(fixture.doc);
            assertEquals(c.path("after"), fixture.graph.counts(fixture.kb, fixture.doc));
          } finally {
            fixture.close();
          }
        }));
  }

  private static List<String> strings(JsonNode node) {
    var out = new ArrayList<String>();
    node.forEach(n -> out.add(n.asText()));
    return out;
  }
}
