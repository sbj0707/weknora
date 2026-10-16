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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Actual Go cloneKnowledge: durable SQL stages, independent file ownership and failure bookkeeping. */
class KnowledgeCloneDocumentGoParityTest {
  @TempDir Path temp;
  final ObjectMapper json=new ObjectMapper();
  final KnowledgeCloneChunksGoParityTest databases=new KnowledgeCloneChunksGoParityTest();

  @TestFactory Stream<DynamicTest> actualGoCloneDocument()throws Exception {
    JsonNode cases;
    String fixture=System.getProperty("weknora.clone.document.fixture","");
    if(!fixture.isEmpty())cases=json.readTree(Files.readAllBytes(Path.of(fixture)));
    else try(var in=getClass().getResourceAsStream("/go-knowledge-clone-document.json")){cases=json.readTree(Objects.requireNonNull(in));}
    assertThat(cases.size()).isEqualTo(75);
    var tests=new ArrayList<DynamicTest>();
    for(var c:cases)tests.add(DynamicTest.dynamicTest(c.path("name").asText(),()->run(c)));
    return tests.stream();
  }

  void run(JsonNode c)throws Exception {
    databases.temp=temp;var db=databases.database();
    for(var statement:c.path("seed_statements"))db.jdbc.execute(statement.asText());
    var ids=new HashMap<String,String>();var events=new ArrayList<Map<String,Object>>();
    String name=c.path("name").asText();var page=new AtomicInteger();var reads=new AtomicInteger();
    var destination=new String[]{""};
    var sourceIDs=db.jdbc.queryForList("SELECT id FROM chunks WHERE tenant_id=7 AND knowledge_id='src' AND deleted_at IS NULL ORDER BY id",String.class);
    var repository=new KnowledgeCloneRepository(db.jdbc,db.manager,Clock.systemUTC()) {
      @Override void createDocument(Map<String,Object> row) {
        String id=text(row,"id");assertThat(UUID.fromString(id).version()).isEqualTo(4);
        assertThat(ids.put(id,"dst")).isNull();destination[0]=id;
        events.add(Map.of("kind","create_document","id",id,"kb",text(row,"knowledge_base_id"),"parse_status",text(row,"parse_status"),"enable_status",text(row,"enable_status"),"embedding_model_id",text(row,"embedding_model_id"),"storage_size",number(row,"storage_size"),"file_path",text(row,"file_path")));
        if(Set.of("document-create-before","document-cleanup-failure","document-create-before-read-uncertain").contains(name))throw new IllegalStateException("controlled document create failure");
        super.createDocument(row);
        String mutation=switch(name) {
          case "created-wrong-kb"->"knowledge_base_id='kb'";
          case "created-wrong-tenant"->"tenant_id=8";
          case "created-completed"->"parse_status='completed'";
          case "created-missing"->"deleted_at='2026-02-01 00:00:00+00:00'";
          case "created-other-marker"->"metadata='{\"_knowledge_transfer\":{\"task_id\":\"another\",\"operation\":\"clone\",\"source_kb\":\"kb\",\"target_kb\":\"other\",\"source_id\":\"src\",\"phase\":\"cloning\"}}'";
          case "document-ack-other-marker"->"metadata='{\"_knowledge_transfer\":{\"task_id\":\"another\",\"phase\":\"cloning\"}}'";
          case "document-ack-null-marker"->"metadata='{\"_knowledge_transfer\":null}'";
          case "document-ack-invalid-marker"->"metadata='{\"_knowledge_transfer\":7}'";
          case "document-ack-missing-marker"->"metadata='{}'";
          case "document-ack-completed"->"parse_status='completed'";
          default->"";
        };
        if(!mutation.isEmpty())db.jdbc.update("UPDATE knowledges SET "+mutation+" WHERE id=?",id);
        if(Set.of("document-create-ack","document-create-uncertain","failed-bookkeeping-sql-failure").contains(name)||name.startsWith("document-ack-"))throw new IllegalStateException("controlled document create failure");
      }
      @Override Map<String,Object> document(long tenant,String id) {
        int read=reads.incrementAndGet();
        if(Set.of("document-create-uncertain","document-create-before-read-uncertain","reload-always-failure").contains(name)||name.equals("reload-once-failure")&&read==1)throw new IllegalStateException("controlled document read failure");
        return super.document(tenant,id);
      }
      @Override void createChunks(List<Map<String,Object>> rows) {
        int start=page.getAndIncrement()*100;
        for(int i=0;i<rows.size();i++) {
          String id=text(rows.get(i),"id");assertThat(UUID.fromString(id).version()).isEqualTo(4);
          assertThat(ids.put(id,"copy:"+sourceIDs.get(start+i))).isNull();
        }
        events.add(Map.of("kind","create_chunks","count",rows.size()));
        if(name.equals("create-before"))throw new IllegalStateException("controlled chunk create failure");
        super.createChunks(rows);
        if(name.equals("create-ack-failure"))throw new IllegalStateException("controlled chunk create failure");
      }
      @Override void deleteChunks(long tenant,String id) {
        events.add(Map.of("kind","delete_chunks","tenant",tenant,"id",id));
        if(name.equals("chunk-cleanup-failure"))throw new IllegalStateException("controlled chunk cleanup failure");
        super.deleteChunks(tenant,id);
      }
    };
    var transfers=new KnowledgeTransferRepository(db.jdbc,json,db.manager,Clock.systemUTC());
    Map<String,Object> src=transfers.get(7,"src");
    var overrides=c.path("source_override").fields();
    while(overrides.hasNext()){var e=overrides.next();src.put(e.getKey(),json.convertValue(e.getValue(),Object.class));}
    if(c.path("source_nil").asBoolean())src=null;
    var store=new KnowledgeCloneChunksGoParityTest.FilesControl(name,events) {
      @Override public void delete(String path)throws IOException {
        if(name.equals("document-cleanup-failure")){events.add(Map.of("kind","delete_image","path",path));throw new IOException("controlled image cleanup failure");}
        super.delete(path);
      }
    };
    var effects=new KnowledgeCloneDocument.Effects() {
      public void requireTransfer(Scope scope,JsonNode source,JsonNode target){requirePair(scope,source,target);}
      public JsonNode loadSource(String id) {
        if(name.equals("source-kb-reload-failure"))throw new IllegalStateException("controlled source KB failure");
        return c.path("source");
      }
      public FileStore sourceStore(JsonNode source,String path){return store;}
      public FileStore destinationStore(JsonNode target){return store;}
      public KnowledgeCloneChunks.Indices indices(JsonNode source,Map<String,Object> dst) {
        events.add(Map.of("kind","engine"));
        if(name.equals("engine-failure"))throw new IllegalStateException("controlled engine failure");
        events.add(Map.of("kind","model","id","target-model"));
        if(name.equals("model-failure"))throw new IllegalStateException("controlled model failure");
        return new KnowledgeCloneChunks.Indices() {
          public void copy(Map<String,String> docs,Map<String,String> chunks) {
            events.add(Map.of("kind","copy_indices","source","kb","target","other","documents",docs,"chunks",chunks,"dimensions",1,"kb_type",text(dst,"type")));
            if(Set.of("index-failure","index-cleanup-failure","chunk-cleanup-failure","image-cleanup-failure").contains(name))throw new IllegalStateException("controlled index failure");
            String mutation=switch(name) {
              case "final-completed"->"parse_status='completed'";
              case "final-timestamp"->"updated_at='2026-03-01 00:00:00+00:00'";
              case "final-other-marker"->"metadata='{\"_knowledge_transfer\":{\"task_id\":\"another\",\"phase\":\"cloning\"}}',updated_at='2026-03-01 00:00:00+00:00'";
              case "final-wrong-kb"->"knowledge_base_id='kb'";
              case "final-deleted"->"deleted_at='2026-03-01 00:00:00+00:00'";
              default->"";
            };
            if(!mutation.isEmpty())db.jdbc.update("UPDATE knowledges SET "+mutation+" WHERE id=?",destination[0]);
          }
          public void delete() {
            events.add(Map.of("kind","delete_indices","ids",List.of(destination[0]),"dimensions",1,"kb_type",text(dst,"type")));
            if(name.equals("index-cleanup-failure"))throw new IllegalStateException("controlled index cleanup failure");
          }
        };
      }
    };
    var chunks=new KnowledgeCloneChunks(transfers,repository,json,effects,Clock.systemUTC());
    String error="";
    try{new KnowledgeCloneDocument(repository,transfers,chunks,json,effects).copy(new Scope(7,"clone-task","kb","other"),c.path("source"),c.path("target"),src);}
    catch(Exception failure){error=Objects.toString(failure.getMessage(),"");}
    for(var tag:db.jdbc.queryForList("SELECT id,name FROM knowledge_tags")) {
      String id=text(tag,"id");if(!id.equals("tag")){assertThat(UUID.fromString(id).version()).isEqualTo(4);ids.put(id,"tag:"+text(tag,"name"));}
    }
    var snapshot=new LinkedHashMap<String,Object>();snapshot.put("error",error);snapshot.put("events",events);
    var query=c.path("queries").fields();while(query.hasNext()){var e=query.next();snapshot.put(e.getKey(),db.jdbc.queryForList(e.getValue().asText()));}
    JsonNode actual=normalize(json.readTree(json.writeValueAsBytes(snapshot)),ids,"");
    for(String table:List.of("documents","chunks","tags")) {
      var rows=new ArrayList<JsonNode>();actual.path(table).forEach(rows::add);rows.sort(Comparator.comparing(r->r.path("id").asText()));
      ((ObjectNode)actual).set(table,json.valueToTree(rows));
    }
    assertThat(actual).isEqualTo(c.path("snapshot"));
  }

  private JsonNode normalize(JsonNode value,Map<String,String> ids,String key)throws Exception {
    if(value.isTextual()) {
      String s=value.asText();
      if(Set.of("metadata","image_info","source_locators","relation_chunks","indirect_relation_chunks").contains(key)&&!s.isEmpty())try{return normalize(json.readTree(s),ids,"");}catch(com.fasterxml.jackson.core.JsonProcessingException ignored){}
      return TextNode.valueOf(ids.getOrDefault(s,s));
    }
    if(value.isObject()) {
      var result=json.createObjectNode();var fields=value.fields();
      while(fields.hasNext()){var e=fields.next();result.set(ids.getOrDefault(e.getKey(),e.getKey()),normalize(e.getValue(),ids,e.getKey()));}
      return result;
    }
    if(value.isArray()){var result=json.createArrayNode();for(var child:value)result.add(normalize(child,ids,""));return result;}
    return value;
  }
}
