package com.tencent.weknora.model.client;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Caller cancellation/deadline shared by model I/O and its stream consumer. */
public final class CallContext implements AutoCloseable {
    private final Instant deadline;private final String sessionId;private final Set<Runnable> callbacks=new HashSet<>();private volatile boolean cancelled;
    public CallContext(){this(null,"");}public CallContext(Instant deadline){this(deadline,"");}public CallContext(Instant deadline,String sessionId){this.deadline=deadline;this.sessionId=sessionId==null?"":sessionId;}
    public String sessionId(){return sessionId;}
    public static CallContext timeout(Duration timeout){return new CallContext(Instant.now().plus(timeout));}
    public Instant deadline(){return deadline;}
    public void check(){if(cancelled||Thread.currentThread().isInterrupted())throw new CancellationException("Model call cancelled");if(deadline!=null&&!Instant.now().isBefore(deadline))throw new ModelClientException("Model call deadline exceeded",true);}
    public synchronized AutoCloseable onCancel(Runnable callback){if(cancelled){callback.run();return ()->{};}callbacks.add(callback);return ()->{synchronized(this){callbacks.remove(callback);}};}
    public void cancel(){List<Runnable> pending;synchronized(this){if(cancelled)return;cancelled=true;pending=new ArrayList<>(callbacks);callbacks.clear();}pending.forEach(Runnable::run);}
    public void pause(Duration duration){long end=System.nanoTime()+duration.toNanos();while(System.nanoTime()<end){check();try{TimeUnit.NANOSECONDS.sleep(Math.min(TimeUnit.MILLISECONDS.toNanos(50),Math.max(1,end-System.nanoTime())));}catch(InterruptedException ex){Thread.currentThread().interrupt();check();}}check();}
    @Override public void close(){cancel();}
}
