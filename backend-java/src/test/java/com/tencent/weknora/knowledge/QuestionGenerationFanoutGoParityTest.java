package com.tencent.weknora.knowledge;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Real Go postprocess handler output and asynq queue contract, including historical invalid rows. */
class QuestionGenerationFanoutGoParityTest {
  @TempDir Path temp;final ObjectMapper json=new ObjectMapper();
  static {try{com.tencent.weknora.retrieval.NativeDatabaseRuntime.initialize();}catch(java.sql.SQLException e){throw new ExceptionInInitializerError(e);}}
  @TestFactory Stream<DynamicTest> productionGoEnrichmentFanout()throws Exception {JsonNode rows;try(var in=getClass().getResourceAsStream("/go-question-generation-fanout.json")){rows=json.readTree(in);}assertThat(rows.size()).isEqualTo(17);var seq=new AtomicInteger();return StreamSupport.stream(rows.spliterator(),false).map(row->DynamicTest.dynamicTest(row.path("name").asText(),()->compare(row,seq.incrementAndGet())));}
  void compare(JsonNode oracle,int n)throws Exception {
    String name=oracle.path("name").asText();var f=new DocumentProcessingCompatibilityTest();f.temp=temp;f.database(n,true);var k=f.seed(json.createObjectNode().put("state","processing"));f.jdbc.update("UPDATE knowledges SET summary_status='none',metadata=? WHERE id='knowledge'",json.createObjectNode().set("process_overrides",json.createObjectNode().put("summary_enabled",oracle.path("summary").asBoolean())).toString());f.jdbc.update("UPDATE knowledge_bases SET question_generation_config=? WHERE id='kb'",json.createObjectNode().put("enabled",oracle.path("question").asBoolean()).put("question_count",oracle.path("count").asInt()).toString());
    if(name.equals("disabled-indexes"))f.jdbc.update("UPDATE knowledge_bases SET indexing_strategy=? WHERE id='kb'","{\"vector_enabled\":false,\"keyword_enabled\":false,\"graph_enabled\":true,\"wiki_enabled\":false}");
    var helper=new QuestionGenerationGoParityTest();for(var chunk:oracle.path("chunks"))helper.chunk(f,chunk.path("id").asText(),chunk.path("content").asText(),chunk.path("chunk_index").asInt(),chunk.path("start_at").asInt(),chunk.path("end_at").asInt(),chunk.path("chunk_type").asText(),chunk.path("parent_chunk_id").asText(),chunk.path("is_enabled").asBoolean(),0);
    if(name.equals("disabled-chunk"))f.jdbc.update("UPDATE chunks SET is_enabled=false WHERE id='chunk-00'");if(name.equals("foreign-document"))f.jdbc.update("UPDATE chunks SET knowledge_id='foreign' WHERE id='chunk-00'");if(name.equals("foreign-kb"))f.jdbc.update("UPDATE chunks SET knowledge_base_id='foreign' WHERE id='chunk-00'");
    var job=f.job(k,"knowledge:post_process");f.jdbc.update("UPDATE java_task_jobs SET payload=? WHERE id=?",oracle.path("payload").toString(),job.id());job=f.tasks.get(1,job.id()).orElseThrow();var reads=new KnowledgeProcessingSpanRepository(f.jdbc,json);var tracker=new KnowledgeProcessingSpanTracker(new KnowledgeProcessingSpanWriteRepository(f.jdbc,json,reads),json);tracker.open(1,"knowledge","native-fanout-oracle");var captured=json.createArrayNode();KnowledgeFileUpload.Enqueuer producer=(owner,type,p,options)->{captured.add(json.createObjectNode().put("type",type).put("queue",options.queue()).put("max_retry",options.maxRetries()).put("timeout",options.timeout().toMillis()).set("payload",p.deepCopy()));return UUID.randomUUID().toString();};var repository=new QuestionGenerationRepository(f.jdbc,json,f.knowledge,f.manager,Clock.systemUTC());var fanout=new QuestionGenerationFanout(f.jdbc,json,repository,producer,f.manager,Clock.systemUTC(),tracker);var post=new KnowledgePostProcessHandler(f.processing,json,tracker,null,fanout);Exception failure=null;try{post.execute(job,f.cancellation(job));}catch(Exception e){failure=e;}
    assertThat(failure!=null).as("actual Go terminal/retry decision").isEqualTo(!oracle.path("error").asText().isEmpty());if(name.equals("foreign-kb"))assertThat(failure).isInstanceOf(com.tencent.weknora.task.TaskFailure.class);assertThat(helper.numbers(captured)).as("actual task payloads/options/order").isEqualTo(helper.numbers(oracle.path("tasks")));
    var state=json.createObjectNode();for(String key:List.of("parse_status","summary_status","pending_subtasks_count"))state.set(key,json.valueToTree(f.jdbc.queryForMap("SELECT * FROM knowledges WHERE id='knowledge'").get(key)));assertThat(helper.numbers(state)).as("actual SQL planned counter/state").isEqualTo(helper.numbers(oracle.path("knowledge")));
    var spans=json.createArrayNode();for(var row:reads.list("knowledge",1).stream().filter(c->Set.of("postprocess","postprocess.question").contains(c.path("name").asText())).sorted(Comparator.comparing(c->c.path("name").asText())).toList()){var span=spans.addObject();for(String key:List.of("name","status","input","output"))span.set(key,row.has(key)?row.get(key):NullNode.instance);}assertThat(helper.numbers(spans)).as("actual stage/group trace fields").isEqualTo(helper.numbers(oracle.path("spans")));
  }
}
