package com.tencent.weknora.knowledge;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.model.*;
import com.tencent.weknora.retrieval.*;
import com.tencent.weknora.security.*;
import com.tencent.weknora.storage.*;
import com.tencent.weknora.task.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.mock.env.MockEnvironment;

/** Actual Go worker/SQL/vec/FTS/local bytes; preserves failed cleanup checkpoints. */
class KnowledgeListDeleteGoParityTest {
  @TempDir Path temp;final ObjectMapper json=new ObjectMapper();
  @TestFactory Stream<DynamicTest> actualGoWorker()throws Exception {JsonNode cases;try(var in=getClass().getResourceAsStream("/go-knowledge-list-delete.json")){cases=json.readTree(in);}assertThat(cases.size()).isEqualTo(46);var sequence=new AtomicInteger();return StreamSupport.stream(cases.spliterator(),false).map(c->DynamicTest.dynamicTest(c.path("name").asText(),()->compare(c,sequence.incrementAndGet())));}
  void compare(JsonNode oracle,int no)throws Exception {
    String name=oracle.path("name").asText();var f=fixture(no,name);var raw=oracle.path("payload");long owner=raw.path("tenant_id").asLong();if(owner<=0)owner=1;
    f.tasks.enqueue(owner,"knowledge:list_delete",null,raw,4,Duration.ZERO,"low",Duration.ofHours(2));var job=f.tasks.claim("delete-worker",Duration.ofMinutes(5),List.of("low")).orElseThrow();
    var indexCalls=json.createArrayNode();var graphCalls=json.createArrayNode();var fileCalls=json.createArrayNode();Path root=temp.resolve("files-"+no);seedFiles(root);
    var catalog=new ResourceCatalog(f.jdbc,null);if(name.startsWith("resource-")){String generated=catalog.register(1,"local://1/image.png",new ResourceCatalog.Registration("image","","",0,"",false));String desired=oracle.path("image_reference").asText();f.jdbc.update("UPDATE resources SET handle=? WHERE handle=?",desired.substring("resource://".length()),generated.substring("resource://".length()));catalog.bind(1,desired,"knowledge","doc","image");if(name.equals("resource-shared-image"))catalog.bind(1,desired,"knowledge","other","image");f.jdbc.update("UPDATE chunks SET image_info=? WHERE id='doc-chunk'",json.createArrayNode().add(json.createObjectNode().put("url",desired)).add(json.createObjectNode().put("url",desired)).toString());}
    var env=new MockEnvironment().withProperty("SSRF_WHITELIST","127.0.0.1").withProperty("SSRF_DNS_WHITELIST_ONLY","true");var models=new ModelClientFactory(f.jdbc,json,new GoSecretCodec(""),new ModelCatalogRuntime(f.jdbc,"","/nonexistent"),new ModelEgressPolicy(f.jdbc,json,env),env);
    var repository=new KnowledgeListDeleteRepository(f.jdbc,json,f.manager,Clock.systemUTC());
    var handler=new KnowledgeListDeleteHandler(repository,json,(tenant,id)->models.embeddingForCleanup(new AuthContext("system-task",tenant,"viewer",false,null,false,Set.of(),Set.of()),id).dimensions(),new KnowledgeListDeleteHandler.Effects(){
      public void prepare(long owner,KnowledgeListDeleteRepository.Plan plan){}
      public List<KnowledgeListDeleteRepository.IndexDeletion> indexes(long owner,KnowledgeListDeleteRepository.Plan plan){if(name.equals("no-engines"))return List.of();return List.of((c,ids,dimension,type)->{indexCalls.add(json.createObjectNode().put("dimension",dimension).put("type",type).set("ids",json.valueToTree(ids)));if(name.equals("vector-error"))throw new java.sql.SQLException("controlled vector deletion error");f.index.deleteByKnowledge(c,ids);});}
      public void graph(TaskRecord job,KnowledgeListDeleteRepository.Plan plan,Runnable guard){guard.run();var namespaces=json.createArrayNode();for(String id:plan.ids())namespaces.add(json.createObjectNode().put("knowledge_base",plan.base().path("id").asText()).put("knowledge",id));graphCalls.add(namespaces);if(name.equals("graph-error"))throw new IllegalStateException("controlled graph deletion error");}
      public void file(long owner,KnowledgeListDeleteRepository.Plan plan,String path)throws Exception{fileCalls.add(path);if(name.equals("file-error"))throw new java.io.IOException("controlled file deletion error");new ResourceFileStore(new LocalFileStore(root,"",null,1024),catalog,owner,"").delete(path);}
      public boolean release(long owner,String reference,List<String> ids){if(!name.startsWith("resource-"))return true;long remaining=-1;for(String id:ids){long count=catalog.release(owner,reference,"knowledge",id);if(count>=0)remaining=count;}return remaining<=0;}
    });
    try{
      Exception failure=null;try{handler.execute(job,f.cancellation(job));}catch(Exception e){failure=e;}
      assertThat(failure!=null).as("Go error/retry decision: %s",failure).isEqualTo(!oracle.path("error").asText().isEmpty());
      assertThat(failure instanceof TaskFailure e&&!e.retryable()).as("Go SkipRetry decision").isEqualTo(oracle.path("skip_retry").asBoolean());
      assertThat((JsonNode)indexCalls).isEqualTo(oracle.path("index_calls"));assertThat((JsonNode)graphCalls).isEqualTo(oracle.path("graph_calls"));assertThat((JsonNode)fileCalls).isEqualTo(oracle.path("file_calls"));
      assertThat((JsonNode)json.valueToTree(f.jdbc.queryForList("SELECT id,tenant_id,knowledge_base_id,parse_status,storage_size,deleted_at IS NOT NULL AS deleted FROM knowledges ORDER BY id"))).isEqualTo(oracle.path("documents"));
      assertThat((JsonNode)json.valueToTree(f.jdbc.queryForList("SELECT id,tenant_id,knowledge_base_id,deleted_at IS NOT NULL AS deleted FROM chunks ORDER BY id"))).isEqualTo(oracle.path("chunks"));
      assertThat((JsonNode)json.valueToTree(f.jdbc.queryForList("SELECT id,storage_used FROM tenants ORDER BY id"))).isEqualTo(oracle.path("storage"));
      assertThat((JsonNode)json.valueToTree(f.jdbc.queryForList("SELECT knowledge_id,chunk_id,source_id FROM lite_embeddings ORDER BY source_id"))).isEqualTo(oracle.path("surviving_indexes"));
      var counts=f.jdbc.execute((ConnectionCallback<ObjectNode>)c->{new SQLiteVectorExtension(Path.of(System.getProperty("weknora.sqlite.vec.extension"))).load(c);var out=json.createObjectNode();for(String table:List.of("lite_embeddings","vec_embeddings_2","lite_embeddings_fts"))try(var st=c.createStatement();var rs=st.executeQuery("SELECT COUNT(*) FROM "+table)){rs.next();out.put(table,rs.getInt(1));}return out;});assertThat((JsonNode)counts).isEqualTo(oracle.path("physical"));
      var files=json.createObjectNode();for(var e:paths().entrySet())files.put(e.getKey(),Files.exists(root.resolve(e.getValue())));assertThat((JsonNode)files).isEqualTo(oracle.path("files"));
      assertThat((JsonNode)json.valueToTree(f.jdbc.queryForList("SELECT knowledge_id,tag_id FROM knowledge_tag_relations ORDER BY knowledge_id"))).isEqualTo(oracle.path("relations").isNull()?json.createArrayNode():oracle.path("relations"));
      assertThat((JsonNode)json.valueToTree(f.jdbc.queryForList("SELECT handle,state FROM resources ORDER BY handle"))).isEqualTo(oracle.path("resources").isNull()?json.createArrayNode():oracle.path("resources"));assertThat((JsonNode)json.valueToTree(f.jdbc.queryForList("SELECT tenant_id,owner_type,owner_id,relation FROM resource_bindings ORDER BY owner_id"))).isEqualTo(oracle.path("resource_bindings").isNull()?json.createArrayNode():oracle.path("resource_bindings"));
      var actual=json.valueToTree(f.jdbc.queryForList("SELECT tenant_id,actor_user_id,actor_role,action,target_type,target_id,scope_type,scope_id,outcome,details FROM audit_logs ORDER BY id"));var expected=oracle.path("audits").isNull()?json.createArrayNode():oracle.path("audits").deepCopy();
      for(var row:actual){var detail=(ObjectNode)json.readTree(row.path("details").asText());assertThat(detail.path("task_id").asText()).isEqualTo(job.id());detail.remove("task_id");((ObjectNode)row).set("details",detail);}for(var row:expected)((ObjectNode)row).set("details",json.readTree(row.path("details").asText()));assertThat(actual).isEqualTo(expected);
      assertThat(f.jdbc.queryForList("SELECT queue,max_attempts,timeout_millis FROM java_task_jobs WHERE id=?",job.id())).containsExactly(Map.of("queue","low","max_attempts",4,"timeout_millis",7200000));
    }finally{models.close();}
  }
  DocumentProcessingCompatibilityTest fixture(int no,String name)throws Exception {
    var f=new DocumentProcessingCompatibilityTest();f.temp=temp;f.database(no,true);f.jdbc.update("UPDATE tenants SET storage_used=CASE WHEN id=1 THEN 500 ELSE 700 END");f.jdbc.update("UPDATE knowledge_bases SET embedding_model_id='embed',summary_model_id='',indexing_strategy=NULL");
    f.jdbc.update("INSERT INTO knowledge_bases(id,tenant_id,name,type,embedding_model_id,summary_model_id) VALUES('other-kb',1,'Other','document','embed',''),('peer-kb',2,'Peer','document','embed','')");
    f.jdbc.update("INSERT INTO models(id,tenant_id,name,type,source,parameters,status) VALUES('embed',1,'unknown','Embedding','remote',?,'active')",json.writeValueAsBytes(json.createObjectNode().put("provider","generic").put("base_url","http://127.0.0.1:1/v1").set("embedding_parameters",json.createObjectNode().put("dimension",2))));
    var entries=new ArrayList<IndexEntry>();var vectors=new HashMap<String,float[]>();for(String id:List.of("doc","other","peer")){int owner=id.equals("peer")?2:1;String kb=id.equals("doc")?"kb":id.equals("other")?"other-kb":"peer-kb";
      f.jdbc.update("INSERT INTO knowledges(id,tenant_id,knowledge_base_id,type,source,title,parse_status,enable_status,embedding_model_id,file_path,storage_size,created_at,updated_at) VALUES(?,?,?,'file','manual',?,'completed','enabled','embed',?,100,?,?)",id,owner,kb,id,"local://"+paths().get(id),"2026-01-01 00:00:00+00:00","2026-01-01 00:00:00+00:00");
      f.jdbc.update("INSERT INTO chunks(id,tenant_id,knowledge_base_id,knowledge_id,chunk_index,start_at,end_at,content,chunk_type,image_info) VALUES(?,?,?,?,0,0,0,'body','text',?)",id+"-chunk",owner,kb,id,id.equals("doc")?"[{\"url\":\"local://1/image.png\"},{\"url\":\"local://1/image.png\"}]":"");
      for(String source:List.of(id+"-chunk",id+"-question")){entries.add(new IndexEntry(source,0,id+"-chunk",id,kb,"","actual index",true));vectors.put(source,new float[]{1,0});}
    }
    f.index.prepare(Set.of(2));new org.springframework.transaction.support.TransactionTemplate(f.manager).executeWithoutResult(s->f.jdbc.execute((ConnectionCallback<Void>)c->{f.index.batchSave(c,entries,vectors);return null;}));
    f.jdbc.update("INSERT INTO knowledge_tags(id,seq_id,tenant_id,knowledge_base_id,name) VALUES('tag',1,1,'kb','Tag')");f.jdbc.update("INSERT INTO knowledge_tag_relations(knowledge_id,tag_id) VALUES('doc','tag')");
    if(name.equals("moving-copy"))f.jdbc.update("UPDATE knowledges SET metadata=? WHERE id='doc'","{\"_knowledge_transfer\":{\"operation\":\"copy\",\"phase\":\"moving\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    if(name.equals("moving-phase-only"))f.jdbc.update("UPDATE knowledges SET metadata=? WHERE id='doc'","{\"_knowledge_transfer\":{\"phase\":\"moving\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    if(name.equals("moving-casefold"))f.jdbc.update("UPDATE knowledges SET metadata=? WHERE id='doc'","{\"_knowledge_transfer\":{\"OPERATION\":\"move\",\"PHASE\":\"moving\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    if(name.equals("moving-null-operation"))f.jdbc.update("UPDATE knowledges SET metadata=? WHERE id='doc'","{\"_knowledge_transfer\":{\"operation\":null,\"phase\":\"moving\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    if(name.equals("moving-duplicate-null"))f.jdbc.update("UPDATE knowledges SET metadata=? WHERE id='doc'","{\"_knowledge_transfer\":{\"operation\":\"move\",\"operation\":null,\"phase\":\"moving\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    if(name.equals("moving-invalid-operation"))f.jdbc.update("UPDATE knowledges SET metadata=? WHERE id='doc'","{\"_knowledge_transfer\":{\"operation\":4,\"operation\":\"move\",\"phase\":\"moving\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    if(name.equals("moving-invalid-metadata"))f.jdbc.update("UPDATE knowledges SET metadata=? WHERE id='doc'","{\"_knowledge_transfer\":4}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    if(name.equals("moving-root-null"))f.jdbc.update("UPDATE knowledges SET metadata=? WHERE id='doc'","null".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    if(name.equals("moving-root-array"))f.jdbc.update("UPDATE knowledges SET metadata=? WHERE id='doc'","[]".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    if(name.equals("moving-outer-casefold"))f.jdbc.update("UPDATE knowledges SET metadata=? WHERE id='doc'","{\"_KNOWLEDGE_TRANSFER\":{\"operation\":\"move\",\"phase\":\"moving\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    switch(name){case "changed-kb-owner"->f.jdbc.update("UPDATE knowledge_bases SET tenant_id=2 WHERE id='kb'");case "deleted-kb"->f.jdbc.update("UPDATE knowledge_bases SET deleted_at=CURRENT_TIMESTAMP WHERE id='kb'");case "deleted-document"->f.jdbc.update("UPDATE knowledges SET deleted_at=CURRENT_TIMESTAMP WHERE id='doc'");case "moving"->f.jdbc.update("UPDATE knowledges SET metadata=? WHERE id='doc'",json.writeValueAsBytes(json.createObjectNode().set("_knowledge_transfer",json.createObjectNode().put("operation","move").put("phase","moving"))));case "model-missing"->f.jdbc.update("DELETE FROM models");case "model-foreign"->f.jdbc.update("UPDATE models SET tenant_id=2");case "model-type"->f.jdbc.update("UPDATE models SET type='KnowledgeQA'");case "empty-document-model"->f.jdbc.update("UPDATE knowledges SET embedding_model_id='' WHERE id='doc'");case "storage-underflow"->f.jdbc.update("UPDATE tenants SET storage_used=10 WHERE id=1");}
    return f;
  }
  static Map<String,String> paths(){return Map.of("doc","1/doc/file.txt","other","1/other/file.txt","peer","2/peer/file.txt","image","1/image.png");}
  static void seedFiles(Path root)throws Exception{for(String path:paths().values()){Files.createDirectories(root.resolve(path).getParent());Files.writeString(root.resolve(path),"isolated bytes");}}
  @Test void staleLeaseAndChangedBindingsPreventMutation()throws Exception {
    var f=fixture(1001,"normal");var p=json.createObjectNode().put("tenant_id",1).put("knowledge_base_id","kb").set("knowledge_ids",json.createArrayNode().add("doc"));f.tasks.enqueue(1,"knowledge:list_delete",null,p,4,Duration.ZERO,"low",Duration.ofHours(2));var job=f.tasks.claim("first",Duration.ofMinutes(5),List.of("low")).orElseThrow();var repository=new KnowledgeListDeleteRepository(f.jdbc,json,f.manager,Clock.systemUTC());var plan=repository.plan(job,List.of("doc"),"kb");
    f.jdbc.update("UPDATE java_task_jobs SET lease_owner='successor' WHERE id=?",job.id());assertThatThrownBy(()->repository.mark(job,plan)).isInstanceOf(CancellationException.class);f.jdbc.update("UPDATE java_task_jobs SET lease_owner='first' WHERE id=?",job.id());f.jdbc.update("UPDATE knowledges SET knowledge_base_id='other-kb' WHERE id='doc'");assertThatThrownBy(()->repository.mark(job,plan)).isInstanceOf(CancellationException.class);assertThat(f.jdbc.queryForObject("SELECT parse_status FROM knowledges WHERE id='doc'",String.class)).isEqualTo("completed");assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM lite_embeddings",Integer.class)).isEqualTo(6);
  }
}
