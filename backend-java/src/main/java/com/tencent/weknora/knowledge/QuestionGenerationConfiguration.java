package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.model.*;
import com.tencent.weknora.model.client.*;
import com.tencent.weknora.security.*;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@ConditionalOnProperty(name="weknora.knowledge.processing.enabled",havingValue="true")
public class QuestionGenerationConfiguration {
  @Bean QuestionGenerationRepository questionGenerationRepository(JdbcTemplate jdbc,ObjectMapper json,KnowledgeRepository knowledge,PlatformTransactionManager tx){return new QuestionGenerationRepository(jdbc,json,knowledge,tx,Clock.systemUTC());}
  @Bean QuestionGenerationText questionGenerationText(ObjectMapper json,Environment env){return new QuestionGenerationText(json,env.getProperty("weknora.knowledge.question.prompt"));}
  @Bean QuestionGenerationFanout questionGenerationFanout(JdbcTemplate jdbc,ObjectMapper json,QuestionGenerationRepository repository,KnowledgeFileUpload.Enqueuer tasks,PlatformTransactionManager tx,KnowledgeProcessingSpanTracker tracker,GraphExtractionFanout graph,org.springframework.beans.factory.ObjectProvider<KnowledgeAutoTagHandler> autoTag){return new QuestionGenerationFanout(jdbc,json,repository,tasks,tx,Clock.systemUTC(),tracker,graph,autoTag.getIfAvailable());}
  @Bean QuestionGenerationHandler questionGenerationHandler(QuestionGenerationRepository repository,ObjectMapper json,ModelClientFactory models,DocumentProcessingHandler.Indexes indexes,KnowledgeProcessingSpanTracker tracker,QuestionGenerationText text){
    var adapter=new SummaryGenerationHandler.Models(){private AuthContext auth(long owner){return new AuthContext("system-task",owner,"viewer",false,null,false,Set.of(),Set.of());}public ChatModel chat(long owner,String id){return models.chat(auth(owner),id);}public EmbeddingModel embedding(long owner,String id){return models.embedding(auth(owner),id);}};
    return new QuestionGenerationHandler(repository,json,adapter,indexes,tracker,text);
  }
}
