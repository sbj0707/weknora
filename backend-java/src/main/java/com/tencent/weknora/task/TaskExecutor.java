package com.tencent.weknora.task;
/** A worker or independently bounded worker pools consumed by the native scheduler. */
public interface TaskExecutor extends AutoCloseable{void poll();int activeCount();@Override void close();}
