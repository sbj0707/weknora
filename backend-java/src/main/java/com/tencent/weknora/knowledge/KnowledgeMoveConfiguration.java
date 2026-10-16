package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.weknora.model.ModelClientFactory;
import com.tencent.weknora.model.client.CallContext;
import com.tencent.weknora.retrieval.*;
import com.tencent.weknora.security.AuthContext;
import com.tencent.weknora.task.*;
import com.tencent.weknora.vectorstore.*;
import io.lettuce.core.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The native move worker shares the existing SQL delivery backend, never a bridge request. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "weknora.knowledge.processing.enabled", havingValue = "true")
public class KnowledgeMoveConfiguration {
  @Bean
  @ConditionalOnMissingBean(KnowledgeMoveProgress.class)
  RedisKnowledgeMoveProgress knowledgeMoveProgress(ObjectMapper json, Environment env) {
    return new RedisKnowledgeMoveProgress(
        json,
        () -> {
          String address = env.getProperty("REDIS_ADDR", "");
          if (address.isEmpty()) throw new IllegalStateException("Redis is not configured");
          URI endpoint = URI.create("redis://" + address);
          var uri =
              RedisURI.Builder.redis(
                  endpoint.getHost(), endpoint.getPort() < 0 ? 6379 : endpoint.getPort());
          int database;
          try {
            database = Integer.parseInt(env.getProperty("REDIS_DB", ""));
          } catch (NumberFormatException error) {
            database = 0;
          }
          uri.withDatabase(database);
          String user = env.getProperty("REDIS_USERNAME", ""),
              password = env.getProperty("REDIS_PASSWORD", "");
          if (!user.isEmpty()) uri.withAuthentication(user, password);
          else if (!password.isEmpty()) uri.withPassword(password.toCharArray());
          boolean tls =
              KnowledgeUploadForms.goTrim(env.getProperty("REDIS_USE_TLS", ""))
                  .equalsIgnoreCase("true");
          uri.withSsl(tls)
              .withVerifyPeer(
                  !KnowledgeUploadForms.goTrim(
                          env.getProperty("REDIS_TLS_INSECURE_SKIP_VERIFY", ""))
                      .equalsIgnoreCase("true"));
          if (tls
              && !KnowledgeUploadForms.goTrim(env.getProperty("REDIS_TLS_SERVER_NAME", ""))
                  .isEmpty())
            throw new IllegalStateException(
                "Native Redis TLS server-name override is not migrated");
          return RedisClient.create(uri.build());
        });
  }

  @Bean
  NamedTaskProducer namedTaskProducer(
      JdbcTemplate jdbc, TaskRepository tasks, PlatformTransactionManager manager) {
    return new NamedTaskProducer(jdbc, tasks, manager, Clock.systemUTC());
  }

  @Bean
  NamedTaskJanitor namedTaskJanitor(NamedTaskProducer tasks) {
    return new NamedTaskJanitor(tasks);
  }

  static final class NamedTaskJanitor {
    private final NamedTaskProducer tasks;

    NamedTaskJanitor(NamedTaskProducer tasks) {
      this.tasks = tasks;
    }

    @org.springframework.scheduling.annotation.Scheduled(
        fixedDelayString = "${weknora.tasks.retention-poll-millis:8000}",
        initialDelayString = "${weknora.tasks.retention-poll-millis:8000}")
    public void purge() {
      try {
        tasks.purgeExpired();
      } catch (RuntimeException failure) {
        org.slf4j.LoggerFactory.getLogger(getClass())
            .warn("Named task retention cleanup unavailable; will retry");
      }
    }
  }

  @Bean
  KnowledgeMoveAdmission knowledgeMoveAdmission(
      JdbcTemplate jdbc,
      ObjectMapper json,
      KnowledgeBaseCodec bases,
      com.tencent.weknora.security.KnowledgeBaseWriteAuthorization writes,
      NamedTaskProducer tasks,
      KnowledgeMoveProgress progress) {
    return new KnowledgeMoveAdmission(
        jdbc, json, bases, writes, tasks, progress, Clock.systemUTC());
  }

  @Bean
  KnowledgeTransferRepository knowledgeTransferRepository(
      JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager manager) {
    return new KnowledgeTransferRepository(jdbc, json, manager, Clock.systemUTC());
  }

  @Bean(destroyMethod = "close")
  NativeMoveRuntime nativeMoveRuntime(
      JdbcTemplate jdbc,
      ObjectMapper json,
      KnowledgeBaseCodec codec,
      DocumentProcessingHandler.Indexes indexes,
      VectorStoreRegistry stores,
      ModelClientFactory models,
      GraphExtractionStore graph,
      KnowledgeReparseService.Cleanup cleanup,
      NamedTaskProducer tasks,
      KnowledgeBaseProfileHandler profiles,
      PlatformTransactionManager manager) {
    return new NativeMoveRuntime(
        jdbc, json, codec, indexes, stores, models, graph, cleanup, tasks, profiles, manager);
  }

  @Bean
  KnowledgeMoveHandler knowledgeMoveHandler(
      KnowledgeTransferRepository repository,
      KnowledgeMoveProgress progress,
      ObjectMapper json,
      NativeMoveRuntime runtime,
      Environment env,
      KnowledgeProcessingLanguage language) {
    return new KnowledgeMoveHandler(
        repository,
        progress,
        json,
        runtime,
        Clock.systemUTC(),
        Duration.ofMillis(
            env.getProperty("weknora.knowledge.document-timeout-ms", Long.class, 7200000L)),
        language);
  }

  static final class NativeMoveRuntime implements KnowledgeMoveHandler.Runtime, AutoCloseable {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final KnowledgeBaseCodec codec;
    private final DocumentProcessingHandler.Indexes indexes;
    private final VectorStoreRegistry stores;
    private final ModelClientFactory models;
    private final GraphExtractionStore graph;
    private final KnowledgeReparseService.Cleanup cleanup;
    private final NamedTaskProducer tasks;
    private final KnowledgeBaseProfileHandler profiles;
    private final TransactionTemplate transaction;
    private final boolean postgres;
    private final ScheduledExecutorService cancellations =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon(true).name("native-move-cancel").factory());

    NativeMoveRuntime(
        JdbcTemplate jdbc,
        ObjectMapper json,
        KnowledgeBaseCodec codec,
        DocumentProcessingHandler.Indexes indexes,
        VectorStoreRegistry stores,
        ModelClientFactory models,
        GraphExtractionStore graph,
        KnowledgeReparseService.Cleanup cleanup,
        NamedTaskProducer tasks,
        KnowledgeBaseProfileHandler profiles,
        PlatformTransactionManager manager) {
      this.jdbc = jdbc;
      this.json = json;
      this.codec = codec;
      this.indexes = indexes;
      this.stores = stores;
      this.models = models;
      this.graph = graph;
      this.cleanup = cleanup;
      this.tasks = tasks;
      this.profiles = profiles;
      transaction = new TransactionTemplate(manager);
      postgres =
          Boolean.TRUE.equals(
              jdbc.execute(
                  (ConnectionCallback<Boolean>)
                      c -> c.getMetaData().getDatabaseProductName().equals("PostgreSQL")));
    }

    public String raw(TaskRecord job) {
      var rows =
          jdbc.queryForList(
              "SELECT payload FROM java_task_jobs WHERE id=? AND tenant_id=?",
              job.id(),
              job.tenantId());
      return rows.isEmpty()
          ? job.payload().toString()
          : Objects.toString(rows.getFirst().get("payload"), "null");
    }

    public JsonNode base(String id) {
      var rows =
          jdbc.queryForList("SELECT * FROM knowledge_bases WHERE id=? AND deleted_at IS NULL", id);
      if (rows.isEmpty()) throw new IllegalStateException("knowledge base not found");
      return codec.fromRow(rows.getFirst());
    }

    public JsonNode tenant(long id) {
      var rows = jdbc.queryForList("SELECT * FROM tenants WHERE id=? AND deleted_at IS NULL", id);
      if (rows.isEmpty()) throw new IllegalStateException("tenant not found");
      return KnowledgeMoveJson.tenant(json, rows.getFirst());
    }

    public void validateEngine(long owner, JsonNode source) throws Exception {
      String bound = source.path("vector_store_id").asText("");
      if (!bound.isEmpty())
        try (var call = new CallContext()) {
          stores.resolve(owner, bound, call).validateKnowledgeIndexMove();
        }
      else {
        var writer = indexes.resolve(owner, source);
        if (!(writer instanceof SQLiteIndexRepository)
            && !(writer instanceof PostgresIndexRepository))
          throw new UnsupportedOperationException(
              "Native index metadata move is not migrated for this engine");
      }
    }

    public KnowledgeMoveExecution.Effects effects(
        TaskRecord job, ObjectNode payload, Runnable guard) {
      return new KnowledgeMoveExecution.Effects() {
        public void requireTransfer(
            KnowledgeTransferRepository.Scope scope, JsonNode source, JsonNode target) {
          guard.run();
        }

        public void moveIndices(
            Map<String, Object> row, JsonNode source, JsonNode target, List<String> chunks)
            throws Exception {
          guard.run();
          long owner = job.tenantId();
          var model =
              models.embeddingForCleanup(
                  new AuthContext(
                      "system-task", owner, "viewer", false, null, false, Set.of(), Set.of()),
                  KnowledgeTransferRepository.text(row, "embedding_model_id"));
          String bound = source.path("vector_store_id").asText("");
          if (!bound.isEmpty())
            try (var context = call(guard)) {
              stores
                  .resolve(owner, bound, context.call)
                  .moveKnowledgeIndices(
                      source.path("id").asText(),
                      target.path("id").asText(),
                      KnowledgeTransferRepository.text(row, "id"),
                      chunks,
                      model.dimensions(),
                      source.path("type").asText(),
                      context.call);
            }
          else {
            var writer = indexes.resolve(owner, source);
            transaction.executeWithoutResult(
                status ->
                    jdbc.execute(
                        (ConnectionCallback<Void>)
                            connection -> {
                              guard.run();
                              writer.move(
                                  connection,
                                  source.path("id").asText(),
                                  target.path("id").asText(),
                                  KnowledgeTransferRepository.text(row, "id"));
                              return null;
                            }));
          }
        }

        public void deleteGraph(String source, String id) throws Exception {
          try (var context = call(guard)) {
            graph.delete(source, id, context.call, guard);
          }
        }

        public void cleanupResources(Map<String, Object> row, JsonNode source) throws Exception {
          ObjectNode document = json.valueToTree(row);
          if (document.path("metadata").isTextual())
            try {
              document.set("metadata", json.readTree(document.path("metadata").asText()));
            } catch (Exception ignored) {
            }
          cleanup.run(job.tenantId(), (ObjectNode) source, document, guard);
        }

        public void cleanupWiki(Map<String, Object> old, Set<String> chunks) {
          throw new UnsupportedOperationException(
              "Native move Wiki source reconciliation is not migrated");
        }

        public void enqueue(KnowledgeMoveExecution.Submission task) throws Exception {
          guard.run();
          try {
            tasks.enqueue(
                job.tenantId(),
                task.type(),
                task.id(),
                task.payload(),
                task.queue(),
                task.maxRetries(),
                task.timeout(),
                task.retention());
          } catch (NamedTaskProducer.Conflict conflict) {
            throw new KnowledgeMoveExecution.AlreadyEnqueued();
          }
        }

        public void ingestWiki(long tenant, String target, String id) {
          throw new UnsupportedOperationException("Native move Wiki ingest is not migrated");
        }
      };
    }

    public void activity(ObjectNode payload, String kb, String action, String outcome, int count) {
      var initiator = payload.path("initiator");
      String actor = initiator.path("user_id").asText(""), role = initiator.path("role").asText("");
      if (actor.isEmpty()) role = "";
      var details =
          json.createObjectNode()
              .put("source_kb_id", payload.path("source_kb_id").asText())
              .put("target_kb_id", payload.path("target_kb_id").asText())
              .put("task_id", payload.path("task_id").asText())
              .put("count", count)
              .put("mode", payload.path("mode").asText())
              .put("trigger", actor.isEmpty() ? "system" : "user");
      details.put(
          "processing_status",
          switch (outcome) {
            case "accepted" -> "pending";
            case "success" -> "completed";
            case "partial" -> "partial";
            default -> "failed";
          });
      for (String key : List.of("api_key_id", "api_key_name"))
        if (!initiator.path(key).isMissingNode() && !initiator.path(key).asText().isEmpty())
          details.set(key, initiator.get(key));
      jdbc.update(
          "INSERT INTO"
              + " audit_logs(tenant_id,actor_user_id,actor_role,action,target_type,target_id,scope_type,scope_id,outcome,details)"
              + " VALUES(?,?,?,?,?,?,'knowledge_base',?,?,"
              + (postgres ? "CAST(? AS JSONB)" : "?")
              + ")",
          payload.path("tenant_id").asLong(),
          actor,
          role,
          action,
          "knowledge_move",
          payload.path("task_id").asText(),
          kb,
          outcome,
          details.toString());
    }

    public void profile(long owner, JsonNode base, ObjectNode payload) {
      profiles.enqueue(owner, base, false, payload);
    }

    private GuardedCall call(Runnable guard) {
      return new GuardedCall(guard);
    }

    private final class GuardedCall implements AutoCloseable {
      final CallContext call = CallContext.timeout(Duration.ofHours(2));
      final ScheduledFuture<?> watcher;

      GuardedCall(Runnable guard) {
        guard.run();
        watcher =
            cancellations.scheduleWithFixedDelay(
                () -> {
                  try {
                    guard.run();
                  } catch (RuntimeException canceled) {
                    call.cancel();
                  }
                },
                25,
                25,
                TimeUnit.MILLISECONDS);
      }

      public void close() {
        watcher.cancel(false);
        call.close();
      }
    }

    public void close() {
      cancellations.shutdownNow();
    }
  }
}
