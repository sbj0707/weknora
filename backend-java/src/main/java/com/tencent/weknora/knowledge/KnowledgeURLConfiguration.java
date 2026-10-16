package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="weknora.knowledge.processing.enabled",havingValue="true")
public class KnowledgeURLConfiguration {
  @Bean KnowledgeURLService knowledgeURLService(JdbcTemplate jdbc,ObjectMapper json,
      KnowledgeRepository repository,KnowledgeFileUpload.Enqueuer tasks,Environment env) {
    return new KnowledgeURLService(jdbc,json,repository,tasks,
        Duration.ofMillis(env.getProperty("weknora.knowledge.document-timeout-ms",Long.class,7200000L)),Clock.systemUTC());
  }
}
