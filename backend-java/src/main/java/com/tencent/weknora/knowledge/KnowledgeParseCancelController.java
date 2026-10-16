package com.tencent.weknora.knowledge;

import com.tencent.weknora.security.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

/** Native queue only; hybrid routing remains with Go until queue cutover is verified. */
@RestController
@ConditionalOnProperty(name="weknora.knowledge.processing.enabled",havingValue="true")
public final class KnowledgeParseCancelController {
  private final KnowledgeContentReadAuthorization content;
  private final KnowledgeBaseWriteAuthorization writes;
  private final KnowledgeParseCancelService service;
  public KnowledgeParseCancelController(KnowledgeContentReadAuthorization content,KnowledgeBaseWriteAuthorization writes,KnowledgeParseCancelService service){this.content=content;this.writes=writes;this.service=service;}
  @PostMapping("/api/v1/knowledge/{id}/cancel-parse")
  public Map<String,Object> cancel(HttpServletRequest request,@PathVariable String id){
    var caller=CurrentAuth.get(request);
    if(caller.isApiKey()&&!caller.fullAccess()&&!caller.capabilities().contains("ingest"))throw new ApiException(403,"FORBIDDEN","Forbidden: API key scope does not allow this operation").withResponse(Map.of("error","Forbidden: API key scope does not allow this operation"));
    String clean=KnowledgeImportNames.sanitizeForLog(id);
    if(clean.isEmpty())throw new ApiException(400,"BAD_REQUEST","Knowledge ID cannot be empty").withAppCode(1000);
    var document=content.lookupKnowledge(clean);
    var grant=writes.authorizeCancelParse(caller,document.path("knowledge_base_id").asText(),request.getRequestURI(),request.getMethod());
    if(document.path("tenant_id").asLong()!=grant.effectiveTenantId())throw new ApiException(403,"FORBIDDEN","Permission denied to access this knowledge").withAppCode(1002);
    return Map.of("success",true,"message","Knowledge parse cancelled","data",service.cancel(caller,grant.effectiveTenantId(),grant.knowledgeBaseId(),clean));
  }
}
