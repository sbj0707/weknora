package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.task.*;
import java.time.*;
import java.util.*;

/** Per-text/standalone-scan graph work participates in the common SQL counter handoff. */
public final class GraphExtractionFanout {
  private final GraphExtractionStore store;private final KnowledgeFileUpload.Enqueuer tasks;private final ObjectMapper json;
  public GraphExtractionFanout(GraphExtractionStore store,KnowledgeFileUpload.Enqueuer tasks,ObjectMapper json){this.store=store;this.tasks=tasks;this.json=json;}
  public boolean enabled(){return store.enabled();}
  public List<ObjectNode> select(List<ObjectNode> chunks){var text=new HashMap<String,ObjectNode>();chunks.stream().filter(c->c.path("chunk_type").asText().equals("text")).forEach(c->text.put(c.path("id").asText(),c));return chunks.stream().filter(c->{String kind=c.path("chunk_type").asText();if(kind.equals("image_caption")||!Set.of("text","image_ocr").contains(kind)||!QuestionGenerationText.extractable(c.path("content").asText()))return false;var parent=text.get(c.path("parent_chunk_id").asText());return !kind.equals("image_ocr")||parent==null||!QuestionGenerationText.extractable(parent.path("content").asText());}).toList();}
  public void enqueue(TaskRecord job,JsonNode kb,ObjectNode chunk,int ordinal){if(!enabled())return;var p=json.createObjectNode().put("tenant_id",job.tenantId()).put("chunk_id",chunk.path("id").asText()).put("model_id",kb.path("summary_model_id").asText());String doc=job.payload().path("knowledge_id").asText();if(!doc.isEmpty())p.put("knowledge_id",doc);if(job.payload().path("attempt").asLong()>0)p.put("attempt",job.payload().path("attempt").asLong());if(ordinal>0)p.put("chunk_index",ordinal);for(String field:List.of("lf_trace_id","lf_parent_obs_id","lf_traceparent","lf_user_id","lf_session_id"))if(!job.payload().path(field).asText().isEmpty())p.set(field,job.payload().get(field));try{tasks.enqueue(job.tenantId(),"chunk:extract",p,new KnowledgeFileUpload.TaskOptions("graph",3,Duration.ofMinutes(30)));}catch(Exception e){throw new IllegalStateException("graph handoff enqueue failed",e);}}
}
