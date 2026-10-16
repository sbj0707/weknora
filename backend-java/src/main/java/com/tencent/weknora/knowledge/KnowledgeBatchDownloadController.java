package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.chunker.GoJsonFirstValue;
import com.tencent.weknora.docreader.GoUtf8;
import com.tencent.weknora.security.*;
import com.tencent.weknora.storage.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/** Complete private ZIP before any response bytes; validate the entire authorized KB batch first. */
@RestController
public final class KnowledgeBatchDownloadController {
  private static final Semaphore SLOTS=new Semaphore(4);
  private static final long LIMIT=512L*1024*1024;
  private final JdbcTemplate jdbc;private final KnowledgeBaseWriteAuthorization writes;private final KnowledgeFileService files;private final ObjectMapper json;private final JsonNode schema;
  public KnowledgeBatchDownloadController(JdbcTemplate jdbc,KnowledgeBaseWriteAuthorization writes,KnowledgeFileService files,ObjectMapper json){this.jdbc=jdbc;this.writes=writes;this.files=files;this.json=json;ObjectNode root=json.createObjectNode().put("kind","struct").put("name","BatchDownloadKnowledgeRequest").put("type","handler.BatchDownloadKnowledgeRequest");ObjectNode field=root.putArray("fields").addObject().put("name","ids");field.putObject("schema").put("kind","slice").put("type","[]string").putObject("elem").put("kind","string").put("type","string");schema=root;}
  @PostMapping("/api/v1/knowledge-bases/{id}/knowledge/batch-download")
  public void download(HttpServletRequest request,HttpServletResponse response,@PathVariable String id)throws IOException {
    var caller=CurrentAuth.get(request);caller.requireRole("contributor");KnowledgeContentReadAuthorization.requireRetrieve(caller);
    // Go's KB-access middleware runs before JSON binding. The handler resolves the same grant again.
    writes.authorizeBatchDownload(caller,id,request.getRequestURI());
    List<String> ids=decode(request.getInputStream());var grant=writes.authorizeBatchDownload(caller,id,request.getRequestURI());long owner=grant.effectiveTenantId();
    List<Map<String,Object>> rows;
    try{var parameters=new ArrayList<Object>();parameters.add(owner);parameters.addAll(ids);rows=jdbc.queryForList("SELECT id,tenant_id,knowledge_base_id,type,file_path,folder_path FROM knowledges WHERE tenant_id=? AND deleted_at IS NULL AND id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")",parameters.toArray());}
    catch(org.springframework.dao.DataAccessException failed){throw KnowledgeDownloadArchive.internal("无法获取待下载文档，请稍后重试");}
    var indexed=new HashMap<String,Map<String,Object>>();for(var row:rows)if(Objects.toString(row.get("knowledge_base_id"),"").equals(id)&&((Number)row.get("tenant_id")).longValue()==owner)indexed.put(Objects.toString(row.get("id")),row);
    var entries=new ArrayList<KnowledgeDownloadArchive.Entry>();for(String document:ids){var row=indexed.get(document);if(row==null)throw new ApiException(404,"NOT_FOUND","部分文档不存在或不属于当前知识库，请刷新列表后重试").withAppCode(1003);if(!Objects.toString(row.get("type"),"").equals("manual")&&Objects.toString(row.get("file_path"),"").isEmpty())continue;entries.add(new KnowledgeDownloadArchive.Entry(document,Objects.toString(row.get("folder_path"),"")));}
    if(entries.isEmpty())throw KnowledgeDownloadArchive.bad("所选文档没有可下载的原始文件，请取消选择后重试");
    if(!SLOTS.tryAcquire())throw new ApiException(429,"TOO_MANY_REQUESTS","当前批量下载过多，请稍后重试").withAppCode(1006);
    Path archive=null;
    try{
      try{archive=Files.createTempFile("weknora-download-",".zip");}catch(IOException failed){throw KnowledgeDownloadArchive.internal("无法创建下载压缩包，请稍后重试");}
      try(var out=Files.newOutputStream(archive)){KnowledgeDownloadArchive.write(out,entries,key->files.open(owner,key),LIMIT);}
      SeekableFileInputStream stream;try{stream=new SeekableFileInputStream(archive);}catch(IOException failed){throw KnowledgeDownloadArchive.internal("无法读取下载压缩包，请稍后重试");}
      String filename="knowledge-files-"+LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))+".zip";
      FileTransport.serve(request,response,stream,filename,true,"application/zip",false);
    }finally{try{if(archive!=null)Files.deleteIfExists(archive);}finally{SLOTS.release();}}
  }
  private List<String> decode(InputStream in){
    final String invalid="请选择 1～200 个文档进行批量下载";var unique=new LinkedHashSet<String>();
    try{byte[] body=GoJsonFirstValue.first(in,65536);JsonNode values=new GoUploadJson(json).decode(GoUtf8.decode(body),schema).path("ids");if(!values.isArray()||values.isEmpty()||values.size()>200)throw new IOException();for(var value:values){String s=value.asText("");if(s.isEmpty()||s.codePointCount(0,s.length())>128)throw new IOException();String clean=StoragePaths.trim(s);if(!clean.isEmpty())unique.add(clean);}}
    catch(Exception failed){throw KnowledgeDownloadArchive.bad(invalid);}
    if(unique.isEmpty())throw KnowledgeDownloadArchive.bad("文档 ID 不能为空");return new ArrayList<>(unique);
  }
}
