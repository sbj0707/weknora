package com.tencent.weknora.task;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Opt-in worker: separate SQL queue, bounded concurrency, renewable fenced leases, cooperative cancel. */
public final class TaskWorker implements TaskExecutor {
    // Lease renewal can wait for a SQL connection. A job deadline must still interrupt its
    // handler when every renewal thread is blocked behind that handler's database statement.
    private static final ScheduledExecutorService DEADLINES=deadlineTimers();
    private static ScheduledExecutorService deadlineTimers(){var timer=new ScheduledThreadPoolExecutor(1,Thread.ofPlatform().daemon(true).name("java-task-deadline-",0).factory());timer.setRemoveOnCancelPolicy(true);return timer;}
    private final TaskRepository repository;private final Map<String,TaskHandler> handlers=new HashMap<>();
    private final String worker="java-"+UUID.randomUUID();private final Duration lease;private final int concurrency;private final TaskQueueOrder queueOrder;
    private final ExecutorService executor;private final ScheduledExecutorService heartbeat;
    private final AtomicInteger active=new AtomicInteger();private final ConcurrentMap<String,TaskCancellation> running=new ConcurrentHashMap<>();
    private volatile boolean closed;
    public TaskWorker(TaskRepository repository,List<TaskHandler> handlers,int concurrency,Duration lease){
        this(repository,handlers,concurrency,lease,null);
    }
    public TaskWorker(TaskRepository repository,List<TaskHandler> handlers,int concurrency,Duration lease,Map<String,Integer> queues){
        queueOrder=queues==null?null:new TaskQueueOrder(queues);
        if(concurrency<1||concurrency>64||lease.toMillis()<300)throw new IllegalArgumentException("Invalid worker configuration");
        this.repository=repository;this.lease=lease;this.concurrency=concurrency;
        for(TaskHandler handler:handlers){if(handler.type()==null||handler.type().isBlank()||this.handlers.putIfAbsent(handler.type(),handler)!=null)throw new IllegalArgumentException("Duplicate or empty Java task type");}
        executor=Executors.newFixedThreadPool(concurrency,Thread.ofPlatform().name("java-task-",0).factory());heartbeat=Executors.newScheduledThreadPool(Math.min(concurrency,4),Thread.ofPlatform().name("java-task-lease-",0).factory());
    }
    public synchronized void poll(){if(closed)return;while(active.get()<concurrency){var next=repository.claim(worker,lease,queueOrder==null?null:queueOrder.next());if(next.isEmpty())return;active.incrementAndGet();try{executor.submit(()->execute(next.get()));}catch(RejectedExecutionException ex){active.decrementAndGet();return;}}}
    private void execute(TaskRecord job){
        TaskCancellation cancellation=new TaskCancellation(repository,job);running.put(job.id(),cancellation);Thread current=Thread.currentThread();
        ScheduledFuture<?> renewal=null,timeout=null;
        try{
            TaskHandler handler=handlers.get(job.type());if(handler==null)throw new TaskFailure("No Java handler registered for this task type",false);
            renewal=heartbeat.scheduleAtFixedRate(()->{try{if(!repository.renew(job,lease)&&cancellation.cancel(TaskCancellation.Reason.LEASE_LOST))current.interrupt();}catch(Exception ex){if(cancellation.cancel(TaskCancellation.Reason.LEASE_LOST))current.interrupt();}},lease.toMillis()/3,lease.toMillis()/3,TimeUnit.MILLISECONDS);
            timeout=DEADLINES.schedule(()->{if(cancellation.cancel(TaskCancellation.Reason.TIMEOUT))current.interrupt();},job.timeoutMillis()>0?job.timeoutMillis():Math.max(1,handler.timeout().toMillis()),TimeUnit.MILLISECONDS);
            cancellation.check();JsonNode result=handler.execute(job,cancellation);
            var committed=repository.get(job.tenantId(),job.id());
            if(committed.isPresent()&&committed.get().status().equals("succeeded")&&committed.get().attempts()==job.attempts())return;
            cancellation.check();repository.complete(job,result);
        }catch(Exception ex){
            Thread.interrupted();
            try{
                if(cancellation.reason()==TaskCancellation.Reason.LEASE_LOST||cancellation.reason()==TaskCancellation.Reason.REQUESTED)repository.acknowledgeCancellation(job);
                else if(ex instanceof TaskFailure failure)repository.fail(job,failure.getMessage(),failure.retryable(),retryDelay(job.attempts()));
                else if(cancellation.reason()==TaskCancellation.Reason.TIMEOUT)repository.fail(job,"Java task deadline exceeded",true,retryDelay(job.attempts()));
                else repository.fail(job,cancellation.reason()==TaskCancellation.Reason.SHUTDOWN?"Java worker stopped":"Java task handler failed: "+ex.getClass().getSimpleName(),true,retryDelay(job.attempts()));
            }catch(Exception persistenceFailure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Java task settlement unavailable; lease will expire");}
        }finally{if(renewal!=null)renewal.cancel(false);if(timeout!=null)timeout.cancel(false);running.remove(job.id());active.decrementAndGet();Thread.interrupted();}
    }
    static Duration retryDelay(int attempt){long n=Math.max(0,attempt-1);return Duration.ofSeconds(n*n*n*n+15+ThreadLocalRandom.current().nextInt(30)*(n+1));}
    public int activeCount(){return active.get();}
    @Override public void close(){closed=true;running.values().forEach(token->token.cancel(TaskCancellation.Reason.SHUTDOWN));executor.shutdownNow();heartbeat.shutdownNow();try{executor.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException ex){Thread.currentThread().interrupt();}}
}
