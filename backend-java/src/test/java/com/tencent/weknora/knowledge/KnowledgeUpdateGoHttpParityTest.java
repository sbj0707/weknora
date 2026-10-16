package com.tencent.weknora.knowledge;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.security.*;
import com.tencent.weknora.task.KnowledgeFileTaskEnqueuer;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.*;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Actual Go partial update HTTP, ownership, raw metadata, SQL and real Asynq admission oracle. */
class KnowledgeUpdateGoHttpParityTest extends KnowledgeFolderGoHttpParityTest {
 KnowledgeUpdateGoHttpParityTest(){json.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);}
 @Override @TestFactory Stream<DynamicTest> folders()throws Exception{JsonNode cases;try(var in=getClass().getResourceAsStream("/go-knowledge-update-http.json")){cases=json.readTree(in);}assertThat(cases.size()).isEqualTo(112);var sequence=new AtomicInteger();return StreamSupport.stream(cases.spliterator(),false).map(row->DynamicTest.dynamicTest(row.path("name").asText(),()->compare(row,sequence.incrementAndGet())));}
 @Override void compare(JsonNode oracle,int no)throws Exception {
  var f=database(no);try {
   String variant=oracle.path("variant").asText();
   f.jdbc.update("UPDATE knowledge_bases SET creator_id='caller',embedding_model_id='',summary_model_id='summary' WHERE id='kb'");f.jdbc.update("INSERT INTO knowledge_bases(id,tenant_id,creator_id,name,type,embedding_model_id,summary_model_id) VALUES('other-kb',1,'other','Other','document','','')");
   var paths=List.of("","A","A/spec","A/spec/deep","B","a%_/sub","C\\windows","中文/说明","e\u0301","é","Z","../A./spec ","A/./spec","aX/sub");
   for(int i=0;i<paths.size();i++)document(f,"d"+i,1,"kb","completed",paths.get(i));
   document(f,"foreign",2,"kb","completed","A");document(f,"other",1,"other-kb","completed","A");document(f,"deleting",1,"kb","deleting","A");document(f,"deleted",1,"kb","completed","A");
   for(String id:List.of("foreign","other","deleting","deleted"))f.jdbc.update("UPDATE knowledges SET title=? WHERE id=?",id.equals("foreign")?"Foreign":id.equals("other")?"Other":id.equals("deleting")?"Deleting":"Deleted",id);
   f.jdbc.update("UPDATE knowledges SET deleted_at=CURRENT_TIMESTAMP WHERE id='deleted'");f.jdbc.update("UPDATE knowledges SET title='Old title',description='Old description',summary_status='completed' WHERE id='d1'");custom(f,"{\"old\":\"value\"}");
   f.jdbc.update("INSERT INTO chunks(id,tenant_id,knowledge_id,knowledge_base_id,content,chunk_index,start_at,end_at,is_enabled,chunk_type,status) VALUES('physical',1,'d1','kb','Unchanged source',0,0,16,true,'text',0)");seedPhysicalIndex(f);
   switch(variant){
    case "missing-kb"->f.jdbc.update("DELETE FROM knowledge_bases WHERE id='kb'");
    case "deleted-kb"->f.jdbc.update("UPDATE knowledge_bases SET deleted_at=CURRENT_TIMESTAMP WHERE id='kb'");
    case "moving"->metadata(f,"{\"_knowledge_transfer\":{\"operation\":\"move\",\"phase\":\"moving\"}}");
    case "foreign-document"->f.jdbc.update("UPDATE knowledges SET tenant_id=2 WHERE id='d1'");
    case "deleted-document"->f.jdbc.update("UPDATE knowledges SET deleted_at=CURRENT_TIMESTAMP WHERE id='d1'");
    case "wrong-kb-doc"->f.jdbc.update("UPDATE knowledges SET knowledge_base_id='other-kb' WHERE id='d1'");
    case "summary-none"->f.jdbc.update("UPDATE knowledges SET summary_status='none' WHERE id='d1'");
    case "summary-empty"->f.jdbc.update("UPDATE knowledges SET summary_status='' WHERE id='d1'");
    case "summary-no-model"->f.jdbc.update("UPDATE knowledge_bases SET summary_model_id='' WHERE id='kb'");
    case "same-metadata"->custom(f,"{\"new\":\"value\"}");
    case "metadata-null-existing"->custom(f,"null");
    case "metadata-array-existing"->custom(f,"[]");
    case "metadata-inflight"->f.jdbc.update("UPDATE knowledges SET summary_status='processing' WHERE id='d1'");

    case "same-zero"->custom(f,"{\"number\":0}");
    case "same-float"->custom(f,"{\"number\":1}");
    case "same-precision"->custom(f,"{\"number\":9007199254740992}");
    case "same-order"->custom(f,"{\"a\":1,\"b\":true}");
    case "empty-array-to-object"->custom(f,"[]");
    case "null-to-null"->custom(f,"null");
    case "summary-failed"->f.jdbc.update("UPDATE knowledges SET summary_status='failed' WHERE id='d1'");
    case "parse-cancelled"->f.jdbc.update("UPDATE knowledges SET parse_status='cancelled' WHERE id='d1'");
    case "parse-deleting"->f.jdbc.update("UPDATE knowledges SET parse_status='deleting' WHERE id='d1'");
    case "custom-string-storage"->custom(f,"\"\"");
    case "metadata-partial-overflow"->custom(f,"{\"before\":1,\"bad\":1e309,\"after\":2}");
    case "write-error"->writeFailure(f);
   }
   if(variant.startsWith("shared-")){f.jdbc.update("INSERT INTO organizations(id,name,owner_tenant_id,owner_id) VALUES('org','Org',1,'caller')");f.jdbc.update("INSERT INTO organization_tenant_members(id,organization_id,tenant_id,role) VALUES('source','org',1,'admin'),('member','org',2,'editor')");f.jdbc.update("INSERT INTO kb_shares(id,knowledge_base_id,organization_id,source_tenant_id,permission,shared_by_user_id) VALUES('share','kb','org',1,?,'caller')",variant.equals("shared-viewer")?"viewer":"editor");if(variant.equals("shared-revoked"))f.jdbc.update("UPDATE kb_shares SET deleted_at=CURRENT_TIMESTAMP");}
   var before=unchanged(f,"knowledges");var chunks=json.<JsonNode>valueToTree(f.jdbc.queryForList("SELECT * FROM chunks ORDER BY id"));var tenant=json.<JsonNode>valueToTree(f.jdbc.queryForList("SELECT * FROM tenants ORDER BY id"));
   var beans=new DefaultListableBeanFactory();beans.registerSingleton("jdbc",f.jdbc);beans.registerSingleton("json",json);beans.registerSingleton("tx",f.manager);beans.registerSingleton("authRepository",new AuthRepository(f.jdbc,json));beans.registerSingleton("codec",new KnowledgeBaseCodec(json));var audit=new RootBeanDefinition(Class.forName("com.tencent.weknora.security.DeniedRoleAuditService"));audit.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);beans.registerBeanDefinition("audit",audit);var writes=beans.createBean(KnowledgeBaseWriteAuthorization.class);beans.registerSingleton("writes",writes);var guard=new RootBeanDefinition(Class.forName("com.tencent.weknora.security.NativeRoleGuard"));guard.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);beans.registerBeanDefinition("guard",guard);
   KnowledgeFileUpload.Enqueuer producer=variant.equals("queue-error")?(owner,type,payload,options)->{throw new IllegalStateException("controlled enqueue failure");}:new KnowledgeFileTaskEnqueuer(f.tasks);beans.registerSingleton("producer",producer);
   var refresh=new KnowledgeSummaryRefresh(f.jdbc,json,beans.getBeanProvider(KnowledgeFileUpload.Enqueuer.class),null);var documents=new KnowledgeRepository(f.jdbc,json,f.manager);
   var scope=oracle.path("scope");var caps=new HashSet<String>();scope.path("Capabilities").forEach(n->caps.add(n.asText()));var allowed=new HashSet<String>();scope.path("KnowledgeBaseIDs").forEach(n->allowed.add(n.asText()));var caller=new AuthContext(oracle.path("user").asText(),oracle.path("caller_tenant").asLong(),oracle.path("effective_role").asText(),false,oracle.path("machine").asBoolean()?1L:null,scope.path("FullAccess").asBoolean(),caps,allowed);
   var controller=new KnowledgeUpdateController(new KnowledgeUpdateService(f.jdbc,json,writes,documents,refresh,f.manager),new KnowledgeProcessingLanguage(new org.springframework.mock.env.MockEnvironment()),json);
   var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).addInterceptors((org.springframework.web.servlet.HandlerInterceptor)beans.getBean("guard")).setControllerAdvice(new com.tencent.weknora.api.ApiErrorAdvice()).build();
   var response=mvc.perform(MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(oracle.path("method").asText()),java.net.URI.create(oracle.path("target").asText())).content(oracle.path("request_body").asText()).contentType("application/json").requestAttr(CurrentAuth.ATTRIBUTE,caller)).andReturn().getResponse();
   assertThat(response.getStatus()).as("actual Go status").isEqualTo(oracle.path("status").asInt());
   JsonNode actual=json.readTree(response.getContentAsByteArray()),expected=oracle.path("body").deepCopy();
   if(actual.path("data").isObject()){java.time.Instant time=java.time.Instant.parse(actual.path("data").path("updated_at").asText());assertThat(time).isAfter(java.time.Instant.parse("2026-01-01T00:00:00Z"));java.time.Instant.parse(expected.path("data").path("updated_at").asText());((ObjectNode)actual.path("data")).put("updated_at","<dynamic RFC3339 update>");((ObjectNode)expected.path("data")).put("updated_at","<dynamic RFC3339 update>");}
   assertThat(canonical(actual)).as("complete actual Go update/binder/error/reloaded DTO (only valid dynamic timestamp normalized)").isEqualTo(canonical(expected));
   var rows=f.jdbc.queryForList("SELECT id,tenant_id,knowledge_base_id,title,description,summary_status,CAST(custom_metadata AS TEXT) AS custom_metadata,parse_status,CASE WHEN deleted_at IS NOT NULL THEN 1 ELSE 0 END AS deleted,CASE WHEN updated_at=? THEN 0 ELSE 1 END AS updated FROM knowledges ORDER BY id",seedTime());
   assertThat(canonical(json.valueToTree(rows))).as("exact raw metadata, update boundaries, summary states and rollback").isEqualTo(canonical(oracle.path("documents")));
   var audits=f.jdbc.queryForList("SELECT tenant_id,actor_user_id,actor_role,action,request_path,request_method,outcome,details FROM audit_logs ORDER BY id");for(var entry:audits)entry.put("details",json.readTree(Objects.toString(entry.get("details"))));assertThat(canonical(json.valueToTree(audits.isEmpty()?null:audits))).as("original actor and Go denial audit").isEqualTo(canonical(oracle.path("audits")));
   var tasks=json.createArrayNode();for(var job:f.tasks.list(1,100)){var task=json.createObjectNode().put("type",job.type()).put("queue",job.queue()).put("max_retry",job.maxAttempts()-1).put("timeout",job.timeoutMillis());task.set("payload",job.payload());tasks.add(task);assertThat(job.availableAt()).isEqualTo(job.createdAt());}assertThat(canonical(tasks)).as("actual durable summary refresh task/options").isEqualTo(canonical(oracle.path("tasks")));
   assertThat(unchanged(f,"knowledges")).as("all other document columns retained").isEqualTo(before);assertThat(json.<JsonNode>valueToTree(f.jdbc.queryForList("SELECT * FROM chunks ORDER BY id"))).isEqualTo(chunks);assertThat(json.<JsonNode>valueToTree(f.jdbc.queryForList("SELECT * FROM tenants ORDER BY id"))).as("quota retained").isEqualTo(tenant);assertThat(f.jdbc.queryForList("SELECT chunk_id,content FROM folder_physical_fts")).containsExactly(Map.of("chunk_id","physical","content","Unchanged source"));
  }finally{release(f);}
 }
 void custom(DocumentProcessingCompatibilityTest f,String raw){f.jdbc.update("UPDATE knowledges SET custom_metadata=? WHERE id='d1'",raw);}
 @Override void writeFailure(DocumentProcessingCompatibilityTest f){f.jdbc.execute("CREATE TRIGGER block_update BEFORE UPDATE OF title ON knowledges WHEN OLD.id='d1' BEGIN SELECT RAISE(ABORT,'controlled document write failure'); END");}
 // Actual Go encoding/json decodes unpaired escaped surrogates as U+FFFD; retain all numerical precision.
 @Override JsonNode canonical(JsonNode value){if(value.isTextual())return TextNode.valueOf(GoUploadJson.unicode(value.textValue()));return super.canonical(value);}
 @Override JsonNode unchanged(DocumentProcessingCompatibilityTest f,String table){var rows=f.jdbc.queryForList("SELECT * FROM "+table+" ORDER BY id");rows.forEach(r->{for(String key:List.of("title","description","summary_status","custom_metadata","updated_at"))r.remove(key);});return json.valueToTree(rows);}
}
