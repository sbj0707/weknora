package com.tencent.weknora.knowledge;

import com.tencent.weknora.security.CurrentAuth;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="weknora.knowledge.processing.enabled",havingValue="true")
public final class ChunkLifecycleController {
  private final ChunkLifecycleService lifecycle;
  public ChunkLifecycleController(ChunkLifecycleService lifecycle){this.lifecycle=lifecycle;}
  @DeleteMapping("/api/v1/chunks/{knowledge_id}/{id}")
  public Map<String,Object> single(HttpServletRequest request,@PathVariable("knowledge_id")String document,@PathVariable String id){
    var grant=lifecycle.authorizeWrite(CurrentAuth.get(request),document,request.getRequestURI(),true);
    lifecycle.delete(grant,KnowledgeImportNames.sanitizeForLog(document),KnowledgeImportNames.sanitizeForLog(id));
    return Map.of("success",true,"message","Chunk deleted");
  }
  @DeleteMapping("/api/v1/chunks/{knowledge_id}")
  public Map<String,Object> all(HttpServletRequest request,@PathVariable("knowledge_id")String document){
    var grant=lifecycle.authorizeWrite(CurrentAuth.get(request),document,request.getRequestURI(),false);
    lifecycle.deleteAll(grant,KnowledgeImportNames.sanitizeForLog(document));
    return Map.of("success",true,"message","All chunks under knowledge deleted");
  }
  @GetMapping("/api/v1/chunks/{knowledge_id}/{id}/revisions")
  public Map<String,Object> revisions(HttpServletRequest request,@PathVariable("knowledge_id")String document,@PathVariable String id){
    var grant=lifecycle.authorizeRead(CurrentAuth.get(request),document,request.getParameter("agent_id"),request.getParameter("agent_source_tenant_id"));
    return Map.of("success",true,"data",lifecycle.revisions(grant,KnowledgeImportNames.sanitizeForLog(document),KnowledgeImportNames.sanitizeForLog(id)));
  }
}
