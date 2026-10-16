package com.tencent.weknora.knowledge;

import com.tencent.weknora.security.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="weknora.knowledge.processing.enabled",havingValue="true")
public final class KnowledgeBaseDeleteController {
  private final KnowledgeBaseWriteAuthorization writes;
  private final KnowledgeBaseDeleteAdmission admission;
  public KnowledgeBaseDeleteController(KnowledgeBaseWriteAuthorization writes,KnowledgeBaseDeleteAdmission admission){this.writes=writes;this.admission=admission;}
  @DeleteMapping("/api/v1/knowledge-bases/{id}")
  public Map<String,Object> delete(HttpServletRequest request,@PathVariable String id){
    var caller=CurrentAuth.get(request);
    Object cached=request.getAttribute(KnowledgeBaseWriteAuthorization.GRANT_ATTRIBUTE);
    var grant=cached instanceof KnowledgeBaseWriteAuthorization.WriteAccess access?access:writes.authorizeDelete(caller,id,request.getRequestURI());
    admission.delete(caller,KnowledgeImportNames.sanitizeForLog(id),grant);
    return Map.of("success",true,"message","Knowledge base deleted successfully");
  }
}
