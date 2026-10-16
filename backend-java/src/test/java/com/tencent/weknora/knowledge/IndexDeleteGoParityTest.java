package com.tencent.weknora.knowledge;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.model.*;
import com.tencent.weknora.retrieval.*;
import com.tencent.weknora.security.*;
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

/** Actual production Go worker + SQLite metadata/vec/FTS, with corrected owner scope. */
class IndexDeleteGoParityTest {
  @TempDir Path temp;
  final ObjectMapper json=new ObjectMapper();
  @TestFactory Stream<DynamicTest> actualGoIndexDeletion()throws Exception{
    JsonNode cases;try(var in=getClass().getResourceAsStream("/go-index-delete.json")){cases=json.readTree(in);}
    assertThat(cases.size()).isEqualTo(27);
    var sequence=new AtomicInteger();
    return StreamSupport.stream(cases.spliterator(),false).map(c->DynamicTest.dynamicTest(c.path("name").asText(),()->compare(c,sequence.incrementAndGet())));
  }
  void compare(JsonNode oracle,int sequence)throws Exception{
    String name=oracle.path("name").asText();var f=fixture(sequence,name);
    var payload=oracle.path("payload");
    var task=f.tasks.enqueue(1,"index:delete",null,payload,11,Duration.ZERO,"low",Duration.ofHours(1));
    var job=f.tasks.claim("cleanup-worker",Duration.ofMinutes(5),List.of("low")).orElseThrow();
    var calls=json.createArrayNode();
    var environment=new MockEnvironment().withProperty("SSRF_WHITELIST","127.0.0.1").withProperty("SSRF_DNS_WHITELIST_ONLY","true");
    var models=new ModelClientFactory(f.jdbc,json,new GoSecretCodec(""),new ModelCatalogRuntime(f.jdbc,"","/nonexistent"),new ModelEgressPolicy(f.jdbc,json,environment),environment);
    try{
      var handler=new IndexDeleteHandler(new IndexDeleteRepository(f.jdbc,f.manager,Clock.systemUTC()),json,(owner,id)->models.embeddingForCleanup(new AuthContext("system-task",owner,"viewer",false,null,false,Set.of(),Set.of()),id).dimensions(),(owner,p)->{
        var engines=new LinkedHashSet<String>();for(var engine:p.path("effective_engines")){String type=engine.path("retriever_engine_type").asText();if(!type.equals("sqlite"))throw new TaskFailure("repository of type "+type+" not found",true);engines.add(type);}
        if(engines.isEmpty())return List.of();
        return List.of((connection,ids,dimension,kbType)->{
          calls.add(json.createObjectNode().put("dimension",dimension).put("kb_type",kbType).set("ids",json.valueToTree(ids)));
          if(name.equals("delete-error")||name.equals("second-batch-error")&&calls.size()==2)throw new java.sql.SQLException("controlled index cleanup failure");
          f.index.deleteByChunk(connection,ids);
        });
      });
      Exception error=null;try{handler.execute(job,f.cancellation(job));}catch(Exception e){error=e;}
      assertThat(error!=null).as("Go failure/retry decision: %s",error).isEqualTo(!oracle.path("error").asText().isEmpty());
      assertThat((JsonNode)calls).as("exact scoped 100-ID batches, duplicates and dimensions").isEqualTo(oracle.path("calls"));
      assertThat((JsonNode)json.valueToTree(f.jdbc.queryForList("SELECT source_id,chunk_id,knowledge_id,knowledge_base_id FROM lite_embeddings ORDER BY source_id"))).isEqualTo(oracle.path("remaining"));
      var counts=f.jdbc.execute((ConnectionCallback<ObjectNode>)connection->{
        new SQLiteVectorExtension(Path.of(System.getProperty("weknora.sqlite.vec.extension"))).load(connection);
        var out=json.createObjectNode();String[] tables={"lite_embeddings","vec_embeddings_2","lite_embeddings_fts"},keys={"Metadata","Vectors","Keywords"};
        for(int i=0;i<tables.length;i++)try(var statement=connection.createStatement();var rs=statement.executeQuery("SELECT COUNT(*) FROM "+tables[i])){rs.next();out.put(keys[i],rs.getInt(1));}
        return out;
      });
      assertThat((JsonNode)counts).as("physical metadata + actual vectors + actual FTS rows").isEqualTo(oracle.path("counts"));
      assertThat(f.jdbc.queryForList("SELECT queue,max_attempts,timeout_millis FROM java_task_jobs WHERE id=?",task.id())).containsExactly(Map.of("queue","low","max_attempts",11,"timeout_millis",3600000));
    }finally{models.close();}
  }
  DocumentProcessingCompatibilityTest fixture(int sequence,String name)throws Exception{
    var f=new DocumentProcessingCompatibilityTest();f.temp=temp;f.database(sequence,true);
    f.jdbc.update("INSERT INTO knowledge_bases(id,tenant_id,name,type,embedding_model_id,summary_model_id) VALUES('peer-kb',2,'Peer','document','embed',''),('other-kb',1,'Other','document','embed','')");
    f.jdbc.update("INSERT INTO models(id,tenant_id,name,type,source,parameters,status) VALUES('embed',1,'unknown','Embedding','remote',?,'active')",json.writeValueAsBytes(json.createObjectNode().put("provider","generic").put("base_url","http://127.0.0.1:1/v1").set("embedding_parameters",json.createObjectNode().put("dimension",2))));
    if(name.equals("model-missing"))f.jdbc.update("DELETE FROM models");
    if(name.equals("model-foreign"))f.jdbc.update("UPDATE models SET tenant_id=2");
    if(name.equals("model-type"))f.jdbc.update("UPDATE models SET type='KnowledgeQA'");
    var ids=new ArrayList<String>();if(Set.of("many","second-batch-error").contains(name))for(int i=0;i<205;i++)ids.add(String.format("chunk-%03d",i));else ids.add("chunk");ids.add("peer");
    var entries=new ArrayList<IndexEntry>();var vectors=new HashMap<String,float[]>();
    for(String id:ids){boolean peer=id.equals("peer");String kb=peer?"peer-kb":"kb",doc=peer?"peer-doc":"doc";int owner=peer?2:1;
      f.jdbc.update("INSERT INTO chunks(id,tenant_id,knowledge_base_id,knowledge_id,chunk_index,start_at,end_at,content,chunk_type) VALUES(?,?,?,?,0,0,0,'body','text')",id,owner,kb,doc);
      for(String source:List.of(id,id+"-question")){entries.add(new IndexEntry(source,0,id,doc,kb,"","actual index",true));vectors.put(source,new float[]{1,0});}
    }
    f.index.prepare(Set.of(2));new org.springframework.transaction.support.TransactionTemplate(f.manager).executeWithoutResult(s->f.jdbc.execute((ConnectionCallback<Void>)c->{f.index.batchSave(c,entries,vectors);return null;}));
    switch(name){
      case "same-owner-other-kb"->f.jdbc.update("UPDATE chunks SET knowledge_base_id='other-kb' WHERE id='chunk'");
      case "soft-deleted-chunk"->f.jdbc.update("UPDATE chunks SET deleted_at=CURRENT_TIMESTAMP WHERE id='chunk'");
      case "soft-deleted-kb"->{f.jdbc.update("UPDATE chunks SET deleted_at=CURRENT_TIMESTAMP WHERE id='chunk'");f.jdbc.update("UPDATE knowledge_bases SET deleted_at=CURRENT_TIMESTAMP WHERE id='kb'");}
      case "changed-kb-owner"->f.jdbc.update("UPDATE knowledge_bases SET tenant_id=2 WHERE id='kb'");
    }
    return f;
  }
  @Test void lostLeaseAndCancellationCannotDeleteExistingPhysicalIndexes()throws Exception{
    var f=fixture(1001,"normal");
    var payload=json.createObjectNode().put("tenant_id",1).put("knowledge_base_id","kb").put("embedding_model_id","embed").set("chunk_ids",json.createArrayNode().add("chunk"));
    f.tasks.enqueue(1,"index:delete",null,payload,11,Duration.ZERO,"low",Duration.ofHours(1));var job=f.tasks.claim("first",Duration.ofMinutes(5),List.of("low")).orElseThrow();
    var repo=new IndexDeleteRepository(f.jdbc,f.manager,Clock.systemUTC());
    f.jdbc.update("UPDATE java_task_jobs SET lease_owner='successor' WHERE id=?",job.id());
    assertThatThrownBy(()->repo.deleteBatch(job,"kb",List.of("chunk"),2,"document",List.of((c,ids,d,t)->f.index.deleteByChunk(c,ids)))).isInstanceOf(CancellationException.class);
    f.jdbc.update("UPDATE java_task_jobs SET lease_owner='first',cancel_requested=true WHERE id=?",job.id());
    assertThatThrownBy(()->repo.deleteBatch(job,"kb",List.of("chunk"),2,"document",List.of((c,ids,d,t)->f.index.deleteByChunk(c,ids)))).isInstanceOf(CancellationException.class);
    assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM lite_embeddings",Integer.class)).isEqualTo(4);
  }
}
