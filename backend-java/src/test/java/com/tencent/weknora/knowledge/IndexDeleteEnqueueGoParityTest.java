package com.tencent.weknora.knowledge;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.task.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Actual Go FAQ/tag-delete admitted producer and Asynq options; no public tag CRUD claim. */
class IndexDeleteEnqueueGoParityTest {
  @TempDir Path temp;
  final ObjectMapper json=new ObjectMapper();
  @TestFactory Stream<DynamicTest> admittedDeletionProducesGoCleanupEnvelope()throws Exception{
    JsonNode cases;try(var in=getClass().getResourceAsStream("/go-index-delete-enqueue.json")){cases=json.readTree(in);}
    assertThat(cases.size()).isEqualTo(9);var sequence=new AtomicInteger();
    return StreamSupport.stream(cases.spliterator(),false).map(c->DynamicTest.dynamicTest(c.path("name").asText(),()->{
      var f=new DocumentProcessingCompatibilityTest();f.temp=temp;f.database(sequence.incrementAndGet(),true);
      TaskRepository tasks=f.tasks;if(c.path("name").asText().equals("enqueue-failure"))tasks=new TaskRepository(f.jdbc,json,f.manager){@Override public TaskRecord enqueue(long owner,String type,String key,JsonNode payload,int attempts,Duration delay,String queue,Duration timeout){throw new IllegalStateException("controlled queue unavailable");}};
      var ids=new ArrayList<String>();c.path("deleted_ids").forEach(id->ids.add(id.asText()));
      var producer=new IndexDeleteEnqueuer(tasks,json);assertThat(producer.enqueue(1,c.path("base"),ids,c.path("engines"),json.createObjectNode())).isEqualTo(!c.path("tasks").isEmpty());
      var rows=f.tasks.list(1,20);assertThat(rows).hasSize(c.path("tasks").size());
      if(!rows.isEmpty()){
        var expected=c.path("tasks").get(0);var actual=rows.getFirst();
        assertThat(actual.type()).isEqualTo(expected.path("type").asText());assertThat(actual.payload()).isEqualTo(expected.path("payload"));assertThat(actual.queue()).isEqualTo(expected.path("queue").asText());assertThat(actual.maxAttempts()).isEqualTo(expected.path("max_retry").asInt()+1);assertThat(actual.timeoutMillis()).isEqualTo(expected.path("timeout").asLong());assertThat(actual.availableAt()).isEqualTo(actual.createdAt());
      }
    }));
  }
  @Test void enqueueRejectsWrongOwnerAndCapturesSnapshotWithFreshDeliveryId()throws Exception{
    var f=new DocumentProcessingCompatibilityTest();f.temp=temp;f.database(1000,true);
    var base=json.createObjectNode().put("id","kb").put("tenant_id",1).put("embedding_model_id","captured-model").put("type","faq").put("vector_store_id","captured-store");
    var engines=json.createArrayNode().add(json.createObjectNode().put("retriever_engine_type","sqlite").put("retriever_type","vector"));
    var producer=new IndexDeleteEnqueuer(f.tasks,json);
    assertThat(producer.enqueue(2,base,List.of("chunk"),engines,null)).isFalse();assertThat(f.tasks.list(2,20)).isEmpty();
    var trace=json.createObjectNode().put("lf_trace_id","trace").put("lf_session_id","session").put("private","not-carried");
    assertThat(producer.enqueue(1,base,List.of("chunk"),engines,trace)).isTrue();assertThat(producer.enqueue(1,base,List.of("chunk"),engines,trace)).isTrue();
    base.put("vector_store_id","new-store");engines.removeAll();trace.put("lf_trace_id","mutated");
    var rows=f.tasks.list(1,20);assertThat(rows).hasSize(2);assertThat(rows.getFirst().id()).isNotEqualTo(rows.getLast().id());
    for(var job:rows){assertThat(job.payload().path("vector_store_id").asText()).isEqualTo("captured-store");assertThat(job.payload().path("effective_engines").size()).isEqualTo(1);assertThat(job.payload().path("lf_trace_id").asText()).isEqualTo("trace");assertThat(job.payload().has("private")).isFalse();}
  }
}
