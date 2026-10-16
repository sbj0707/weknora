package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.weknora.security.CurrentAuth;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="weknora.knowledge.processing.enabled",havingValue="true")
public final class ChunkEditController {
  private final ChunkEditService service;private final ObjectMapper json;private final KnowledgeProcessingLanguage language;
  public ChunkEditController(ChunkEditService service,ObjectMapper json,KnowledgeProcessingLanguage language){this.service=service;this.json=json;this.language=language;}
  @PutMapping("/api/v1/chunks/{knowledge_id}/{id}")
  public Map<String,Object> edit(HttpServletRequest request,@PathVariable("knowledge_id")String document,@PathVariable String id)throws IOException{return apply(request,document,id,false);}
  @PostMapping("/api/v1/chunks/{knowledge_id}/{id}/revert")
  public Map<String,Object> revert(HttpServletRequest request,@PathVariable("knowledge_id")String document,@PathVariable String id)throws IOException{return apply(request,document,id,true);}
  private Map<String,Object> apply(HttpServletRequest request,String document,String id,boolean revert)throws IOException {
    var caller=CurrentAuth.get(request);var grant=service.authorize(caller,document,request.getRequestURI(),revert);
    String cleanDocument=KnowledgeImportNames.sanitizeForLog(document),cleanId=KnowledgeImportNames.sanitizeForLog(id);service.fetch(grant,cleanDocument,cleanId);
    var command=ChunkEditRequest.decode(request.getInputStream().readAllBytes(),json,revert);
    return service.edit(grant,caller,cleanDocument,cleanId,command,language.resolve(request),revert);
  }
}
