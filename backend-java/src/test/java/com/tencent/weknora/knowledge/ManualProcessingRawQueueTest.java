package com.tencent.weknora.knowledge;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.weknora.task.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Malformed manual JSON reaches the real claim/settle path instead of poisoning the SQL queue. */
class ManualProcessingRawQueueTest {
 @TempDir Path temp;
 @TestFactory java.util.stream.Stream<DynamicTest> malformedManualIsClaimedAndAcknowledged(){var no=new java.util.concurrent.atomic.AtomicInteger();return java.util.stream.Stream.of("{broken", "{\"tenant_id\":1,\"content\":42}", "{\"tenant_id\":1} trailing").map(raw->DynamicTest.dynamicTest(raw,()->{
  var f=new DocumentProcessingCompatibilityTest();f.temp=temp;f.database(no.incrementAndGet(),true);var json=new ObjectMapper();var initial=f.tasks.enqueue(1,"manual:process",UUID.randomUUID().toString(),json.createObjectNode(),4,Duration.ZERO,"manual-test",Duration.ofMinutes(30));f.jdbc.update("UPDATE java_task_jobs SET payload=? WHERE id=?",raw,initial.id());
  var job=f.tasks.claim("manual-raw-worker",Duration.ofMinutes(1),List.of("manual-test")).orElseThrow();
  var constructor=TaskCancellation.class.getDeclaredConstructor(TaskRepository.class,TaskRecord.class);constructor.setAccessible(true);var cancellation=constructor.newInstance(f.tasks,job);
  var repository=new ManualProcessingRepository(f.jdbc,f.knowledge,json,f.manager,Clock.systemUTC());
  var handler=new ManualProcessingHandler(repository,json,(owner,id)->{throw new AssertionError("malformed payload cannot request a model");},(owner,kb)->{throw new AssertionError("malformed payload cannot request indexes");},(owner,type,payload,opts)->{throw new AssertionError("malformed payload cannot enqueue postprocess");},null,null,5);
  var result=handler.execute(job,cancellation);assertThat(result.path("status").asText()).isEqualTo("invalid_payload");assertThat(f.tasks.complete(job,result)).isTrue();assertThat(f.tasks.get(1,job.id()).orElseThrow().status()).isEqualTo("succeeded");assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_processing_spans",Long.class)).isZero();assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM chunks",Long.class)).isZero();
 }));}
 @Test void malformedOtherTaskRetainsExistingPersistenceFailure()throws Exception{var f=new DocumentProcessingCompatibilityTest();f.temp=temp;f.database(99,true);var json=new ObjectMapper();var task=f.tasks.enqueue(1,"document:process",UUID.randomUUID().toString(),json.createObjectNode(),4,Duration.ZERO);f.jdbc.update("UPDATE java_task_jobs SET payload='{broken' WHERE id=?",task.id());assertThatThrownBy(()->f.tasks.get(1,task.id())).isInstanceOf(IllegalStateException.class).hasMessage("Invalid persisted Java task JSON");}
}
