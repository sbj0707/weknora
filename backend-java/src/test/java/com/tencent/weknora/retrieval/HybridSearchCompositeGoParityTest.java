package com.tencent.weknora.retrieval;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;

/** Exact raw float64 comparison to the running production Go CompositeScore using explicit architecture-independent FMA (oracle recorded on ARM64). */
class HybridSearchCompositeGoParityTest {
  @TestFactory Stream<DynamicTest> actualGoComposite()throws Exception{
    var json=new ObjectMapper();JsonNode oracle;try(var in=getClass().getResourceAsStream("/go-hybrid-composite.json")){oracle=json.readTree(in);}assertFalse(oracle.path("goarch").asText().isEmpty());assertEquals(288,oracle.path("cases").size());
    return StreamSupport.stream(oracle.path("cases").spliterator(),false).map(c->DynamicTest.dynamicTest(c.toString(),()->{var row=json.createObjectNode().put("match_type",c.path("graph").asBoolean()?6:0).put("knowledge_source",c.path("web").asBoolean()?"web_search":"manual");double result=RerankStage.composite(row,c.path("model").asDouble(),c.path("base").asDouble());assertEquals(c.path("bits").asLong(),Double.doubleToRawLongBits(result));}));
  }
}
