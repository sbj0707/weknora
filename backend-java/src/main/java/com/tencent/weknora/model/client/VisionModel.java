package com.tencent.weknora.model.client;

import java.util.List;

/** Vision calls use the same catalog-selected provider protocol as Go VLM. */
public interface VisionModel {
  String modelId();

  String predict(CallContext context, List<byte[]> images, String prompt);
}
