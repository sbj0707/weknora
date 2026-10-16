package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** Initial admission must never overwrite progress already written by a running worker. */
public interface KnowledgeMoveProgress {
  void initialize(ObjectNode progress);

  void save(ObjectNode progress);

  ObjectNode get(String taskId);
}
