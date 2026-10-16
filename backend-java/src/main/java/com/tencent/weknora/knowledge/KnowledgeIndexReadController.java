package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.weknora.security.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/knowledge")
public final class KnowledgeIndexReadController {
  private final KnowledgeContentReadAuthorization authorization;
  private final KnowledgeIndexReadService service;
  public KnowledgeIndexReadController(KnowledgeContentReadAuthorization authorization,KnowledgeIndexReadService service) {this.authorization=authorization;this.service=service;}

  @GetMapping("/{id}/index-status")
  public Map<String,Object> status(HttpServletRequest request,@PathVariable String id) {
    var access=access(request,id);
    try(var deadline=KnowledgeIndexReadDeadline.start(java.time.Duration.ofSeconds(8))) {return Map.of("success",true,"data",service.status(access.knowledge(),access.parent()));}
    catch(RuntimeException ex) {throw internal("Document index statistics unavailable");}
  }

  @GetMapping("/{id}/chunks/{chunk_id}/details")
  public Map<String,Object> detail(HttpServletRequest request,@PathVariable String id,@PathVariable(name="chunk_id")String chunkId) {
    var access=access(request,id);
    try(var deadline=KnowledgeIndexReadDeadline.start(java.time.Duration.ofSeconds(12))) {return Map.of("success",true,"data",service.detail(access.knowledge(),access.parent(),chunkId));}
    catch(ApiException ex) {throw ex;}
    catch(RuntimeException ex) {throw internal("Chunk details unavailable");}
  }

  private KnowledgeContentReadAuthorization.KnowledgeAccess access(HttpServletRequest request,String id) {
    var auth=CurrentAuth.get(request);auth.requireRole("viewer");KnowledgeContentReadAuthorization.requireRetrieve(auth);
    var access=authorization.authorizeKnowledge(auth,id,request.getParameter("agent_id"),request.getParameter("agent_source_tenant_id"));
    ObjectNode knowledge=access.knowledge();
    if(knowledge.path("tenant_id").asLong()!=access.parent().effectiveTenantId())throw new ApiException(403,"FORBIDDEN","Permission denied to access this knowledge").withAppCode(1002);
    return access;
  }
  private static ApiException internal(String message) {return new ApiException(500,"INTERNAL_SERVER_ERROR",message).withAppCode(1007);}
}
