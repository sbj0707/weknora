package com.tencent.weknora.knowledge;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.task.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;

/** Actual Go Asynq worker, SQL/vec/FTS, task inspector, audit and pending-op differential. */
class KnowledgeReparseWorkerGoParityTest extends KnowledgeReparseGoHttpParityTest {
  @Override @TestFactory Stream<DynamicTest> folders()throws Exception{
    JsonNode cases;try(var in=getClass().getResourceAsStream("/go-knowledge-reparse-worker.json")){assertThat(in).isNotNull();cases=json.readTree(in);}
    assertThat(cases.size()).isGreaterThan(50);var no=new AtomicInteger();
    return StreamSupport.stream(cases.spliterator(),false).map(oracle->DynamicTest.dynamicTest(oracle.path("name").asText(),()->compare(oracle,no.incrementAndGet())));
  }
  @Override MockEnvironment environment(JsonNode oracle){return super.environment(oracle).withProperty("WEKNORA_LANGUAGE",oracle.path("worker_language").asText());}
  @Override int queueOverhead(){return 1;}
  @Override JsonNode invoke(DocumentProcessingCompatibilityTest f,KnowledgeReparseService service,org.springframework.test.web.servlet.MockMvc ignored,JsonNode oracle)throws Exception{
    var queued=f.tasks.enqueue(1,"knowledge:list_reparse",UUID.randomUUID().toString(),json.createObjectNode(),4,Duration.ZERO,"low",Duration.ofHours(1));
    f.jdbc.update("UPDATE java_task_jobs SET id='batch-job',payload=? WHERE id=?",oracle.path("request_body").asText(),queued.id());
    var job=f.tasks.claim("worker",Duration.ofMinutes(5),List.of("low")).orElseThrow();
    var handler=new KnowledgeListReparseHandler(f.jdbc,json,f.knowledge,service,new KnowledgeProcessingLanguage(environment(oracle)));
    String error="";boolean skip=false;
    try{handler.execute(job,f.cancellation(job));}catch(TaskFailure failure){error=failure.getMessage();skip=!failure.retryable();}
    return json.createObjectNode().put("error",error).put("skip_retry",skip);
  }
}
