package com.tencent.weknora.knowledge;

import static com.tencent.weknora.knowledge.KnowledgeTransferRepository.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Go FAQ diff queries and separately committed repository mutations; never a whole-clone transaction. */
class KnowledgeFAQCloneRepository extends KnowledgeCloneRepository {
  record Pair(String source,String target) {}
  record Diff(List<String> add,List<String> remove,List<Pair> matched) {}
  private final TransactionTemplate transaction;
  private final KnowledgeFAQMetadata metadata;
  private final boolean postgres;
  KnowledgeFAQCloneRepository(JdbcTemplate jdbc,PlatformTransactionManager manager,Clock clock,ObjectMapper json) {
    super(jdbc,manager,clock);transaction=new TransactionTemplate(manager);metadata=new KnowledgeFAQMetadata(json);
    postgres=Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>)c->c.getMetaData().getDatabaseProductName().equals("PostgreSQL")));
  }
  List<Map<String,Object>> documents(long owner,String kb) {
    return query("SELECT * FROM knowledges WHERE tenant_id=? AND knowledge_base_id=? AND deleted_at IS NULL ORDER BY created_at DESC",List.of(owner,kb));
  }
  Diff diff(long owner,String source,String target) {
    List<Map<String,Object>> src,dst;
    try{src=hashes(owner,source);}catch(RuntimeException e){throw wrap("failed to list source FAQ chunks: ",e);}
    try{dst=hashes(owner,target);}catch(RuntimeException e){throw wrap("failed to list destination FAQ chunks: ",e);}
    var targets=new LinkedHashMap<String,List<String>>();
    for(var row:dst)targets.computeIfAbsent(text(row,"content_hash"),unused->new ArrayList<>()).add(text(row,"id"));
    var sourceHashes=new HashSet<String>();for(var row:src)sourceHashes.add(text(row,"content_hash"));
    var add=new ArrayList<String>();var remove=new ArrayList<String>();var matched=new ArrayList<Pair>();
    for(var row:src) {
      var ids=targets.get(text(row,"content_hash"));
      if(ids==null)add.add(text(row,"id"));else matched.add(new Pair(text(row,"id"),ids.getFirst()));
    }
    for(var row:dst)if(!sourceHashes.contains(text(row,"content_hash")))remove.add(text(row,"id"));
    for(var e:targets.entrySet())if(!e.getKey().isEmpty()&&sourceHashes.contains(e.getKey())&&e.getValue().size()>1)
      remove.addAll(e.getValue().subList(1,e.getValue().size()));
    return new Diff(add,remove,matched);
  }
  private List<Map<String,Object>> hashes(long owner,String kb) {
    var out=new ArrayList<Map<String,Object>>();String last="";
    while(true) {
      var args=new ArrayList<Object>(List.of(owner,kb));if(!last.isEmpty())args.add(last);
      var rows=query("SELECT id,content_hash FROM chunks WHERE tenant_id=? AND knowledge_base_id=? AND chunk_type='faq' AND deleted_at IS NULL"+
          (last.isEmpty()?"":" AND id>?")+" ORDER BY id ASC LIMIT 5000",args);
      out.addAll(rows);if(rows.size()<5000)return out;last=text(rows.getLast(),"id");
    }
  }
  Map<String,Map<String,Object>> statuses(long owner,List<String> ids) {
    var out=new HashMap<String,Map<String,Object>>();
    for(int at=0;at<ids.size();at+=5000) {
      var batch=ids.subList(at,Math.min(ids.size(),at+5000));var args=new ArrayList<Object>();args.add(owner);args.addAll(batch);
      for(var row:query("SELECT id,tag_id,is_enabled,flags,metadata FROM chunks WHERE tenant_id=? AND id IN ("+marks(batch.size())+") AND deleted_at IS NULL",args)) {
        row.put("answer_strategy",metadata.strategy(row.get("metadata")));out.put(text(row,"id"),row);
      }
    }
    return out;
  }
  void remove(long owner,List<String> ids) {
    for(int at=0;at<ids.size();at+=5000) {
      var batch=ids.subList(at,Math.min(ids.size(),at+5000));var args=new ArrayList<Object>();args.add(now());args.add(owner);args.addAll(batch);
      update("UPDATE chunks SET deleted_at=? WHERE tenant_id=? AND id IN ("+marks(batch.size())+") AND deleted_at IS NULL",args);
    }
  }
  @Override void createChunks(List<Map<String,Object>> rows) {
    // GORM mutates the supplied objects with these defaults before the subsequent index call.
    for(var row:rows){row.put("is_enabled",true);if(number(row,"flags")==0)row.put("flags",1);}
    super.createChunks(rows);
  }
  void updateRows(List<Map<String,Object>> rows) {
    transaction.executeWithoutResult(status->{
      // Go uses id-only, unscoped updates and one transaction for the complete call.
      for(var row:rows)update("UPDATE chunks SET content=?,is_enabled=?,tag_id=?,flags=?,status=?,updated_at=? WHERE id=?",
          List.of(text(row,"content"),KnowledgeFAQMetadata.enabled(row),text(row,"tag_id"),number(row,"flags"),number(row,"status"),now(),text(row,"id")));
    });
  }
  void patchMetadata(long owner,String id,String value) {
    var rows=query("SELECT * FROM chunks WHERE tenant_id=? AND id=? AND deleted_at IS NULL ORDER BY id LIMIT 1",List.of(owner,id));
    if(rows.isEmpty())throw new IllegalStateException("record not found");
    var row=rows.getFirst();row.put("metadata",value);row.put("updated_at",now());
    var columns=new ArrayList<>(row.keySet());columns.removeAll(List.of("id","seq_id"));
    var args=new ArrayList<Object>();var sets=new ArrayList<String>();
    for(String key:columns){sets.add(key+"="+parameter(key));args.add(value(key,row.get(key)));}args.add(id);
    update("UPDATE chunks SET "+String.join(",",sets)+" WHERE id=? AND deleted_at IS NULL",args);
  }
  void indexedDocument(Map<String,Object> row) {
    row.put("updated_at",now());row.put("processed_at",now());
    var columns=new ArrayList<>(row.keySet());columns.removeAll(List.of("id","deleted_at","pending_subtasks_count"));
    if(row.get("custom_metadata")==null)columns.remove("custom_metadata");
    var args=new ArrayList<Object>();var sets=new ArrayList<String>();
    for(String key:columns){sets.add(key+"="+parameter(key));args.add(value(key,row.get(key)));}args.add(text(row,"id"));
    update("UPDATE knowledges SET "+String.join(",",sets)+" WHERE id=? AND deleted_at IS NULL",args);
  }
  private static final Set<String> JSON=Set.of("metadata","custom_metadata","profile","last_faq_import_result","relation_chunks","indirect_relation_chunks","source_locators");
  private String parameter(String key){return postgres&&JSON.contains(key)?"?::jsonb":"?";}
  private Object value(String key,Object value) {
    if(value==null||!JSON.contains(key))return value;
    String raw=KnowledgeCloneRepository.raw(value).trim();
    return raw.isEmpty()?null:(postgres||key.equals("source_locators")?raw:raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }
  private List<Map<String,Object>> query(String sql,List<?> args) {
    try{return KnowledgeTransferRows.query(jdbc,sql,args.toArray());}catch(org.springframework.dao.DataAccessException e){throw wrap("",new IllegalStateException(ChunkEditService.diagnostic(e),e));}
  }
  private void update(String sql,List<?> args) {
    try{jdbc.update(sql,args.toArray());}catch(org.springframework.dao.DataAccessException e){throw wrap("",new IllegalStateException(ChunkEditService.diagnostic(e),e));}
  }
  private static String marks(int n){return String.join(",",Collections.nCopies(n,"?"));}
  private static RuntimeException wrap(String prefix,Exception e){return new IllegalStateException(prefix+Objects.toString(e.getMessage(),""),e);}
}
