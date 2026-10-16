package com.tencent.weknora.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;

/** Actual Go Formater.ParseGraph outputs, including binary64 exponent/rounding/negative-zero boundaries. */
class GraphExtractionTextGoParityTest {
  final ObjectMapper json=new ObjectMapper();
  @TestFactory Stream<DynamicTest> actualGoFloat64EntityAndNestedAttributes()throws Exception{
    var cases=json.readTree(Objects.requireNonNull(getClass().getResourceAsStream("/go-graph-number-audit.json")));
    assertEquals(521,cases.size());var text=new GraphExtractionText(json,"unused");
    return StreamSupport.stream(cases.spliterator(),false).map(c->DynamicTest.dynamicTest(c.path("input").asText(),()->{
      if(c.path("error").asBoolean()){assertThrows(IllegalArgumentException.class,()->text.parse(c.path("input").asText()));return;}
      var graph=text.parse(c.path("input").asText());var expected=c.path("graph").path("node");assertEquals(expected.size(),graph.nodes().size());assertTrue(graph.relations().isEmpty());
      for(int i=0;i<expected.size();i++){var e=expected.get(i);var n=graph.nodes().get(i);assertEquals(e.path("name").asText(),n.name());assertEquals(e.path("attributes"),json.valueToTree(n.attributes()));assertTrue(n.chunks().isEmpty());}
    }));
  }
}
