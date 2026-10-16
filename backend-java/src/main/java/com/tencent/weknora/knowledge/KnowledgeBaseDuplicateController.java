package com.tencent.weknora.knowledge;

import com.tencent.weknora.security.CurrentAuth;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
public final class KnowledgeBaseDuplicateController {
  private final KnowledgeBaseDuplicateService service;
  private final KnowledgeProcessingLanguage language;
  public KnowledgeBaseDuplicateController(KnowledgeBaseDuplicateService service,KnowledgeProcessingLanguage language){this.service=service;this.language=language;}
  @PostMapping("/api/v1/knowledge-bases/{id}/duplicate")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String,Object> duplicate(HttpServletRequest request,@PathVariable String id) {
    return Map.of("success",true,"data",service.duplicate(CurrentAuth.get(request),id,request.getParameter("agent_id"),request.getParameter("agent_source_tenant_id"),language.resolve(request)));
  }
}
