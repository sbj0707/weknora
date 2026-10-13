package com.tencent.weknora.model.client;

/** Safe to surface: callers never receive a request URL, credential, or raw transport cause. */
public class ModelClientException extends RuntimeException {
    private final boolean transport;
    public ModelClientException(String message){this(message,false);}
    public ModelClientException(String message,boolean transport){super(message);this.transport=transport;}
    public boolean transport(){return transport;}
}
