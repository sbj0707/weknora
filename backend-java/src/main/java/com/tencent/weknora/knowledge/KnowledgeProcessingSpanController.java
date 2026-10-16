package com.tencent.weknora.knowledge;

import com.tencent.weknora.security.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/knowledge")
public final class KnowledgeProcessingSpanController {
  private final KnowledgeContentReadAuthorization authorization;
  private final KnowledgeProcessingSpanService service;
  public KnowledgeProcessingSpanController(KnowledgeContentReadAuthorization authorization,KnowledgeProcessingSpanService service) {this.authorization=authorization;this.service=service;}
  @GetMapping({"/{id}/spans","/{id}/stages"})
  public Map<String,Object> read(HttpServletRequest request,@PathVariable String id) {
    var auth=CurrentAuth.get(request);auth.requireRole("viewer");KnowledgeContentReadAuthorization.requireRetrieve(auth);
    String clean=KnowledgeImportNames.sanitizeForLog(id);
    if(clean.isEmpty())throw new ApiException(400,"BAD_REQUEST","Knowledge ID cannot be empty").withAppCode(1000);
    var grant=authorization.authorizeKnowledge(auth,clean,request.getParameter("agent_id"),request.getParameter("agent_source_tenant_id"));
    if(grant.knowledge().path("tenant_id").asLong()!=grant.parent().effectiveTenantId())throw new ApiException(403,"FORBIDDEN","Permission denied to access this knowledge").withAppCode(1002);
    return Map.of("success",true,"data",service.read(grant.knowledge(),request.getParameter("attempt")));
  }
}
