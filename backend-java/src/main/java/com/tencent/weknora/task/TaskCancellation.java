package com.tencent.weknora.task;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;

public final class TaskCancellation {
    public enum Reason {REQUESTED,LEASE_LOST,TIMEOUT,SHUTDOWN}
    private final AtomicReference<Reason> reason=new AtomicReference<>();
    private final TaskRepository repository;private final TaskRecord lease;
    TaskCancellation(TaskRepository repository,TaskRecord lease){this.repository=repository;this.lease=lease;}
    public boolean isCanceled(){if(reason.get()==null&&repository.canceledOrLeaseLost(lease))cancel(Reason.LEASE_LOST);return reason.get()!=null;}
    public void check(){if(isCanceled()||Thread.currentThread().isInterrupted())throw new CancellationException("Java task canceled");}
    public Reason reason(){return reason.get();}
    boolean cancel(Reason cause){return reason.compareAndSet(null,cause);}
}
