package com.tencent.weknora.knowledge;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Real Go HTTP/middleware, staged SQL effects, audit, payload and actual Asynq options. */
class KnowledgeBaseDeleteGoHttpParityTest extends KnowledgeFolderGoHttpParityTest {
  JsonNode rows()throws Exception {try(var in=getClass().getResourceAsStream("/go-knowledge-base-delete-http.json")){return json.readTree(in);}}
  @Override @TestFactory Stream<DynamicTest> folders()throws Exception {
    var cases=rows();assertThat(cases.size()).isEqualTo(50);var n=new AtomicInteger();
    return StreamSupport.stream(cases.spliterator(),false).map(row->DynamicTest.dynamicTest(row.path("name").asText(),()->compareDelete(row,n.incrementAndGet(),false)));
  }
  @TestFactory Stream<DynamicTest> nativeAuthentication()throws Exception {
    var n=new AtomicInteger(1000);
    return StreamSupport.stream(rows().spliterator(),false).filter(row->Set.of("owner","viewer-creator","contributor","api-management","api-no-user","api-full","api-retrieve","api-ingest","api-wrong-kb","api-full-wrong-kb").contains(row.path("name").asText()))
      .map(row->DynamicTest.dynamicTest("authenticated/"+row.path("name").asText(),()->compareDelete(row,n.incrementAndGet(),true)));
  }
  @Override DocumentProcessingCompatibilityTest database(int no)throws Exception {
    var f=super.database(no);
    try(var c=f.source.getConnection()){ScriptUtils.executeSqlScript(c,new FileSystemResource("../migrations/sqlite/000006_task_queue_and_dead_letters.up.sql"));}
    return f;
  }
  boolean postgres(DocumentProcessingCompatibilityTest f){return Boolean.TRUE.equals(f.jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)c->c.getMetaData().getDatabaseProductName().equals("PostgreSQL")));}
  void compareDelete(JsonNode oracle,int no,boolean authenticate)throws Exception {
    var f=database(no);
    try {
      for(String table:List.of("chunks","knowledges","knowledge_bases","tenants","users"))f.jdbc.update("DELETE FROM "+table);
      for(var statement:oracle.path("seed_statements"))f.jdbc.execute(statement.asText());
      String name=oracle.path("name").asText();boolean pg=postgres(f);
      var scope=oracle.path("scope");var caps=new HashSet<String>();scope.path("Capabilities").forEach(x->caps.add(x.asText()));var ids=new HashSet<String>();scope.path("KnowledgeBaseIDs").forEach(x->ids.add(x.asText()));
      var caller=new AuthContext(oracle.path("user").asText(),oracle.path("caller_tenant").asLong(),oracle.path("effective_role").asText(),false,oracle.path("machine").asBoolean()?17L:null,scope.path("FullAccess").asBoolean(),caps,ids);
      String key="delete-key-"+UUID.randomUUID();
      if(caller.isApiKey())f.jdbc.update("INSERT INTO tenant_api_keys(id,tenant_id,name,key_hash,api_key,full_access,capabilities,knowledge_base_ids) VALUES(17,?,'Fixture key',?,?,?,?,?)",caller.tenantId(),AuthFilter.hashApiKey(key),key,caller.fullAccess(),json.writeValueAsString(caps),json.writeValueAsString(ids));
      var beans=new DefaultListableBeanFactory();beans.registerSingleton("jdbc",f.jdbc);beans.registerSingleton("json",json);beans.registerSingleton("tx",f.manager);beans.registerSingleton("authRepository",new AuthRepository(f.jdbc,json));beans.registerSingleton("codec",new KnowledgeBaseCodec(json));
      var audit=new RootBeanDefinition(Class.forName("com.tencent.weknora.security.DeniedRoleAuditService"));audit.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);beans.registerBeanDefinition("audit",audit);
      var writes=beans.createBean(KnowledgeBaseWriteAuthorization.class);beans.registerSingleton("writes",writes);
      var guard=new RootBeanDefinition(Class.forName("com.tencent.weknora.security.NativeRoleGuard"));guard.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);beans.registerBeanDefinition("guard",guard);
      var schedules=new ArrayList<String>();
      var admission=new KnowledgeBaseDeleteAdmission(f.jdbc,json,new KnowledgeBaseCodec(json),new KnowledgeBaseTaskScrubber(f.jdbc,json,Clock.systemUTC()),f.tasks,schedules::add,new MockEnvironment().withProperty("RETRIEVE_DRIVER",oracle.path("driver").asText()),Clock.systemUTC());
      if(name.equals("enqueue-failure")) {
        if(pg){f.jdbc.execute("CREATE OR REPLACE FUNCTION reject_kb_enqueue() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'controlled enqueue failure'; END $$");f.jdbc.execute("CREATE TRIGGER reject_enqueue BEFORE INSERT ON java_task_jobs FOR EACH ROW EXECUTE FUNCTION reject_kb_enqueue()");}
        else f.jdbc.execute("CREATE TRIGGER reject_enqueue BEFORE INSERT ON java_task_jobs BEGIN SELECT RAISE(ABORT,'controlled enqueue failure'); END");
      }
      var setup=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new KnowledgeBaseDeleteController(writes,admission)).addInterceptors((org.springframework.web.servlet.HandlerInterceptor)beans.getBean("guard")).setControllerAdvice(new com.tencent.weknora.api.ApiErrorAdvice());
      String token="";
      if(authenticate){
        var jwt=new JwtCodec(json,"isolated-native-kb-delete-secret");setup.addFilters(new AuthFilter(jwt,new AuthRepository(f.jdbc,json),json));
        if(!caller.isApiKey()){
          f.jdbc.update("INSERT INTO tenant_members(user_id,tenant_id,role,status) VALUES(?,?,?,'active')",caller.userId(),caller.tenantId(),caller.role());token=jwt.sign(Map.of("user_id",caller.userId(),"tenant_id",caller.tenantId(),"exp",4102444800L));
          f.jdbc.update("INSERT INTO auth_tokens(id,user_id,token,token_type,expires_at,is_revoked) VALUES(?,?,?,'access_token','2099-01-01 00:00:00',false)",UUID.randomUUID().toString(),caller.userId(),token);
        }
      }
      var mvc=setup.build();JsonNode body=null;
      for(var status:oracle.path("statuses")) {
        var request=MockMvcRequestBuilders.delete(oracle.path("target").asText());
        if(authenticate){if(caller.isApiKey())request.header("X-API-Key",key);else request.header("Authorization","Bearer "+token);}else request.requestAttr(CurrentAuth.ATTRIBUTE,caller);
        var response=mvc.perform(request).andReturn().getResponse();assertThat(response.getStatus()).as("Go status/%s",name).isEqualTo(status.asInt());body=json.readTree(response.getContentAsByteArray());
      }
      assertThat(canonical(body)).as("complete Go response/%s",name).isEqualTo(canonical(oracle.path("body")));
      var state=json.createObjectNode();
      var queries=new LinkedHashMap<String,String>();
      String bases=name.equals("kb-table-missing")?"hidden_knowledge_bases":"knowledge_bases",sources=name.equals("source-list-failure")?"hidden_data_sources":"data_sources";
      queries.put("bases","SELECT id,tenant_id,CASE WHEN deleted_at IS NULL THEN 0 ELSE 1 END AS deleted,CASE WHEN updated_at='2026-01-01 00:00:00+00:00' THEN 0 ELSE 1 END AS updated FROM "+bases+" ORDER BY id");
      queries.put("shares","SELECT id,CASE WHEN deleted_at IS NULL THEN 0 ELSE 1 END AS deleted FROM kb_shares ORDER BY id");
      queries.put("sources","SELECT id,CASE WHEN deleted_at IS NULL THEN 0 ELSE 1 END AS deleted,CASE WHEN updated_at='2026-01-01 00:00:00+00:00' THEN 0 ELSE 1 END AS updated FROM "+sources+" ORDER BY id");
      queries.put("logs","SELECT id,status,error_message,CASE WHEN finished_at IS NULL THEN 0 ELSE 1 END AS finished,CASE WHEN updated_at='2026-01-01 00:00:00+00:00' THEN 0 ELSE 1 END AS updated FROM sync_logs ORDER BY id");
      queries.put("pending","SELECT scope,scope_id,task_type FROM task_pending_ops ORDER BY scope,scope_id");
      queries.put("documents","SELECT id,parse_status,storage_size,CASE WHEN deleted_at IS NULL THEN 0 ELSE 1 END AS deleted FROM knowledges ORDER BY id");
      queries.put("chunks","SELECT id,content,CASE WHEN deleted_at IS NULL THEN 0 ELSE 1 END AS deleted FROM chunks ORDER BY id");queries.put("tenants","SELECT id,storage_used FROM tenants ORDER BY id");
      for(var entry:queries.entrySet()){var result=f.jdbc.queryForList(entry.getValue());state.set(entry.getKey(),json.valueToTree(result.isEmpty()?null:result));}
      assertThat(canonical(state)).as("all Go staged SQL outcomes/%s",name).isEqualTo(canonical(oracle.path("state")));
      var audits=f.jdbc.queryForList("SELECT tenant_id,actor_user_id,actor_role,action,scope_type,scope_id,target_type,target_id,request_path,request_method,outcome,details FROM "+(name.equals("audit-missing")?"hidden_audit_logs":"audit_logs")+" ORDER BY id");for(var row:audits)row.put("details",json.readTree(row.get("details").toString()));
      assertThat(canonical(json.valueToTree(audits.isEmpty()?null:audits))).as("Go audit/actor/%s",name).isEqualTo(canonical(oracle.path("audits")));
      var tasks=json.createArrayNode();
      for(var row:f.jdbc.queryForList("SELECT tenant_id,type,payload,queue,max_attempts,timeout_millis,status FROM java_task_jobs ORDER BY created_at,id")){
        assertThat(((Number)row.get("tenant_id")).longValue()).isEqualTo(caller.tenantId());
        tasks.add(json.createObjectNode().put("type",row.get("type").toString()).put("queue",row.get("queue").toString()).put("max_retry",((Number)row.get("max_attempts")).intValue()-1).put("timeout",((Number)row.get("timeout_millis")).longValue()).put("scheduled",false).set("payload",json.readTree(row.get("payload").toString())));
      }
      assertThat(canonical(tasks)).as("real Go Asynq options and snapshot/%s",name).isEqualTo(canonical(oracle.path("tasks")));
      var removed=oracle.path("state").path("sources");var expectedSchedules=new ArrayList<String>();
      for(String id:List.of("s2","s1"))for(var source:removed)if(source.path("id").asText().equals(id)&&source.path("deleted").asInt()==1)expectedSchedules.add(id);
      assertThat(schedules).as("remove schedule only after successful source delete").containsExactlyElementsOf(expectedSchedules);
      assertThat(com.tencent.weknora.bridge.GoCompatibilityBridge.ownsNative(new org.springframework.mock.web.MockHttpServletRequest("DELETE","/api/v1/knowledge-bases/kb"))).isTrue();
    }finally {release(f);}
  }
}
