package com.tencent.weknora.task;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;

/** Business handlers must be idempotent for task ID + attempt fencing; queue delivery is at least once. */
public interface TaskHandler {
    String type();
    JsonNode execute(TaskRecord job,TaskCancellation cancellation) throws Exception;
    default Duration timeout(){return Duration.ofMinutes(10);}
}
