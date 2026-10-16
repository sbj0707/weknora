package com.tencent.weknora.vectorstore;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.model.client.*;
import com.tencent.weknora.retrieval.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;

/** Production Go registry/factory outcomes, including actual blocked-construction interleavings. */
class VectorStoreRegistryGoParityTest {
  final ObjectMapper json=new ObjectMapper();
  static final class Engine implements VectorStoreEngine {public String engine(){return "elasticsearch";}public Set<String> capabilities(){return Set.of("vector","keywords");}public List<RetrievalResult> retrieve(RetrievalQuery q,CallContext c){throw new UnsupportedOperationException();}public void batchSave(List<IndexEntry> e,Map<String,float[]> v,CallContext c){throw new UnsupportedOperationException();}public void delete(String field,List<String> ids,CallContext c){throw new UnsupportedOperationException();}}
  @TestFactory Stream<DynamicTest> actualGoColdLoadLifecycle()throws Exception{JsonNode cases;try(var in=getClass().getResourceAsStream("/go-vector-store-registry.json")){cases=json.readTree(in);}assertEquals(13,cases.size());return StreamSupport.stream(cases.spliterator(),false).map(c->DynamicTest.dynamicTest(c.path("name").asText(),()->{
    String name=c.path("name").asText();var calls=new AtomicInteger();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);boolean blocked=Set.of("register-wins","unregister-during-build","leader-cancel").contains(name);var current=new AtomicReference<>(Instant.now());Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return current.get();}};
    var metadata=new VectorStoreRegistry.Metadata(){public boolean owned(long owner,String id){return !name.equals("foreign");}public VectorStoreMetadata.Store get(long owner,String id){if(name.equals("read-error"))throw new IllegalStateException();return name.equals("missing")?null:new VectorStoreMetadata.Store("store",1,"elasticsearch",json.createObjectNode(),json.createObjectNode());}};
    try(var registry=new VectorStoreRegistry(metadata,(store,call)->{int n=calls.incrementAndGet();if(blocked){entered.countDown();while(release.getCount()>0){call.pause(Duration.ofMillis(1));}}if(name.equals("factory-error")||name.equals("cooldown-expired")&&n==1)throw new IllegalStateException("secret/endpoint must not escape");if(name.equals("factory-nil"))return null;if(name.equals("factory-panic"))throw new AssertionError("third party");return new Engine();},clock);var executor=Executors.newVirtualThreadPerTaskExecutor()){
      var actual=new ArrayList<String>();
      if(blocked){var leader=new CallContext();var pending=executor.submit(()->resolve(registry,leader));assertTrue(entered.await(2,TimeUnit.SECONDS));if(name.equals("register-wins"))registry.register("store",new Engine());if(name.equals("unregister-during-build"))registry.unregister("store");if(name.equals("leader-cancel")){leader.cancel();actual.add(pending.get(2,TimeUnit.SECONDS));release.countDown();actual.add(resolve(registry,new CallContext()));}else{release.countDown();actual.add(pending.get(2,TimeUnit.SECONDS));actual.add(resolve(registry,new CallContext()));}leader.close();}
      else{actual.add(resolve(registry,new CallContext()));if(Set.of("cache","factory-error","factory-nil","factory-panic").contains(name))actual.add(resolve(registry,new CallContext()));if(name.equals("cooldown-expired")){current.set(current.get().plusSeconds(31));actual.add(resolve(registry,new CallContext()));}if(name.equals("unregistered")){registry.unregister("store");actual.add(resolve(registry,new CallContext()));}}
      assertEquals(c.path("outcomes"),json.valueToTree(actual));assertEquals(c.path("builds").asInt(),calls.get());assertEquals(c.path("published").asBoolean(),registry.registered("store"));
    }
  }));}
  static String resolve(VectorStoreRegistry registry,CallContext call){try(call){registry.resolve(1,"store",call);return "ok";}catch(CancellationException canceled){return "context canceled";}catch(VectorStoreRegistry.Failure failed){return failed.getMessage();}}
}
