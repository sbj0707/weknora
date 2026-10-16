package com.tencent.weknora.vectorstore;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.model.ModelEgressPolicy;
import com.tencent.weknora.model.client.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;

/** Real socket framing, retry gates and TCP reuse replayed from the production Go transport. */
class Elasticsearch8MoveTransportGoParityTest {
  final ObjectMapper json = new ObjectMapper();
  @TestFactory Stream<DynamicTest> actualGoTransport() throws Exception {
    JsonNode cases;try(var in=getClass().getResourceAsStream("/go-elasticsearch-move-transport.json")){cases=json.readTree(in);}
    assertEquals(24,cases.size());return StreamSupport.stream(cases.spliterator(),false).map(c->DynamicTest.dynamicTest(c.path("name").asText(),()->replay(c)));
  }

  void replay(JsonNode oracle) throws Exception {
    String name=oracle.path("name").asText();
    try(var server=new WireServer(name);var http=new SafeModelHttp(new ModelEgressPolicy(null,json,new MockEnvironment().withProperty("SSRF_WHITELIST","127.0.0.1")));var workers=Executors.newVirtualThreadPerTaskExecutor();var build=new CallContext()){
      var engine=new Elasticsearch8Store(new VectorStoreMetadata.Store("owned",1,"elasticsearch",json.createObjectNode().put("addr",server.address()),json.createObjectNode().put("index_name","vectors")),http,json,build);
      var errors=new ArrayList<String>();boolean early=false;int atGate=-1;
      for(int attempt=0;attempt<2;attempt++){
        try(var call=attempt==0&&name.startsWith("deadline-")?CallContext.timeout(Duration.ofMillis(300)):new CallContext()){
          var reader=new AtomicReference<Thread>();
          var done=workers.submit(()->{reader.set(Thread.currentThread());try{engine.moveKnowledgeIndices("source","target","doc",null,2,"document",call);return "";}catch(RuntimeException failed){return failed.getMessage();}});
          String error="";
          if(attempt==0&&oracle.path("gate").asBoolean()){
            assertTrue(server.entered.await(3,TimeUnit.SECONDS),"HTTP gate entered");
            if((name.startsWith("cancel-")||name.startsWith("deadline-"))&&name.endsWith("-body"))awaitBodyRead(reader,done);
            if(name.startsWith("cancel-"))call.cancel();
            if(name.startsWith("cancel-")||name.startsWith("deadline-")){error=done.get(3,TimeUnit.SECONDS);early=true;}
            else try{error=done.get(100,TimeUnit.MILLISECONDS);early=true;}catch(TimeoutException stillWaiting){}
            atGate=server.requests.get();server.release.countDown();if(!early)error=done.get(3,TimeUnit.SECONDS);
          }else error=done.get(3,TimeUnit.SECONDS);
          errors.add(error);
        }
      }
      assertEquals(oracle.path("errors"),json.valueToTree(errors),"Exact Go errors for both invocations");
      assertEquals(oracle.path("completed_before_release").asBoolean(),early,"Result before response EOF");
      assertEquals(oracle.path("requests_at_gate").asInt(),atGate,"No retry before drain finishes");
      assertEquals(oracle.path("requests").asInt(),server.requests.get(),"Actual attempts");
      assertEquals(oracle.path("connections"),json.valueToTree(server.moves),"Physical TCP reuse across retries/caller contexts");
      assertEquals(oracle.path("total_connections").asInt(),server.connections.get(),"Includes constructor HTTP connection reuse");
      assertTrue(server.failures.isEmpty(),server.failures.toString());
    }
  }

  private static void awaitBodyRead(AtomicReference<Thread> reader, Future<?> done) throws Exception {
    // Observe the real consumer, without adding hooks or delays to the production transport.
    // A server-side flush alone can still cancel exchange() before it returns the response.
    long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
    while(System.nanoTime()<limit&&!done.isDone()){
      Thread thread=reader.get();
      if(thread!=null&&Arrays.stream(thread.getStackTrace()).anyMatch(frame->
          frame.getClassName().equals("com.tencent.weknora.model.client.SafeModelHttp$Inbox")
              &&frame.getMethodName().equals("read")))return;
      Thread.sleep(1);
    }
    fail("Client did not enter the response body read before cancellation/deadline");
  }

  static final class WireServer implements AutoCloseable {
    final String name;final ServerSocket listener=new ServerSocket(0,50,InetAddress.getByName("127.0.0.1"));
    final ExecutorService workers=Executors.newVirtualThreadPerTaskExecutor();
    final Set<Socket> sockets=ConcurrentHashMap.newKeySet();final AtomicInteger requests=new AtomicInteger(),connections=new AtomicInteger();
    final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
    final List<Integer> moves=Collections.synchronizedList(new ArrayList<>());final List<String> failures=Collections.synchronizedList(new ArrayList<>());
    final Map<Integer,Integer> moveIDs=new HashMap<>();volatile boolean closed;
    WireServer(String name)throws IOException{this.name=name;workers.submit(()->{while(!closed)try{Socket socket=listener.accept();sockets.add(socket);int id=connections.incrementAndGet();workers.submit(()->serve(socket,id));}catch(IOException stopped){if(!closed)failures.add(stopped.toString());}});}
    String address(){return "http://127.0.0.1:"+listener.getLocalPort();}
    void serve(Socket socket,int connection){
      try(socket){var input=socket.getInputStream();var output=socket.getOutputStream();
        while(!closed){String request=line(input);if(request==null)return;String[] parts=request.split(" ");if(parts.length<2)throw new IOException("Invalid request line");
          var headers=new TreeMap<String,String>(String.CASE_INSENSITIVE_ORDER);for(String h;(h=line(input))!=null&&!h.isEmpty();){int colon=h.indexOf(':');headers.put(h.substring(0,colon),h.substring(colon+1).trim());}
          int size=Integer.parseInt(headers.getOrDefault("Content-Length","0"));if(input.readNBytes(size).length!=size)return;
          if(parts[0].equals("HEAD")){reply(output,200,"",false);continue;}
          if(parts[1].equals("/vectors/_mapping")){reply(output,200,"{\"vectors\":{\"mappings\":{\"properties\":{\"chunk_id\":{\"type\":\"keyword\"}}}}}",false);continue;}
          if(!parts[1].equals("/vectors/_update_by_query?refresh=true"))throw new IOException("Unexpected path "+parts[1]);
          int n=requests.incrementAndGet();synchronized(moveIDs){int id=moveIDs.computeIfAbsent(connection,k->moveIDs.size()+1);moves.add(id);}
          if(n==1||name.equals("reset-exhausted")&&n<=4||name.equals("last-retry-open-body")&&n<=4){
            switch(name){
              case "retry-body-gate"->{write(output,"HTTP/1.1 503 Service Unavailable\r\nContent-Length: 2048\r\n\r\npartial");gate();write(output," ".repeat(2041));continue;}
              case "retry-large-body"->{reply(output,503,"x".repeat(2<<20),false);continue;}
              case "retry-empty-body"->{reply(output,503,"",false);continue;}
              case "retry-truncated-body"->{raw(output,503,"Content-Length: 2048\r\n","partial");return;}
              case "truncated-valid-json"->{raw(output,200,"Content-Length: 2048\r\n","{\"total\":1,\"updated\":1}");return;}
              case "truncated-partial-json"->{raw(output,200,"Content-Length: 2048\r\n","{\"total\":");return;}
              case "invalid-chunk-valid-json","invalid-chunk-partial-json"->{String b=name.contains("partial")?"{\"total\":" : "{\"total\":1,\"updated\":1}";raw(output,200,"Transfer-Encoding: chunked\r\n",Integer.toHexString(b.length())+"\r\n"+b+"\r\nxyz\r\n");return;}
              case "empty-truncated-body"->{raw(output,200,"Content-Length: 2048\r\n","");return;}
              case "reset-first","reset-exhausted"->{return;}
              case "cancel-preheaders","deadline-preheaders"->{gate();return;}
              case "cancel-retry-body","deadline-retry-body","cancel-success-body","deadline-success-body"->{raw(output,name.contains("retry")?503:200,"Content-Length: 2048\r\n","{\"total\":");gate();return;}
              case "success-open-body","error-open-body"->{int status=name.startsWith("error")?400:200;String body=status==200?"{\"total\":1,\"updated\":1}":"{\"error\":{\"type\":\"fixture\",\"reason\":\"failed\"}}";chunkOpen(output,status,body);gate();write(output,"0\r\n\r\n");continue;}
              case "last-retry-open-body"->{String body="{\"error\":{\"type\":\"fixture\",\"reason\":\"failed\"}}";if(n==4){chunkOpen(output,503,body);gate();write(output,"0\r\n\r\n");}else reply(output,503,body,false);continue;}
              case "small-trailing-json"->{reply(output,200,"{\"total\":1,\"updated\":1} {\"ignored\":true}",false);continue;}
              case "large-trailing-json"->{reply(output,200,"{\"total\":1,\"updated\":1}"+" ".repeat(8192),false);continue;}
              case "connection-close"->{reply(output,200,"{\"total\":1,\"updated\":1}",true);return;}
            }
          }
          reply(output,200,"{\"total\":1,\"updated\":1}",false);
        }
      }catch(IOException disconnected){/* Closing a first-value response intentionally closes unfinished bodies. */}
      catch(Exception error){if(!closed)failures.add(error.toString());}finally{sockets.remove(socket);}
    }
    void gate()throws InterruptedException{entered.countDown();release.await();}
    static String line(InputStream input)throws IOException{var out=new ByteArrayOutputStream();for(int c;(c=input.read())!=-1;){if(c=='\n')return out.toString(StandardCharsets.ISO_8859_1).replaceAll("\r$","");out.write(c);}return out.size()==0?null:out.toString(StandardCharsets.ISO_8859_1);}
    static void write(OutputStream out,String value)throws IOException{out.write(value.getBytes(StandardCharsets.UTF_8));out.flush();}
    static void raw(OutputStream out,int status,String headers,String body)throws IOException{write(out,"HTTP/1.1 "+status+" Fixture\r\nX-Elastic-Product: Elasticsearch\r\n"+headers+"\r\n"+body);}
    static void reply(OutputStream out,int status,String body,boolean close)throws IOException{raw(out,status,"Content-Length: "+body.getBytes(StandardCharsets.UTF_8).length+"\r\n"+(close?"Connection: close\r\n":""),body);}
    static void chunkOpen(OutputStream out,int status,String body)throws IOException{raw(out,status,"Transfer-Encoding: chunked\r\n",Integer.toHexString(body.length())+"\r\n"+body+"\r\n");}
    @Override public void close()throws IOException{closed=true;release.countDown();listener.close();for(var socket:sockets)socket.close();workers.shutdownNow();}
  }
}
