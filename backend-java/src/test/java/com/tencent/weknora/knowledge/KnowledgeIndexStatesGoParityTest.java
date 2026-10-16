package com.tencent.weknora.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.*;

class KnowledgeIndexStatesGoParityTest {
  @TestFactory Stream<DynamicTest> productionGoEvidenceStates() throws Exception {
    var json=new ObjectMapper();JsonNode corpus;
    try(var input=new GZIPInputStream(getClass().getResourceAsStream("/go-knowledge-index-states.json.gz"))){corpus=json.readTree(input);}
    assertEquals(964,corpus.size());List<DynamicTest> tests=new ArrayList<>();int i=0;
    for(var test:corpus) {int number=i++;tests.add(DynamicTest.dynamicTest("production-Go-state-"+number,()->{
      ObjectNode row=(ObjectNode)test.path("row").deepCopy();
      if(test.path("type").asText().equals("document")) {List<ObjectNode> spans=new ArrayList<>();for(var span:test.path("spans"))spans.add((ObjectNode)span);KnowledgeIndexStates.document(row,test.path("parse_status").asText(),spans);}
      else KnowledgeIndexStates.chunk(row,test.path("chunk"),test.path("refreshing").asBoolean());
      assertEquals(test.path("expected"),row,test.toString());
    }));}return tests.stream();
  }
  @Test void questionAliasesUseUtf8ByteLimitAndGoHash() throws Exception {
    assertEquals("chunk-q",KnowledgeIndexReadService.sourceId("chunk","q"));
    assertEquals("chunk-q"+java.util.HexFormat.of().formatHex(Arrays.copyOf(java.security.MessageDigest.getInstance("SHA-256").digest("问".repeat(40).getBytes(java.nio.charset.StandardCharsets.UTF_8)),12)),KnowledgeIndexReadService.sourceId("chunk","问".repeat(40)));
  }
}
