package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.weknora.model.ModelClientFactory;
import com.tencent.weknora.security.AuthContext;
import com.tencent.weknora.vectorstore.VectorStoreRegistry;
import java.time.Clock;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@ConditionalOnProperty(name="weknora.knowledge.processing.enabled",havingValue="true")
public class KnowledgeBaseDeleteConfiguration {
  @Bean KnowledgeBaseTaskScrubber knowledgeBaseTaskScrubber(JdbcTemplate jdbc,ObjectMapper json) {
    return new KnowledgeBaseTaskScrubber(jdbc,json,Clock.systemUTC());
  }
  @Bean KnowledgeBaseDeleteRepository knowledgeBaseDeleteRepository(JdbcTemplate jdbc,ObjectMapper json,PlatformTransactionManager manager) {
    return new KnowledgeBaseDeleteRepository(jdbc,json,manager,Clock.systemUTC());
  }
  @Bean KnowledgeBaseDeleteResources knowledgeBaseDeleteResources(JdbcTemplate jdbc,ObjectMapper json,ModelClientFactory models,
      VectorStoreRegistry stores,GraphExtractionStore graph,KnowledgeBaseTaskScrubber tasks,Environment env) {
    return new KnowledgeBaseDeleteResources(jdbc,json,(owner,id)->models.embeddingForCleanup(
        new AuthContext("system-task",owner,"viewer",false,null,false,Set.of(),Set.of()),id).dimensions(),stores,graph,tasks,env,Clock.systemUTC());
  }
  @Bean KnowledgeBaseDeleteAdmission knowledgeBaseDeleteAdmission(JdbcTemplate jdbc,ObjectMapper json,KnowledgeBaseCodec codec,
      KnowledgeBaseTaskScrubber scrubber,com.tencent.weknora.task.TaskRepository tasks,
      org.springframework.beans.factory.ObjectProvider<KnowledgeBaseDeleteAdmission.SourceSchedules> schedules,Environment env) {
    return new KnowledgeBaseDeleteAdmission(jdbc,json,codec,scrubber,tasks,schedules.getIfAvailable(),env,Clock.systemUTC());
  }
  @Bean KnowledgeBaseDeleteHandler knowledgeBaseDeleteHandler(KnowledgeBaseDeleteRepository repository,KnowledgeBaseDeleteResources resources,ObjectMapper json,JdbcTemplate jdbc) {
    return new KnowledgeBaseDeleteHandler(repository,resources,json,jdbc);
  }
}
