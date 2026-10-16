package com.tencent.weknora.vectorstore;

import com.tencent.weknora.model.client.CallContext;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Go-compatible cold-load lifecycle: owner check, shared detached build, cooldown and generation fence. */
public final class VectorStoreRegistry implements AutoCloseable {
  public enum Reason { FORBIDDEN, NOT_FOUND, UNAVAILABLE }
  public static final class Failure extends RuntimeException {
    private final Reason reason;
    public Failure(Reason reason){super(switch(reason){case FORBIDDEN->"vector store access denied";case NOT_FOUND->"vector store not available";case UNAVAILABLE->"vector store engine unavailable";});this.reason=reason;}
    public Reason reason(){return reason;}
  }
  public interface Metadata {boolean owned(long owner,String id);VectorStoreMetadata.Store get(long owner,String id);default boolean owned(long owner,String id,CallContext call){return owned(owner,id);}default VectorStoreMetadata.Store get(long owner,String id,CallContext call){return get(owner,id);}}
  @FunctionalInterface public interface Factory {VectorStoreEngine build(VectorStoreMetadata.Store store,CallContext call);}
  private final Metadata metadata;private final Factory factory;private final Clock clock;
  private final Map<String,VectorStoreEngine> engines=new HashMap<>();private final Map<String,Long> generations=new HashMap<>();private final Map<String,Instant> cooldowns=new HashMap<>();private final Map<String,CompletableFuture<VectorStoreEngine>> flights=new HashMap<>();
  private final ExecutorService builders=Executors.newVirtualThreadPerTaskExecutor();private final Set<VectorStoreEngine> ownedEngines=Collections.newSetFromMap(new IdentityHashMap<>());
  public VectorStoreRegistry(VectorStoreMetadata metadata,Factory factory){this(new Metadata(){public boolean owned(long owner,String id){return metadata.owned(owner,id);}public VectorStoreMetadata.Store get(long owner,String id){return metadata.get(owner,id);}public boolean owned(long owner,String id,CallContext call){return metadata.owned(owner,id,call);}public VectorStoreMetadata.Store get(long owner,String id,CallContext call){return metadata.get(owner,id,call);}},factory,Clock.systemUTC());}
  public VectorStoreRegistry(Metadata metadata,Factory factory,Clock clock){this.metadata=metadata;this.factory=factory;this.clock=clock;}
  public VectorStoreEngine resolve(long owner,String id,CallContext caller){
    caller.check();boolean owned;try{owned=metadata.owned(owner,id,caller);}catch(RuntimeException failure){caller.check();throw new Failure(Reason.UNAVAILABLE);}if(!owned)throw new Failure(Reason.FORBIDDEN);
    CompletableFuture<VectorStoreEngine> flight;
    synchronized(this){var found=engines.get(id);if(found!=null)return found;if(cooldowns.containsKey(id)&&clock.instant().isBefore(cooldowns.get(id)))throw new Failure(Reason.UNAVAILABLE);String key=owner+":"+id;flight=flights.get(key);if(flight==null){long generation=generations.getOrDefault(id,0L);flight=new CompletableFuture<>();flights.put(key,flight);var result=flight;builders.submit(()->build(owner,id,key,generation,result));}}
    for(;;){caller.check();try{return flight.get(25,TimeUnit.MILLISECONDS);}catch(TimeoutException waiting){}catch(InterruptedException interrupted){Thread.currentThread().interrupt();caller.check();}catch(ExecutionException failed){if(failed.getCause() instanceof Failure reason)throw reason;throw new Failure(Reason.UNAVAILABLE);}}
  }
  private void build(long owner,String id,String key,long generation,CompletableFuture<VectorStoreEngine> result){
    try(var call=CallContext.timeout(Duration.ofSeconds(10))){synchronized(this){if(engines.containsKey(id)){flights.remove(key,result);result.complete(engines.get(id));return;}}
      VectorStoreMetadata.Store store;try{store=metadata.get(owner,id,call);}catch(RuntimeException read){throw new Failure(Reason.UNAVAILABLE);}if(store==null)throw new Failure(Reason.NOT_FOUND);
      VectorStoreEngine engine;try{engine=factory.build(store,call);}catch(RuntimeException failed){synchronized(this){cooldowns.put(id,clock.instant().plusSeconds(30));}throw new Failure(Reason.UNAVAILABLE);}
      if(engine==null)throw new Failure(Reason.UNAVAILABLE);
      synchronized(this){ownedEngines.add(engine);if(generations.getOrDefault(id,0L)!=generation)throw new Failure(Reason.UNAVAILABLE);engines.put(id,engine);cooldowns.remove(id);}synchronized(this){flights.remove(key,result);result.complete(engine);}
    }catch(Throwable failure){synchronized(this){flights.remove(key,result);result.completeExceptionally(failure instanceof Failure?failure:new Failure(Reason.UNAVAILABLE));}}finally{synchronized(this){flights.remove(key,result);}}
  }
  synchronized boolean registered(String id){return engines.containsKey(id);}
  public synchronized void register(String id,VectorStoreEngine engine){engines.put(id,engine);ownedEngines.add(engine);generations.merge(id,1L,Long::sum);}
  public synchronized void unregister(String id){engines.remove(id);generations.merge(id,1L,Long::sum);cooldowns.remove(id);}
  @Override public void close(){builders.shutdownNow();synchronized(this){ownedEngines.forEach(e->{try{e.close();}catch(Exception ignored){}});ownedEngines.clear();engines.clear();}}
}
