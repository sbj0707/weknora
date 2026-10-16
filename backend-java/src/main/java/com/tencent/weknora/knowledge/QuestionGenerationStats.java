package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.weknora.task.TaskRecord;

/** Fields are the existing Go span output contract, not Java worker result internals. */
final class QuestionGenerationStats {
  final ObjectNode output;final boolean batch;
  QuestionGenerationStats(ObjectMapper json,TaskRecord job,boolean batch,int chunks){this.batch=batch;output=json.createObjectNode().put("status","success").put("questions_generated",0).put("index_entries_prepared",0).put("index_batch_succeeded",false).put("retry",Math.max(0,job.attempts()-1)).put("max_retry",Math.max(0,job.maxAttempts()-1));if(batch){output.put("batch_index",job.payload().path("batch_index").asInt()).put("chunks_in_batch",chunks).put("chunks_processed",0).put("empty_chunks",0).put("llm_failed",0);}else {for(String key:java.util.List.of("total_chunks","text_chunks","empty_content_chunks","llm_attempts","llm_success","llm_empty","llm_failed","chunk_update_failed","metadata_set_failed"))output.put(key,0);output.put("index_batch_attempted",false);}}
  void status(String status){output.put("status",status);}
  void increment(String key){output.put(key,output.path(key).asInt()+1);}
  void generated(int count,String sample){output.put("questions_generated",output.path("questions_generated").asInt()+count);if(!output.has("sample_question"))output.put("sample_question",SummaryGenerationText.bound(sample,200));}
}
