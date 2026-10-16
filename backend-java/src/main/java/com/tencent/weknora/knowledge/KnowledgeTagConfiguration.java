package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@ConditionalOnProperty(name="weknora.knowledge.processing.enabled",havingValue="true")
public class KnowledgeTagConfiguration {
 @Bean KnowledgeTagRepository knowledgeTagRepository(JdbcTemplate jdbc,ObjectMapper json,PlatformTransactionManager transactions,IndexDeleteEnqueuer cleanup,KnowledgeListDeleteEnqueuer documents,Environment env){return new KnowledgeTagRepository(jdbc,json,transactions,(owner,base,ids,engines)->cleanup.enqueue(owner,base,ids,engines,null),env.getProperty("RETRIEVE_DRIVER","")).withDocuments((caller,owner,base,ids)->documents.enqueue(caller,owner,base,ids,null));}
}
