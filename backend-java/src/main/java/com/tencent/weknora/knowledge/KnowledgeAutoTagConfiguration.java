package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.weknora.model.ModelClientFactory;
import com.tencent.weknora.security.AuthContext;
import java.time.Clock;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@ConditionalOnProperty(name="weknora.knowledge.processing.enabled",havingValue="true")
public class KnowledgeAutoTagConfiguration {
  @Bean KnowledgeAutoTagRepository knowledgeAutoTagRepository(JdbcTemplate jdbc,ObjectMapper json,KnowledgeRepository knowledge,PlatformTransactionManager tx){return new KnowledgeAutoTagRepository(jdbc,json,knowledge,tx,Clock.systemUTC());}
  @Bean KnowledgeAutoTagHandler knowledgeAutoTagHandler(KnowledgeAutoTagRepository repository,ObjectMapper json,ModelClientFactory models,KnowledgeProcessingSpanTracker tracker,KnowledgeFileUpload.Enqueuer tasks){return new KnowledgeAutoTagHandler(repository,json,(owner,id)->models.chat(new AuthContext("system-task",owner,"viewer",false,null,false,Set.of(),Set.of()),id),tracker,tasks);}
}
