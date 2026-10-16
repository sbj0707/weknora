package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.task.*;
import java.time.Duration;

/** Go's zero-enrichment handoff, guarded against duplicate delivery and cancellation. */
public final class KnowledgePostProcessHandler implements TaskHandler {
  private final DocumentProcessingRepository repository;
  private final KnowledgeProcessConfig process;
  private final ObjectMapper json;
  private final KnowledgeProcessingSpanTracker tracker;
  private final SummaryGenerationHandler summary;
  private final QuestionGenerationFanout fanout;
  private final KnowledgeAutoTagHandler autoTag;
  private KnowledgeBaseProfileHandler profiles;
  public KnowledgePostProcessHandler withProfiles(KnowledgeBaseProfileHandler profiles){this.profiles=profiles;return this;}
  private void refreshProfile(TaskRecord job,DocumentProcessingRepository.Snapshot snapshot){if(profiles!=null)profiles.enqueue(job.tenantId(),snapshot.knowledgeBase(),false,job.payload());}

  public KnowledgePostProcessHandler(DocumentProcessingRepository repository, ObjectMapper json) {
    this(repository,json,null);
  }

  public KnowledgePostProcessHandler(DocumentProcessingRepository repository, ObjectMapper json,KnowledgeProcessingSpanTracker tracker) {
    this(repository,json,tracker,null);
  }
  public KnowledgePostProcessHandler(DocumentProcessingRepository repository,ObjectMapper json,KnowledgeProcessingSpanTracker tracker,SummaryGenerationHandler summary) {
    this(repository,json,tracker,summary,null);
  }
  public KnowledgePostProcessHandler(DocumentProcessingRepository repository,ObjectMapper json,KnowledgeProcessingSpanTracker tracker,SummaryGenerationHandler summary,QuestionGenerationFanout fanout) {
    this(repository,json,tracker,summary,fanout,null);
  }
  public KnowledgePostProcessHandler(DocumentProcessingRepository repository,ObjectMapper json,KnowledgeProcessingSpanTracker tracker,SummaryGenerationHandler summary,QuestionGenerationFanout fanout,KnowledgeAutoTagHandler autoTag) {
    this.autoTag=autoTag;
    this.fanout=fanout;
    this.summary=summary;
    this.repository = repository;
    this.tracker=tracker;
    this.json = json;
    process = new KnowledgeProcessConfig(json);
  }

  @Override
  public String type() {
    return "knowledge:post_process";
  }

  @Override
  public Duration timeout() {
    return Duration.ofMinutes(30);
  }

  @Override
  public JsonNode execute(TaskRecord job, TaskCancellation cancellation) throws Exception {
    if(tracker!=null&&job.payload() instanceof com.fasterxml.jackson.databind.node.ObjectNode p&&p.path("attempt").asLong()<=0)p.put("attempt",tracker.latest(job.tenantId(),p.path("knowledge_id").asText()));
    var trace=new KnowledgeProcessingSpanPipeline(tracker,json,job,true);
    try {
    cancellation.check();
    var loaded = repository.load(job);
    if (loaded.isEmpty()) return json.createObjectNode().put("skipped", "knowledge_not_found");
    var snapshot = loaded.get();
    if(trace.superseded())return json.createObjectNode().put("skipped","attempt_superseded");
    if (!snapshot.knowledge().path("parse_status").asText().equals("processing")) {
      trace.finishStatus(snapshot.knowledge(),"non_processing_status");
      return json.createObjectNode().put("skipped", "non_processing_status");
    }
    JsonNode override = snapshot.knowledge().path("metadata").path("process_overrides");
    var effective =
        process.resolve(snapshot.knowledgeBase(), override.isMissingNode() ? null : override);
    if(tracker!=null)tracker.finishRunningStage(job.tenantId(),job.payload().path("knowledge_id").asText(),job.payload().path("attempt").asLong(),"multimodal");
    trace.begin("postprocess",json.createObjectNode().put("graph_enabled",effective.path("graph_enabled").asBoolean()).put("wiki_enabled",snapshot.knowledgeBase().path("indexing_strategy").path("wiki_enabled").asBoolean()).put("question_enabled",effective.path("question_generation_config").path("enabled").asBoolean()&&(snapshot.knowledgeBase().path("indexing_strategy").path("vector_enabled").asBoolean()||snapshot.knowledgeBase().path("indexing_strategy").path("keyword_enabled").asBoolean())));
    try {
      DocumentProcessingHandler.requireSupportedEnrichment(
          snapshot.knowledgeBase(), effective, true,summary!=null||fanout!=null,fanout!=null,fanout!=null&&fanout.graphSupported(),autoTag!=null,profiles!=null);
    } catch (TaskFailure failure) {
      if (!failure.retryable()) repository.failed(job, snapshot, failure.getMessage());
      throw failure;
    }
    if(fanout!=null) {
      var plan=fanout.handoff(job,snapshot,effective);
      if(!plan.current()){trace.abort("TASK_SUPERSEDED","knowledge_no_longer_current");return json.createObjectNode().put("skipped","knowledge_no_longer_current");}
      if(plan.total()==0)fanout.group(job,plan);
      boolean tagged=plan.enqueuedAutoTag();
      var output=json.createObjectNode().put("planned_graph_count",plan.plannedGraph()).put("planned_question_count",plan.questionBatches()).put("chunks_total",plan.textChunks()).put("enqueued_summary",plan.summary()).put("enqueued_question",plan.questionBatches()>0).put("enqueued_question_count",plan.questionBatches()).put("enqueued_wiki",false).put("wiki_slot_owned",false).put("enqueued_graph",plan.enqueuedGraph()>0).put("enqueued_graph_count",plan.enqueuedGraph()).put("enqueued_auto_tag",tagged);
      boolean completed=plan.total()>0||plan.completed()||repository.complete(job,snapshot);if(completed){trace.end("postprocess",output);trace.finish(output);}else trace.abort("TASK_SUPERSEDED","knowledge_no_longer_current");if(plan.total()==0)output.put("completed",completed);if(completed)refreshProfile(job,snapshot);return output;
    } else if(summary!=null&&effective.path("summary_enabled").asBoolean()) {
      boolean queued=summary.handoff(job,snapshot);
      if(queued){var output=json.createObjectNode().put("enqueued_summary",true).put("enqueued_subtasks",1);trace.end("postprocess",output);trace.finish(output);}
      else trace.abort("TASK_SUPERSEDED","knowledge_no_longer_current");
      if(queued)refreshProfile(job,snapshot);return json.createObjectNode().put("enqueued_summary",queued);
    }
    boolean completed=repository.complete(job, snapshot);
    if(completed) {trace.end("postprocess",json.createObjectNode().put("enqueued_subtasks",0));trace.finish(json.createObjectNode().put("enqueued_subtasks",0));}
    else trace.abort("TASK_SUPERSEDED","knowledge_no_longer_current");
    if(completed)refreshProfile(job,snapshot);return json.createObjectNode().put("completed",completed);
    } catch(java.util.concurrent.CancellationException error) {
      try {
        var current=repository.load(job);
        if(current.isPresent()&&java.util.Set.of("cancelled","deleting").contains(current.get().knowledge().path("parse_status").asText()))trace.abort("USER_CANCELLED","user cancelled");
        else if(trace.superseded()||cancellation.reason()!=TaskCancellation.Reason.LEASE_LOST)trace.abort("TASK_CANCELLED","postprocess cancelled");
      }catch(Exception ignored){}
      throw error;
    } catch(Exception error) {
      trace.fail("postprocess","POSTPROCESS_FAILED",error instanceof TaskFailure?error.getMessage():"Knowledge postprocessing failed",error);
      throw error;
    }
  }
}
