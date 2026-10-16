package com.tencent.weknora.vectorstore;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.weknora.model.ModelURLPolicy;
import com.tencent.weknora.security.GoSecretCodec;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class VectorStorePublicConfiguration {
  @Bean
  VectorStorePublicForms vectorStorePublicForms(ObjectMapper json) {
    return new VectorStorePublicForms(json);
  }

  @Bean
  VectorStoreEnvironment vectorStoreEnvironment(ObjectMapper json, Environment environment) {
    return new VectorStoreEnvironment(json, environment);
  }

  @Bean
  VectorStorePublicRepository vectorStorePublicRepository(
      JdbcTemplate jdbc,
      PlatformTransactionManager tx,
      VectorStoreMetadata metadata,
      GoSecretCodec secrets,
      ObjectMapper json) {
    return new VectorStorePublicRepository(jdbc, tx, metadata, secrets, json);
  }

  @Bean
  VectorStorePublicService vectorStorePublicService(
      VectorStorePublicRepository repository,
      VectorStoreEnvironment environment,
      VectorStoreRegistry registry,
      VectorStoreConfiguration.VectorStoreHttpRuntime runtime,
      ObjectMapper json,
      ModelURLPolicy policy) {
    return new VectorStorePublicService(
        repository,
        environment,
        registry,
        (store, call) -> {
          if (!store.engine().equals("elasticsearch"))
            throw new IllegalArgumentException("Native engine is not migrated");
          return new Elasticsearch8Store(store, runtime.http, json, call);
        },
        runtime.http,
        json,
        policy);
  }
}
