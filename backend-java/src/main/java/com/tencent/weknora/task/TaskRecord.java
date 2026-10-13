package com.tencent.weknora.task;
import com.fasterxml.jackson.databind.JsonNode;

/** The attempt counter is a fencing token; a reclaimed job invalidates older completions. */
public record TaskRecord(String id,long tenantId,String type,String idempotencyKey,JsonNode payload,
                         String status,int attempts,int maxAttempts,long availableAt,
                         String leaseOwner,Long leaseUntil,boolean cancelRequested,
                         JsonNode result,String lastError,long createdAt,long updatedAt,String queue,long timeoutMillis) {
    public TaskRecord {payload=payload.deepCopy();if(result!=null)result=result.deepCopy();}
    /** Backward-compatible jobs use the default queue and the handler's deadline. */
    public TaskRecord(String id,long tenantId,String type,String idempotencyKey,JsonNode payload,
                      String status,int attempts,int maxAttempts,long availableAt,
                      String leaseOwner,Long leaseUntil,boolean cancelRequested,
                      JsonNode result,String lastError,long createdAt,long updatedAt){
        this(id,tenantId,type,idempotencyKey,payload,status,attempts,maxAttempts,availableAt,
             leaseOwner,leaseUntil,cancelRequested,result,lastError,createdAt,updatedAt,"default",0);
    }
    public boolean terminal(){return java.util.Set.of("succeeded","canceled","dead").contains(status);}
}
