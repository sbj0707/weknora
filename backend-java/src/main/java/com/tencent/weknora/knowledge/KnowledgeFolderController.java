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
public final class KnowledgeFolderController {
  private final KnowledgeFolderService folders;private final ObjectMapper json;
  public KnowledgeFolderController(KnowledgeFolderService folders,ObjectMapper json){this.folders=folders;this.json=json;}
  @GetMapping("/api/v1/knowledge-bases/{id}/knowledge/folders")
  public Map<String,Object> tree(HttpServletRequest request,@PathVariable String id){var caller=CurrentAuth.get(request);var grant=folders.read(caller,id,request.getParameter("agent_id"),request.getParameter("agent_source_tenant_id"));String clean=KnowledgeImportNames.sanitizeForLog(id);if(!clean.equals(id))grant=folders.read(caller,clean,request.getParameter("agent_id"),request.getParameter("agent_source_tenant_id"));return Map.of("success",true,"data",folders.tree(grant));}
  @PutMapping("/api/v1/knowledge-bases/{id}/knowledge/folders")
  public Map<String,Object> rename(HttpServletRequest request,@PathVariable String id)throws IOException{var grant=folders.renameGrant(CurrentAuth.get(request),id,request.getRequestURI());var body=KnowledgeFolderRequest.decode(request.getInputStream().readAllBytes(),json,false);return result(folders.rename(grant,body.from(),body.to()),body.to());}
  @PostMapping("/api/v1/knowledge/folder")
  public Map<String,Object> move(HttpServletRequest request)throws IOException{var caller=CurrentAuth.get(request);ChunkLifecycleService.capability(caller,"ingest");caller.requireRole("contributor");var body=KnowledgeFolderRequest.decode(request.getInputStream().readAllBytes(),json,true);var grant=folders.moveGrant(caller,body.kb(),request.getRequestURI());return result(folders.move(grant,body.ids(),body.to()),body.to());}
  private static Map<String,Object> result(long count,String path){return Map.of("success",true,"data",Map.of("moved_count",count,"folder_path",KnowledgeImportNames.normalizeFolder(path)));}
}
