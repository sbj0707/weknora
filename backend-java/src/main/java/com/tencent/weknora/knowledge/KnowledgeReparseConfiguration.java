package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.weknora.model.ModelClientFactory;
import com.tencent.weknora.model.client.CallContext;
import com.tencent.weknora.security.AuthContext;
import com.tencent.weknora.storage.StorageResolver;
import com.tencent.weknora.task.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@ConditionalOnProperty(name="weknora.knowledge.processing.enabled",havingValue="true")
public class KnowledgeReparseConfiguration {
  @Bean KnowledgeReparseService.Cleanup knowledgeReparseCleanup(JdbcTemplate jdbc,ObjectMapper json,
      KnowledgeRepository documents,KnowledgeProcessingSpanTracker tracker,TaskRepository queue,
      KnowledgeFileUpload.Enqueuer producer,DocumentProcessingHandler.Indexes indexes,
      ModelClientFactory models,GraphExtractionStore graph,ManualProcessingImages images,
      PlatformTransactionManager manager,Environment environment){
    var tx=new TransactionTemplate(manager);boolean postgres=Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>)c->c.getMetaData().getDatabaseProductName().equals("PostgreSQL")));
    KnowledgeReparseService.Cleanup cleanup=(owner,base,row,taskGuard)->{
      taskGuard.run();
      if(row.path("parse_status").asText().equals("draft")&&row.path("storage_size").asLong()==0)return;
      String id=row.path("id").asText(),kb=base.path("id").asText();List<String> failures=new ArrayList<>();
      Runnable guard=()->{
        taskGuard.run();
        var current=jdbc.queryForList("SELECT tenant_id,knowledge_base_id FROM knowledges WHERE id=? AND deleted_at IS NULL",id);
        if(current.isEmpty()||((Number)current.getFirst().get("tenant_id")).longValue()!=owner||!Objects.toString(current.getFirst().get("knowledge_base_id"),"").equals(kb))throw new java.util.concurrent.CancellationException("knowledge binding changed during reparse cleanup");
        var parents=jdbc.queryForList("SELECT tenant_id FROM knowledge_bases WHERE id=? AND deleted_at IS NULL",kb);if(parents.isEmpty()||((Number)parents.getFirst().get("tenant_id")).longValue()!=owner)throw new java.util.concurrent.CancellationException("knowledge base binding changed during reparse cleanup");
      };
      if(!row.path("embedding_model_id").asText().isEmpty())try{
        guard.run();var writer=indexes.resolve(owner,base);
        var model=models.embeddingForCleanup(new AuthContext("system-task",owner,"viewer",false,null,false,Set.of(),Set.of()),row.path("embedding_model_id").asText());writer.prepare(Set.of(model.dimensions()));
        tx.executeWithoutResult(s->jdbc.execute((ConnectionCallback<Void>)c->{guard.run();writer.deleteByKnowledge(c,List.of(id));return null;}));
      }catch(Exception error){failures.add(error instanceof com.tencent.weknora.security.ApiException typed&&typed.status()==404&&"Model not found".equals(typed.getMessage())?"model not found":message(error));}
      List<String> rawImages=List.of();try{guard.run();rawImages=jdbc.queryForList("SELECT image_info FROM chunks WHERE tenant_id=? AND knowledge_id=? AND deleted_at IS NULL AND image_info<>''",String.class,owner,id);}catch(Exception error){failures.add(message(error));}
      try{guard.run();Object now=postgres?Timestamp.from(Instant.now()):Instant.now().toString();jdbc.update("UPDATE chunks SET deleted_at=?,updated_at=? WHERE tenant_id=? AND knowledge_id=? AND deleted_at IS NULL",now,now,owner,id);}catch(Exception error){failures.add(message(error));}
      try{images.cleanup(new DocumentProcessingRepository.Snapshot(row,base,Map.of()),rawImages,guard);}catch(Exception ignored){}
      try(var call=new CallContext()){guard.run();graph.delete(kb,id,call,guard);}catch(Exception error){failures.add(message(error));}
      long old=row.path("storage_size").asLong();if(old>0){try{guard.run();jdbc.update("UPDATE tenants SET storage_used=CASE WHEN storage_used-? < 0 THEN 0 ELSE storage_used-? END WHERE id=?",old,old,owner);}catch(Exception error){failures.add(message(error));}row.put("storage_size",0);}
      taskGuard.run();if(!failures.isEmpty())throw new IllegalStateException(String.join("\n",failures));
    };
    return cleanup;
  }
  @Bean KnowledgeReparseService knowledgeReparseService(JdbcTemplate jdbc,ObjectMapper json,
      KnowledgeRepository documents,KnowledgeProcessingSpanTracker tracker,TaskRepository queue,
      KnowledgeFileUpload.Enqueuer producer,KnowledgeReparseService.Cleanup cleanup,Environment environment) {
    return new KnowledgeReparseService(jdbc,json,documents,tracker,queue,producer,cleanup,
        Duration.ofMillis(environment.getProperty("weknora.knowledge.document-timeout-ms",Long.class,7200000L)),Clock.systemUTC());
  }
  @Bean KnowledgeListReparseHandler knowledgeListReparseHandler(JdbcTemplate jdbc,ObjectMapper json,
      KnowledgeRepository documents,KnowledgeReparseService reparse,KnowledgeProcessingLanguage language){return new KnowledgeListReparseHandler(jdbc,json,documents,reparse,language);}
  // Retain the factory entry point used by the standalone Go differential runners.
  KnowledgeReparseService knowledgeReparseService(JdbcTemplate jdbc,ObjectMapper json,
      KnowledgeRepository documents,KnowledgeProcessingSpanTracker tracker,TaskRepository queue,
      KnowledgeFileUpload.Enqueuer producer,DocumentProcessingHandler.Indexes indexes,
      ModelClientFactory models,GraphExtractionStore graph,ManualProcessingImages images,
      PlatformTransactionManager manager,Environment environment) {
    return knowledgeReparseService(jdbc,json,documents,tracker,queue,producer,
        knowledgeReparseCleanup(jdbc,json,documents,tracker,queue,producer,indexes,models,graph,images,manager,environment),environment);
  }
  private static String message(Exception error){return error instanceof org.springframework.dao.DataAccessException?ChunkEditService.diagnostic(error):Objects.toString(error.getMessage(),"Internal server error");}
}
