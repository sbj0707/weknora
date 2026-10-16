package com.tencent.weknora.knowledge;

import com.tencent.weknora.task.TaskRecord;
import java.sql.Connection;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Owner/KB-scoped cleanup includes soft-deleted chunks and KBs. No schema mutation. */
public final class IndexDeleteRepository {
  @FunctionalInterface public interface Deletion {
    void delete(Connection transaction,List<String> ids,int dimension,String kbType) throws Exception;
  }
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final boolean postgres;
  public IndexDeleteRepository(JdbcTemplate jdbc,PlatformTransactionManager manager,Clock clock){
    this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.clock=clock;
    postgres=Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>)c->c.getMetaData().getDatabaseProductName().equals("PostgreSQL")));
  }
  public int deleteBatch(TaskRecord job,String kb,List<String> ids,int dimension,String kbType,List<Deletion> stores){
    return Objects.requireNonNull(tx.execute(status->{
      fence(job);
      if(stores.isEmpty())return 0;
      var bases=jdbc.queryForList("SELECT tenant_id FROM knowledge_bases WHERE id=?"+(postgres?" FOR UPDATE":""),kb);
      if(bases.isEmpty()||number(bases.getFirst().get("tenant_id"))!=job.tenantId())return 0;
      if(ids.isEmpty())return 0;
      List<Object> args=new ArrayList<>(List.of(job.tenantId(),kb));args.addAll(ids);
      var allowed=new HashSet<>(jdbc.queryForList("SELECT id FROM chunks WHERE tenant_id=? AND knowledge_base_id=? AND id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")"+(postgres?" FOR UPDATE":""),String.class,args.toArray()));
      var scoped=ids.stream().filter(allowed::contains).toList();
      if(scoped.isEmpty())return 0;
      jdbc.execute((ConnectionCallback<Void>)c->{
        try{for(var store:stores)store.delete(c,scoped,dimension,kbType);}
        catch(CancellationException e){throw e;}
        catch(Exception e){throw new CleanupFailure(e);}
        return null;
      });
      fence(job);
      return scoped.size();
    }));
  }
  private void fence(TaskRecord job){
    var rows=jdbc.queryForList("SELECT status,attempts,lease_owner,lease_until,cancel_requested FROM java_task_jobs WHERE tenant_id=? AND id=?"+(postgres?" FOR UPDATE":""),job.tenantId(),job.id());
    if(rows.isEmpty())throw new CancellationException("index deletion lease unavailable");
    var row=rows.getFirst();
    if(!Objects.equals(row.get("status"),"running")||number(row.get("attempts"))!=job.attempts()||!Objects.equals(row.get("lease_owner"),job.leaseOwner())||number(row.get("lease_until"))<=clock.millis()||Boolean.TRUE.equals(row.get("cancel_requested"))||number(row.get("cancel_requested"))!=0)throw new CancellationException("index deletion lease lost or canceled");
  }
  private static long number(Object value){return value instanceof Number n?n.longValue():0;}
  public static final class CleanupFailure extends RuntimeException {CleanupFailure(Exception cause){super("Index cleanup failed",cause);}}
}
