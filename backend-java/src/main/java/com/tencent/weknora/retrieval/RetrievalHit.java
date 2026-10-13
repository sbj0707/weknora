package com.tencent.weknora.retrieval;
public record RetrievalHit(String id,String content,String sourceId,int sourceType,String chunkId,
                           String knowledgeId,String knowledgeBaseId,String tagId,double score,int matchType) { }
