package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.weknora.model.client.CallContext;

/** All callers must validate the SQL owner/document/KB grant before accessing a graph namespace. */
public interface GraphExtractionStore {
  boolean enabled();
  void add(String kb,String knowledge,GraphExtractionText.Graph graph,CallContext call,Runnable guard);
  default void delete(String kb,String doc,CallContext call,Runnable guard){if(enabled())throw new UnsupportedOperationException("graph deletion is not implemented");}
  default GraphExtractionText.Graph search(String kb,String doc,java.util.List<String> names,CallContext call,Runnable guard){if(enabled())throw new UnsupportedOperationException("graph search is not implemented");return null;}
  ObjectNode counts(String kb,String knowledge);
  ObjectNode detail(String kb,String knowledge,String chunk);
}
