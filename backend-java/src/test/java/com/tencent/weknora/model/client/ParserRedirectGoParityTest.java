package com.tencent.weknora.model.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ParserRedirectGoParityTest {
    @Test void actualGoRedirectTracesPreserveMethodBodyAndHeaderPolicy() throws Exception {
        var json = new ObjectMapper();
        var fixture = json.readTree(getClass().getResourceAsStream("/go-parser-redirects.json"));
        List<String> keys = List.of("Accept", "Accept-Language", "User-Agent", "Authorization", "Cookie", "Content-Type", "X-Request-ID");
        var active = new AtomicReference<JsonNode>();
        var traces = Collections.synchronizedList(new ArrayList<Map<String,Object>>());
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", exchange -> {
            var headers = new LinkedHashMap<String,String>();
            for (String key : keys) headers.put(key, Objects.toString(exchange.getRequestHeaders().getFirst(key), ""));
            traces.add(Map.of("method", exchange.getRequestMethod(), "path", exchange.getRequestURI().toString(),
                    "body", new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), "headers", headers));
            JsonNode scene = active.get();
            if (exchange.getRequestURI().getPath().equals("/final")) exchange.sendResponseHeaders(200, -1);
            else {
                String target = switch (scene.path("mode").asText()) {
                    case "cross" -> base.replace("127.0.0.1", "localhost") + "/final";
                    case "cycle" -> base + "/start";
                    default -> base + "/final";
                };
                exchange.getResponseHeaders().set("Location", target);
                exchange.sendResponseHeaders(scene.path("redirect_status").asInt(), -1);
            }
            exchange.close();
        });
        server.start();
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        try (var http = new SafeModelHttp((uri, call) -> new InetAddress[]{loopback}, 5, true)) {
            int count = 0;
            for (JsonNode scene : fixture) {
                active.set(scene); traces.clear();
                var headers = new LinkedHashMap<String,String>();
                keys.forEach(key -> headers.put(key, "fixture-" + key));
                String error = ""; int status = 0;
                try (var call = CallContext.timeout(Duration.ofSeconds(5))) {
                    try (var response = switch (scene.path("method").asText()) {
                        case "POST" -> http.post(call, base + "/start", headers, "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), Duration.ofSeconds(5));
                        case "HEAD" -> http.head(call, base + "/start", headers, Duration.ofSeconds(5));
                        default -> http.get(call, base + "/start", headers, Duration.ofSeconds(5));
                    }) { status = response.status(); response.bytes(1024); }
                    catch (ModelClientException ex) {
                        if (ex.getMessage().contains("cross-origin request replay is forbidden")) error = "cross_origin";
                        else if (ex.getMessage().contains("stopped after 5 redirects")) error = "redirect_limit";
                        else throw ex;
                    }
                }
                assertEquals(scene.path("result").asInt(), status, scene.toString());
                assertEquals(scene.path("error_kind").asText(), error, scene.toString());
                assertEquals(scene.path("requests"), json.valueToTree(traces), scene.toString());
                count++;
            }
            assertEquals(18, count);
        } finally { server.stop(0); }
    }
}
