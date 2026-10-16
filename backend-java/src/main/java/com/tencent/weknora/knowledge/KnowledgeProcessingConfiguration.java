package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.docreader.*;
import com.tencent.weknora.model.*;
import com.tencent.weknora.retrieval.*;
import com.tencent.weknora.security.*;
import com.tencent.weknora.storage.*;
import com.tencent.weknora.task.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Opt-in native workers; ordinary bridge startup does not require a vector extension. */
@Configuration
@ConditionalOnProperty(name = "weknora.knowledge.processing.enabled", havingValue = "true")
public class KnowledgeProcessingConfiguration {
  @Bean
  public KnowledgeRepository knowledgeRepository(
      JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager tx) throws IOException {
    return new KnowledgeRepository(jdbc, json, tx);
  }

  @Bean
  public KnowledgeUploadAudit knowledgeUploadAudit(JdbcTemplate jdbc, ObjectMapper json) {
    return new KnowledgeUploadAudit(jdbc, json);
  }

  @Bean
  public KnowledgeUploadForms knowledgeUploadForms(ObjectMapper json) {
    return new KnowledgeUploadForms(json);
  }

  @Bean
  public KnowledgeFileUpload knowledgeFileUpload(
      KnowledgeRepository repo,
      ObjectMapper json,
      StorageResolver storage,
      KnowledgeFileUpload.Enqueuer tasks,
      Environment env) {
    return new KnowledgeFileUpload(
        repo,
        json,
        (owner, kb) -> storage.forKnowledgeBase(owner, kb).files(),
        tasks,
        Clock.systemUTC(),
        Duration.ofMillis(
            env.getProperty("weknora.knowledge.document-timeout-ms", Long.class, 7200000L)));
  }

  @Bean
  public DocumentProcessingRepository documentProcessingRepository(
      JdbcTemplate jdbc,
      ObjectMapper json,
      KnowledgeRepository repo,
      PlatformTransactionManager tx) {
    return new DocumentProcessingRepository(jdbc, json, repo, tx, Clock.systemUTC());
  }

  @Bean
  public RemoteDocumentDownloads remoteDocumentDownloads(ModelURLPolicy urls, ModelEgressPolicy egress) {
    return new RemoteDocumentDownloads(urls, egress);
  }

  @Bean
  public DocumentProcessingHandler documentProcessingHandler(
      DocumentProcessingRepository repo,
      ObjectMapper json,
      StorageResolver storage,
      SimpleFormatReader simple,
      RemoteDocumentReader remote,
      ModelURLPolicy urls,
      ModelClientFactory models,
      DataSource dataSource,
      Environment env,
      KnowledgeFileUpload.Enqueuer tasks,
      DocumentProcessingHandler.Indexes indexes,
      KnowledgeProcessingSpanTracker tracker,Neo4jGraphRepository graph,KnowledgeBaseProfileHandler profiles,
      RemoteDocumentDownloads downloads) {
    DocumentProcessingHandler.Readers readers =
        (engine, type) -> {
          DocumentReader selected;
          if (engine.equals("simple")
              || engine.isEmpty() && SimpleFormatReader.isSimpleFormat(type)) selected = simple;
          else if (Set.of(
                  "anydoc",
                  "weknoracloud",
                  "mineru",
                  "mineru_cloud",
                  "paddleocr_vl",
                  "paddleocr_vl_cloud")
              .contains(engine))
            throw new TaskFailure("Native parser engine is not migrated: " + engine, false);
          else {
            if (!remote.isConnected()) return null;
            selected = remote;
          }
          return selected;
        };
    return new DocumentProcessingHandler(
        repo,
        json,
        (owner, kb, path) -> storage.forKnowledgeBase(owner, kb).files().open(path),
        readers,
        (owner, id) ->
            models.embedding(
                new AuthContext(
                    "system-task", owner, "viewer", false, null, false, Set.of(), Set.of()),
                id),
        indexes,
        NativeDocumentSources::build,
        tasks,
        options -> {
          for (String key :
              List.of(
                  "mineru_endpoint",
                  "mineru_vlm_server_url",
                  "odl_hybrid_url",
                  "paddleocr_vl_endpoint",
                  "paddleocr_vl_cloud_base_url")) {
            String raw = KnowledgeUploadForms.goTrim(options.getOrDefault(key, ""));
            if (!raw.isEmpty())
              try {
                urls.validate(raw);
              } catch (ApiException e) {
                throw new DocumentProcessingHandler.ParserEndpointRejected();
              }
          }
        },tracker,true,true,true,true).withGraphCleanup(graph).withProfiles(profiles)
        .withRemoteSources(downloads, (owner, kb, bytes, filename) ->
            storage.forKnowledgeBase(owner, kb).files().saveBytes(bytes, owner, filename, true));
  }

  @Bean
  public DocumentProcessingHandler.Indexes documentIndexes(DataSource dataSource, Environment env) {
    return         new DocumentProcessingHandler.Indexes() {
          private IndexWriter writer;

          @Override
          public synchronized IndexWriter resolve(long owner, JsonNode kb) throws TaskFailure {
            if (kb.hasNonNull("vector_store_id") && !kb.path("vector_store_id").asText().isEmpty())
              throw new TaskFailure("Native bound vector store selection is not migrated", false);
            if (writer != null) return writer;
            try {
              String database;
              try (var connection = dataSource.getConnection()) {
                database=connection.getMetaData().getDatabaseProductName();
              }
              if(database.equals("PostgreSQL")) {
                var candidate=new PostgresIndexRepository(dataSource);
                candidate.initialize();writer=candidate;return writer;
              }
              if(!database.equals("SQLite")) throw new TaskFailure("Native document indexing is not migrated for this database",false);
              String path =
                  env.getProperty(
                      "weknora.sqlite.vec.extension",
                      env.getProperty("WEKNORA_SQLITE_VEC_EXTENSION", ""));
              if (path.isEmpty())
                throw new TaskFailure(
                    "Native SQLite indexing requires WEKNORA_SQLITE_VEC_EXTENSION", false);
              var candidate =
                  new SQLiteIndexRepository(dataSource, new SQLiteVectorExtension(Path.of(path)));
              candidate.initialize();
              writer = candidate;
              return writer;
            } catch (TaskFailure error) {
              throw error;
            } catch (Exception error) {
              throw new TaskFailure("Native document index initialization failed", true);
            }
          }
        };
  }

  @Bean
  public DataTableSummaryRepository dataTableSummaryRepository(JdbcTemplate jdbc, ObjectMapper json,
      KnowledgeRepository knowledge, PlatformTransactionManager tx) throws IOException {
    return new DataTableSummaryRepository(jdbc,json,knowledge,tx,Clock.systemUTC());
  }

  @Bean
  public DataTableSummaryHandler dataTableSummaryHandler(DataTableSummaryRepository repository,
      ObjectMapper json, ModelClientFactory models, StorageResolver storage,
      DocumentProcessingHandler.Indexes indexes, Environment env) {
    var nativeModels=new DataTableSummaryHandler.Models() {
      private AuthContext context(long owner) {
        return new AuthContext("system-task",owner,"viewer",false,null,false,Set.of(),Set.of());
      }
      public com.tencent.weknora.model.client.ChatModel chat(long owner,String id) {
        return models.chat(context(owner),id);
      }
      public com.tencent.weknora.model.client.EmbeddingModel embedding(long owner,String id) {
        return models.embedding(context(owner),id);
      }
    };
    long limit=50L*1024*1024;
    try {long mb=Long.parseLong(env.getProperty("MAX_FILE_SIZE_MB","50"));if(mb>0)limit=Math.multiplyExact(mb,1024*1024);}catch(NumberFormatException|ArithmeticException ignored){}
    return new DataTableSummaryHandler(repository,json,nativeModels,
        (owner,kb,path)->storage.forKnowledgeBase(owner,kb).files().open(path),indexes,
        ()->openTableDatabase(env),limit);
  }

  static java.sql.Connection openTableDatabase(Environment env) throws java.sql.SQLException {
    var connection=java.sql.DriverManager.getConnection("jdbc:duckdb:");
    String skip=env.getProperty("DUCKDB_SKIP_EXTENSION_LOAD","");
    if(!skip.equals("1")&&!skip.equalsIgnoreCase("true")) {
      try(var statement=connection.createStatement()) {
        for(String extension:List.of("spatial","excel")) {
          try {statement.execute("LOAD "+extension);}
          catch(java.sql.SQLException first) {
            try {statement.execute("INSTALL "+extension);statement.execute("LOAD "+extension);}
            catch(java.sql.SQLException unavailable) {
              org.slf4j.LoggerFactory.getLogger(KnowledgeProcessingConfiguration.class).warn("DuckDB {} extension unavailable",extension);
            }
          }
        }
      } catch(java.sql.SQLException e) {connection.close();throw e;}
    }
    return connection;
  }

  @Bean
  public KnowledgePostProcessHandler knowledgePostProcessHandler(
      DocumentProcessingRepository repo, ObjectMapper json,KnowledgeProcessingSpanTracker tracker,SummaryGenerationHandler summary,QuestionGenerationFanout fanout,org.springframework.beans.factory.ObjectProvider<KnowledgeAutoTagHandler> autoTag,KnowledgeBaseProfileHandler profiles) {
    return new KnowledgePostProcessHandler(repo, json,tracker,summary,fanout,autoTag.getIfAvailable()).withProfiles(profiles);
  }
}
