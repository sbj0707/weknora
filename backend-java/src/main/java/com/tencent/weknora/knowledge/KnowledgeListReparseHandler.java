package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.security.*;
import com.tencent.weknora.task.*;
import java.time.Duration;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Validate the entire current selection before the first destructive submission. */
public final class KnowledgeListReparseHandler implements TaskHandler {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final KnowledgeRepository documents;
  private final KnowledgeReparseService service;
  private final KnowledgeProcessingLanguage language;
  public KnowledgeListReparseHandler(JdbcTemplate jdbc,ObjectMapper json,KnowledgeRepository documents,KnowledgeReparseService service){this(jdbc,json,documents,service,new KnowledgeProcessingLanguage(new org.springframework.core.env.StandardEnvironment()));}
  public KnowledgeListReparseHandler(JdbcTemplate jdbc,ObjectMapper json,KnowledgeRepository documents,KnowledgeReparseService service,KnowledgeProcessingLanguage language){this.jdbc=jdbc;this.json=json;this.documents=documents;this.service=service;this.language=language;}
  public String type(){return "knowledge:list_reparse";}
  public Duration timeout(){return Duration.ofHours(1);}
  public JsonNode execute(TaskRecord job,TaskCancellation cancellation)throws Exception{
    var raw=jdbc.queryForList("SELECT payload FROM java_task_jobs WHERE id=? AND tenant_id=?",job.id(),job.tenantId());
    JsonNode payload;try{payload=KnowledgeListReparsePayload.decode(raw.isEmpty()?job.payload().toString():Objects.toString(raw.getFirst().get("payload"),"null"),json);}
    catch(java.io.IOException invalid){throw new TaskFailure(invalid.getMessage(),true);}
    long owner=payload.path("tenant_id").asLong();
    if(owner<=0)throw terminal("invalid reparse task tenant");if(owner!=job.tenantId())throw terminal("reparse task owner does not match queue owner");
    var ids=new LinkedHashSet<String>();for(var id:payload.path("knowledge_ids")){String text=id.asText("");if(KnowledgeUploadForms.goTrim(text).isEmpty())throw terminal("invalid reparse task IDs");ids.add(text);}
    cancellation.check();if(ids.isEmpty())return json.createObjectNode().put("submitted",0).put("failed",0);
    var args=new ArrayList<Object>();args.add(owner);args.addAll(ids);
    var rows=jdbc.queryForList("SELECT id,tenant_id,knowledge_base_id,metadata FROM knowledges WHERE tenant_id=? AND id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+") AND deleted_at IS NULL",args.toArray());
    String kb=payload.path("knowledge_base_id").asText();var seen=new HashSet<String>();
    for(var row:rows){String parent=Objects.toString(row.get("knowledge_base_id"),"");if(parent.isEmpty()||!kb.isEmpty()&&!kb.equals(parent))throw terminal("reparse task binding changed");
      try{if(new KnowledgeTransferState(json).moving(row.get("metadata")))throw terminal("reparse task document unavailable: error code: 1005, error message: knowledge has an unfinished move; retry the move first");}
      catch(IllegalArgumentException invalid){throw terminal("reparse task document unavailable: "+invalid.getMessage());}
      kb=parent;seen.add(row.get("id").toString());
    }
    for(String id:ids)if(!seen.contains(id))throw terminal("reparse task document missing");
    JsonNode base;try{base=documents.knowledgeBase(owner,kb);}catch(ApiException failure){throw new TaskFailure(failure.status()==404?"knowledge base not found":workerError(failure),true);}
    if(base.path("tenant_id").asLong()!=owner)throw terminal("reparse task KB binding changed");
    if(jdbc.queryForList("SELECT id FROM tenants WHERE id=? AND deleted_at IS NULL",owner).isEmpty())throw new TaskFailure("tenant not found",true);
    var worker=new KnowledgeReparseService.WorkerContext(job.id(),payload.path("initiator"),cancellation::check);
    var failures=new ArrayList<String>();int submitted=0;
    for(String id:ids){cancellation.check();try{service.reparseFromTask(owner,kb,id,payload.get("process_config"),language.resolve((String)null),worker);submitted++;}
      catch(java.util.concurrent.CancellationException canceled){throw canceled;}
      catch(RuntimeException error){failures.add("knowledge "+KnowledgeImportNames.sanitizeForLog(id)+": "+workerError(error));}}
    if(!failures.isEmpty())throw new TaskFailure("skip retry for the task: batch reparse submitted "+submitted+" item(s) and failed "+failures.size()+": "+String.join("\n",failures),false);
    return json.createObjectNode().put("submitted",submitted).put("failed",0);
  }
  private static String workerError(RuntimeException error){
    if(error instanceof ApiException typed){int code=typed.appCode()==null?switch(typed.status()){case 400->1000;case 401->1001;case 403->1002;case 404->1003;case 409->1005;default->1007;}:typed.appCode();return "error code: "+code+", error message: "+typed.getMessage();}
    if(error instanceof org.springframework.dao.DataAccessException data)return ChunkEditService.diagnostic(data);
    return Objects.toString(error.getMessage(),"Internal server error");
  }
  private static TaskFailure terminal(String message){return new TaskFailure(message+": skip retry for the task",false);}
}
