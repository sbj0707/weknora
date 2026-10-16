package com.tencent.weknora.knowledge;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.security.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.*;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Actual Go route/service/SQL oracle; complete DTO, order, read-only state and denied audits. */
class KnowledgeMoveTargetsGoHttpParityTest extends KnowledgeFolderGoHttpParityTest {
  JsonNode rows() throws Exception {
    try(var in=getClass().getResourceAsStream("/go-knowledge-move-targets.json")){return json.readTree(in);}
  }
  @Override @TestFactory Stream<DynamicTest> folders() throws Exception {
    var rows=rows();assertThat(rows.size()).isEqualTo(31);var n=new AtomicInteger();
    return StreamSupport.stream(rows.spliterator(),false).map(row->DynamicTest.dynamicTest(row.path("name").asText(),()->compare(row,n.incrementAndGet())));
  }
  @TestFactory Stream<DynamicTest> authenticatedNativeRoutes() throws Exception {
    var n=new AtomicInteger(1000);
    return StreamSupport.stream(rows().spliterator(),false)
      .filter(row->Set.of("viewer","api-retrieve","api-full","api-ingest","api-wrong-kb","api-full-wrong-kb").contains(row.path("name").asText()))
      .map(row->DynamicTest.dynamicTest("authenticated/"+row.path("name").asText(),()->compare(row,n.incrementAndGet(),true)));
  }
  @Override void compare(JsonNode oracle,int no) throws Exception {compare(oracle,no,false);}
  void compare(JsonNode oracle,int no,boolean authenticate) throws Exception {
    var f=database(no);
    try {
      for(String table:List.of("chunks","knowledges","knowledge_bases","tenants","users"))f.jdbc.update("DELETE FROM "+table);
      for(var statement:oracle.path("seed_statements"))f.jdbc.execute(statement.asText());
      var before=new LinkedHashMap<String,JsonNode>();
      for(String table:List.of("chunks","knowledges","knowledge_bases","tenants","users","user_kb_pins","vector_stores")) {
        boolean hidden=isPostgres(f)?postgresHidden(f,table):Boolean.TRUE.equals(f.jdbc.queryForObject("SELECT COUNT(*)>0 FROM sqlite_master WHERE type='table' AND name=?",Boolean.class,"hidden_"+table));
        String name=hidden?"hidden_"+table:table;
        before.put(name,json.valueToTree(f.jdbc.queryForList("SELECT * FROM "+name)));
      }
      var beans=new DefaultListableBeanFactory();beans.registerSingleton("jdbc",f.jdbc);beans.registerSingleton("json",json);beans.registerSingleton("tx",f.manager);
      var authRepository=new AuthRepository(f.jdbc,json);beans.registerSingleton("authRepository",authRepository);beans.registerSingleton("codec",new KnowledgeBaseCodec(json));
      var audit=new RootBeanDefinition(Class.forName("com.tencent.weknora.security.DeniedRoleAuditService"));audit.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);beans.registerBeanDefinition("audit",audit);
      var guard=new RootBeanDefinition(Class.forName("com.tencent.weknora.security.NativeRoleGuard"));guard.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);beans.registerBeanDefinition("guard",guard);
      var scope=oracle.path("scope");var caps=new HashSet<String>();scope.path("Capabilities").forEach(x->caps.add(x.asText()));var ids=new HashSet<String>();scope.path("KnowledgeBaseIDs").forEach(x->ids.add(x.asText()));
      var caller=new AuthContext(oracle.path("user").asText(),oracle.path("caller_tenant").asLong(),oracle.path("effective_role").asText(),false,oracle.path("machine").asBoolean()?1L:null,scope.path("FullAccess").asBoolean(),caps,ids);
      var reads=new KnowledgeBaseReadAuthorization(f.jdbc,new KnowledgeBaseCodec(json),json,Set.of());
      var controller=new KnowledgeBaseController(new KnowledgeBaseRepository(f.jdbc,json,"sqlite"),null,reads);
      var setup=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
        .addInterceptors((org.springframework.web.servlet.HandlerInterceptor)beans.getBean("guard"))
        .setControllerAdvice(new com.tencent.weknora.api.ApiErrorAdvice());
      var uri=java.net.URI.create(oracle.path("target").asText());var request=MockMvcRequestBuilders.get(uri.getRawPath());
      if(uri.getRawQuery()!=null)for(String pair:uri.getRawQuery().split("&")){String[] p=pair.split("=",2);request.param(p[0],p.length==2?p[1]:"");}
      if(authenticate) {
        var jwt=new JwtCodec(json,"isolated-native-move-targets-test-secret");
        setup.addFilters(new AuthFilter(jwt,authRepository,json));
        if(caller.isApiKey()) {
          String key="move-target-key-"+UUID.randomUUID();
          f.jdbc.update("INSERT INTO tenant_api_keys(tenant_id,name,key_hash,api_key,full_access,capabilities,knowledge_base_ids) VALUES(?,'Targets',?,?,?,?,?)",caller.tenantId(),AuthFilter.hashApiKey(key),key,caller.fullAccess(),json.writeValueAsString(caps),json.writeValueAsString(ids));
          request.header("X-API-Key",key);
        }else {
          f.jdbc.update("INSERT INTO tenant_members(user_id,tenant_id,role,status) VALUES(?,?,?,'active')",caller.userId(),caller.tenantId(),caller.role());
          String token=jwt.sign(Map.of("user_id",caller.userId(),"tenant_id",caller.tenantId(),"exp",4102444800L));
          f.jdbc.update("INSERT INTO auth_tokens(id,user_id,token,token_type,expires_at,is_revoked) VALUES(?,?,?,'access_token','2099-01-01 00:00:00',false)",UUID.randomUUID().toString(),caller.userId(),token);
          request.header("Authorization","Bearer "+token);
        }
      }else request.requestAttr(CurrentAuth.ATTRIBUTE,caller);
      var response=setup.build().perform(request).andReturn().getResponse();
      assertThat(response.getStatus()).as("Go status for %s",oracle.path("name")).isEqualTo(oracle.path("status").asInt());
      assertThat(canonical(json.readTree(response.getContentAsByteArray()))).as("complete Go DTO/order/errors for %s",oracle.path("name")).isEqualTo(canonical(oracle.path("body")));
      for(var entry:before.entrySet())assertThat(json.<JsonNode>valueToTree(f.jdbc.queryForList("SELECT * FROM "+entry.getKey()))).as("read preserves %s",entry.getKey()).isEqualTo(entry.getValue());
      var audits=f.jdbc.queryForList("SELECT tenant_id,actor_user_id,actor_role,action,request_path,request_method,outcome,details FROM audit_logs ORDER BY id");
      for(var row:audits)row.put("details",json.readTree(row.get("details").toString()));
      assertThat(canonical(json.valueToTree(audits.isEmpty()?null:audits))).as("Go role-denial audit").isEqualTo(canonical(oracle.path("audits")));
      assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM java_task_jobs",Integer.class)).isZero();
      assertThat(com.tencent.weknora.bridge.GoCompatibilityBridge.ownsNative(new org.springframework.mock.web.MockHttpServletRequest("GET",uri.getRawPath()))).isTrue();
    }finally {release(f);}
  }
  boolean isPostgres(DocumentProcessingCompatibilityTest f){return Boolean.TRUE.equals(f.jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)c->c.getMetaData().getDatabaseProductName().equals("PostgreSQL")));}
  boolean postgresHidden(DocumentProcessingCompatibilityTest f,String table){return Boolean.TRUE.equals(f.jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL",Boolean.class,"hidden_"+table));}
}
