package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.weknora.model.ModelClientFactory;
import com.tencent.weknora.security.AuthContext;
import com.tencent.weknora.task.TaskRepository;
import java.time.Clock;import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@ConditionalOnProperty(name="weknora.knowledge.processing.enabled",havingValue="true")
public class KnowledgeBaseProfileConfiguration {
 @Bean KnowledgeBaseProfileText knowledgeBaseProfileText(ObjectMapper json,Environment env,GoKnowledgePrompts prompts){return new KnowledgeBaseProfileText(json,prompts.selected("generate_kb_description_prompt_id",env.getProperty("weknora.knowledge.profile.prompt")));}
 @Bean KnowledgeBaseProfileRepository knowledgeBaseProfileRepository(JdbcTemplate jdbc,ObjectMapper json,KnowledgeRepository knowledge,PlatformTransactionManager tx){return new KnowledgeBaseProfileRepository(jdbc,json,knowledge,tx,Clock.systemUTC());}
 @Bean KnowledgeBaseProfileHandler knowledgeBaseProfileHandler(KnowledgeBaseProfileRepository repository,ObjectMapper json,ModelClientFactory models,KnowledgeBaseProfileText text,TaskRepository tasks,KnowledgeProcessingLanguage language){return new KnowledgeBaseProfileHandler(repository,json,(owner,id)->models.chat(new AuthContext("system-task",owner,"viewer",false,null,false,Set.of(),Set.of()),id),text,tasks,Clock.systemUTC()).withLanguage(language);}
 @Bean KnowledgeBaseProfileGenerator knowledgeBaseProfileGenerator(KnowledgeBaseProfileRepository repository,ObjectMapper json,ModelClientFactory models,KnowledgeBaseProfileText text){return new KnowledgeBaseProfileGenerator(repository,json,(owner,id)->models.chat(new AuthContext("system-task",owner,"viewer",false,null,false,Set.of(),Set.of()),id),text,Clock.systemUTC());}
}

