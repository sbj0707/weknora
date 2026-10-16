package com.tencent.weknora.retrieval;

import java.sql.SQLException;
import java.util.Map;

/** Go CopyIndices commits independently of chunk publication and never calls an embedding model. */
public interface IndexCopier {
  void copyIndices(String sourceKb, Map<String, String> documents,
      Map<String, String> chunks, String targetKb, int dimension, String knowledgeType)
      throws SQLException;

  static String sourceId(String source, String oldChunk, String newChunk) {
    if (source.equals(oldChunk)) return newChunk;
    if (source.startsWith(oldChunk + "-")) return newChunk + source.substring(oldChunk.length());
    return java.util.UUID.randomUUID().toString();
  }
}
