package com.tencent.weknora.knowledge;

import static com.tencent.weknora.knowledge.KnowledgeTransferRepository.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.storage.FileStore;
import java.io.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Actual Go executeKnowledgeClone, real synchronous deletion and copying, including a second attempt. */
class KnowledgeCloneExecutionGoParityTest {
  @TempDir Path temp;
  final ObjectMapper json=new ObjectMapper();
  final KnowledgeCloneChunksGoParityTest databases=new KnowledgeCloneChunksGoParityTest();

  @TestFactory Stream<DynamicTest> actualGoCloneExecution()throws Exception {
    JsonNode cases;String fixture=System.getProperty("weknora.clone.execution.fixture","");
    if(!fixture.isEmpty())cases=json.readTree(Files.readAllBytes(Path.of(fixture)));
    else try(var in=getClass().getResourceAsStream("/go-knowledge-clone-execution.json")){cases=json.readTree(Objects.requireNonNull(in));}
    assertThat(cases.size()).isEqualTo(46);
    var tests=new ArrayList<DynamicTest>();
    for(var c:cases)tests.add(DynamicTest.dynamicTest(c.path("name").asText(),()->run(c)));
    return tests.stream();
  }

  void run(JsonNode c)throws Exception {
    databases.temp=temp;var db=databases.database();
    for(var statement:c.path("seed_statements"))db.jdbc.execute(statement.asText());
    String name=c.path("name").asText();var attempt=new int[]{0};
    var ids=new HashMap<String,String>();var copies=new HashMap<String,Integer>();
    List<Map<String,Object>> events=Collections.synchronizedList(new ArrayList<>());
    var repository=new KnowledgeCloneRepository(db.jdbc,db.manager,Clock.systemUTC()) {
      @Override void createDocument(Map<String,Object> row) {
        String id=text(row,"id"),source=text(row,"source");assertThat(UUID.fromString(id).version()).isEqualTo(4);
        assertThat(ids.put(id,"copy:"+source+":"+copies.merge(source,1,Integer::sum))).isNull();
        events.add(Map.of("kind","create_document","id",id,"source",source));
        if(attempt[0]==1&&name.equals("copy-second-create-failure")&&source.equals("src-b"))throw new IllegalStateException("controlled second document failure");
        super.createDocument(row);
      }
      @Override void createChunks(List<Map<String,Object>> rows) {
        for(var row:rows) {
          String id=text(row,"id");assertThat(UUID.fromString(id).version()).isEqualTo(4);
          assertThat(ids.put(id,"chunk:"+ids.get(text(row,"knowledge_id")))).isNull();
        }
        events.add(Map.of("kind","create_chunks","id",text(rows.getFirst(),"knowledge_id"),"count",rows.size()));
        super.createChunks(rows);
      }
      @Override void deleteChunks(long owner,String id) {
        events.add(Map.of("kind","delete_chunks","tenant",owner,"id",id));super.deleteChunks(owner,id);
      }
    };
    var transfers=new KnowledgeTransferRepository(db.jdbc,json,db.manager,Clock.systemUTC());
    var store=new KnowledgeCloneChunksGoParityTest.FilesControl(name,events) {
      @Override public void delete(String path)throws IOException {
        super.delete(path);
        if(attempt[0]==1&&name.equals("delete-file-failure"))throw new IOException("controlled deletion file failure");
      }
    };
    var effects=new KnowledgeCloneDocument.Effects() {
      public void requireTransfer(Scope scope,JsonNode source,JsonNode target){requirePair(scope,source,target);}
      public JsonNode loadSource(String id){return c.path("source");}
      public FileStore sourceStore(JsonNode source,String path){return store;}
      public FileStore destinationStore(JsonNode target){return store;}
      public KnowledgeCloneChunks.Indices indices(JsonNode source,Map<String,Object> dst) {
        events.add(Map.of("kind","engine"));events.add(Map.of("kind","model","id","target-model"));
        return new KnowledgeCloneChunks.Indices() {
          public void copy(Map<String,String> docs,Map<String,String> chunks) {
            events.add(Map.of("kind","copy_indices","source","kb","target","other","documents",docs,"chunks",chunks,"dimensions",1,"kb_type",text(dst,"type")));
            if(attempt[0]==1&&name.equals("copy-second-index-failure")&&docs.containsKey("src-b"))throw new IllegalStateException("controlled second index failure");
          }
          public void delete(){events.add(Map.of("kind","delete_indices","ids",List.of(text(dst,"id")),"dimensions",1,"kb_type",text(dst,"type")));}
        };
      }
    };
    var cleanupEffects=new KnowledgeReferencedDelete.Effects() {
      public JsonNode prepare(long owner,String kb){return c.path("target");}
      public void indexes(long owner,JsonNode base,List<Map<String,Object>> docs) {
        record Key(String model,String type){}
        var groups=new LinkedHashMap<Key,List<String>>();
        for(var row:docs)groups.computeIfAbsent(new Key(text(row,"embedding_model_id"),text(row,"type")),unused->new ArrayList<>()).add(text(row,"id"));
        for(var e:groups.entrySet()) {
          if(KnowledgeUploadForms.goTrim(e.getKey().model()).isEmpty())continue;
          events.add(Map.of("kind","engine"));events.add(Map.of("kind","model","id",e.getKey().model()));
          events.add(Map.of("kind","delete_indices","ids",e.getValue(),"dimensions",1,"kb_type",e.getKey().type()));
          if(attempt[0]==1&&name.equals("delete-index-failure"))throw new IllegalStateException("controlled deletion index failure");
        }
      }
      public void wiki(long owner,JsonNode base,Map<String,Object> doc){throw new AssertionError("No Wiki is enabled in this fixture");}
      public void graph(long owner,String kb,List<String> docs) {
        events.add(Map.of("kind","delete_graph","ids",docs));
        if(attempt[0]==1&&name.equals("delete-graph-failure"))throw new IllegalStateException("controlled deletion graph failure");
      }
      public void file(long owner,JsonNode base,String path)throws Exception{store.delete(path);}
      public boolean release(long owner,String path,List<String> docs){return true;}
      public void dequeue(long owner,String id){}
      public void profile(long owner,JsonNode base){}
      public void audit(long owner,JsonNode base,List<Map<String,Object>> docs){}
    };
    var chunks=new KnowledgeCloneChunks(transfers,repository,json,effects,Clock.systemUTC());
    var doc=new KnowledgeCloneDocument(repository,transfers,chunks,json,effects);
    var execution=new KnowledgeCloneExecution(new KnowledgeClonePlanner(db.jdbc,transfers,json),doc,
        new KnowledgeReferencedDelete(db.jdbc,transfers,json,cleanupEffects,db.manager,Clock.systemUTC()));
    var scope=new Scope(7,"clone-task","kb","other");
    for(int n=1;n<=2;n++) {
      attempt[0]=n;events.clear();
      KnowledgeCloneExecution.Progress progress=(done,total)->{
        events.add(Map.of("kind","progress","done",done,"total",total));
        if(attempt[0]==1&&done==0)for(var statement:c.path("progress_mutations"))db.jdbc.execute(statement.asText());
      };
      if(name.equals("no-progress"))progress=null;
      String error="";
      try{execution.execute(scope,c.path("source"),c.path("target"),progress);}
      catch(Exception failure){error=Objects.toString(failure.getMessage(),"");}
      for(var tag:db.jdbc.queryForList("SELECT id,name FROM knowledge_tags")) {
        String id=text(tag,"id");if(!Set.of("tag-source","tag-target").contains(id))ids.put(id,"tag:"+text(tag,"name"));
      }
      var snapshot=new LinkedHashMap<String,Object>();snapshot.put("error",error);snapshot.put("events",events);
      var queries=c.path("queries").fields();while(queries.hasNext()){var e=queries.next();snapshot.put(e.getKey(),db.jdbc.queryForList(e.getValue().asText()));}
      JsonNode actual=normalize(json.readTree(json.writeValueAsBytes(snapshot)),ids,"");
      for(String table:List.of("documents","chunks","tags")) {
        var rows=new ArrayList<JsonNode>();actual.path(table).forEach(rows::add);rows.sort(Comparator.comparing(r->r.path("id").asText()));
        ((ObjectNode)actual).set(table,json.valueToTree(rows));
      }
      // The engine and graph branches run concurrently in Go and Java. Their relative order is
      // unspecified; keep every event and all progress/deletion/copy barriers intact.
      canonicalizeCleanupEvents(actual);var expected=c.path("snapshots").get(n-1).deepCopy();canonicalizeCleanupEvents(expected);
      assertThat(actual).as("attempt %d",n).isEqualTo(expected);
      if(n==1)for(var statement:c.path("repair_statements"))db.jdbc.execute(statement.asText());
    }
  }

  private void canonicalizeCleanupEvents(JsonNode snapshot) {
    var events=(ArrayNode)snapshot.path("events");
    for(int graph=0;graph<events.size();graph++) {
      if(!events.get(graph).path("kind").asText().equals("delete_graph"))continue;
      int first=graph,last=graph;
      while(first>0&&cleanupEngineEvent(events.get(first-1),events.get(graph)))first--;
      while(last+1<events.size()&&cleanupEngineEvent(events.get(last+1),events.get(graph)))last++;
      var segment=new ArrayList<JsonNode>();
      for(int i=first;i<=last;i++)if(i!=graph)segment.add(events.get(i));
      segment.add(events.get(graph));
      // Only move the independent graph event. Preserve the exact serial index calls, including
      // retry targets whose IDs were generated during the preceding failed attempt.
      for(int i=first;i<=last;i++)events.set(i,segment.get(i-first));
      graph=last;
    }
  }
  private boolean cleanupEngineEvent(JsonNode event,JsonNode graph) {
    String kind=event.path("kind").asText();
    if(kind.equals("engine")||kind.equals("model"))return true;
    if(!kind.equals("delete_indices"))return false;
    var ids=new HashSet<JsonNode>();graph.path("ids").forEach(ids::add);
    for(var id:event.path("ids"))if(!ids.contains(id))return false;
    return true;
  }
  private JsonNode normalize(JsonNode value,Map<String,String> ids,String key)throws Exception {
    if(value.isTextual()) {
      String s=value.asText();
      if(Set.of("metadata","image_info","source_locators","relation_chunks","indirect_relation_chunks").contains(key)&&!s.isEmpty())try{return normalize(json.readTree(s),ids,"");}catch(com.fasterxml.jackson.core.JsonProcessingException ignored){}
      return TextNode.valueOf(ids.getOrDefault(s,s));
    }
    if(value.isObject()) {var out=json.createObjectNode();var it=value.fields();while(it.hasNext()){var e=it.next();out.set(ids.getOrDefault(e.getKey(),e.getKey()),normalize(e.getValue(),ids,e.getKey()));}return out;}
    if(value.isArray()){var out=json.createArrayNode();for(var child:value)out.add(normalize(child,ids,""));return out;}
    return value;
  }
}
