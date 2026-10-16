package com.tencent.weknora.vectorstore;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import com.tencent.weknora.model.ModelEgressPolicy;
import com.tencent.weknora.model.client.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;

class Elasticsearch8MoveContextGoParityTest {
  final ObjectMapper json = new ObjectMapper();

  @TestFactory
  Stream<DynamicTest> actualGoMoveContext() throws Exception {
    JsonNode contexts;
    try (var in = getClass().getResourceAsStream("/go-elasticsearch-index-move.json")) {
      contexts = json.readTree(in).path("contexts");
    }
    assertEquals(2, contexts.size());
    return StreamSupport.stream(contexts.spliterator(), false)
        .map(
            c ->
                DynamicTest.dynamicTest(
                    c.path("name").asText(),
                    () -> {
                      var entered = new CountDownLatch(1);
                      var release = new CountDownLatch(1);
                      var count = new AtomicInteger();
                      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                      var workers = Executors.newVirtualThreadPerTaskExecutor();
                      server.setExecutor(workers);
                      server.createContext(
                          "/",
                          exchange -> {
                            try {
                              if (exchange.getRequestMethod().equals("HEAD")) {
                                exchange.sendResponseHeaders(200, -1);
                                return;
                              }
                              byte[] body =
                                  "{\"vectors\":{\"mappings\":{\"properties\":{\"chunk_id\":{\"type\":\"keyword\"}}}}}"
                                      .getBytes(StandardCharsets.UTF_8);
                              if (exchange
                                  .getRequestURI()
                                  .getPath()
                                  .endsWith("/_update_by_query")) {
                                count.incrementAndGet();
                                entered.countDown();
                                try {
                                  release.await();
                                } catch (InterruptedException stopped) {
                                  Thread.currentThread().interrupt();
                                }
                                body =
                                    "{\"total\":1,\"updated\":1}".getBytes(StandardCharsets.UTF_8);
                              }
                              exchange.sendResponseHeaders(200, body.length);
                              exchange.getResponseBody().write(body);
                            } finally {
                              exchange.close();
                            }
                          });
                      server.start();
                      try (var http =
                              new SafeModelHttp(
                                  new ModelEgressPolicy(
                                      null,
                                      json,
                                      new MockEnvironment()
                                          .withProperty("SSRF_WHITELIST", "127.0.0.1")));
                          var executor = Executors.newVirtualThreadPerTaskExecutor();
                          var build = new CallContext()) {
                        var engine =
                            new Elasticsearch8Store(
                                new VectorStoreMetadata.Store(
                                    "owned",
                                    1,
                                    "elasticsearch",
                                    json.createObjectNode()
                                        .put(
                                            "addr",
                                            "http://127.0.0.1:" + server.getAddress().getPort()),
                                    json.createObjectNode().put("index_name", "vectors")),
                                http,
                                json,
                                build);
                        try (var call =
                            c.path("deadline").asBoolean()
                                ? CallContext.timeout(Duration.ofMillis(300))
                                : new CallContext()) {
                          var done =
                              executor.submit(
                                  () -> {
                                    try {
                                      engine.moveKnowledgeIndices(
                                          "source", "target", "doc", null, 2, "document", call);
                                      return "success";
                                    } catch (CancellationException cancelled) {
                                      return "cancel";
                                    } catch (ModelClientException deadline) {
                                      assertThrows(ModelClientException.class, call::check);
                                      return "deadline";
                                    }
                                  });
                          assertTrue(entered.await(2, TimeUnit.SECONDS));
                          if (c.path("cancelled").asBoolean()) call.cancel();
                          assertEquals(c.path("name").asText(), done.get(2, TimeUnit.SECONDS));
                          assertTrue(c.path("completed_before_response").asBoolean());
                          assertEquals(1, release.getCount());
                          assertEquals(c.path("requests").asInt(), count.get());
                        }
                      } finally {
                        release.countDown();
                        server.stop(0);
                        workers.shutdownNow();
                      }
                    }));
  }
}
