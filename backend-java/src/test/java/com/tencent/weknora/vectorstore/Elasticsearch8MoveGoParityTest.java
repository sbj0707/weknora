package com.tencent.weknora.vectorstore;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import com.tencent.weknora.model.ModelEgressPolicy;
import com.tencent.weknora.model.client.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;

/** Production Go repository/typed-client output replayed through the real Java HTTP adapter. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Elasticsearch8MoveGoParityTest {
  final ObjectMapper json = new ObjectMapper();
  final AtomicReference<JsonNode> current = new AtomicReference<>();
  final List<JsonNode> requests = Collections.synchronizedList(new ArrayList<>());
  HttpServer server;
  ExecutorService workers;
  SafeModelHttp http;
  JsonNode fixture;
  String address;

  @BeforeAll
  void start() throws Exception {
    try (var in = getClass().getResourceAsStream("/go-elasticsearch-index-move.json")) {
      fixture = json.readTree(in);
    }
    workers = Executors.newVirtualThreadPerTaskExecutor();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(workers);
    server.createContext(
        "/",
        exchange -> {
          try {
            var c = current.get();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("X-Elastic-Product", "Elasticsearch");
            if ((exchange.getRequestURI().getPath().endsWith("/_update_by_query")
                    && c.path("no_move_product").asBoolean())
                || (!exchange.getRequestURI().getPath().endsWith("/_update_by_query")
                    && c.path("no_initial_product").asBoolean()))
              exchange.getResponseHeaders().remove("X-Elastic-Product");
            if (!c.path("location").asText().isEmpty())
              exchange.getResponseHeaders().set("Location", c.path("location").asText());
            if (exchange.getRequestMethod().equals("HEAD")) {
              exchange.sendResponseHeaders(200, -1);
              return;
            }
            byte[] body;
            int status = 200;
            if (exchange.getRequestURI().getPath().endsWith("/_mapping")) {
              body =
                  ("{\"vectors\":{\"mappings\":{\"properties\":{\"chunk_id\":{\"type\":\""
                          + c.path("mapping").asText()
                          + "\"}}}}}")
                      .getBytes(StandardCharsets.UTF_8);
            } else {
              assertEquals("/vectors/_update_by_query", exchange.getRequestURI().getPath());
              var request =
                  json.createObjectNode()
                      .put("method", exchange.getRequestMethod())
                      .put("path", exchange.getRequestURI().getPath())
                      .put("query", exchange.getRequestURI().getRawQuery());
              request.set("body", json.readTree(exchange.getRequestBody()));
              requests.add(request);
              if (c.path("drop_first").asBoolean() && requests.size() == 1) return;
              if (c.path("then_success").asBoolean() && requests.size() > 1)
                body = "{\"total\":1,\"updated\":1}".getBytes(StandardCharsets.UTF_8);
              else {
                status = c.path("status").asInt();
                body = c.path("body").asText().getBytes(StandardCharsets.UTF_8);
              }
            }
            exchange.sendResponseHeaders(
                status, status == 204 ? -1 : body.length == 0 ? -1 : body.length);
            if (status != 204 && body.length != 0) exchange.getResponseBody().write(body);
          } finally {
            exchange.close();
          }
        });
    server.start();
    address = "http://127.0.0.1:" + server.getAddress().getPort();
    http =
        new SafeModelHttp(
            new ModelEgressPolicy(
                null, json, new MockEnvironment().withProperty("SSRF_WHITELIST", "127.0.0.1")));
  }

  @AfterAll
  void stop() {
    if (http != null) http.close();
    if (server != null) server.stop(0);
    if (workers != null) workers.shutdownNow();
  }

  @Test
  void schemaMatchesActualGoResponse() {
    assertEquals(fixture.path("schema"), new Elasticsearch8MoveResponse(json).schema());
  }

  @TestFactory
  Stream<DynamicTest> actualGoMove() {
    assertEquals(252, fixture.path("cases").size());
    return StreamSupport.stream(fixture.path("cases").spliterator(), false)
        .map(
            c ->
                DynamicTest.dynamicTest(
                    c.path("name").asText(),
                    () -> {
                      current.set(c);
                      requests.clear();
                      try (var call = new CallContext()) {
                        var engine =
                            new Elasticsearch8Store(
                                new VectorStoreMetadata.Store(
                                    "owned",
                                    1,
                                    "elasticsearch",
                                    json.createObjectNode().put("addr", address),
                                    json.createObjectNode().put("index_name", "vectors")),
                                http,
                                json,
                                call);
                        String error = "";
                        try {
                          engine.moveKnowledgeIndices(
                              c.path("source").asText(),
                              c.path("target").asText(),
                              c.path("knowledge").asText(),
                              Elasticsearch8GoParityIT.strings(c.path("chunks")),
                              c.path("dimension").asInt(),
                              c.path("kind").asText(),
                              call);
                        } catch (RuntimeException failure) {
                          error = failure.getMessage();
                        }
                        assertEquals(c.path("error").asText(), error, "actual Go error");
                        assertEquals(
                            c.path("requests"),
                            json.valueToTree(requests),
                            "actual Go method/path/query/body and retry count");
                      }
                    }));
  }
}
