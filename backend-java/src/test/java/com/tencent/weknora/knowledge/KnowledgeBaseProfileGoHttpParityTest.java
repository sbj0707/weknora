package com.tencent.weknora.knowledge;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.*;
import org.junit.jupiter.api.*;

/** Real Go public handler/security middleware, compared with native MVC and SQL/provider effects. */
class KnowledgeBaseProfileGoHttpParityTest extends KnowledgeBaseProfileGoParityTest {
 @Override @TestFactory Stream<DynamicTest> actualGoKnowledgeBaseDescriptions()throws Exception{
  JsonNode cases;try(var in=getClass().getResourceAsStream("/go-knowledge-base-profile-http.json")){cases=json.readTree(in);}assertThat(cases.size()).isEqualTo(23);var seq=new AtomicInteger();return StreamSupport.stream(cases.spliterator(),false).map(row->DynamicTest.dynamicTest(row.path("name").asText(),()->compare(row,seq.incrementAndGet())));
 }
}
