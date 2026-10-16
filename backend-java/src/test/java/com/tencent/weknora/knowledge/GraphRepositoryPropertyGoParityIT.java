package com.tencent.weknora.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.tencent.weknora.model.client.CallContext;
import java.time.Duration;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;

/** Actual Go relation-only and stored scalar-list conversion, read over real Neo4j. */
class GraphRepositoryPropertyGoParityIT {
  @TestFactory Stream<DynamicTest> actualGoStoredProperties() throws Exception {
    var helper = new GraphRepositoryGoParityIT();
    JsonNode cases = helper.json.readTree(Objects.requireNonNull(
        getClass().getResourceAsStream("/go-graph-properties.json")));
    return StreamSupport.stream(cases.spliterator(), false).map(c ->
        DynamicTest.dynamicTest(c.path("name").asText(), () -> {
          var f = new GraphRepositoryGoParityIT();
          try {
            f.connect();
            f.kb = c.path("namespace").path("knowledge_base").asText();
            f.doc = c.path("namespace").path("knowledge").asText();
            f.peer = f.doc;
            f.cleanup();
            var nodes = new ArrayList<GraphExtractionText.Node>();
            for (var n : c.path("graph").path("node")) nodes.add(new GraphExtractionText.Node(
                n.path("name").asText(), strings(n.path("attributes")), strings(n.path("chunks"))));
            var relations = new ArrayList<GraphExtractionText.Relation>();
            for (var r : c.path("graph").path("relation")) relations.add(new GraphExtractionText.Relation(
                r.path("node1").asText(), r.path("node2").asText(), r.path("type").asText()));
            try (var call = CallContext.timeout(Duration.ofSeconds(30))) {
              f.graph.add(f.kb, f.doc, new GraphExtractionText.Graph(nodes, relations), call, () -> {});
            }
            if (!c.path("attributes").isNull()) try (var session = f.driver.session()) {
              List<Object> values = f.json.convertValue(c.path("attributes"), new TypeReference<List<Object>>() {});
              if (!c.path("float_bits").isEmpty()) {
                values = new ArrayList<>();
                for (var bits : c.path("float_bits")) values.add(Double.longBitsToDouble(Long.parseUnsignedLong(bits.asText(), 16)));
              }
              session.run("MATCH(n {kg:$doc}) SET n.attributes=$values", Map.of("doc", f.doc, "values", values)).consume();
            }
            assertEquals("", c.path("failure").asText(), "Go baseline must safely read this legitimate stored graph");
            assertEquals(c.path("search"), f.search(f.kb, f.doc, List.of("Alice")));
          } finally {
            f.close();
          }
        }));
  }

  private static List<String> strings(JsonNode node) {
    var out = new ArrayList<String>();
    node.forEach(n -> out.add(n.asText()));
    return out;
  }
}
