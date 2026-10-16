package com.tencent.weknora.knowledge;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.model.*;
import com.tencent.weknora.retrieval.IndexEntry;
import com.tencent.weknora.security.*;
import com.tencent.weknora.task.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/** Replay production Go handlers/services/Asynq against the native controller and real SQL/vec/FTS. */
class KnowledgeReparseGoHttpParityTest extends KnowledgeFolderGoHttpParityTest {
  @Override @TestFactory Stream<DynamicTest> folders() throws Exception {
    JsonNode cases;
    try(var in=getClass().getResourceAsStream("/go-knowledge-reparse-http.json")){cases=json.readTree(in);}
    assertThat(cases.size()).isEqualTo(100);
    var sequence=new AtomicInteger();
    return StreamSupport.stream(cases.spliterator(),false).map(row->DynamicTest.dynamicTest(
        row.path("name").asText(),()->compare(row,sequence.incrementAndGet())));
  }

  @Override void compare(JsonNode oracle,int no) throws Exception {
    var f=database(no);ModelClientFactory models=null;
    try {
      migratePending(f);
      for(String table:List.of("knowledges","knowledge_bases","tenants"))f.jdbc.update("DELETE FROM "+table);
      for(var seed:oracle.path("seed_sql")){
        var args=new ArrayList<Object>();
        for(var value:seed.path("args"))args.add(json.treeToValue(value,Object.class));
        executeSeed(f,seed.path("sql").asText(),args.toArray());
      }
      var entries=List.of(new IndexEntry("physical",0,"physical","d1","kb","","Old indexed body",true),
          new IndexEntry("other-physical",0,"other-physical","d2","kb","","Other indexed body",true));
      var index=writer(f);index.prepare(Set.of(3));
      new TransactionTemplate(f.manager).executeWithoutResult(s->f.jdbc.execute((ConnectionCallback<Void>)c->{
        index.batchSave(c,entries,Map.of("physical",new float[]{1,0,0},"other-physical",new float[]{0,1,0}));return null;
      }));
      var parameters=json.createObjectNode().put("provider","generic").put("base_url","http://127.0.0.1:9/v1").put("api_key","synthetic-key");
      parameters.set("embedding_parameters",json.createObjectNode().put("dimension",3));
      for(String id:List.of("summary","embedding"))f.jdbc.update("INSERT INTO models(id,tenant_id,name,type,source,parameters,is_builtin,status) VALUES(?,1,'unknown',?,'remote',?,false,'active')",id,id.equals("summary")?"KnowledgeQA":"Embedding",parameters.toString());
      var oldJobs=new LinkedHashMap<String,String>();
      for(String id:List.of("d1","d2"))oldJobs.put(id,f.tasks.enqueue(1,"summary:generation","old-"+id,
          json.createObjectNode().put("tenant_id",1).put("knowledge_id",id).put("knowledge_base_id","kb"),4,Duration.ZERO,"summary",Duration.ofMinutes(30)).id());
      var env=environment(oracle);
      models=new ModelClientFactory(f.jdbc,json,new GoSecretCodec(""),new ModelCatalogRuntime(f.jdbc,"","/nonexistent"),new ModelEgressPolicy(f.jdbc,json,env),env);
      var beans=new DefaultListableBeanFactory();
      beans.registerSingleton("jdbc",f.jdbc);beans.registerSingleton("json",json);beans.registerSingleton("tx",f.manager);
      beans.registerSingleton("authRepository",new AuthRepository(f.jdbc,json));beans.registerSingleton("codec",new KnowledgeBaseCodec(json));
      var deniedAudit=new RootBeanDefinition(Class.forName("com.tencent.weknora.security.DeniedRoleAuditService"));
      deniedAudit.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);beans.registerBeanDefinition("audit",deniedAudit);
      var writes=beans.createBean(KnowledgeBaseWriteAuthorization.class);
      beans.registerSingleton("writes",writes);
      var guard=new RootBeanDefinition(Class.forName("com.tencent.weknora.security.NativeRoleGuard"));
      guard.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);beans.registerBeanDefinition("guard",guard);
      var tracker=new KnowledgeProcessingSpanTracker(new KnowledgeProcessingSpanWriteRepository(f.jdbc,json,new KnowledgeProcessingSpanRepository(f.jdbc,json)),json);
      var captured=json.createArrayNode();var durable=new KnowledgeFileTaskEnqueuer(f.tasks);
      KnowledgeFileUpload.Enqueuer producer=(owner,type,payload,options)->{
        if(oracle.path("variant").asText().contains("queue-error"))throw new IllegalStateException("controlled enqueue failure");
        String id=durable.enqueue(owner,type,payload,options);
        captured.add(json.createObjectNode().put("type",type).put("queue",options.queue())
            .put("max_retry",options.maxRetries()).put("timeout",options.timeout().toMillis()).set("payload",payload.deepCopy()));
        return id;
      };
      var service=new KnowledgeReparseConfiguration().knowledgeReparseService(f.jdbc,json,f.knowledge,tracker,f.tasks,
          producer,(owner,kb)->index,models,new Neo4jGraphRepository(null,json),new ManualProcessingImages(null,null,json),f.manager,env);
      var admission=new KnowledgeDeleteAdmissionService(f.jdbc,json,null);
      var controller=new KnowledgeReparseController(service,admission,writes,new KnowledgeProcessingLanguage(env),json);
      var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
          .addInterceptors((org.springframework.web.servlet.HandlerInterceptor)beans.getBean("guard"))
          .setControllerAdvice(new com.tencent.weknora.api.ApiErrorAdvice()).build();
      JsonNode actual=invoke(f,service,mvc,oracle),expected=oracle.path("body").deepCopy();
      for(var body:List.of(actual,expected)){
        if(body.path("data").has("updated_at")){
          var data=(ObjectNode)body.path("data");String time=data.path("updated_at").asText();
          if(Instant.parse(time).isAfter(Instant.parse("2026-01-01T00:00:00Z")))data.put("updated_at","<dynamic RFC3339 update>");
        }
        if(body.path("data").has("task_id")){
          String id=body.path("data").path("task_id").asText();assertThat(UUID.fromString(id).version()).isEqualTo(4);
          ((ObjectNode)body.path("data")).put("task_id","<UUIDv4>");
        }
      }
      assertThat(canonical(actual)).as("complete actual Go response").isEqualTo(canonical(expected));
      var rows=f.jdbc.queryForList("SELECT id,tenant_id,knowledge_base_id,type,title,source,file_path,file_name,file_type,description,summary_status,metadata,parse_status,enable_status,embedding_model_id,storage_size,pending_subtasks_count,error_message,CASE WHEN processed_at IS NULL THEN 1 ELSE 0 END AS processed_null,CASE WHEN deleted_at IS NULL THEN 0 ELSE 1 END AS deleted FROM knowledges ORDER BY id");
      var wanted=oracle.path("documents").deepCopy();
      for(var row:rows)row.put("metadata",metadataValue(row.get("metadata")));
      for(var row:wanted)((ObjectNode)row).set("metadata",metadataValue(row.get("metadata")));
      assertThat(canonical(json.valueToTree(rows))).as("all document checkpoints, SQL NULLs and unrelated rows").isEqualTo(canonical(wanted));
      assertThat(canonical(json.valueToTree(f.jdbc.queryForList("SELECT id,storage_used FROM tenants ORDER BY id"))))
          .as("quota effects including partially failed cleanup").isEqualTo(canonical(oracle.path("tenants")));
      assertThat(canonical(json.valueToTree(f.jdbc.queryForList("SELECT id,tenant_id,knowledge_id,content,CASE WHEN deleted_at IS NULL THEN 0 ELSE 1 END AS deleted FROM chunks ORDER BY id"))))
          .as("chunk soft deletion").isEqualTo(canonical(oracle.path("chunks")));
      assertThat(canonical(json.valueToTree(indexRows(f))))
          .as("real physical index cleanup").isEqualTo(canonical(oracle.path("indexes")));
      var spans=f.jdbc.queryForList("SELECT knowledge_id,attempt,name,kind,status FROM knowledge_processing_spans ORDER BY knowledge_id,attempt,kind,name");
      assertThat(canonical(json.valueToTree(spans.isEmpty()?null:spans))).as("attempt admission order").isEqualTo(canonical(oracle.path("spans")));
      assertThat(canonical(captured)).as("complete task payload, order and options").isEqualTo(canonical(oracle.path("tasks")));
      assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM java_task_jobs",Integer.class)).isEqualTo(captured.size()+oldJobs.size()+queueOverhead());
      var oldQueue=json.createObjectNode();
      for(var entry:oldJobs.entrySet()){
        var old=f.tasks.get(1,entry.getValue()).orElseThrow();
        oldQueue.put(entry.getKey(),!old.status().equals("canceled"));
        if(old.status().equals("canceled"))assertThat(old.cancelRequested()).isTrue();
      }
      assertThat(oldQueue).as("old task execution eligibility against actual Go Asynq inspector").isEqualTo(oracle.path("old_queue"));
      assertThat(canonical(json.valueToTree(f.jdbc.queryForList("SELECT tenant_id,task_type,scope,scope_id,op,dedup_key FROM task_pending_ops ORDER BY id"))))
          .as("actual Go pending-op scrub, preserving retracts and other KB/task/document tuples").isEqualTo(canonical(oracle.path("pending_ops")));
      var audits=f.jdbc.queryForList("SELECT tenant_id,actor_user_id,actor_role,action,request_path,request_method,outcome,details FROM audit_logs ORDER BY id");
      for(var entry:audits)entry.put("details",json.readTree(Objects.toString(entry.get("details"))));
      assertThat(canonical(json.valueToTree(audits.isEmpty()?null:audits))).as("original actor and rejection audit").isEqualTo(canonical(oracle.path("audits")));
    }finally{if(models!=null)models.close();release(f);}
  }

  MockEnvironment environment(JsonNode oracle){return new MockEnvironment().withProperty("SSRF_WHITELIST","127.0.0.1").withProperty("SSRF_DNS_WHITELIST_ONLY","true");}
  int queueOverhead(){return 0;}
  JsonNode invoke(DocumentProcessingCompatibilityTest f,KnowledgeReparseService service,org.springframework.test.web.servlet.MockMvc mvc,JsonNode oracle)throws Exception{
    var scope=oracle.path("scope");var caps=new HashSet<String>();scope.path("Capabilities").forEach(n->caps.add(n.asText()));var allowed=new HashSet<String>();scope.path("KnowledgeBaseIDs").forEach(n->allowed.add(n.asText()));
    var caller=new AuthContext(oracle.path("user").asText(),oracle.path("caller_tenant").asLong(),oracle.path("role").asText(),false,oracle.path("machine").asBoolean()?scope.path("KeyID").asLong():null,scope.path("FullAccess").asBoolean(),caps,allowed);
    var response=mvc.perform(MockMvcRequestBuilders.post(java.net.URI.create(oracle.path("target").asText())).header("Accept-Language","en-US").content(oracle.path("request_body").asText()).contentType("application/json").requestAttr(CurrentAuth.ATTRIBUTE,caller)).andReturn().getResponse();
    assertThat(response.getStatus()).as("actual Go HTTP status").isEqualTo(oracle.path("status").asInt());return json.readTree(response.getContentAsByteArray());
  }

  JsonNode metadataValue(Object raw) throws Exception {
    if(raw==null||raw instanceof JsonNode node&&node.isNull())return json.nullNode();
    if(raw instanceof JsonNode node&&(node.isObject()||node.isArray()))return node;
    String value=raw instanceof byte[] bytes?com.tencent.weknora.docreader.GoUtf8.decode(bytes):raw instanceof JsonNode node?node.asText():raw.toString();
    return json.readTree(value);
  }
  void migratePending(DocumentProcessingCompatibilityTest f)throws Exception{
    try(var c=f.source.getConnection()){
      ScriptUtils.executeSqlScript(c,new FileSystemResource("../migrations/sqlite/000006_task_queue_and_dead_letters.up.sql"));
    }
  }
  void executeSeed(DocumentProcessingCompatibilityTest f,String sql,Object[] args){f.jdbc.update(sql,args);}
  com.tencent.weknora.retrieval.IndexWriter writer(DocumentProcessingCompatibilityTest f){return f.index;}
  List<Map<String,Object>> indexRows(DocumentProcessingCompatibilityTest f){return f.jdbc.queryForList("SELECT source_id,source_type,chunk_id,knowledge_id,knowledge_base_id,content,dimension,is_enabled FROM lite_embeddings ORDER BY source_id");}
}
