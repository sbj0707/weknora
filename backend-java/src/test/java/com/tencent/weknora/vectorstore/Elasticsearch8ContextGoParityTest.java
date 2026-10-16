package com.tencent.weknora.vectorstore;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import com.tencent.weknora.model.ModelEgressPolicy;
import com.tencent.weknora.model.client.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;

/** Actual HTTP constructor cancellation, replaying corrected production Go factory outcomes. */
class Elasticsearch8ContextGoParityTest {
  final ObjectMapper json=new ObjectMapper();
  @TestFactory Stream<DynamicTest> actualGoBuildContext()throws Exception{JsonNode cases;try(var in=getClass().getResourceAsStream("/go-vector-store-es-context.json")){cases=json.readTree(in);}assertEquals(2,cases.size());return StreamSupport.stream(cases.spliterator(),false).map(c->DynamicTest.dynamicTest(c.path("name").asText(),()->{
    var count=new AtomicInteger();var entered=new CountDownLatch(1);var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var workers=Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(workers);server.createContext("/",exchange->{try{if(exchange.getRequestURI().getPath().equals("/warm")){exchange.sendResponseHeaders(200,-1);return;}count.incrementAndGet();entered.countDown();try{Thread.sleep(800);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}exchange.sendResponseHeaders(404,-1);}finally{exchange.close();}});server.start();
    try(var http=new SafeModelHttp(new ModelEgressPolicy(null,json,new MockEnvironment().withProperty("SSRF_WHITELIST","127.0.0.1")));var executor=Executors.newVirtualThreadPerTaskExecutor()){
      String address="http://127.0.0.1:"+server.getAddress().getPort();try(var warm=new CallContext();var response=http.get(warm,address+"/warm",Map.of(),Duration.ofSeconds(2))){assertEquals(200,response.status());}
      // A generous supplied budget makes cold JVM scheduling irrelevant; the actual stalled HTTP must terminate at that budget.
      try(var call=c.path("name").asText().equals("deadline")?CallContext.timeout(Duration.ofMillis(300)):new CallContext()){
        var future=executor.submit(()->{try{new Elasticsearch8Store(new VectorStoreMetadata.Store("store",1,"elasticsearch",json.createObjectNode().put("addr",address),json.createObjectNode().put("index_name","fixture_context")),http,json,call);return true;}catch(RuntimeException error){assertThrows(RuntimeException.class,call::check);return false;}});
        assertTrue(entered.await(2,TimeUnit.SECONDS));if(c.path("name").asText().equals("cancel"))call.cancel();assertEquals(c.path("returned_engine").asBoolean(),future.get(2,TimeUnit.SECONDS));assertEquals(c.path("http_requests").asInt(),count.get());assertTrue(c.path("error_is_context").asBoolean());assertTrue(c.path("request_context_done").asBoolean());
      }
    }finally{server.stop(0);workers.shutdownNow();}
  }));}
}
