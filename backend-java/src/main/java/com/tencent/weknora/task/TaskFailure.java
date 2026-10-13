package com.tencent.weknora.task;
/** Only operator-safe text belongs here; arbitrary dependency messages are never stored automatically. */
public final class TaskFailure extends Exception {
    private final boolean retryable;
    public TaskFailure(String safeMessage,boolean retryable){super(safeMessage);this.retryable=retryable;}
    public boolean retryable(){return retryable;}
}
