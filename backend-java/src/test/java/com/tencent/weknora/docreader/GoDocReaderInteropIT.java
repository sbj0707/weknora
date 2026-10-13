package com.tencent.weknora.docreader;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Explicit live Go interop suite, excluded from normal unit tests by its IT suffix. */
class GoDocReaderInteropIT {
    @Test void javaCallsGoStreamingLegacyAndEngineRpcs() throws Exception {
        String address = System.getProperty("docreader.interop.address");
        assertNotNull(address, "Start src/test/fixtures/docreader_server.go and provide -Ddocreader.interop.address");
        try (var client = new DocReaderClient(settings(address, false))) { verify(client); }
    }

    @Test void javaCallsGoWithVerifiedMutualTlsAndBearerToken() throws Exception {
        String address = System.getProperty("docreader.interop.tls-address");
        assertNotNull(address, "Start Go fixture with --tls-cert/--tls-key/--client-ca and provide -Ddocreader.interop.tls-address");
        try (var client = new DocReaderClient(settings(address, true))) { verify(client); }
    }

    private DocReaderSettings settings(String address, boolean tls) {
        Path resources = Path.of("src/test/resources/docreader-tls");
        return new DocReaderSettings(address, tls, tls ? resources.resolve("client.pem") : null,
                tls ? resources.resolve("client.key") : null, tls ? resources.resolve("ca.pem") : null,
                tls ? "docreader.test" : "", "interop-test-token", 50 * 1024 * 1024, Duration.ofSeconds(5));
    }

    private void verify(DocReaderClient client) {
        for (String engine : List.of("builtin", "legacy")) {
            var request = new DocumentReadRequest(new byte[]{0, 1, (byte)255}, "文档.pdf", "pdf", "", "测试", engine, "request-42", Map.of("ocr", "true"));
            var result = client.read(request);
            assertEquals("Go→Java 😀中文", result.markdownContent());
            assertEquals(Map.of("parser", engine, "request_id", "request-42"), result.metadata());
            assertEquals("/tmp/go-images", result.imageDirPath());
            assertArrayEquals(new byte[]{0, 1, (byte)255}, result.imageRefs().getFirst().imageData());
            assertEquals(3L, result.sourceBlocks().getFirst().locator().get("page"));
        }
        var engines = client.listEngines(Map.of("description", "interop description"));
        assertEquals(1, engines.size()); assertTrue(engines.getFirst().available());
        assertEquals("interop description", engines.getFirst().description());
        assertEquals(List.of("pdf", "docx"), engines.getFirst().fileTypes());
    }
}
