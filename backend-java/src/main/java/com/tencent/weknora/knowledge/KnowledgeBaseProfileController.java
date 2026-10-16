package com.tencent.weknora.knowledge;

import com.tencent.weknora.model.client.CallContext;
import com.tencent.weknora.security.*;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="weknora.knowledge.processing.enabled",havingValue="true")
public final class KnowledgeBaseProfileController {
  private final KnowledgeBaseWriteAuthorization writes;
  private final KnowledgeBaseProfileGenerator generator;
  private final KnowledgeBaseProfileRepository repository;
  private final KnowledgeProcessingLanguage language;
  public KnowledgeBaseProfileController(KnowledgeBaseWriteAuthorization writes,KnowledgeBaseProfileGenerator generator,KnowledgeBaseProfileRepository repository,KnowledgeProcessingLanguage language){this.writes=writes;this.generator=generator;this.repository=repository;this.language=language;}
  @PostMapping("/api/v1/knowledge-bases/{id}/profile/generate")
  public Map<String,Object> generate(HttpServletRequest request,@PathVariable String id){
    var caller=CurrentAuth.get(request);
    var grant=request.getAttribute(KnowledgeBaseWriteAuthorization.GRANT_ATTRIBUTE) instanceof KnowledgeBaseWriteAuthorization.WriteAccess g?g:writes.authorizeProfile(caller,id,request.getRequestURI(),request.getMethod());
    String locale=language.resolve(request);
    try(var call=CallContext.timeout(Duration.ofMinutes(2))){
      var result=generator.generate(grant.effectiveTenantId(),grant.knowledgeBase(),true,locale,call,call::check,p->repository.saveSynchronous(grant.effectiveTenantId(),id,p,call::check));
      return Map.of("success",true,"data",result.profile());
    }catch(KnowledgeBaseProfileGenerator.Unsupported|KnowledgeBaseProfileGenerator.ModelMissing e){throw new ApiException(400,"BAD_REQUEST",e.getMessage()).withAppCode(1000);}
    catch(RuntimeException e){throw new ApiException(500,"INTERNAL_SERVER_ERROR",Objects.requireNonNullElse(e.getMessage(),"knowledge base profile generation failed")).withAppCode(1007);}
  }
}
