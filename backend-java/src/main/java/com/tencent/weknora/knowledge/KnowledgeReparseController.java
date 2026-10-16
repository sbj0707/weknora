package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.weknora.security.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="weknora.knowledge.processing.enabled",havingValue="true")
public final class KnowledgeReparseController {
  private final KnowledgeReparseService service;
  private final KnowledgeDeleteAdmissionService admission;
  private final KnowledgeBaseWriteAuthorization writes;
  private final KnowledgeProcessingLanguage language;
  private final ObjectMapper json;
  public KnowledgeReparseController(KnowledgeReparseService service,KnowledgeDeleteAdmissionService admission,KnowledgeBaseWriteAuthorization writes,KnowledgeProcessingLanguage language,ObjectMapper json){this.service=service;this.admission=admission;this.writes=writes;this.language=language;this.json=json;}
  @PostMapping("/api/v1/knowledge/{id}/reparse")
  public Map<String,Object> single(HttpServletRequest request,@PathVariable String id)throws IOException{
    var caller=CurrentAuth.get(request);ChunkLifecycleService.capability(caller,"ingest");String clean=KnowledgeImportNames.sanitizeForLog(id);if(clean.isEmpty())throw KnowledgeReparseRequest.bad("Knowledge ID cannot be empty");
    var row=admission.lookup(clean);var grant=writes.authorizeReparse(caller,Objects.toString(row.get("knowledge_base_id"),""),request.getRequestURI());
    if(((Number)row.get("tenant_id")).longValue()!=grant.effectiveTenantId())throw new ApiException(403,"FORBIDDEN","Permission denied to access this knowledge").withAppCode(1002);
    var config=request.getContentLengthLong()==0?null:KnowledgeReparseRequest.decode(request.getInputStream().readAllBytes(),json,false).processConfig();
    try{return Map.of("success",true,"message","Knowledge reparse task submitted","data",service.reparse(caller,grant.effectiveTenantId(),grant.knowledgeBaseId(),clean,config,language.resolve(request),Objects.toString(request.getAttribute("request_id"),"")));}
    catch(ApiException typed){throw typed;}catch(RuntimeException failure){throw new ApiException(500,"INTERNAL_SERVER_ERROR",ChunkEditService.diagnostic(failure)).withAppCode(1007);}
  }
  @PostMapping("/api/v1/knowledge/batch-reparse")
  public Map<String,Object> batch(HttpServletRequest request)throws IOException{
    var caller=CurrentAuth.get(request);ChunkLifecycleService.capability(caller,"ingest");
    if(!caller.isApiKey()&&!caller.isAtLeast("contributor"))throw new ApiException(403,"FORBIDDEN","Forbidden: insufficient workspace role").withResponse(Map.of("error","Forbidden: insufficient workspace role"));
    var body=KnowledgeReparseRequest.decode(request.getInputStream().readAllBytes(),json,true);
    var grant=writes.authorizeBatchReparse(caller,body.knowledgeBase(),request.getRequestURI());
    service.validateBatch(grant.effectiveTenantId(),grant.knowledgeBaseId(),body.ids());String task=service.enqueueBatch(caller,grant,body);
    return Map.of("success",true,"message","Batch reparse task submitted","data",Map.of("task_id",task,"reparse_count",body.ids().size()));
  }
}
