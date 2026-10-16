package com.tencent.weknora.knowledge;

import com.tencent.weknora.security.*;
import com.tencent.weknora.storage.FileTransport;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.springframework.web.bind.annotation.*;

/** Download exposes original bytes under an Editor grant; previews accept a Viewer grant. */
@RestController
public final class KnowledgeFileController {
  private final KnowledgeContentReadAuthorization reads;private final KnowledgeBaseWriteAuthorization writes;private final KnowledgeFileService files;
  public KnowledgeFileController(KnowledgeContentReadAuthorization reads,KnowledgeBaseWriteAuthorization writes,KnowledgeFileService files){this.reads=reads;this.writes=writes;this.files=files;}
  @GetMapping("/api/v1/knowledge/{id}/download")
  public void download(HttpServletRequest request,HttpServletResponse response,@PathVariable String id)throws IOException {serve(request,response,id,true);}
  @GetMapping("/api/v1/knowledge/{id}/preview")
  public void preview(HttpServletRequest request,HttpServletResponse response,@PathVariable String id)throws IOException {serve(request,response,id,false);}
  private void serve(HttpServletRequest request,HttpServletResponse response,String id,boolean download)throws IOException {
    // Spring's implicit HEAD mapping must not expose a new endpoint absent from Go's GET router.
    if(!request.getMethod().equals("GET")){response.setStatus(404);response.setHeader("Content-Type","text/plain");response.getOutputStream().write("404 page not found".getBytes(StandardCharsets.UTF_8));return;}
    var caller=CurrentAuth.get(request);caller.requireRole(download?"contributor":"viewer");KnowledgeContentReadAuthorization.requireRetrieve(caller);
    String clean=KnowledgeImportNames.sanitizeForLog(id);if(clean.isEmpty())throw new ApiException(400,"BAD_REQUEST","Knowledge ID cannot be empty").withAppCode(1000);
    var document=reads.lookupKnowledge(id);long owner=owner(caller,document,request,download);
    // Go's route guard resolves the raw parameter, then the handler resolves its sanitized ID.
    // A changed ID must acquire its own KB grant and API-key allowlist check before opening bytes.
    if(!clean.equals(id)){
      document=reads.lookupKnowledge(clean);
      try{owner=owner(caller,document,request,download);}catch(ApiException denied){
        if(denied.status()==403&&denied.getMessage().equals("Permission denied to access this knowledge base"))throw new ApiException(403,"FORBIDDEN","Permission denied to access this knowledge").withAppCode(1002);
        throw denied;
      }
    }
    if(document.path("tenant_id").asLong()!=owner)throw new ApiException(403,"FORBIDDEN","Permission denied to access this knowledge").withAppCode(1002);
    KnowledgeFileService.Content content;
    try{content=files.open(owner,clean);}catch(Exception failure){throw new ApiException(500,"INTERNAL_SERVER_ERROR","Failed to retrieve file").withAppCode(1007).withDetails(failure.getMessage());}
    FileTransport.serve(request,response,content.stream(),content.filename(),download);
  }
  private long owner(AuthContext caller,com.fasterxml.jackson.databind.node.ObjectNode document,HttpServletRequest request,boolean download){
    if(download)return writes.authorizeFileDownload(caller,document.path("knowledge_base_id").asText(),request.getRequestURI()).effectiveTenantId();
    return reads.authorizeKnowledge(caller,document.path("id").asText(),request.getParameter("agent_id"),request.getParameter("agent_source_tenant_id")).parent().effectiveTenantId();
  }
}
