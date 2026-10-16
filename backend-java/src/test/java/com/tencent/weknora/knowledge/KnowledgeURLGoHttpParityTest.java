package com.tencent.weknora.knowledge;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.model.ModelURLPolicy;
import com.tencent.weknora.security.*;
import com.tencent.weknora.task.KnowledgeFileTaskEnqueuer;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Actual Go Gin/service/repository/Asynq observations, including partial commits and denial audits. */
class KnowledgeURLGoHttpParityTest extends KnowledgeFolderGoHttpParityTest {
  @Override @TestFactory Stream<DynamicTest> folders() throws Exception {
    JsonNode rows;try(var in=getClass().getResourceAsStream("/go-knowledge-url-http.json")){rows=json.readTree(in);}
    assertThat(rows.size()).isEqualTo(94);var sequence=new AtomicInteger();
    return StreamSupport.stream(rows.spliterator(),false).map(row->DynamicTest.dynamicTest(row.path("name").asText(),()->compare(row,sequence.incrementAndGet())));
  }
  @Override void compare(JsonNode oracle,int no) throws Exception {
    var f=database(no);
    try {
      for(String table:List.of("knowledge_tag_relations","knowledge_tags","knowledges","knowledge_bases","tenants"))f.jdbc.update("DELETE FROM "+table);
      for(var seed:oracle.path("seed_sql")) {
        var args=new ArrayList<Object>();for(var v:seed.path("args"))args.add(json.treeToValue(v,Object.class));
        executeSeed(f,seed.path("sql").asText(),args.toArray());
      }
      var env=new MockEnvironment().withProperty("SSRF_WHITELIST","127.0.0.1").withProperty("SSRF_DNS_WHITELIST_ONLY","true");
      var beans=new DefaultListableBeanFactory();beans.registerSingleton("jdbc",f.jdbc);beans.registerSingleton("json",json);beans.registerSingleton("tx",f.manager);
      beans.registerSingleton("authRepository",new AuthRepository(f.jdbc,json));beans.registerSingleton("codec",new KnowledgeBaseCodec(json));
      var audit=new RootBeanDefinition(Class.forName("com.tencent.weknora.security.DeniedRoleAuditService"));
      audit.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);beans.registerBeanDefinition("audit",audit);
      var writes=beans.createBean(KnowledgeBaseWriteAuthorization.class);
      var captured=json.createArrayNode();var durable=new KnowledgeFileTaskEnqueuer(f.tasks);
      KnowledgeFileUpload.Enqueuer producer=(owner,type,payload,options)-> {
        if(oracle.path("variant").asText().contains("queue-error"))throw new IllegalStateException("controlled enqueue failure");
        String id=durable.enqueue(owner,type,payload,options);
        captured.add(json.createObjectNode().put("type",type).put("queue",options.queue()).put("max_retry",options.maxRetries())
            .put("timeout",options.timeout().toMillis()).set("payload",payload.deepCopy()));
        return id;
      };
      var service=new KnowledgeURLService(f.jdbc,json,f.knowledge,producer,Duration.ofHours(2),Clock.systemUTC());
      var controller=new KnowledgeURLController(service,writes,new KnowledgeProcessingLanguage(env),json,new ModelURLPolicy(f.jdbc,json,env));
      var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
          .setControllerAdvice(new com.tencent.weknora.api.ApiErrorAdvice()).build();
      var scope=oracle.path("scope");var caps=new HashSet<String>();var bases=new HashSet<String>();
      scope.path("Capabilities").forEach(v->caps.add(v.asText()));scope.path("KnowledgeBaseIDs").forEach(v->bases.add(v.asText()));
      var caller=new AuthContext(oracle.path("user").asText(),oracle.path("caller_tenant").asLong(),oracle.path("role").asText(),false,
          oracle.path("machine").asBoolean()?scope.path("KeyID").asLong():null,scope.path("FullAccess").asBoolean(),caps,bases);
      var result=mvc.perform(MockMvcRequestBuilders.post("/api/v1/knowledge-bases/kb/knowledge/url")
          .requestAttr(CurrentAuth.ATTRIBUTE,caller).header("Accept-Language","en-US").header("X-Request-ID","not-in-url-task")
          .contentType("application/json").content(oracle.path("request").asText())).andReturn().getResponse();
      assertThat(result.getStatus()).as("actual Go HTTP status; body=%s",result.getContentAsString()).isEqualTo(oracle.path("status").asInt());
      var documents=f.jdbc.queryForList("SELECT id,tenant_id,knowledge_base_id,type,title,description,source,channel,parse_status,pending_subtasks_count,summary_status,enable_status,embedding_model_id,file_name,file_type,file_size,file_hash,file_path,storage_size,metadata,custom_metadata,error_message,CASE WHEN created_at='2026-01-01T00:00:00Z' THEN 1 ELSE 0 END AS original_created,CASE WHEN updated_at='2026-01-01T00:00:00Z' THEN 1 ELSE 0 END AS original_updated,CASE WHEN processed_at IS NULL THEN 1 ELSE 0 END AS processed_null,CASE WHEN deleted_at IS NULL THEN 0 ELSE 1 END AS deleted FROM knowledges ORDER BY id");
      Set<String> generated=new HashSet<>();for(var row:documents){
        for(String field:List.of("metadata","custom_metadata")) {
          Object raw=row.get(field);if(raw!=null)row.put(field,json.readTree(raw instanceof byte[] b?com.tencent.weknora.docreader.GoUtf8.decode(b):raw.toString()));
        }
        String id=row.get("id").toString();if(!id.equals("old")){assertThat(UUID.fromString(id).version()).isEqualTo(4);generated.add(id);}}
      Set<String> expectedGenerated=new HashSet<>();for(var row:oracle.path("documents")){String id=row.path("id").asText();if(!id.equals("old")){assertThat(UUID.fromString(id).version()).isEqualTo(4);expectedGenerated.add(id);}}
      assertThat(normalize(json.readTree(result.getContentAsByteArray()),generated)).as("complete Go response, no dropped fields").isEqualTo(normalize(oracle.path("body"),expectedGenerated));
      assertThat(normalize(json.valueToTree(documents.isEmpty()?null:documents),generated)).as("all SQL document fields including NULL vs zero and partial commit").isEqualTo(normalize(oracle.path("documents"),expectedGenerated));
      var audits=f.jdbc.queryForList("SELECT tenant_id,actor_user_id,actor_role,action,target_type,target_id,scope_type,scope_id,request_path,request_method,outcome,details FROM audit_logs ORDER BY id");
      for(var row:audits)row.put("details",json.readTree(row.get("details").toString()));
      assertThat(normalize(json.valueToTree(audits.isEmpty()?null:audits),generated)).as("full owner/original actor/API identity and denied role audits").isEqualTo(normalize(oracle.path("audits"),expectedGenerated));
      var relations=f.jdbc.queryForList("SELECT knowledge_id,tag_id FROM knowledge_tag_relations ORDER BY knowledge_id,tag_id");
      assertThat(normalize(json.valueToTree(relations.isEmpty()?null:relations),generated)).as("exact tag relations").isEqualTo(normalize(oracle.path("relations"),expectedGenerated));
      assertThat(normalize(captured,generated)).as("full task fields and queue/retry/deadline").isEqualTo(normalize(oracle.path("tasks"),expectedGenerated));
      assertThat(canonical(json.valueToTree(f.jdbc.queryForList("SELECT id,storage_used,storage_quota FROM tenants ORDER BY id")))).as("both tenant quotas unchanged at admission").isEqualTo(canonical(oracle.path("tenants")));
    } finally {release(f);}
  }
  void executeSeed(DocumentProcessingCompatibilityTest f,String sql,Object[] args){f.jdbc.update(sql,args);}
  JsonNode normalize(JsonNode value,Set<String> generated) {
    if(value.isTextual()&&generated.contains(value.asText()))return TextNode.valueOf("<knowledge UUIDv4>");
    if(value.isArray()){var out=json.createArrayNode();value.forEach(v->out.add(normalize(v,generated)));return out;}
    if(value.isObject()) {
      var out=json.createObjectNode();value.fields().forEachRemaining(e->{String name=e.getKey();JsonNode v=e.getValue();
        if(name.equals("task_id")){assertThat(UUID.fromString(v.asText()).version()).isEqualTo(4);out.put(name,"<task UUIDv4>");}
        else if(Set.of("created_at","updated_at").contains(name)&&v.isTextual()&&!v.asText().equals("2026-01-01T00:00:00Z")){
          assertThat(Instant.parse(v.asText())).isAfter(Instant.parse("2026-01-01T00:00:00Z"));out.put(name,"<generated RFC3339>");
        } else if(Set.of("metadata","custom_metadata").contains(name)&&v.isTextual()) {
          try {out.set(name,normalize(json.readTree(v.asText()),generated));}catch(Exception error){throw new AssertionError("Invalid stored JSON",error);}
        } else out.set(name,normalize(v,generated));});return out;
    }
    return canonical(value);
  }
}
