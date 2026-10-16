package com.tencent.weknora.security;

import com.fasterxml.jackson.databind.ObjectMapper;import jakarta.servlet.http.*;import java.util.*;
import org.springframework.stereotype.Component;import org.springframework.web.method.HandlerMethod;import org.springframework.web.servlet.*;import org.springframework.web.servlet.config.annotation.*;

/** Production Go role floors and KB update ownership/access checks before body binding. */
@Component
final class NativeRoleGuard implements HandlerInterceptor,WebMvcConfigurer {
 private record Rule(String role,String template,boolean systemAdminBypass,boolean pathTenant){}
 private final DeniedRoleAuditService audit;private final ObjectMapper mapper;private final KnowledgeBaseWriteAuthorization writes;
 NativeRoleGuard(DeniedRoleAuditService audit,ObjectMapper mapper){this(audit,mapper,null);}
 @org.springframework.beans.factory.annotation.Autowired NativeRoleGuard(DeniedRoleAuditService audit,ObjectMapper mapper,KnowledgeBaseWriteAuthorization writes){this.audit=audit;this.mapper=mapper;this.writes=writes;}
 @Override public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(this).order(Integer.MIN_VALUE+100);}
 @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler)throws Exception{
  if(!(handler instanceof HandlerMethod)||!(request.getAttribute(CurrentAuth.ATTRIBUTE) instanceof AuthContext auth))return true;
  Object matched=request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);if(writes!=null&&"PUT".equals(request.getMethod())&&"/api/v1/knowledge-bases/{id}".equals(matched)){Object variables=request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);if(variables instanceof Map<?,?> map&&map.get("id")!=null)request.setAttribute(KnowledgeBaseWriteAuthorization.GRANT_ATTRIBUTE,writes.authorizeUpdate(auth,map.get("id").toString(),request.getRequestURI(),request.getMethod()));return true;}
  if(writes!=null&&"DELETE".equals(request.getMethod())&&"/api/v1/knowledge-bases/{id}".equals(matched)){Object variables=request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);if(variables instanceof Map<?,?> map&&map.get("id")!=null)request.setAttribute(KnowledgeBaseWriteAuthorization.GRANT_ATTRIBUTE,writes.authorizeDelete(auth,map.get("id").toString(),request.getRequestURI()));return true;}
  if(writes!=null&&"POST".equals(request.getMethod())&&"/api/v1/knowledge-bases/{id}/knowledge/manual".equals(matched)){Object variables=request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);if(variables instanceof Map<?,?> map&&map.get("id")!=null)request.setAttribute(KnowledgeBaseWriteAuthorization.GRANT_ATTRIBUTE,writes.authorizeManualCreate(auth,map.get("id").toString(),request.getRequestURI(),request.getMethod()));return true;}
  if(writes!=null&&"POST".equals(request.getMethod())&&"/api/v1/knowledge-bases/{id}/profile/generate".equals(matched)){Object variables=request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);if(variables instanceof Map<?,?> map&&map.get("id")!=null)request.setAttribute(KnowledgeBaseWriteAuthorization.GRANT_ATTRIBUTE,writes.authorizeProfile(auth,map.get("id").toString(),request.getRequestURI(),request.getMethod()));return true;}
  if(writes!=null&&Set.of("POST","PUT","DELETE").contains(request.getMethod())&&Set.of("/api/v1/knowledge-bases/{id}/tags","/api/v1/knowledge-bases/{id}/tags/{tag_id}").contains(matched)){Object variables=request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);if(variables instanceof Map<?,?> map&&map.get("id")!=null)request.setAttribute(KnowledgeBaseWriteAuthorization.GRANT_ATTRIBUTE,writes.authorizeTag(auth,map.get("id").toString(),request.getRequestURI(),request.getMethod()));return true;}
  Rule rule=rule(request.getMethod(),matched==null?"":matched.toString());if(rule==null||auth.isApiKey())return true;
  if(rule.pathTenant){Object variables=request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);if(variables instanceof Map<?,?> map){Object id=map.containsKey("tenantId")?map.get("tenantId"):map.get("id");if(id==null)return true;try{if(Long.parseLong(id.toString())!=auth.tenantId()&&!auth.crossTenantSuperuser())return true;}catch(NumberFormatException ex){return true;}}}
  if(auth.crossTenantSuperuser()||!auth.rbacEnforced()||rule.systemAdminBypass&&auth.systemAdmin()||AuthContext.level(auth.role())>=AuthContext.level(rule.role))return true;
  audit.record(auth,request.getRequestURI(),rule.template,request.getMethod(),rule.role);response.setStatus(403);response.setContentType("application/json");mapper.writeValue(response.getOutputStream(),Map.of("error","Forbidden: insufficient workspace role"));return false;
 }
 static Rule rule(String method,String pattern){
  String root="/api/v1/tenants/{tenantId}";
  if(Set.of("/api/v1/vector-stores","/api/v1/vector-stores/types","/api/v1/vector-stores/test","/api/v1/vector-stores/{id}","/api/v1/vector-stores/{id}/test").contains(pattern))return new Rule(method.equals("GET")?"viewer":"admin",pattern.replace("{id}",":id"),false,false);
  if(pattern.equals("/api/v1/system/parser-engines")&&method.equals("GET"))return new Rule("viewer",pattern,false,false);
  if(Set.of("/api/v1/system/parser-engines/check","/api/v1/system/docreader/reconnect").contains(pattern)&&method.equals("POST"))return new Rule("admin",pattern,false,false);
  if(pattern.equals(root+"/members"))return new Rule(method.equals("GET")?"viewer":"owner","/api/v1/tenants/:id/members",false,true);
  if(pattern.equals(root+"/members/{userId}"))return new Rule("owner","/api/v1/tenants/:id/members/:user_id",false,true);
  if(pattern.equals(root+"/leave"))return new Rule("viewer","/api/v1/tenants/:id/leave",false,true);
  if(pattern.equals(root+"/api-keys")||pattern.equals(root+"/api-keys/{keyId}"))return new Rule("owner",pattern.endsWith("{keyId}")?"/api/v1/tenants/:id/api-keys/:key_id":"/api/v1/tenants/:id/api-keys",false,true);
  if(pattern.equals(root+"/invitations"))return new Rule(method.equals("GET")?"viewer":"owner","/api/v1/tenants/:id/invitations",false,true);
  if(pattern.equals(root+"/invitations/{invId}"))return new Rule("owner","/api/v1/tenants/:id/invitations/:inv_id",false,true);
  if(pattern.equals(root+"/invite-links"))return new Rule("owner","/api/v1/tenants/:id/invite-links",false,true);
  if(pattern.equals("/api/v1/tenants/{id}"))return new Rule(method.equals("GET")?"viewer":"owner","/api/v1/tenants/:id",false,true);
  if(Set.of("/api/v1/tenants/kv/retrieval-config","/api/v1/tenants/kv/memory-config","/api/v1/tenants/kv/parser-engine-config").contains(pattern))return new Rule(method.equals("GET")?"viewer":"admin","/api/v1/tenants/kv/:key",false,false);
  if(pattern.equals("/api/v1/models"))return new Rule(method.equals("GET")?"viewer":"admin",pattern,false,false);
  if(pattern.equals("/api/v1/models/{id}"))return new Rule(method.equals("GET")?"viewer":"admin","/api/v1/models/:id",method.equals("PUT"),false);
  if(pattern.equals("/api/v1/models/{id}/credentials"))return new Rule("admin","/api/v1/models/:id/credentials",true,false);
  if(pattern.equals("/api/v1/models/{id}/credentials/{field}"))return new Rule("admin","/api/v1/models/:id/credentials/:field",true,false);
  if(Set.of("/api/v1/models/providers","/api/v1/models/catalog/resolve","/api/v1/chunker/preview").contains(pattern))return new Rule("viewer",pattern,false,false);
  if(pattern.equals("/api/v1/knowledge-bases/{id}/duplicate")&&method.equals("POST"))return new Rule("contributor","/api/v1/knowledge-bases/:id/duplicate",false,false);
  if(pattern.equals("/api/v1/knowledge-bases"))return new Rule(method.equals("POST")?"contributor":"viewer",pattern,false,false);
  if(Set.of("/api/v1/knowledge-bases/{id}/images","/api/v1/knowledge-bases/{id}/gallery-config","/api/v1/knowledge-bases/{id}/move-targets").contains(pattern)&&method.equals("GET"))return new Rule("viewer",pattern.replace("{id}",":id"),false,false);
  if(pattern.equals("/api/v1/knowledge-bases/{id}")&&method.equals("GET"))return new Rule("viewer","/api/v1/knowledge-bases/:id",false,false);
  if(pattern.equals("/api/v1/knowledge-bases/{id}/pin"))return new Rule("viewer","/api/v1/knowledge-bases/:id/pin",false,false);
  if(pattern.equals("/api/v1/knowledge/batch-delete")&&method.equals("POST"))return new Rule("contributor","/api/v1/knowledge/batch-delete",false,false);
  if(pattern.equals("/api/v1/knowledge/tags")&&method.equals("PUT"))return new Rule("contributor",pattern,false,false);
  if(pattern.equals("/api/v1/knowledge-bases/{id}/knowledge/batch-download")&&method.equals("POST"))return new Rule("contributor","/api/v1/knowledge-bases/:id/knowledge/batch-download",false,false);
  if(pattern.equals("/api/v1/knowledge/batch-reparse")&&method.equals("POST"))return new Rule("contributor","/api/v1/knowledge/batch-reparse",false,false);
  if(pattern.equals("/api/v1/knowledge/move")&&method.equals("POST"))return new Rule("contributor",pattern,false,false);
  if(pattern.equals("/api/v1/knowledge/move/progress/{task_id}")&&method.equals("GET"))return new Rule("viewer","/api/v1/knowledge/move/progress/:task_id",false,false);
  if(pattern.equals("/api/v1/knowledge/folder")&&method.equals("POST"))return new Rule("contributor","/api/v1/knowledge/folder",false,false);
  if(pattern.equals("/api/v1/knowledge-bases/{id}/knowledge/folders")&&method.equals("GET"))return new Rule("viewer","/api/v1/knowledge-bases/:id/knowledge/folders",false,false);
  if(pattern.equals("/api/v1/knowledge-bases/{id}/knowledge")&&method.equals("DELETE"))return new Rule("admin","/api/v1/knowledge-bases/:id/knowledge",false,false);
  if(pattern.equals("/api/v1/knowledge-bases/{id}/hybrid-search")&&Set.of("GET","POST").contains(method))return new Rule("viewer","/api/v1/knowledge-bases/:id/hybrid-search",false,false);
  if(pattern.equals("/api/v1/knowledge-bases/{id}/tags")&&method.equals("GET"))return new Rule("viewer","/api/v1/knowledge-bases/:id/tags",false,false);
  if(method.equals("GET")){
   if(Set.of("/api/v1/knowledge/batch","/api/v1/knowledge/search").contains(pattern))return new Rule("viewer",pattern,false,false);
   if(pattern.equals("/api/v1/knowledge/{id}/download"))return new Rule("contributor","/api/v1/knowledge/:id/download",false,false);
   if(pattern.equals("/api/v1/knowledge/{id}/preview"))return new Rule("viewer","/api/v1/knowledge/:id/preview",false,false);
   if(pattern.equals("/api/v1/chunks/{knowledge_id}/{id}/revisions"))return new Rule("viewer","/api/v1/chunks/:knowledge_id/:id/revisions",false,false);
   if(pattern.equals("/api/v1/knowledge/{id}/index-status"))return new Rule("viewer","/api/v1/knowledge/:id/index-status",false,false);
   if(Set.of("/api/v1/knowledge/{id}/spans","/api/v1/knowledge/{id}/stages").contains(pattern))return new Rule("viewer",pattern.replace("{id}",":id"),false,false);
   if(pattern.equals("/api/v1/knowledge/{id}/chunks/{chunk_id}/details"))return new Rule("viewer","/api/v1/knowledge/:id/chunks/:chunk_id/details",false,false);
   if(pattern.equals("/api/v1/knowledge-bases/{id}/knowledge"))return new Rule("viewer","/api/v1/knowledge-bases/:id/knowledge",false,false);
   if(Set.of("/api/v1/knowledge/{id}","/api/v1/knowledge/{id:(?!batch$|search$).+}").contains(pattern))return new Rule("viewer","/api/v1/knowledge/:id",false,false);
   if(pattern.equals("/api/v1/chunks/{knowledge_id}"))return new Rule("viewer","/api/v1/chunks/:knowledge_id",false,false);
   if(pattern.equals("/api/v1/chunks/by-id/{id}"))return new Rule("viewer","/api/v1/chunks/by-id/:id",false,false);
  }
  return null;
 }
}
