package com.tencent.weknora.task;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** Real SQLite transactions exercise queue state changes, not a mocked SQL mirror. */
class TaskRepositoryTest {
    Path database;JdbcTemplate jdbc;TaskRepository tasks;DataSourceTransactionManager manager;ObjectMapper json=new ObjectMapper();MutableClock clock=new MutableClock();
    @BeforeEach void initialize() throws Exception {
        database=Files.createTempFile("weknora-java-tasks-",".db");var source=new DriverManagerDataSource("jdbc:sqlite:"+database);source.setDriverClassName("org.sqlite.JDBC");
        jdbc=new JdbcTemplate(source);manager=new DataSourceTransactionManager(source);tasks=new TaskRepository(jdbc,json,manager,clock);
        try(var connection=source.getConnection()){ScriptUtils.executeSqlScript(connection,new FileSystemResource("migration/sql/java-tasks.sql"));}
        jdbc.execute("CREATE TABLE business_values(id TEXT PRIMARY KEY,value TEXT)");
    }
    @AfterEach void cleanup() throws Exception {Files.deleteIfExists(database);}
    TaskRecord enqueue(String key,int attempts){return tasks.enqueue(1,"parse",key,json.createObjectNode().put("document_id","doc"),attempts,Duration.ZERO);}
    TaskRecord claim(){return tasks.claim("worker-1",Duration.ofSeconds(3)).orElseThrow();}
    @Test void idempotencyIsTenantAndTypeScopedAndDoesNotOverwritePayload(){
        var first=enqueue("same",3);var duplicate=tasks.enqueue(1,"parse","same",json.createObjectNode().put("changed",true),10,Duration.ofDays(1));assertThat(duplicate.id()).isEqualTo(first.id());assertThat(duplicate.payload()).isEqualTo(first.payload());assertThat(duplicate.maxAttempts()).isEqualTo(3);
        assertThat(tasks.enqueue(2,"parse","same",json.createObjectNode(),3,Duration.ZERO).id()).isNotEqualTo(first.id());assertThat(tasks.enqueue(1,"embed","same",json.createObjectNode(),3,Duration.ZERO).id()).isNotEqualTo(first.id());assertThat(tasks.get(2,first.id())).isEmpty();assertThat(tasks.cancel(2,first.id())).isFalse();
    }
    @Test void enqueueJoinsBusinessTransactionAndRollsBackAtomically(){
        assertThatThrownBy(()->new TransactionTemplate(manager).executeWithoutResult(status->{jdbc.update("INSERT INTO business_values VALUES('one','value')");enqueue("atomic",3);throw new IllegalStateException("rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM business_values",Long.class)).isZero();assertThat(tasks.list(1,200)).isEmpty();
    }
    @Test void leaseExcludesOtherWorkerAndCompletionIsFencedAcrossRecovery(){
        var job=enqueue("lease",3);var old=claim();assertThat(tasks.claim("worker-2",Duration.ofSeconds(3))).isEmpty();clock.advance(Duration.ofSeconds(4));var current=tasks.claim("worker-2",Duration.ofSeconds(3)).orElseThrow();assertThat(current.attempts()).isEqualTo(2);assertThat(tasks.complete(old,json.createObjectNode())).isFalse();assertThat(tasks.renew(old,Duration.ofSeconds(3))).isFalse();assertThat(tasks.complete(current,json.createObjectNode().put("done",true))).isTrue();assertThat(tasks.get(1,job.id()).orElseThrow().result().path("done").asBoolean()).isTrue();assertThat(jdbc.queryForObject("SELECT outcome FROM java_task_attempts WHERE task_id=? AND attempt=1",String.class,job.id())).isEqualTo("lease_expired");
    }
    @Test void retryBackoffDeadLetterAndOperatorRetryKeepMonotonicFences(){
        var job=enqueue("retry",2);var first=claim();assertThat(tasks.fail(first,"transient",true,Duration.ofSeconds(10))).isTrue();assertThat(tasks.claim("worker-2",Duration.ofSeconds(3))).isEmpty();clock.advance(Duration.ofSeconds(10));var second=claim();assertThat(tasks.fail(second,"final",true,Duration.ZERO)).isTrue();assertThat(tasks.get(1,job.id()).orElseThrow().status()).isEqualTo("dead");assertThat(jdbc.queryForObject("SELECT error FROM java_task_dead_letters WHERE task_id=?",String.class,job.id())).isEqualTo("final");
        assertThat(tasks.retryDead(2,job.id(),1)).isFalse();assertThat(tasks.retryDead(1,job.id(),1)).isTrue();var third=claim();assertThat(third.attempts()).isEqualTo(3);assertThat(tasks.complete(third,json.createObjectNode())).isTrue();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM java_task_dead_letters",Long.class)).isZero();
    }
    @Test void cancellationOverridesCompletionAndCannotCancelTerminalJobs(){
        var pending=enqueue("pending",3);assertThat(tasks.cancel(1,pending.id())).isTrue();assertThat(tasks.claim("worker",Duration.ofSeconds(3))).isEmpty();assertThat(tasks.get(1,pending.id()).orElseThrow().status()).isEqualTo("canceled");
        var running=enqueue("running",3);var lease=claim();assertThat(tasks.cancel(1,running.id())).isTrue();assertThat(tasks.canceledOrLeaseLost(lease)).isTrue();assertThat(tasks.renew(lease,Duration.ofSeconds(3))).isFalse();assertThat(tasks.complete(lease,json.createObjectNode().put("bad",true))).isTrue();var after=tasks.get(1,running.id()).orElseThrow();assertThat(after.status()).isEqualTo("canceled");assertThat(after.result()).isNull();assertThat(tasks.cancel(1,running.id())).isFalse();
    }
    @Test void cancellationOfExpiredWorkerNeverRestartsItsBusinessHandler(){
        var job=enqueue("cancel-expired",3);claim();tasks.cancel(1,job.id());clock.advance(Duration.ofSeconds(4));assertThat(tasks.claim("next",Duration.ofSeconds(3))).isEmpty();assertThat(tasks.get(1,job.id()).orElseThrow().status()).isEqualTo("canceled");
    }
    @Test void abandonedFinalAttemptIsDeadLetteredWithoutExecutingAgain(){
        var job=enqueue("expired-budget",1);claim();clock.advance(Duration.ofSeconds(4));assertThat(tasks.claim("next",Duration.ofSeconds(3))).isEmpty();assertThat(tasks.get(1,job.id()).orElseThrow().status()).isEqualTo("dead");assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM java_task_dead_letters",Long.class)).isEqualTo(1);
    }
    @Test void delayedJobsAndHeartbeatsRespectClockAndExpiry(){
        var job=tasks.enqueue(1,"parse","delayed",json.createObjectNode(),3,Duration.ofSeconds(2));assertThat(tasks.claim("worker",Duration.ofSeconds(3))).isEmpty();clock.advance(Duration.ofSeconds(2));var lease=claim();clock.advance(Duration.ofSeconds(2));assertThat(tasks.renew(lease,Duration.ofSeconds(3))).isTrue();clock.advance(Duration.ofSeconds(2));assertThat(tasks.claim("next",Duration.ofSeconds(3))).isEmpty();clock.advance(Duration.ofSeconds(2));assertThat(tasks.renew(lease,Duration.ofSeconds(3))).isFalse();assertThat(tasks.claim("next",Duration.ofSeconds(3))).isPresent();
    }
    @Test void businessEffectsAndCompletionCommitTogetherAndRejectStaleWorker(){
        enqueue("commit",3);var old=claim();clock.advance(Duration.ofSeconds(4));var current=claim();assertThat(tasks.commitResult(old,()->jdbc.update("INSERT INTO business_values VALUES('old','wrong')"),json.createObjectNode())).isFalse();
        assertThat(tasks.commitResult(current,()->jdbc.update("INSERT INTO business_values VALUES('current','right')"),json.createObjectNode().put("done",true))).isTrue();assertThat(jdbc.queryForList("SELECT id FROM business_values")).containsExactly(Map.of("id","current"));
    }
    @Test void failedBusinessEffectRollsBackCompletionAndCanBeRetried(){
        var job=enqueue("effect-rollback",3);var lease=claim();assertThatThrownBy(()->tasks.commitResult(lease,()->{jdbc.update("INSERT INTO business_values VALUES('partial','wrong')");throw new IllegalStateException("effect failed");},json.createObjectNode())).isInstanceOf(IllegalStateException.class);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM business_values",Long.class)).isZero();assertThat(tasks.get(1,job.id()).orElseThrow().status()).isEqualTo("running");
    }
    @Test void invalidPayloadBudgetAndLeaseInputsFailBeforeAnyInsert(){
        assertThatThrownBy(()->tasks.enqueue(0,"parse","x",json.createObjectNode(),3,Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->tasks.enqueue(1,"parse","x",json.createObjectNode(),0,Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->tasks.enqueue(1,"parse","x",json.createObjectNode().put("large","x".repeat(1024*1024)),3,Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->tasks.claim("worker",Duration.ofMillis(1))).isInstanceOf(IllegalArgumentException.class);assertThat(tasks.list(1,200)).isEmpty();
    }
    static class MutableClock extends Clock {long millis=Instant.parse("2026-10-13T00:00:00Z").toEpochMilli();void advance(Duration by){millis+=by.toMillis();}@Override public ZoneId getZone(){return ZoneOffset.UTC;}@Override public Clock withZone(ZoneId zone){return this;}@Override public Instant instant(){return Instant.ofEpochMilli(millis);}@Override public long millis(){return millis;}}
}
