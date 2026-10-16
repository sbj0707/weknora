package com.tencent.weknora.knowledge;

import com.tencent.weknora.security.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
public final class KnowledgeGalleryController {
  private final KnowledgeBaseReadAuthorization reads;
  private final GalleryConfigService config;
  private final GalleryImageRepository images;

  public KnowledgeGalleryController(
      KnowledgeBaseReadAuthorization reads,
      GalleryConfigService config,
      GalleryImageRepository images) {
    this.reads = reads;
    this.config = config;
    this.images = images;
  }

  private KnowledgeBaseReadAuthorization.ReadAccess authorize(HttpServletRequest r, String id) {
    var caller = CurrentAuth.get(r);
    caller.requireRole("viewer");
    KnowledgeContentReadAuthorization.requireRetrieve(caller);
    var grant =
        reads.authorizeRead(
            caller, id, r.getParameter("agent_id"), r.getParameter("agent_source_tenant_id"));
    String clean = KnowledgeImportNames.sanitizeForLog(id);
    return clean.equals(id)
        ? grant
        : reads.authorizeRead(
            caller, clean, r.getParameter("agent_id"), r.getParameter("agent_source_tenant_id"));
  }

  @GetMapping("/api/v1/knowledge-bases/{id}/gallery-config")
  public Map<String, Object> config(HttpServletRequest r, @PathVariable String id) {
    authorize(r, id);
    return Map.of("success", true, "data", config.resolve(CurrentAuth.get(r)));
  }

  @GetMapping("/api/v1/knowledge-bases/{id}/images")
  public Map<String, Object> images(HttpServletRequest r, @PathVariable String id) {
    var grant = authorize(r, id);
    var page = KnowledgeContentReadController.pagination(r, false);
    return images.list(
        grant.effectiveTenantId(),
        grant.knowledgeBaseId(),
        page,
        r,
        config.resolve(CurrentAuth.get(r)),
        config);
  }
}
