package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.model.*;
import com.tencent.weknora.security.*;
import java.time.*;
import java.util.*;
import org.neo4j.driver.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@org.springframework.boot.autoconfigure.EnableAutoConfiguration(exclude=org.springframework.boot.autoconfigure.neo4j.Neo4jAutoConfiguration.class)
public class GraphExtractionConfiguration {
  @Bean(destroyMethod="close") public Neo4jGraphRepository nativeGraphStore(ObjectMapper json,Environment env){Driver driver=null;if("true".equalsIgnoreCase(env.getProperty("NEO4J_ENABLE",""))){String uri=env.getProperty("NEO4J_URI","bolt://neo4j:7687");String user=env.getProperty("NEO4J_USERNAME","neo4j"),password=env.getProperty("NEO4J_PASSWORD","");driver=GraphDatabase.driver(uri,AuthTokens.basic(user,password),Config.builder().withConnectionTimeout(10,java.util.concurrent.TimeUnit.SECONDS).withMaxTransactionRetryTime(30,java.util.concurrent.TimeUnit.SECONDS).build());try{driver.verifyConnectivity();try(var session=driver.session()){session.run("RETURN apoc.version() AS version").single();}}catch(RuntimeException e){driver.close();throw new IllegalStateException("Native Neo4j/APOC initialization failed",e);}}return new Neo4jGraphRepository(driver,json);}
  @Bean @ConditionalOnProperty(name="weknora.knowledge.processing.enabled",havingValue="true") GraphExtractionFanout graphExtractionFanout(Neo4jGraphRepository graph,KnowledgeFileUpload.Enqueuer tasks,ObjectMapper json){return new GraphExtractionFanout(graph,tasks,json);}
  @Bean @ConditionalOnProperty(name="weknora.knowledge.processing.enabled",havingValue="true") GraphExtractionRepository graphExtractionRepository(JdbcTemplate jdbc,ObjectMapper json,KnowledgeRepository knowledge,PlatformTransactionManager tx){return new GraphExtractionRepository(jdbc,json,knowledge,tx,Clock.systemUTC());}
  @Bean @ConditionalOnProperty(name="weknora.knowledge.processing.enabled",havingValue="true") GraphExtractionText graphExtractionText(ObjectMapper json,Environment env){return new GraphExtractionText(json,env.getProperty("weknora.knowledge.graph.prompt"));}
  @Bean @ConditionalOnProperty(name="weknora.knowledge.processing.enabled",havingValue="true") GraphExtractionHandler graphExtractionHandler(GraphExtractionRepository repository,ObjectMapper json,ModelClientFactory models,Neo4jGraphRepository graph,KnowledgeProcessingSpanTracker tracker,GraphExtractionText text){return new GraphExtractionHandler(repository,json,(owner,id)->models.chat(new AuthContext("system-task",owner,"viewer",false,null,false,Set.of(),Set.of()),id),graph,tracker,text);}
}
