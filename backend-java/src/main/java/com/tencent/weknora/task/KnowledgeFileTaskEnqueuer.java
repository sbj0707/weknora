package com.tencent.weknora.task;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.weknora.knowledge.KnowledgeFileUpload;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Durable native producer. Asynq MaxRetry excludes the initial attempt. */
@Component
@ConditionalOnProperty(name="weknora.tasks.enabled",havingValue="true")
public final class KnowledgeFileTaskEnqueuer implements KnowledgeFileUpload.Enqueuer {
    private final TaskRepository repository;
    public KnowledgeFileTaskEnqueuer(TaskRepository repository){this.repository=repository;}
    @Override public String enqueue(long tenant,String type,ObjectNode payload,KnowledgeFileUpload.TaskOptions options){
        if(options==null||options.maxRetries()<0||options.maxRetries()>99||options.timeout()==null||options.timeout().isZero()||options.timeout().isNegative())throw new IllegalArgumentException("Invalid producer task options");
        // Go assigns a fresh Asynq task ID for each call; business payload IDs are not queue dedup keys.
        return repository.enqueue(tenant,type,null,payload,options.maxRetries()+1,Duration.ZERO,options.queue(),options.timeout()).id();
    }
}
