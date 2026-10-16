package com.tencent.weknora.knowledge;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import com.tencent.weknora.model.*;
import com.tencent.weknora.security.*;
import com.tencent.weknora.task.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Actual Go HTTP/authorization/Asynq/model/SQL and physical index differential. */
class KnowledgeSummaryPublicGoHttpParityTest extends KnowledgeFolderGoHttpParityTest {
 @Override @TestFactory Stream<DynamicTest> folders()throws Exception{JsonNode cases;try(var in=getClass().getResourceAsStream("/go-knowledge-summary-public-http.json")){cases=json.readTree(in);}assertThat(cases.size()).isEqualTo(56);var sequence=new AtomicInteger();return StreamSupport.stream(cases.spliterator(),false).map(row->DynamicTest.dynamicTest(row.path("name").asText(),()->compare(row,sequence.incrementAndGet())));}
 @Override void compare(JsonNode oracle,int no)throws Exception {
  var f=database(no);var requests=Collections.synchronizedList(new ArrayList<JsonNode>());HttpServer server=null;ModelClientFactory models=null;
  try {
   String variant=oracle.path("variant").asText(),name=variant.replaceFirst("^initial-","");boolean initial=variant.startsWith("initial-");
   f.jdbc.update("UPDATE knowledge_bases SET creator_id='caller',embedding_model_id='embedding',summary_model_id='summary',indexing_strategy=? WHERE id='kb'","{\"vector_enabled\":true,\"keyword_enabled\":true}");
   f.jdbc.update("INSERT INTO knowledge_bases(id,tenant_id,creator_id,name,type,embedding_model_id,summary_model_id) VALUES('other-kb',1,'other','Other','document','','')");
   var paths=List.of("","A","A/spec","A/spec/deep","B","a%_/sub","C\\windows","中文/说明","e\u0301","é","Z","../A./spec ","A/./spec","aX/sub");
   for(int i=0;i<paths.size();i++)document(f,"d"+i,1,"kb","completed",paths.get(i));
   document(f,"foreign",2,"kb","completed","A");document(f,"other",1,"other-kb","completed","A");document(f,"deleting",1,"kb","deleting","A");document(f,"deleted",1,"kb","completed","A");
   for(String id:List.of("foreign","other","deleting","deleted"))f.jdbc.update("UPDATE knowledges SET title=? WHERE id=?",id.equals("foreign")?"Foreign":id.equals("other")?"Other":id.equals("deleting")?"Deleting":"Deleted",id);
   f.jdbc.update("UPDATE knowledges SET deleted_at=CURRENT_TIMESTAMP WHERE id='deleted'");f.jdbc.update("UPDATE knowledges SET title='Old title',description='Old description',summary_status='completed' WHERE id='d1'");custom(f,"{\"old\":\"value\"}");
   

   switch(variant){
    case "missing-kb"->f.jdbc.update("DELETE FROM knowledge_bases WHERE id='kb'");
    case "deleted-kb"->f.jdbc.update("UPDATE knowledge_bases SET deleted_at=CURRENT_TIMESTAMP WHERE id='kb'");
    case "foreign-document"->f.jdbc.update("UPDATE knowledges SET tenant_id=2 WHERE id='d1'");
    case "deleted-document"->f.jdbc.update("UPDATE knowledges SET deleted_at=CURRENT_TIMESTAMP WHERE id='d1'");
    case "wrong-kb-doc"->f.jdbc.update("UPDATE knowledges SET knowledge_base_id='other-kb' WHERE id='d1'");
    case "summary-no-model"->f.jdbc.update("UPDATE knowledge_bases SET summary_model_id='' WHERE id='kb'");
    case "summary-pending"->f.jdbc.update("UPDATE knowledges SET summary_status='pending' WHERE id='d1'");
    case "summary-processing"->f.jdbc.update("UPDATE knowledges SET summary_status='processing' WHERE id='d1'");
    case "summary-failed"->f.jdbc.update("UPDATE knowledges SET summary_status='failed' WHERE id='d1'");
    case "parse-cancelled","parse-deleting"->f.jdbc.update("UPDATE knowledges SET parse_status=? WHERE id='d1'",variant.substring(6));
    case "moving"->metadata(f,"{\"_knowledge_transfer\":{\"operation\":\"move\",\"phase\":\"moving\"}}");
   }
   if(initial)f.jdbc.update("UPDATE knowledges SET summary_status='none' WHERE id='d1'");
   if(name.equals("empty-status"))f.jdbc.update("UPDATE knowledges SET summary_status='' WHERE id='d1'");
   if(initial&&name.equals("no-model"))f.jdbc.update("UPDATE knowledge_bases SET summary_model_id='' WHERE id='kb'");
   if(name.equals("model-missing"))f.jdbc.update("UPDATE knowledge_bases SET summary_model_id='missing' WHERE id='kb'");
   if(initial&&name.startsWith("parse-"))f.jdbc.update("UPDATE knowledges SET parse_status=? WHERE id='d1'",name.substring(6));
   String content=name.equals("short")?"短😀":"A production document explains onboarding, access controls and delivery checkpoints.";
   f.jdbc.update("INSERT INTO chunks(id,tenant_id,knowledge_id,knowledge_base_id,content,chunk_index,start_at,end_at,is_enabled,chunk_type,status) VALUES('physical',1,'d1','kb',?,0,0,?,true,'text',0)",content,content.codePointCount(0,content.length()));
   if(name.equals("no-text"))f.jdbc.update("UPDATE chunks SET deleted_at=CURRENT_TIMESTAMP WHERE id='physical'");
   if(name.equals("disabled"))f.jdbc.update("UPDATE chunks SET is_enabled=false WHERE id='physical'");
   if(Set.of("existing","duplicates").contains(name))f.jdbc.update("INSERT INTO chunks(id,tenant_id,knowledge_id,knowledge_base_id,content,source_content,chunk_index,start_at,end_at,is_enabled,chunk_type,status,parent_chunk_id) VALUES('summary-existing',1,'d1','kb','Old summary','Old original',2,0,0,false,'summary',0,'physical')");
   if(name.equals("duplicates"))f.jdbc.update("INSERT INTO chunks(id,tenant_id,knowledge_id,knowledge_base_id,content,chunk_index,start_at,end_at,is_enabled,chunk_type,status,parent_chunk_id) VALUES('summary-duplicate',1,'d1','kb','Old duplicate',3,0,0,true,'summary',0,'physical')");
   if(name.equals("edited")){f.jdbc.update("UPDATE chunks SET content_revision=1 WHERE id='physical'");f.jdbc.update("INSERT INTO chunks(id,tenant_id,knowledge_id,knowledge_base_id,content,chunk_index,start_at,end_at,is_enabled,chunk_type,status) VALUES('physical-2',1,'d1','kb','Edited replacement 😀 delivered last.',1,0,20,true,'text',0)");}
   if(name.equals("overlap")){f.jdbc.update("UPDATE chunks SET content='Alpha beta gamma delta epsilon ',end_at=31 WHERE id='physical'");f.jdbc.update("INSERT INTO chunks(id,tenant_id,knowledge_id,knowledge_base_id,content,chunk_index,start_at,end_at,is_enabled,chunk_type,status) VALUES('physical-2',1,'d1','kb','gamma delta epsilon zeta eta final.',1,11,50,true,'text',0)");}
   if(name.equals("custom-metadata"))custom(f,"{\"z\":null,\"a\":\" approved \",\"bool\":true}");
   if(variant.startsWith("shared-")){f.jdbc.update("INSERT INTO organizations(id,name,owner_tenant_id,owner_id) VALUES('org','Org',1,'caller')");f.jdbc.update("INSERT INTO organization_tenant_members(id,organization_id,tenant_id,role) VALUES('source','org',1,'admin'),('member','org',2,'editor')");f.jdbc.update("INSERT INTO kb_shares(id,knowledge_base_id,organization_id,source_tenant_id,permission,shared_by_user_id) VALUES('share','kb','org',1,?,'caller')",variant.equals("shared-viewer")?"viewer":"editor");if(variant.equals("shared-revoked"))f.jdbc.update("UPDATE kb_shares SET deleted_at=CURRENT_TIMESTAMP");}
   server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
   server.createContext("/v1/",exchange->{try{
    ObjectNode body=(ObjectNode)json.readTree(exchange.getRequestBody());String path=exchange.getRequestURI().getPath();requests.add(json.createObjectNode().put("path",path).set("body",body));ObjectNode response;int status=200;
    if(path.equals("/v1/chat/completions")){
     if(name.equals("chat-error")){status=503;response=json.createObjectNode().set("error",json.createObjectNode().put("message","synthetic provider unavailable"));}
     else{if(name.equals("revision-changed"))f.jdbc.update("UPDATE chunks SET content_revision=1 WHERE id='physical'");if(name.equals("metadata-changed"))custom(f,"{\"new\":\"metadata\"}");if(name.equals("cancel-during-chat"))f.jdbc.update("UPDATE knowledges SET parse_status='cancelled' WHERE id='d1'");response=json.createObjectNode().set("choices",json.createArrayNode().add(json.createObjectNode().put("finish_reason","stop").set("message",json.createObjectNode().put("role","assistant").put("content",oracle.path("reply").asText()))));}
    }else if(name.equals("embedding-error")){status=400;response=json.createObjectNode().set("error",json.createObjectNode().put("message","synthetic embedding unavailable"));}
    else{var values=json.createArrayNode();for(int i=0;i<body.path("input").size();i++)values.add(json.createObjectNode().put("index",i).set("embedding",json.createArrayNode().add(1).add(0).add(0)));response=json.createObjectNode().set("data",values);}
    byte[] bytes=json.writeValueAsBytes(response);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}
   }finally{exchange.close();}});server.start();
   ObjectNode parameters=json.createObjectNode().put("provider","generic").put("base_url","http://127.0.0.1:"+server.getAddress().getPort()+"/v1").put("api_key","synthetic-key");parameters.set("embedding_parameters",json.createObjectNode().put("dimension",3));
   for(String id:List.of("summary","embedding"))f.jdbc.update("INSERT INTO models(id,tenant_id,name,type,source,parameters,is_builtin,status) VALUES(?,1,'unknown',?,'remote',?,false,'active')",id,id.equals("summary")?"KnowledgeQA":"Embedding",json.writeValueAsString(parameters));
   if(name.equals("chunk-error"))chunkFailure(f);if(name.equals("index-error"))indexFailure(f);
   var env=new MockEnvironment().withProperty("SSRF_WHITELIST","127.0.0.1").withProperty("SSRF_DNS_WHITELIST_ONLY","true");models=new ModelClientFactory(f.jdbc,json,new GoSecretCodec(""),new ModelCatalogRuntime(f.jdbc,"","/nonexistent"),new ModelEgressPolicy(f.jdbc,json,env),env);
   var before=unchanged(f,"knowledges");var tenant=json.<JsonNode>valueToTree(allRows(f,"tenants"));var source=json.<JsonNode>valueToTree(sourceRows(f));
   var beans=new DefaultListableBeanFactory();beans.registerSingleton("jdbc",f.jdbc);beans.registerSingleton("json",json);beans.registerSingleton("tx",f.manager);beans.registerSingleton("authRepository",new AuthRepository(f.jdbc,json));beans.registerSingleton("codec",new KnowledgeBaseCodec(json));var audit=new RootBeanDefinition(Class.forName("com.tencent.weknora.security.DeniedRoleAuditService"));audit.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);beans.registerBeanDefinition("audit",audit);var writes=beans.createBean(KnowledgeBaseWriteAuthorization.class);
   KnowledgeFileUpload.Enqueuer producer=variant.equals("queue-error")?(owner,type,payload,options)->{throw new IllegalStateException("controlled enqueue failure");}:new KnowledgeFileTaskEnqueuer(f.tasks);beans.registerSingleton("producer",producer);
   var refresh=new KnowledgeSummaryRefresh(f.jdbc,json,beans.getBeanProvider(KnowledgeFileUpload.Enqueuer.class),null);var documents=new KnowledgeRepository(f.jdbc,json,f.manager);var repository=new SummaryGenerationRepository(f.jdbc,json,documents,f.manager,Clock.systemUTC());
   var scope=oracle.path("scope");var caps=new HashSet<String>();scope.path("Capabilities").forEach(n->caps.add(n.asText()));var allowed=new HashSet<String>();scope.path("KnowledgeBaseIDs").forEach(n->allowed.add(n.asText()));var caller=new AuthContext(oracle.path("user").asText(),oracle.path("caller_tenant").asLong(),oracle.path("effective_role").asText(),false,oracle.path("machine").asBoolean()?1L:null,scope.path("FullAccess").asBoolean(),caps,allowed);
   var service=new KnowledgeSummaryRegenerationService(f.jdbc,json,writes,documents,repository,refresh,models,new SummaryGenerationText(json,"PROFILE {{language}}",8192,1024),(owner,kb)->writer(f),null,f.manager);
   var controller=new KnowledgeSummaryRegenerationController(service,new KnowledgeProcessingLanguage(env));var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new com.tencent.weknora.api.ApiErrorAdvice()).build();
   var response=mvc.perform(MockMvcRequestBuilders.post(java.net.URI.create(oracle.path("target").asText())).header("Accept-Language","en-US").content(oracle.path("request_body").asText()).contentType("application/json").requestAttr(CurrentAuth.ATTRIBUTE,caller)).andReturn().getResponse();
   assertThat(response.getStatus()).as("actual Go HTTP status").isEqualTo(oracle.path("status").asInt());JsonNode actual=json.readTree(response.getContentAsByteArray()),expected=oracle.path("body").deepCopy();
   if(actual.path("data").isObject()){normalizeTime((ObjectNode)actual.path("data"));normalizeTime((ObjectNode)expected.path("data"));}
   assertThat(canonical(actual)).as("complete actual Go response").isEqualTo(canonical(expected));assertThat(canonical(json.valueToTree(requests))).as("exact model HTTP calls").isEqualTo(canonical(oracle.path("requests")));
   var rows=f.jdbc.queryForList("SELECT id,tenant_id,knowledge_base_id,title,description,summary_status,CAST(custom_metadata AS TEXT) AS custom_metadata,parse_status,CASE WHEN deleted_at IS NOT NULL THEN 1 ELSE 0 END AS deleted,CASE WHEN updated_at=? THEN 0 ELSE 1 END AS updated FROM knowledges ORDER BY id",seedTime());assertThat(canonical(json.valueToTree(rows))).as("all persisted states and checkpoints").isEqualTo(canonical(oracle.path("documents")));
   var summaries=json.createArrayNode();var ids=new HashMap<String,String>();for(var row:f.jdbc.queryForList("SELECT * FROM chunks WHERE knowledge_id='d1' AND chunk_type='summary' AND deleted_at IS NULL ORDER BY id")){String id=row.get("id").toString(),normalized=id.startsWith("summary-")?id:"new-summary";ids.put(id,normalized);var chunk=summaries.addObject().put("id",normalized);for(var map:Map.of("content","content","source_content","source_content","index","chunk_index","parent","parent_chunk_id","status","status","seq_id","seq_id").entrySet())chunk.set(map.getKey(),json.valueToTree(Objects.requireNonNullElse(row.get(map.getValue()),map.getKey().equals("seq_id")?0:"")));chunk.put("enabled",Boolean.TRUE.equals(row.get("is_enabled"))||Objects.toString(row.get("is_enabled")).equals("1"));}
   var ordered=new ArrayList<JsonNode>();summaries.forEach(ordered::add);ordered.sort(Comparator.comparing(c->c.path("id").asText()));assertThat(canonical(json.valueToTree(ordered))).as("summary chunks: reuse, source content, seq and duplicates").isEqualTo(canonical(oracle.path("summaries")));
   var indexes=indexRows(f);for(var row:indexes){row.put("source_id",ids.get(row.get("source_id")));row.put("chunk_id",ids.get(row.get("chunk_id")));}indexes.sort(Comparator.comparing(r->r.get("source_id").toString()));assertThat(canonical(json.valueToTree(indexes))).as("physical index metadata").isEqualTo(canonical(oracle.path("indexes")));
   var tasks=json.createArrayNode();for(var job:f.tasks.list(1,100)){var task=json.createObjectNode().put("type",job.type()).put("queue",job.queue()).put("max_retry",job.maxAttempts()-1).put("timeout",job.timeoutMillis());task.set("payload",job.payload());tasks.add(task);}assertThat(canonical(tasks)).as("actual durable admission/options").isEqualTo(canonical(oracle.path("tasks")));
   var audits=f.jdbc.queryForList("SELECT tenant_id,actor_user_id,actor_role,action,request_path,request_method,outcome,details FROM audit_logs ORDER BY id");for(var entry:audits)entry.put("details",json.readTree(Objects.toString(entry.get("details"))));assertThat(canonical(json.valueToTree(audits.isEmpty()?null:audits))).as("denial audit actor/template").isEqualTo(canonical(oracle.path("audits")));
   assertThat(json.<JsonNode>valueToTree(allRows(f,"tenants"))).as("quota unchanged").isEqualTo(tenant);
   if(!name.equals("metadata-changed")&&!name.equals("cancel-during-chat"))assertThat(unchanged(f,"knowledges")).as("unrelated document columns retained").isEqualTo(before);
   if(!name.equals("revision-changed"))assertThat(json.<JsonNode>valueToTree(sourceRows(f))).as("source chunks retained").isEqualTo(source);
  }finally{if(models!=null)models.close();if(server!=null)server.stop(0);release(f);}
 }
 com.tencent.weknora.retrieval.IndexWriter writer(DocumentProcessingCompatibilityTest f){return f.index;}
 List<Map<String,Object>> allRows(DocumentProcessingCompatibilityTest f,String table){return f.jdbc.queryForList("SELECT * FROM "+table+" ORDER BY id");}
 List<Map<String,Object>> sourceRows(DocumentProcessingCompatibilityTest f){return f.jdbc.queryForList("SELECT * FROM chunks WHERE chunk_type<>'summary' ORDER BY id");}
 List<Map<String,Object>> indexRows(DocumentProcessingCompatibilityTest f){return f.jdbc.queryForList("SELECT source_id,source_type,chunk_id,knowledge_id,knowledge_base_id,content,dimension,is_enabled FROM lite_embeddings ORDER BY source_id");}
 void chunkFailure(DocumentProcessingCompatibilityTest f){f.jdbc.execute("CREATE TRIGGER fail_chunk BEFORE INSERT ON chunks BEGIN SELECT RAISE(FAIL,'synthetic chunk failure');END");}
 void indexFailure(DocumentProcessingCompatibilityTest f){f.jdbc.execute("CREATE TRIGGER fail_index BEFORE INSERT ON lite_embeddings BEGIN SELECT RAISE(FAIL,'synthetic index failure');END");}
 void normalizeTime(ObjectNode record){String time=record.path("updated_at").asText();Instant stamp=Instant.parse(time);if(stamp.isAfter(Instant.parse("2026-01-01T00:00:00Z")))record.put("updated_at","<dynamic RFC3339 update>");}
 void custom(DocumentProcessingCompatibilityTest f,String value){f.jdbc.update("UPDATE knowledges SET custom_metadata=? WHERE id='d1'",value);}
 @Override JsonNode unchanged(DocumentProcessingCompatibilityTest f,String table){var rows=allRows(f,table);rows.forEach(r->{for(String key:List.of("description","summary_status","profile","updated_at"))r.remove(key);});return json.valueToTree(rows);}
}
