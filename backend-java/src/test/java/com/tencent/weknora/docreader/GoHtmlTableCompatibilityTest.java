package com.tencent.weknora.docreader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import static org.junit.jupiter.api.Assertions.assertEquals;
class GoHtmlTableCompatibilityTest {
 @TestFactory Stream<DynamicTest> actualGoHtmlTableOutputs() throws Exception {
  var fixture=new ObjectMapper().readTree(getClass().getResourceAsStream("/go-html-tables.json"));
  assertEquals(70,fixture.size());
  return StreamSupport.stream(fixture.spliterator(),false).map(test -> DynamicTest.dynamicTest(test.path("name").asText(), () -> {
   String actual=HtmlTableNormalizer.normalize(test.path("input").asText());
   assertEquals(test.path("output").asText(),actual);
   assertEquals(test.path("twice").asText(),HtmlTableNormalizer.normalize(actual));
  }));
 }
}
