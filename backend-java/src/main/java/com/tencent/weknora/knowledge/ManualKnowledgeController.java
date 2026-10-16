package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.weknora.security.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name = "weknora.knowledge.processing.enabled", havingValue = "true")
public final class ManualKnowledgeController {
  private final ManualKnowledgeRepository repository;
  private final ManualKnowledgeService service;
  private final KnowledgeBaseWriteAuthorization writes;
  private final ObjectMapper json;

  public ManualKnowledgeController(
      ManualKnowledgeRepository repository,
      ManualKnowledgeService service,
      KnowledgeBaseWriteAuthorization writes,
      ObjectMapper json) {
    this.repository = repository;
    this.service = service;
    this.writes = writes;
    this.json = json;
  }

  @PostMapping("/api/v1/knowledge-bases/{id}/knowledge/manual")
  public Map<String, Object> create(HttpServletRequest request, @PathVariable String id)
      throws IOException {
    var caller = CurrentAuth.get(request);
    var grant =
        writes.authorizeManualCreate(caller, id, request.getRequestURI(), request.getMethod());
    var body = ManualKnowledgeRequest.decode(request.getInputStream().readAllBytes(), json);
    return Map.of(
        "success",
        true,
        "data",
        service.create(
            caller, grant, body, Objects.toString(request.getAttribute("request_id"), "")));
  }

  @PutMapping("/api/v1/knowledge/manual/{id}")
  public Map<String, Object> update(HttpServletRequest request, @PathVariable String id)
      throws IOException {
    var caller = CurrentAuth.get(request);
    if (caller.isApiKey() && !caller.fullAccess() && !caller.capabilities().contains("ingest"))
      throw new ApiException(
              403, "FORBIDDEN", "Forbidden: API key scope does not allow this operation")
          .withResponse(Map.of("error", "Forbidden: API key scope does not allow this operation"));
    String clean = KnowledgeImportNames.sanitizeForLog(id);
    if (clean.isEmpty()) throw ManualKnowledgeRequest.bad("Knowledge ID cannot be empty");
    var row = repository.lookup(clean);
    var grant =
        writes.authorizeManualUpdate(
            caller,
            row.path("knowledge_base_id").asText(),
            request.getRequestURI(),
            request.getMethod());
    if (row.path("tenant_id").asLong() != grant.effectiveTenantId())
      throw new ApiException(403, "FORBIDDEN", "Permission denied to access this knowledge")
          .withAppCode(1002);
    var body = ManualKnowledgeRequest.decode(request.getInputStream().readAllBytes(), json);
    return Map.of(
        "success",
        true,
        "data",
        service.update(
            caller, grant, row, body, Objects.toString(request.getAttribute("request_id"), "")));
  }
}
