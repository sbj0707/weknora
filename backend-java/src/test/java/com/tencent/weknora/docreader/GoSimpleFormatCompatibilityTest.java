package com.tencent.weknora.docreader;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class GoSimpleFormatCompatibilityTest {
    private final SimpleFormatReader reader = new SimpleFormatReader();

    @TestFactory Stream<DynamicTest> actualGoSimpleReaderFixtures() throws Exception {
        JsonNode inputs;
        try (var in = getClass().getResourceAsStream("/go-simple-reader.json")) { assertNotNull(in); inputs = new ObjectMapper().readTree(in); }
        assertEquals(71, inputs.size());
        return IntStream.range(0, inputs.size()).mapToObj(i -> DynamicTest.dynamicTest("Go fixture " + i + " " + inputs.get(i).get("file_name").asText(), () -> verify(inputs.get(i))));
    }

    private void verify(JsonNode input) {
        var request = new DocumentReadRequest(Base64.getDecoder().decode(input.get("data").asText()), input.get("file_name").asText(), input.get("file_type").asText(), "", "", "", "", Map.of());
        String error = input.get("error").asText();
        if (!error.isEmpty()) { assertEquals(error, assertThrows(IllegalArgumentException.class, () -> reader.read(request)).getMessage()); return; }
        var actual = reader.read(request); var expected = input.get("expected");
        assertEquals(expected.get("MarkdownContent").asText(), actual.markdownContent());
        assertEquals(expected.get("ImageDirPath").asText(), actual.imageDirPath());
        assertEquals(expected.get("Error").asText(), actual.error());
        assertEquals(expected.get("IsAudio").asBoolean(), actual.isAudio());
        assertArrayEquals(bytes(expected.get("AudioData")), actual.audioData());
        JsonNode images = expected.get("ImageRefs");
        assertEquals(images.isNull() ? 0 : images.size(), actual.imageRefs().size());
        for (int i=0;i<actual.imageRefs().size();i++) {
            var image = actual.imageRefs().get(i); var original = images.get(i);
            assertEquals(original.get("Filename").asText(), image.filename());
            assertEquals(original.get("OriginalRef").asText(), image.originalRef());
            assertEquals(original.get("MimeType").asText(), image.mimeType());
            assertEquals(original.get("StorageKey").asText(), image.storageKey());
            assertEquals(original.get("IsOriginal").asBoolean(), image.isOriginal());
            assertArrayEquals(bytes(original.get("ImageData")), image.imageData());
        }
        assertTrue(actual.metadata().isEmpty()); assertTrue(actual.sourceBlocks().isEmpty());
    }
    private byte[] bytes(JsonNode node) { return node == null || node.isNull() ? new byte[0] : Base64.getDecoder().decode(node.asText()); }

    @Test void standaloneImageEvidenceSurvivesOcrAndIsNotDuplicatedWhenAlreadyPresent() {
        var request = new DocumentReadRequest(new byte[]{(byte)137,80,78,71,13,10,26,10}, "scan.png", "png", "", "", "", "", Map.of());
        var text = new DocumentReadResult("OCR 正文", List.of(), "dir", Map.of("parser", "ocr"), "", List.of());
        var result = SimpleFormatReader.ensureOriginalImage(request, text);
        assertEquals("![scan.png](images/scan.png)\n\nOCR 正文", result.markdownContent());
        assertTrue(result.imageRefs().getFirst().isOriginal());
        assertArrayEquals(request.fileContent(), result.imageRefs().getFirst().imageData());
        assertSame(result, SimpleFormatReader.ensureOriginalImage(request, result));
    }

    @Test void formatDetectionAndAudioPreserveOriginalBytesWithoutTranscribing() {
        for (String type : List.of(".MD", "csv", "json", "png", "mp3", "ogg")) assertTrue(SimpleFormatReader.isSimpleFormat(type));
        assertFalse(SimpleFormatReader.isSimpleFormat("pdf")); assertFalse(SimpleFormatReader.isSimpleFormat("..md"));
        var request = new DocumentReadRequest(new byte[]{0,1,(byte)255}, "speech.M4A", "", "", "", "", "", Map.of());
        var result = reader.read(request); assertTrue(result.isAudio()); assertArrayEquals(request.fileContent(), result.audioData());
        assertEquals("[Audio file: speech.M4A]", result.markdownContent());
    }
}
