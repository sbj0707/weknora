package com.tencent.weknora.knowledge;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.security.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Actual Go public delete handlers, SQL services, full route guards and Asynq admission. */
class KnowledgeDeleteGoHttpParityTest {
 @TempDir Path temp;final ObjectMapper json=new ObjectMapper();
 static{try{com.tencent.weknora.retrieval.NativeDatabaseRuntime.initialize();}catch(java.sql.SQLException e){throw new ExceptionInInitializerError(e);}}
 @TestFactory Stream<DynamicTest> goDeleteAdmission()throws Exception{JsonNode cases;try(var in=getClass().getResourceAsStream(resource())){cases=json.readTree(in);}assertThat(cases.size()).isEqualTo(caseCount());var seq=new AtomicInteger();return StreamSupport.stream(cases.spliterator(),false).map(row->DynamicTest.dynamicTest(row.path("name").asText(),()->compare(row,seq.incrementAndGet())));}
 void compare(JsonNode oracle,int no)throws Exception{
  var f=database(no);try{String name=oracle.path("name").asText();
  f.jdbc.update("UPDATE knowledge_bases SET creator_id='caller',embedding_model_id='',summary_model_id='' WHERE id='kb'");f.jdbc.update("INSERT INTO knowledge_bases(id,tenant_id,creator_id,name,type,embedding_model_id,summary_model_id) VALUES('other-kb',1,'other','Other','document','',''),('empty',1,'caller','Empty','document','','')");
  var ids=List.of("doc","second","other");for(int i=0;i<ids.size();i++){String id=ids.get(i);f.jdbc.update("INSERT INTO knowledges(id,tenant_id,knowledge_base_id,type,title,source,parse_status,created_at,updated_at) VALUES(?,1,?,'file','Document','manual','completed',?,?)",id,id.equals("other")?"other-kb":"kb",seedTime(i),seedTime(i));}
  if(name.endsWith("-moving"))metadata(f,"{\"_knowledge_transfer\":{\"operation\":\"move\",\"phase\":\"moving\"}}");
  if(name.endsWith("-moving-copy"))metadata(f,"{\"_knowledge_transfer\":{\"operation\":\"copy\",\"phase\":\"moving\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
  if(name.endsWith("-moving-phase-only"))metadata(f,"{\"_knowledge_transfer\":{\"phase\":\"moving\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
  if(name.endsWith("-moving-casefold"))metadata(f,"{\"_knowledge_transfer\":{\"OPERATION\":\"move\",\"PHASE\":\"moving\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
  if(name.endsWith("-moving-null-operation"))metadata(f,"{\"_knowledge_transfer\":{\"operation\":null,\"phase\":\"moving\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
  if(name.endsWith("-moving-duplicate-null"))metadata(f,"{\"_knowledge_transfer\":{\"operation\":\"move\",\"operation\":null,\"phase\":\"moving\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
  if(name.endsWith("-moving-invalid-operation"))metadata(f,"{\"_knowledge_transfer\":{\"operation\":4,\"operation\":\"move\",\"phase\":\"moving\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
  if(name.endsWith("-moving-invalid-metadata"))metadata(f,"{\"_knowledge_transfer\":4}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
  if(name.endsWith("-moving-root-null"))metadata(f,"null".getBytes(java.nio.charset.StandardCharsets.UTF_8));
  if(name.endsWith("-moving-root-array"))metadata(f,"[]".getBytes(java.nio.charset.StandardCharsets.UTF_8));
  if(name.endsWith("-moving-outer-casefold"))metadata(f,"{\"_KNOWLEDGE_TRANSFER\":{\"operation\":\"move\",\"phase\":\"moving\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
  if(name.endsWith("-missing-kb"))f.jdbc.update("DELETE FROM knowledge_bases WHERE id='kb'");if(name.endsWith("-deleted-kb"))f.jdbc.update("UPDATE knowledge_bases SET deleted_at=CURRENT_TIMESTAMP WHERE id='kb'");
  if(name.endsWith("-foreign-document"))f.jdbc.update("UPDATE knowledges SET tenant_id=2 WHERE id='doc'");if(name.endsWith("-deleted-document"))f.jdbc.update("UPDATE knowledges SET deleted_at=CURRENT_TIMESTAMP WHERE id='doc'");if(name.endsWith("-temporary"))f.jdbc.update("UPDATE knowledge_bases SET is_temporary=true WHERE id='kb'");
  if(name.contains("-shared-")){f.jdbc.update("INSERT INTO organizations(id,name,owner_tenant_id,owner_id) VALUES('org','Org',1,'caller')");f.jdbc.update("INSERT INTO organization_tenant_members(id,organization_id,tenant_id,role) VALUES('source','org',1,'admin'),('member','org',2,'editor')");f.jdbc.update("INSERT INTO kb_shares(id,knowledge_base_id,organization_id,source_tenant_id,permission,shared_by_user_id) VALUES('share','kb','org',1,?,'caller')",name.endsWith("-viewer")?"viewer":"editor");if(name.endsWith("-revoked"))f.jdbc.update("UPDATE kb_shares SET deleted_at=CURRENT_TIMESTAMP");}
  if(name.endsWith("-api-audit"))f.jdbc.update("INSERT INTO tenant_api_keys(id,tenant_id,name,key_hash,api_key) VALUES(77,1,'Automation','isolated','isolated')");
  if(name.endsWith("-queue-error"))queueFailure(f);
  var beans=new org.springframework.beans.factory.support.DefaultListableBeanFactory();beans.registerSingleton("jdbc",f.jdbc);beans.registerSingleton("json",json);beans.registerSingleton("tx",f.manager);beans.registerSingleton("authRepository",new AuthRepository(f.jdbc,json));beans.registerSingleton("codec",new KnowledgeBaseCodec(json));var audit=new org.springframework.beans.factory.support.RootBeanDefinition(Class.forName("com.tencent.weknora.security.DeniedRoleAuditService"));audit.setAutowireMode(org.springframework.beans.factory.support.AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);beans.registerBeanDefinition("audit",audit);var writes=beans.createBean(KnowledgeBaseWriteAuthorization.class);beans.registerSingleton("writes",writes);var guard=new org.springframework.beans.factory.support.RootBeanDefinition(Class.forName("com.tencent.weknora.security.NativeRoleGuard"));guard.setAutowireMode(org.springframework.beans.factory.support.AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);beans.registerBeanDefinition("guard",guard);var role=beans.getBean("guard");
  var admission=new KnowledgeDeleteAdmissionService(f.jdbc,json,new KnowledgeListDeleteEnqueuer(f.tasks,json).withAuditKeys(f.jdbc));
  var scope=oracle.path("scope");var caps=new HashSet<String>();scope.path("Capabilities").forEach(n->caps.add(n.asText()));var allowed=new HashSet<String>();scope.path("KnowledgeBaseIDs").forEach(n->allowed.add(n.asText()));var caller=new AuthContext(oracle.path("user").asText(),oracle.path("caller_tenant").asLong(),oracle.path("role").asText(),false,oracle.path("machine").asBoolean()?scope.path("KeyID").asLong():null,scope.path("FullAccess").asBoolean(),caps,allowed);
  var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new KnowledgeDeleteController(writes,admission)).addInterceptors((org.springframework.web.servlet.HandlerInterceptor)role).setControllerAdvice(new com.tencent.weknora.api.ApiErrorAdvice()).build();var method=org.springframework.http.HttpMethod.valueOf(oracle.path("method").asText());var response=mvc.perform(MockMvcRequestBuilders.request(method,oracle.path("target").asText()).requestAttr(CurrentAuth.ATTRIBUTE,caller).contentType("application/json").content(oracle.path("request").asText())).andReturn().getResponse();var actualBody=json.readTree(response.getContentAsByteArray());
  assertThat(response.getStatus()).as("actual Go public status").isEqualTo(oracle.path("status").asInt());assertThat(canonical(actualBody)).as("complete Go public envelope").isEqualTo(canonical(oracle.path("body")));
  var docs=json.valueToTree(f.jdbc.queryForList("SELECT id,tenant_id,knowledge_base_id,parse_status,CASE WHEN deleted_at IS NOT NULL THEN 1 ELSE 0 END AS deleted FROM knowledges ORDER BY id"));assertThat(canonical(docs)).as("no document mutation during admission").isEqualTo(canonical(oracle.path("documents")));
  var tasks=json.createArrayNode();for(var task:f.jdbc.queryForList("SELECT id,type,payload,queue,max_attempts,timeout_millis FROM java_task_jobs ORDER BY created_at,id")){var payload=json.readTree(task.get("payload").toString());var out=json.createObjectNode().put("type",task.get("type").toString()).put("queue",task.get("queue").toString()).put("max_retry",((Number)task.get("max_attempts")).longValue()-1).put("timeout",((Number)task.get("timeout_millis")).longValue());out.set("payload",payload);tasks.add(out);if(actualBody.path("data").has("task_id"))assertThat(actualBody.path("data").path("task_id").asText()).isEqualTo(task.get("id").toString());}assertThat(canonical(tasks)).as("exact owner/KB/ordered IDs/initiator/queue/retry/timeout").isEqualTo(canonical(oracle.path("tasks")));
 }finally{release(f);}
 }
 String resource(){return "/go-knowledge-delete-http.json";}
 int caseCount(){return 123;}
 DocumentProcessingCompatibilityTest database(int no)throws Exception{var f=new DocumentProcessingCompatibilityTest();f.temp=temp;f.database(no,false);return f;}
 Object seedTime(int minute){return String.format("2026-01-01T00:0%d:00Z",minute);}
 void metadata(DocumentProcessingCompatibilityTest f,Object raw){f.jdbc.update("UPDATE knowledges SET metadata=? WHERE id='doc'",raw);}
 void queueFailure(DocumentProcessingCompatibilityTest f){f.jdbc.execute("CREATE TRIGGER fail_delete_queue BEFORE INSERT ON java_task_jobs BEGIN SELECT RAISE(ABORT,'controlled enqueue failure'); END");}
 void release(DocumentProcessingCompatibilityTest f){}
 JsonNode canonical(JsonNode value){if(value.isNumber())return DecimalNode.valueOf(value.decimalValue().stripTrailingZeros());if(value.isArray()){var out=json.createArrayNode();value.forEach(n->out.add(canonical(n)));return out;}if(value.isObject()){var out=json.createObjectNode();value.fields().forEachRemaining(e->{if(e.getKey().equals("task_id")){assertThat(UUID.fromString(e.getValue().asText()).version()).isEqualTo(4);out.put("task_id","__new_task_uuid__");}else out.set(e.getKey(),canonical(e.getValue()));});return out;}return value;}
}
