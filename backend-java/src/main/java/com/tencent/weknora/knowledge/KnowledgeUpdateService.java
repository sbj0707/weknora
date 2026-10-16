package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.weknora.security.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(name="weknora.knowledge.processing.enabled",havingValue="true")
public final class KnowledgeUpdateService {
  private final JdbcTemplate jdbc;private final ObjectMapper json;private final KnowledgeBaseWriteAuthorization writes;private final KnowledgeRepository documents;private final KnowledgeSummaryRefresh refresh;private final TransactionTemplate tx;private final KnowledgeTransferState transfer;
  public KnowledgeUpdateService(JdbcTemplate jdbc,ObjectMapper json,KnowledgeBaseWriteAuthorization writes,KnowledgeRepository documents,KnowledgeSummaryRefresh refresh,PlatformTransactionManager manager){this.jdbc=jdbc;this.json=json;this.writes=writes;this.documents=documents;this.refresh=refresh;tx=new TransactionTemplate(manager);transfer=new KnowledgeTransferState(json);}
  public KnowledgeBaseWriteAuthorization.WriteAccess authorize(AuthContext caller,String id,String raw){
    ChunkLifecycleService.capability(caller,"ingest");
    List<Map<String,Object>> rows;
    try{rows=jdbc.queryForList("SELECT * FROM knowledges WHERE id=? AND deleted_at IS NULL",id);}catch(org.springframework.dao.DataAccessException failure){throw new ApiException(503,"SERVICE_UNAVAILABLE","cannot verify KB access right now").withAppCode(1008);}
    if(rows.isEmpty())throw missing("Knowledge not found");var row=rows.getFirst();
    var grant=writes.authorizeDocumentUpdate(caller,text(row,"knowledge_base_id"),raw,"PUT");
    if(number(row,"tenant_id")!=grant.effectiveTenantId())throw new ApiException(403,"FORBIDDEN","Permission denied to access this knowledge").withAppCode(1002);
    return grant;
  }
  public ObjectNode update(KnowledgeBaseWriteAuthorization.WriteAccess grant,String id,KnowledgeUpdateRequest command,String language){
    try {
      var saved=tx.execute(status->{
        var rows=jdbc.queryForList("SELECT * FROM knowledges WHERE tenant_id=? AND id=? AND deleted_at IS NULL",grant.effectiveTenantId(),id);
        if(rows.isEmpty())throw internal("knowledge not found");var row=rows.getFirst();
        if(transfer.moving(row.get("metadata")))throw new ApiException(409,"CONFLICT","knowledge has an unfinished move; retry the move first").withAppCode(1005);
        var bases=jdbc.queryForList("SELECT tenant_id FROM knowledge_bases WHERE id=? AND deleted_at IS NULL",text(row,"knowledge_base_id"));
        if(bases.isEmpty())throw missing("knowledge base not found");
        if(number(bases.getFirst(),"tenant_id")!=number(row,"tenant_id")||!text(row,"knowledge_base_id").equals(grant.knowledgeBaseId()))throw new ApiException(403,"FORBIDDEN","knowledge does not belong to its knowledge base").withAppCode(1002);
        String title=command.title()!=null&&!command.title().isEmpty()?command.title():text(row,"title");
        String description=command.description()!=null?command.description():text(row,"description");
        String summary=command.description()!=null?(description.isEmpty()?"none":"completed"):text(row,"summary_status");boolean changed=false;
        if(command.customMetadata()!=null) {
          JsonNode metadata;try{metadata=KnowledgeUpdateRequest.metadata(command.customMetadata(),json);}catch(IOException error){throw internal("custom_metadata must be a JSON object: "+error.getMessage());}KnowledgeUpdateRequest.validate(metadata);
          JsonNode old=KnowledgeUpdateRequest.existingMetadata(raw(row.get("custom_metadata")),json);
          changed=!KnowledgeUpdateRequest.equivalent(old,metadata);
        }
        boolean pg=jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)c->c.getMetaData().getDatabaseProductName().equals("PostgreSQL"));
        String sql="UPDATE knowledges SET title=?,description=?,summary_status=?,updated_at=?"+(command.customMetadata()!=null?",custom_metadata="+(pg?"?::json":"?"):"")+" WHERE tenant_id=? AND id=? AND deleted_at IS NULL";
        var values=new ArrayList<Object>(List.of(title,description,summary,pg?Timestamp.from(Instant.now()):GoSqliteTimestamp.write(Instant.now())));if(command.customMetadata()!=null)values.add(command.customMetadata());values.add(grant.effectiveTenantId());values.add(id);jdbc.update(sql,values.toArray());
        return new Saved(changed,summary);
      });
      if(saved.changed())refresh.enqueue(grant.effectiveTenantId(),grant.knowledgeBaseId(),id,saved.summary(),language);
    }catch(ApiException typed){throw typed;}catch(RuntimeException failure){throw internal(diagnostic(failure));}
    try {var result=documents.get(grant.effectiveTenantId(),id).orElse(null);if(result!=null){documents.assignTags(grant.effectiveTenantId(),grant.knowledgeBaseId(),result,List.of());var stored=jdbc.queryForList("SELECT custom_metadata FROM knowledges WHERE tenant_id=? AND id=? AND deleted_at IS NULL",grant.effectiveTenantId(),id);if(!stored.isEmpty()&&stored.getFirst().get("custom_metadata")!=null)result.putRawValue("custom_metadata",new com.fasterxml.jackson.databind.util.RawValue(raw(stored.getFirst().get("custom_metadata"))));}return result;}catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Knowledge updated but failed to reload {}",id,failure);return null;}
  }
  private record Saved(boolean changed,String summary){}
  private static long number(Map<String,Object> row,String field){return ((Number)row.get(field)).longValue();}
  private static String text(Map<String,Object> row,String field){return Objects.toString(row.get(field),"");}
  private static String raw(Object value){return value instanceof byte[] bytes?new String(bytes,StandardCharsets.UTF_8):Objects.toString(value,"");}
  private static String diagnostic(RuntimeException error){Throwable cause=error;while(cause.getCause()!=null)cause=cause.getCause();if(cause instanceof org.postgresql.util.PSQLException pg&&pg.getServerErrorMessage()!=null){var server=pg.getServerErrorMessage();return server.getSeverity()+": "+server.getMessage()+" (SQLSTATE "+pg.getSQLState()+")";}String text=Objects.toString(cause.getMessage(),"Internal server error");if(text.startsWith("[SQLITE_")&&text.endsWith(")")){int at=text.indexOf(" (");if(at>=0)text=text.substring(at+2,text.length()-1);}return text;}
  private static ApiException internal(String message){return new ApiException(500,"INTERNAL_SERVER_ERROR",message).withAppCode(1007);}
  private static ApiException missing(String message){return new ApiException(404,"NOT_FOUND",message).withAppCode(1003);}
}
