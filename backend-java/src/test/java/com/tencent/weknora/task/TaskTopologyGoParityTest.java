package com.tencent.weknora.task;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import static org.assertj.core.api.Assertions.*;

class TaskTopologyGoParityTest {
    Path database;JdbcTemplate jdbc;TaskRepository tasks;ObjectMapper json=new ObjectMapper();volatile RuntimeException settlementFailure;
    @BeforeEach void initialize()throws Exception{
        database=Files.createTempFile("weknora-native-pools-",".db");var source=new DriverManagerDataSource("jdbc:sqlite:"+database);source.setDriverClassName("org.sqlite.JDBC");jdbc=new JdbcTemplate(source);tasks=new TaskRepository(jdbc,json,new DataSourceTransactionManager(source)){
            @Override public boolean complete(TaskRecord lease,JsonNode result){try{return super.complete(lease,result);}catch(RuntimeException failure){settlementFailure=failure;throw failure;}}
        };
        try(var c=source.getConnection()){ScriptUtils.executeSqlScript(c,new FileSystemResource("migration/sql/java-tasks.sql"));}
        jdbc.execute("CREATE TABLE system_settings(key TEXT PRIMARY KEY,value TEXT,last_modified_by TEXT)");
    }
    @AfterEach void clean()throws Exception{Files.deleteIfExists(database);}
    JsonNode fixture()throws Exception{return json.readTree(Path.of(System.getProperty("weknora.task.topology.fixture","src/test/java/com/tencent/weknora/task/go-task-topology.json")).toFile());}
    JsonNode tree(Object value)throws Exception{return json.readTree(json.writeValueAsString(value));}
    @Test void completeQueueRegistryMatchesActualGoWithoutSharingMaintenanceOrPostprocessCapacity()throws Exception{
        var expected=fixture();var defs=TaskTopology.definitions().stream().map(d->Map.of("Name",d.name(),"Pool",d.pool(),"Weight",d.weight(),"SharedWeight",d.sharedWeight(),"TaskTypes",d.taskTypes())).toList();
        assertThat(tree(defs)).isEqualTo(expected.get("definitions"));assertThat(tree(TaskTopology.concurrency(null))).isEqualTo(expected.get("defaults"));
        for(var setting:TaskTopology.settings())assertThat(tree(TaskTopology.queues(setting.pool()))).isEqualTo(expected.get("weights").get(setting.pool()));
        var fields=expected.path("tasks").fields();while(fields.hasNext()){var field=fields.next();assertThat(TaskTopology.queueForType(field.getKey()).orElse("")).as(field.getKey()).isEqualTo(field.getValue().asText());}
        assertThat(TaskTopology.queues("shared")).doesNotContainKeys("low","sync","postprocess","wiki");
    }
    @Test void dbBootstrapAndTypedIntegerEnvironmentResolutionMatch294ProductionGoCases()throws Exception{
        var cases=fixture().path("settings");assertThat(cases.size()).isEqualTo(294);
        for(var item:cases){jdbc.update("DELETE FROM system_settings");if(item.path("present").asBoolean())jdbc.update("INSERT INTO system_settings VALUES(?,?,?)",item.path("key").asText(),item.path("raw").asText(),item.path("modified").asText());
            var environment=new MockEnvironment().withProperty(item.path("environment").asText(),item.path("env").asText());
            if(item.path("broken").asBoolean())jdbc.execute("ALTER TABLE system_settings RENAME TO unavailable_settings");
            try{var settings=new TaskPoolSettings(jdbc,json,environment);var spec=TaskTopology.settings().stream().filter(s->s.pool().equals(item.path("pool").asText())).findFirst().orElseThrow();
                assertThat(settings.read(spec)).as(item.path("name").asText()+" "+spec.pool()).isEqualTo(item.path("resolved").asLong());
                assertThat(tree(TaskTopology.concurrency(settings))).as(item.path("name").asText()+" allocations").isEqualTo(item.get("allocation"));
            }finally{if(item.path("broken").asBoolean())jdbc.execute("ALTER TABLE unavailable_settings RENAME TO system_settings");}
        }
    }
    @Test void saturatedEnrichmentAndSharedWorkersLeaveReservedCoreAndPostprocessCapacity()throws Exception{
        var enrichmentStarted=new CountDownLatch(2);var release=new CountDownLatch(1);var coreStarted=new CountDownLatch(1);var postStarted=new CountDownLatch(1);
        TaskHandler enrichment=new TaskHandler(){public String type(){return "summary:generation";}public JsonNode execute(TaskRecord job,TaskCancellation cancellation)throws Exception{enrichmentStarted.countDown();release.await();cancellation.check();return json.createObjectNode();}};
        TaskHandler core=new TaskHandler(){public String type(){return "document:process";}public JsonNode execute(TaskRecord job,TaskCancellation cancellation){coreStarted.countDown();return json.createObjectNode();}};
        TaskHandler post=new TaskHandler(){public String type(){return "knowledge:post_process";}public JsonNode execute(TaskRecord job,TaskCancellation cancellation){postStarted.countDown();return json.createObjectNode();}};
        var capacity=TaskTopology.concurrency(setting->1);
        for(int i=0;i<3;i++)tasks.enqueue(1,enrichment.type(),null,json.createObjectNode(),4,Duration.ZERO,"summary",Duration.ofMinutes(30));
        try(var pools=new TaskPoolGroup(tasks,List.of(enrichment,core,post),capacity,Duration.ofSeconds(3))){
            pools.poll();assertThat(enrichmentStarted.await(2,TimeUnit.SECONDS)).isTrue();assertThat(pools.activeByPool()).containsEntry("enrichment",1).containsEntry("shared",1);
            var document=tasks.enqueue(1,core.type(),null,json.createObjectNode(),4,Duration.ZERO,"default",Duration.ofHours(2));
            var followup=tasks.enqueue(1,post.type(),null,json.createObjectNode(),4,Duration.ZERO,"postprocess",Duration.ofMinutes(30));
            pools.poll();assertThat(coreStarted.await(2,TimeUnit.SECONDS)).isTrue();assertThat(postStarted.await(2,TimeUnit.SECONDS)).isTrue();
            await(document.id(),"succeeded");await(followup.id(),"succeeded");assertThat(pools.activeByPool()).containsEntry("enrichment",1).containsEntry("shared",1);release.countDown();
        }finally{release.countDown();}
    }
    @Test void springOptInInstantiatesExactlyOneExecutorAndClosesItsIndependentPools(){
        var runner=new ApplicationContextRunner().withUserConfiguration(TaskConfiguration.class).withBean(TaskRepository.class,()->tasks).withBean(JdbcTemplate.class,()->jdbc).withBean(ObjectMapper.class,()->json).withPropertyValues("weknora.tasks.enabled=true","weknora.tasks.poll-millis=60000");
        runner.run(context->{assertThat(context).hasSingleBean(TaskWorker.class).hasSingleBean(TaskExecutor.class).doesNotHaveBean(TaskPoolGroup.class);});
        runner.withPropertyValues("weknora.tasks.topology=go").run(context->{assertThat(context).hasSingleBean(TaskPoolGroup.class).hasSingleBean(TaskExecutor.class).doesNotHaveBean(TaskWorker.class);assertThat(context.getBean(TaskPoolGroup.class).capacities()).isEqualTo(TaskTopology.concurrency(null));});
        runner.withPropertyValues("weknora.tasks.enabled=false").run(context->assertThat(context).doesNotHaveBean(TaskExecutor.class));
    }
    @Test void malformedPartialOrUnboundedCapacityFailsBeforeAnyJobIsClaimed(){
        assertThatThrownBy(()->new TaskPoolGroup(tasks,List.of(),Map.of("core",1),Duration.ofSeconds(3))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new TaskPoolGroup(tasks,List.of(),TaskTopology.concurrency(setting->65),Duration.ofSeconds(3))).isInstanceOf(IllegalArgumentException.class);
        assertThat(tasks.list(1,200)).isEmpty();
    }
    void await(String id,String status)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<end){if(tasks.get(1,id).orElseThrow().status().equals(status))return;Thread.sleep(10);}throw new AssertionError(tasks.get(1,id).toString(),settlementFailure);}
}
