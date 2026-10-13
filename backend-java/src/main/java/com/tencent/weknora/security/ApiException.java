package com.tencent.weknora.security;

public class ApiException extends RuntimeException {
    private final int status;
    private final String code;
    private Object details;
    private Integer appCode;
    private java.util.Map<String, Object> response;
    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
    public int status() { return status; }
    public String code() { return code; }
    public Object details() { return details; }
    public Integer appCode() { return appCode; }
    public java.util.Map<String, Object> response() { return response; }
    public ApiException withDetails(Object value) { details = value; return this; }
    public ApiException withAppCode(int value) { appCode = value; return this; }
    public ApiException withResponse(java.util.Map<String, Object> value) { response = value; return this; }
}
