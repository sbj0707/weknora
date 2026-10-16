package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.weknora.model.client.CallContext;
import com.tencent.weknora.security.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
public final class HybridSearchController {
  private final KnowledgeBaseReadAuthorization authorization;private final HybridSearchService search;private final HybridSearchResourceURLs resources;private final java.util.function.Supplier<CallContext> calls;
  @org.springframework.beans.factory.annotation.Autowired
  public HybridSearchController(KnowledgeBaseReadAuthorization authorization,HybridSearchService search,HybridSearchResourceURLs resources){this(authorization,search,resources,CallContext::new);}
  HybridSearchController(KnowledgeBaseReadAuthorization authorization,HybridSearchService search,HybridSearchResourceURLs resources,java.util.function.Supplier<CallContext> calls){this.authorization=authorization;this.search=search;this.resources=resources;this.calls=calls;}
  @RequestMapping(value="/api/v1/knowledge-bases/{id}/hybrid-search",method={RequestMethod.GET,RequestMethod.POST})
  public Map<String,Object> search(HttpServletRequest servlet,@PathVariable String id)throws IOException{
    var caller=CurrentAuth.get(servlet);caller.requireRole("viewer");String agent=servlet.getParameter("agent_id"),source=servlet.getParameter("agent_source_tenant_id");
    var grant=authorization.authorizeRead(caller,id,agent,source);var request=HybridSearchRequest.decode(servlet.getInputStream().readAllBytes());boolean publicMode=resources.mode(caller,servlet.getParameter("resource_urls"));
    try(var call=calls.get()){
      var result=search.search(caller,grant,request,agent,source,call);resources.rewrite(result.rows(),grant.effectiveTenantId(),publicMode);
      if(result.rows()!=null)for(ObjectNode row:result.rows())row.remove(List.of("context_header","content_revision","chunk_metadata_raw"));var response=new LinkedHashMap<String,Object>();response.put("success",true);response.put("data",result.rows());if(result.diagnostics()!=null)response.put("meta",Map.of("rerank",result.diagnostics()));return response;
    }catch(ApiException typed){throw typed;}catch(RuntimeException failure){throw new ApiException(500,"INTERNAL_SERVER_ERROR",Objects.requireNonNullElse(failure.getMessage(),"hybrid search failed")).withAppCode(1007);}
  }
}
