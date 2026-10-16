package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.weknora.task.TaskRecord;
import java.util.*;

/** Per-delivery tracing context. Business transaction/lease/source fencing remains authoritative. */
final class KnowledgeProcessingSpanPipeline {
  private final KnowledgeProcessingSpanTracker tracker;
  private final ObjectMapper json;
  private final long owner;
  private final String knowledge;
  private final TaskRecord job;
  private final Map<String,KnowledgeProcessingSpanTracker.Span> stages=new LinkedHashMap<>();
  private long attempt;
  private String active="docreader";
  private boolean failed;
  KnowledgeProcessingSpanPipeline(KnowledgeProcessingSpanTracker tracker,ObjectMapper json,TaskRecord job,boolean latestFallback) {
    this.tracker=tracker;this.json=json;this.job=job;owner=job.tenantId();knowledge=job.payload().path("knowledge_id").asText();
    attempt=job.payload().path("attempt").asLong();
    if(tracker!=null&&attempt<=0&&latestFallback)attempt=tracker.latest(owner,knowledge);
  }
  void open() {
    if(tracker==null||attempt>0)return;
    try {
      attempt=tracker.open(owner,knowledge,job.payload().path("langfuse_trace_id").asText()).number();
      // TaskRecord owns a delivery-local payload copy; do not rewrite the durable queue payload.
      if(job.payload() instanceof ObjectNode payload)payload.put("attempt",attempt);
    }catch(RuntimeException ignored){attempt=0;}
  }
  boolean superseded() {return tracker!=null&&attempt>0&&tracker.latest(owner,knowledge)>attempt;}
  void begin(String stage,ObjectNode input) {
    active=stage;if(tracker==null||attempt<=0)return;
    stages.put(stage,tracker.beginStage(owner,knowledge,attempt,stage,input));
  }
  void end(String stage,ObjectNode output) {if(tracker!=null)tracker.end(stages.get(stage),output);}
  void skip(String stage,String reason) {
    if(!stages.containsKey(stage))begin(stage,null);
    if(tracker!=null)tracker.skip(stages.get(stage),reason);
  }
  void fail(String stage,String code,String message,Throwable detail) {
    if(tracker==null||attempt<=0||failed)return;
    failed=true;
    if(!stages.containsKey(stage))begin(stage,null);
    tracker.fail(stages.get(stage),code,message,detail);
    // Java publication is atomic: any other planned-but-unpublished stages must stop too.
    tracker.abort(owner,knowledge,attempt,"UPSTREAM_FAILED",message,"processing attempt failed");
  }
  void failActive(String code,String message,Throwable detail) {fail(active,code,message,detail);}
  String active() {return active;}
  void abort(String code,String reason) {if(tracker!=null&&attempt>0)tracker.abort(owner,knowledge,attempt,code,reason,reason);}
  void finish(ObjectNode output) {if(tracker!=null&&attempt>0)tracker.finalizeAttempt(owner,knowledge,attempt,"done",output,"","");}
  void finishStatus(ObjectNode row,String reason) {
    if(tracker!=null&&attempt<=0&&Set.of("completed","failed","cancelled","deleting").contains(row.path("parse_status").asText()))attempt=tracker.latest(owner,knowledge);
    if(tracker==null||attempt<=0)return;
    switch(row.path("parse_status").asText()) {
      case "completed" -> finish(null);
      case "failed" -> tracker.finalizeAttempt(owner,knowledge,attempt,"failed",null,"UNKNOWN",row.path("error_message").asText());
      case "cancelled","deleting" -> abort("USER_CANCELLED",reason);
      default -> abort("TASK_SUPERSEDED",reason);
    }
  }
}
