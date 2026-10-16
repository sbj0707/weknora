package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.model.ModelClientFactory;
import com.tencent.weknora.model.client.CallContext;
import com.tencent.weknora.security.AuthContext;
import com.tencent.weknora.storage.StorageResolver;
import com.tencent.weknora.task.*;
import java.time.Clock;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@ConditionalOnProperty(name="weknora.knowledge.processing.enabled",havingValue="true")
public class KnowledgeListDeleteConfiguration {
  @Bean KnowledgeListDeleteEnqueuer knowledgeListDeleteEnqueuer(TaskRepository tasks,ObjectMapper json,JdbcTemplate jdbc){return new KnowledgeListDeleteEnqueuer(tasks,json).withAuditKeys(jdbc);}
  @Bean KnowledgeListDeleteRepository knowledgeListDeleteRepository(JdbcTemplate jdbc,ObjectMapper json,PlatformTransactionManager tx){return new KnowledgeListDeleteRepository(jdbc,json,tx,Clock.systemUTC());}
  @Bean KnowledgeListDeleteHandler knowledgeListDeleteHandler(KnowledgeListDeleteRepository repository,ObjectMapper json,ModelClientFactory models,DocumentProcessingHandler.Indexes indexes,GraphExtractionStore graph,StorageResolver storage,TaskRepository tasks,KnowledgeBaseProfileHandler profiles,JdbcTemplate jdbc,Environment env){
    return new KnowledgeListDeleteHandler(repository,json,(owner,id)->models.embeddingForCleanup(new AuthContext("system-task",owner,"viewer",false,null,false,Set.of(),Set.of()),id).dimensions(),new KnowledgeListDeleteHandler.Effects(){
      public void prepare(long owner,KnowledgeListDeleteRepository.Plan plan)throws Exception {
        storage.forKnowledgeBase(owner,plan.base().path("id").asText());
        boolean vector=plan.documents().stream().anyMatch(d->!KnowledgeUploadForms.goTrim(KnowledgeListDeleteRepository.text(d,"embedding_model_id")).isEmpty());
        if(vector){if(!plan.base().path("vector_store_id").asText().isEmpty())throw new TaskFailure("Native bound vector store document cleanup is not migrated",false);engines(owner);}
      }
      private Set<String> engines(long owner)throws Exception {
        String database=jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>)c->c.getMetaData().getDatabaseProductName());String supported=database.equals("PostgreSQL")?"postgres":"sqlite";
        var rows=jdbc.queryForList("SELECT retriever_engines FROM tenants WHERE id=?",owner);if(rows.isEmpty())throw new TaskFailure("tenant not found",true);Object raw=rows.getFirst().get("retriever_engines");var selected=new LinkedHashSet<String>();
        if(raw!=null&&(database.equals("PostgreSQL")||raw instanceof byte[])){JsonNode node=raw instanceof byte[] b?json.readTree(b):json.readTree(raw.toString());var configured=node.isArray()?node:node.path("engines");if(configured.isArray())for(var engine:configured)selected.add(engine.path("retriever_engine_type").asText());}
        if(selected.isEmpty())for(String driver:env.getProperty("RETRIEVE_DRIVER","").split(",")){driver=driver.trim();if(Set.of("sqlite","postgres","elasticsearch_v7","elasticsearch_v8","qdrant","milvus","weaviate","doris","tencent_vectordb","opensearch").contains(driver))selected.add(driver);}
        for(String engine:selected)if(!engine.equals(supported))throw new TaskFailure("Native document cleanup engine is not migrated: "+engine,false);return selected;
      }
      public List<KnowledgeListDeleteRepository.IndexDeletion> indexes(long owner,KnowledgeListDeleteRepository.Plan plan)throws Exception {if(engines(owner).isEmpty())return List.of();var writer=indexes.resolve(owner,plan.base());return List.of((connection,ids,dimension,type)->writer.deleteByKnowledge(connection,ids));}
      public void graph(TaskRecord job,KnowledgeListDeleteRepository.Plan plan,Runnable guard){try(var call=CallContext.timeout(java.time.Duration.ofHours(2))){for(String id:plan.ids()){guard.run();graph.delete(plan.base().path("id").asText(),id,call,guard);}}}
      public void file(long owner,KnowledgeListDeleteRepository.Plan plan,String reference)throws Exception {storage.forKnowledgeBase(owner,plan.base().path("id").asText()).files().delete(reference);}
      public boolean release(long owner,String reference,List<String> ids){long remaining=-1;for(String id:ids){long count=storage.catalog().release(owner,reference,"knowledge",id);if(count>=0)remaining=count;}return remaining<=0;}
      public void dequeue(long owner,String id){tasks.cancelKnowledge(owner,id);}
      public void profile(long owner,JsonNode base,JsonNode source){profiles.enqueue(owner,base,false,source);}
    });
  }
}
