package com.tencent.weknora.knowledge;

import com.tencent.weknora.model.client.CallContext;
import com.tencent.weknora.security.ApiException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiFunction;

/** Go's four running groups, per-running-group budget, first failure cancels siblings. */
final class HybridSearchFanout {
  private HybridSearchFanout(){}
  static <G,R> List<R> run(List<G> groups,CallContext caller,BiFunction<G,CallContext,R> operation){
    if(groups.isEmpty())return List.of();if(groups.size()==1){try{return List.of(operation.apply(groups.getFirst(),caller));}catch(RuntimeException failure){try{caller.check();}catch(CancellationException canceled){throw canceled();}catch(RuntimeException deadline){throw new ApiException(500,"INTERNAL_SERVER_ERROR","timeout: context deadline exceeded").withAppCode(1007);}throw failure;}}
    var futures=new ArrayList<Future<R>>();var results=new ArrayList<R>();
    try(var stop=new CallContext();var parent=caller.onCancel(stop::cancel);var executor=Executors.newFixedThreadPool(4,Thread.ofVirtual().factory())){
      var completed=new ExecutorCompletionService<R>(executor);
      try{
        caller.check();
        for(var group:groups)futures.add(completed.submit(()->{
          try(var child=new CallContext(caller.deadline(),caller.sessionId());var registration=stop.onCancel(child::cancel)){
            try{child.check();return operation.apply(group,child);}catch(Throwable failure){stop.cancel();throw failure;}
          }
        }));
        for(int i=0;i<groups.size();){caller.check();var next=completed.poll(25,TimeUnit.MILLISECONDS);if(next!=null){results.add(next.get());i++;}}
        return results;
      }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw canceled();}
      catch(ExecutionException|RuntimeException failure){
        try{caller.check();}catch(CancellationException canceled){throw canceled();}catch(RuntimeException deadline){throw unavailable();}
        throw unavailable();
      }finally{stop.cancel();for(var future:futures)if(!future.isDone())future.cancel(false);}
    }catch(ApiException typed){throw typed;}catch(Exception closeFailure){throw unavailable();}
  }
  static ApiException unavailable(){return new ApiException(400,"VECTOR_STORE_UNAVAILABLE","vector retrieval failed for one or more bound stores").withAppCode(2201);}
  static ApiException canceled(){return new ApiException(500,"INTERNAL_SERVER_ERROR","context canceled").withAppCode(1007);}
}
