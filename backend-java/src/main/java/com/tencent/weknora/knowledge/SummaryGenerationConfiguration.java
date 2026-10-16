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
public class SummaryGenerationConfiguration {
  @Bean SummaryGenerationRepository summaryGenerationRepository(JdbcTemplate jdbc,ObjectMapper json,KnowledgeRepository knowledge,PlatformTransactionManager tx){return new SummaryGenerationRepository(jdbc,json,knowledge,tx,Clock.systemUTC());}
  @Bean SummaryGenerationText summaryGenerationText(ObjectMapper json,Environment env){return new SummaryGenerationText(json,env.getProperty("weknora.knowledge.summary.prompt"),env.getProperty("weknora.knowledge.summary.max-input-chars",Integer.class,8192),env.getProperty("weknora.knowledge.summary.max-tokens",Integer.class,1024));}
  @Bean SummaryGenerationHandler summaryGenerationHandler(SummaryGenerationRepository repository,ObjectMapper json,ModelClientFactory models,DocumentProcessingHandler.Indexes indexes,KnowledgeProcessingSpanTracker tracker,SummaryGenerationText text,KnowledgeFileUpload.Enqueuer tasks,KnowledgeBaseProfileHandler profiles){
    var adapter=new SummaryGenerationHandler.Models(){private AuthContext auth(long owner){return new AuthContext("system-task",owner,"viewer",false,null,false,Set.of(),Set.of());}public ChatModel chat(long owner,String id){return models.chat(auth(owner),id);}public EmbeddingModel embedding(long owner,String id){return models.embedding(auth(owner),id);}};
    return new SummaryGenerationHandler(repository,json,adapter,indexes,tracker,text,tasks).withProfiles(profiles);
  }
}
