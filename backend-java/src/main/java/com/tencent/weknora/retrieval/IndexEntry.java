package com.tencent.weknora.retrieval;

/** Existing Go IndexInfo metadata. Source types: chunk=0, passage=1, summary=2. */
public record IndexEntry(String sourceId,int sourceType,String chunkId,String knowledgeId,
                         String knowledgeBaseId,String tagId,String content,boolean enabled) { }
