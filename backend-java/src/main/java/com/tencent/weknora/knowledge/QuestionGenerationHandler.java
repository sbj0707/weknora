package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.model.client.*;
import com.tencent.weknora.retrieval.*;
import com.tencent.weknora.task.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Native question:generation, including legacy, single-chunk and ordered-window payloads. */
public final class QuestionGenerationHandler implements TaskHandler {
  private final QuestionGenerationRepository repository;private final ObjectMapper json;private final SummaryGenerationHandler.Models models;private final DocumentProcessingHandler.Indexes indexes;private final KnowledgeProcessingSpanTracker tracker;private final QuestionGenerationText text;
  public QuestionGenerationHandler(QuestionGenerationRepository repository,ObjectMapper json,SummaryGenerationHandler.Models models,DocumentProcessingHandler.Indexes indexes,KnowledgeProcessingSpanTracker tracker,QuestionGenerationText text){this.repository=repository;this.json=json;this.models=models;this.indexes=indexes;this.tracker=tracker;this.text=text;}
  public String type(){return "question:generation";}
  public Duration timeout(){return Duration.ofMinutes(30);}
  public JsonNode execute(TaskRecord job,TaskCancellation cancellation)throws Exception {
    var output=json.createObjectNode();KnowledgeProcessingSpanTracker.Span span=null;Exception failure=null;boolean terminal=false,superseded=false,validated=false;QuestionGenerationStats stats=null;var watcher=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"native-question-cancellation");t.setDaemon(true);return t;});
    try(var call=CallContext.timeout(timeout())) {
      var monitor=watcher.scheduleWithFixedDelay(()->{if(cancellation.isCanceled())call.cancel();},25,25,TimeUnit.MILLISECONDS);
      try {
        cancellation.check();if(repository.superseded(job)){superseded=true;return output.put("skipped","attempt_superseded");}
        repository.binding(job);validated=true;
        JsonNode payload=job.payload();var ids=new ArrayList<String>();for(var id:payload.path("chunk_ids"))ids.add(id.asText());if(ids.isEmpty()&&!payload.path("chunk_id").asText().isEmpty())ids.add(payload.path("chunk_id").asText());boolean batch=!ids.isEmpty();stats=new QuestionGenerationStats(json,job,batch,ids.size());int count=QuestionGenerationText.count(payload.path("question_count").asInt());
        long attempt=payload.path("attempt").asLong();if(tracker!=null&&attempt>0){var parent=batch?tracker.lookupName(job.tenantId(),payload.path("knowledge_id").asText(),attempt,"postprocess.question"):null;if(parent==null)parent=tracker.lookupStage(job.tenantId(),payload.path("knowledge_id").asText(),attempt,"postprocess");var input=json.createObjectNode().put("question_count",payload.path("question_count").asInt()).put("language",payload.path("language").asText());if(batch)input.put("batch_index",payload.path("batch_index").asInt()).put("chunks",ids.size());span=tracker.beginSubspan(parent,batch?"postprocess.question.batch["+payload.path("batch_index").asInt()+"]":"postprocess.question","subspan",input);}
        if(!text.configured()){stats.status("prompt_not_configured");throw new TaskFailure("Question generation prompt is not configured",true);}
        stats.status("get_tenant_failed");var loaded=repository.load(job);if(loaded.isEmpty()){stats.status("knowledge_not_found");terminal=true;return output.put("skipped","knowledge_not_found");}var snapshot=loaded.get();stats.status("success");
        String state=snapshot.knowledge().path("parse_status").asText();if(Set.of("cancelled","deleting").contains(state)){stats.status("knowledge_"+state);terminal=true;return output.put("skipped","knowledge_"+state);}
        List<ObjectNode> chunks;if(batch){chunks=new ArrayList<>();for(String id:ids)chunks.add(repository.chunk(snapshot,id));}else chunks=snapshot.text().stream().sorted(Comparator.comparingLong(c->c.path("start_at").asLong())).toList();
        if(!batch){stats.output.put("total_chunks",chunks.size()).put("text_chunks",chunks.size());}if(!batch&&chunks.isEmpty()){stats.status("no_text_chunks");terminal=true;return output.put("skipped","no_text_chunks");}
        stats.status("get_chat_model_failed");var chat=models.chat(job.tenantId(),snapshot.knowledgeBase().path("summary_model_id").asText());stats.output.put("model_id",snapshot.knowledgeBase().path("summary_model_id").asText());stats.status("get_embedding_model_failed");var embedding=models.embedding(job.tenantId(),snapshot.knowledgeBase().path("embedding_model_id").asText());stats.status("init_retrieve_engine_failed");var writer=indexes.resolve(job.tenantId(),snapshot.knowledgeBase());stats.status("success");
        var images=new LinkedHashMap<String,List<ObjectNode>>();for(var c:snapshot.all())images.put(c.path("id").asText(),repository.images(job,snapshot,List.of(c)));String instructions=new KnowledgeProcessConfig(json).resolve(snapshot.knowledgeBase(),snapshot.knowledge().path("metadata").get("process_overrides")).path("question_generation_config").path("custom_instructions").asText();
        var entries=new ArrayList<IndexEntry>();var sources=new LinkedHashMap<String,ObjectNode>();int questionsTotal=0,failed=0;
        for(int i=0;i<chunks.size();i++) {
          cancellation.check();if(repository.businessCanceled(job))throw new CancellationException("question knowledge canceled");ObjectNode chunk=chunks.get(i);if(chunk==null||SummaryGenerationText.trim(chunk.path("content").asText()).isEmpty()){stats.increment(batch?"empty_chunks":"empty_content_chunks");continue;}
          ObjectNode previous=i>0?chunks.get(i-1):(batch?repository.chunk(snapshot,payload.path("prev_chunk_id").asText()):null),next=i+1<chunks.size()?chunks.get(i+1):(batch?repository.chunk(snapshot,payload.path("next_chunk_id").asText()):null);
          List<String> questions;if(!batch)stats.increment("llm_attempts");try {String prompt=text.prompt(enrich(chunk,images),enrich(previous,images),enrich(next,images),snapshot.knowledge().path("title").asText(),payload.path("language").asText(),count,instructions);questions=text.parse(chat.chat(call,List.of(json.createObjectNode().put("role","user").put("content",prompt)),text.options()),count);}
          catch(CancellationException e){throw e;}catch(RuntimeException e){stats.increment("llm_failed");failed++;if(repository.businessCanceled(job))throw new CancellationException("question knowledge canceled");continue;}
          cancellation.check();if(repository.businessCanceled(job))throw new CancellationException("question knowledge canceled");if(questions.isEmpty()){if(!batch)stats.increment("llm_empty");continue;}if(!repository.current(job,snapshot,chunk))continue;stats.increment(batch?"chunks_processed":"llm_success");stats.generated(questions.size(),questions.getFirst());
          var metadata=json.createArrayNode();var candidates=new ArrayList<IndexEntry>();for(String question:questions){String id="q"+UUID.randomUUID().toString().replace("-","");metadata.add(json.createObjectNode().put("id",id).put("question",question).put("content_revision",chunk.path("content_revision").asLong()));String title=SummaryGenerationText.trim(snapshot.knowledge().path("title").asText());candidates.add(new IndexEntry(QuestionGenerationText.sourceId(chunk.path("id").asText(),id),0,chunk.path("id").asText(),snapshot.knowledge().path("id").asText(),snapshot.knowledgeBase().path("id").asText(),"",title.isEmpty()?question:title+"\n"+question,true));}
          try{if(!repository.metadata(job,snapshot,chunk,metadata))continue;}catch(CancellationException|QuestionGenerationRepository.Stale e){throw e;}catch(RuntimeException e){if(!batch)stats.increment("chunk_update_failed");failed++;continue;}
          entries.addAll(candidates);sources.put(chunk.path("id").asText(),chunk);questionsTotal+=questions.size();
        }
        stats.output.put("index_entries_prepared",entries.size());if(!entries.isEmpty()) {stats.status("index_questions_failed");if(!batch)stats.output.put("index_batch_attempted",true);var values=embed(embedding,call,entries,cancellation);cancellation.check();if(values.length!=entries.size())throw new ModelClientException("embedding model returned unexpected count");var vectors=new LinkedHashMap<String,float[]>();for(int i=0;i<values.length;i++){FloatVectors.validate(values[i],embedding.dimensions());vectors.put(entries.get(i).sourceId(),values[i]);}writer.prepare(Set.of(embedding.dimensions()));repository.index(job,snapshot,entries,vectors,writer,sources);stats.output.put("index_batch_succeeded",true);}stats.status("success");
        terminal=true;return output.put("status","completed").put("chunks_total",chunks.size()).put("questions_total",questionsTotal).put("chunks_failed",failed).put("indexed_entries",entries.size());
      }catch(QuestionGenerationRepository.MissingBase e){terminal=true;if(stats!=null)stats.status("kb_not_found");return output.put("skipped","kb_not_found");}
      catch(QuestionGenerationRepository.Stale e){if(stats!=null)stats.status("superseded");superseded=true;terminal=true;return output.put("skipped","attempt_superseded");}
      catch(Exception e){if(e instanceof CancellationException&&repository.businessCanceled(job)&&!cancellation.isCanceled()&&!Thread.currentThread().isInterrupted()){if(stats!=null)stats.status("knowledge_cancelled");terminal=true;return output.put("skipped","knowledge_cancelled");}failure=e;throw e;}
      finally {monitor.cancel(false);if(validated&&!superseded&&cancellation.reason()!=TaskCancellation.Reason.LEASE_LOST&&(terminal||job.attempts()>=job.maxAttempts()))repository.drain(job);if(tracker!=null&&span!=null){if(failure!=null)tracker.fail(span,"QUESTION_FAILED",Objects.requireNonNullElse(failure.getMessage(),"Question generation failed"),failure);else if(stats!=null&&!stats.output.path("status").asText().equals("success"))tracker.fail(span,"QUESTION_FAILED",stats.output.path("status").asText(),null);else tracker.end(span,stats==null?output:stats.output);}}
    }finally{watcher.shutdownNow();}
  }
  private static String enrich(ObjectNode chunk,Map<String,List<ObjectNode>> images){return chunk==null?"":QuestionGenerationText.enrich(chunk.path("content").asText(),images.getOrDefault(chunk.path("id").asText(),List.of()));}
  private static float[][] embed(EmbeddingModel model,CallContext call,List<IndexEntry> entries,TaskCancellation cancellation){RuntimeException last=null;for(int i=0;i<5;i++){cancellation.check();try{return model.embed(call,entries.stream().map(e->IndexText.embeddingInput(e.content())).toList(),false);}catch(CancellationException e){throw e;}catch(RuntimeException e){last=e;if(i<4)call.pause(Duration.ofMillis(200L<<i));}}throw last;}
}
