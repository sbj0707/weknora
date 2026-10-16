package com.tencent.weknora.knowledge;

import static com.tencent.weknora.knowledge.KnowledgeTransferRepository.*;

import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Synchronous cleanup of already-authorized clone references; never admits request-body IDs. */
final class KnowledgeReferencedDelete implements KnowledgeCloneExecution.Removals {
  interface Effects {
    /** Resolve the current KB/file routing and owner context before the first mutation. */
    JsonNode prepare(long owner,String kb)throws Exception;
    void indexes(long owner,JsonNode base,List<Map<String,Object>> documents)throws Exception;
    void wiki(long owner,JsonNode base,Map<String,Object> document)throws Exception;
    void graph(long owner,String kb,List<String> ids)throws Exception;
    void file(long owner,JsonNode base,String path)throws Exception;
    boolean release(long owner,String path,List<String> documents)throws Exception;
    void dequeue(long owner,String id)throws Exception;
    void profile(long owner,JsonNode base)throws Exception;
    void audit(long owner,JsonNode base,List<Map<String,Object>> documents)throws Exception;
  }
  private final JdbcTemplate jdbc;
  private final KnowledgeTransferRepository transfers;
  private final ObjectMapper json;
  private final Effects effects;
  private final TransactionTemplate transactions;
  private final Clock clock;
  private final boolean postgres;
  KnowledgeReferencedDelete(JdbcTemplate jdbc,KnowledgeTransferRepository transfers,ObjectMapper json,
      Effects effects,PlatformTransactionManager manager,Clock clock) {
    this.jdbc=jdbc;this.transfers=transfers;this.json=json;this.effects=effects;this.clock=clock;
    transactions=new TransactionTemplate(manager);
    postgres=Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>)c->c.getMetaData().getDatabaseProductName().equals("PostgreSQL")));
  }
  @Override public void delete(Scope scope,JsonNode target,List<String> requested)throws Exception {
    if(target==null||target.path("tenant_id").asLong()!=scope.tenant()||!target.path("id").asText().equals(scope.target()))throw new IllegalArgumentException("kb_access: forbidden");
    var ids=new LinkedHashSet<String>();
    for(String id:requested) {
      if(id==null||KnowledgeUploadForms.goTrim(id).isEmpty())throw new IllegalArgumentException("error code: 1000, error message: resource ID cannot be empty");
      ids.add(id);
    }
    if(ids.isEmpty())return;
    var first=documents(scope.tenant(),ids);
    var bindings=new LinkedHashMap<String,String>();
    for(var row:first) {
      if(!text(row,"knowledge_base_id").equals(scope.target()))throw forbidden("cleanup resource binding changed");
      bindings.put(text(row,"id"),text(row,"knowledge_base_id"));
    }
    if(bindings.isEmpty())return;
    var current=new HashMap<String,Map<String,Object>>();
    for(var row:documents(scope.tenant(),bindings.keySet()))current.put(text(row,"id"),row);
    var rows=new ArrayList<Map<String,Object>>();
    for(var entry:bindings.entrySet()) {
      var row=current.get(entry.getKey());if(row==null)continue;
      if(!text(row,"knowledge_base_id").equals(entry.getValue()))throw forbidden("cleanup resource binding changed");
      if(new KnowledgeTransferState(json).moving(row.get("metadata")))throw new IllegalStateException("error code: 1005, error message: knowledge has an unfinished move; retry the move first");
      rows.add(row);
    }
    if(rows.isEmpty())return;
    JsonNode base=effects.prepare(scope.tenant(),scope.target());
    if(base==null||!base.path("id").asText().equals(scope.target())||base.path("tenant_id").asLong()!=scope.tenant())throw forbidden("knowledge does not belong to its knowledge base");
    var liveIds=rows.stream().map(r->text(r,"id")).toList();
    var images=new LinkedHashSet<String>();var imageArgs=arguments(scope.tenant(),liveIds);
    for(var raw:query("SELECT image_info FROM chunks WHERE tenant_id=? AND knowledge_id IN ("+marks(liveIds.size())+") AND image_info!='' AND deleted_at IS NULL",imageArgs)) {
      try{for(var image:KnowledgeImageRequest.images(text(raw,"image_info"),json))if(!image.path("url").asText().isEmpty())images.add(image.path("url").asText());}
      catch(Exception ignored){/* Go logs malformed image metadata and continues. */}
    }
    var inFlight=new ArrayList<String>();
    for(var row:rows) {
      if(Set.of("pending","processing").contains(text(row,"parse_status")))inFlight.add(text(row,"id"));
      var after=new LinkedHashMap<>(row);after.put("parse_status","deleting");
      transfers.checkpoint(row,after);row.clear();row.putAll(after);
    }
    for(String id:inFlight)bestEffort(()->effects.dequeue(scope.tenant(),id));
    // Go errgroup waits for every cleanup branch, even if another branch fails.
    Exception failure=null;
    try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
      var completions=new ExecutorCompletionService<Exception>(executor);
      completions.submit(()->attempt(()->effects.indexes(scope.tenant(),base,rows)));
      if(base.path("indexing_strategy").path("wiki_enabled").asBoolean())for(var row:rows)bestEffort(()->effects.wiki(scope.tenant(),base,row));
      completions.submit(()->attempt(()->{
        var args=new ArrayList<Object>();args.add(time());args.addAll(arguments(scope.tenant(),liveIds));
        update("UPDATE chunks SET deleted_at=? WHERE tenant_id=? AND knowledge_id IN ("+marks(liveIds.size())+") AND deleted_at IS NULL",args);
      }));
      completions.submit(()->attempt(()->effects.graph(scope.tenant(),scope.target(),liveIds)));
      for(int i=0;i<3;i++){var error=completions.take().get();if(failure==null&&error!=null)failure=error;}
    }
    if(failure!=null)throw failure;
    for(String id:liveIds)bestEffort(()->update("DELETE FROM knowledge_tag_relations WHERE knowledge_id=?",List.of(id)));
    var deletionArgs=new ArrayList<Object>();deletionArgs.add(time());deletionArgs.addAll(arguments(scope.tenant(),liveIds));
    update("UPDATE knowledges SET deleted_at=? WHERE tenant_id=? AND id IN ("+marks(liveIds.size())+") AND deleted_at IS NULL",deletionArgs);
    long delta=0;
    for(var row:rows) {
      String path=text(row,"file_path");if(!path.isEmpty())bestEffort(()->effects.file(scope.tenant(),base,path));
      delta-=number(row,"storage_size");
    }
    for(String image:images)bestEffort(()->{if(effects.release(scope.tenant(),image,liveIds))effects.file(scope.tenant(),base,image);});
    long adjustment=delta;
    bestEffort(()->transactions.executeWithoutResult(status->{
      var tenants=query("SELECT storage_used FROM tenants WHERE id=? AND deleted_at IS NULL"+(postgres?" FOR UPDATE":""),List.of(scope.tenant()));
      if(tenants.isEmpty())throw new IllegalStateException("record not found");
      long value=Math.max(0,number(tenants.getFirst(),"storage_used")+adjustment);
      update("UPDATE tenants SET storage_used=?,updated_at=? WHERE id=? AND deleted_at IS NULL",List.of(value,time(),scope.tenant()));
    }));
    bestEffort(()->effects.profile(scope.tenant(),base));
    bestEffort(()->effects.audit(scope.tenant(),base,rows));
  }
  private List<Map<String,Object>> documents(long owner,Collection<String> ids) {
    return query("SELECT * FROM knowledges WHERE tenant_id=? AND id IN ("+marks(ids.size())+") AND deleted_at IS NULL",arguments(owner,ids));
  }
  private List<Map<String,Object>> query(String sql,List<?> args) {
    try{return jdbc.queryForList(sql,args.toArray());}
    catch(org.springframework.dao.DataAccessException e){throw new IllegalStateException(ChunkEditService.diagnostic(e),e);}
  }
  private void update(String sql,List<?> args) {
    try{jdbc.update(sql,args.toArray());}
    catch(org.springframework.dao.DataAccessException e){throw new IllegalStateException(ChunkEditService.diagnostic(e),e);}
  }
  private Object time(){return postgres?java.sql.Timestamp.from(clock.instant()):GoSqliteTimestamp.write(clock.instant());}
  private static ArrayList<Object> arguments(long owner,Collection<String> ids){var args=new ArrayList<Object>();args.add(owner);args.addAll(ids);return args;}
  private static String marks(int n){return String.join(",",Collections.nCopies(n,"?"));}
  private static IllegalArgumentException forbidden(String message){return new IllegalArgumentException("error code: 1002, error message: "+message);}
  @FunctionalInterface private interface Work {void run()throws Exception;}
  private static Exception attempt(Work work){try{work.run();return null;}catch(Exception e){return e;}}
  private static void bestEffort(Work work){var error=attempt(work);if(error!=null)org.slf4j.LoggerFactory.getLogger(KnowledgeReferencedDelete.class).warn("Referenced document cleanup best-effort step failed: {}",error.getMessage());}
}
