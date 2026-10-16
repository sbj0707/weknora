package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.weknora.security.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Direct chunk tombstones and immutable revision reads, separate from physical index cleanup. */
@Service
@ConditionalOnProperty(name="weknora.knowledge.processing.enabled",havingValue="true")
public final class ChunkLifecycleService {
  private final JdbcTemplate jdbc;
  private final KnowledgeBaseWriteAuthorization writes;
  private final KnowledgeBaseReadAuthorization reads;
  private final TransactionTemplate tx;
  private final KnowledgeTransferState transfer;
  public ChunkLifecycleService(JdbcTemplate jdbc,KnowledgeBaseWriteAuthorization writes,KnowledgeBaseReadAuthorization reads,PlatformTransactionManager manager,ObjectMapper json){this.jdbc=jdbc;this.writes=writes;this.reads=reads;tx=new TransactionTemplate(manager);transfer=new KnowledgeTransferState(json);}
  public KnowledgeBaseWriteAuthorization.WriteAccess authorizeWrite(AuthContext caller,String document,String rawPath,boolean single){
    capability(caller,"ingest");var row=lookup(document);
    return writes.authorizeChunkMutation(caller,text(row,"knowledge_base_id"),rawPath,"DELETE",single);
  }
  public KnowledgeBaseReadAuthorization.ReadAccess authorizeRead(AuthContext caller,String document,String agent,String source){
    capability(caller,"retrieve");caller.requireRole("viewer");var row=lookup(document);
    return reads.authorizeRead(caller,text(row,"knowledge_base_id"),agent,source);
  }
  private Map<String,Object> lookup(String id){
    try{var rows=jdbc.queryForList("SELECT * FROM knowledges WHERE id=? AND deleted_at IS NULL",id);if(rows.isEmpty())throw missing("Knowledge not found");return rows.getFirst();}
    catch(DataAccessException failure){throw new ApiException(503,"SERVICE_UNAVAILABLE","cannot verify KB access right now").withAppCode(1008);}
  }
  public void delete(KnowledgeBaseWriteAuthorization.WriteAccess grant,String document,String chunk){
    var selected=chunk(grant.effectiveTenantId(),document,chunk);
    execute(()->tx.executeWithoutResult(status->{
      writableDocument(grant,document,false);
      var current=chunk(grant.effectiveTenantId(),document,text(selected,"id"));
      if(!text(current,"knowledge_base_id").equals(grant.knowledgeBaseId()))throw wrapped(1002,"chunk does not belong to its knowledge base");
      jdbc.update("UPDATE chunks SET deleted_at=? WHERE tenant_id=? AND id=? AND deleted_at IS NULL",timestamp(),grant.effectiveTenantId(),text(current,"id"));
    }));
  }
  public void deleteAll(KnowledgeBaseWriteAuthorization.WriteAccess grant,String document){
    execute(()->tx.executeWithoutResult(status->{
      if(AuthPreferences.trimGoSpace(document).isEmpty())throw wrapped(1000,"resource ID cannot be empty");
      writableDocument(grant,document,true);
      // Go bulk deletion is scoped by tenant and document, across all chunk types/statuses.
      jdbc.update("UPDATE chunks SET deleted_at=? WHERE tenant_id=? AND knowledge_id=? AND deleted_at IS NULL",timestamp(),grant.effectiveTenantId(),document);
    }));
  }
  private void writableDocument(KnowledgeBaseWriteAuthorization.WriteAccess grant,String document,boolean batch){
    var rows=jdbc.queryForList("SELECT * FROM knowledges WHERE tenant_id=? AND id=? AND deleted_at IS NULL",grant.effectiveTenantId(),document);
    if(rows.isEmpty()){if(batch)throw wrapped(1003,"knowledge not found");throw internal("knowledge not found");}
    var row=rows.getFirst();if(transfer.moving(row.get("metadata")))throw wrapped(1005,"knowledge has an unfinished move; retry the move first");
    var bases=jdbc.queryForList("SELECT * FROM knowledge_bases WHERE id=? AND deleted_at IS NULL",text(row,"knowledge_base_id"));
    if(bases.isEmpty())throw internal("knowledge base not found");
    if(number(bases.getFirst(),"tenant_id")!=number(row,"tenant_id")||!text(row,"knowledge_base_id").equals(grant.knowledgeBaseId()))throw wrapped(1002,"knowledge does not belong to its knowledge base");
  }
  private Map<String,Object> chunk(long owner,String document,String id){
    if(document.isEmpty())throw bad("Knowledge ID cannot be empty");if(id.isEmpty())throw bad("Chunk ID cannot be empty");
    try{var rows=jdbc.queryForList("SELECT * FROM chunks WHERE tenant_id=? AND id=? AND deleted_at IS NULL",owner,id);
      if(rows.isEmpty())throw missing("Chunk not found");var row=rows.getFirst();if(!text(row,"knowledge_id").equals(document))throw new ApiException(403,"FORBIDDEN","No permission to access this chunk").withAppCode(1002);return row;
    }catch(DataAccessException failure){throw internal(diagnostic(failure));}
  }
  public List<Map<String,Object>> revisions(KnowledgeBaseReadAuthorization.ReadAccess grant,String document,String id){
    chunk(grant.effectiveTenantId(),document,id);
    try{var result=new ArrayList<Map<String,Object>>();for(var row:jdbc.queryForList("SELECT * FROM chunk_revisions WHERE tenant_id=? AND chunk_id=? ORDER BY revision DESC",grant.effectiveTenantId(),id)){
      var out=new LinkedHashMap<String,Object>();for(String field:List.of("id","knowledge_base_id","knowledge_id","chunk_id","content","editor_id","edit_source"))out.put(field,text(row,field));
      out.put("tenant_id",number(row,"tenant_id"));out.put("revision",number(row,"revision"));Object enabled=row.get("is_enabled");out.put("is_enabled",enabled instanceof Boolean b?b:enabled!=null&&(enabled.toString().equals("1")||enabled.toString().equalsIgnoreCase("true")));
      for(String field:List.of("edited_at","created_at"))out.put(field,row.get(field)==null?"0001-01-01T00:00:00Z":KnowledgeBaseRepository.timestamp(row.get(field)));result.add(out);
    }return result;}catch(DataAccessException failure){throw internal(diagnostic(failure));}
  }
  private Object timestamp(){return jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)c->c.getMetaData().getDatabaseProductName().equals("PostgreSQL"))?Timestamp.from(Instant.now()):Instant.now().toString();}
  private static void execute(Runnable action){try{action.run();}catch(ApiException typed){throw typed;}catch(RuntimeException failure){throw internal(diagnostic(failure));}}
  private static String diagnostic(RuntimeException failure){Throwable cause=failure;while(cause.getCause()!=null)cause=cause.getCause();if(cause instanceof org.postgresql.util.PSQLException pg&&pg.getServerErrorMessage()!=null){var server=pg.getServerErrorMessage();return server.getSeverity()+": "+server.getMessage()+" (SQLSTATE "+pg.getSQLState()+")";}String message=Objects.toString(cause.getMessage(),"Internal server error");if(message.startsWith("[SQLITE_")&&message.endsWith(")")){int open=message.indexOf(" (");if(open>=0)message=message.substring(open+2,message.length()-1);}return message;}
  static void capability(AuthContext caller,String capability){if(caller.isApiKey()&&!caller.fullAccess()&&!caller.capabilities().contains(capability))throw new ApiException(403,"FORBIDDEN","Forbidden: API key scope does not allow this operation").withResponse(Map.of("error","Forbidden: API key scope does not allow this operation"));}
  private static long number(Map<String,Object> row,String field){Object value=row.get(field);return value instanceof Number n?n.longValue():0;}
  private static String text(Map<String,Object> row,String field){return Objects.toString(row.get(field),"");}
  private static ApiException wrapped(int code,String message){return internal("error code: "+code+", error message: "+message);}
  private static ApiException internal(String message){return new ApiException(500,"INTERNAL_SERVER_ERROR",message).withAppCode(1007);}
  private static ApiException missing(String message){return new ApiException(404,"NOT_FOUND",message).withAppCode(1003);}
  private static ApiException bad(String message){return new ApiException(400,"BAD_REQUEST",message).withAppCode(1000);}
}
