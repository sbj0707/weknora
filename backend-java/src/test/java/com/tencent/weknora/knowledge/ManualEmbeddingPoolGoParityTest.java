package com.tencent.weknora.knowledge;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import com.tencent.weknora.model.ModelEgressPolicy;
import com.tencent.weknora.model.client.*;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;

/** True provider barrier and actual Go pool goldens; compare float32 raw bits without epsilon. */
class ManualEmbeddingPoolGoParityTest {
 final ObjectMapper json=new ObjectMapper();
 @TestFactory Stream<DynamicTest> actualGoPool()throws Exception{var cases=json.readTree(getClass().getResourceAsStream("/go-manual-embedding-pool.json"));assertThat(cases.size()).isEqualTo(4);return StreamSupport.stream(cases.spliterator(),false).map(c->DynamicTest.dynamicTest(c.path("name").asText(),()->compare(c)));}
 void compare(JsonNode c)throws Exception{
  String name=c.path("name").asText();int size=c.path("capacity").asInt();var active=new AtomicInteger();var max=new AtomicInteger();var calls=new AtomicInteger();var gate=new CountDownLatch(1);var threads=Executors.newCachedThreadPool(Thread.ofPlatform().daemon().factory());var call=CallContext.timeout(Duration.ofSeconds(15));var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(threads);
  server.createContext("/v1/embeddings",e->{try{var b=json.readTree(e.getRequestBody());int n=active.incrementAndGet();max.accumulateAndGet(n,Math::max);int count=calls.incrementAndGet();if(size==5&&count==5){if(name.equals("pool-five-cancel"))call.cancel();gate.countDown();}if(name.equals("pool-five-cancel")){while(!Thread.currentThread().isInterrupted()){try{Thread.sleep(25);}catch(InterruptedException stop){Thread.currentThread().interrupt();break;}}return;}if(size==5&&!gate.await(10,TimeUnit.SECONDS))throw new AssertionError("Production manual pool serialized independent sub-batches");int first=Integer.parseInt(b.path("input").get(0).asText().substring(7));int status=200;JsonNode response;if(name.equals("pool-five-error")&&first==0){status=500;response=json.createObjectNode().set("error",json.createObjectNode().put("message","controlled manual provider failure"));}else{Thread.sleep(12-first);var data=json.createArrayNode();for(int i=b.path("input").size()-1;i>=0;i--){int value=Integer.parseInt(b.path("input").get(i).asText().substring(7));data.add(json.createObjectNode().put("index",i).set("embedding",json.createArrayNode().add(value+100).add(value*3)));}response=json.createObjectNode().set("data",data);}byte[] body=json.writeValueAsBytes(response);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(status,body.length);try(var out=e.getResponseBody()){out.write(body);}}catch(InterruptedException stop){Thread.currentThread().interrupt();}finally{active.decrementAndGet();e.close();}});server.start();
  var policy=new ModelEgressPolicy(null,json,new MockEnvironment().withProperty("SSRF_WHITELIST","127.0.0.1"));
  try(var http=new SafeModelHttp(policy);var pool=new ManualEmbeddingPool(size);call){var endpoint=new ModelEndpoint("manual-model","manual-model","manual-model","http://127.0.0.1:"+server.getAddress().getPort()+"/v1/embeddings",json.createObjectNode(),json.createObjectNode(),false,Map.of(),List.of());var model=new OpenAIEmbedding(http,endpoint,2,false);var texts=IntStream.range(0,12).mapToObj(i->"manual-"+i).toList();float[][] result=new float[0][];boolean failed=false;try{result=pool.embed(model,call,texts,2);}catch(ModelClientException|CancellationException expected){failed=true;}assertThat(failed).isEqualTo(c.path("failed").asBoolean());assertThat(calls.get()).isEqualTo(c.path("calls").asInt());assertThat(max.get()).isEqualTo(c.path("max_active").asInt());assertThat(result.length).isEqualTo(c.path("vectors").size());for(int i=0;i<result.length;i++)for(int j=0;j<result[i].length;j++)assertThat(Integer.toUnsignedLong(Float.floatToRawIntBits(result[i][j]))).as("source %s dimension %s",i,j).isEqualTo(c.path("vectors").get(i).get(j).asLong());}finally{server.stop(0);threads.shutdownNow();}
 }
}
