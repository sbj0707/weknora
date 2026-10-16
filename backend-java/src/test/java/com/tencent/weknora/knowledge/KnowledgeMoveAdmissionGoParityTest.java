package com.tencent.weknora.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.weknora.security.*;
import com.tencent.weknora.task.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.*;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Actual Go public handler/API-key gate/role guard; Java uses the native SQL admission/producer. */
class KnowledgeMoveAdmissionGoParityTest {
  @TempDir Path temp;final ObjectMapper json=new ObjectMapper();int sequence;
  @TestFactory Stream<DynamicTest> admission()throws Exception {
    JsonNode rows;try(var in=getClass().getResourceAsStream("/go-knowledge-move-admission.json")){rows=json.readTree(in);}
    var tests=new ArrayList<DynamicTest>();for(var c:rows)for(boolean blob:List.of(false,true))tests.add(DynamicTest.dynamicTest(c.path("name").asText()+(blob?"/blob":"/text"),()->replay(c,blob)));assertEquals(104,tests.size());return tests.stream();
  }
  void replay(JsonNode c,boolean blob)throws Exception {
    var f=new DocumentProcessingCompatibilityTest();f.temp=temp;f.database(++sequence,false);String variant=c.path("variant").asText();
    f.jdbc.update("INSERT INTO tenants(id,name,business,storage_engine_config) VALUES(7,'Owner','test','{}'),(8,'Other','test','{}')");
    if(c.path("tenant").isObject()){var config=c.path("tenant").path("storage_engine_config");f.jdbc.update("UPDATE tenants SET storage_engine_config=? WHERE id=7",blob?json.writeValueAsBytes(config):config.toString());}
    for(String column:List.of("vector_store_id","storage_backend_id","storage_provider_config"))if(f.jdbc.queryForList("PRAGMA table_info(knowledge_bases)").stream().noneMatch(row->column.equals(row.get("name"))))f.jdbc.execute("ALTER TABLE knowledge_bases ADD COLUMN "+column+" TEXT");
    for(JsonNode base:List.of(c.path("source"),c.path("target"))){
      f.jdbc.update("INSERT INTO knowledge_bases(id,tenant_id,creator_id,name,type,embedding_model_id,summary_model_id,vector_store_id,storage_backend_id,storage_provider_config) VALUES(?,?,?,'KB',?,?,'',?,?,?)",base.path("id").asText(),base.path("tenant_id").asLong(),base.path("creator_id").asText(),base.path("type").asText(),base.path("embedding_model_id").asText(),base.path("vector_store_id").isTextual()?base.path("vector_store_id").asText():null,base.path("storage_backend_id").isTextual()?base.path("storage_backend_id").asText():null,base.path("storage_provider_config").isObject()?(blob?json.writeValueAsBytes(base.path("storage_provider_config")):base.path("storage_provider_config").toString()):null);
    }
    for(String id:List.of("doc","second"))f.jdbc.update("INSERT INTO knowledges(id,tenant_id,knowledge_base_id,title,type,source,parse_status,updated_at) VALUES(?,7,'source','Document','file','','completed','2026-01-01 00:00:00+00:00')",id);
    if(variant.equals("missing-document")||variant.equals("foreign-document"))f.jdbc.update("DELETE FROM knowledges WHERE id='doc'");
    if(variant.equals("other-document-kb"))f.jdbc.update("UPDATE knowledges SET knowledge_base_id='target' WHERE id='doc'");
    if(variant.equals("processing-document"))f.jdbc.update("UPDATE knowledges SET parse_status='processing' WHERE id='doc'");
    if(variant.equals("missing-source"))f.jdbc.update("DELETE FROM knowledge_bases WHERE id='source'");
    if(variant.equals("missing-target"))f.jdbc.update("DELETE FROM knowledge_bases WHERE id='target'");
    if(variant.equals("queue-failure"))f.jdbc.execute("CREATE TRIGGER move_enqueue_failure BEFORE INSERT ON java_task_jobs BEGIN SELECT RAISE(ABORT,'controlled enqueue failure'); END");
    f.jdbc.update("INSERT INTO tenant_api_keys(id,tenant_id,name,key_hash,scope_type,capabilities,knowledge_base_ids) VALUES(19,7,'test-key','test-hash','tenant','[]','[]')");
    var beans=new DefaultListableBeanFactory();beans.registerSingleton("jdbc",f.jdbc);beans.registerSingleton("json",json);beans.registerSingleton("tx",f.manager);beans.registerSingleton("authRepository",new AuthRepository(f.jdbc,json));beans.registerSingleton("codec",new KnowledgeBaseCodec(json));
    var denied=new RootBeanDefinition(Class.forName("com.tencent.weknora.security.DeniedRoleAuditService"));denied.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);beans.registerBeanDefinition("audit",denied);
    var writes=beans.createBean(KnowledgeBaseWriteAuthorization.class);beans.registerSingleton("writes",writes);
    var guard=new RootBeanDefinition(Class.forName("com.tencent.weknora.security.NativeRoleGuard"));guard.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);beans.registerBeanDefinition("guard",guard);
    ObjectNode[] initial={null};var progress=new KnowledgeMoveProgress(){public void initialize(ObjectNode p){initial[0]=p.deepCopy().put("created_at",0).put("updated_at",0);if(variant.equals("progress-failure"))throw new IllegalStateException("controlled progress failure");}public void save(ObjectNode p){fail("admission uses SETNX");}public ObjectNode get(String id){throw new UnsupportedOperationException();}};
    var service=new KnowledgeMoveAdmission(f.jdbc,json,new KnowledgeBaseCodec(json),writes,new NamedTaskProducer(f.jdbc,f.tasks,f.manager,Clock.systemUTC()),progress,Clock.systemUTC());
    var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new KnowledgeMoveController(service,progress,json)).addInterceptors((org.springframework.web.servlet.HandlerInterceptor)beans.getBean("guard")).setControllerAdvice(new com.tencent.weknora.api.ApiErrorAdvice()).build();
    var scope=c.path("scope");boolean machine=!scope.isNull();var caps=new HashSet<String>();scope.path("Capabilities").forEach(v->caps.add(v.asText()));var allowed=new HashSet<String>();scope.path("KnowledgeBaseIDs").forEach(v->allowed.add(v.asText()));
    var caller=c.path("caller");var auth=new AuthContext(caller.path("UserID").asText(),caller.path("TenantID").asLong(),caller.path("Role").asText(),false,machine?scope.path("KeyID").asLong():null,scope.path("FullAccess").asBoolean(),caps,allowed);
    JsonNode before=json.valueToTree(f.jdbc.queryForList("SELECT * FROM knowledges ORDER BY id"));
    var response=mvc.perform(MockMvcRequestBuilders.post("/api/v1/knowledge/move").contentType("application/json").content(c.path("raw").asText()).requestAttr(CurrentAuth.ATTRIBUTE,auth)).andReturn().getResponse();
    assertEquals(c.path("status").asInt(),response.getStatus(),response.getContentAsString());var actual=json.readTree(response.getContentAsByteArray());
    var jobs=f.jdbc.queryForList("SELECT * FROM java_task_jobs ORDER BY created_at");JsonNode task=json.nullNode();
    if(!jobs.isEmpty()){
      assertEquals(1,jobs.size());var job=jobs.getFirst();ObjectNode payload=(ObjectNode)json.readTree(job.get("payload").toString());String id=payload.path("task_id").asText();
      assertEquals(java.math.BigInteger.valueOf(7),KnowledgeMoveTaskIds.tenant(id));assertTrue(id.startsWith("kg_move_7_"));
      assertTrue(id.matches("kg_move_7_[0-9]{13,}_[a-f0-9]{8}_source"));assertEquals(id,actual.path("data").path("task_id").asText());assertEquals(id,initial[0].path("task_id").asText());
      assertEquals("low",job.get("queue"));assertEquals(4,((Number)job.get("max_attempts")).intValue());assertEquals(7200000,((Number)job.get("timeout_millis")).longValue());
      assertEquals(id,f.jdbc.queryForObject("SELECT business_id FROM java_task_named_keys",String.class));
      payload.put("task_id","<task-id>");((ObjectNode)actual.path("data")).put("task_id","<task-id>");initial[0].put("task_id","<task-id>");
      task=json.createObjectNode().put("type",job.get("type").toString()).put("queue",job.get("queue").toString()).put("max_retry",3).put("timeout_ms",7200000).set("payload",payload);
    }
    assertEquals(c.path("body"),actual,"complete Go HTTP response");assertEquals(c.path("task"),task,"full payload and producer options");
    assertEquals(c.path("initial"),initial[0]==null?json.nullNode():json.readTree(initial[0].toString()),"best-effort initial Redis record");
    assertEquals(before,json.valueToTree(f.jdbc.queryForList("SELECT * FROM knowledges ORDER BY id")),"admission never mutates a document");
  }
}
