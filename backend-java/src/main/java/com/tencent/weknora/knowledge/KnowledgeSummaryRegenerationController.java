package com.tencent.weknora.knowledge;

import com.tencent.weknora.security.CurrentAuth;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="weknora.knowledge.processing.enabled",havingValue="true")
public final class KnowledgeSummaryRegenerationController {
  private final KnowledgeSummaryRegenerationService service;
  private final KnowledgeProcessingLanguage language;
  public KnowledgeSummaryRegenerationController(KnowledgeSummaryRegenerationService service,KnowledgeProcessingLanguage language){this.service=service;this.language=language;}
  @PostMapping("/api/v1/knowledge/{id}/regenerate-summary")
  public Map<String,Object> regenerate(HttpServletRequest request,@PathVariable String id){
    var grant=service.authorize(CurrentAuth.get(request),id,request.getRequestURI());
    String clean=KnowledgeImportNames.sanitizeForLog(id);
    if(clean.isEmpty())throw new com.tencent.weknora.security.ApiException(400,"BAD_REQUEST","Knowledge ID cannot be empty").withAppCode(1000);
    return Map.of("success",true,"data",service.regenerate(grant,clean,language.resolve(request)));
  }
}
