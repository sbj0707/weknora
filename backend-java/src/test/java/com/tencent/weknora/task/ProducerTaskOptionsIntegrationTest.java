package com.tencent.weknora.task;

import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.knowledge.KnowledgeFileUpload.TaskOptions;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** Real database and worker boundaries: producers' options survive persistence and execution. */
class ProducerTaskOptionsIntegrationTest {
    Path database;JdbcTemplate jdbc;TaskRepository tasks;KnowledgeFileTaskEnqueuer producer;
    DriverManagerDataSource source;DataSourceTransactionManager manager;ObjectMapper json=new ObjectMapper();
    @BeforeEach void initialize() throws Exception {
        database=Files.createTempFile("weknora-native-producer-",".db");source=new DriverManagerDataSource("jdbc:sqlite:"+database);source.setDriverClassName("org.sqlite.JDBC");
        jdbc=new JdbcTemplate(source);manager=new DataSourceTransactionManager(source);tasks=new TaskRepository(jdbc,json,manager);producer=new KnowledgeFileTaskEnqueuer(tasks);
        try(var connection=source.getConnection()){ScriptUtils.executeSqlScript(connection,new FileSystemResource("migration/sql/java-tasks.sql"));}
    }
    @AfterEach void clean() throws Exception {Files.deleteIfExists(database);}
    TaskRecord enqueue(String type,String queue,int retries,Duration timeout){return tasks.get(1,producer.enqueue(1,type,json.createObjectNode().put("knowledge_id","same-document"),new TaskOptions(queue,retries,timeout))).orElseThrow();}
    @Test void uploadAndPostprocessOptionsSurviveRepositoryRestartWithoutAccidentalDeduplication(){
        var first=enqueue("document:process","default",3,Duration.ofHours(2));
        var second=enqueue("document:process","default",3,Duration.ofHours(2));
        var post=enqueue("knowledge:post_process","postprocess",3,Duration.ofMinutes(30));
        var summary=enqueue("datatable:summary","summary",3,Duration.ofMinutes(30));
        var reopened=new TaskRepository(new JdbcTemplate(source),json,manager);
        assertThat(first.id()).isNotEqualTo(second.id());
        for(var original:List.of(first,second,post,summary)){
            var stored=reopened.get(1,original.id()).orElseThrow();assertThat(stored).isEqualTo(original);
            assertThat(stored.maxAttempts()).isEqualTo(4);assertThat(reopened.get(2,stored.id())).isEmpty();
        }
        assertThat(first.timeoutMillis()).isEqualTo(Duration.ofHours(2).toMillis());
        assertThat(post.queue()).isEqualTo("postprocess");assertThat(summary.queue()).isEqualTo("summary");
    }
    @Test void maxRetryThreeAllowsExactlyFourExecutionsBeforeDeadLetter(){
        var job=enqueue("document:process","default",3,Duration.ofHours(2));
        for(int attempt=1;attempt<=4;attempt++){
            var lease=tasks.claim("core",Duration.ofSeconds(2),List.of("default")).orElseThrow();
            assertThat(lease.id()).isEqualTo(job.id());assertThat(lease.attempts()).isEqualTo(attempt);
            assertThat(tasks.fail(lease,"parser unavailable",true,Duration.ZERO)).isTrue();
            assertThat(tasks.get(1,job.id()).orElseThrow().status()).isEqualTo(attempt==4?"dead":"retry");
        }
        assertThat(tasks.claim("core",Duration.ofSeconds(2),List.of("default"))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM java_task_attempts",Integer.class)).isEqualTo(4);
        var noRetry=enqueue("document:process","default",0,Duration.ofHours(2));
        var first=tasks.claim("core",Duration.ofSeconds(2),List.of("default")).orElseThrow();
        tasks.fail(first,"parser unavailable",true,Duration.ZERO);assertThat(tasks.get(1,noRetry.id()).orElseThrow().status()).isEqualTo("dead");
    }
    @Test void poolCannotStealOtherQueuesEvenWhenTheirLeaseHasExpired(){
        var summary=enqueue("datatable:summary","summary",3,Duration.ofMinutes(30));
        assertThat(tasks.claim("core",Duration.ofSeconds(2),List.of("default"))).isEmpty();
        var summaryLease=tasks.claim("enrichment",Duration.ofSeconds(2),List.of("summary")).orElseThrow();
        jdbc.update("UPDATE java_task_jobs SET lease_until=0 WHERE id=?",summary.id());
        var document=enqueue("document:process","default",3,Duration.ofHours(2));
        var documentLease=tasks.claim("core",Duration.ofSeconds(2),List.of("default")).orElseThrow();assertThat(documentLease.id()).isEqualTo(document.id());
        assertThat(tasks.claim("core",Duration.ofSeconds(2),List.of("default"))).isEmpty();
        var recovered=tasks.claim("enrichment-2",Duration.ofSeconds(2),List.of("summary")).orElseThrow();
        assertThat(recovered.id()).isEqualTo(summary.id());assertThat(recovered.attempts()).isEqualTo(2);
        assertThat(tasks.complete(summaryLease,json.createObjectNode())).isFalse();
    }
    @Test void priorityOrderChoosesFirstNonemptyEligibleQueueAndLeavesDelayedTasksAlone(){
        var summary=enqueue("datatable:summary","summary",3,Duration.ofMinutes(30));
        var document=enqueue("document:process","default",3,Duration.ofHours(2));
        jdbc.update("UPDATE java_task_jobs SET available_at=? WHERE id=?",System.currentTimeMillis()+60000,document.id());
        assertThat(tasks.claim("shared",Duration.ofSeconds(2),List.of("default","summary")).orElseThrow().id()).isEqualTo(summary.id());
        jdbc.update("UPDATE java_task_jobs SET available_at=0 WHERE id=?",document.id());
        var secondSummary=enqueue("datatable:summary","summary",3,Duration.ofMinutes(30));
        assertThat(tasks.claim("shared",Duration.ofSeconds(2),List.of("summary","default")).orElseThrow().id()).isEqualTo(secondSummary.id());
        assertThat(tasks.get(1,document.id()).orElseThrow().status()).isEqualTo("pending");
    }
    @Test void perJobDeadlineOverridesHandlerDefaultInBothDirections() throws Exception {
        TaskHandler handler=new TaskHandler(){public String type(){return "document:process";}public Duration timeout(){return Duration.ofMillis(30);}public JsonNode execute(TaskRecord job,TaskCancellation cancel)throws Exception{Thread.sleep(job.payload().path("delay").asLong());cancel.check();return json.createObjectNode().put("ok",true);}};
        var shortDeadline=tasks.enqueue(1,handler.type(),"short",json.createObjectNode().put("delay",10000),2,Duration.ZERO,"default",Duration.ofMillis(100));
        try(var worker=new TaskWorker(tasks,List.of(handler),1,Duration.ofSeconds(2),Map.of("default",1))){worker.poll();assertThat(await(shortDeadline.id(),"retry").lastError()).isEqualTo("Java task deadline exceeded");}
        var longerDeadline=tasks.enqueue(1,handler.type(),"longer",json.createObjectNode().put("delay",200),2,Duration.ZERO,"default",Duration.ofSeconds(2));
        try(var worker=new TaskWorker(tasks,List.of(handler),1,Duration.ofSeconds(2),Map.of("default",1))){worker.poll();assertThat(await(longerDeadline.id(),"succeeded").result().path("ok").asBoolean()).isTrue();}
    }
    @Test void producerEnqueueRollsBackWithBusinessEffectsAndRejectsInvalidOptions(){
        jdbc.execute("CREATE TABLE document_states(id TEXT PRIMARY KEY)");
        assertThatThrownBy(()->new TransactionTemplate(manager).executeWithoutResult(status->{jdbc.update("INSERT INTO document_states VALUES('partial')");enqueue("document:process","default",3,Duration.ofHours(2));throw new IllegalStateException("index insert failed");})).isInstanceOf(IllegalStateException.class);
        assertThat(tasks.list(1,200)).isEmpty();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM document_states",Integer.class)).isZero();
        for(var invalid:List.of(new TaskOptions("",3,Duration.ofHours(2)),new TaskOptions("default",-1,Duration.ofHours(2)),new TaskOptions("default",3,Duration.ZERO)))
            assertThatThrownBy(()->producer.enqueue(1,"document:process",json.createObjectNode(),invalid)).isInstanceOf(IllegalArgumentException.class);
        assertThat(tasks.list(1,200)).isEmpty();
    }
    @Test void explicitSchemaUpgradePreservesExistingPendingJobAndItsRetryBudget()throws Exception{
        jdbc.execute("DROP INDEX java_task_jobs_claim");
        jdbc.execute("ALTER TABLE java_task_jobs DROP COLUMN queue");
        jdbc.execute("ALTER TABLE java_task_jobs DROP COLUMN timeout_millis");
        jdbc.execute("CREATE INDEX java_task_jobs_claim ON java_task_jobs(status,available_at,lease_until)");
        jdbc.update("INSERT INTO java_task_jobs(id,tenant_id,type,idempotency_key,payload,status,max_attempts,available_at,created_at,updated_at) VALUES('old-job',1,'document:process','old-key','{}','pending',4,0,0,0)");
        try(var connection=source.getConnection()){ScriptUtils.executeSqlScript(connection,new FileSystemResource("migration/sql/java-tasks-options-upgrade.sql"));}
        var restored=tasks.get(1,"old-job").orElseThrow();assertThat(restored.queue()).isEqualTo("default");
        assertThat(restored.timeoutMillis()).isZero();assertThat(restored.maxAttempts()).isEqualTo(4);
        assertThat(tasks.claim("core",Duration.ofSeconds(2),List.of("default")).orElseThrow().id()).isEqualTo("old-job");
    }
    TaskRecord await(String id,String expected)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<deadline){var record=tasks.get(1,id).orElseThrow();if(record.status().equals(expected))return record;Thread.sleep(10);}
        throw new AssertionError("Expected "+expected+" for "+tasks.get(1,id));
    }
}
