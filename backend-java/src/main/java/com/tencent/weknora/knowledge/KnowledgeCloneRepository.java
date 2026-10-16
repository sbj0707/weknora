package com.tencent.weknora.knowledge;

import static com.tencent.weknora.knowledge.KnowledgeTransferRepository.*;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Clone commits match the Go repositories: each chunk page commits independently of document CAS. */
class KnowledgeCloneRepository {
  final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final boolean pg;

  KnowledgeCloneRepository(JdbcTemplate jdbc,PlatformTransactionManager manager,Clock clock) {
    this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.clock=clock;
    pg=Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>)c->c.getMetaData().getDatabaseProductName().equals("PostgreSQL")));
  }

  String tag(long sourceTenant,long targetTenant,String targetKB,String sourceTag) {
    try {
      var sources=jdbc.queryForList("SELECT * FROM knowledge_tags WHERE tenant_id=? AND id=?",sourceTenant,sourceTag);
      if(sources.isEmpty())return "";
      var source=sources.getFirst();String name=text(source,"name");
      try {
        var existing=jdbc.queryForList("SELECT id FROM knowledge_tags WHERE tenant_id=? AND knowledge_base_id=? AND name=? ORDER BY id LIMIT 1",targetTenant,targetKB,name);
        if(!existing.isEmpty())return text(existing.getFirst(),"id");
      }catch(org.springframework.dao.DataAccessException ignored){/* Go still attempts creation after lookup failure. */}
      String id=UUID.randomUUID().toString();
      tx.executeWithoutResult(status->{
        var args=new ArrayList<Object>(List.of(id,targetTenant,targetKB,name,text(source,"color"),name.equals("未分类")?-1:number(source,"sort_order"),time(clock.instant()),time(clock.instant())));
        if(!pg)args.add(jdbc.queryForObject("SELECT COALESCE(MAX(seq_id),0)+1 FROM knowledge_tags",Long.class));
        jdbc.update("INSERT INTO knowledge_tags(id,tenant_id,knowledge_base_id,name,color,sort_order,created_at,updated_at"+(pg?"":",seq_id")+") VALUES(?,?,?,?,?,?,?,?"+(pg?"":",?")+")",args.toArray());
      });
      return id;
    }catch(org.springframework.dao.DataAccessException e){
      org.slf4j.LoggerFactory.getLogger(getClass()).warn("Clone tag mapping failed for {}",sourceTag,e);return "";
    }
  }

  void createChunks(List<Map<String,Object>> rows) {
    if(rows.isEmpty())return;
    sql(()->tx.executeWithoutResult(status->{
      var keys=new ArrayList<>(List.of("id","tenant_id","knowledge_id","knowledge_base_id","tag_id","content","source_content","content_revision","index_status","last_editor_id","chunk_index","is_enabled","flags","status","start_at","end_at","pre_chunk_id","next_chunk_id","chunk_type","parent_chunk_id","relation_chunks","indirect_relation_chunks","metadata","content_hash","image_info","created_at","updated_at","deleted_at","context_header","source_locators"));
      long seq=0;
      if(!pg){seq=Objects.requireNonNull(jdbc.queryForObject("SELECT COALESCE(MAX(seq_id),0) FROM chunks",Long.class));keys.add("seq_id");}
      var tuples=new ArrayList<String>();var args=new ArrayList<Object>();
      for(var row:rows) {
        var values=new LinkedHashMap<>(row);
        // GORM CreateInBatches fills tagged zero defaults even with Select("*").
        // Actual Go CloneChunk therefore persists false->true and flags 0->1.
        values.put("is_enabled",true);
        if(number(values,"flags")==0)values.put("flags",1);
        if(!pg)values.put("seq_id",++seq);
        var params=new ArrayList<String>();
        for(String key:keys) {
          boolean json=Set.of("relation_chunks","indirect_relation_chunks","metadata","source_locators").contains(key);
          params.add(pg&&json?"?::jsonb":"?");
          Object value=values.get(key);
          if(json&&value instanceof byte[] bytes)value=new String(bytes,StandardCharsets.UTF_8);
          // types.JSON.Value returns []byte; SourceLocators.Value returns string.
          if(json&&!key.equals("source_locators")&&value instanceof String raw) {
            value=raw.isEmpty()?null:(pg?raw.trim():raw.trim().getBytes(StandardCharsets.UTF_8));
          }
          args.add(value);
        }
        tuples.add("("+String.join(",",params)+")");
      }
      jdbc.update("INSERT INTO chunks("+String.join(",",keys)+") VALUES"+String.join(",",tuples),args.toArray());
    }));
  }

  void deleteChunks(long tenant,String id) {
    sql(()->jdbc.update("UPDATE chunks SET deleted_at=? WHERE tenant_id=? AND knowledge_id=? AND deleted_at IS NULL",time(clock.instant()),tenant,id));
  }
  static final class MissingDocument extends IllegalArgumentException {
    MissingDocument(){super("knowledge not found");}
  }

  Map<String,Object> document(long tenant,String id) {
    try {
      var rows=jdbc.queryForList("SELECT * FROM knowledges WHERE tenant_id=? AND id=? AND deleted_at IS NULL",tenant,id);
      if(rows.isEmpty())throw new MissingDocument();
      return new LinkedHashMap<>(rows.getFirst());
    }catch(org.springframework.dao.DataAccessException e){throw new IllegalStateException(ChunkEditService.diagnostic(e),e);}
  }

  void createDocument(Map<String,Object> row) {
    if(text(row,"channel").isEmpty())row.put("channel","web");
    if(text(row,"summary_status").isEmpty())row.put("summary_status","none");
    if(raw(row.get("custom_metadata")).isEmpty())row.put("custom_metadata","{}");
    Object now=now();row.put("created_at",now);row.put("updated_at",now);
    var keys=List.of("id","tenant_id","knowledge_base_id","type","title","description","source","channel","parse_status","pending_subtasks_count","summary_status","profile","enable_status","embedding_model_id","file_name","folder_path","file_type","file_size","file_hash","file_path","storage_size","metadata","custom_metadata","last_faq_import_result","created_at","updated_at","processed_at","error_message","deleted_at");
    var params=new ArrayList<String>();var args=new ArrayList<Object>();
    for(String key:keys) {
      boolean json=Set.of("profile","metadata","custom_metadata","last_faq_import_result").contains(key);
      params.add(pg&&json?"?::jsonb":"?");
      Object value=row.get(key);
      if(json&&value!=null) {
        String raw=raw(value).trim();
        value=raw.isEmpty()?null:(pg?raw:raw.getBytes(StandardCharsets.UTF_8));
      }
      args.add(value);
    }
    sql(()->jdbc.update("INSERT INTO knowledges("+String.join(",",keys)+") VALUES("+String.join(",",params)+")",args.toArray()));
  }

  static String raw(Object value){return value instanceof byte[] bytes?new String(bytes,StandardCharsets.UTF_8):Objects.toString(value,"");}
  Object now(){return time(clock.instant());}
  private Object time(Instant now){return pg?java.sql.Timestamp.from(now):GoSqliteTimestamp.write(now);}
  private void sql(Runnable action){try{action.run();}catch(org.springframework.dao.DataAccessException e){throw new IllegalStateException(ChunkEditService.diagnostic(e),e);}}
}
