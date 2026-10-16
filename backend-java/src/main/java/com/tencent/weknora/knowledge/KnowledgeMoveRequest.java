package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.chunker.GoJsonFirstValue;
import com.tencent.weknora.docreader.GoUtf8;
import com.tencent.weknora.security.ApiException;
import java.io.IOException;
import java.util.*;

record KnowledgeMoveRequest(String source, String target, List<String> ids, String mode) {
  static KnowledgeMoveRequest decode(byte[] bytes, ObjectMapper json) {
    JsonNode value;
    try {
      value =
          new GoUploadJson(json)
              .decode(
                  GoUtf8.decode(Arrays.copyOf(bytes, GoJsonFirstValue.length(bytes))),
                  KnowledgeMoveJson.formSchema(json, "MoveKnowledgeRequest"));
    } catch (IOException error) {
      throw bad("Invalid request parameters: " + error.getMessage());
    }
    var errors = new ArrayList<String>();
    boolean missingIds =
        value.path("knowledge_ids").isNull() || value.path("knowledge_ids").isMissingNode();
    if (missingIds || value.path("knowledge_ids").isEmpty())
      errors.add(validation("KnowledgeIDs", missingIds ? "required" : "min"));
    String source = value.path("source_kb_id").asText(""),
        target = value.path("target_kb_id").asText(""),
        mode = value.path("mode").asText("");
    if (source.isEmpty()) errors.add(validation("SourceKBID", "required"));
    if (target.isEmpty()) errors.add(validation("TargetKBID", "required"));
    if (mode.isEmpty()) errors.add(validation("Mode", "required"));
    else if (!Set.of("reuse_vectors", "reparse").contains(mode))
      errors.add(validation("Mode", "oneof"));
    if (!errors.isEmpty()) throw bad("Invalid request parameters: " + String.join("\n", errors));
    var ids = new ArrayList<String>();
    for (JsonNode id : value.path("knowledge_ids")) ids.add(id.asText(""));
    return new KnowledgeMoveRequest(source, target, List.copyOf(ids), mode);
  }

  private static String validation(String field, String tag) {
    return "Key: 'MoveKnowledgeRequest."
        + field
        + "' Error:Field validation for '"
        + field
        + "' failed on the '"
        + tag
        + "' tag";
  }

  static ApiException bad(String message) {
    return new ApiException(400, "BAD_REQUEST", message).withAppCode(1000);
  }
}
