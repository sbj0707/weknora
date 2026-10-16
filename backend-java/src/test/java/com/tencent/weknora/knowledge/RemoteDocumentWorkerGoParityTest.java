package com.tencent.weknora.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import com.tencent.weknora.docreader.*;
import com.tencent.weknora.model.*;
import com.tencent.weknora.task.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

/** Production Go ProcessDocument observations versus the native SQL/reader/queue pipeline. */
class RemoteDocumentWorkerGoParityTest {
  @TempDir Path directory;
  final ObjectMapper json = new ObjectMapper();

  @TestFactory Stream<DynamicTest> actualGoRemoteDocumentWorker() throws Exception {
    JsonNode fixtures;
    try (var input = getClass().getResourceAsStream("/go-remote-document-worker.json")) {
      assertNotNull(input, "Actual Go worker oracle is required"); fixtures = json.readTree(input);
    }
    var cases = new ArrayList<DynamicTest>(); int number=0;
    for (var fixture : fixtures) {int caseNumber=number++;cases.add(DynamicTest.dynamicTest(fixture.path("input").path("name").asText(),() -> replay(fixture,caseNumber)));}
    return cases.stream();
  }

  void replay(JsonNode fixture,int number) throws Exception {
    var f = new DocumentProcessingCompatibilityTest(); f.temp=directory;f.database(number,false);
    var input=fixture.path("input");
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/",e -> {try {
      int status=input.path("status").asInt(200);if(status==0)status=200;
      e.getResponseHeaders().set("Content-Disposition","attachment; filename=\"discovered.TXT\"");
      byte[] body="downloaded body".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      e.sendResponseHeaders(status,body.length);e.getResponseBody().write(body);
    }finally{e.close();}});server.start();
    String origin="http://127.0.0.1:"+server.getAddress().getPort();
    var environment=new MockEnvironment().withProperty("SSRF_WHITELIST","127.0.0.1");
    var requests=json.createArrayNode();var saved=json.createArrayNode();var opened=json.createArrayNode();
    var data=new AtomicReference<>(new byte[0]);
    try(var downloads=new RemoteDocumentDownloads(new ModelURLPolicy(f.jdbc,json,environment),new ModelEgressPolicy(f.jdbc,json,environment))) {
      var row=f.seed(json.createObjectNode().put("file_type",input.path("file_type").asText()));
      f.jdbc.update("UPDATE knowledges SET type=?,source=?,title=?,file_name=?,file_type=?,file_path='',file_hash='' WHERE id='knowledge'",
          input.path("mode").asText(),origin+"/page",input.path("title").asText().replace("$ORIGIN",origin),input.path("filename").asText(),input.path("file_type").asText());
      row=f.knowledge.get(1,"knowledge").orElseThrow();
      var payload=json.createObjectNode().put("tenant_id",1).put("knowledge_base_id","kb").put("knowledge_id","knowledge")
          .put("file_name",input.path("filename").asText()).put("file_type",input.path("file_type").asText()).put("request_id","remote-request").put("language","zh-CN");
      payload.put(input.path("mode").asText().equals("url")?"url":"file_url",origin+(input.path("mode").asText().equals("url")?"/page":"/download"));
      f.tasks.enqueue(1,"document:process",UUID.randomUUID().toString(),payload,1,Duration.ZERO,"default",Duration.ofMinutes(30));
      var job=f.tasks.claim("worker",Duration.ofMinutes(5),List.of("default")).orElseThrow();
      var handler=new DocumentProcessingHandler(f.processing,json,(owner,kb,path) -> {opened.add(path);return new ByteArrayInputStream(data.get());},
          (engine,type) -> {
            if(engine.equals("simple")||engine.isEmpty()&&SimpleFormatReader.isSimpleFormat(type))return new SimpleFormatReader();
            if(input.path("no_reader").asBoolean())return null;
            return request -> {
              requests.add(json.createObjectNode().put("url",request.url().replace(origin,"$ORIGIN")).put("title",request.title().replace(origin,"$ORIGIN"))
                  .put("filename",request.fileName()).put("file_type",request.fileType()).put("content",new String(request.fileContent(),java.nio.charset.StandardCharsets.UTF_8))
                  .put("engine",request.parserEngine()).put("request_id",request.requestId()));
              if(input.path("reader_failure").asBoolean())throw new IllegalStateException("controlled reader failure");
              return new DocumentReadResult("remote parsed body",List.of(),"",Map.of("title",input.path("extracted_title").asText()),input.path("parser_error").asText(),List.of());
            };
          },(owner,id) -> {throw new AssertionError("Embedding disabled in this Go worker corpus");},(owner,kb) -> f.index,NativeDocumentSources::build,f.enqueuer())
          .withRemoteSources(downloads,(owner,kb,bytes,filename) -> {
            saved.add(json.createObjectNode().put("tenant",owner).put("filename",filename).put("temporary",true).put("content",new String(bytes,java.nio.charset.StandardCharsets.UTF_8)));
            if(input.path("save_failure").asBoolean())throw new IOException("controlled storage failure");data.set(bytes);return "downloaded";
          });
      String error="";try{handler.execute(job,f.cancellation(job));}catch(TaskFailure failure){error=failure.getMessage();}
      assertEquals(fixture.path("error").asText(),error,"worker returned error");
      var current=f.knowledge.get(1,"knowledge").orElseThrow();var actual=json.createObjectNode();
      for(String key:List.of("title","file_name","file_type","file_path","parse_status","enable_status"))actual.put(key,current.path(key).asText().replace(origin,"$ORIGIN"));
      actual.put("error",current.path("error_message").asText());assertEquals(fixture.path("row"),actual,"knowledge row");
      var chunks=json.createArrayNode();for(var chunk:f.jdbc.queryForList("SELECT content,chunk_type,chunk_index FROM chunks WHERE knowledge_id='knowledge' AND deleted_at IS NULL ORDER BY chunk_index"))
        chunks.add(json.createObjectNode().put("content",chunk.get("content").toString()).put("type",chunk.get("chunk_type").toString()).put("index",((Number)chunk.get("chunk_index")).intValue()));
      assertEquals(fixture.path("chunks"),chunks,"persisted chunks");assertEquals(fixture.path("requests"),requests,"reader requests");
      assertEquals(fixture.path("saved"),json.readTree(json.writeValueAsBytes(saved)),"storage writes");assertEquals(fixture.path("opened"),opened,"storage reads");
      var tasks=json.createArrayNode();f.enqueues.forEach(e -> tasks.add(e.path("type").asText()));assertEquals(fixture.path("tasks"),tasks,"postprocess submission");
    }finally{server.stop(0);}
  }

  @TestFactory Stream<DynamicTest> nativeDownloadStopsBeforeStorageAfterSourceCancelOrLeaseChange() {
    return Stream.of("source", "cancel", "lease").map(kind -> DynamicTest.dynamicTest(kind, () -> {
      var f=new DocumentProcessingCompatibilityTest();f.temp=directory;f.database(100+List.of("source","cancel","lease").indexOf(kind),false);
      var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var saves=new AtomicInteger();
      var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
      server.createContext("/",e -> {try{e.sendResponseHeaders(200,0);e.getResponseBody().flush();entered.countDown();release.await(5,TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();}finally{e.close();}});server.start();
      var env=new MockEnvironment().withProperty("SSRF_WHITELIST","127.0.0.1");
      try(var downloads=new RemoteDocumentDownloads(new ModelURLPolicy(f.jdbc,json,env),new ModelEgressPolicy(f.jdbc,json,env));var executor=Executors.newVirtualThreadPerTaskExecutor()) {
        f.seed(json.createObjectNode().put("file_type","txt"));String url="http://127.0.0.1:"+server.getAddress().getPort()+"/download.txt";
        f.jdbc.update("UPDATE knowledges SET type='file_url',source=?,file_path='',file_name='',file_type='' WHERE id='knowledge'",url);
        var payload=json.createObjectNode().put("tenant_id",1).put("knowledge_base_id","kb").put("knowledge_id","knowledge").put("file_url",url);
        f.tasks.enqueue(1,"document:process",UUID.randomUUID().toString(),payload,1,Duration.ZERO,"default",Duration.ofMinutes(30));
        var job=f.tasks.claim("worker",Duration.ofMinutes(5),List.of("default")).orElseThrow();
        var handler=f.handler((owner,kb,path)->{throw new AssertionError("Canceled download cannot read a file");},(owner,id)->{throw new AssertionError("Canceled download cannot embed");},f.index)
            .withRemoteSources(downloads,(owner,kb,bytes,name)->{saves.incrementAndGet();return "unexpected";});
        var running=executor.submit(()->handler.execute(job,f.cancellation(job)));assertTrue(entered.await(3,TimeUnit.SECONDS));
        switch(kind){case "source" -> f.jdbc.update("UPDATE knowledges SET source='https://new-source.example/document.txt' WHERE id='knowledge'");
          case "cancel" -> assertTrue(f.tasks.cancel(1,job.id()));
          case "lease" -> f.jdbc.update("UPDATE java_task_jobs SET attempts=attempts+1,lease_owner='successor' WHERE id=?",job.id());}
        var error=assertThrows(ExecutionException.class,()->running.get(3,TimeUnit.SECONDS));assertInstanceOf(CancellationException.class,error.getCause());
        assertEquals(0,saves.get());assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM chunks",Integer.class));
        assertEquals("processing",f.knowledge.get(1,"knowledge").orElseThrow().path("parse_status").asText());
        assertEquals("",f.knowledge.get(1,"knowledge").orElseThrow().path("file_name").asText());
      } finally {release.countDown();server.stop(0);}
    }));
  }
}
