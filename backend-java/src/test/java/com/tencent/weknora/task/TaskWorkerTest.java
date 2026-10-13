package com.tencent.weknora.task;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import static org.assertj.core.api.Assertions.*;

class TaskWorkerTest {
    Path database;TaskRepository tasks;ObjectMapper json=new ObjectMapper();
    @BeforeEach void initialize() throws Exception {database=Files.createTempFile("weknora-java-worker-",".db");var source=new DriverManagerDataSource("jdbc:sqlite:"+database);source.setDriverClassName("org.sqlite.JDBC");try(var connection=source.getConnection()){ScriptUtils.executeSqlScript(connection,new FileSystemResource("migration/sql/java-tasks.sql"));}tasks=new TaskRepository(new JdbcTemplate(source),json,new DataSourceTransactionManager(source));}
    @AfterEach void clean() throws Exception{Files.deleteIfExists(database);}
    TaskRecord enqueue(String key,String type){return tasks.enqueue(1,type,key,json.createObjectNode(),3,Duration.ZERO);}
    TaskRecord await(String id,String state) throws Exception {long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<deadline){var record=tasks.get(1,id).orElseThrow();if(record.status().equals(state))return record;Thread.sleep(10);}throw new AssertionError("Expected task state "+state+", got "+tasks.get(1,id));}
    @Test void successfulHandlerStoresResultAndHonorsBoundedConcurrency() throws Exception {
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);
        TaskHandler handler=new TaskHandler(){public String type(){return "work";}public JsonNode execute(TaskRecord job,TaskCancellation cancel)throws Exception{started.countDown();release.await();cancel.check();return json.createObjectNode().put("ok",true);}};
        var first=enqueue("first","work");var second=enqueue("second","work");
        try(var worker=new TaskWorker(tasks,List.of(handler),1,Duration.ofSeconds(2))){worker.poll();assertThat(started.await(2,TimeUnit.SECONDS)).isTrue();
            var records=tasks.list(1,200);assertThat(records.stream().map(TaskRecord::status)).containsExactlyInAnyOrder("running","pending");
            var running=records.stream().filter(job->job.status().equals("running")).findFirst().orElseThrow();var pending=records.stream().filter(job->job.status().equals("pending")).findFirst().orElseThrow();
            release.countDown();assertThat(await(running.id(),"succeeded").result().path("ok").asBoolean()).isTrue();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(worker.activeCount()!=0&&System.nanoTime()<deadline)Thread.sleep(5);assertThat(worker.activeCount()).isZero();worker.poll();await(pending.id(),"succeeded");}
    }
    @Test void missingHandlerAndTerminalFailureGoToDeadLetterWithoutDependencySecret() throws Exception {
        var missing=enqueue("missing","unknown");try(var worker=new TaskWorker(tasks,List.of(),1,Duration.ofSeconds(2))){worker.poll();assertThat(await(missing.id(),"dead").lastError()).contains("No Java handler");}
        TaskHandler handler=new TaskHandler(){public String type(){return "failing";}public JsonNode execute(TaskRecord job,TaskCancellation cancel)throws Exception{throw new TaskFailure("Invalid document structure",false);}};
        var failed=enqueue("terminal","failing");try(var worker=new TaskWorker(tasks,List.of(handler),1,Duration.ofSeconds(2))){worker.poll();assertThat(await(failed.id(),"dead").lastError()).isEqualTo("Invalid document structure");}
    }
    @Test void arbitraryDependencyExceptionsRetryWithClassOnlyAndNoCredentials() throws Exception {
        TaskHandler handler=new TaskHandler(){public String type(){return "network";}public JsonNode execute(TaskRecord job,TaskCancellation cancel)throws Exception{throw new java.io.IOException("https://secret:password@private.invalid?api_key=secret");}};
        var failed=enqueue("retry","network");try(var worker=new TaskWorker(tasks,List.of(handler),1,Duration.ofSeconds(2))){worker.poll();var state=await(failed.id(),"retry");assertThat(state.lastError()).isEqualTo("Java task handler failed: IOException");assertThat(state.availableAt()).isGreaterThan(state.updatedAt());}
    }
    @Test void cancelInterruptsRunningHandlerAndNeverPublishesResult() throws Exception {
        var started=new CountDownLatch(1);
        TaskHandler handler=new TaskHandler(){public String type(){return "long";}public JsonNode execute(TaskRecord job,TaskCancellation cancel)throws Exception{started.countDown();Thread.sleep(10000);return json.createObjectNode().put("wrong",true);}};
        var job=enqueue("cancel","long");try(var worker=new TaskWorker(tasks,List.of(handler),1,Duration.ofMillis(600))){worker.poll();assertThat(started.await(2,TimeUnit.SECONDS)).isTrue();tasks.cancel(1,job.id());assertThat(await(job.id(),"canceled").result()).isNull();}
    }
    @Test void deadlineStopsHandlerAndPersistsRetryRatherThanAFalseSuccess() throws Exception {
        TaskHandler handler=new TaskHandler(){public String type(){return "deadline";}public Duration timeout(){return Duration.ofMillis(100);}public JsonNode execute(TaskRecord job,TaskCancellation cancel)throws Exception{Thread.sleep(10000);return json.createObjectNode();}};
        var job=enqueue("timeout","deadline");try(var worker=new TaskWorker(tasks,List.of(handler),1,Duration.ofSeconds(2))){worker.poll();assertThat(await(job.id(),"retry").lastError()).isEqualTo("Java task deadline exceeded");}
    }
    @Test void shutdownLeavesRunningWorkRecoverableAndDuplicateTypeFailsBeforeRunning() throws Exception {
        var started=new CountDownLatch(1);TaskHandler handler=new TaskHandler(){public String type(){return "shutdown";}public JsonNode execute(TaskRecord job,TaskCancellation cancel)throws Exception{started.countDown();Thread.sleep(10000);return json.createObjectNode();}};
        assertThatThrownBy(()->new TaskWorker(tasks,List.of(handler,handler),1,Duration.ofSeconds(2))).isInstanceOf(IllegalArgumentException.class);
        var job=enqueue("shutdown","shutdown");var worker=new TaskWorker(tasks,List.of(handler),1,Duration.ofSeconds(2));worker.poll();assertThat(started.await(2,TimeUnit.SECONDS)).isTrue();worker.close();assertThat(await(job.id(),"retry").lastError()).isEqualTo("Java worker stopped");
    }
}
