package com.tencent.weknora.docreader;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Existing Go deployment knobs, including Go-style duration units. */
public record DocReaderSettings(String address, boolean tlsEnabled, Path certificate, Path key,
                                Path ca, String serverName, String authToken,
                                int maxMessageBytes, Duration callTimeout) {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(30);
    private static final Pattern DURATION_PART = Pattern.compile("([0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(ns|us|µs|μs|ms|s|m|h)");

    public DocReaderSettings {
        address = address == null ? "" : address;
        serverName = serverName == null ? "" : serverName;
        authToken = authToken == null ? "" : authToken;
        if (maxMessageBytes <= 0) throw new IllegalArgumentException("maxMessageBytes must be positive");
        if (callTimeout == null || callTimeout.isNegative() || callTimeout.isZero()) {
            throw new IllegalArgumentException("callTimeout must be positive");
        }
        if (tlsEnabled && (certificate == null) != (key == null)) {
            throw new IllegalArgumentException("GRPC_TLS_CERT and GRPC_TLS_KEY must be set together for mTLS");
        }
    }

    public static DocReaderSettings fromEnvironment(Map<String, String> env) {
        return fromEnvironment(env::get);
    }

    public static DocReaderSettings fromEnvironment(Function<String, String> get) {
        int maxBytes = 50 * 1024 * 1024;
        try {
            int mb = Integer.parseInt(value(get, "MAX_FILE_SIZE_MB"));
            if (mb > 0) maxBytes = Math.multiplyExact(mb, 1024 * 1024);
        } catch (NumberFormatException | ArithmeticException ignored) { }
        return new DocReaderSettings(value(get, "DOCREADER_ADDR"), "true".equals(get.apply("GRPC_TLS_ENABLED")),
                path(get, "GRPC_TLS_CERT"), path(get, "GRPC_TLS_KEY"), path(get, "GRPC_TLS_CA"),
                value(get, "GRPC_TLS_SERVER_NAME"), value(get, "GRPC_AUTH_TOKEN"), maxBytes,
                parseCallTimeout(value(get, "WEKNORA_DOCREADER_CALL_TIMEOUT")));
    }

    public DocReaderSettings withAddress(String newAddress) {
        return new DocReaderSettings(newAddress, tlsEnabled, certificate, key, ca, serverName,
                authToken, maxMessageBytes, callTimeout);
    }

    static Duration parseCallTimeout(String text) {
        String input = text.trim();
        if (input.startsWith("+")) input = input.substring(1);
        Matcher matcher = DURATION_PART.matcher(input);
        int end = 0;
        java.math.BigDecimal nanos = java.math.BigDecimal.ZERO;
        while (matcher.find()) {
            if (matcher.start() != end) return DEFAULT_TIMEOUT;
            long unit = switch (matcher.group(2)) {
                case "h" -> 3_600_000_000_000L;
                case "m" -> 60_000_000_000L;
                case "s" -> 1_000_000_000L;
                case "ms" -> 1_000_000L;
                case "us", "µs", "μs" -> 1_000L;
                default -> 1L;
            };
            // Go truncates each fractional component to nanoseconds before summing.
            nanos = nanos.add(new java.math.BigDecimal(new java.math.BigDecimal(matcher.group(1))
                    .multiply(java.math.BigDecimal.valueOf(unit)).toBigInteger()));
            end = matcher.end();
        }
        if (end != input.length() || end == 0) return DEFAULT_TIMEOUT;
        try {
            long value = nanos.toBigInteger().longValueExact();
            return value > 0 ? Duration.ofNanos(value) : DEFAULT_TIMEOUT;
        } catch (ArithmeticException ignored) { return DEFAULT_TIMEOUT; }
    }

    private static String value(Function<String, String> get, String key) {
        String value = get.apply(key);
        return value == null ? "" : value;
    }
    private static Path path(Function<String, String> get, String key) {
        String value = value(get, key);
        return value.isEmpty() ? null : Path.of(value);
    }
}
