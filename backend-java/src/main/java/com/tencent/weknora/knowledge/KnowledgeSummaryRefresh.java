package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Refresh admission follows metadata persistence; admission failure must not roll back the edit. */
@Service
@ConditionalOnProperty(name="weknora.knowledge.processing.enabled",havingValue="true")
public final class KnowledgeSummaryRefresh {
  private final JdbcTemplate jdbc;private final ObjectMapper json;private final ObjectProvider<KnowledgeFileUpload.Enqueuer> tasks;private final KnowledgeProcessingSpanTracker tracker;
  public KnowledgeSummaryRefresh(JdbcTemplate jdbc,ObjectMapper json,ObjectProvider<KnowledgeFileUpload.Enqueuer> tasks,KnowledgeProcessingSpanTracker tracker){this.jdbc=jdbc;this.json=json;this.tasks=tasks;this.tracker=tracker;}
  public void enqueue(long owner,String kb,String id,String status,String language){
    try{enqueueRequired(owner,kb,id,status,language);}catch(Exception failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Metadata saved but summary refresh admission failed for {}: {}",id,failure.getMessage());}
  }
  /** Explicit refresh must expose admission failure; metadata edits remain best effort. */
  public void enqueueRequired(long owner,String kb,String id,String status,String language)throws Exception{
    if(status.isEmpty()||status.equals("none"))return;
    try {
      var producer=tasks.getIfAvailable();if(producer==null)throw new IllegalStateException("summary refresh dependencies are unavailable");
      var bases=jdbc.queryForList("SELECT summary_model_id FROM knowledge_bases WHERE tenant_id=? AND id=? AND deleted_at IS NULL",owner,kb);
      if(bases.isEmpty())throw new IllegalStateException("knowledge base not found");
      if(Objects.toString(bases.getFirst().get("summary_model_id"),"").isEmpty())throw new IllegalStateException("summary model is not configured");
      var payload=json.createObjectNode().put("tenant_id",owner).put("knowledge_base_id",kb).put("knowledge_id",id).put("language",language).put("refresh",true);
      long attempt=tracker==null?0:tracker.latest(owner,id);if(attempt!=0)payload.put("attempt",attempt);
      state(owner,id,"pending");producer.enqueue(owner,"summary:generation",payload,new KnowledgeFileUpload.TaskOptions("summary",3,Duration.ofMinutes(30)));
    }catch(Exception failure){state(owner,id,"failed");throw failure;}
  }
  private void state(long owner,String id,String value){try{boolean pg=jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)c->c.getMetaData().getDatabaseProductName().equals("PostgreSQL"));jdbc.update("UPDATE knowledges SET summary_status=?,updated_at=? WHERE tenant_id=? AND id=? AND deleted_at IS NULL",value,pg?java.sql.Timestamp.from(java.time.Instant.now()):GoSqliteTimestamp.write(java.time.Instant.now()),owner,id);}catch(RuntimeException ignored){}}
}
