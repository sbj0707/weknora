package com.tencent.weknora.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import com.tencent.weknora.model.client.CallContext;
import com.tencent.weknora.security.ApiException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class HybridSearchFanoutTest {
  @Test void fourGroupsRunConcurrentlyAndRemainingGroupsWaitForTheirRunningSlot()throws Exception{
    var running=new AtomicInteger();var maximum=new AtomicInteger();var ready=new CountDownLatch(4);var release=new CountDownLatch(1);
    try(var caller=new CallContext();var executor=Executors.newSingleThreadExecutor()){
      var result=executor.submit(()->HybridSearchFanout.run(List.of(1,2,3,4,5,6,7,8),caller,(id,child)->{int active=running.incrementAndGet();maximum.accumulateAndGet(active,Math::max);ready.countDown();try{assertTrue(release.await(2,TimeUnit.SECONDS));child.check();return id;}catch(InterruptedException interrupted){throw new RuntimeException(interrupted);}finally{running.decrementAndGet();}}));
      assertTrue(ready.await(2,TimeUnit.SECONDS));assertEquals(4,maximum.get());release.countDown();assertEquals(Set.of(1,2,3,4,5,6,7,8),new HashSet<>(result.get(2,TimeUnit.SECONDS)));assertEquals(4,maximum.get());
    }
  }
  @Test void firstStoreFailureCancelsInFlightSiblingAndPublishesNoPartialResult(){
    var entered=new CountDownLatch(1);var canceled=new AtomicBoolean();
    try(var caller=new CallContext()){
      var failure=assertThrows(ApiException.class,()->HybridSearchFanout.run(List.of(1,2),caller,(id,child)->{if(id==1){try{assertTrue(entered.await(2,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}throw new IllegalStateException("physical query failure");}entered.countDown();try{child.pause(Duration.ofSeconds(5));return id;}catch(CancellationException stop){canceled.set(true);throw stop;}}));assertEquals(400,failure.status());assertEquals(2201,failure.appCode());assertTrue(canceled.get());caller.check();
    }
  }
  @Test void explicitCallerCancellationHasGoContextCanceledContract()throws Exception{
    var started=new CountDownLatch(2);try(var caller=new CallContext();var executor=Executors.newSingleThreadExecutor()){
      var result=executor.submit(()->HybridSearchFanout.run(List.of(1,2),caller,(id,child)->{started.countDown();child.pause(Duration.ofSeconds(5));return id;}));assertTrue(started.await(2,TimeUnit.SECONDS));caller.cancel();var error=assertThrows(ExecutionException.class,()->result.get(2,TimeUnit.SECONDS));assertInstanceOf(ApiException.class,error.getCause());var failure=(ApiException)error.getCause();assertEquals(500,failure.status());assertEquals("context canceled",failure.getMessage());assertEquals(1007,failure.appCode());
    }
  }
}
