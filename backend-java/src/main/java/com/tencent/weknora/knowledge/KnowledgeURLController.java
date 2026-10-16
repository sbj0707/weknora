package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.weknora.model.ModelURLPolicy;
import com.tencent.weknora.security.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="weknora.knowledge.processing.enabled",havingValue="true")
public final class KnowledgeURLController {
  private final KnowledgeURLService service;private final KnowledgeBaseWriteAuthorization writes;
  private final KnowledgeProcessingLanguage language;private final ObjectMapper json;private final ModelURLPolicy security;
  public KnowledgeURLController(KnowledgeURLService service,KnowledgeBaseWriteAuthorization writes,
      KnowledgeProcessingLanguage language,ObjectMapper json,ModelURLPolicy security) {
    this.service=service;this.writes=writes;this.language=language;this.json=json;this.security=security;
  }
  @PostMapping("/api/v1/knowledge-bases/{id}/knowledge/url")
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  public Map<String,Object> create(HttpServletRequest request,@PathVariable String id)throws IOException {
    var caller=CurrentAuth.get(request);
    var grant=writes.authorizeURLCreate(caller,id,request.getRequestURI(),request.getMethod());
    var body=KnowledgeURLRequest.decode(request.getInputStream().readAllBytes(),json);
    KnowledgeURLSecurity.validate(body.url(),security);
    return Map.of("success",true,"data",service.create(caller,grant,body,language.resolve(request)));
  }
}
