package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.model.client.CallContext;
import java.sql.*;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Independent Go teardown statements; failed best-effort stages do not roll back earlier work. */
public final class KnowledgeBaseDeleteRepository implements KnowledgeBaseDeleteProcessor.Repository {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final Clock clock;
  private final TransactionTemplate transaction;
  private final boolean postgres;
  private final KnowledgeBaseDeleteSql sql;
  public KnowledgeBaseDeleteRepository(JdbcTemplate jdbc,ObjectMapper json,PlatformTransactionManager manager,Clock clock) {
    this.jdbc=jdbc;this.json=json;this.clock=clock;this.sql=new KnowledgeBaseDeleteSql(jdbc);transaction=new TransactionTemplate(manager);
    postgres=Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>)c->c.getMetaData().getDatabaseProductName().equals("PostgreSQL")));
  }
  public List<KnowledgeBaseDeleteProcessor.Document> documents(KnowledgeBaseDeleteProcessor.Payload payload,CallContext call) {
    call.check();
    return sql.query(call,"SELECT id,embedding_model_id,type,file_path,storage_size FROM knowledges WHERE tenant_id=? AND knowledge_base_id=? AND deleted_at IS NULL ORDER BY created_at DESC",payload.tenantId(),payload.knowledgeBaseId())
      .stream().map(row->new KnowledgeBaseDeleteProcessor.Document(text(row,"id"),text(row,"embedding_model_id"),text(row,"type"),text(row,"file_path"),number(row.get("storage_size")))).toList();
  }
  public List<String> images(KnowledgeBaseDeleteProcessor.Payload payload,List<String> ids,CallContext call) {
    call.check();if(ids.isEmpty())return List.of();var args=new ArrayList<Object>();args.add(payload.tenantId());args.addAll(ids);
    var images=new LinkedHashSet<String>();
    for(var row:sql.query(call,"SELECT image_info FROM chunks WHERE tenant_id=? AND knowledge_id IN ("+marks(ids.size())+") AND image_info!='' AND deleted_at IS NULL",args.toArray())) {
      Object raw=row.get("image_info");
      try {
        String text=raw instanceof byte[] bytes?com.tencent.weknora.docreader.GoUtf8.decode(bytes):raw.toString();
        JsonNode values=KnowledgeImageRequest.images(text,json);
        for(var image:values){if(image.isNull())throw new KnowledgeBaseDeleteProcessor.Panic();String url=image.path("url").asText("");if(!url.isEmpty())images.add(url);}
      }catch(KnowledgeBaseDeleteProcessor.Panic panic){throw panic;}
      catch(Exception malformed){/* Go logs malformed image_info and continues with other rows. */}
    }
    return List.copyOf(images);
  }
  public void chunks(KnowledgeBaseDeleteProcessor.Payload payload,String id,CallContext call) {
    call.check();sql.update(call,"UPDATE chunks SET deleted_at=? WHERE tenant_id=? AND knowledge_id=? AND deleted_at IS NULL",time(),payload.tenantId(),id);
  }
  public void accounting(KnowledgeBaseDeleteProcessor.Payload payload,long delta,CallContext call) {
    call.check();transaction.executeWithoutResult(status->{
      var rows=sql.query(call,"SELECT storage_used FROM tenants WHERE id=? AND deleted_at IS NULL"+(postgres?" FOR UPDATE":""),payload.tenantId());
      if(rows.isEmpty())throw new IllegalStateException("tenant not found");
      long value=Math.max(0,number(rows.getFirst().get("storage_used"))+delta);
      sql.update(call,"UPDATE tenants SET storage_used=?,updated_at=? WHERE id=? AND deleted_at IS NULL",value,time(),payload.tenantId());
    });
  }
  public void documentsDeleted(KnowledgeBaseDeleteProcessor.Payload payload,List<String> ids,CallContext call) {
    call.check();if(ids.isEmpty())return;var args=new ArrayList<Object>();args.add(time());args.add(payload.tenantId());args.addAll(ids);
    sql.update(call,"UPDATE knowledges SET deleted_at=? WHERE tenant_id=? AND id IN ("+marks(ids.size())+") AND deleted_at IS NULL",args.toArray());
  }
  public void wiki(KnowledgeBaseDeleteProcessor.Payload payload,String table,CallContext call) {
    call.check();if(payload.knowledgeBaseId().isEmpty())return;
    if(!Set.of("wiki_pages","wiki_folders","wiki_page_revisions","wiki_page_issues").contains(table))throw new IllegalArgumentException("invalid Wiki cleanup table");
    if(table.equals("wiki_page_revisions"))sql.update(call,"DELETE FROM wiki_page_revisions WHERE tenant_id=? AND knowledge_base_id=?",payload.tenantId(),payload.knowledgeBaseId());
    else sql.update(call,"UPDATE "+table+" SET deleted_at=? WHERE tenant_id=? AND knowledge_base_id=? AND deleted_at IS NULL",time(),payload.tenantId(),payload.knowledgeBaseId());
  }
  private Object time(){return postgres?Timestamp.from(clock.instant()):GoSqliteTimestamp.write(clock.instant());}
  private static String marks(int n){return String.join(",",Collections.nCopies(n,"?"));}
  private static String text(Map<String,Object> row,String key){return Objects.toString(row.get(key),"");}
  private static long number(Object value){return value instanceof Number n?n.longValue():0;}
}
