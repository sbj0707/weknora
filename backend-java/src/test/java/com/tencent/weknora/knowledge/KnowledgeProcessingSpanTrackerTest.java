package com.tencent.weknora.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Executes native writes against the existing production schema, not mocks. */
class KnowledgeProcessingSpanTrackerTest {
  final ObjectMapper json=new ObjectMapper();
  final MutableClock clock=new MutableClock();
  SingleConnectionDataSource source;
  Path path;
  JdbcTemplate jdbc;
  KnowledgeProcessingSpanRepository reads;
  KnowledgeProcessingSpanWriteRepository writes;
  KnowledgeProcessingSpanTracker tracker;
  @BeforeEach void setup() throws Exception {
    path=Files.createTempFile("native-span-tracker-",".db");source=new SingleConnectionDataSource("jdbc:sqlite:"+path,true);jdbc=new JdbcTemplate(source);
    for(String script:List.of("000000_init.up.sql","000008_processing_spans_and_pending_subtasks.up.sql"))ScriptUtils.executeSqlScript(source.getConnection(),new FileSystemResource("../migrations/sqlite/"+script));
    jdbc.update("INSERT INTO tenants(id,name,business) VALUES(7,'Owner','test')");
    jdbc.update("INSERT INTO knowledge_bases(id,name,tenant_id,embedding_model_id,summary_model_id) VALUES('kb','KB',7,'','')");
    jdbc.update("INSERT INTO knowledges(id,tenant_id,knowledge_base_id,type,title,source,parse_status) VALUES('doc',7,'kb','file','Document','test','processing')");
    reads=new KnowledgeProcessingSpanRepository(jdbc,json);writes=new KnowledgeProcessingSpanWriteRepository(jdbc,json,clock,reads);
    var counter=new java.util.concurrent.atomic.AtomicInteger();tracker=new KnowledgeProcessingSpanTracker(writes,json,clock,()->"span"+counter.incrementAndGet());
  }
  @AfterEach void close() throws Exception {source.destroy();Files.deleteIfExists(path);}
  ObjectNode map(String field,String value) {return json.createObjectNode().put(field,value);}
  ObjectNode named(String name) {return reads.list("doc",1).stream().filter(row->row.path("name").asText().equals(name)).reduce((a,b)->b).orElseThrow();}
  @Test void nativeLifecycleProducesRealTraceAndKeepsInputOnEnd() {
    tracker.open(7,"doc","native-trace");var stage=tracker.beginStage(7,"doc",1,"embedding",map("model_id","embedding"));
    clock.advance(Duration.ofMillis(250));tracker.end(stage,json.createObjectNode().put("vectors_written",2));
    var row=named("embedding");assertEquals("embedding",row.path("input").path("model_id").asText());assertEquals(250,row.path("duration_ms").asLong());assertEquals("done",row.path("status").asText());
    tracker.finalizeAttempt(7,"doc",1,"done",json.createObjectNode().put("chunks",2),"","");
    var knowledge=json.createObjectNode().put("id","doc").put("parse_status","completed").put("updated_at",clock.instant().toString());
    var result=new KnowledgeProcessingSpanService(reads,json,clock).read(knowledge,null);assertEquals(1,result.path("attempt").asLong());assertEquals("done",result.path("trace").path("status").asText());assertEquals("native-trace",result.path("trace").path("metadata").path("langfuse_trace_id").asText());
    assertEquals(5,result.path("trace").path("children").size());
  }
  @Test void stageReentryKeepsIdentityChildrenAndOldOutputAsProductionGoDoes() {
    tracker.open(7,"doc","");var stage=tracker.beginStage(7,"doc",1,"embedding",map("model_id","old"));var child=tracker.beginSubspan(stage,"embedding.batch[0]","subspan",null);
    tracker.end(stage,json.createObjectNode().put("vectors_written",2));clock.advance(Duration.ofSeconds(1));var retry=tracker.beginStage(7,"doc",1,"embedding",map("model_id","new"));
    assertEquals(stage.id(),retry.id());var row=named("embedding");assertEquals("running",row.path("status").asText());assertEquals(2,row.path("output").path("vectors_written").asInt());assertFalse(row.has("finished_at"));assertFalse(row.has("duration_ms"));assertEquals(retry.id(),named("embedding.batch[0]").path("parent_span_id").asText());
  }
  @Test void stageFailureCancelsOnlyDependentStagesAndClosesRoot() {
    tracker.open(7,"doc","");var doc=tracker.beginStage(7,"doc",1,"docreader",null);tracker.end(doc,null);
    var chunk=tracker.beginStage(7,"doc",1,"chunking",null);var embedding=tracker.beginStage(7,"doc",1,"embedding",null);tracker.beginSubspan(embedding,"embedding.batch[0]","subspan",null);
    tracker.beginStage(7,"doc",1,"multimodal",null);tracker.beginStage(7,"doc",1,"postprocess",null);
    clock.advance(Duration.ofSeconds(1));tracker.fail(chunk,"CHUNK_FAILED","bad",new RuntimeException("PRIVATE_ERROR_DETAIL"));
    assertEquals("done",named("docreader").path("status").asText());assertEquals("failed",named("chunking").path("status").asText());assertEquals("failed",named("knowledge_processing").path("status").asText());
    for(String name:List.of("embedding","multimodal","postprocess","embedding.batch[0]"))assertEquals("cancelled",named(name).path("status").asText());
    assertFalse(reads.list("doc",1).toString().contains("PRIVATE_ERROR_DETAIL"));assertEquals("PRIVATE_ERROR_DETAIL",jdbc.queryForObject("SELECT error_detail FROM knowledge_processing_spans WHERE name='chunking'",String.class));
  }
  @Test void embeddingFailureKeepsIndependentMultimodalRunning() {
    tracker.open(7,"doc","");var embedding=tracker.beginStage(7,"doc",1,"embedding",null);tracker.beginStage(7,"doc",1,"multimodal",null);tracker.beginStage(7,"doc",1,"postprocess",null);
    tracker.fail(embedding,"FAIL","bad",null);assertEquals("running",named("multimodal").path("status").asText());assertEquals("cancelled",named("postprocess").path("status").asText());
  }
  @Test void supersedeCancelsOpenSameNameThenCreatesNewChild() {
    tracker.open(7,"doc","");var stage=tracker.beginStage(7,"doc",1,"multimodal",null);var first=tracker.beginSubspan(stage,"multimodal.image[0]","generation",map("image","first"));
    clock.advance(Duration.ofSeconds(1));var second=tracker.beginSubspan(stage,"multimodal.image[0]","invalid-kind",null);
    var rows=reads.list("doc",1);assertEquals("cancelled",rows.stream().filter(row->row.path("span_id").asText().equals(first.id())).findFirst().orElseThrow().path("status").asText());assertNotEquals(first.id(),second.id());assertEquals("subspan",second.kind());assertEquals(first.id(),tracker.lookupName(7,"doc",1,"multimodal.image[0]").id());
  }
  @Test void abortSweepsChildrenUnderAlreadyDoneStagesAndPreservesTerminalRows() {
    tracker.open(7,"doc","");var stage=tracker.beginStage(7,"doc",1,"multimodal",null);var child=tracker.beginSubspan(stage,"multimodal.image[0]","generation",null);tracker.end(stage,null);
    tracker.abort(7,"doc",1,"","requested"," ");assertEquals("done",named("multimodal").path("status").asText());assertEquals("cancelled",named("multimodal.image[0]").path("status").asText());assertEquals("cancelled",named("knowledge_processing").path("status").asText());
    assertTrue(named("multimodal.image[0]").has("finished_at"));
  }
  @Test void subspanProgressDoesNotWriteParentKnowledgeHeartbeat() {
    tracker.open(7,"doc","");var stage=tracker.beginStage(7,"doc",1,"multimodal",null);String before=jdbc.queryForObject("SELECT updated_at FROM knowledges WHERE id='doc'",String.class);
    clock.advance(Duration.ofSeconds(5));var child=tracker.beginSubspan(stage,"multimodal.image[0]","generation",null);tracker.end(child,null);
    assertEquals(before,jdbc.queryForObject("SELECT updated_at FROM knowledges WHERE id='doc'",String.class));assertTrue(Instant.parse(named("multimodal.image[0]").path("updated_at").asText()).isAfter(Instant.parse(KnowledgeProcessingSpanRepository.timestamp(before))));
  }
  @Test void foreignOwnerCannotAllocateWriteOrReadTracingRows() {
    tracker.open(7,"doc","");long before=jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_processing_spans",Long.class);
    assertThrows(IllegalArgumentException.class,()->tracker.open(8,"doc","foreign"));assertNull(tracker.beginStage(8,"doc",1,"embedding",null));assertThrows(IllegalArgumentException.class,()->writes.list(8,"doc",1));assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_processing_spans",Long.class));
  }
  @Test void missingTableDoesNotBreakBestEffortStagesAndOpenReportsFailure() {
    jdbc.execute("DROP TABLE knowledge_processing_spans");assertNull(tracker.beginStage(7,"doc",1,"embedding",null));assertThrows(RuntimeException.class,()->tracker.open(7,"doc",""));
  }
  @Test void mutationStillFencesOwnerChangedAfterInitialCheck() {
    tracker.open(7,"doc","");
    var racingJdbc=new JdbcTemplate(source) {
      @Override public List<Map<String,Object>> queryForList(String sql,Object... args) {
        var result=super.queryForList(sql,args);
        if(sql.startsWith("SELECT id FROM knowledges"))jdbc.update("UPDATE knowledges SET tenant_id=8 WHERE id='doc'");
        return result;
      }
    };
    var racingWrites=new KnowledgeProcessingSpanWriteRepository(racingJdbc,json,clock,reads);
    var row=named("knowledge_processing").put("status","done");
    assertThrows(IllegalArgumentException.class,()->racingWrites.upsert(7,row));
    assertEquals("running",named("knowledge_processing").path("status").asText());
    jdbc.update("UPDATE knowledges SET tenant_id=7 WHERE id='doc'");
    assertEquals(0,racingWrites.cancelOpen(7,"doc",1,null,"FOREIGN","not allowed"));
    assertEquals("running",named("knowledge_processing").path("status").asText());
  }
  @Test void rootWithoutStartStaysNullOnFinalizeAndCodesUseGoUnicodeWhitespace() {
    tracker.open(7,"doc","");jdbc.update("UPDATE knowledge_processing_spans SET started_at=NULL");
    tracker.finalizeAttempt(7,"doc",1,"failed",null,"\u00a0FAIL\u0085","bad");
    assertFalse(named("knowledge_processing").has("started_at"));assertFalse(named("knowledge_processing").has("duration_ms"));
    assertEquals("FAIL",named("knowledge_processing").path("error_code").asText());
  }
  @Test void nonpositiveInternalListScansHistoryAndStageReentryKeepsItsStoredAttempt() {
    tracker.open(7,"doc","");tracker.beginStage(7,"doc",1,"embedding",map("model_id","old"));
    tracker.open(7,"doc","");var last=tracker.beginStage(7,"doc",2,"embedding",map("model_id","latest"));
    assertEquals(4,writes.list(7,"doc",0).size());
    var retried=tracker.beginStage(7,"doc",0,"embedding",map("model_id","retry"));
    assertEquals(last.id(),retried.id());assertEquals(2,retried.attempt());assertEquals(2,reads.list("doc",2).size());
  }
  @Test void unicodeNamesAndErrorByteLimitsPreserveValidReplacementRune() {
    String name="问".repeat(300);String fitted=KnowledgeProcessingSpanTracker.fitName(name);assertEquals(255,fitted.codePointCount(0,fitted.length()));assertEquals(fitted,KnowledgeProcessingSpanTracker.fitName(name));
    assertEquals("问",KnowledgeProcessingSpanTracker.truncate("问问",4));assertEquals("\uFFFD",KnowledgeProcessingSpanTracker.truncate("\uFFFD",3));assertEquals("ab",KnowledgeProcessingSpanTracker.clean("a\u0000b"));
  }
  static final class MutableClock extends Clock {
    Instant now=Instant.parse("2026-10-14T01:02:03.123456789Z");
    void advance(Duration delta) {now=now.plus(delta);}
    @Override public ZoneId getZone() {return ZoneOffset.UTC;}
    @Override public Clock withZone(ZoneId zone) {return Clock.fixed(now,zone);}
    @Override public Instant instant() {return now;}
  }
}
