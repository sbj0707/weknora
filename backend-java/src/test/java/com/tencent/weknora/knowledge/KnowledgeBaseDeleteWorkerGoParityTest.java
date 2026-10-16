package com.tencent.weknora.knowledge;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.model.client.CallContext;
import java.time.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.*;
import org.junit.jupiter.api.*;

/** Actual Go worker stages, controlled external calls, real SQL scopes and retry-visible state. */
class KnowledgeBaseDeleteWorkerGoParityTest extends KnowledgeFolderGoHttpParityTest {
  JsonNode rows()throws Exception{try(var in=getClass().getResourceAsStream("/go-knowledge-base-delete-worker.json")){return json.readTree(in);}}
  @Override @TestFactory Stream<DynamicTest> folders()throws Exception {
    var cases=rows();assertThat(cases.size()).isEqualTo(40);var n=new AtomicInteger();
    return StreamSupport.stream(cases.spliterator(),false).map(row->DynamicTest.dynamicTest(row.path("name").asText(),()->compareWorker(row,n.incrementAndGet(),false)));
  }
  @Test void interruptedParentStillRunsDetachedCleanup()throws Exception {
    JsonNode oracle=null;for(var row:rows())if(row.path("name").asText().equals("context-cancel"))oracle=row;
    assertThat(oracle).isNotNull();compareWorker(oracle,1000,true);
    assertThat(Thread.currentThread().isInterrupted()).isFalse();
  }
  @Test void sqlDeadlineStopsActualDatabaseWorkAndReleasesConnection()throws Exception {
    var f=database(2000);
    try(var call=CallContext.timeout(Duration.ofMillis(40))) {
      var sql=new KnowledgeBaseDeleteSql(f.jdbc);long start=System.nanoTime();
      assertThatThrownBy(()->sql.query(call,"WITH RECURSIVE busy(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM busy WHERE n<1000000000) SELECT SUM(n) FROM busy"))
        .isInstanceOf(com.tencent.weknora.model.client.ModelClientException.class).hasMessageContaining("deadline exceeded");
      assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(3));
      assertThat(f.jdbc.queryForObject("SELECT 1",Integer.class)).isEqualTo(1);
    }finally{release(f);}
  }
  @Test void detachedCleanupIgnoresLateParentInterruption()throws Exception {
    Thread parent=Thread.currentThread();var stages=new AtomicInteger();var sweeps=new AtomicInteger();
    var repository=new KnowledgeBaseDeleteProcessor.Repository(){
      public List<KnowledgeBaseDeleteProcessor.Document> documents(KnowledgeBaseDeleteProcessor.Payload p,CallContext c){return List.of();}
      public List<String> images(KnowledgeBaseDeleteProcessor.Payload p,List<String> ids,CallContext c){throw new AssertionError("no documents");}
      public void chunks(KnowledgeBaseDeleteProcessor.Payload p,String id,CallContext c){throw new AssertionError("no documents");}
      public void accounting(KnowledgeBaseDeleteProcessor.Payload p,long delta,CallContext c){throw new AssertionError("no documents");}
      public void documentsDeleted(KnowledgeBaseDeleteProcessor.Payload p,List<String> ids,CallContext c){throw new AssertionError("no documents");}
      public void wiki(KnowledgeBaseDeleteProcessor.Payload p,String table,CallContext c){if(stages.incrementAndGet()==1)parent.interrupt();c.check();}
    };
    var effects=new KnowledgeBaseDeleteProcessor.Effects(){
      public KnowledgeBaseDeleteProcessor.Indexes resolve(KnowledgeBaseDeleteProcessor.Payload p,CallContext c){throw new AssertionError("no documents");}
      public int dimensions(KnowledgeBaseDeleteProcessor.Payload p,String model,CallContext c){throw new AssertionError("no documents");}
      public void file(KnowledgeBaseDeleteProcessor.Payload p,String ref,CallContext c){throw new AssertionError("no documents");}
      public boolean releaseImage(KnowledgeBaseDeleteProcessor.Payload p,String ref,List<String> ids,CallContext c){throw new AssertionError("no documents");}
      public void graph(KnowledgeBaseDeleteProcessor.Payload p,List<String> ids,CallContext c){throw new AssertionError("no documents");}
      public void scrub(KnowledgeBaseDeleteProcessor.Payload p,List<String> ids,CallContext c){c.check();sweeps.incrementAndGet();}
    };
    try(var call=new CallContext()){
      new KnowledgeBaseDeleteProcessor(repository,effects).process(new KnowledgeBaseDeleteProcessor.Payload(1,"kb",List.of(),json.createArrayNode(),null),call);
      assertThat(stages.get()).isEqualTo(4);assertThat(sweeps.get()).isEqualTo(2);assertThat(parent.isInterrupted()).isTrue();
    }finally{Thread.interrupted();}
  }
  @Override DocumentProcessingCompatibilityTest database(int no)throws Exception {
    var f=super.database(no);
    try(var c=f.source.getConnection()){org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(c,new org.springframework.core.io.FileSystemResource("../migrations/sqlite/000006_task_queue_and_dead_letters.up.sql"));}
    return f;
  }
  void compareWorker(JsonNode oracle,int no,boolean interrupt)throws Exception {
    var f=database(no);
    try {
      for(String table:List.of("chunks","knowledges","knowledge_bases","tenants","users"))f.jdbc.update("DELETE FROM "+table);
      for(var statement:oracle.path("seed_statements"))f.jdbc.execute(statement.asText());
      var raw=oracle.path("payload");var dataSources=new ArrayList<String>();raw.path("data_source_ids").forEach(x->dataSources.add(x.asText()));
      var payload=new KnowledgeBaseDeleteProcessor.Payload(raw.path("tenant_id").asLong(),raw.path("knowledge_base_id").asText(),dataSources,raw.path("effective_engines"),raw.has("vector_store_id")?raw.path("vector_store_id").asText():null);
      var events=json.createArrayNode();var results=json.createArrayNode();String original=oracle.path("name").asText();String[] variant={original.equals("retry-document-error")?"documents-error":original};
      var real=new KnowledgeBaseDeleteRepository(f.jdbc,json,f.manager,Clock.systemUTC());
      class Recorder {
        void event(CallContext call,String op,Map<String,Object> values) {
          var details=new LinkedHashMap<>(values);details.put("op",op);boolean canceled=false;
          try{call.check();}catch(RuntimeException error){canceled=true;}
          details.put("canceled",canceled);details.put("short_deadline",call.deadline()!=null&&!call.deadline().isAfter(Instant.now().plusSeconds(6)));events.add(json.valueToTree(details));
          if(variant[0].contains("wiki-all-error")&&op.startsWith("wiki_")||variant[0].equals(op+"-error")||variant[0].equals("skip-store-wiki-error")&&op.equals("wiki_pages"))throw new IllegalStateException("controlled "+op+" error");
        }
      }
      var recorder=new Recorder();
      var repository=new KnowledgeBaseDeleteProcessor.Repository(){
        public List<KnowledgeBaseDeleteProcessor.Document> documents(KnowledgeBaseDeleteProcessor.Payload p,CallContext c)throws Exception{recorder.event(c,"list",Map.of());return real.documents(p,c);}
        public List<String> images(KnowledgeBaseDeleteProcessor.Payload p,List<String> ids,CallContext c)throws Exception{recorder.event(c,"images",Map.of("ids",ids));return real.images(p,ids,c);}
        public void chunks(KnowledgeBaseDeleteProcessor.Payload p,String id,CallContext c)throws Exception{recorder.event(c,"chunks",Map.of("id",id));real.chunks(p,id,c);}
        public void accounting(KnowledgeBaseDeleteProcessor.Payload p,long delta,CallContext c)throws Exception{recorder.event(c,"accounting",Map.of("delta",delta));real.accounting(p,delta,c);}
        public void documentsDeleted(KnowledgeBaseDeleteProcessor.Payload p,List<String> ids,CallContext c)throws Exception{recorder.event(c,"documents",Map.of("ids",ids));real.documentsDeleted(p,ids,c);}
        public void wiki(KnowledgeBaseDeleteProcessor.Payload p,String table,CallContext c)throws Exception{recorder.event(c,table,Map.of());real.wiki(p,table,c);}
      };
      var effects=new KnowledgeBaseDeleteProcessor.Effects(){
        public KnowledgeBaseDeleteProcessor.Indexes resolve(KnowledgeBaseDeleteProcessor.Payload p,CallContext c)throws Exception {
          if(p.vectorStoreId()!=null&&!p.vectorStoreId().isEmpty()){
            recorder.event(c,"resolve",Map.of());
            switch(original){
              case "skip-store","skip-store-wiki-error"->throw new KnowledgeBaseDeleteProcessor.Failure(KnowledgeBaseDeleteProcessor.Verdict.SKIP_RETRY,"skip retry for the task");
              case "store-error"->throw new KnowledgeBaseDeleteProcessor.Failure(KnowledgeBaseDeleteProcessor.Verdict.RETRY,"vector store engine unavailable");
              case "store-cancel"->throw new CancellationException("context canceled");
            }
          }
          return (ids,dimension,type,call)->recorder.event(call,"indexes",Map.of("ids",ids,"dimensions",dimension,"type",type));
        }
        public int dimensions(KnowledgeBaseDeleteProcessor.Payload p,String model,CallContext c){recorder.event(c,"model",Map.of("id",model));if(model.isEmpty())throw new IllegalStateException("empty model");return 2;}
        public void file(KnowledgeBaseDeleteProcessor.Payload p,String reference,CallContext c){recorder.event(c,"file",Map.of("reference",reference));}
        public boolean releaseImage(KnowledgeBaseDeleteProcessor.Payload p,String reference,List<String> ids,CallContext c){return true;}
        public void graph(KnowledgeBaseDeleteProcessor.Payload p,List<String> ids,CallContext c){recorder.event(c,"graph",Map.of("namespaces",ids.stream().map(id->Map.of("knowledge_base",p.knowledgeBaseId(),"knowledge",id)).toList()));}
        public void scrub(KnowledgeBaseDeleteProcessor.Payload p,List<String> ids,CallContext c){
          try{recorder.event(c,"pending",Map.of());c.check();f.jdbc.update("DELETE FROM task_pending_ops WHERE scope='knowledge_base' AND scope_id=?",p.knowledgeBaseId());}catch(RuntimeException ignored){}
          var details=new LinkedHashMap<String,Object>();details.put("ids",ids);details.put("data_source_ids",p.dataSourceIds());recorder.event(c,"queue",details);
        }
      };
      var processor=new KnowledgeBaseDeleteProcessor(repository,effects);int attempts=oracle.path("results").size();
      for(int attempt=0;attempt<attempts;attempt++) {
        try(var call=original.equals("parent-deadline")?CallContext.timeout(Duration.ofHours(1)):new CallContext()) {
          if(original.equals("context-cancel"))call.cancel();
          if(interrupt)Thread.currentThread().interrupt();
          String verdict="success",message="";
          try{processor.process(payload,call);}
          catch(Exception error){verdict=error instanceof KnowledgeBaseDeleteProcessor.Failure failure&&failure.verdict()==KnowledgeBaseDeleteProcessor.Verdict.SKIP_RETRY?"skip_retry":"retry";message=error instanceof CancellationException?"context canceled":joined(error);}
          if(interrupt){assertThat(Thread.currentThread().isInterrupted()).isTrue();Thread.interrupted();}
          results.add(json.createObjectNode().put("verdict",verdict).put("message",message));
        }
        variant[0]=original;
      }
      assertThat(canonical(results)).as("actual Go retry/SkipRetry and errors").isEqualTo(canonical(oracle.path("results")));
      assertThat(canonical(modelGroups(events))).as("Go side effect boundaries and detached cleanup; only Go map group ordering is unspecified").isEqualTo(canonical(modelGroups(oracle.path("events"))));
      var state=json.createObjectNode();
      for(String table:List.of("knowledges","chunks","knowledge_bases","wiki_pages","wiki_folders","wiki_page_issues"))state.set(table,json.valueToTree(f.jdbc.queryForList("SELECT id,tenant_id,"+(table.equals("knowledge_bases")?"":"knowledge_base_id,")+"CASE WHEN deleted_at IS NULL THEN 0 ELSE 1 END AS deleted FROM "+table+" ORDER BY id")));
      for(var entry:Map.of("tenants","SELECT id,storage_used FROM tenants ORDER BY id","wiki_page_revisions","SELECT id,tenant_id,knowledge_base_id FROM wiki_page_revisions ORDER BY id","task_pending_ops","SELECT tenant_id,task_type,scope,scope_id,op FROM task_pending_ops ORDER BY id").entrySet())state.set(entry.getKey(),json.valueToTree(f.jdbc.queryForList(entry.getValue())));
      assertThat(canonical(state)).as("all Go SQL side effects, tenant/KB isolation and partial failures").isEqualTo(canonical(oracle.path("state")));
    }finally{Thread.interrupted();release(f);}
  }
  private String joined(Throwable error){StringBuilder message=new StringBuilder(Objects.toString(error.getMessage(),""));for(var suppressed:error.getSuppressed())message.append('\n').append(joined(suppressed));return message.toString();}
  private JsonNode modelGroups(JsonNode events) {
    var output=json.createArrayNode();
    for(int i=0;i<events.size();) {
      if(!events.get(i).path("op").asText().equals("model")){output.add(events.get(i++));continue;}
      // Go ranges over map[groupKey][]knowledgeID. Preserve each model lookup and its
      // following deletion calls, and preserve this phase relative to every other stage.
      var groups=new ArrayList<ArrayNode>();
      while(i<events.size()&&events.get(i).path("op").asText().equals("model")) {
        var group=json.createArrayNode().add(events.get(i++));
        while(i<events.size()&&events.get(i).path("op").asText().equals("indexes"))group.add(events.get(i++));
        groups.add(group);
      }
      groups.sort(Comparator.comparing(g->g.get(0).path("id").asText()));groups.forEach(g->g.forEach(output::add));
    }
    return output;
  }
}
