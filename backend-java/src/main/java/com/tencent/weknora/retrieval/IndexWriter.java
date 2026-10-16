package com.tencent.weknora.retrieval;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

/** Index mutations join the caller's fenced chunk transaction and never commit it. */
public interface IndexWriter {
    /** Create dimension-specific virtual tables before entering the chunk transaction. */
    void prepare(Set<Integer> dimensions) throws SQLException;
    void batchSave(Connection transaction,List<IndexEntry> entries,Map<String,float[]> embeddings) throws SQLException;
    /** Capture the index rows being replaced before calling the remote embedding provider. */
    List<String> sourceIdsByKnowledge(Connection transaction,List<String> knowledgeIds) throws SQLException;
    void deleteByKnowledge(Connection transaction,List<String> knowledgeIds) throws SQLException;
    void deleteByChunk(Connection transaction,List<String> chunkIds) throws SQLException;
    void deleteBySource(Connection transaction,List<String> sourceIds) throws SQLException;
    long estimateStorageSize(List<IndexEntry> entries,Map<String,float[]> embeddings);
    /** Retain physical vectors/FTS/IDs while changing only KB and tag metadata. */
    default void move(Connection transaction,String sourceKb,String targetKb,String knowledgeId) throws SQLException {
        throw new UnsupportedOperationException("Native index metadata move is not migrated for this engine");
    }
}
