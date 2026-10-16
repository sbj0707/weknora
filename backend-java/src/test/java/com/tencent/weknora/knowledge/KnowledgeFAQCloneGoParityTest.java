package com.tencent.weknora.knowledge;

import static com.tencent.weknora.knowledge.KnowledgeTransferRepository.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.storage.FileStore;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Actual Go FAQ diff, status sync, full index inputs, image ownership and independently committed retry state. */
class KnowledgeFAQCloneGoParityTest {
  @TempDir Path temp;
  final ObjectMapper json=new ObjectMapper();
  final KnowledgeCloneChunksGoParityTest databases=new KnowledgeCloneChunksGoParityTest();
  @TestFactory Stream<DynamicTest> actualGoFAQClone()throws Exception {
    JsonNode cases;String file=System.getProperty("weknora.faq.clone.fixture","");
    if(!file.isEmpty())cases=json.readTree(Files.readAllBytes(Path.of(file)));
    else try(var in=getClass().getResourceAsStream("/go-knowledge-faq-clone.json")){cases=json.readTree(Objects.requireNonNull(in));}
    assertThat(cases.size()).isEqualTo(74);
    var tests=new ArrayList<DynamicTest>();for(var c:cases)tests.add(DynamicTest.dynamicTest(c.path("name").asText(),()->run(c)));return tests.stream();
  }
  void run(JsonNode c)throws Exception {
    databases.temp=temp;var db=databases.database();for(var statement:c.path("seed_statements"))db.jdbc.execute(statement.asText());
    String name=c.path("name").asText();var attempt=new int[]{0};var created=new int[]{0};var documents=new int[]{0};var engines=new int[]{0};
    var ids=new HashMap<String,String>();var events=new ArrayList<Map<String,Object>>();
    java.util.function.Predicate<String> fails=kind->attempt[0]==1&&name.equals(kind);
    var repository=new KnowledgeFAQCloneRepository(db.jdbc,db.manager,Clock.systemUTC(),json) {
      @Override List<Map<String,Object>> documents(long owner,String kb) {
        if(kb.equals("kb")&&fails.test("source-reload-failure"))throw new IllegalStateException("controlled source reload failure");
        return super.documents(owner,kb);
      }
      @Override Diff diff(long owner,String source,String target) {
        if(fails.test("diff-failure"))throw new IllegalStateException("controlled diff failure");
        var diff=super.diff(owner,source,target);
        if(fails.test("escaped-add"))diff.add().add("protected-chunk");
        if(fails.test("escaped-delete"))diff.remove().add("protected-chunk");
        if(fails.test("escaped-match"))diff.matched().add(new Pair("protected-chunk","dst-b"));
        return diff;
      }
      @Override Map<String,Map<String,Object>> statuses(long owner,List<String> ids) {
        if(!ids.isEmpty()&&fails.test("status-read-failure"))throw new IllegalStateException("controlled status read failure");
        return super.statuses(owner,ids);
      }
      @Override void createDocument(Map<String,Object> row) {
        String id=text(row,"id");assertThat(UUID.fromString(id).version()).isEqualTo(4);ids.put(id,"new-document:"+(++documents[0]));
        events.add(Map.of("kind","create_document","id",id));if(fails.test("container-create-failure"))throw new IllegalStateException("controlled container create failure");super.createDocument(row);
      }
      @Override void createChunks(List<Map<String,Object>> rows) {
        var newIds=new ArrayList<String>();for(var row:rows){String id=text(row,"id");assertThat(UUID.fromString(id).version()).isEqualTo(4);ids.put(id,"new-chunk:"+(++created[0]));newIds.add(id);}
        events.add(Map.of("kind","create_chunks","ids",newIds));
        if(fails.test("chunk-create-failure")||fails.test("late-chunk-create-failure")&&created[0]>50)throw new IllegalStateException("controlled chunk create failure");
        super.createChunks(rows);if(fails.test("chunk-create-ack-failure"))throw new IllegalStateException("controlled chunk create failure");
      }
      @Override void remove(long owner,List<String> ids) {events.add(Map.of("kind","delete_chunks","ids",ids,"tenant",owner));super.remove(owner,ids);}
      @Override void updateRows(List<Map<String,Object>> rows) {
        var values=new ArrayList<Map<String,Object>>();for(var row:rows)values.add(Map.of("id",text(row,"id"),"content",text(row,"content"),"enabled",KnowledgeFAQMetadata.enabled(row),"flags",number(row,"flags"),"status",number(row,"status"),"tag_id",text(row,"tag_id")));
        events.add(Map.of("kind","update_rows","rows",values));super.updateRows(rows);
      }
      @Override void patchMetadata(long owner,String id,String value){events.add(Map.of("kind","patch_metadata","id",id,"metadata",value));super.patchMetadata(owner,id,value);}
      @Override void indexedDocument(Map<String,Object> row){events.add(Map.of("kind","update_document","id",text(row,"id")));if(fails.test("document-update-failure"))throw new IllegalStateException("controlled document update failure");super.indexedDocument(row);}
    };
    var store=new KnowledgeCloneChunksGoParityTest.FilesControl(name,events);
    var effects=new KnowledgeFAQClone.Effects() {
      public FileStore destinationStore(JsonNode target){return store;}
      public KnowledgeFAQClone.Model model(String id) {
        events.add(Map.of("kind","model","id",id));if(fails.test("model-failure"))throw new IllegalStateException("controlled model failure");return ()->1;
      }
      public KnowledgeFAQClone.Engine engine(JsonNode base) {
        engines[0]++;events.add(Map.of("kind","engine"));
        if(fails.test("engine-failure")||fails.test("index-engine-failure")&&engines[0]==2)throw new IllegalStateException("controlled engine failure");
        return new KnowledgeFAQClone.Engine() {
          public void delete(List<String> ids,int dimension){events.add(Map.of("kind","delete_indices","ids",ids,"dimensions",dimension,"kb_type","faq"));if(fails.test("delete-index-failure"))throw new IllegalStateException("controlled index delete failure");}
          public void index(KnowledgeFAQClone.Model model,List<KnowledgeFAQMetadata.Entry> entries) {
            for(var entry:entries)if(ids.containsKey(entry.chunkId()))ids.put(entry.sourceId(),ids.get(entry.chunkId())+entry.sourceId().substring(entry.chunkId().length()));
            events.add(Map.of("kind","index","entries",entries));if(fails.test("index-failure")||fails.test("late-index-failure")&&created[0]>50)throw new IllegalStateException("controlled index failure");
          }
          public void enabled(Map<String,Boolean> values){events.add(Map.of("kind","enabled","values",values));if(fails.test("enabled-failure"))throw new IllegalStateException("controlled enabled failure");}
          public void tags(Map<String,String> values){events.add(Map.of("kind","tags","values",values));if(fails.test("tags-failure"))throw new IllegalStateException("controlled tags failure");}
        };
      }
      public void progress(ObjectNode value){value.put("created_at",0);value.put("updated_at",0);events.add(Map.of("kind","progress","value",value));if(fails.test("progress-failure"))throw new IllegalStateException("controlled progress failure");}
      public void error(ObjectNode value,Exception error,String message){events.add(Map.of("kind","error","error",Objects.toString(error.getMessage(),""),"message",message));}
    };
    var transfers=new KnowledgeTransferRepository(db.jdbc,json,db.manager,Clock.systemUTC());
    var execution=new KnowledgeFAQClone(new KnowledgeClonePlanner(db.jdbc,transfers,json),repository,json,effects,Clock.systemUTC());
    for(int n=1;n<=2;n++) {
      attempt[0]=n;engines[0]=0;events.clear();
      var progress=json.createObjectNode().put("task_id","clone-task").put("source_id","kb").put("target_id","other").put("status","processing").put("progress",0).put("total",0).put("processed",0).put("message","").put("error","").put("created_at",0).put("updated_at",0);
      String error="";try{execution.execute(new Scope(7,"clone-task","kb","other"),c.path("source"),c.path("target"),progress);}catch(Exception e){error=Objects.toString(e.getMessage(),"");}
      progress.put("updated_at",0);
      for(var row:db.jdbc.queryForList("SELECT id,name FROM knowledge_tags")){String id=text(row,"id");if(!Set.of("tag-source","tag-target").contains(id))ids.put(id,"tag:"+text(row,"name"));}
      var snapshot=new LinkedHashMap<String,Object>();snapshot.put("error",error);snapshot.put("events",events);snapshot.put("progress",progress);
      var queries=c.path("queries").fields();while(queries.hasNext()){var e=queries.next();snapshot.put(e.getKey(),db.jdbc.queryForList(e.getValue().asText()));}
      JsonNode actual=normalize(json.readTree(json.writeValueAsBytes(snapshot)),ids,"");
      // Go CompositeRetrieveEngine deduplicates through a map. Only entries inside
      // one index call are unordered; preserve calls, duplicates, and every field.
      for(var event:actual.path("events"))if(event.path("kind").asText().equals("index")) {
        var entries=new ArrayList<JsonNode>();event.path("entries").forEach(entries::add);
        entries.sort(Comparator.comparing(e->e.path("sourceId").asText()));
        ((ObjectNode)event).set("entries",json.valueToTree(entries));
      }
      for(String table:List.of("documents","chunks","tags")) {var rows=new ArrayList<JsonNode>();actual.path(table).forEach(rows::add);rows.sort(Comparator.comparing(r->r.path("id").asText()));((ObjectNode)actual).set(table,json.valueToTree(rows));}
      assertThat(actual).as("attempt %d",n).isEqualTo(c.path("snapshots").get(n-1));
      if(n==1)for(var statement:c.path("repair_statements"))db.jdbc.execute(statement.asText());
    }
  }
  private JsonNode normalize(JsonNode value,Map<String,String> ids,String key)throws Exception {
    if(value.isTextual()) {
      String s=value.asText();if(Set.of("metadata","image_info","source_locators","relation_chunks","indirect_relation_chunks").contains(key)&&!s.isEmpty())try{return normalize(json.readTree(s),ids,"");}catch(com.fasterxml.jackson.core.JsonProcessingException ignored){}
      return TextNode.valueOf(ids.getOrDefault(s,s));
    }
    if(value.isObject()){var out=json.createObjectNode();var it=value.fields();while(it.hasNext()){var e=it.next();out.set(ids.getOrDefault(e.getKey(),e.getKey()),normalize(e.getValue(),ids,e.getKey()));}return out;}
    if(value.isArray()){var out=json.createArrayNode();for(var child:value)out.add(normalize(child,ids,""));return out;}return value;
  }
}
