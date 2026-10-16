package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.model.client.*;
import com.tencent.weknora.task.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Native production chunk:extract; completed extraction publishes into real Neo4j/APOC. */
public final class GraphExtractionHandler implements TaskHandler {
  @FunctionalInterface public interface Models {ChatModel chat(long owner,String id);}
  private final GraphExtractionRepository repository;private final ObjectMapper json;private final Models models;private final GraphExtractionStore graph;private final KnowledgeProcessingSpanTracker tracker;private final GraphExtractionText text;
  public GraphExtractionHandler(GraphExtractionRepository repository,ObjectMapper json,Models models,GraphExtractionStore graph,KnowledgeProcessingSpanTracker tracker,GraphExtractionText text){this.repository=repository;this.json=json;this.models=models;this.graph=graph;this.tracker=tracker;this.text=text;}
  public String type(){return "chunk:extract";}
  public Duration timeout(){return Duration.ofMinutes(30);}
  public JsonNode execute(TaskRecord job,TaskCancellation cancellation)throws Exception {
    var out=json.createObjectNode();ObjectNode knowledge=null;KnowledgeProcessingSpanTracker.Span span=null;Exception failure=null;boolean terminal=false,bound=true,superseded=false;var watcher=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"native-graph-cancellation");t.setDaemon(true);return t;});
    try(var call=CallContext.timeout(timeout())){var monitor=watcher.scheduleWithFixedDelay(()->{if(cancellation.isCanceled())call.cancel();},25,25,TimeUnit.MILLISECONDS);try{
      cancellation.check();repository.payload(job);repository.fence(job);if(repository.superseded(job)){superseded=true;terminal=true;return out.put("skipped","attempt_superseded");}
      String doc=job.payload().path("knowledge_id").asText();long attempt=job.payload().path("attempt").asLong();if(!doc.isEmpty()&&attempt>0&&tracker!=null){var parent=tracker.lookupStage(job.tenantId(),doc,attempt,"postprocess");span=tracker.beginSubspan(parent,"postprocess.graph.chunk["+job.payload().path("chunk_index").asInt()+"]","subspan",json.createObjectNode().put("chunk_id",job.payload().path("chunk_id").asText()).put("chunk_index",job.payload().path("chunk_index").asInt()).put("model_id",job.payload().path("model_id").asText()));}
      if(!doc.isEmpty()){var owned=repository.knowledge(job,doc);if(owned.isEmpty()){bound=false;terminal=true;return out.put("skipped","knowledge_not_found");}knowledge=owned.get();String state=knowledge.path("parse_status").asText();if(Set.of("cancelled","deleting").contains(state)){terminal=true;return out.put("skipped","knowledge_"+state);}}
      var chunk=repository.chunk(job);if(chunk==null)throw new TaskFailure("Chunk not found",true);if(knowledge==null){var owned=repository.knowledge(job,chunk.path("knowledge_id").asText());if(owned.isEmpty()){bound=false;terminal=true;return out.put("skipped","knowledge_not_found");}knowledge=owned.get();}
      if(!repository.bound(job,knowledge,chunk)){bound=false;throw new TaskFailure("graph chunk binding changed",false);}String state=knowledge.path("parse_status").asText();if(Set.of("cancelled","deleting").contains(state)){terminal=true;return out.put("skipped","knowledge_"+state);}
      String content=chunk.path("content").asText();out.put("chunk_chars",content.codePointCount(0,content.length())).put("chunk_preview",preview(content,200));var kb=repository.base(job,chunk.path("knowledge_base_id").asText());var effective=new KnowledgeProcessConfig(json).resolve(kb,knowledge.path("metadata").get("process_overrides")).path("extract_config");if(!effective.path("enabled").asBoolean()){terminal=true;return out.put("skipped","extract_disabled");}
      if(!graph.enabled())throw new TaskFailure("Neo4j graph storage is disabled",false);var model=models.chat(job.tenantId(),job.payload().path("model_id").asText());ObjectNode response;try{response=model.chat(call,text.messages(content,effective),text.options());}catch(RuntimeException error){cancellation.check();String changed=repository.current(job,knowledge,chunk);if(!changed.isEmpty())throw new GraphExtractionRepository.Changed(changed);throw error;}cancellation.check();String reason=repository.current(job,knowledge,chunk);if(!reason.isEmpty())throw new GraphExtractionRepository.Changed(reason);var extracted=text.parse(response.path("content").asText());var nodes=extracted.nodes().stream().map(n->new GraphExtractionText.Node(n.name(),n.attributes(),List.of(chunk.path("id").asText()))).toList();var published=new GraphExtractionText.Graph(nodes,extracted.relations());ObjectNode original=knowledge;
      graph.add(kb.path("id").asText(),knowledge.path("id").asText(),published,call,()->{cancellation.check();String changed=repository.current(job,original,chunk);if(!changed.isEmpty())throw new GraphExtractionRepository.Changed(changed);});out.put("nodes_added",nodes.size()).put("relations_added",extracted.relations().size());if(!nodes.isEmpty())out.set("sample_nodes",json.valueToTree(nodes.stream().limit(2).map(GraphExtractionText.Node::name).toList()));if(!extracted.relations().isEmpty())out.set("sample_relations",json.valueToTree(extracted.relations().stream().limit(2).map(r->r.source()+" --["+r.type()+"]--> "+r.target()).toList()));terminal=true;return out;
    }catch(GraphExtractionRepository.Changed e){superseded=e.reason.equals("attempt_superseded");if(e.reason.equals("knowledge_not_found"))bound=false;terminal=true;return out.put("skipped",e.reason);}
    catch(GraphExtractionRepository.BindingChanged e){bound=false;failure=new TaskFailure("graph chunk binding changed",false);throw failure;}
    catch(Exception e){failure=e;throw e;}
    finally{monitor.cancel(false);if(bound&&!superseded&&(terminal||job.attempts()>=job.maxAttempts())&&cancellation.reason()!=TaskCancellation.Reason.LEASE_LOST)repository.drain(job,knowledge);if(tracker!=null&&span!=null){if(failure!=null)tracker.fail(span,"GRAPH_EXTRACT_FAILED",Objects.requireNonNullElse(failure.getMessage(),"Graph extraction failed"),failure);else tracker.end(span,out);}}
    }finally{watcher.shutdownNow();}
  }
  private static String preview(String content,int max){content=SummaryGenerationText.trim(content).replace("\n"," ").replace("\t"," ");while(content.contains("  "))content=content.replace("  "," ");return content.codePointCount(0,content.length())<=max?content:content.substring(0,content.offsetByCodePoints(0,max))+"...(truncated)";}
}
