package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.weknora.security.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Folder paths are display/navigation labels; re-filing does not rebuild stored documents. */
@Service
@ConditionalOnProperty(name="weknora.knowledge.processing.enabled",havingValue="true")
public final class KnowledgeFolderService {
  private final JdbcTemplate jdbc;
  private final KnowledgeBaseReadAuthorization reads;
  private final KnowledgeBaseWriteAuthorization writes;
  private final TransactionTemplate tx;
  private final KnowledgeTransferState transfer;
  public KnowledgeFolderService(JdbcTemplate jdbc,KnowledgeBaseReadAuthorization reads,KnowledgeBaseWriteAuthorization writes,PlatformTransactionManager manager,ObjectMapper json){this.jdbc=jdbc;this.reads=reads;this.writes=writes;tx=new TransactionTemplate(manager);transfer=new KnowledgeTransferState(json);}
  public KnowledgeBaseReadAuthorization.ReadAccess read(AuthContext caller,String kb,String agent,String source){ChunkLifecycleService.capability(caller,"retrieve");caller.requireRole("viewer");return reads.authorizeRead(caller,kb,agent,source);}
  public KnowledgeBaseWriteAuthorization.WriteAccess renameGrant(AuthContext caller,String kb,String raw){return writes.authorizeFolderRename(caller,kb,raw,"PUT");}
  public KnowledgeBaseWriteAuthorization.WriteAccess moveGrant(AuthContext caller,String kb,String raw){return writes.authorizeFolderMove(caller,kb,raw,"POST");}
  public Map<String,Object> tree(KnowledgeBaseReadAuthorization.ReadAccess grant){
    try {
      var nodes=new LinkedHashMap<String,Node>();long root=0,total=0;
      for(var row:jdbc.queryForList("SELECT folder_path,COUNT(*) AS count FROM knowledges WHERE tenant_id=? AND knowledge_base_id=? AND parse_status<>'deleting' AND deleted_at IS NULL GROUP BY folder_path",grant.effectiveTenantId(),grant.knowledgeBaseId())) {
        long count=((Number)row.get("count")).longValue();total+=count;String path=KnowledgeImportNames.normalizeFolder(text(row,"folder_path"));
        if(path.isEmpty()){root+=count;continue;}ensure(nodes,path).direct+=count;
      }
      var paths=new ArrayList<>(nodes.keySet());paths.sort(Comparator.<String>comparingLong(p->p.chars().filter(c->c=='/').count()).reversed());
      for(String path:paths){Node node=nodes.get(path);node.total+=node.direct;String parent=parent(path);if(!parent.isEmpty())nodes.get(parent).total+=node.total;}
      var roots=new ArrayList<Node>();for(Node node:nodes.values()){String parent=parent(node.path);if(parent.isEmpty())roots.add(node);else nodes.get(parent).children.add(node);}
      Comparator<Node> order=(a,b)->Arrays.compareUnsigned(lower(a.name).getBytes(StandardCharsets.UTF_8),lower(b.name).getBytes(StandardCharsets.UTF_8));
      roots.sort(order);nodes.values().forEach(n->n.children.sort(order));
      return Map.of("root_document_count",root,"total_document_count",total,"folders",roots.stream().map(Node::dto).toList());
    }catch(DataAccessException failure){throw internal(diagnostic(failure));}
  }
  public long rename(KnowledgeBaseWriteAuthorization.WriteAccess grant,String from,String to){
    String source=KnowledgeImportNames.normalizeFolder(from);if(source.isEmpty())throw KnowledgeFolderRequest.bad("源文件夹路径不能为空");
    String target=target(to);if(target.isEmpty())throw KnowledgeFolderRequest.bad("目标文件夹路径不能为空");if(source.equals(target))return 0;if(target.startsWith(source+"/"))throw KnowledgeFolderRequest.bad("不能将文件夹移动到它自己的子目录下");
    try {return tx.execute(status->{
      currentBase(grant);
      String escaped=source.replace("\\","\\\\").replace("%","\\%").replace("_","\\_");
      var rows=jdbc.queryForList("SELECT id,folder_path FROM knowledges WHERE tenant_id=? AND knowledge_base_id=? AND deleted_at IS NULL AND (folder_path=? OR folder_path LIKE ? ESCAPE ?)",grant.effectiveTenantId(),grant.knowledgeBaseId(),source,escaped+"/%","\\");
      long affected=0;for(var row:rows){String destination=KnowledgeImportNames.normalizeFolder(target+text(row,"folder_path").substring(source.length()));affected+=jdbc.update("UPDATE knowledges SET folder_path=?,updated_at=? WHERE tenant_id=? AND knowledge_base_id=? AND id=? AND deleted_at IS NULL",destination,now(),grant.effectiveTenantId(),grant.knowledgeBaseId(),row.get("id"));}return affected;
    });}catch(DataAccessException failure){throw internal(diagnostic(failure));}
  }
  public long move(KnowledgeBaseWriteAuthorization.WriteAccess grant,List<String> ids,String folder){
    // Handler verifies the entire batch before service validation of the target label.
    String placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));var args=new ArrayList<Object>();args.add(grant.effectiveTenantId());args.addAll(ids);
    try {
      var selected=jdbc.queryForList("SELECT * FROM knowledges WHERE tenant_id=? AND id IN ("+placeholders+") AND deleted_at IS NULL",args.toArray());
      if(selected.size()!=ids.size())throw KnowledgeFolderRequest.bad("One or more knowledge entries not found");
      for(var row:selected)if(!text(row,"knowledge_base_id").equals(grant.knowledgeBaseId()))throw KnowledgeFolderRequest.bad("Knowledge "+KnowledgeImportNames.sanitizeForLog(text(row,"id"))+" does not belong to knowledge base "+KnowledgeImportNames.sanitizeForLog(grant.knowledgeBaseId()));
      String target=target(folder);
      return tx.execute(status->{
        currentBase(grant);var byId=new HashMap<String,Map<String,Object>>();for(var row:jdbc.queryForList("SELECT * FROM knowledges WHERE tenant_id=? AND id IN ("+placeholders+") AND deleted_at IS NULL",args.toArray()))byId.put(text(row,"id"),row);
        for(String id:ids){var row=byId.get(id);if(row==null)throw new ApiException(404,"NOT_FOUND","knowledge not found").withAppCode(1003);if(transfer.moving(row.get("metadata")))throw new ApiException(409,"CONFLICT","knowledge has an unfinished move; retry the move first").withAppCode(1005);if(!text(row,"knowledge_base_id").equals(grant.knowledgeBaseId()))throw new ApiException(403,"FORBIDDEN","knowledge outside target KB").withAppCode(1002);}
        var update=new ArrayList<Object>();update.add(target);update.add(now());update.add(grant.effectiveTenantId());update.add(grant.knowledgeBaseId());update.addAll(ids);
        return (long)jdbc.update("UPDATE knowledges SET folder_path=?,updated_at=? WHERE tenant_id=? AND knowledge_base_id=? AND id IN ("+placeholders+") AND deleted_at IS NULL",update.toArray());
      });
    }catch(DataAccessException failure){throw internal(diagnostic(failure));}catch(IllegalArgumentException failure){throw internal(failure.getMessage());}
  }
  private void currentBase(KnowledgeBaseWriteAuthorization.WriteAccess grant){var rows=jdbc.queryForList("SELECT tenant_id FROM knowledge_bases WHERE id=? AND deleted_at IS NULL",grant.knowledgeBaseId());if(rows.isEmpty())throw new ApiException(404,"NOT_FOUND","knowledge base not found").withAppCode(1003);if(((Number)rows.getFirst().get("tenant_id")).longValue()!=grant.effectiveTenantId())throw new ApiException(403,"FORBIDDEN","knowledge does not belong to its knowledge base").withAppCode(1002);}
  private String target(String value){var safe=KnowledgeImportNames.validate(KnowledgeUploadForms.goTrim(value));if(!safe.valid())throw new ApiException(400,"VALIDATION_ERROR","文件夹路径包含非法字符").withAppCode(1010);return KnowledgeImportNames.normalizeFolder(safe.value());}
  private Object now(){return jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)c->c.getMetaData().getDatabaseProductName().equals("PostgreSQL"))?Timestamp.from(Instant.now()):GoSqliteTimestamp.write(Instant.now());}
  private static Node ensure(Map<String,Node> nodes,String path){Node found=nodes.get(path);if(found!=null)return found;var node=new Node(path);nodes.put(path,node);String parent=parent(path);if(!parent.isEmpty())ensure(nodes,parent);return node;}
  private static String parent(String path){int at=path.lastIndexOf('/');return at<0?"":path.substring(0,at);}
  private static String lower(String value){var out=new StringBuilder();value.codePoints().forEach(cp->out.appendCodePoint(Character.toLowerCase(cp)));return out.toString();}
  private static String text(Map<String,Object> row,String field){return Objects.toString(row.get(field),"");}
  private static String diagnostic(RuntimeException failure){Throwable cause=failure;while(cause.getCause()!=null)cause=cause.getCause();if(cause instanceof org.postgresql.util.PSQLException pg&&pg.getServerErrorMessage()!=null){var server=pg.getServerErrorMessage();return server.getSeverity()+": "+server.getMessage()+" (SQLSTATE "+pg.getSQLState()+")";}String message=Objects.toString(cause.getMessage(),"Internal server error");if(message.startsWith("[SQLITE_")&&message.endsWith(")")){int at=message.indexOf(" (");if(at>=0)message=message.substring(at+2,message.length()-1);}return message;}
  private static ApiException internal(String message){return new ApiException(500,"INTERNAL_SERVER_ERROR",message).withAppCode(1007);}
  private static final class Node {final String path,name;long direct,total;final List<Node> children=new ArrayList<>();Node(String path){this.path=path;name=path.substring(path.lastIndexOf('/')+1);}Map<String,Object> dto(){var out=new LinkedHashMap<String,Object>();out.put("path",path);out.put("name",name);out.put("document_count",direct);out.put("total_count",total);if(!children.isEmpty())out.put("children",children.stream().map(Node::dto).toList());return out;}}
}
