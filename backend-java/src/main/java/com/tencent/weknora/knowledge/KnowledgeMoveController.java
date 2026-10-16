package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.security.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.math.BigInteger;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name = "weknora.knowledge.processing.enabled", havingValue = "true")
public final class KnowledgeMoveController {
  private final KnowledgeMoveAdmission service;
  private final KnowledgeMoveProgress progress;
  private final ObjectMapper json;

  public KnowledgeMoveController(
      KnowledgeMoveAdmission service, KnowledgeMoveProgress progress, ObjectMapper json) {
    this.service = service;
    this.progress = progress;
    this.json = json;
  }

  @PostMapping("/api/v1/knowledge/move")
  public Map<String, Object> move(HttpServletRequest request) throws IOException {
    var caller = CurrentAuth.get(request);
    ChunkLifecycleService.capability(caller, "ingest");
    var form = KnowledgeMoveRequest.decode(request.getInputStream().readAllBytes(), json);
    return Map.of("success", true, "data", service.submit(caller, form, request.getRequestURI()));
  }

  @GetMapping("/api/v1/knowledge/move/progress/{task_id}")
  public Map<String, Object> progress(HttpServletRequest request, @PathVariable String task_id) {
    var caller = CurrentAuth.get(request);
    ChunkLifecycleService.capability(caller, "retrieve");
    if (task_id.isEmpty()) throw KnowledgeMoveRequest.bad("Task ID cannot be empty");
    BigInteger owner = KnowledgeMoveTaskIds.tenant(task_id);
    if (caller.tenantId() == 0)
      throw new ApiException(401, "UNAUTHORIZED", "Unauthorized").withAppCode(1001);
    if (!owner.equals(BigInteger.valueOf(caller.tenantId())))
      throw new ApiException(404, "NOT_FOUND", "task not found").withAppCode(1003);
    return Map.of("success", true, "data", progress.get(task_id));
  }
}
