package com.tencent.weknora.docreader;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.weknora.model.client.CallContext;
import com.tencent.weknora.model.client.SafeModelHttp;
import io.grpc.Context;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Native HTTP/JSON docreader adapter, using checked DNS pinned to each connection. */
public final class HttpDocumentReader implements RemoteDocumentReader {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration CLIENT_TIMEOUT = Duration.ofMinutes(5);
    private final SafeModelHttp.AddressResolver resolver;
    private final java.util.function.Consumer<String> inputValidation;
    private final SafeModelHttp http;
    private volatile String baseUrl;

    public HttpDocumentReader(String address, SafeModelHttp.AddressResolver resolver) {
        this(address, resolver, target -> {
            try (var call = CallContext.timeout(CLIENT_TIMEOUT)) { resolver.resolve(URI.create(target), call); }
        });
    }

    public HttpDocumentReader(String address, SafeModelHttp.AddressResolver resolver,
                              java.util.function.Consumer<String> inputValidation) {
        this.resolver = resolver;
        this.inputValidation = inputValidation;
        this.baseUrl = checkedAddress(address);
        this.http = new SafeModelHttp(resolver);
    }

    @Override public void reconnect(String address) { baseUrl = checkedAddress(address); }
    @Override public boolean isConnected() { return !baseUrl.isEmpty(); }

    private String checkedAddress(String address) {
        String value = goTrim(address == null ? "" : address);
        if (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        if (!value.isEmpty()) {
            try (var call = CallContext.timeout(CLIENT_TIMEOUT)) { validate(value, call); }
        }
        return value;
    }

    private void validate(String target, CallContext call) {
        try {
            URI uri = URI.create(target);
            if (!List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
                throw new IllegalArgumentException("invalid http/https URL");
            inputValidation.accept(target);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("docreader address failed SSRF validation: " + ex.getMessage(), ex);
        }
    }

    @Override public DocumentReadResult read(DocumentReadRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (request.fileContent().length > 0) body.put("file_content", Base64.getEncoder().encodeToString(request.fileContent()));
        put(body, "file_name", request.fileName()); put(body, "file_type", request.fileType());
        put(body, "url", request.url()); put(body, "title", request.title()); put(body, "request_id", request.requestId());
        Map<String, Object> config = new LinkedHashMap<>();
        put(config, "parser_engine", request.parserEngine());
        if (!request.parserEngineOverrides().isEmpty()) config.put("parser_engine_overrides", request.parserEngineOverrides());
        body.put("config", config);
        return DocReaderHttpJson.read(exchange("read", body));
    }

    @Override public List<DocumentReadResult.ParserEngineInfo> listEngines(Map<String, String> overrides) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (overrides != null && !overrides.isEmpty()) body.put("config_overrides", overrides);
        return DocReaderHttpJson.engines(exchange("list-engines", body));
    }

    private byte[] exchange(String operation, Map<String, Object> body) {
        String base = baseUrl;
        if (base.isEmpty()) throw new IllegalStateException("docreader service not connected");
        Context parent = Context.current();
        Instant deadline = Instant.now().plus(CLIENT_TIMEOUT);
        if (parent.getDeadline() != null) {
            Instant callerDeadline = Instant.now().plusNanos(Math.max(0, parent.getDeadline().timeRemaining(TimeUnit.NANOSECONDS)));
            if (callerDeadline.isBefore(deadline)) deadline = callerDeadline;
        }
        try (var call = new CallContext(deadline)) {
            Context.CancellationListener listener = ignored -> call.cancel();
            parent.addListener(listener, Runnable::run);
            try {
                if (parent.isCancelled()) call.cancel();
                validate(base, call);
                byte[] payload = JSON.writeValueAsBytes(body);
                try (var response = http.post(call, base + "/" + operation,
                        Map.of("Content-Type", "application/json"), payload, CLIENT_TIMEOUT)) {
                    byte[] bytes = response.body().readAllBytes();
                    if (response.status() != 200) throw new IllegalStateException("http " + operation + " status "
                            + response.status() + ": " + GoUtf8.decode(bytes));
                    return bytes;
                }
            } catch (IllegalStateException ex) { throw ex; }
            catch (IOException | RuntimeException ex) { throw new IllegalStateException("http " + operation + " failed: " + ex.getMessage(), ex); }
            finally { parent.removeListener(listener); }
        }
    }

    private static void put(Map<String, Object> target, String key, String value) {
        if (!value.isEmpty()) target.put(key, value);
    }
    static String goTrim(String text) {
        int start = 0, end = text.length();
        while (start < end && GoUtf8.isSpace(text.codePointAt(start))) start += Character.charCount(text.codePointAt(start));
        while (end > start && GoUtf8.isSpace(text.codePointBefore(end))) end -= Character.charCount(text.codePointBefore(end));
        return text.substring(start, end);
    }
    @Override public void close() { http.close(); }
}
