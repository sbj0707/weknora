package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/** Internal native lifecycle recorder; task handlers must pass the fenced owner. */
@Service
public class KnowledgeProcessingSpanTracker {
  public record Span(long owner,String knowledge,long attempt,String id,String parent,String name,String kind,Instant started) {}
  public record Attempt(Span root,long number) {}
  private final KnowledgeProcessingSpanWriteRepository repository;
  private final ObjectMapper json;
  private final Clock clock;
  private final Supplier<String> ids;
  private final Map<String,Instant> starts=new ConcurrentHashMap<>();
  @org.springframework.beans.factory.annotation.Autowired
  public KnowledgeProcessingSpanTracker(KnowledgeProcessingSpanWriteRepository repository,ObjectMapper json) {this(repository,json,Clock.systemUTC());}
  public KnowledgeProcessingSpanTracker(KnowledgeProcessingSpanWriteRepository repository,ObjectMapper json,Clock clock) {this(repository,json,clock,()->UUID.randomUUID().toString().replace("-",""));}
  KnowledgeProcessingSpanTracker(KnowledgeProcessingSpanWriteRepository repository,ObjectMapper json,Clock clock,Supplier<String> ids) {this.repository=repository;this.json=json;this.clock=clock;this.ids=ids;}
  public Attempt open(long owner,String knowledge,String trace) {
    long attempt=repository.next(owner,knowledge);Instant now=clock.instant();
    var span=new Span(owner,knowledge,attempt,ids.get(),"","knowledge_processing","root",now);
    var row=row(span,"running");var metadata=json.createObjectNode();if(trace!=null&&!trace.isEmpty())metadata.put("langfuse_trace_id",trace);row.set("metadata",metadata);
    repository.upsert(owner,row);starts.put(span.id(),now);heartbeat(span);return new Attempt(span,attempt);
  }
  public long latest(long owner,String knowledge) {try {return repository.latest(owner,knowledge);}catch(RuntimeException ignored){return 0;}}
  public Span beginStage(long owner,String knowledge,long attempt,String name,ObjectNode input) {
    if(knowledge.isEmpty()||name.isEmpty())return null;
    try {
      String root="";ObjectNode existing=null;
      for(var row:repository.list(owner,knowledge,attempt)) {
        if(row.path("kind").asText().equals("root")&&root.isEmpty())root=row.path("span_id").asText();
        if(row.path("kind").asText().equals("stage")&&row.path("name").asText().equals(name))existing=row;
      }
      var span=new Span(owner,knowledge,existing==null?attempt:existing.path("attempt").asLong(),existing==null?ids.get():existing.path("span_id").asText(),existing==null?root:existing.path("parent_span_id").asText(),name,"stage",clock.instant());
      var row=row(span,"running");if(input!=null)row.set("input",input);repository.upsert(owner,row);starts.put(span.id(),span.started());heartbeat(span);return span;
    }catch(RuntimeException ex){return null;}
  }
  public Span beginSubspan(Span parent,String name,String kind,ObjectNode input) {
    if(parent==null||name.isEmpty())return null;
    name=fitName(name);if(!Set.of("generation","subspan").contains(kind))kind="subspan";
    try {
      try {repository.cancelOpen(parent.owner(),parent.knowledge(),parent.attempt(),name,"TASK_SUPERSEDED","superseded by a new run of the same subtask");}catch(RuntimeException ignored){}
      var span=new Span(parent.owner(),parent.knowledge(),parent.attempt(),ids.get(),parent.id(),name,kind,clock.instant());var row=row(span,"running");if(input!=null)row.set("input",input);repository.upsert(span.owner(),row);starts.put(span.id(),span.started());return span;
    }catch(RuntimeException ex){return null;}
  }
  public void end(Span span,ObjectNode output) {
    if(span==null)return;var row=finished(span,"done",true);if(output!=null)row.set("output",output);save(span,row);heartbeat(span);
  }
  public void skip(Span span,String reason) {
    if(span==null)return;var row=finished(span,"skipped",false).put("error_message",reason);save(span,row);heartbeat(span);
  }
  public void fail(Span span,String code,String message,Throwable detail) {
    if(span==null)return;
    var row=finished(span,"failed",true).put("error_code",trimSpace(code)).put("error_message",truncate(message,1024)).put("error_detail",detail==null?"":truncate(detail.getMessage()==null?detail.toString():detail.getMessage(),8192));save(span,row);
    String reason="upstream "+span.name()+" failed"+(code.isEmpty()?"":" ("+code+")");
    try {repository.cancelDescendants(span.owner(),span.knowledge(),span.attempt(),span.id(),reason);}catch(RuntimeException ignored){}
    if(span.kind().equals("stage")) {
      var downstream=dependents(span.name());
      try {for(var other:repository.list(span.owner(),span.knowledge(),span.attempt()))if(other.path("kind").asText().equals("stage")&&downstream.contains(other.path("name").asText())&&Set.of("pending","running").contains(other.path("status").asText())) {
        other.put("status","cancelled").put("error_code","UPSTREAM_FAILED").put("error_message",reason).put("finished_at",clock.instant().toString()).remove("updated_at");
        repository.upsert(span.owner(),other);repository.cancelDescendants(span.owner(),span.knowledge(),span.attempt(),other.path("span_id").asText(),reason);
      }}catch(RuntimeException ignored){}
      if(KnowledgeProcessingSpanTree.STAGES.contains(span.name()))finalizeAttempt(span.owner(),span.knowledge(),span.attempt(),"failed",null,code,message);
    }
    heartbeat(span);
  }
  public Span lookupStage(long owner,String knowledge,long attempt,String name) {return lookup(owner,knowledge,attempt,name,true);}
  public void finishRunningStage(long owner,String knowledge,long attempt,String name) {
    if(knowledge.isEmpty()||attempt<=0||name.isEmpty())return;
    try{for(var row:repository.list(owner,knowledge,attempt))if(row.path("kind").asText().equals("stage")&&row.path("name").asText().equals(name)&&row.path("status").asText().equals("running")){end(handle(owner,row),null);return;}}catch(RuntimeException ignored){}
  }
  public Span lookupName(long owner,String knowledge,long attempt,String name) {if(knowledge.isEmpty()||attempt<=0||name.isEmpty())return null;return lookup(owner,knowledge,attempt,fitName(name),false);}
  private Span lookup(long owner,String knowledge,long attempt,String name,boolean stage) {
    Span found=null;try {for(var row:repository.list(owner,knowledge,attempt))if(row.path("name").asText().equals(name)&&(!stage||row.path("kind").asText().equals("stage")))return handle(owner,row);}catch(RuntimeException ignored){}return found;
  }
  public void finalizeAttempt(long owner,String knowledge,long attempt,String status,ObjectNode output,String code,String message) {
    if(knowledge.isEmpty()||attempt<=0)return;
    try {for(var root:repository.list(owner,knowledge,attempt))if(root.path("kind").asText().equals("root")) {
      if(Set.of("done","failed","cancelled","skipped").contains(root.path("status").asText()))return;
      var span=handle(owner,root);var row=finished(span,status,false);row.put("duration_ms",elapsed(span.started(),clock.instant())).put("error_code",trimSpace(code)).put("error_message",truncate(message,1024));
      if(!root.has("started_at"))row.putNull("started_at");
      if(root.has("input"))row.set("input",root.get("input"));if(root.has("metadata"))row.set("metadata",root.get("metadata"));if(output!=null)row.set("output",output);save(span,row);heartbeat(span);return;
    }}catch(RuntimeException ignored){}
  }
  public void abort(long owner,String knowledge,long attempt,String code,String message,String reason) {
    if(knowledge.isEmpty()||attempt<=0)return;if(reason.isEmpty())reason="user cancelled";if(code.isEmpty())code="USER_CANCELLED";
    try {repository.cancelOpen(owner,knowledge,attempt,null,code,reason);}catch(RuntimeException ignored){}
    finalizeAttempt(owner,knowledge,attempt,"cancelled",null,code,message);
  }
  private ObjectNode row(Span span,String status) {return json.createObjectNode().put("knowledge_id",span.knowledge()).put("attempt",span.attempt()).put("span_id",span.id()).put("parent_span_id",span.parent()).put("name",span.name()).put("kind",span.kind()).put("status",status).put("started_at",span.started().toString());}
  private ObjectNode finished(Span span,String status,boolean cached) {
    Instant now=clock.instant(),start=cached?starts.remove(span.id()):null;
    return row(span,status).put("finished_at",now.toString()).put("duration_ms",cached?elapsed(start==null?span.started():start,now):0);
  }
  private static long elapsed(Instant start,Instant end) {return start.equals(Instant.parse("0001-01-01T00:00:00Z"))?0:Duration.between(start,end).toMillis();}
  private Span handle(long owner,ObjectNode row) {return new Span(owner,row.path("knowledge_id").asText(),row.path("attempt").asLong(),row.path("span_id").asText(),row.path("parent_span_id").asText(),row.path("name").asText(),row.path("kind").asText(),row.has("started_at")?Instant.parse(row.path("started_at").asText()):Instant.parse("0001-01-01T00:00:00Z"));}
  private void save(Span span,ObjectNode row) {try {repository.upsert(span.owner(),row);}catch(RuntimeException ignored){}}
  private void heartbeat(Span span) {try {repository.heartbeat(span.owner(),span.knowledge(),span.kind());}catch(RuntimeException ignored){}}
  static String fitName(String name) {
    if(name.codePointCount(0,name.length())<=255)return name;
    try {byte[] digest=MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8));return name.substring(0,name.offsetByCodePoints(0,246))+"~"+HexFormat.of().formatHex(Arrays.copyOf(digest,4));}catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
  }
  static String truncate(String value,int limit) {
    byte[] bytes=value.getBytes(StandardCharsets.UTF_8);if(bytes.length<=limit)return clean(value);
    try {return clean(StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.IGNORE).onUnmappableCharacter(java.nio.charset.CodingErrorAction.IGNORE).decode(java.nio.ByteBuffer.wrap(bytes,0,limit)).toString());}catch(java.nio.charset.CharacterCodingException ex){throw new IllegalArgumentException(ex);}
  }
  static String clean(String value) {var out=new StringBuilder();value.codePoints().filter(cp->cp!=0&&(cp<0xD800||cp>0xDFFF)).forEach(out::appendCodePoint);return out.toString();}
  static String trimSpace(String value) {return value.replaceAll("^[\\p{IsWhite_Space}]+|[\\p{IsWhite_Space}]+$","");}
  private static Set<String> dependents(String stage) {
    return switch(stage) {case "docreader"->Set.of("chunking","embedding","multimodal","postprocess");case "chunking"->Set.of("embedding","multimodal","postprocess");case "embedding","multimodal"->Set.of("postprocess");default->Set.of();};
  }
}
