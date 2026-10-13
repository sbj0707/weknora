package com.tencent.weknora.chunker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GoChunkerCompatibilityTest {
    @TestFactory
    Stream<DynamicTest> actualProductionGoOutputs() throws Exception {
        JsonNode fixtures;
        try (var input = new GZIPInputStream(getClass().getResourceAsStream("/go-chunker.json.gz"))) {
            fixtures = new ObjectMapper().readTree(input);
        }
        assertEquals(254, fixtures.size());
        return StreamSupport.stream(fixtures.spliterator(), false).map(test ->
                DynamicTest.dynamicTest(test.path("name").asText(), () -> {
                    String text = test.path("text").asText();
                    SplitterConfig config = config(test.path("config"));
                    List<Chunk> chunks = TextSplitter.splitText(text, config);
                    checkChunks(test.path("chunks"), chunks);
                    for (int i=0; i<chunks.size(); i++) assertEquals(test.path("embeddings").get(i).asText(), chunks.get(i).embeddingContent());
                    var hierarchy = TextSplitter.splitTextParentChild(text, config, config(test.path("child_config")));
                    checkChunks(test.path("parent_child").path("Parents"), hierarchy.parents());
                    JsonNode children = test.path("parent_child").path("Children");
                    assertEquals(children.size(), hierarchy.children().size());
                    for (int i=0; i<hierarchy.children().size(); i++) {
                        checkChunk(children.get(i), hierarchy.children().get(i).chunk());
                        assertEquals(children.get(i).path("ParentIndex").asInt(), hierarchy.children().get(i).parentIndex());
                    }
                    var images = TextSplitter.extractImageRefs(text);
                    assertEquals(test.path("images").size(), images.size());
                    for (int i=0; i<images.size(); i++) {
                        JsonNode expected = test.path("images").get(i);
                        var actual = images.get(i);
                        assertEquals(expected.path("OriginalRef").asText(), actual.originalRef());
                        assertEquals(expected.path("AltText").asText(), actual.altText());
                        assertEquals(expected.path("Start").asInt(), actual.start());
                        assertEquals(expected.path("End").asInt(), actual.end());
                    }
                }));
    }
    private static SplitterConfig config(JsonNode json) {
        List<String> separators = new ArrayList<>();
        json.path("Separators").forEach(value -> separators.add(value.asText()));
        return new SplitterConfig(json.path("ChunkSize").asInt(), json.path("ChunkOverlap").asInt(), separators);
    }
    private static void checkChunks(JsonNode expected, List<Chunk> chunks) {
        assertEquals(expected.size(), chunks.size());
        for (int i=0; i<chunks.size(); i++) checkChunk(expected.get(i), chunks.get(i));
    }
    private static void checkChunk(JsonNode expected, Chunk actual) {
        assertEquals(expected.path("Content").asText(), actual.content(), "content");
        assertEquals(expected.path("ContextHeader").asText(), actual.contextHeader(), "context header");
        assertEquals(expected.path("Seq").asInt(), actual.seq(), "seq");
        assertEquals(expected.path("Start").asInt(), actual.start(), "start rune offset");
        assertEquals(expected.path("End").asInt(), actual.end(), "end rune offset");
    }
}
