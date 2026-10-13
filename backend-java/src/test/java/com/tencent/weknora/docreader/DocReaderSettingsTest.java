package com.tencent.weknora.docreader;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DocReaderSettingsTest {
    @Test void environmentNamesAndDefaultsMatchGo() {
        var defaults = DocReaderSettings.fromEnvironment(Map.of());
        assertEquals(50 * 1024 * 1024, defaults.maxMessageBytes());
        assertEquals(Duration.ofMinutes(30), defaults.callTimeout());
        assertEquals("", defaults.address()); assertFalse(defaults.tlsEnabled());
        var configured = DocReaderSettings.fromEnvironment(Map.of("DOCREADER_ADDR", "reader:50051", "MAX_FILE_SIZE_MB", "12", "GRPC_TLS_ENABLED", "true", "GRPC_TLS_CA", "/tmp/ca.pem", "GRPC_TLS_SERVER_NAME", "reader.test", "GRPC_AUTH_TOKEN", "test-only", "WEKNORA_DOCREADER_CALL_TIMEOUT", "1h2m3.5s"));
        assertEquals("reader:50051", configured.address()); assertTrue(configured.tlsEnabled());
        assertEquals(12 * 1024 * 1024, configured.maxMessageBytes());
        assertEquals(Path.of("/tmp/ca.pem"), configured.ca());
        assertEquals("reader.test", configured.serverName());
        assertEquals("test-only", configured.authToken());
        assertEquals(Duration.ofHours(1).plusMinutes(2).plusMillis(3500), configured.callTimeout());
    }

    @Test void invalidEnvironmentDurationsAndMessageLimitsUseGoDefaults() {
        for (String value : new String[]{"", "0", "0s", "-1m", "not-a-duration", "1s junk", "1d", "999999999999h"}) {
            assertEquals(Duration.ofMinutes(30), DocReaderSettings.parseCallTimeout(value), value);
        }
        assertEquals(Duration.ofNanos(1500), DocReaderSettings.parseCallTimeout("1.5µs"));
        assertEquals(Duration.ofNanos(2), DocReaderSettings.parseCallTimeout("1.5ns1.5ns"));
        assertEquals(Duration.ofMillis(500), DocReaderSettings.parseCallTimeout(" +.5s "));
        for (String value : new String[]{"no", "0", "-1", "999999999999", "2048"}) {
            assertEquals(50 * 1024 * 1024, DocReaderSettings.fromEnvironment(Map.of("MAX_FILE_SIZE_MB", value)).maxMessageBytes());
        }
    }

    @Test void mtlsCertificateAndKeyMustBePairedWhenTlsIsEnabled() {
        assertThrows(IllegalArgumentException.class, () -> DocReaderSettings.fromEnvironment(Map.of("GRPC_TLS_ENABLED", "true", "GRPC_TLS_CERT", "/tmp/cert.pem")));
        assertDoesNotThrow(() -> DocReaderSettings.fromEnvironment(Map.of("GRPC_TLS_ENABLED", "false", "GRPC_TLS_CERT", "/tmp/cert.pem")));
    }
}
