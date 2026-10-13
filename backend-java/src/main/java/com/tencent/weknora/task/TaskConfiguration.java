package com.tencent.weknora.task;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.*;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="weknora.tasks.enabled",havingValue="true")
@EnableScheduling
public class TaskConfiguration {
    @Bean(destroyMethod="close")
    @ConditionalOnProperty(name="weknora.tasks.topology",havingValue="single",matchIfMissing=true)
    TaskWorker taskWorker(TaskRepository repository,List<TaskHandler> handlers,@Value("${weknora.tasks.concurrency:2}")int concurrency,@Value("${weknora.tasks.lease-millis:30000}")long lease,@Value("${weknora.tasks.queues:default=3,chat_attachment=3,postprocess=1,summary=2,multimodal=1,graph=1,question=1,memory=1,sync=2,low=1,wiki=1}")String queues){return new TaskWorker(repository,handlers,concurrency,Duration.ofMillis(lease),TaskQueueOrder.parse(queues));}
    @Bean(destroyMethod="close")
    @ConditionalOnProperty(name="weknora.tasks.topology",havingValue="go")
    TaskPoolGroup taskPoolGroup(TaskRepository repository,List<TaskHandler> handlers,org.springframework.jdbc.core.JdbcTemplate jdbc,com.fasterxml.jackson.databind.ObjectMapper json,org.springframework.core.env.Environment environment,@Value("${weknora.tasks.lease-millis:30000}")long lease){
        return new TaskPoolGroup(repository,handlers,TaskTopology.concurrency(new TaskPoolSettings(jdbc,json,environment)),Duration.ofMillis(lease));
    }
    @Bean TaskPoller taskPoller(TaskExecutor worker){return new TaskPoller(worker);}
    static class TaskPoller {
        private final TaskExecutor worker;TaskPoller(TaskExecutor worker){this.worker=worker;}
        @Scheduled(fixedDelayString="${weknora.tasks.poll-millis:1000}") public void poll(){try{worker.poll();}catch(Exception ex){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Java task queue unavailable; polling will retry");}}
    }
}
