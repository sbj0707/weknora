package com.tencent.weknora.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import com.tencent.weknora.model.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;

class RemoteDocumentDownloadsGoParityTest {
  final ObjectMapper json = new ObjectMapper();
  RemoteDocumentDownloads downloader() {
    var env = new MockEnvironment().withProperty("SSRF_WHITELIST", "127.0.0.1");
    return new RemoteDocumentDownloads(new ModelURLPolicy(null,json,env),new ModelEgressPolicy(null,json,env));
  }

  @TestFactory Stream<DynamicTest> actualGoDownloadAndFilenameObservations() throws Exception {
    JsonNode fixtures;
    String path = System.getProperty("weknora.fileurl.fixture", "");
    if (path.isEmpty()) {
      try (var input = getClass().getResourceAsStream("/go-fileurl-download.json")) {
        assertNotNull(input, "Actual Go download oracle must be exported"); fixtures = json.readTree(input);
      }
    } else fixtures = json.readTree(Path.of(path).toFile());
    var cases = new ArrayList<DynamicTest>();
    for (var fixture : fixtures) {
      if (fixture.has("header")) cases.add(DynamicTest.dynamicTest("header: " + fixture.path("header").asText(),
          () -> assertEquals(fixture.path("expected").asText(),RemoteDocumentDownloads.dispositionFilename(fixture.path("header").asText()))));
      else if (fixture.has("url")) cases.add(DynamicTest.dynamicTest("path: " + fixture.path("url").asText(),
          () -> assertEquals(fixture.path("expected").asText(),RemoteDocumentDownloads.urlFilename(fixture.path("url").asText()))));
      else cases.add(DynamicTest.dynamicTest(fixture.path("input").path("name").asText(), () -> replay(fixture)));
    }
    return cases.stream();
  }

  void replay(JsonNode fixture) throws Exception {
    var input = fixture.path("input");
    byte[] data = new byte[input.path("size").asInt() == 0 ? 23 : input.path("size").asInt()]; Arrays.fill(data,(byte)'x');
    var requests = new CopyOnWriteArrayList<String>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/", e -> {
      try {
        requests.add(e.getRequestURI().getRawPath());
        if (input.path("redirect").asBoolean() && !e.getRequestURI().getPath().equals("/redirected.pdf")) {
          e.getResponseHeaders().set("Location","/redirected.pdf");e.sendResponseHeaders(302,-1);return;
        }
        if (!input.path("disposition").asText().isEmpty()) e.getResponseHeaders().set("Content-Disposition",input.path("disposition").asText());
        int status = input.path("status").asInt(); if (status == 0) status = 200;
        e.sendResponseHeaders(status,status == 204 ? -1 : input.path("chunked").asBoolean() ? 0 : data.length);
        if (status != 204) e.getResponseBody().write(data);
      } catch (IOException ignored) { /* Expected when size rejection closes the response early. */ }
      finally { e.close(); }
    }); server.start();
    try (var download = downloader()) {
      String url = "http://127.0.0.1:" + server.getAddress().getPort() + input.path("path").asText();
      if (!fixture.path("error").asText().isEmpty()) {
        var error = assertThrows(IOException.class, () -> download.download(url,input.path("filename").asText(),input.path("file_type").asText(),() -> {}));
        assertEquals(fixture.path("error").asText(),error.getMessage());
      } else {
        var result = download.download(url,input.path("filename").asText(),input.path("file_type").asText(),() -> {});
        assertEquals(fixture.path("filename").asText(),result.filename());
        assertEquals(fixture.path("file_type").asText(),result.fileType());
        assertEquals(fixture.path("size").asInt(),result.bytes().length);
        assertEquals(fixture.path("sha256").asText(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(result.bytes())));
      }
      assertEquals(input.path("redirect").asBoolean() ? 2 : 1,requests.size());
    } finally { server.stop(0); }
  }

  @Test void cancellationClosesAHangingResponseBeforeItsNetworkDeadline() throws Exception {
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var canceled = new AtomicBoolean();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/",e -> {try {e.sendResponseHeaders(200,0);e.getResponseBody().flush();entered.countDown();release.await(5,TimeUnit.SECONDS);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}finally{e.close();}});
    server.start();
    try (var download = downloader(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var future = executor.submit(() -> download.download("http://127.0.0.1:"+server.getAddress().getPort()+"/hanging.txt","","",() -> {if(canceled.get())throw new CancellationException("lease lost");}));
      assertTrue(entered.await(3,TimeUnit.SECONDS));canceled.set(true);
      var error = assertThrows(ExecutionException.class,() -> future.get(3,TimeUnit.SECONDS));
      assertInstanceOf(CancellationException.class,error.getCause());assertEquals("lease lost",error.getCause().getMessage());
    } finally {release.countDown();server.stop(0);}
  }

  @Test void everyRedirectChecksItsOwnHostBeforeConnecting() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/",e -> {e.getResponseHeaders().set("Location","http://169.254.169.254/latest/meta-data");e.sendResponseHeaders(302,-1);e.close();});server.start();
    try (var download=downloader()) {
      var error=assertThrows(IOException.class,() -> download.download("http://127.0.0.1:"+server.getAddress().getPort()+"/a.txt","","",() -> {}));
      assertTrue(error.getMessage().contains("blocked by SSRF policy"),error.getMessage());
    } finally {server.stop(0);}
  }
}
