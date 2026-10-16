package com.tencent.weknora.knowledge;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.weknora.task.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** A malformed durable image payload must reach the handler and retry, rather than poison claim. */
class ImageMultimodalRawQueueTest {
  @TempDir Path temp;

  @TestFactory
  java.util.stream.Stream<DynamicTest> corruptPayloadReachesProductionWorkerAndRetries() {
    var no = new AtomicInteger();
    return java.util.stream.Stream.of(
            "{broken", "{\"tenant_id\":1,\"enable_ocr\":42}", "{\"tenant_id\":1} trailing")
        .map(
            raw ->
                DynamicTest.dynamicTest(
                    raw,
                    () -> {
                      var f = new DocumentProcessingCompatibilityTest();
                      f.temp = temp;
                      f.database(no.incrementAndGet(), true);
                      var json = new ObjectMapper();
                      var task =
                          f.tasks.enqueue(
                              1,
                              "image:multimodal",
                              UUID.randomUUID().toString(),
                              json.createObjectNode(),
                              4,
                              Duration.ZERO,
                              "multimodal",
                              Duration.ofMinutes(30));
                      f.jdbc.update(
                          "UPDATE java_task_jobs SET payload=? WHERE id=?", raw, task.id());
                      var repository =
                          new ImageMultimodalRepository(
                              f.jdbc, json, f.knowledge, f.manager, Clock.systemUTC());
                      var handler =
                          new ImageMultimodalHandler(
                              repository,
                              json,
                              (owner, c) -> {
                                throw new AssertionError("malformed payload cannot request VLM");
                              },
                              (owner, id) -> {
                                throw new AssertionError("malformed payload cannot embed");
                              },
                              (job, source, call) -> {
                                throw new AssertionError("malformed payload cannot read an image");
                              },
                              (owner, kb) -> {
                                throw new AssertionError(
                                    "malformed payload cannot resolve indexes");
                              },
                              (owner, type, payload, options) -> {
                                throw new AssertionError(
                                    "malformed payload cannot enqueue descendants");
                              },
                              null,
                              null,
                              5);
                      try (var worker =
                          new TaskWorker(
                              f.tasks,
                              List.of(handler),
                              1,
                              Duration.ofSeconds(10),
                              Map.of("multimodal", 1))) {
                        worker.poll();
                        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                        while (worker.activeCount() > 0 && System.nanoTime() < deadline)
                          Thread.sleep(10);
                        assertThat(worker.activeCount()).isZero();
                      }
                      var settled = f.tasks.get(1, task.id()).orElseThrow();
                      assertThat(settled.status()).isEqualTo("retry");
                      assertThat(settled.attempts()).isEqualTo(1);
                      assertThat(settled.lastError())
                          .startsWith("unmarshal image multimodal payload:");
                      assertThat(settled.availableAt()).isGreaterThan(settled.createdAt());
                      assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM chunks", Long.class))
                          .isZero();
                      assertThat(
                              f.jdbc.queryForObject(
                                  "SELECT COUNT(*) FROM knowledge_processing_spans", Long.class))
                          .isZero();
                    }));
  }
}
