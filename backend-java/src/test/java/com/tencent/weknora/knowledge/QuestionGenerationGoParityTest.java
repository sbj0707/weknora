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
class QuestionGenerationGoParityTest {
  @TempDir Path temp;final ObjectMapper json=new ObjectMapper();
  @TestFactory Stream<DynamicTest> productionGoQuestionWorkerCheckpoints()throws Exception {
    JsonNode cases;try(var in=getClass().getResourceAsStream("/go-question-generation.json")){cases=json.readTree(in);}assertThat(cases.size()).isEqualTo(114);var sequence=new AtomicInteger();
    return StreamSupport.stream(cases.spliterator(),false).map(row->DynamicTest.dynamicTest(row.path("mode").asText()+":"+row.path("name").asText(),()->compare(row,sequence.incrementAndGet())));
  }
  static {try {com.tencent.weknora.retrieval.NativeDatabaseRuntime.initialize();}catch(java.sql.SQLException e){throw new ExceptionInInitializerError(e);}}
  void compare(JsonNode oracle,int caseNo)throws Exception {
    String name=oracle.path("name").asText(),mode=oracle.path("mode").asText(),id1=oracle.path("id1").asText();var f=new DocumentProcessingCompatibilityTest();f.temp=temp;f.database(caseNo,true);var k=f.seed(json.createObjectNode().put("state","finalizing"));
    try(var c=f.source.getConnection()){org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(c,new org.springframework.core.io.FileSystemResource("migration/sql/java-question-drains-upgrade.sql"));}
    f.jdbc.update("UPDATE knowledges SET title=?,description='summary remains visible',summary_status='completed',pending_subtasks_count=2,error_message='old error',storage_size=11 WHERE id='knowledge'","  Document title 中文  ");
    if(Set.of("cancelled","deleting").contains(name))f.jdbc.update("UPDATE knowledges SET parse_status=? WHERE id='knowledge'",name);
    if(name.startsWith("model-missing"))f.jdbc.update("UPDATE knowledge_bases SET summary_model_id='missing' WHERE id='kb'");if(name.startsWith("embedding-missing"))f.jdbc.update("UPDATE knowledge_bases SET embedding_model_id='missing' WHERE id='kb'");
    if(Set.of("custom","override").contains(name))f.jdbc.update("UPDATE knowledge_bases SET question_generation_config=? WHERE id='kb'","{\"enabled\":true,\"custom_instructions\":\"  KB business instruction 中文  \"}");
    if(name.equals("override"))f.jdbc.update("UPDATE knowledges SET metadata=? WHERE id='knowledge'","{\"process_overrides\":{\"question_generation_config\":{\"enabled\":true,\"custom_instructions\":\"  Override instruction 😀 \"}}}");
    if(!(name.equals("no-text")&&mode.equals("legacy"))){chunk(f,id1,oracle.path("body1").asText(),0,0,60,"text","",!name.equals("disabled"),0);chunk(f,"text-2",oracle.path("body2").asText(),1,60,120,"text","",true,0);}
    if(name.equals("boundary")){chunk(f,"before","Preceding boundary context.",2,-40,0,"text","",true,0);chunk(f,"after","Following boundary context.",3,120,150,"text","",true,0);}
    if(name.equals("image")){chunk(f,"image","OCR source",4,0,0,"image_ocr",id1,true,0);f.jdbc.update("UPDATE chunks SET image_info=? WHERE id='image'","[{\"url\":\"page.png\",\"caption\":\"Caption text\",\"ocr_text\":\"OCR policy text\"}]");}
    if(name.equals("versioned"))f.jdbc.update("UPDATE chunks SET content_revision=4 WHERE id=?",id1);if(name.equals("kb-missing"))f.jdbc.update("UPDATE knowledge_bases SET deleted_at='2026-01-01' WHERE id='kb'");
    if(name.equals("metadata-old"))f.jdbc.update("UPDATE chunks SET metadata=? WHERE id=?","{\"generated_questions\":[{\"id\":\"old\",\"question\":\"Old question\",\"content_revision\":0}],\"unrelated\":\"old\"}",id1);
    if(name.equals("missing-chunk"))f.jdbc.update("UPDATE chunks SET deleted_at='2026-01-01' WHERE id=?",id1);
    if(name.equals("foreign-tenant-chunk"))f.jdbc.update("UPDATE chunks SET tenant_id=2 WHERE id=?",id1);
    if(name.equals("foreign-document-chunk"))f.jdbc.update("UPDATE chunks SET knowledge_id='foreign-document' WHERE id=?",id1);
    if(name.equals("foreign-neighbor"))f.jdbc.update("UPDATE chunks SET knowledge_id='foreign-document' WHERE id='text-2'");
    var requests=Collections.synchronizedList(new ArrayList<JsonNode>());var chats=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/v1/",exchange->{try {
      ObjectNode body=(ObjectNode)json.readTree(exchange.getRequestBody());String path=exchange.getRequestURI().getPath();requests.add(json.createObjectNode().put("path",path).set("body",body));ObjectNode response;int status=200;
      if(path.endsWith("chat/completions")){int n=chats.incrementAndGet();if(name.equals("chat-error")||(name.equals("chat-error-second")&&n==2)){status=503;response=json.createObjectNode().set("error",json.createObjectNode().put("message","synthetic provider unavailable"));}else {if(name.equals("superseded-during-chat")&&n==1){new KnowledgeProcessingSpanTracker(new KnowledgeProcessingSpanWriteRepository(f.jdbc,json,new KnowledgeProcessingSpanRepository(f.jdbc,json)),json).open(1,"knowledge","successor");}if(name.equals("revision-changed")&&n==1)f.jdbc.update("UPDATE chunks SET content_revision=1 WHERE id=?",id1);if(name.equals("cancel-during-chat")&&n==1)f.jdbc.update("UPDATE knowledges SET parse_status='cancelled' WHERE id='knowledge'");response=json.createObjectNode().set("choices",json.createArrayNode().add(json.createObjectNode().put("finish_reason","stop").set("message",json.createObjectNode().put("role","assistant").put("content",oracle.path("reply").asText()))));}}
      else if(name.startsWith("embedding-error")){status=400;response=json.createObjectNode().set("error",json.createObjectNode().put("message","synthetic embedding unavailable"));}
      else {var values=json.createArrayNode();for(int i=0;i<body.path("input").size();i++)values.add(json.createObjectNode().put("index",i).set("embedding",json.createArrayNode().add(1).add(0).add(0)));response=json.createObjectNode().set("data",values);}
      byte[] bytes=json.writeValueAsBytes(response);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}
    }finally{exchange.close();}});server.start();
    var env=new MockEnvironment().withProperty("SSRF_WHITELIST","127.0.0.1").withProperty("SSRF_DNS_WHITELIST_ONLY","true");var models=new ModelClientFactory(f.jdbc,json,new GoSecretCodec(""),new ModelCatalogRuntime(f.jdbc,"","/nonexistent"),new ModelEgressPolicy(f.jdbc,json,env),env);
    try {
      ObjectNode p=json.createObjectNode().put("provider","generic").put("base_url","http://127.0.0.1:"+server.getAddress().getPort()+"/v1").put("api_key","synthetic-key");p.set("embedding_parameters",json.createObjectNode().put("dimension",3));for(String id:List.of("summary","embedding"))f.jdbc.update("INSERT INTO models(id,tenant_id,name,type,source,parameters,is_builtin,status) VALUES(?,1,'unknown',?,'remote',?,false,'active')",id,id.equals("summary")?"KnowledgeQA":"Embedding",json.writeValueAsBytes(p));
      if(name.equals("metadata-error"))f.jdbc.execute("CREATE TRIGGER fail_metadata BEFORE UPDATE OF metadata ON chunks BEGIN SELECT RAISE(FAIL,'synthetic metadata failure');END");if(name.startsWith("index-error"))f.jdbc.execute("CREATE TRIGGER fail_index BEFORE INSERT ON lite_embeddings BEGIN SELECT RAISE(FAIL,'synthetic index failure');END");
      ObjectNode payload=(ObjectNode)oracle.path("payload").deepCopy();var job=f.job(k,"question:generation");f.jdbc.update("UPDATE java_task_jobs SET payload=?,attempts=? WHERE id=?",payload.toString(),oracle.path("retry").asInt()+1,job.id());job=f.tasks.get(1,job.id()).orElseThrow();
      var reads=new KnowledgeProcessingSpanRepository(f.jdbc,json);var tracker=new KnowledgeProcessingSpanTracker(new KnowledgeProcessingSpanWriteRepository(f.jdbc,json,reads),json);var attempt=tracker.open(1,"knowledge","question-oracle-trace");var parent=tracker.beginStage(1,"knowledge",attempt.number(),"postprocess",null);if(!mode.equals("legacy")){var group=tracker.beginSubspan(parent,"postprocess.question","subspan",null);tracker.end(group,null);}var repository=new QuestionGenerationRepository(f.jdbc,json,f.knowledge,f.manager,Clock.systemUTC());var handler=new QuestionGenerationHandler(repository,json,new SummaryGenerationHandler.Models(){AuthContext auth(long owner){return new AuthContext("system-task",owner,"viewer",false,null,false,Set.of(),Set.of());}public ChatModel chat(long owner,String id){return models.chat(auth(owner),id);}public EmbeddingModel embedding(long owner,String id){return models.embedding(auth(owner),id);}},(owner,kb)->f.index,tracker,new QuestionGenerationText(json,name.equals("default-prompt")?null:oracle.path("prompt").asText()));
      Exception failure=null;try{handler.execute(job,f.cancellation(job));}catch(Exception e){failure=e;}
      assertThat(failure!=null).as("worker retry decision: %s",failure).isEqualTo(!oracle.path("error").asText().isEmpty());
      assertThat(numbers(canonicalRequests(json.valueToTree(requests)))).as("actual model HTTP with unordered embedding partitions normalized").isEqualTo(numbers(canonicalRequests(oracle.path("requests"))));
      assertThat(numbers(projection(f))).as("committed SQL metadata, actual index and shared terminal counter").isEqualTo(numbers(projectOracle(oracle)));
      var workerSpan=reads.list("knowledge",1).stream().filter(c->c.path("name").asText().equals(oracle.path("span").path("name").asText())).reduce((a,b)->b).orElseThrow();var span=json.createObjectNode();for(String field:List.of("name","status","input","output","error_code"))span.set(field,workerSpan.has(field)?workerSpan.get(field):(field.equals("error_code")?json.getNodeFactory().textNode(""):NullNode.instance));assertThat(numbers(span)).as("actual Go tracker input/status/output").isEqualTo(numbers(oracle.path("span")));
      // Replaying terminal accounting is idempotent, independently of the shared summary worker.
      if(!name.equals("superseded-during-chat")&&(failure==null||oracle.path("retry").asInt()==3)){JsonNode before=projection(f);repository.drain(job);assertThat(projection(f)).isEqualTo(before);}
    }finally{models.close();server.stop(0);}
  }
  void chunk(DocumentProcessingCompatibilityTest f,String id,String body,int index,int start,int end,String type,String parent,boolean enabled,int revision){f.jdbc.update("INSERT INTO chunks(id,tenant_id,knowledge_base_id,knowledge_id,content,chunk_index,start_at,end_at,chunk_type,parent_chunk_id,is_enabled,content_revision,status,source_content,seq_id) VALUES(?,1,'kb','knowledge',?,?,?,?,?,?,?,?,0,'',(SELECT COALESCE(MAX(seq_id),0)+1 FROM chunks))",id,body,index,start,end,type,parent,enabled,revision);}
  ObjectNode canonicalRequests(JsonNode requests){var out=json.createObjectNode();var chats=out.putArray("chats");var embeddings=new ArrayList<String>();JsonNode options=null;for(var r:requests){if(r.path("path").asText().endsWith("chat/completions"))chats.add(r);else {ObjectNode body=(ObjectNode)r.path("body").deepCopy();for(var value:body.path("input"))embeddings.add(value.asText());body.remove("input");if(options==null)options=body;else assertThat(body).isEqualTo(options);}}Collections.sort(embeddings);out.set("embedding_inputs",json.valueToTree(embeddings));out.set("embedding_options",options==null?NullNode.instance:options);return out;}
  ObjectNode projectOracle(JsonNode oracle){var out=json.createObjectNode();for(String key:List.of("knowledge","chunks","indexes"))out.set(key,oracle.get(key));return out;}
  ObjectNode projection(DocumentProcessingCompatibilityTest f)throws Exception {
    var out=json.createObjectNode();var state=f.jdbc.queryForMap("SELECT * FROM knowledges WHERE id='knowledge'");var k=out.putObject("knowledge");for(String key:List.of("description","summary_status","parse_status","pending_subtasks_count","error_message","storage_size"))k.set(key,json.valueToTree(state.get(key)));
    var chunks=out.putArray("chunks");var ids=new HashMap<String,String>();for(var row:f.jdbc.queryForList("SELECT * FROM chunks WHERE chunk_type='text' AND deleted_at IS NULL ORDER BY id")){var c=chunks.addObject();for(String key:List.of("id","tenant_id","knowledge_id","content_revision"))c.set(key,json.valueToTree(row.get(key)));c.put("enabled",!row.get("is_enabled").toString().equals("0"));Object raw=row.get("metadata");JsonNode metadata=raw==null?NullNode.instance:json.readTree(raw instanceof byte[] bytes?new String(bytes,java.nio.charset.StandardCharsets.UTF_8):raw.toString());if(metadata.isObject()){int n=0;for(var question:metadata.path("generated_questions")){String id=question.path("id").asText(),label="question-"+(++n),chunk=row.get("id").toString();ids.put(QuestionGenerationText.sourceId(chunk,id),QuestionGenerationText.sourceId(chunk,label));((ObjectNode)question).put("id",label);}}c.set("metadata",metadata);}
    var rows=f.jdbc.queryForList("SELECT source_id,source_type,chunk_id,knowledge_id,knowledge_base_id,content,dimension,is_enabled FROM lite_embeddings ORDER BY source_id");for(var row:rows){String id=row.get("source_id").toString();row.put("source_id",ids.getOrDefault(id,"stale-"+row.get("chunk_id")+"-"+row.get("content")));}rows.sort(Comparator.comparing(e->e.get("source_id").toString()));out.set("indexes",json.valueToTree(rows));return out;
  }
  JsonNode numbers(JsonNode n){if(n.isNumber())return DecimalNode.valueOf(n.decimalValue().stripTrailingZeros());if(n.isArray()){var a=json.createArrayNode();n.forEach(v->a.add(numbers(v)));return a;}if(n.isObject()){var a=json.createObjectNode();n.fields().forEachRemaining(e->a.set(e.getKey(),numbers(e.getValue())));return a;}return n;}
}
