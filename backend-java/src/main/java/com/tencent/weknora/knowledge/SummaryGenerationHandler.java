package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.model.client.*;
import com.tencent.weknora.retrieval.*;
import com.tencent.weknora.task.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Native Go summary:generation checkpoints; an index failure retains the saved summary/chunk. */
public final class SummaryGenerationHandler implements TaskHandler {
  public interface Models {ChatModel chat(long owner,String id);EmbeddingModel embedding(long owner,String id);}
  private final SummaryGenerationRepository repository;private final ObjectMapper json;private final Models models;private final DocumentProcessingHandler.Indexes indexes;private final KnowledgeProcessingSpanTracker tracker;private final SummaryGenerationText text;private final KnowledgeFileUpload.Enqueuer tasks;
  public SummaryGenerationHandler(SummaryGenerationRepository repository,ObjectMapper json,Models models,DocumentProcessingHandler.Indexes indexes,KnowledgeProcessingSpanTracker tracker,SummaryGenerationText text,KnowledgeFileUpload.Enqueuer tasks){this.repository=repository;this.json=json;this.models=models;this.indexes=indexes;this.tracker=tracker;this.text=text;this.tasks=tasks;}
  private KnowledgeBaseProfileHandler profiles;
  public SummaryGenerationHandler withProfiles(KnowledgeBaseProfileHandler profiles){this.profiles=profiles;return this;}
  public String type(){return "summary:generation";}
  public Duration timeout(){return Duration.ofMinutes(30);}
  boolean handoff(TaskRecord job,DocumentProcessingRepository.Snapshot snapshot){return repository.handoff(job,snapshot,tasks);}
  public JsonNode execute(TaskRecord job,TaskCancellation cancellation)throws Exception {
    ObjectNode output=json.createObjectNode();KnowledgeProcessingSpanTracker.Span span=null;Exception failure=null;boolean terminal=false,superseded=false,validated=false;ObjectNode profileBase=null;boolean profileRefreshSaved=false;var watcher=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"native-summary-cancellation");t.setDaemon(true);return t;});
    try(var call=CallContext.timeout(timeout())) {
      var monitor=watcher.scheduleWithFixedDelay(()->{if(cancellation.isCanceled())call.cancel();},25,25,TimeUnit.MILLISECONDS);
      try {
        cancellation.check();if(repository.superseded(job)){superseded=true;return output.put("skipped","attempt_superseded");}
        var loaded=repository.load(job);if(loaded.isEmpty()){terminal=true;return output.put("skipped","knowledge_not_found");}var snapshot=loaded.get();validated=true;profileBase=snapshot.knowledgeBase();boolean refresh=job.payload().path("refresh").asBoolean();
        if(!refresh&&tracker!=null){long attempt=job.payload().path("attempt").asLong();if(attempt<=0)attempt=tracker.latest(job.tenantId(),job.payload().path("knowledge_id").asText());var parent=tracker.lookupStage(job.tenantId(),job.payload().path("knowledge_id").asText(),attempt,"postprocess");span=tracker.beginSubspan(parent,"postprocess.summary","subspan",json.createObjectNode().put("language",job.payload().path("language").asText()));}
        String model=snapshot.knowledgeBase().path("summary_model_id").asText();output.put("model_id",model);
        if(model.isEmpty()){if(refresh)throw new TaskFailure("Summary model is not configured",true);repository.state(job,snapshot,"failed",snapshot.knowledge().path("description").asText(),snapshot.knowledge().get("profile"),false);terminal=true;return output.put("skipped","no_summary_model");}
        String state=snapshot.knowledge().path("parse_status").asText();if(Set.of("cancelled","deleting").contains(state)){terminal=true;return output.put("skipped","knowledge_"+state);}
        repository.state(job,snapshot,"processing",snapshot.knowledge().path("description").asText(),snapshot.knowledge().get("profile"),false);output.put("text_chunks",snapshot.text().size());
        if(snapshot.text().isEmpty()){repository.state(job,snapshot,"failed","",null,false);terminal=true;return output.put("skipped","no_text_chunks");}
        SummaryGenerationText.Result result;
        try {
          ChatModel chat=models.chat(job.tenantId(),model);
          String input=text.input(snapshot.text(),snapshot.knowledge().path("custom_metadata"),repository.images(job,snapshot));
          result=text.parse(chat.chat(call,List.of(json.createObjectNode().put("role","system").put("content",text.prompt(job.payload().path("language").asText())),json.createObjectNode().put("role","user").put("content",input)),text.options()));
          cancellation.check();
        } catch(SummaryGenerationText.InsufficientContent error) {
          failure=error;repository.state(job,snapshot,"failed","",null,false);terminal=true;return output.put("fallback","insufficient_content");
        } catch(CancellationException error){throw error;}
        catch(RuntimeException error) {
          failure=error;boolean retry=job.attempts()<job.maxAttempts();
          if(retry){repository.state(job,snapshot,"pending",snapshot.knowledge().path("description").asText(),snapshot.knowledge().get("profile"),false);output.put("retrying",true);}
          else if(repository.sourceChanged(job,snapshot)){terminal=true;return output.put("skipped","content_revision_changed");}
          else {String fallback=SummaryGenerationText.fallback(snapshot.text());repository.state(job,snapshot,"failed",fallback,null,true);output.put("fallback",fallback.isEmpty()?"empty":"first_chunk");output.put("fallback_chars",fallback.codePointCount(0,fallback.length()));}
          throw new TaskFailure(retry?"Summary generation attempt failed":"Summary generation exhausted retries",true);
        }
        if(repository.sourceChanged(job,snapshot)){terminal=true;return output.put("skipped","content_revision_changed");}
        repository.state(job,snapshot,"completed",result.summary(),result.profile(),true);profileRefreshSaved=true;output.put("summary_chars",result.summary().codePointCount(0,result.summary().length())).put("has_profile",result.profile()!=null).put("summary_preview",SummaryGenerationText.bound(result.summary(),240));
        JsonNode strategy=snapshot.knowledgeBase().path("indexing_strategy");boolean needs=strategy.path("vector_enabled").asBoolean()||strategy.path("keyword_enabled").asBoolean();
        if(!SummaryGenerationText.trim(result.summary()).isEmpty()&&needs) {
          var entries=repository.summaries(job,snapshot,SummaryGenerationText.summaryChunk(result),refresh);
          // Like Go, chunk creation commits before model/index resolution and can survive an index retry.
          var writer=indexes.resolve(job.tenantId(),snapshot.knowledgeBase());var modelEmbedding=models.embedding(job.tenantId(),snapshot.knowledgeBase().path("embedding_model_id").asText());
          repository.deleteRefreshIndexes(job,snapshot,entries,writer);
          var values=embed(modelEmbedding,call,entries,cancellation);cancellation.check();
          if(values.length!=entries.size())throw new ModelClientException("embedding model returned unexpected count");var vectors=new LinkedHashMap<String,float[]>();for(int i=0;i<values.length;i++){FloatVectors.validate(values[i],modelEmbedding.dimensions());vectors.put(entries.get(i).sourceId(),values[i]);}
          writer.prepare(Set.of(modelEmbedding.dimensions()));repository.index(job,snapshot,entries,vectors,writer);output.put("summary_chunk_indexed",true);
        }
        terminal=true;return output.put("status","completed");
      } catch(SummaryGenerationRepository.Stale error){superseded=repository.superseded(job);terminal=true;return output.put("skipped",superseded?"attempt_superseded":"content_revision_changed");}
      catch(Exception error){if(error instanceof CancellationException&&!cancellation.isCanceled()&&!Thread.currentThread().isInterrupted()&&repository.businessCanceled(job)){terminal=true;failure=null;return output.put("skipped","content_revision_changed");}if(failure==null)failure=error;throw error;}
      finally {monitor.cancel(false);if(validated&&!superseded&&cancellation.reason()!=TaskCancellation.Reason.LEASE_LOST&&(terminal||job.attempts()>=job.maxAttempts()))try{repository.drain(job);}catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Summary terminal accounting unavailable");}
        if(profiles!=null&&validated&&!superseded&&profileBase!=null&&cancellation.reason()!=TaskCancellation.Reason.LEASE_LOST&&((!job.payload().path("refresh").asBoolean()&&terminal)||(job.payload().path("refresh").asBoolean()&&profileRefreshSaved)))profiles.enqueue(job.tenantId(),profileBase,false,job.payload());
        if(tracker!=null&&span!=null){if(failure!=null)tracker.fail(span,"SUMMARY_FAILED",failure.getMessage()==null?"Summary generation failed":failure.getMessage(),failure);else tracker.end(span,output);}
      }
    } finally {watcher.shutdownNow();}
  }
  private static float[][] embed(EmbeddingModel model,CallContext call,List<IndexEntry> entries,TaskCancellation cancellation) {
    RuntimeException last=null;for(int i=0;i<5;i++){cancellation.check();try{return model.embed(call,entries.stream().map(e->IndexText.embeddingInput(e.content())).toList(),false);}catch(CancellationException e){throw e;}catch(RuntimeException e){last=e;if(i<4)call.pause(Duration.ofMillis(200L<<i));}}throw last;
  }
}
