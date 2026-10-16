package com.tencent.weknora.knowledge;

import static com.tencent.weknora.knowledge.KnowledgeTransferRepository.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.storage.FileStore;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Actual Go CloneChunk, all persisted chunk fields, image bytes and failure-side effect order. */
class KnowledgeCloneChunksGoParityTest {
  @TempDir Path temp;
  final ObjectMapper json=new ObjectMapper();
  private final AtomicInteger databases=new AtomicInteger();

  @TestFactory Stream<DynamicTest> actualGoCloneChunks()throws Exception {
    String file=System.getProperty("weknora.clone.chunks.fixture","");
    JsonNode cases;
    if(!file.isEmpty())cases=json.readTree(Files.readAllBytes(Path.of(file)));
    else try(var in=getClass().getResourceAsStream("/go-knowledge-clone-chunks.json")){cases=json.readTree(Objects.requireNonNull(in));}
    assertThat(cases.size()).isEqualTo(68);
    var tests=new ArrayList<DynamicTest>();
    for(var c:cases)tests.add(DynamicTest.dynamicTest(c.path("name").asText(),()->run(c)));
    return tests.stream();
  }

  void run(JsonNode c)throws Exception {
    var db=database();for(var s:c.path("seed_statements"))db.jdbc.execute(s.asText());
    if(postgres())db.jdbc.execute("SELECT setval('chunks_seq_id_seq',GREATEST(COALESCE((SELECT MAX(seq_id) FROM chunks),0)+1,100000000),false)");
    var ids=new HashMap<String,String>();var events=new ArrayList<Map<String,Object>>();
    String name=c.path("name").asText();var page=new AtomicInteger();
    var sourceIDs=db.jdbc.queryForList("SELECT id FROM chunks WHERE tenant_id=7 AND knowledge_id='src' AND deleted_at IS NULL ORDER BY id",String.class);
    var repository=new KnowledgeCloneRepository(db.jdbc,db.manager,Clock.systemUTC()) {
      @Override void createChunks(List<Map<String,Object>> rows) {
        int start=page.getAndIncrement()*100;
        for(int i=0;i<rows.size();i++) {
          String id=text(rows.get(i),"id");assertThat(UUID.fromString(id).version()).isEqualTo(4);
          assertThat(ids.put(id,"copy:"+sourceIDs.get(start+i))).isNull();
        }
        events.add(Map.of("kind","create_chunks","count",rows.size()));
        if(name.equals("create-before")||name.equals("late-page-failure")&&page.get()==2)throw new IllegalStateException("controlled chunk create failure");
        super.createChunks(rows);
        if(Set.of("create-ack-failure","claim-conflict").contains(name)) {
          if(name.equals("claim-conflict"))db.jdbc.update("UPDATE knowledges SET parse_status='completed' WHERE id='dst'");
          throw new IllegalStateException("controlled chunk create failure");
        }
      }
      @Override void deleteChunks(long tenant,String id) {
        events.add(Map.of("kind","delete_chunks","tenant",tenant,"id",id));
        if(name.equals("chunk-cleanup-failure"))throw new IllegalStateException("controlled chunk cleanup failure");
        super.deleteChunks(tenant,id);
      }
    };
    var transfers=new KnowledgeTransferRepository(db.jdbc,json,db.manager,Clock.systemUTC());
    var src=transfers.get(7,"src");var dst=new LinkedHashMap<String,Object>();
    dst.put("id","dst");dst.put("tenant_id",7L);dst.put("knowledge_base_id","other");dst.put("type","manual");
    dst.put("embedding_model_id",name.equals("no-model")?"":"target-model");
    var store=new FilesControl(name,events);
    var effects=new KnowledgeCloneChunks.Effects() {
      public void requireTransfer(Scope scope,JsonNode source,JsonNode target){requirePair(scope,source,target);}
      public FileStore destinationStore(JsonNode target){return store;}
      public KnowledgeCloneChunks.Indices indices(JsonNode source,Map<String,Object> destination) {
        events.add(Map.of("kind","engine"));
        if(name.equals("engine-failure"))throw new IllegalStateException("controlled engine failure");
        events.add(Map.of("kind","model","id","target-model"));
        if(name.equals("model-failure"))throw new IllegalStateException("controlled model failure");
        return new KnowledgeCloneChunks.Indices() {
          public void copy(Map<String,String> docs,Map<String,String> chunks) {
            events.add(Map.of("kind","copy_indices","source","kb","target","other","documents",docs,"chunks",chunks,"dimensions",1,"kb_type","manual"));
            if(Set.of("index-failure","index-cleanup-failure","chunk-cleanup-failure","image-cleanup-failure").contains(name))throw new IllegalStateException("controlled index failure");
          }
          public void delete() {
            events.add(Map.of("kind","delete_indices","ids",List.of("dst"),"dimensions",1,"kb_type","manual"));
            if(name.equals("index-cleanup-failure"))throw new IllegalStateException("controlled index cleanup failure");
          }
        };
      }
    };
    String error="";
    try{new KnowledgeCloneChunks(transfers,repository,json,effects,Clock.systemUTC()).copy(new Scope(7,"clone-task","kb","other"),c.path("source"),c.path("target"),src,dst);}
    catch(Exception failure){error=Objects.toString(failure.getMessage(),"");}
    for(var tag:db.jdbc.queryForList("SELECT id,name FROM knowledge_tags")) {
      String id=text(tag,"id");if(!Set.of("tag","existing").contains(id)){assertThat(UUID.fromString(id).version()).isEqualTo(4);ids.put(id,"tag:"+text(tag,"name"));}
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

  boolean postgres(){return false;}
  DocumentProcessingCompatibilityTest database()throws Exception {
    var f=new DocumentProcessingCompatibilityTest();f.temp=temp;f.database(databases.incrementAndGet(),true);
    for(String table:List.of("knowledge_tag_relations","chunks","knowledges","knowledge_tags","tenants"))f.jdbc.execute("DELETE FROM "+table);
    return f;
  }
  static class FilesControl implements FileStore {
    final String name;final List<Map<String,Object>> events;int saved;
    FilesControl(String name,List<Map<String,Object>> events){this.name=name;this.events=events;}
    public InputStream open(String path)throws IOException {
      events.add(Map.of("kind","read","path",path));
      if(name.equals("image-read-failure")||name.equals("image-partial-failure")&&path.contains("second"))throw new IOException("controlled read failure");
      if(name.equals("image-buffer-failure"))return new InputStream(){public int read()throws IOException{throw new IOException("controlled buffer failure");}};
      byte[] data=switch(name) {
        case "sniff-png"->new byte[]{(byte)137,80,78,71,13,10,26,10};
        case "sniff-jpeg"->new byte[]{(byte)255,(byte)216,(byte)255};
        case "sniff-gif"->"GIF89a".getBytes(StandardCharsets.US_ASCII);
        case "sniff-webp"->"RIFFxxxxWEBPVP".getBytes(StandardCharsets.US_ASCII);
        case "sniff-bmp"->new byte[]{'B','M'};
        default->path.getBytes(StandardCharsets.UTF_8);
      };
      if(name.equals("image-close-failure"))return new ByteArrayInputStream(data){@Override public void close()throws IOException{throw new IOException("controlled close failure");}};
      return new ByteArrayInputStream(data);
    }
    public String saveBytes(byte[] data,long tenant,String filename,boolean temporary)throws IOException {
      int dot=filename.lastIndexOf('.');assertThat(UUID.fromString(filename.substring(0,dot)).version()).isEqualTo(4);
      String ext=filename.substring(dot),path="local://"+tenant+"/exports/copied-"+(++saved)+ext;
      events.add(Map.of("kind","save","data",data,"tenant",tenant,"temporary",temporary,"extension",ext,"path",path));
      if(name.equals("image-save-failure"))throw new IOException("controlled save failure");return path;
    }
    public void delete(String path)throws IOException{events.add(Map.of("kind","delete_image","path",path));if(name.equals("image-cleanup-failure"))throw new IOException("controlled image cleanup failure");}
    public void checkConnectivity(){throw new AssertionError();}
    public String saveFile(InputStream in,String filename,long tenant,String knowledge){throw new AssertionError();}
    public String url(String path){throw new AssertionError();}
    public String copy(String path,long tenant,String knowledge){throw new AssertionError("Clone must copy into exports using bytes");}
  }
}
