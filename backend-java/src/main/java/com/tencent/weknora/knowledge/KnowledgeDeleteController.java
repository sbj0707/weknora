package com.tencent.weknora.knowledge;

import com.tencent.weknora.security.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="weknora.knowledge.processing.enabled",havingValue="true")
public final class KnowledgeDeleteController {
  private final KnowledgeBaseWriteAuthorization writes;
  private final KnowledgeDeleteAdmissionService admission;
  public KnowledgeDeleteController(KnowledgeBaseWriteAuthorization writes,KnowledgeDeleteAdmissionService admission){this.writes=writes;this.admission=admission;}
  @DeleteMapping("/api/v1/knowledge/{id}")
  public Map<String,Object> single(HttpServletRequest request,@PathVariable String id){
    var caller=CurrentAuth.get(request);capability(caller,false);String clean=KnowledgeImportNames.sanitizeForLog(id);
    if(clean.isEmpty())throw KnowledgeDeleteRequest.bad("Knowledge ID cannot be empty");
    var document=admission.lookup(clean);String kb=KnowledgeListDeleteRepository.text(document,"knowledge_base_id");
    var grant=writes.authorizeKnowledgeDelete(caller,kb,request.getRequestURI(),request.getMethod());
    if(((Number)document.get("tenant_id")).longValue()!=grant.effectiveTenantId())throw forbidden("Permission denied to access this knowledge");
    admission.moving(document);String task=admission.enqueue(caller,grant,List.of(clean),"Failed to enqueue delete task");
    return Map.of("success",true,"message","Delete task submitted","data",Map.of("task_id",task));
  }
  @PostMapping("/api/v1/knowledge/batch-delete")
  public Map<String,Object> batch(HttpServletRequest request)throws IOException {
    var caller=CurrentAuth.get(request);capability(caller,false);floor(caller,"contributor");
    var body=KnowledgeDeleteRequest.decode(request.getInputStream().readAllBytes());
    var grant=writes.authorizeKnowledgeBatchDelete(caller,body.knowledgeBase(),request.getRequestURI(),request.getMethod());
    admission.validateBatch(grant,body.ids());String task=admission.enqueue(caller,grant,body.ids(),"Failed to enqueue batch delete task");
    return Map.of("success",true,"message","Batch delete task submitted","data",Map.of("task_id",task,"deleted_count",body.ids().size()));
  }
  @DeleteMapping("/api/v1/knowledge-bases/{id}/knowledge")
  public Map<String,Object> clear(HttpServletRequest request,@PathVariable String id){
    var caller=CurrentAuth.get(request);capability(caller,true);floor(caller,"admin");
    var grant=writes.authorizeKnowledgeClear(caller,id,request.getRequestURI(),request.getMethod());
    if(!grant.ownTenant()||!grant.permission().equals("admin"))throw forbidden("Only knowledge base owner can clear contents");
    var ids=admission.list(grant);if(ids.isEmpty())return Map.of("success",true,"message","Knowledge base is already empty","data",Map.of("deleted_count",0));
    admission.enqueue(caller,grant,ids,"Failed to enqueue cleanup task");
    return Map.of("success",true,"message","Knowledge base contents clear task submitted","data",Map.of("deleted_count",ids.size()));
  }
  private static void capability(AuthContext caller,boolean full){if(caller.isApiKey()&&!caller.fullAccess()&&(full||!caller.capabilities().contains("ingest")))throw new ApiException(403,"FORBIDDEN","Forbidden: API key scope does not allow this operation").withResponse(Map.of("error","Forbidden: API key scope does not allow this operation"));}
  private static void floor(AuthContext caller,String role){if(!caller.isApiKey()&&!caller.isAtLeast(role))throw new ApiException(403,"FORBIDDEN","Forbidden: insufficient workspace role").withResponse(Map.of("error","Forbidden: insufficient workspace role"));}
  private static ApiException forbidden(String message){return new ApiException(403,"FORBIDDEN",message).withAppCode(1002);}
}
