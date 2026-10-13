package com.tencent.weknora.knowledge;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.docreader.DocumentReadResult;
import com.tencent.weknora.task.*;
import java.io.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfficeProcessingCompatibilityTest {
  @TempDir Path temp;
  final ObjectMapper json = new ObjectMapper();

  JsonNode fixture(String resource, String name) throws Exception {
    for (var fixture : json.readTree(getClass().getResourceAsStream(resource)))
      if (fixture.path("name").asText().equals(name)) return fixture;
    throw new AssertionError(name);
  }

  @Test
  void supportedOfficeAndDamagedZipActuallyPersistChunksAndFinishDurablePostprocess()
      throws Exception {
    int count = 0;
    for (String name :
        List.of("doc-0-docx", "doc-invalid-zip", "zip-crc-error", "xlsx-custom-format")) {
      var f = fixture("/go-office-attachment.json", name);
      String parsed = f.path("parsed").asText();
      byte[] bytes = Base64.getDecoder().decode(f.path("data").asText());
      var env = new DocumentProcessingCompatibilityTest();
      env.temp = temp;
      env.database(count++, false);
      var row = env.seed(json.createObjectNode().put("file_type", f.path("file_type").asText()));
      env.jdbc.update("UPDATE knowledges SET file_hash='original-file-hash' WHERE id='knowledge'");
      var handler =
          new DocumentProcessingHandler(
              env.processing,
              json,
              (owner, kb, path) -> new ByteArrayInputStream(bytes),
              (engine, type) ->
                  request -> new DocumentReadResult(parsed, List.of(), "", Map.of(), "", List.of()),
              (owner, id) -> {
                throw new AssertionError("embedding is disabled");
              },
              (owner, kb) -> env.index,
              NativeDocumentSources::build,
              env.enqueuer());
      var job = env.job(row, "document:process");
      handler.execute(job, env.cancellation(job));
      env.tasks.complete(job, json.createObjectNode());
      var chunks =
          env.jdbc.queryForList(
              "SELECT content,source_locators FROM chunks WHERE knowledge_id='knowledge' ORDER BY"
                  + " chunk_index");
      assertThat(chunks).hasSize(1);
      JsonNode expected = f.path("locators");

      String stored = Objects.toString(chunks.get(0).get("source_locators"), "null");
      assertThat(json.readTree(stored)).isEqualTo(expected);
      var post =
          env.tasks.claim("post", Duration.ofMinutes(5), List.of("postprocess")).orElseThrow();
      new KnowledgePostProcessHandler(env.processing, json).execute(post, env.cancellation(post));
      env.tasks.complete(post, json.createObjectNode());
      assertThat(
              env.jdbc.queryForObject(
                  "SELECT parse_status FROM knowledges WHERE id='knowledge'", String.class))
          .isEqualTo("completed");
      assertThat(env.tasks.list(1, 10))
          .allSatisfy(task -> assertThat(task.status()).isEqualTo("succeeded"));
    }
  }

  @Test
  void knownGoNonterminatingFractionFailsBeforeChunkIndexOrPostprocessPublication()
      throws Exception {
    var f = json.readTree(getClass().getResourceAsStream("/go-excel-nontermination.json"));
    byte[] bytes = Base64.getDecoder().decode(f.path("data").asText());
    var env = new DocumentProcessingCompatibilityTest();
    env.temp = temp;
    env.database(10, false);
    var row = env.seed(json.createObjectNode().put("file_type", "xlsx"));
    var handler =
        new DocumentProcessingHandler(
            env.processing,
            json,
            (owner, kb, path) -> new ByteArrayInputStream(bytes),
            (engine, type) ->
                request -> new DocumentReadResult("NaN", List.of(), "", Map.of(), "", List.of()),
            (owner, id) -> {
              throw new AssertionError("nonterminating fraction must fail before embedding");
            },
            (owner, kb) -> {
              throw new AssertionError("nonterminating fraction must fail before indexing");
            },
            NativeDocumentSources::build,
            env.enqueuer());
    var job = env.job(row, "document:process");
    assertThatExceptionOfType(TaskFailure.class)
        .isThrownBy(() -> handler.execute(job, env.cancellation(job)))
        .satisfies(failure -> assertThat(failure.retryable()).isFalse());
    assertThat(env.jdbc.queryForObject("SELECT count(*) FROM chunks", Integer.class)).isZero();
    assertThat(env.tasks.list(1, 10))
        .noneMatch(task -> task.type().equals("knowledge:post_process"));
    assertThat(
            env.jdbc.queryForObject(
                "SELECT parse_status FROM knowledges WHERE id='knowledge'", String.class))
        .isEqualTo("failed");
  }
}
