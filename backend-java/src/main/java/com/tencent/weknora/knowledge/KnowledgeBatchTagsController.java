package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.weknora.security.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name = "weknora.knowledge.processing.enabled", havingValue = "true")
public final class KnowledgeBatchTagsController {
  private final KnowledgeBatchTagsService service;
  private final ObjectMapper json;

  public KnowledgeBatchTagsController(KnowledgeBatchTagsService service, ObjectMapper json) {
    this.service = service;
    this.json = json;
  }

  @PutMapping("/api/v1/knowledge/tags")
  public Map<String, Object> update(HttpServletRequest request) throws IOException {
    var caller = CurrentAuth.get(request);
    ChunkLifecycleService.capability(caller, "ingest");
    caller.requireRole("contributor");
    if (caller.tenantId() == 0)
      throw new ApiException(401, "UNAUTHORIZED", "Unauthorized").withAppCode(1001);
    service.update(
        caller,
        KnowledgeBatchTagsRequest.decode(request.getInputStream().readAllBytes(), json),
        request.getRequestURI());
    return Map.of("success", true);
  }
}
