package com.tencent.weknora.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.weknora.security.ApiException;
import io.lettuce.core.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;

/** An owned real Redis process replays the production Go record decoder and SET/SETNX contract. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisKnowledgeMoveProgressGoParityIT {
  final ObjectMapper json=new ObjectMapper();
  Path directory;Process process;RedisClient observer;io.lettuce.core.api.StatefulRedisConnection<String,String> connection;
  RedisKnowledgeMoveProgress progress;JsonNode fixture;
  @BeforeAll void start()throws Exception {
    fixture=json.readTree(getClass().getResourceAsStream("/go-knowledge-move-progress.json"));
    directory=Files.createTempDirectory("weknora-move-progress-redis-");int port;
    try(var socket=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){port=socket.getLocalPort();}
    process=new ProcessBuilder(System.getProperty("weknora.redis.executable","redis-server"),"--bind","127.0.0.1","--port",Integer.toString(port),"--save","","--appendonly","no","--dir",directory.toString())
        .redirectErrorStream(true).redirectOutput(directory.resolve("redis.log").toFile()).start();
    String uri="redis://127.0.0.1:"+port;observer=RedisClient.create(uri);observer.setDefaultTimeout(Duration.ofSeconds(1));
    long end=System.nanoTime()+Duration.ofSeconds(8).toNanos();
    for(;;)try{connection=observer.connect();connection.sync().ping();break;}catch(RuntimeException waiting){if(!process.isAlive()||System.nanoTime()>end)throw waiting;Thread.sleep(20);}
    progress=new RedisKnowledgeMoveProgress(json,()->RedisClient.create(uri));
  }
  @AfterAll void stop()throws Exception {
    if(progress!=null)progress.close();if(connection!=null)connection.close();if(observer!=null)observer.shutdown();
    if(process!=null){process.destroy();if(!process.waitFor(3,java.util.concurrent.TimeUnit.SECONDS)){process.destroyForcibly();assertTrue(process.waitFor(3,java.util.concurrent.TimeUnit.SECONDS));}}
  }
  @TestFactory Stream<DynamicTest> decode(){
    var tests=new ArrayList<DynamicTest>();int index=0;
    for(var c:fixture.path("decode")){int number=++index;tests.add(DynamicTest.dynamicTest("Go record "+number,()->{
      connection.sync().set("knowledge_move_progress:task",c.path("raw").asText());
      ObjectNode value=null;String error="";
      try{value=progress.get("task");}catch(Exception failure){error=failure.getMessage();}
      assertEquals(c.path("error").asText(),error);assertEquals(c.path("value"),value==null?json.nullNode():json.readTree(value.toString()));
    }));}
    assertEquals(19,tests.size());return tests.stream();
  }
  @Test void initialCannotOverwriteRunningProgressOrExtendItsTtl() {
    String key="knowledge_move_progress:task";connection.sync().del(key);
    progress.initialize((ObjectNode)fixture.path("initial"));
    assertEquals(fixture.path("initial_bytes").asText(),connection.sync().get(key));ttl(86400);
    progress.save((ObjectNode)fixture.path("completed"));
    assertEquals(fixture.path("completed_bytes").asText(),connection.sync().get(key));ttl(86400);
    long remaining=fixture.path("after_late_initial_ttl_seconds").asLong();connection.sync().expire(key,remaining);
    progress.initialize((ObjectNode)fixture.path("initial"));
    assertEquals(fixture.path("after_late_initial_bytes").asText(),connection.sync().get(key));ttl(remaining);
  }
  @Test void missingAndExpiredAreNotFound() {
    String key="knowledge_move_progress:task";connection.sync().del(key);
    var absent=assertThrows(ApiException.class,()->progress.get("task"));assertEquals(404,absent.status());
    assertEquals(fixture.path("missing_error").asText(),"error code: "+absent.appCode()+", error message: "+absent.getMessage());
    progress.initialize((ObjectNode)fixture.path("initial"));connection.sync().expire(key,0);
    assertEquals(404,assertThrows(ApiException.class,()->progress.get("task")).status());
  }
  void ttl(long seconds){long value=connection.sync().ttl("knowledge_move_progress:task");assertTrue(value<=seconds&&value>=seconds-1,"real Redis TTL: "+value);}
}
