package com.tencent.weknora.knowledge;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import com.tencent.weknora.model.*;
import com.tencent.weknora.model.client.*;
import com.tencent.weknora.security.*;
import com.tencent.weknora.task.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

/** Captured real Go worker and HTTP provider calls; persisted checkpoints are compared. */
class SummaryGenerationGoParityTest {
  @TempDir Path temp;final ObjectMapper json=new ObjectMapper();
  @TestFactory Stream<DynamicTest> productionGoSummaryWorkerCheckpoints()throws Exception {
    JsonNode cases;try(var in=getClass().getResourceAsStream("/go-summary-generation.json")){cases=json.readTree(in);}assertThat(cases.size()).isEqualTo(44);var sequence=new AtomicInteger();
    return StreamSupport.stream(cases.spliterator(),false).map(row->DynamicTest.dynamicTest(row.path("name").asText(),()->compare(row,sequence.incrementAndGet())));
  }
  void compare(JsonNode oracle,int caseNo)throws Exception {
    String name=oracle.path("name").asText();var f=new DocumentProcessingCompatibilityTest();f.temp=temp;f.database(caseNo,true);var k=f.seed(json.createObjectNode().put("state","finalizing"));
    f.jdbc.update("UPDATE knowledges SET description='old summary',profile=?,summary_status='pending',pending_subtasks_count=1,error_message='old error',storage_size=11 WHERE id='knowledge'","{\"gist\":\"Old profile\"}");
    if(Set.of("cancelled","deleting").contains(name))f.jdbc.update("UPDATE knowledges SET parse_status=? WHERE id='knowledge'",name);
    if(name.equals("custom-metadata"))f.jdbc.update("UPDATE knowledges SET custom_metadata=? WHERE id='knowledge'","{\"z\":null,\"a\":\"  approved  \",\"bool\":true,\"list\":[\"x\",2,null],\"object\":{\"b\":2,\"a\":\"x\"}}");
    if(Set.of("graph-only","wiki-only").contains(name))f.jdbc.update("UPDATE knowledge_bases SET indexing_strategy=? WHERE id='kb'",name.equals("graph-only")?"{\"graph_enabled\":true}":"{\"wiki_enabled\":true}");
    if(name.equals("no-model"))f.jdbc.update("UPDATE knowledge_bases SET summary_model_id='' WHERE id='kb'");
    if(name.startsWith("model-missing"))f.jdbc.update("UPDATE knowledge_bases SET summary_model_id='missing' WHERE id='kb'");
    if(!name.equals("no-text"))chunk(f,"text-1",oracle.path("body").asText(),0,0,oracle.path("body").asText().codePointCount(0,oracle.path("body").asText().length()),"text","",!Set.of("disabled-initial","refresh-disabled").contains(name),name.equals("edited")?1:0);
    if(name.equals("edited"))chunk(f,"text-2","Edited replacement 😀 delivered last.",1,0,20,"text","",true,0);
    if(name.equals("overlap")){f.jdbc.update("UPDATE chunks SET content='Alpha beta gamma delta epsilon ',end_at=31 WHERE id='text-1'");chunk(f,"text-2","gamma delta epsilon zeta eta final.",1,11,50,"text","",true,0);}
    if(name.equals("image-ocr")){chunk(f,"image-1","Recognized onboarding process details.",1,0,0,"image_ocr","text-1",true,0);f.jdbc.update("UPDATE chunks SET image_info=? WHERE id='image-1'","[{\"url\":\"page.png\",\"caption\":\"Page caption\",\"ocr_text\":\"Recognized onboarding process details.\"}]");}
    if(Set.of("refresh","refresh-duplicates").contains(name))chunk(f,"summary-existing","old summary chunk",2,0,0,"summary","text-1",false,0);
    if(name.equals("refresh-duplicates"))chunk(f,"summary-duplicate","old duplicate chunk",3,0,0,"summary","text-1",true,0);
    if(name.equals("plain"))f.jdbc.execute("INSERT INTO chunks(id,tenant_id,knowledge_base_id,knowledge_id,content,chunk_index,start_at,end_at,chunk_type,seq_id,deleted_at) VALUES('deleted-sequence',2,'kb','knowledge','deleted history',0,0,0,'text',10000,'2026-01-01')");
    var requests=Collections.synchronizedList(new ArrayList<JsonNode>());var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/v1/",exchange->{try{
      ObjectNode body=(ObjectNode)json.readTree(exchange.getRequestBody());String path=exchange.getRequestURI().getPath();requests.add(json.createObjectNode().put("path",path).set("body",body));
      ObjectNode response;int status=200;
      if(path.equals("/v1/chat/completions")) {
        if(name.startsWith("chat-error")||name.equals("refresh-error-final")){status=503;response=json.createObjectNode().set("error",json.createObjectNode().put("message","synthetic provider unavailable"));}
        else {if(Set.of("revision-changed","refresh-stale").contains(name))f.jdbc.update("UPDATE chunks SET content_revision=1 WHERE id='text-1'");if(name.equals("metadata-changed"))f.jdbc.update("UPDATE knowledges SET custom_metadata='{\"new\":\"metadata\"}' WHERE id='knowledge'");if(name.equals("cancel-during-chat"))f.jdbc.update("UPDATE knowledges SET parse_status='cancelled' WHERE id='knowledge'");
          response=json.createObjectNode().set("choices",json.createArrayNode().add(json.createObjectNode().put("finish_reason","stop").set("message",json.createObjectNode().put("role","assistant").put("content",oracle.path("reply").asText()))));}
      }else if(name.startsWith("embedding-error")){status=400;response=json.createObjectNode().set("error",json.createObjectNode().put("message","synthetic embedding unavailable"));}
      else {var values=json.createArrayNode();for(int i=0;i<body.path("input").size();i++)values.add(json.createObjectNode().put("index",i).set("embedding",json.createArrayNode().add(1).add(0).add(0)));response=json.createObjectNode().set("data",values);}
      byte[] bytes=json.writeValueAsBytes(response);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}
    }finally{exchange.close();}});server.start();
    var env=new MockEnvironment().withProperty("SSRF_WHITELIST","127.0.0.1").withProperty("SSRF_DNS_WHITELIST_ONLY","true");var models=new ModelClientFactory(f.jdbc,json,new GoSecretCodec(""),new ModelCatalogRuntime(f.jdbc,"","/nonexistent"),new ModelEgressPolicy(f.jdbc,json,env),env);
    try {
      f.jdbc.update("UPDATE knowledges SET title='SECRET_FILENAME_EXCLUDED' WHERE id='knowledge'");
      ObjectNode p=json.createObjectNode().put("provider","generic").put("base_url","http://127.0.0.1:"+server.getAddress().getPort()+"/v1").put("api_key","synthetic-key");p.set("embedding_parameters",json.createObjectNode().put("dimension",3));for(String id:List.of("summary","embedding"))f.jdbc.update("INSERT INTO models(id,tenant_id,name,type,source,parameters,is_builtin,status) VALUES(?,1,'unknown',?,'remote',?,false,'active')",id,id.equals("summary")?"KnowledgeQA":"Embedding",json.writeValueAsBytes(p));
      if(name.equals("chunk-error"))f.jdbc.execute("CREATE TRIGGER fail_chunk BEFORE INSERT ON chunks BEGIN SELECT RAISE(FAIL,'synthetic chunk failure');END");if(name.equals("index-error"))f.jdbc.execute("CREATE TRIGGER fail_index BEFORE INSERT ON lite_embeddings BEGIN SELECT RAISE(FAIL,'synthetic index failure');END");
      ObjectNode payload=(ObjectNode)oracle.path("payload").deepCopy();var job=f.job(k,"summary:generation");f.jdbc.update("UPDATE java_task_jobs SET payload=?,attempts=? WHERE id=?",payload.toString(),oracle.path("retry").asInt()+1,job.id());job=f.tasks.get(1,job.id()).orElseThrow();
      var repo=new SummaryGenerationRepository(f.jdbc,json,f.knowledge,f.manager,Clock.systemUTC());var handler=new SummaryGenerationHandler(repo,json,new SummaryGenerationHandler.Models(){AuthContext auth(long owner){return new AuthContext("system-task",owner,"viewer",false,null,false,Set.of(),Set.of());}public ChatModel chat(long owner,String id){return models.chat(auth(owner),id);}public EmbeddingModel embedding(long owner,String id){return models.embedding(auth(owner),id);}},(owner,kb)->f.index,null,new SummaryGenerationText(json,name.equals("default-prompt")?null:"PROFILE {{language}}",oracle.path("maximum").asInt(),1024),(owner,type,pp,opts)->"unused");
      Exception failure=null;try{handler.execute(job,f.cancellation(job));}catch(Exception e){failure=e;}
      assertThat(failure!=null).as("worker retry decision").isEqualTo(!oracle.path("error").asText().isEmpty());
      assertThat(numbers(json.valueToTree(requests))).as("actual HTTP requests").isEqualTo(numbers(oracle.path("requests")));
      assertThat(numbers(projection(f))).as("committed SQL state").isEqualTo(numbers(projectOracle(oracle)));
    }finally{models.close();server.stop(0);}
  }
  void chunk(DocumentProcessingCompatibilityTest f,String id,String body,int index,int start,int end,String type,String parent,boolean enabled,int revision){f.jdbc.update("INSERT INTO chunks(id,tenant_id,knowledge_base_id,knowledge_id,content,chunk_index,start_at,end_at,chunk_type,parent_chunk_id,is_enabled,content_revision,status,source_content,seq_id) VALUES(?,1,'kb','knowledge',?,?,?,?,?,?,?,?,0,'',(SELECT COALESCE(MAX(seq_id),0)+1 FROM chunks))",id,body,index,start,end,type,parent,enabled,revision);}
  ObjectNode projectOracle(JsonNode oracle){var out=json.createObjectNode();for(String key:List.of("knowledge","chunks","indexes"))out.set(key,oracle.get(key));return out;}
  ObjectNode projection(DocumentProcessingCompatibilityTest f)throws Exception {
    var out=json.createObjectNode();var state=f.jdbc.queryForMap("SELECT * FROM knowledges WHERE id='knowledge'");var k=out.putObject("knowledge");for(String key:List.of("description","summary_status","parse_status","pending_subtasks_count","error_message","storage_size"))k.set(key,json.valueToTree(state.get(key)));Object profile=state.get("profile");k.set("profile",profile==null?NullNode.instance:json.readTree(profile.toString()));
    var chunks=out.putArray("chunks");var ids=new HashMap<String,String>();for(var row:f.jdbc.queryForList("SELECT * FROM chunks WHERE chunk_type='summary' AND deleted_at IS NULL ORDER BY id")){String id=row.get("id").toString();ids.put(id,id.startsWith("summary-")?id:"new-summary");var c=chunks.addObject().put("id",ids.get(id));for(var map:Map.of("content","content","source_content","source_content","type","chunk_type","index","chunk_index","parent","parent_chunk_id","status","status","seq_id","seq_id","start","start_at","end","end_at").entrySet())c.set(map.getKey(),json.valueToTree(Objects.requireNonNullElse(row.get(map.getValue()),"")));c.put("enabled",!row.get("is_enabled").toString().equals("0"));}
    var ordered=new ArrayList<JsonNode>();chunks.forEach(ordered::add);ordered.sort(Comparator.comparing(c->c.path("id").asText()));out.set("chunks",json.valueToTree(ordered));var indexes=out.putArray("indexes");var rows=f.jdbc.queryForList("SELECT source_id,source_type,chunk_id,knowledge_id,knowledge_base_id,content,dimension,is_enabled FROM lite_embeddings ORDER BY source_id");rows.sort(Comparator.comparing(e->ids.get(e.get("source_id"))));for(var row:rows){var n=indexes.addObject();row.forEach((key,value)->n.set(key,json.valueToTree(value)));n.put("source_id",ids.get(row.get("source_id"))).put("chunk_id",ids.get(row.get("chunk_id")));}return out;
  }
  JsonNode numbers(JsonNode n){if(n.isNumber())return DecimalNode.valueOf(n.decimalValue().stripTrailingZeros());if(n.isArray()){var a=json.createArrayNode();n.forEach(v->a.add(numbers(v)));return a;}if(n.isObject()){var a=json.createObjectNode();n.fields().forEachRemaining(e->a.set(e.getKey(),numbers(e.getValue())));return a;}return n;}
}
