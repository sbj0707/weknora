package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.weknora.model.ModelClientFactory;
import com.tencent.weknora.security.AuthContext;
import com.tencent.weknora.task.TaskFailure;
import com.tencent.weknora.task.TaskRepository;
import java.time.Clock;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@ConditionalOnProperty(name="weknora.knowledge.processing.enabled",havingValue="true")
public class IndexDeleteConfiguration {
  @Bean IndexDeleteEnqueuer indexDeleteEnqueuer(TaskRepository tasks,ObjectMapper json){return new IndexDeleteEnqueuer(tasks,json);}
  @Bean IndexDeleteRepository indexDeleteRepository(JdbcTemplate jdbc,PlatformTransactionManager manager){return new IndexDeleteRepository(jdbc,manager,Clock.systemUTC());}
  @Bean IndexDeleteHandler indexDeleteHandler(IndexDeleteRepository repository,ObjectMapper json,ModelClientFactory models,DocumentProcessingHandler.Indexes indexes,JdbcTemplate jdbc){
    return new IndexDeleteHandler(repository,json,(owner,id)->models.embeddingForCleanup(new AuthContext("system-task",owner,"viewer",false,null,false,Set.of(),Set.of()),id).dimensions(),(owner,payload)->{
      if(payload.hasNonNull("vector_store_id")&&!payload.path("vector_store_id").asText().isEmpty())throw new TaskFailure("Native bound vector store cleanup is not migrated",false);
      String database=jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>)c->c.getMetaData().getDatabaseProductName());
      String supported=database.equals("PostgreSQL")?"postgres":"sqlite";
      var selected=new LinkedHashSet<String>();
      for(var engine:payload.path("effective_engines")){
        String name=engine.path("retriever_engine_type").asText();
        if(!name.equals(supported))throw new TaskFailure("Native index cleanup engine is not migrated: "+name,false);
        selected.add(name);
      }
      if(selected.isEmpty())return List.of();
      var snapshot=json.createObjectNode();
      var writer=indexes.resolve(owner,snapshot);
      return List.of((connection,ids,dimension,kbType)->writer.deleteByChunk(connection,ids));
    });
  }
}
