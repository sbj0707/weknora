package com.tencent.weknora.chunker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GoAdaptiveChunkerCompatibilityTest {
    private final ObjectMapper mapper=new ObjectMapper();
    @TestFactory
    Stream<DynamicTest> actualProductionGoAdaptiveOutputs() throws Exception {
        JsonNode fixtures;
        try (var input=new GZIPInputStream(getClass().getResourceAsStream("/go-adaptive-chunker.json.gz"))) {
            fixtures=mapper.readTree(input);
        }
        assertEquals(175,fixtures.size());
        return StreamSupport.stream(fixtures.spliterator(),false).map(test -> DynamicTest.dynamicTest(test.path("name").asText(), () -> {
            String text=test.path("text").asText();
            var config=config(test.path("config"));
            var output=AdaptiveChunker.splitWithDiagnostics(text,config);
            checkChunks(test.path("chunks"),output.chunks());
            for (int i=0;i<output.chunks().size();i++) assertEquals(test.path("embeddings").get(i).asText(),output.chunks().get(i).embeddingContent());
            compareJson(test.path("diagnostics"),mapper.valueToTree(output.diagnostics()));
            compareJson(test.path("profile"),mapper.valueToTree(DocumentProfile.of(text)));
            assertEquals(test.path("language").asText(),TokenEstimator.detectLanguage(text));
            var hierarchy=AdaptiveChunker.splitParentChildWithDiagnostics(text,config,config(test.path("child_config")));
            checkChunks(test.path("parent_child").path("Parents"),hierarchy.result().parents());
            JsonNode children=test.path("parent_child").path("Children");
            assertEquals(children.size(),hierarchy.result().children().size());
            for (int i=0;i<children.size();i++) {
                checkChunk(children.get(i),hierarchy.result().children().get(i).chunk());
                assertEquals(children.get(i).path("ParentIndex").asInt(),hierarchy.result().children().get(i).parentIndex());
            }
            compareJson(test.path("parent_diagnostics"),mapper.valueToTree(hierarchy.diagnostics()));
        }));
    }
    private static SplitterConfig config(JsonNode input) {
        List<String> separators=new ArrayList<>(),languages=new ArrayList<>();
        input.path("Separators").forEach(v -> separators.add(v.asText()));
        input.path("Languages").forEach(v -> languages.add(v.asText()));
        return new SplitterConfig(input.path("ChunkSize").asInt(),input.path("ChunkOverlap").asInt(),separators,input.path("Strategy").asText(),input.path("TokenLimit").asInt(),languages);
    }
    private static void checkChunks(JsonNode expected,List<Chunk> actual) {
        assertEquals(expected.size(),actual.size());
        for (int i=0;i<actual.size();i++) checkChunk(expected.get(i),actual.get(i));
    }
    private static void checkChunk(JsonNode expected,Chunk actual) {
        assertEquals(expected.path("Content").asText(),actual.content(),"content");
        assertEquals(expected.path("ContextHeader").asText(),actual.contextHeader(),"context");
        assertEquals(expected.path("Seq").asInt(),actual.seq(),"seq");
        assertEquals(expected.path("Start").asInt(),actual.start(),"start");
        assertEquals(expected.path("End").asInt(),actual.end(),"end");
    }
    private static void compareJson(JsonNode expected,JsonNode actual) {
        if (expected.isNumber() && actual.isNumber()) { assertEquals(expected.asDouble(),actual.asDouble(),1e-10);return; }
        if (expected.isObject() && actual.isObject()) {
            expected.fields().forEachRemaining(field -> compareJson(field.getValue(),actual.path(field.getKey())));
            actual.fields().forEachRemaining(field -> {
                if (!expected.has(field.getKey())) assertEquals(true,field.getValue().isNull(),"unexpected non-null field "+field.getKey());
            });
            return;
        }
        if (expected.isArray() && actual.isArray()) {
            assertEquals(expected.size(),actual.size());
            for (int i=0;i<expected.size();i++) compareJson(expected.get(i),actual.get(i));
            return;
        }
        assertEquals(expected,actual);
    }
}
