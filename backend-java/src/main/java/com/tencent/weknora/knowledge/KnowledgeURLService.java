package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.weknora.docreader.ParserConfiguration;
import com.tencent.weknora.security.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.*;

/** Native URL admission. Database checkpoints intentionally follow the Go service order. */
public final class KnowledgeURLService {
  private final JdbcTemplate jdbc;private final ObjectMapper json;private final KnowledgeRepository repository;
  private final KnowledgeFileUpload.Enqueuer tasks;private final KnowledgeProcessConfig processes;
  private final Duration timeout;private final Clock clock;
  public KnowledgeURLService(JdbcTemplate jdbc,ObjectMapper json,KnowledgeRepository repository,
      KnowledgeFileUpload.Enqueuer tasks,Duration timeout,Clock clock) {
    this.jdbc=jdbc;this.json=json;this.repository=repository;this.tasks=tasks;
    this.processes=new KnowledgeProcessConfig(json);this.timeout=timeout;this.clock=clock;
  }
  public ObjectNode create(AuthContext caller,KnowledgeBaseWriteAuthorization.WriteAccess grant,
      KnowledgeURLRequest request,String language) {
    long owner=grant.effectiveTenantId();String kbId=grant.knowledgeBaseId();var kb=processBase(grant);
    String url=request.url(),filename=request.filename(),type=request.fileType();
    String pathName=KnowledgeURLSecurity.filename(url);
    boolean file=KnowledgeProcessConfig.supported(KnowledgeProcessConfig.fileType(pathName))||!filename.isEmpty()||!type.isEmpty();
    if(file&&kb.path("type").asText().equals("faq"))throw ManualKnowledgeRequest.bad("FAQ 知识库不支持文件上传，请使用 FAQ 导入功能");
    if(!KnowledgeURLSecurity.validServiceURL(url))throw internal("invalid URL");
    String hash=hash(url);
    // Go's URL repository fallback retains the initial hash predicate; file_url has no switch case.
    if(!file) {
      var duplicates=jdbc.queryForList("SELECT id FROM knowledges WHERE tenant_id=? AND knowledge_base_id=? AND deleted_at IS NULL AND parse_status NOT IN ('failed','deleting') AND type='url' AND file_hash=? ORDER BY id LIMIT 1",owner,kbId,hash);
      if(!duplicates.isEmpty()) {
        String id=duplicates.getFirst().get("id").toString();touchDuplicate(owner,id);
        var existing=repository.get(owner,id).orElseThrow();
        throw new ApiException(409,"duplicate_url","URL already exists: "+existing.path("source").asText())
            .withResponse(Map.of("success",false,"code","duplicate_url","message","URL already exists: "+existing.path("source").asText(),"data",existing));
      }
    }
    if(file) {
      if(filename.isEmpty())filename=pathName;
      if(!filename.isEmpty()) {
        boolean valid=KnowledgeURLSecurity.safeText(filename);
        for(int i=0;i<filename.length();i++){char c=filename.charAt(i);if(c<32&&c!=9&&c!=10&&c!=13)valid=false;}
        if(!valid)throw new ApiException(400,"VALIDATION_ERROR","文件名包含非法字符").withAppCode(1010);
        filename=KnowledgeUploadForms.goTrim(filename);
      }
      if(type.isEmpty())type=KnowledgeProcessConfig.fileType(filename);
      type=ParserConfiguration.normalizeFileType(type);
    } else type="html";
    // WithExecutionTenant changes execution owner but keeps the authenticated caller's TenantInfo.
    var tenant=jdbc.queryForMap("SELECT storage_quota,storage_used FROM tenants WHERE id=? AND deleted_at IS NULL",caller.tenantId());
    if(number(tenant.get("storage_quota"))>0&&number(tenant.get("storage_used"))>=number(tenant.get("storage_quota")))throw internal("Storage quota exceeded");
    var row=repository.blank();String now=clock.instant().toString();
    row.put("id",UUID.randomUUID().toString()).put("tenant_id",owner).put("knowledge_base_id",kbId)
        .put("type",file?"file_url":"url").put("channel",request.channel().isEmpty()?"web":request.channel())
        .put("title",request.title()).put("source",url).put("file_hash",hash).put("file_type",type)
        .put("parse_status","pending").put("enable_status","disabled").put("created_at",now).put("updated_at",now)
        .put("embedding_model_id",kb.path("embedding_model_id").asText());
    if(file) {
      String display=!filename.isEmpty()?filename:!request.title().isEmpty()?request.title():!pathName.isEmpty()?pathName:url;
      row.put("file_name",display);if(request.title().isEmpty())row.put("title",display);
    }
    JsonNode overrides=request.overrides();boolean supplied=overrides!=null&&!overrides.isNull();
    ObjectNode effective;
    if(file)effective=processes.resolveFileImport(kb,overrides,request.multimodel(),type);
    else {
      effective=processes.resolve(kb,overrides);
      if(request.multimodel()!=null&&(!supplied||overrides.path("enable_multimodel").isNull()||overrides.path("enable_multimodel").isMissingNode()))effective.put("enable_multimodel",request.multimodel());
      processes.validateProcessOverrides(kb,overrides,List.of("html"));
    }
    if(supplied)row.set("metadata",json.createObjectNode().set("process_overrides",processes.normalizeOverrides(overrides)));
    repository.create(owner,row);
    repository.assignTags(owner,kbId,row,request.tags());
    var question=effective.path("question_generation_config");
    var payload=json.createObjectNode().put("tenant_id",owner).put("knowledge_id",row.path("id").asText())
        .put("knowledge_base_id",kbId).put("request_id","").put("language",language)
        .put("enable_multimodel",effective.path("enable_multimodel").asBoolean())
        .put("enable_question_generation",question.path("enabled").asBoolean())
        .put("question_count",question.path("question_count").asInt()>0?question.path("question_count").asInt():3);
    if(file){payload.put("file_url",url);if(!filename.isEmpty())payload.put("file_name",filename);if(!type.isEmpty())payload.put("file_type",type);}
    else payload.put("url",url);
    String task;
    try {task=tasks.enqueue(owner,"document:process",payload,new KnowledgeFileUpload.TaskOptions("default",3,timeout));}
    catch(Exception error){repository.markEnqueueFailed(owner,row,clock.instant());audit(caller,owner,kbId,row,"",false);return row;}
    audit(caller,owner,kbId,row,task,true);
    if(file&&KnowledgeProcessConfig.dataTable(type))try {
      tasks.enqueue(owner,"datatable:summary",json.createObjectNode().put("tenant_id",owner)
          .put("knowledge_id",row.path("id").asText()).put("summary_model",kb.path("summary_model_id").asText())
          .put("embedding_model",kb.path("embedding_model_id").asText()),new KnowledgeFileUpload.TaskOptions("summary",3,Duration.ofMinutes(30)));
    }catch(Exception error){org.slf4j.LoggerFactory.getLogger(getClass()).warn("URL table summary enqueue failed after document admission");}
    return row;
  }
  private void touchDuplicate(long owner,String id) {
    // GORM Save writes zero-valued fields too, including defaults applied by AfterFind.
    var row=repository.get(owner,id).orElseThrow();String now=clock.instant().toString();
    row.put("created_at",now).put("updated_at",now);
    var fields=List.of("title","description","source","channel","parse_status","pending_subtasks_count","summary_status","enable_status","embedding_model_id","file_name","folder_path","file_type","file_size","file_hash","file_path","storage_size","error_message");
    var values=new ArrayList<Object>();var sets=new ArrayList<String>();
    for(String field:fields){sets.add(field+"=?");var value=row.path(field);values.add(value.isNumber()?value.longValue():value.asText(""));}
    sets.add("created_at=?");sets.add("updated_at=?");boolean postgres=Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>)c->c.getMetaData().getDatabaseProductName().equals("PostgreSQL")));Object timestamp=postgres?java.sql.Timestamp.from(clock.instant()):GoSqliteTimestamp.write(clock.instant());values.add(timestamp);values.add(timestamp);
    values.add(owner);values.add(id);jdbc.update("UPDATE knowledges SET "+String.join(",",sets)+" WHERE tenant_id=? AND id=? AND deleted_at IS NULL",values.toArray());
  }
  private void audit(AuthContext caller,long owner,String kb,ObjectNode row,String task,boolean accepted) {
    try {
      var details=json.createObjectNode().put("title",row.path("title").asText()).put("source_type",row.path("type").asText())
          .put("file_type",row.path("file_type").asText()).put("processing_status",accepted?"pending":"failed");
      if(accepted)details.put("task_id",task).put("trigger",caller.userId().isEmpty()||caller.userId().matches("system-[0-9]+")?"system":"user");
      else details.put("failure_stage","enqueue");
      if(caller.isApiKey()&&caller.apiKeyId()>0) {
        details.put("api_key_id",caller.apiKeyId());var names=jdbc.queryForList("SELECT name FROM tenant_api_keys WHERE id=?",caller.apiKeyId());
        if(!names.isEmpty()&&names.getFirst().get("name")!=null&&!names.getFirst().get("name").toString().isEmpty())details.put("api_key_name",names.getFirst().get("name").toString());
      }
      boolean pg=Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>)c->c.getMetaData().getDatabaseProductName().equals("PostgreSQL")));
      jdbc.update("INSERT INTO audit_logs(tenant_id,actor_user_id,actor_role,action,target_type,target_id,scope_type,scope_id,outcome,details) VALUES(?,?,?,'knowledge.created','knowledge',?,'knowledge_base',?,?,"+(pg?"CAST(? AS JSONB)":"?")+")",owner,caller.userId(),caller.role(),row.path("id").asText(),kb,accepted?"accepted":"failed",details.toString());
    }catch(Exception error){org.slf4j.LoggerFactory.getLogger(getClass()).warn("URL creation audit failed after business mutation");}
  }
  private ObjectNode processBase(KnowledgeBaseWriteAuthorization.WriteAccess grant) {
    var kb=grant.knowledgeBase().deepCopy();
    boolean sqlite=Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>)c->c.getMetaData().getDatabaseProductName().equals("SQLite")));
    if(sqlite) {
      var raw=jdbc.queryForMap("SELECT * FROM knowledge_bases WHERE tenant_id=? AND id=? AND deleted_at IS NULL",grant.effectiveTenantId(),grant.knowledgeBaseId());
      // These Go sql.Scanner implementations only unmarshal []byte. SQLite TEXT returns string.
      for(String field:List.of("chunking_config","image_processing_config","question_generation_config","vlm_config","asr_config","extract_config"))
        if(raw.get(field) instanceof String)kb.set(field,json.createObjectNode());
    }
    return kb;
  }
  private static long number(Object value){return value==null?0:((Number)value).longValue();}
  private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
  private static ApiException internal(String message){return new ApiException(500,"INTERNAL_SERVER_ERROR",message).withAppCode(1007);}
}
