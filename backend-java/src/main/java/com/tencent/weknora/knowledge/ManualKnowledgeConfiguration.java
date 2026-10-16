package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.weknora.model.ModelClientFactory;
import com.tencent.weknora.model.ModelEgressPolicy;
import com.tencent.weknora.model.client.SafeModelHttp;
import com.tencent.weknora.security.AuthContext;
import com.tencent.weknora.storage.StorageResolver;
import java.time.Clock;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@ConditionalOnProperty(name = "weknora.knowledge.processing.enabled", havingValue = "true")
public class ManualKnowledgeConfiguration {
  @Bean
  ManualKnowledgeRepository manualKnowledgeRepository(
      JdbcTemplate jdbc, KnowledgeRepository knowledge, ObjectMapper json) {
    return new ManualKnowledgeRepository(jdbc, knowledge, json);
  }

  @Bean
  ManualKnowledgeService manualKnowledgeService(
      ManualKnowledgeRepository repository,
      ObjectMapper json,
      KnowledgeFileUpload.Enqueuer tasks,
      StorageResolver storage) {
    return new ManualKnowledgeService(repository, json, tasks, storage.catalog());
  }

  @Bean
  ManualProcessingRepository manualProcessingRepository(
      JdbcTemplate jdbc,
      KnowledgeRepository knowledge,
      ObjectMapper json,
      PlatformTransactionManager manager) {
    return new ManualProcessingRepository(jdbc, knowledge, json, manager, Clock.systemUTC());
  }

  @Bean(destroyMethod="close")
  ManualEmbeddingPool manualEmbeddingPool(Environment env){return new ManualEmbeddingPool(Integer.parseInt(env.getProperty("CONCURRENCY_POOL_SIZE","5")));}

  @Bean(destroyMethod="close")
  ManualImageResolver manualImageResolver(ModelEgressPolicy policy,Environment env){return new ManualImageResolver(new SafeModelHttp(policy,5,true),env.getProperty("IMAGE_HOST_KEEP_URL",""));}
  @Bean
  ManualProcessingImages manualProcessingImages(StorageResolver storage,ManualImageResolver resolver,ObjectMapper json){return new ManualProcessingImages(storage,resolver,json);}

  @Bean
  ManualProcessingHandler manualProcessingHandler(
      ManualProcessingRepository repository,
      ObjectMapper json,
      ModelClientFactory models,
      DocumentProcessingHandler.Indexes indexes,
      KnowledgeFileUpload.Enqueuer tasks,
      KnowledgeProcessingSpanTracker tracker,
      Neo4jGraphRepository graph,
      Environment env,
      ManualProcessingImages images,
      ManualEmbeddingPool pool,
      ImageMultimodalFanout multimodal) {
    int batch;
    try {
      batch = Integer.parseInt(env.getProperty("BATCH_EMBED_SIZE", "5"));
    } catch (NumberFormatException error) {
      batch = 0;
    }
    return new ManualProcessingHandler(
        repository,
        json,
        (owner, id) ->
            models.embeddingForCleanup(
                new AuthContext(
                    "system-task", owner, "viewer", false, null, false, Set.of(), Set.of()),
                id),
        indexes,
        tasks,
        tracker,
        graph,
        batch).withImages(images).withEmbeddingPool(pool).withMultimodal(multimodal);
  }
}
