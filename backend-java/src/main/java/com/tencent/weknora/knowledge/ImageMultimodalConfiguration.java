package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.weknora.model.*;
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
public class ImageMultimodalConfiguration {
  @Bean
  ImageMultimodalRepository imageMultimodalRepository(
      JdbcTemplate jdbc,
      ObjectMapper json,
      KnowledgeRepository knowledge,
      PlatformTransactionManager manager) {
    return new ImageMultimodalRepository(jdbc, json, knowledge, manager, Clock.systemUTC());
  }

  @Bean(destroyMethod = "close")
  ImageMultimodalImages imageMultimodalImages(StorageResolver storage, ModelEgressPolicy policy) {
    return new ImageMultimodalImages(storage, new SafeModelHttp(policy, 5, true));
  }

  @Bean
  ImageMultimodalFanout imageMultimodalFanout(
      ImageMultimodalRepository repository,
      ObjectMapper json,
      ModelClientFactory models,
      KnowledgeFileUpload.Enqueuer tasks) {
    return new ImageMultimodalFanout(
        repository,
        json,
        (owner, cfg) -> models.vision(auth(owner), cfg.path("model_id").asText()),
        tasks);
  }

  @Bean
  ImageMultimodalHandler imageMultimodalHandler(
      ImageMultimodalRepository repository,
      ObjectMapper json,
      ModelClientFactory models,
      ImageMultimodalImages images,
      DocumentProcessingHandler.Indexes indexes,
      KnowledgeFileUpload.Enqueuer tasks,
      KnowledgeProcessingSpanTracker tracker,
      ManualEmbeddingPool pool,
      Environment env) {
    int batch;
    try {
      batch = Integer.parseInt(env.getProperty("BATCH_EMBED_SIZE", "5"));
    } catch (NumberFormatException invalid) {
      batch = 0;
    }
    return new ImageMultimodalHandler(
        repository,
        json,
        (owner, cfg) -> {
          if (cfg.path("model_id").asText().isEmpty())
            throw new IllegalStateException(
                "Native legacy inline VLM configuration is not migrated");
          return models.vision(auth(owner), cfg.path("model_id").asText());
        },
        (owner, id) -> models.embeddingForCleanup(auth(owner), id),
        images,
        indexes,
        tasks,
        tracker,
        pool,
        batch);
  }

  private static AuthContext auth(long owner) {
    return new AuthContext("system-task", owner, "viewer", false, null, false, Set.of(), Set.of());
  }
}
