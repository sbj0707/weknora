package com.tencent.weknora.knowledge;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import com.tencent.weknora.model.*;
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

/** Real Go service/model HTTP/SQL/tracker output, including corrected source fences. */
class KnowledgeAutoTagGoParityTest {
  @TempDir Path temp;final ObjectMapper json=new ObjectMapper();
  static {try{com.tencent.weknora.retrieval.NativeDatabaseRuntime.initialize();}catch(java.sql.SQLException e){throw new ExceptionInInitializerError(e);}}
  @TestFactory Stream<DynamicTest> actualGoAutomaticTagging()throws Exception{
    JsonNode rows;try(var in=getClass().getResourceAsStream("/go-knowledge-auto-tag.json")){rows=json.readTree(in);}assertThat(rows.size()).isEqualTo(52);var sequence=new AtomicInteger();return StreamSupport.stream(rows.spliterator(),false).map(row->DynamicTest.dynamicTest(row.path("name").asText(),()->compare(row,sequence.incrementAndGet())));
  }
  void compare(JsonNode oracle,int caseNo)throws Exception{
    String name=oracle.path("name").asText();var f=new DocumentProcessingCompatibilityTest();f.temp=temp;f.database(caseNo,true);var knowledge=f.seed(json.createObjectNode().put("state","completed"));var job=f.job(knowledge,"knowledge:auto_tag");JsonNode payload=oracle.path("payload").deepCopy();f.jdbc.update("UPDATE java_task_jobs SET payload=? WHERE id=?",payload.toString(),job.id());job=f.tasks.get(1,job.id()).orElseThrow();
    var goKnowledge=oracle.path("knowledge");f.jdbc.update("UPDATE knowledges SET file_name=?,description=?,parse_status=?,summary_status='completed',pending_subtasks_count=2 WHERE id='knowledge'",goKnowledge.path("file_name").asText(),goKnowledge.path("description").asText(),goKnowledge.path("parse_status").asText());f.jdbc.update("UPDATE knowledge_bases SET auto_tag_config=? WHERE id='kb'",json.writeValueAsBytes(oracle.path("config")));
    if(name.equals("no-model"))f.jdbc.update("UPDATE knowledge_bases SET summary_model_id='' WHERE id='kb'");if(name.equals("model-missing"))f.jdbc.update("UPDATE knowledge_bases SET summary_model_id='missing' WHERE id='kb'");
    for(var c:oracle.path("chunks"))f.jdbc.update("INSERT INTO chunks(id,tenant_id,knowledge_id,knowledge_base_id,content,chunk_type,chunk_index,start_at,end_at,is_enabled,content_revision,status,seq_id) VALUES(?,1,'knowledge','kb',?,?,?,?,0,true,0,0,(SELECT COALESCE(MAX(seq_id),0)+1 FROM chunks))",c.path("id").asText(),c.path("content").asText(),c.path("chunk_type").asText(),c.path("chunk_index").asInt(),c.path("start_at").asInt());
    if(name.equals("disabled-chunk"))f.jdbc.update("UPDATE chunks SET is_enabled=false WHERE id='text-1'");if(name.equals("foreign-chunk"))f.jdbc.update("UPDATE chunks SET knowledge_base_id='foreign' WHERE id='text-1'");
    for(int i=0;i<oracle.path("tag_count").asInt();i++)f.jdbc.update("INSERT INTO knowledge_tags(id,tenant_id,knowledge_base_id,name,sort_order,created_at,seq_id) VALUES(?,1,'kb',?,?,'2026-01-01',?)",String.format("tag-%03d",i+1),"标签 "+(i+1),i,i+1);
    if(Set.of("already-tagged","append","all-attached").contains(name))f.jdbc.update("INSERT INTO knowledge_tag_relations(knowledge_id,tag_id,created_at) VALUES('knowledge','tag-001','2026-01-01')");
    var reads=new KnowledgeProcessingSpanRepository(f.jdbc,json);var tracker=new KnowledgeProcessingSpanTracker(new KnowledgeProcessingSpanWriteRepository(f.jdbc,json,reads),json);var attempt=tracker.open(1,"knowledge","auto-tag-oracle");var parent=tracker.beginStage(1,"knowledge",attempt.number(),"postprocess",null);tracker.end(parent,null);if(name.equals("superseded-entry"))tracker.open(1,"knowledge","successor");
    if(name.equals("missing-knowledge"))f.jdbc.update("DELETE FROM knowledges WHERE id='knowledge'");if(name.equals("foreign-knowledge"))f.jdbc.update("UPDATE knowledges SET tenant_id=2 WHERE id='knowledge'");if(name.equals("foreign-kb"))f.jdbc.update("UPDATE knowledge_bases SET tenant_id=2 WHERE id='kb'");if(name.equals("missing-kb"))f.jdbc.update("DELETE FROM knowledge_bases WHERE id='kb'");if(name.equals("faq"))f.jdbc.update("UPDATE knowledge_bases SET type='faq' WHERE id='kb'");
    var requests=Collections.synchronizedList(new ArrayList<JsonNode>());var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/v1/",exchange->{try{var body=json.readTree(exchange.getRequestBody());requests.add(json.createObjectNode().put("path",exchange.getRequestURI().getPath()).set("body",body));if(name.equals("cancel-during-chat"))f.jdbc.update("UPDATE knowledges SET parse_status='cancelled' WHERE id='knowledge'");if(name.equals("deleted-tag-during-chat"))f.jdbc.update("DELETE FROM knowledge_tags WHERE id='tag-001'");if(name.equals("summary-during-chat"))f.jdbc.update("UPDATE knowledges SET description='Fresh parallel summary' WHERE id='knowledge'");if(name.equals("revision-during-chat"))f.jdbc.update("UPDATE chunks SET content_revision=1 WHERE id='text-1'");if(name.equals("superseded-during-chat"))tracker.open(1,"knowledge","successor");ObjectNode response;int status=200;if(name.equals("chat-error")){status=503;response=json.createObjectNode().set("error",json.createObjectNode().put("message","synthetic provider failure"));}else response=json.createObjectNode().set("choices",json.createArrayNode().add(json.createObjectNode().put("finish_reason","stop").set("message",json.createObjectNode().put("role","assistant").put("content",oracle.path("reply").asText()))));byte[] bytes=json.writeValueAsBytes(response);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}}finally{exchange.close();}});server.start();
    var env=new MockEnvironment().withProperty("SSRF_WHITELIST","127.0.0.1").withProperty("SSRF_DNS_WHITELIST_ONLY","true");var models=new ModelClientFactory(f.jdbc,json,new GoSecretCodec(""),new ModelCatalogRuntime(f.jdbc,"","/nonexistent"),new ModelEgressPolicy(f.jdbc,json,env),env);
    try{
      ObjectNode params=json.createObjectNode().put("provider","generic").put("base_url","http://127.0.0.1:"+server.getAddress().getPort()+"/v1").put("api_key","synthetic-key");f.jdbc.update("INSERT INTO models(id,tenant_id,name,type,source,parameters,is_builtin,status) VALUES('summary',1,'unknown','KnowledgeQA','remote',?,false,'active')",json.writeValueAsBytes(params));var repository=new KnowledgeAutoTagRepository(f.jdbc,json,f.knowledge,f.manager,Clock.systemUTC());var handler=new KnowledgeAutoTagHandler(repository,json,(owner,id)->models.chat(new AuthContext("system-task",owner,"viewer",false,null,false,Set.of(),Set.of()),id),tracker,null);Exception failure=null;try{handler.execute(job,f.cancellation(job));}catch(Exception e){failure=e;}
      assertThat(failure!=null).as("Go retry/error decision: %s",failure).isEqualTo(!oracle.path("error").asText().isEmpty());assertThat(numbers(json.valueToTree(requests))).as("actual provider HTTP request").isEqualTo(numbers(oracle.path("requests")));assertThat((JsonNode)json.valueToTree(f.jdbc.queryForList("SELECT tag_id FROM knowledge_tag_relations WHERE knowledge_id='knowledge' ORDER BY tag_id",String.class))).as("additive/idempotent scoped relations").isEqualTo(oracle.path("relations"));
      var optional=reads.list("knowledge",1).stream().filter(s->s.path("name").asText().equals("postprocess.auto_tag")).reduce((a,b)->b);JsonNode projection=NullNode.instance;if(optional.isPresent()){var span=optional.get();var projected=json.createObjectNode();for(String field:List.of("name","status","input","output","error_code"))projected.set(field,span.has(field)?span.get(field):(field.equals("error_code")?json.getNodeFactory().textNode(""):NullNode.instance));projection=projected;}assertThat(numbers(projection)).as("actual Go trace").isEqualTo(numbers(oracle.path("span")));
      if(!name.equals("missing-knowledge"))assertThat(f.jdbc.queryForObject("SELECT pending_subtasks_count FROM knowledges WHERE id='knowledge'",Integer.class)).as("auto tag never owns counter").isEqualTo(2);
    }finally{models.close();server.stop(0);}
  }
  JsonNode numbers(JsonNode n){if(n.isNumber())return DecimalNode.valueOf(n.decimalValue().stripTrailingZeros());if(n.isArray()){var a=json.createArrayNode();n.forEach(v->a.add(numbers(v)));return a;}if(n.isObject()){var a=json.createObjectNode();n.fields().forEachRemaining(e->a.set(e.getKey(),numbers(e.getValue())));return a;}return n;}
}
