package com.tencent.weknora.vectorstore;

import com.tencent.weknora.model.client.CallContext;
import com.tencent.weknora.retrieval.*;
import java.util.*;

/** One physical instance, distinct from environment-selected default engines. */
public interface VectorStoreEngine extends AutoCloseable {
  String engine();
  Set<String> capabilities();
  List<RetrievalResult> retrieve(RetrievalQuery query,CallContext call);
  void batchSave(List<IndexEntry> entries,Map<String,float[]> embeddings,CallContext call);
  void delete(String field,List<String> ids,CallContext call);
  default void validateKnowledgeIndexMove() { throw new UnsupportedOperationException("vector store does not support knowledge index move"); }
  default void moveKnowledgeIndices(String source,String target,String knowledge,List<String> chunks,int dimensions,String type,CallContext call) {
    throw new UnsupportedOperationException("vector store does not support knowledge index move");
  }
  @Override default void close(){}
}
