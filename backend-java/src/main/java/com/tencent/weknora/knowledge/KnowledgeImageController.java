package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.weknora.security.CurrentAuth;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name = "weknora.knowledge.processing.enabled", havingValue = "true")
public final class KnowledgeImageController {
  private final KnowledgeImageService service;
  private final ObjectMapper json;

  public KnowledgeImageController(KnowledgeImageService service, ObjectMapper json) {
    this.service = service;
    this.json = json;
  }

  @PutMapping("/api/v1/knowledge/image/{id}/{chunk_id}")
  public Map<String, Object> update(
      HttpServletRequest request, @PathVariable String id, @PathVariable("chunk_id") String chunk)
      throws IOException {
    var caller = CurrentAuth.get(request);
    var grant = service.authorize(caller, id, request.getRequestURI());
    String cleanId = KnowledgeImportNames.sanitizeForLog(id),
        cleanChunk = KnowledgeImportNames.sanitizeForLog(chunk);
    if (cleanId.isEmpty()) throw KnowledgeFolderRequest.bad("Knowledge ID cannot be empty");
    if (cleanChunk.isEmpty()) throw KnowledgeFolderRequest.bad("Chunk ID cannot be empty");
    if (!cleanId.equals(id)) grant = service.authorize(caller, cleanId, request.getRequestURI());
    service.update(
        grant,
        cleanId,
        cleanChunk,
        KnowledgeImageRequest.decode(request.getInputStream().readAllBytes(), json));
    return Map.of("success", true, "message", "Knowledge chunk image updated successfully");
  }
}
