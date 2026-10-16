package com.tencent.weknora.knowledge;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.retrieval.*;
import com.tencent.weknora.task.TaskRepository;
import com.zaxxer.hikari.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Actual Go manual worker and PostgreSQL halfvec replay with Hikari maximumPoolSize=1. */
class ManualProcessingPostgresGoParityIT extends ManualProcessingGoParityTest {
  private HikariDataSource pool;
  private PostgresIndexRepository index;

  @Override
  @TestFactory
  Stream<DynamicTest> actualGoManualWorker() throws Exception {
    var cases =
        json.readTree(
            Files.readAllBytes(
                Path.of(
                    Objects.requireNonNull(
                        System.getProperty("weknora.manual.worker.pg.fixture")))));
    assertThat(cases.size()).isEqualTo(45);
    var seq = new AtomicInteger();
    return StreamSupport.stream(cases.spliterator(), false)
        .map(
            c ->
                DynamicTest.dynamicTest(
                    "postgres/" + c.path("name").asText(),
                    () -> compare(c, seq.incrementAndGet())));
  }

  @Override
  DocumentProcessingCompatibilityTest database(int no, String name) throws Exception {
    String url = Objects.requireNonNull(System.getProperty("weknora.manual.pg.url"));
    if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/weknora_manual_parity"))
      throw new IllegalArgumentException("Owned loopback manual database required");
    var config = new HikariConfig();
    config.setJdbcUrl(url);
    config.setUsername("postgres");
    config.setPassword("local-parity-only");
    config.setMaximumPoolSize(1);
    config.setMinimumIdle(1);
    config.setConnectionTimeout(2000);
    config.addDataSourceProperty("stringtype", "unspecified");
    pool = new HikariDataSource(config);
    var f = new DocumentProcessingCompatibilityTest();
    f.temp = temp;
    f.jdbc = new JdbcTemplate(pool);
    f.manager = new DataSourceTransactionManager(pool);
    f.tasks = new TaskRepository(f.jdbc, json, f.manager);
    f.knowledge = new KnowledgeRepository(f.jdbc, json, f.manager);
    f.jdbc.execute(
        "TRUNCATE"
            + " tenants,knowledge_bases,knowledges,chunks,models,embeddings,knowledge_processing_spans,java_task_jobs"
            + " RESTART IDENTITY CASCADE");
    // The Go migration creates this sequence independently, without OWNED BY.
    // TRUNCATE RESTART IDENTITY therefore does not reset it between oracle cases.
    f.jdbc.execute("ALTER SEQUENCE chunks_seq_id_seq RESTART WITH 100000000");
    f.jdbc.update(
        "INSERT INTO tenants(id,name,business)"
            + " VALUES(1,'owner','isolated'),(2,'foreign','isolated')");
    f.jdbc.update(
        "INSERT INTO"
            + " knowledge_bases(id,tenant_id,name,type,embedding_model_id,summary_model_id,indexing_strategy)"
            + " VALUES('kb',1,'KB','document','embedding','',?::jsonb)",
        name.equals("disabled-indexes")
            ? "{\"graph_enabled\":true}"
            : "{\"vector_enabled\":true,\"keyword_enabled\":true}");
    index = new PostgresIndexRepository(pool);
    index.initialize();
    return f;
  }

  @Override String oldSequence(){return "nextval('chunks_seq_id_seq')";}
  @Override
  IndexWriter writer(DocumentProcessingCompatibilityTest f) {
    return index;
  }

  @Override
  String indexTable() {
    return "embeddings";
  }

  @Override
  JsonNode vectors(DocumentProcessingCompatibilityTest f, Map<String, String> ids)
      throws Exception {
    var rows =
        f.jdbc.queryForList(
            "SELECT source_id,embedding::text AS embedding FROM embeddings WHERE dimension=2");
    rows.sort(Comparator.comparing(row -> ids.get(row.get("source_id").toString())));
    var result = json.createArrayNode();
    for (var row : rows) {
      var bits = json.createArrayNode();
      for (var value : json.readTree(row.get("embedding").toString()))
        bits.add(Integer.toUnsignedLong(Float.floatToRawIntBits((float) value.asDouble())));
      result.add(
          json.createObjectNode()
              .put("source_id", ids.get(row.get("source_id").toString()))
              .set("bits", bits));
    }
    return result;
  }

  @Override
  void release(DocumentProcessingCompatibilityTest f) {
    if (pool != null) {
      pool.close();
      pool = null;
    }
  }
}
