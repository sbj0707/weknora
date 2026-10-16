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
public final class ChunkQuestionController {
  private final ChunkQuestionService service;private final ObjectMapper json;
  public ChunkQuestionController(ChunkQuestionService service,ObjectMapper json){this.service=service;this.json=json;}
  @PutMapping("/api/v1/chunks/by-id/{id}/questions")
  public Map<String,Object> upsert(HttpServletRequest request,@PathVariable String id)throws IOException{return apply(request,id,false);}
  @DeleteMapping("/api/v1/chunks/by-id/{id}/questions")
  public Map<String,Object> delete(HttpServletRequest request,@PathVariable String id)throws IOException{return apply(request,id,true);}
  private Map<String,Object> apply(HttpServletRequest request,String id,boolean delete)throws IOException{
    var grant=service.authorize(CurrentAuth.get(request),id,request.getRequestURI(),request.getMethod());String clean=KnowledgeImportNames.sanitizeForLog(id);
    if(clean.isEmpty())throw ChunkQuestionRequest.bad(delete?"Chunk ID cannot be empty":"Chunk ID is required");
    var command=ChunkQuestionRequest.decode(request.getInputStream().readAllBytes(),json,delete);return delete?service.delete(grant,clean,command):service.upsert(grant,clean,command);
  }
}
