package com.tencent.weknora.knowledge;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.weknora.model.ModelClientFactory;
import com.tencent.weknora.model.client.CallContext;
import com.tencent.weknora.retrieval.*;
import com.tencent.weknora.security.GoSecretCodec;
import com.tencent.weknora.task.*;
import com.tencent.weknora.vectorstore.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual native queue/worker, physical indexes, local files and resource claims versus Go. */
class KnowledgeBaseDeleteRuntimeGoParityTest extends KnowledgeFolderGoHttpParityTest {
  JsonNode rows()throws Exception { try(var in=getClass().getResourceAsStream("/go-knowledge-base-delete-physical.json")){return json.readTree(in);} }
  @Override @TestFactory Stream<DynamicTest> folders()throws Exception {
    var cases=rows();assertThat(cases.size()).isEqualTo(12);var n=new AtomicInteger();
    return StreamSupport.stream(cases.spliterator(),false).map(row->DynamicTest.dynamicTest(row.path("name").asText(),()->compareRuntime(row,n.incrementAndGet())));
  }
  @Override DocumentProcessingCompatibilityTest database(int no)throws Exception {
    var f=super.database(no);
    try(var c=f.source.getConnection()){ScriptUtils.executeSqlScript(c,new FileSystemResource("../migrations/sqlite/000006_task_queue_and_dead_letters.up.sql"));}
    return f;
  }
  boolean postgres(DocumentProcessingCompatibilityTest f){return Boolean.TRUE.equals(f.jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)c->c.getMetaData().getDatabaseProductName().equals("PostgreSQL")));}
  void index(DocumentProcessingCompatibilityTest f,JsonNode oracle)throws Exception {
    boolean pg=postgres(f);
    IndexWriter writer;
    if(postgres(f)){writer=new PostgresIndexRepository(f.jdbc.getDataSource());((PostgresIndexRepository)writer).initialize();}
    else writer=f.index;
    var entries=new ArrayList<IndexEntry>();var vectors=new LinkedHashMap<String,float[]>();
    for(var item:oracle.path("entries"))entries.add(new IndexEntry(item.path("SourceID").asText(),item.path("SourceType").asInt(),item.path("ChunkID").asText(),item.path("KnowledgeID").asText(),item.path("KnowledgeBaseID").asText(),item.path("TagID").asText(),item.path("Content").asText(),item.path("IsEnabled").asBoolean()));
    oracle.path("vectors").fields().forEachRemaining(e->{var values=new float[e.getValue().size()];for(int i=0;i<values.length;i++)values[i]=(float)e.getValue().get(i).asDouble();vectors.put(e.getKey(),values);});
    writer.prepare(Set.of(2));
    new TransactionTemplate(f.manager).executeWithoutResult(status->f.jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>)c->{if(!pg)new SQLiteVectorExtension(Path.of(System.getProperty("weknora.sqlite.vec.extension"))).load(c);writer.batchSave(c,entries,vectors);return null;}));
  }
  Path seed(DocumentProcessingCompatibilityTest f,JsonNode oracle,int no)throws Exception {
    for(String table:List.of("chunks","knowledges","knowledge_bases","tenants","users"))f.jdbc.update("DELETE FROM "+table);
    for(var statement:oracle.path("seed_statements"))f.jdbc.execute(statement.asText());
    Path base=temp.resolve("files-"+no);
    for(var name:oracle.path("initial_files")){var path=base.resolve(name.asText());Files.createDirectories(path.getParent());Files.writeString(path,name.asText());}
    if(oracle.path("missing_file").asBoolean())Files.delete(base.resolve("1/a.txt"));
    index(f,oracle);
    for(var statement:oracle.path("mutations"))f.jdbc.execute(statement.asText());
    return base;
  }
  VectorStoreRegistry stores(DocumentProcessingCompatibilityTest f){return new VectorStoreRegistry(new VectorStoreMetadata(f.jdbc,json,new GoSecretCodec("")),(store,call)->{throw new IllegalStateException("No bound engine required by physical SQL scenarios");});}
  KnowledgeBaseDeleteResources resources(DocumentProcessingCompatibilityTest f,JsonNode row,Path base,VectorStoreRegistry registry){
    var env=new MockEnvironment().withProperty("STORAGE_TYPE",row.path("provider").asText("local")).withProperty("LOCAL_STORAGE_BASE_DIR",base.toString()).withProperty("weknora.sqlite.vec.extension",System.getProperty("weknora.sqlite.vec.extension",""));
    return new KnowledgeBaseDeleteResources(f.jdbc,json,(owner,id)->{if(id.isEmpty())throw new IllegalStateException("empty embedding model");return 2;},registry,new Neo4jGraphRepository(null,json),new KnowledgeBaseTaskScrubber(f.jdbc,json,Clock.systemUTC()),env,Clock.systemUTC());
  }
  JsonNode normal()throws Exception {for(var row:rows())if(row.path("name").asText().equals("normal"))return row;throw new AssertionError("normal Go oracle missing");}

  void compareRuntime(JsonNode oracle,int no)throws Exception {
    var f=database(no);
    try(var registry=stores(f)) {
      var base=seed(f,oracle,no);
      var repository=new KnowledgeBaseDeleteRepository(f.jdbc,json,f.manager,Clock.systemUTC());
      var handler=new KnowledgeBaseDeleteHandler(repository,resources(f,oracle,base,registry),json,f.jdbc);
      var job=f.tasks.enqueue(1,"kb:delete",null,oracle.path("payload"),4,Duration.ZERO,"low",Duration.ofHours(2));
      // These represent downstream work in known queues; foreign-tenant work on the same globally
      // identified KB must stop too. The cleanup job itself must survive the scope sweep.
      var obsolete=List.of(f.tasks.enqueue(2,"document:process",null,json.createObjectNode().put("knowledge_base_id","kb"),3,Duration.ZERO),
          f.tasks.enqueue(1,"datasource:sync",null,json.createObjectNode().put("data_source_id","source"),3,Duration.ZERO,"sync",Duration.ZERO));
      try(var worker=new TaskWorker(f.tasks,List.of(handler),1,Duration.ofSeconds(3),Map.of("low",1))) {
        for(int i=0;i<oracle.path("results").size();i++) {
          if(i>0)f.jdbc.update("UPDATE java_task_jobs SET available_at=0 WHERE id=?",job.id());
          worker.poll();await(f,job.id(),oracle.path("results").get(i).asText().equals("success")?"succeeded":"retry",worker);
        }
      }
      assertThat(f.tasks.get(1,job.id()).orElseThrow().attempts()).isEqualTo(oracle.path("results").size());
      for(var record:obsolete)assertThat(f.tasks.get(record.tenantId(),record.id()).orElseThrow().status()).isEqualTo("canceled");
      assertThat(canonical(state(f))).as("actual Go SQL/index/vector/FTS/resource side effects").isEqualTo(canonical(oracle.path("state")));
      var files=json.createObjectNode();for(var name:oracle.path("initial_files"))files.put(name.asText(),Files.exists(base.resolve(name.asText())));
      assertThat(files).as("actual Go physical file retention/deletion").isEqualTo(oracle.path("files"));
    } finally {release(f);}
  }

  ObjectNode state(DocumentProcessingCompatibilityTest f) {
    boolean pg=postgres(f);
    var out=json.createObjectNode();
    for(String table:List.of("knowledges","chunks","wiki_pages","wiki_folders","wiki_page_issues"))out.set(table,json.valueToTree(f.jdbc.queryForList("SELECT id,tenant_id,knowledge_base_id,CASE WHEN deleted_at IS NULL THEN 0 ELSE 1 END AS deleted FROM "+table+" ORDER BY id")));
    var queries=new LinkedHashMap<String,String>();
    queries.put("tenants","SELECT id,storage_used FROM tenants ORDER BY id");queries.put("wiki_page_revisions","SELECT id,tenant_id,knowledge_base_id FROM wiki_page_revisions ORDER BY id");queries.put("task_pending_ops","SELECT tenant_id,scope,scope_id,op FROM task_pending_ops ORDER BY id");
    queries.put("resources","SELECT id,state,CASE WHEN deleted_at IS NULL THEN 0 ELSE 1 END AS deleted FROM resources ORDER BY id");queries.put("resource_bindings","SELECT resource_id,owner_type,owner_id FROM resource_bindings ORDER BY resource_id,owner_type,owner_id");
    queries.put("indexes","SELECT id,source_id,knowledge_id,knowledge_base_id,dimension FROM "+(postgres(f)?"embeddings":"lite_embeddings")+" ORDER BY id");
    if(postgres(f))queries.put("vectors","SELECT id,embedding::text AS embedding FROM embeddings ORDER BY id");
    else {queries.put("vectors","SELECT rowid AS id,hex(embedding) AS embedding FROM vec_embeddings_2 ORDER BY rowid");queries.put("keyword_hits","SELECT rowid AS id FROM lite_embeddings_fts WHERE lite_embeddings_fts MATCH 'body OR question' ORDER BY rowid");}
    try(var call=new CallContext()) {
      var sql=new KnowledgeBaseDeleteSql(f.jdbc,c->{if(!pg)new SQLiteVectorExtension(Path.of(System.getProperty("weknora.sqlite.vec.extension"))).load(c);});
      for(var entry:queries.entrySet())out.set(entry.getKey(),json.valueToTree(sql.query(call,entry.getValue())));
    }
    return out;
  }

  void await(DocumentProcessingCompatibilityTest f,String id,String status,TaskWorker worker)throws Exception {
    long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
    while(System.nanoTime()<end){
      try {
        var job=f.tasks.get(1,id).orElseThrow();if(job.status().equals(status)&&worker.activeCount()==0)return;
        // Cancellation may exhaust a short lease while the single connection is busy. Once
        // the original execution is terminal, exercise the native recovery claim: the sticky
        // cancel flag must settle it without invoking the business handler again.
        if(status.equals("canceled")&&job.status().equals("running")&&job.cancelRequested()&&worker.activeCount()==0)worker.poll();
      }
      catch(org.springframework.jdbc.CannotGetJdbcConnectionException busy){/* A size-one pool can be held by the SQL we are testing; re-observe the same job. */}
      Thread.sleep(10);
    }
    throw new AssertionError("Expected "+status+", got "+f.tasks.get(1,id));
  }

  @Test void springConfigurationRegistersActualCleanupHandler()throws Exception {
    var f=database(1000);
    var codec=new KnowledgeBaseCodec(json);
    try(var registry=stores(f)) {
      new ApplicationContextRunner().withUserConfiguration(KnowledgeBaseDeleteConfiguration.class)
        .withPropertyValues("weknora.knowledge.processing.enabled=true")
        .withBean(org.springframework.jdbc.core.JdbcTemplate.class,()->f.jdbc).withBean(ObjectMapper.class,()->json)
        .withBean(org.springframework.transaction.PlatformTransactionManager.class,()->f.manager)
        .withBean(KnowledgeBaseCodec.class,()->codec).withBean(TaskRepository.class,()->f.tasks)
        .withBean(ModelClientFactory.class,()->org.mockito.Mockito.mock(ModelClientFactory.class))
        .withBean(VectorStoreRegistry.class,()->registry).withBean(GraphExtractionStore.class,()->new Neo4jGraphRepository(null,json))
        .run(context->{assertThat(context).hasNotFailed();assertThat(context.getBeansOfType(TaskHandler.class).values()).extracting(TaskHandler::type).containsExactly("kb:delete");});
    } finally {release(f);}
  }

  @Test void authenticatedPublicDeleteRunsActualWorkerAndMatchesGoPhysicalState()throws Exception {
    var f=database(1005);
    try(var registry=stores(f)) {
      var oracle=normal();var base=seed(f,oracle,1005);
      f.jdbc.update("UPDATE knowledge_bases SET deleted_at=NULL,creator_id='caller' WHERE id='kb'");
      f.jdbc.update("INSERT INTO users(id,username,email,password_hash,tenant_id,is_active) VALUES('caller','Caller','caller@example.test','unused',1,true)");
      String key="native-delete-"+UUID.randomUUID();
      f.jdbc.update("INSERT INTO tenant_api_keys(id,tenant_id,name,key_hash,api_key,full_access,capabilities,knowledge_base_ids) VALUES(17,1,'Delete key',?,?,false,?,?)",com.tencent.weknora.security.AuthFilter.hashApiKey(key),key,"[\"manage_kbs\"]","[\"kb\"]");
      var beans=new org.springframework.beans.factory.support.DefaultListableBeanFactory();
      beans.registerSingleton("jdbc",f.jdbc);beans.registerSingleton("json",json);beans.registerSingleton("tx",f.manager);
      beans.registerSingleton("authRepository",new com.tencent.weknora.security.AuthRepository(f.jdbc,json));beans.registerSingleton("codec",new KnowledgeBaseCodec(json));
      var audit=new org.springframework.beans.factory.support.RootBeanDefinition(Class.forName("com.tencent.weknora.security.DeniedRoleAuditService"));audit.setAutowireMode(org.springframework.beans.factory.support.AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);beans.registerBeanDefinition("audit",audit);
      var writes=beans.createBean(com.tencent.weknora.security.KnowledgeBaseWriteAuthorization.class);
      String driver=postgres(f)?"postgres":"sqlite";
      var admission=new KnowledgeBaseDeleteAdmission(f.jdbc,json,new KnowledgeBaseCodec(json),new KnowledgeBaseTaskScrubber(f.jdbc,json,Clock.systemUTC()),f.tasks,null,new MockEnvironment().withProperty("RETRIEVE_DRIVER",driver),Clock.systemUTC());
      var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new KnowledgeBaseDeleteController(writes,admission))
          .addFilters(new com.tencent.weknora.security.AuthFilter(new com.tencent.weknora.security.JwtCodec(json,"native-kb-delete-runtime-secret"),new com.tencent.weknora.security.AuthRepository(f.jdbc,json),json))
          .setControllerAdvice(new com.tencent.weknora.api.ApiErrorAdvice()).build();
      var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/knowledge-bases/kb").header("X-API-Key",key)).andReturn().getResponse();
      assertThat(response.getStatus()).isEqualTo(200);
      assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_bases WHERE id='kb' AND deleted_at IS NOT NULL",Integer.class)).isEqualTo(1);
      assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM knowledges WHERE tenant_id=1 AND deleted_at IS NULL",Integer.class)).isEqualTo(2);
      String job=f.jdbc.queryForObject("SELECT id FROM java_task_jobs WHERE type='kb:delete'",String.class);
      var handler=new KnowledgeBaseDeleteHandler(new KnowledgeBaseDeleteRepository(f.jdbc,json,f.manager,Clock.systemUTC()),resources(f,oracle,base,registry),json,f.jdbc);
      try(var worker=new TaskWorker(f.tasks,List.of(handler),1,Duration.ofSeconds(3),Map.of("low",1))) {worker.poll();await(f,job,"succeeded",worker);}
      assertThat(canonical(state(f))).as("public auth -> admission -> worker -> real Go physical result").isEqualTo(canonical(oracle.path("state")));
      var files=json.createObjectNode();for(var name:oracle.path("initial_files"))files.put(name.asText(),Files.exists(base.resolve(name.asText())));
      assertThat(files).isEqualTo(oracle.path("files"));
      assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action='kb.deleted'",Integer.class)).isEqualTo(1);
      assertThat(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/knowledge-bases/kb").header("X-API-Key",key)).andReturn().getResponse().getStatus()).isEqualTo(404);
      assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM java_task_jobs WHERE type='kb:delete'",Integer.class)).isEqualTo(1);
    }finally {release(f);}
  }

  @Test void malformedPersistedPayloadIsClaimedAndRetriedByActualWorker()throws Exception {
    var f=database(1001);
    try(var registry=stores(f)) {
      var oracle=normal();var base=seed(f,oracle,1001);var before=state(f);
      var handler=new KnowledgeBaseDeleteHandler(new KnowledgeBaseDeleteRepository(f.jdbc,json,f.manager,Clock.systemUTC()),resources(f,oracle,base,registry),json,f.jdbc);
      var job=f.tasks.enqueue(1,"kb:delete",null,json.createObjectNode(),4,Duration.ZERO,"low",Duration.ofHours(2));
      f.jdbc.update("UPDATE java_task_jobs SET payload='{' WHERE id=?",job.id());
      try(var worker=new TaskWorker(f.tasks,List.of(handler),1,Duration.ofSeconds(3),Map.of("low",1))){worker.poll();await(f,job.id(),"retry",worker);}
      assertThat(f.tasks.get(1,job.id()).orElseThrow().lastError()).isEqualTo("unexpected end of JSON input");assertThat(state(f)).isEqualTo(before);
    } finally {release(f);}
  }

  @Test void foreignPayloadCannotSwitchQueueOwner()throws Exception {
    var f=database(1002);
    try(var registry=stores(f)) {
      var oracle=normal();var base=seed(f,oracle,1002);var before=state(f);
      var handler=new KnowledgeBaseDeleteHandler(new KnowledgeBaseDeleteRepository(f.jdbc,json,f.manager,Clock.systemUTC()),resources(f,oracle,base,registry),json,f.jdbc);
      var payload=oracle.path("payload").deepCopy();((ObjectNode)payload).put("tenant_id",2);
      var job=f.tasks.enqueue(1,"kb:delete",null,payload,4,Duration.ZERO,"low",Duration.ofHours(2));
      try(var worker=new TaskWorker(f.tasks,List.of(handler),1,Duration.ofSeconds(3),Map.of("low",1))){worker.poll();await(f,job.id(),"dead",worker);}
      assertThat(state(f)).isEqualTo(before);
    } finally {release(f);}
  }

  @Test void forbiddenBoundStoreStillRunsDetachedWikiAndDurableTaskCleanup()throws Exception {
    var f=database(1003);
    try(var registry=stores(f)) {
      var oracle=normal();var base=seed(f,oracle,1003);
      var payload=(ObjectNode)oracle.path("payload").deepCopy();payload.put("vector_store_id","missing-bound-store");
      var handler=new KnowledgeBaseDeleteHandler(new KnowledgeBaseDeleteRepository(f.jdbc,json,f.manager,Clock.systemUTC()),resources(f,oracle,base,registry),json,f.jdbc);
      var job=f.tasks.enqueue(1,"kb:delete",null,payload,4,Duration.ZERO,"low",Duration.ofHours(2));
      try(var worker=new TaskWorker(f.tasks,List.of(handler),1,Duration.ofSeconds(3),Map.of("low",1))){worker.poll();await(f,job.id(),"dead",worker);}
      assertThat(f.tasks.get(1,job.id()).orElseThrow().lastError()).isEqualTo("skip retry for the task");
      assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM knowledges WHERE deleted_at IS NULL",Integer.class)).isEqualTo(3);
      assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM wiki_pages WHERE tenant_id=1 AND deleted_at IS NULL",Integer.class)).isZero();
      assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM task_pending_ops WHERE scope_id='kb'",Integer.class)).isZero();
      assertThat(Files.exists(base.resolve("1/a.txt"))).isTrue();
    } finally {release(f);}
  }

  @Test void cancelInterruptsActualSqlAndRunsDetachedCleanup()throws Exception { interruptSql(false,1004); }
  @Test void deadlineInterruptsActualSqlAndRunsDetachedCleanup()throws Exception { interruptSql(true,1005); }
  void interruptSql(boolean deadline,int no)throws Exception {
    var f=database(no);
    try(var registry=stores(f)) {
      var oracle=normal();var base=seed(f,oracle,no);var entered=new CountDownLatch(1);
      var real=new KnowledgeBaseDeleteRepository(f.jdbc,json,f.manager,Clock.systemUTC());
      var blocked=new KnowledgeBaseDeleteProcessor.Repository(){
        public List<KnowledgeBaseDeleteProcessor.Document> documents(KnowledgeBaseDeleteProcessor.Payload p,CallContext c)throws Exception {entered.countDown();blockSql(f,c);return real.documents(p,c);}
        public List<String> images(KnowledgeBaseDeleteProcessor.Payload p,List<String> ids,CallContext c)throws Exception{return real.images(p,ids,c);}
        public void chunks(KnowledgeBaseDeleteProcessor.Payload p,String id,CallContext c)throws Exception{real.chunks(p,id,c);}
        public void accounting(KnowledgeBaseDeleteProcessor.Payload p,long delta,CallContext c)throws Exception{real.accounting(p,delta,c);}
        public void documentsDeleted(KnowledgeBaseDeleteProcessor.Payload p,List<String> ids,CallContext c)throws Exception{real.documentsDeleted(p,ids,c);}
        public void wiki(KnowledgeBaseDeleteProcessor.Payload p,String table,CallContext c)throws Exception{real.wiki(p,table,c);}
      };
      var handler=new KnowledgeBaseDeleteHandler(blocked,resources(f,oracle,base,registry),json,f.jdbc);
      var job=f.tasks.enqueue(1,"kb:delete",null,oracle.path("payload"),4,Duration.ZERO,"low",deadline?Duration.ofMillis(200):Duration.ofHours(2));
      long start=System.nanoTime();
      try(var worker=new TaskWorker(f.tasks,List.of(handler),1,Duration.ofMillis(600),Map.of("low",1))){
        worker.poll();assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
        if(!deadline)assertThat(requestCancel(f,job.id())).isTrue();
        await(f,job.id(),deadline?"retry":"canceled",worker);
      }
      assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(4));
      assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM knowledges WHERE deleted_at IS NULL",Integer.class)).isEqualTo(3);
      assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM wiki_pages WHERE tenant_id=1 AND deleted_at IS NULL",Integer.class)).isZero();
      assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM task_pending_ops WHERE scope_id='kb'",Integer.class)).isZero();
      assertThat(f.jdbc.queryForObject("SELECT 1",Integer.class)).isEqualTo(1);
    } finally {release(f);}
  }
  void blockSql(DocumentProcessingCompatibilityTest f,CallContext call){new KnowledgeBaseDeleteSql(f.jdbc).query(call,"WITH RECURSIVE busy(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM busy WHERE n<1000000000) SELECT SUM(n) FROM busy");}
  boolean requestCancel(DocumentProcessingCompatibilityTest f,String id){return f.tasks.cancel(1,id);}
}
