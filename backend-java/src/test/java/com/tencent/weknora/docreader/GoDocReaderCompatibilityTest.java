package com.tencent.weknora.docreader;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import docreader.Docreader;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GoDocReaderCompatibilityTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private JsonNode fixture() throws Exception {
        try (var stream = getClass().getResourceAsStream("/go-docreader.json")) {
            assertNotNull(stream); return JSON.readTree(stream);
        }
    }

    @Test void generatedJavaRequestMatchesRealGoProtobuf() throws Exception {
        var expected = Docreader.ReadRequest.parseFrom(Base64.getDecoder().decode(fixture().get("request").asText()));
        var request = new DocumentReadRequest(new byte[]{0, 1, (byte)255}, "文档.pdf", "pdf", "", "测试", "builtin", "request-42", Map.of("ocr", "true"));
        assertEquals(expected, request.toProto());
        assertEquals("docreader.DocReader", docreader.DocReaderGrpc.SERVICE_NAME);
    }

    @Test void canonicalGoStreamAndUnaryFramesDecodeInJava() throws Exception {
        var fixture = fixture();
        var header = Docreader.ReadStreamResponse.parseFrom(Base64.getDecoder().decode(fixture.get("stream_frames").get(0).asText()));
        var image = Docreader.ReadStreamResponse.parseFrom(Base64.getDecoder().decode(fixture.get("stream_frames").get(1).asText()));
        var unary = Docreader.ReadResponse.parseFrom(Base64.getDecoder().decode(fixture.get("unary").asText()));
        assertTrue(header.hasMeta()); assertTrue(image.hasImage());
        assertEquals("😀中\n正文", header.getMeta().getMarkdownContent());
        assertEquals(header.getMeta().getMarkdownContent(), unary.getMarkdownContent());
        assertEquals(image.getImage(), unary.getImageRefs(0));
        assertEquals(header.getMeta().getSourceBlocksList(), unary.getSourceBlocksList());
    }

    @Test void sourceMappingsMatchActualGoTypedUnmarshalAndSerialization() throws Exception {
        int checked = 0;
        for (var item : fixture().get("source_cases")) {
            var input = Docreader.SourceBlock.newBuilder().setStart((int)item.get("start").asLong())
                    .setEnd((int)item.get("end").asLong()).setLocatorJson(item.get("locator_json").asText()).build();
            var actual = SourceBlockDecoder.decode(List.of(input));
            var expected = item.get("expected");
            String scenario = item.toString();
            if (expected.isNull()) assertTrue(actual.isEmpty(), scenario);
            else {
                assertEquals(1, actual.size(), scenario);
                assertEquals(expected.get("Start").asLong(), actual.getFirst().start(), scenario);
                assertEquals(expected.get("End").asLong(), actual.getFirst().end(), scenario);
                JsonNode output = JSON.readTree(JSON.writeValueAsBytes(actual.getFirst().locator()));
                assertTrue(expected.get("Locator").equals((left, right) -> {
                    if (left.isNumber() && right.isNumber()) return left.decimalValue().compareTo(right.decimalValue());
                    return left.equals(right) ? 0 : 1;
                }, output), scenario + " actual=" + output);
            }
            checked++;
        }
        assertEquals(138, checked);
    }
}
