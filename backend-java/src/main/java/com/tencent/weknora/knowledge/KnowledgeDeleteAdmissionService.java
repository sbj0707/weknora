package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.security.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Admits current persisted child bindings; the worker rechecks those bindings before effects. */
@Service
@ConditionalOnProperty(name="weknora.knowledge.processing.enabled",havingValue="true")
public final class KnowledgeDeleteAdmissionService {
  private final JdbcTemplate jdbc;private final ObjectMapper json;private final KnowledgeListDeleteEnqueuer producer;
  public KnowledgeDeleteAdmissionService(JdbcTemplate jdbc,ObjectMapper json,KnowledgeListDeleteEnqueuer producer){this.jdbc=jdbc;this.json=json;this.producer=producer;}
  public Map<String,Object> lookup(String id){var rows=jdbc.queryForList("SELECT * FROM knowledges WHERE id=? AND deleted_at IS NULL",id);if(rows.isEmpty())throw new ApiException(404,"NOT_FOUND","Knowledge not found").withAppCode(1003);return rows.getFirst();}
  public void validateBatch(KnowledgeBaseWriteAuthorization.WriteAccess grant,List<String> ids){
    var args=new ArrayList<Object>();args.add(grant.effectiveTenantId());args.addAll(ids);
    var rows=jdbc.queryForList("SELECT * FROM knowledges WHERE tenant_id=? AND id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+") AND deleted_at IS NULL",args.toArray());
    if(rows.size()!=ids.size())throw KnowledgeDeleteRequest.bad("One or more knowledge entries not found");
    for(var row:rows){moving(row);if(!KnowledgeListDeleteRepository.text(row,"knowledge_base_id").equals(grant.knowledgeBaseId()))throw KnowledgeDeleteRequest.bad("Knowledge "+KnowledgeImportNames.sanitizeForLog(KnowledgeListDeleteRepository.text(row,"id"))+" does not belong to knowledge base "+KnowledgeImportNames.sanitizeForLog(grant.knowledgeBaseId()));}
  }
  public List<String> list(KnowledgeBaseWriteAuthorization.WriteAccess grant){var ids=new ArrayList<String>();for(var row:jdbc.queryForList("SELECT * FROM knowledges WHERE tenant_id=? AND knowledge_base_id=? AND deleted_at IS NULL ORDER BY created_at DESC",grant.effectiveTenantId(),grant.knowledgeBaseId())){moving(row);ids.add(KnowledgeListDeleteRepository.text(row,"id"));}return List.copyOf(ids);}
  public String enqueue(AuthContext caller,KnowledgeBaseWriteAuthorization.WriteAccess grant,List<String> ids,String message){try{return producer.enqueue(caller,grant.effectiveTenantId(),grant.knowledgeBase(),ids,null);}catch(RuntimeException e){throw new ApiException(500,"INTERNAL_SERVER_ERROR",message).withAppCode(1007);}}
  public void moving(Map<String,Object> row){boolean moving;try{moving=new KnowledgeTransferState(json).moving(row.get("metadata"));}catch(IllegalArgumentException e){throw new ApiException(500,"INTERNAL_SERVER_ERROR","Internal server error").withResponse(Map.of("success",false,"error",Map.of("code",1007,"message","Internal server error")));}if(moving)throw new ApiException(409,"CONFLICT","knowledge has an unfinished move; retry the move first").withAppCode(1005);}
}
