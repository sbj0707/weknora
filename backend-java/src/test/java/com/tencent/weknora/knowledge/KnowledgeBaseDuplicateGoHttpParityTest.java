package com.tencent.weknora.knowledge;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.security.*;
import com.tencent.weknora.vectorstore.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Independently exported Go handler, configuration clone, raw persisted target and audit projection. */
class KnowledgeBaseDuplicateGoHttpParityTest extends KnowledgeFolderGoHttpParityTest {
  static final List<String> JSON_COLUMNS=List.of("chunking_config","image_processing_config","vlm_config","asr_config","cos_config","storage_provider_config","extract_config","faq_config","question_generation_config","auto_tag_config","profile_config","generated_profile","wiki_config","indexing_strategy");
  JsonNode rows() throws Exception {try(var in=getClass().getResourceAsStream("/go-knowledge-base-duplicate.json")){return json.readTree(in);}}
  @Override @TestFactory Stream<DynamicTest> folders() throws Exception {
    var rows=rows();assertThat(rows.size()).isEqualTo(45);var n=new AtomicInteger();
    return StreamSupport.stream(rows.spliterator(),false).map(row->DynamicTest.dynamicTest(row.path("name").asText(),()->compare(row,n.incrementAndGet(),false)));
  }
  @TestFactory Stream<DynamicTest> nativeAuthentication() throws Exception {
    var n=new AtomicInteger(1000);
    return StreamSupport.stream(rows().spliterator(),false).filter(row->Set.of("owner","viewer","contributor","api-management","api-no-user","api-retrieve","api-full","api-ingest","api-wrong-kb","api-full-wrong-kb").contains(row.path("name").asText()))
      .map(row->DynamicTest.dynamicTest("authenticated/"+row.path("name").asText(),()->compare(row,n.incrementAndGet(),true)));
  }
  void compare(JsonNode oracle,int no,boolean authenticate) throws Exception {
    var f=database(no);
    try {
      for(String table:List.of("chunks","knowledges","knowledge_bases","tenants","users"))f.jdbc.update("DELETE FROM "+table);
      for(var statement:oracle.path("seed_statements"))f.jdbc.execute(statement.asText());
      boolean pg=isPostgres(f);String bases=hidden(f,"knowledge_bases")?"hidden_knowledge_bases":"knowledge_bases";
      String auditsTable=hidden(f,"audit_logs")?"hidden_audit_logs":"audit_logs";
      var scope=oracle.path("scope");var caps=new HashSet<String>();scope.path("Capabilities").forEach(x->caps.add(x.asText()));var ids=new HashSet<String>();scope.path("KnowledgeBaseIDs").forEach(x->ids.add(x.asText()));
      var caller=new AuthContext(oracle.path("user").asText(),oracle.path("caller_tenant").asLong(),oracle.path("effective_role").asText(),false,oracle.path("machine").asBoolean()?17L:null,scope.path("FullAccess").asBoolean(),caps,ids);
      String key="duplicate-key-"+UUID.randomUUID();
      if(caller.isApiKey())f.jdbc.update("INSERT INTO tenant_api_keys(id,tenant_id,name,key_hash,api_key,full_access,capabilities,knowledge_base_ids) VALUES(17,?,'Fixture key',?,?,?,?,?)",caller.tenantId(),AuthFilter.hashApiKey(key),key,caller.fullAccess(),json.writeValueAsString(caps),json.writeValueAsString(ids));
      var before=new LinkedHashMap<String,JsonNode>();
      for(String table:List.of("chunks","knowledges",bases,"tenants","users","user_kb_pins","vector_stores"))before.put(table,json.valueToTree(f.jdbc.queryForList("SELECT * FROM "+table+" ORDER BY "+(table.equals("user_kb_pins")?"kb_id":"id"))));
      var beans=new DefaultListableBeanFactory();beans.registerSingleton("jdbc",f.jdbc);beans.registerSingleton("json",json);beans.registerSingleton("tx",f.manager);beans.registerSingleton("authRepository",new AuthRepository(f.jdbc,json));beans.registerSingleton("codec",new KnowledgeBaseCodec(json));
      var audit=new RootBeanDefinition(Class.forName("com.tencent.weknora.security.DeniedRoleAuditService"));audit.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);beans.registerBeanDefinition("audit",audit);
      var guard=new RootBeanDefinition(Class.forName("com.tencent.weknora.security.NativeRoleGuard"));guard.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);beans.registerBeanDefinition("guard",guard);
      var codec=new KnowledgeBaseCodec(json);var reads=new KnowledgeBaseReadAuthorization(f.jdbc,codec,json,Set.of());var repository=new KnowledgeBaseRepository(f.jdbc,json,"sqlite");
      var metadata=new VectorStoreMetadata(f.jdbc,json,new GoSecretCodec(""));
      try(var registry=new VectorStoreRegistry(metadata,(store,call)->{if(oracle.path("name").asText().equals("unavailable-vector"))throw new IllegalStateException("controlled adapter failure");return new VectorStoreEngine(){public String engine(){return store.engine();}public Set<String> capabilities(){return Set.of();}public java.util.List<com.tencent.weknora.retrieval.RetrievalResult> retrieve(com.tencent.weknora.retrieval.RetrievalQuery query,com.tencent.weknora.model.client.CallContext call){throw new AssertionError("duplicate never retrieves content");}public void batchSave(java.util.List<com.tencent.weknora.retrieval.IndexEntry> e,Map<String,float[]> v,com.tencent.weknora.model.client.CallContext c){throw new AssertionError("duplicate never copies indexes");}public void delete(String f,List<String> i,com.tencent.weknora.model.client.CallContext c){throw new AssertionError("duplicate never deletes indexes");}};})) {
        var controller=new KnowledgeBaseDuplicateController(new KnowledgeBaseDuplicateService(f.jdbc,json,codec,reads,repository,registry),new KnowledgeProcessingLanguage(new MockEnvironment()));
        var setup=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).addInterceptors((org.springframework.web.servlet.HandlerInterceptor)beans.getBean("guard")).setControllerAdvice(new com.tencent.weknora.api.ApiErrorAdvice());
        var request=MockMvcRequestBuilders.post(oracle.path("target").asText());if(!oracle.path("locale").asText().isEmpty())request.header("Accept-Language",oracle.path("locale").asText());
        if(authenticate) {
          var jwt=new JwtCodec(json,"isolated-native-duplicate-test-secret");setup.addFilters(new AuthFilter(jwt,new AuthRepository(f.jdbc,json),json));
          if(caller.isApiKey())request.header("X-API-Key",key);else {
            f.jdbc.update("INSERT INTO tenant_members(user_id,tenant_id,role,status) VALUES(?,?,?,'active')",caller.userId(),caller.tenantId(),caller.role());
            String token=jwt.sign(Map.of("user_id",caller.userId(),"tenant_id",caller.tenantId(),"exp",4102444800L));
            f.jdbc.update("INSERT INTO auth_tokens(id,user_id,token,token_type,expires_at,is_revoked) VALUES(?,?,?,'access_token','2099-01-01 00:00:00',false)",UUID.randomUUID().toString(),caller.userId(),token);request.header("Authorization","Bearer "+token);
          }
        }else request.requestAttr(CurrentAuth.ATTRIBUTE,caller);
        Instant start=Instant.now();var response=setup.build().perform(request).andReturn().getResponse();Instant finish=Instant.now();
        assertThat(response.getStatus()).as("Go status for %s",oracle.path("name")).isEqualTo(oracle.path("status").asInt());
        JsonNode body=json.readTree(response.getContentAsByteArray());String id=body.path("data").path("target_id").asText(),expectedId=oracle.path("target_id").asText();
        ObjectNode persisted=null,sqlTypes=null;
        if(response.getStatus()==201) {
          assertThat(UUID.fromString(id).version()).isEqualTo(4);assertThat(id).isNotEqualTo("source");
          var target=body.path("data").path("knowledge_base");Instant created=Instant.parse(target.path("created_at").asText());assertThat(created).isBetween(start.minusSeconds(1),finish.plusSeconds(1));assertThat(target.path("updated_at")).isEqualTo(target.path("created_at"));
          var row=f.jdbc.queryForMap("SELECT * FROM knowledge_bases WHERE id=?",id);persisted=json.createObjectNode();sqlTypes=json.createObjectNode();
          for(var entry:row.entrySet()) {
            Object value=entry.getValue();String field=entry.getKey();
            if(value==null)persisted.putNull(field);
            else if(JSON_COLUMNS.contains(field))persisted.set(field,value instanceof byte[] bytes?json.readTree(bytes):json.readTree(value.toString()));
            else if(field.endsWith("_at"))persisted.put(field,KnowledgeBaseRepository.timestamp(value));
            else if(field.equals("is_temporary"))persisted.put(field,value instanceof Boolean b?b.booleanValue():value.toString().equals("1")||value.toString().equals("true"));
            else persisted.set(field,json.valueToTree(value));
          }
          assertThat(persisted.path("created_at")).isEqualTo(persisted.path("updated_at"));assertThat(Duration.between(created,Instant.parse(persisted.path("created_at").asText())).abs().toNanos()).isLessThan(1000);
          if(!pg){assertThat(row.get("created_at").toString()).isEqualTo(GoSqliteTimestamp.write(created));for(String column:JSON_COLUMNS)sqlTypes.put(column,f.jdbc.queryForObject("SELECT typeof("+column+") FROM knowledge_bases WHERE id=?",String.class,id));}
        }
        assertThat(canonical(normalize(body,id))).as("complete ordered Go HTTP DTO").isEqualTo(canonical(normalize(oracle.path("body"),expectedId)));
        assertThat(canonical(normalize(persisted==null?json.nullNode():persisted,id))).as("raw persisted target, excluding validated fresh ID/time").isEqualTo(canonical(normalize(oracle.path("persisted"),expectedId)));
        assertThat(sqlTypes==null?json.nullNode():sqlTypes).as("SQLite BLOB/TEXT/NULL storage classes").isEqualTo(oracle.path("sql_types"));
        for(var entry:before.entrySet()) {
          String order=entry.getKey().equals("user_kb_pins")?"kb_id":"id";String where=entry.getKey().equals(bases)&&!id.isEmpty()?" WHERE id<>?":"";
          var rows=where.isEmpty()?f.jdbc.queryForList("SELECT * FROM "+entry.getKey()+" ORDER BY "+order):f.jdbc.queryForList("SELECT * FROM "+entry.getKey()+where+" ORDER BY "+order,id);
          assertThat(json.<JsonNode>valueToTree(rows)).as("preserve all original %s",entry.getKey()).isEqualTo(entry.getValue());
        }
        var audits=f.jdbc.queryForList("SELECT tenant_id,actor_user_id,actor_role,action,scope_type,scope_id,target_type,target_id,request_path,request_method,outcome,details FROM "+auditsTable+" ORDER BY id");
        for(var row:audits)row.put("details",json.readTree(row.get("details").toString()));
        assertThat(canonical(normalize(json.valueToTree(audits.isEmpty()?null:audits),id))).as("Go success/denial audit with original actor").isEqualTo(canonical(normalize(oracle.path("audits"),expectedId)));
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM java_task_jobs",Integer.class)).isZero();
        assertThat(com.tencent.weknora.bridge.GoCompatibilityBridge.ownsNative(new org.springframework.mock.web.MockHttpServletRequest("POST",oracle.path("target").asText()))).isTrue();
      }
    }finally {release(f);}
  }
  JsonNode normalize(JsonNode node,String id) {
    if(node.isTextual()&&!id.isEmpty()&&node.asText().equals(id))return TextNode.valueOf("__target__");
    if(node.isArray()){var out=json.createArrayNode();node.forEach(v->out.add(normalize(v,id)));return out;}
    if(node.isObject()){boolean target=!id.isEmpty()&&node.path("id").asText().equals(id);var out=json.createObjectNode();node.fields().forEachRemaining(e->out.set(e.getKey(),target&&Set.of("created_at","updated_at").contains(e.getKey())?TextNode.valueOf("__validated_time__"):normalize(e.getValue(),id)));return out;}
    return node;
  }
  boolean isPostgres(DocumentProcessingCompatibilityTest f){return Boolean.TRUE.equals(f.jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)c->c.getMetaData().getDatabaseProductName().equals("PostgreSQL")));}
  boolean hidden(DocumentProcessingCompatibilityTest f,String table){return isPostgres(f)?Boolean.TRUE.equals(f.jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL",Boolean.class,"hidden_"+table)):Boolean.TRUE.equals(f.jdbc.queryForObject("SELECT COUNT(*)>0 FROM sqlite_master WHERE type='table' AND name=?",Boolean.class,"hidden_"+table));}
}
