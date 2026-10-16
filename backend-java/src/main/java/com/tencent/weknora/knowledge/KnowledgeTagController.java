package com.tencent.weknora.knowledge;

import com.tencent.weknora.security.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="weknora.knowledge.processing.enabled",havingValue="true")
@RequestMapping("/api/v1/knowledge-bases/{id}/tags")
public final class KnowledgeTagController {
  private final KnowledgeTagRepository tags;private final KnowledgeBaseWriteAuthorization writes;private final KnowledgeBaseReadAuthorization reads;
  public KnowledgeTagController(KnowledgeTagRepository tags,KnowledgeBaseWriteAuthorization writes,KnowledgeBaseReadAuthorization reads){this.tags=tags;this.writes=writes;this.reads=reads;}
  @GetMapping public Map<String,Object> list(HttpServletRequest request,@PathVariable String id){
    var caller=CurrentAuth.get(request);caller.requireRole("viewer");var grant=reads.authorizeRead(caller,id,Objects.requireNonNullElse(request.getParameter("agent_id"),""),Objects.requireNonNullElse(request.getParameter("agent_source_tenant_id"),""));
    long page=number(request,"page"),size=number(request,"page_size");var invalid=new ArrayList<String>();if(page<0)invalid.add(validation("Page","min"));if(size<0)invalid.add(validation("PageSize","min"));else if(size>1000)invalid.add(validation("PageSize","max"));if(!invalid.isEmpty())throw KnowledgeTagRequestDecoder.bad("分页参数不合法",String.join("\n",invalid));
    try{return Map.of("success",true,"data",tags.list(grant,page==0?1:page,size==0?20:size,KnowledgeImportNames.sanitizeForLog(Objects.requireNonNullElse(request.getParameter("keyword"),""))));}catch(ApiException e){throw e;}catch(RuntimeException e){throw KnowledgeTagRepository.internal();}
  }
  @PostMapping public Map<String,Object> create(HttpServletRequest request,@PathVariable String id)throws IOException{var caller=CurrentAuth.get(request);var grant=write(request,caller,id);var change=KnowledgeTagRequestDecoder.decode(request.getInputStream().readAllBytes(),"create");try{return Map.of("success",true,"data",tags.create(caller,grant,change));}catch(ApiException e){throw e;}catch(RuntimeException e){throw KnowledgeTagRepository.internal();}}
  @PutMapping("/{tag_id}") public Map<String,Object> update(HttpServletRequest request,@PathVariable String id,@PathVariable("tag_id")String tag)throws IOException{var caller=CurrentAuth.get(request);var grant=write(request,caller,id);String resolved=tags.resolve(grant.effectiveTenantId(),tag);var change=KnowledgeTagRequestDecoder.decode(request.getInputStream().readAllBytes(),"update");try{return Map.of("success",true,"data",tags.update(caller,grant,resolved,change));}catch(ApiException e){throw e;}catch(RuntimeException e){throw KnowledgeTagRepository.internal();}}
  @DeleteMapping("/{tag_id}") public Map<String,Object> delete(HttpServletRequest request,@PathVariable String id,@PathVariable("tag_id")String tag)throws IOException{var caller=CurrentAuth.get(request);var grant=write(request,caller,id);String resolved=tags.resolve(grant.effectiveTenantId(),tag);var change=KnowledgeTagRequestDecoder.decode(request.getInputStream().readAllBytes(),"delete");try{var excluded=tags.exclusions(grant.effectiveTenantId(),id,change.excluded());tags.delete(caller,grant,resolved,"true".equals(request.getParameter("force")),"true".equals(request.getParameter("content_only")),excluded);return Map.of("success",true);}catch(ApiException e){throw e;}catch(RuntimeException e){throw KnowledgeTagRepository.internal();}}
  private KnowledgeBaseWriteAuthorization.WriteAccess write(HttpServletRequest request,AuthContext caller,String id){return request.getAttribute(KnowledgeBaseWriteAuthorization.GRANT_ATTRIBUTE) instanceof KnowledgeBaseWriteAuthorization.WriteAccess grant?grant:writes.authorizeTag(caller,id,request.getRequestURI(),request.getMethod());}
  private static long number(HttpServletRequest request,String key){String raw=request.getParameter(key);if(raw==null||raw.isEmpty())return 0;String reason="invalid syntax";if(raw.matches("[+-]?[0-9]+")){try{return Long.parseLong(raw);}catch(NumberFormatException e){reason="value out of range";}}String quote="\""+raw.replace("\\","\\\\").replace("\"","\\\"")+"\"";throw KnowledgeTagRequestDecoder.bad("分页参数不合法","strconv.ParseInt: parsing "+quote+": "+reason);}
  private static String validation(String field,String rule){return "Key: 'Pagination."+field+"' Error:Field validation for '"+field+"' failed on the '"+rule+"' tag";}
}
