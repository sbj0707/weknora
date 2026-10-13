package com.tencent.weknora.storage;

import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;

/** Physical locations are deliberately excluded from public resource DTOs. */
@JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
public record StoredResource(String id,String handle,long tenantId,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) String storageBackendId,String provider,
    @JsonIgnore String physicalPath,@JsonIgnore String locationHash,String kind,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) String mimeType,@JsonInclude(JsonInclude.Include.NON_EMPTY) String originalName,long size,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) String contentHash,String lifecycle,
    @JsonInclude(JsonInclude.Include.NON_NULL) Instant expiresAt,String state,Instant createdAt,Instant updatedAt,Instant deletedAt) {
    public String reference(){return "resource://"+handle;}
}
