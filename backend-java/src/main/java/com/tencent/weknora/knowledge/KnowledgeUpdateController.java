package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.weknora.security.CurrentAuth;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="weknora.knowledge.processing.enabled",havingValue="true")
public final class KnowledgeUpdateController {
  private final KnowledgeUpdateService service;private final KnowledgeProcessingLanguage language;private final ObjectMapper json;
  public KnowledgeUpdateController(KnowledgeUpdateService service,KnowledgeProcessingLanguage language,ObjectMapper json){this.service=service;this.language=language;this.json=json;}
  @PutMapping("/api/v1/knowledge/{id}")
  public Map<String,Object> update(HttpServletRequest request,@PathVariable String id)throws IOException {
    var grant=service.authorize(CurrentAuth.get(request),id,request.getRequestURI());
    String clean=KnowledgeImportNames.sanitizeForLog(id);if(clean.isEmpty())throw KnowledgeFolderRequest.bad("Knowledge ID cannot be empty");if(!clean.equals(id))grant=service.authorize(CurrentAuth.get(request),clean,request.getRequestURI());
    var result=service.update(grant,clean,KnowledgeUpdateRequest.decode(request.getInputStream().readAllBytes(),json),language.resolve(request));
    var out=new LinkedHashMap<String,Object>();out.put("success",true);out.put("message","Knowledge updated successfully");out.put("data",result);return out;
  }
}
