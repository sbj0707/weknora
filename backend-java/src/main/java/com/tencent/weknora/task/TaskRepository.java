package com.tencent.weknora.task;

import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable Java queue with tenant-scoped reads and fenced leases; does not touch Redis/Asynq. */
@Repository
public class TaskRepository {
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final TransactionTemplate transaction;private final Clock clock;private Boolean postgres;
    @org.springframework.beans.factory.annotation.Autowired
    public TaskRepository(JdbcTemplate jdbc,ObjectMapper json,PlatformTransactionManager manager){this(jdbc,json,manager,Clock.systemUTC());}
    public TaskRepository(JdbcTemplate jdbc,ObjectMapper json,PlatformTransactionManager manager,Clock clock){this.jdbc=jdbc;this.json=json;this.clock=clock;transaction=new TransactionTemplate(manager);}
    /** Joins an existing business transaction, so enqueue and its database mutation commit together. */
    public TaskRecord enqueue(long tenant,String type,String key,JsonNode payload,int maxAttempts,Duration delay){
        return enqueue(tenant,type,key,payload,maxAttempts,delay,"default",Duration.ZERO);
    }
    /** timeout zero retains the handler deadline; a positive value is the producer's per-job deadline. */
    public TaskRecord enqueue(long tenant,String type,String key,JsonNode payload,int maxAttempts,Duration delay,String queue,Duration timeout){
        if(queue==null||queue.isBlank()||queue.length()>128||timeout==null||timeout.isNegative()||(!timeout.isZero()&&timeout.toMillis()==0)||delay==null)throw new IllegalArgumentException("Invalid task queue/deadline");
        if(tenant<=0||type==null||type.isBlank()||type.length()>128||payload==null||maxAttempts<1||maxAttempts>100||delay.isNegative())throw new IllegalArgumentException("Invalid task parameters");
        String idempotency=key==null||key.isBlank()?UUID.randomUUID().toString():key;if(idempotency.length()>256||payload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>1024*1024)throw new IllegalArgumentException("Task key/payload exceeds limit");
        return transaction.execute(status->{long now=clock.millis();String id=UUID.randomUUID().toString();
            jdbc.update("INSERT INTO java_task_jobs(id,tenant_id,type,idempotency_key,payload,status,max_attempts,available_at,created_at,updated_at,queue,timeout_millis) VALUES(?,?,?,?,?,'pending',?,?,?,?,?,?) ON CONFLICT(tenant_id,type,idempotency_key) DO NOTHING",id,tenant,type,idempotency,payload.toString(),maxAttempts,Math.addExact(now,delay.toMillis()),now,now,queue,timeout.toMillis());
            return map(jdbc.queryForMap("SELECT * FROM java_task_jobs WHERE tenant_id=? AND type=? AND idempotency_key=?",tenant,type,idempotency));});
    }
    public Optional<TaskRecord> get(long tenant,String id){var rows=jdbc.queryForList("SELECT * FROM java_task_jobs WHERE tenant_id=? AND id=?",tenant,id);return rows.isEmpty()?Optional.empty():Optional.of(map(rows.getFirst()));}
    public List<TaskRecord> list(long tenant,int limit){return jdbc.queryForList("SELECT * FROM java_task_jobs WHERE tenant_id=? ORDER BY created_at DESC,id DESC LIMIT ?",tenant,Math.max(1,Math.min(limit,200))).stream().map(this::map).toList();}
    /** One transaction locks and advances the attempt counter before exposing a lease. */
    public Optional<TaskRecord> claim(String worker,Duration lease){
        return claim(worker,lease,null);
    }
    /** Ordered queue membership is an explicit worker-pool boundary, including expired leases. */
    public Optional<TaskRecord> claim(String worker,Duration lease,List<String> queues){
        if(queues!=null&&(queues.isEmpty()||queues.size()>128||queues.stream().anyMatch(q->q==null||q.isBlank()||q.length()>128)))throw new IllegalArgumentException("Invalid worker queues");
        if(worker==null||worker.isBlank()||worker.length()>128||lease.toMillis()<100)throw new IllegalArgumentException("Invalid lease");
        return transaction.execute(status->{reserveSqliteWriter();long now=clock.millis();
            for(int skipped=0;skipped<32;skipped++){
                String filter="",order="";List<Object> parameters=new ArrayList<>(List.of(now,now));
                if(queues!=null){
                    filter=" AND queue IN ("+String.join(",",Collections.nCopies(queues.size(),"?"))+")";parameters.addAll(queues);
                    StringBuilder priority=new StringBuilder("CASE queue ");
                    for(int i=0;i<queues.size();i++){priority.append("WHEN ? THEN ").append(i).append(' ');parameters.add(queues.get(i));}
                    order=priority.append("END,").toString();
                }
                var rows=jdbc.queryForList("SELECT * FROM java_task_jobs WHERE ((status IN ('pending','retry') AND available_at<=?) OR (status='running' AND lease_until<=?))"+filter+" ORDER BY "+order+"available_at,created_at,id LIMIT 1"+(isPostgres()?" FOR UPDATE SKIP LOCKED":""),parameters.toArray());
                if(rows.isEmpty())return Optional.empty();TaskRecord current=map(rows.getFirst());
                if(current.status().equals("running"))finishAttempt(current,"lease_expired","Worker lease expired",now);
                if(current.cancelRequested()) {jdbc.update("UPDATE java_task_jobs SET status='canceled',lease_owner=NULL,lease_until=NULL,updated_at=? WHERE id=?",now,current.id());continue;}
                if(current.attempts()>=current.maxAttempts()){dead(current,"Retry budget exhausted after expired lease",now);continue;}
                int attempt=current.attempts()+1;long until=Math.addExact(now,lease.toMillis());
                int changed=jdbc.update("UPDATE java_task_jobs SET status='running',attempts=?,lease_owner=?,lease_until=?,updated_at=? WHERE id=? AND attempts=? AND status=?",attempt,worker,until,now,current.id(),current.attempts(),current.status());
                if(changed==0)continue;
                jdbc.update("INSERT INTO java_task_attempts(task_id,attempt,lease_owner,started_at) VALUES(?,?,?,?)",current.id(),attempt,worker,now);
                return Optional.of(map(jdbc.queryForMap("SELECT * FROM java_task_jobs WHERE id=?",current.id())));
            }return Optional.empty();});
    }
    public boolean renew(TaskRecord lease,Duration duration){if(duration.toMillis()<100)throw new IllegalArgumentException("Invalid lease");long now=clock.millis();return jdbc.update("UPDATE java_task_jobs SET lease_until=?,updated_at=? WHERE id=? AND tenant_id=? AND status='running' AND attempts=? AND lease_owner=? AND lease_until>? AND cancel_requested=?",Math.addExact(now,duration.toMillis()),now,lease.id(),lease.tenantId(),lease.attempts(),lease.leaseOwner(),now,false)==1;}
    public boolean canceledOrLeaseLost(TaskRecord lease){var job=get(lease.tenantId(),lease.id());return job.isEmpty()||job.get().cancelRequested()||!job.get().status().equals("running")||job.get().attempts()!=lease.attempts()||!Objects.equals(job.get().leaseOwner(),lease.leaseOwner())||job.get().leaseUntil()==null||job.get().leaseUntil()<=clock.millis();}
    public boolean complete(TaskRecord lease,JsonNode result){return settle(lease,"succeeded",result,null,null);}
    /** Final SQL effects and task completion share one fenced transaction. External writes require their own idempotency key. */
    public boolean commitResult(TaskRecord lease,Runnable sqlEffects,JsonNode result){return Boolean.TRUE.equals(transaction.execute(status->{
        reserveSqliteWriter();
        var rows=jdbc.queryForList("SELECT * FROM java_task_jobs WHERE id=? AND tenant_id=?"+(isPostgres()?" FOR UPDATE":""),lease.id(),lease.tenantId());
        if(rows.isEmpty())return false;TaskRecord current=map(rows.getFirst());
        if(current.cancelRequested()||!current.status().equals("running")||current.attempts()!=lease.attempts()||!Objects.equals(current.leaseOwner(),lease.leaseOwner())||current.leaseUntil()==null||current.leaseUntil()<=clock.millis())return false;
        sqlEffects.run();if(!complete(lease,result))throw new java.util.concurrent.CancellationException("Task lost lease during commit");return true;}));}
    public boolean fail(TaskRecord lease,String safeError,boolean retryable,Duration delay){if(delay.isNegative())throw new IllegalArgumentException("Invalid retry delay");return settle(lease,retryable&&lease.attempts()<lease.maxAttempts()?"retry":"dead",null,sanitize(safeError),delay);}
    public boolean acknowledgeCancellation(TaskRecord lease){return settle(lease,"canceled",null,null,null);}
    private boolean settle(TaskRecord lease,String target,JsonNode result,String error,Duration delay){return Boolean.TRUE.equals(transaction.execute(status->{reserveSqliteWriter();long now=clock.millis();
        var rows=jdbc.queryForList("SELECT * FROM java_task_jobs WHERE id=? AND tenant_id=?"+(isPostgres()?" FOR UPDATE":""),lease.id(),lease.tenantId());if(rows.isEmpty())return false;TaskRecord current=map(rows.getFirst());
        if(!current.status().equals("running")||current.attempts()!=lease.attempts()||!Objects.equals(current.leaseOwner(),lease.leaseOwner())||current.leaseUntil()==null||current.leaseUntil()<=now)return false;
        String state=current.cancelRequested()?"canceled":target;
        jdbc.update("UPDATE java_task_jobs SET status=?,result=?,last_error=?,available_at=?,lease_owner=NULL,lease_until=NULL,updated_at=? WHERE id=?",state,state.equals("succeeded")&&result!=null?result.toString():null,error,delay==null?now:Math.addExact(now,delay.toMillis()),now,lease.id());
        finishAttempt(lease,state,error,now);if(state.equals("dead"))deadLetter(current,error,now);return true;}));}
    public boolean cancel(long tenant,String id){return jdbc.update("UPDATE java_task_jobs SET cancel_requested=?,status=CASE WHEN status IN ('pending','retry') THEN 'canceled' ELSE status END,updated_at=? WHERE tenant_id=? AND id=? AND status IN ('pending','running','retry')",true,clock.millis(),tenant,id)==1;}
    /** Mirrors Go's narrow per-document cancellation set within the native SQL queue. */
    public int cancelKnowledge(long tenant,String knowledge){
        if(knowledge==null||knowledge.isEmpty())return 0;
        var types=List.of("document:process","manual:process","image:multimodal","knowledge:post_process","question:generation","summary:generation","chunk:extract");
        String after="";int changed=0;
        for(;;){
            List<Object> args=new ArrayList<>(List.of(tenant,after));args.addAll(types);
            var rows=jdbc.queryForList("SELECT id,payload FROM java_task_jobs WHERE tenant_id=? AND id>? AND status IN ('pending','running','retry') AND type IN ("+String.join(",",Collections.nCopies(types.size(),"?"))+") ORDER BY id LIMIT 500",args.toArray());
            if(rows.isEmpty())return changed;
            for(var row:rows){after=row.get("id").toString();try{var payload=json.readTree(row.get("payload").toString());if(payload.isObject()&&payload.path("knowledge_id").isTextual()&&payload.path("knowledge_id").asText().equals(knowledge)&&cancel(tenant,after))changed++;}catch(java.io.IOException ignored){/* Go also ignores invalid payload JSON. */}}
        }
    }
    /** Explicit operator retry keeps the fencing counter monotonic and grants a bounded new budget. */
    public boolean retryDead(long tenant,String id,int extraAttempts){if(extraAttempts<1||extraAttempts>100)throw new IllegalArgumentException("Invalid retry budget");return Boolean.TRUE.equals(transaction.execute(status->{int changed=jdbc.update("UPDATE java_task_jobs SET status='pending',max_attempts=attempts+?,cancel_requested=?,available_at=?,updated_at=? WHERE tenant_id=? AND id=? AND status='dead'",extraAttempts,false,clock.millis(),clock.millis(),tenant,id);if(changed==1)jdbc.update("DELETE FROM java_task_dead_letters WHERE task_id=? AND tenant_id=?",id,tenant);return changed==1;}));}
    private void dead(TaskRecord job,String error,long now){jdbc.update("UPDATE java_task_jobs SET status='dead',last_error=?,lease_owner=NULL,lease_until=NULL,updated_at=? WHERE id=?",error,now,job.id());deadLetter(job,error,now);}
    private void deadLetter(TaskRecord job,String error,long now){jdbc.update("INSERT INTO java_task_dead_letters(task_id,tenant_id,type,payload,error,failed_at) VALUES(?,?,?,?,?,?) ON CONFLICT(task_id) DO UPDATE SET error=excluded.error,failed_at=excluded.failed_at",job.id(),job.tenantId(),job.type(),job.payload().toString(),error==null?"Task failed":error,now);}
    private void finishAttempt(TaskRecord job,String outcome,String error,long now){jdbc.update("UPDATE java_task_attempts SET finished_at=?,outcome=?,error=? WHERE task_id=? AND attempt=? AND finished_at IS NULL",now,outcome,error,job.id(),job.attempts());}
    private boolean isPostgres(){if(postgres==null)postgres=jdbc.execute((ConnectionCallback<Boolean>)connection->connection.getMetaData().getDatabaseProductName().equalsIgnoreCase("PostgreSQL"));return Boolean.TRUE.equals(postgres);}
    /** Acquire SQLite's writer reservation before a read snapshot: concurrent deferred
     * read-to-write upgrades otherwise fail immediately with SQLITE_BUSY, even with a
     * busy timeout. This changes no rows; the existing transaction owns the lock until
     * commit. PostgreSQL keeps its row-level locks and concurrent SKIP LOCKED claims. */
    private void reserveSqliteWriter(){if(!isPostgres())jdbc.update("UPDATE java_task_jobs SET updated_at=updated_at WHERE 1=0");}
    // Raw handlers decode original persisted bytes, including Go's task-specific malformed JSON
    // behavior (manual ACKs; image/reparse/KB deletion retry). Invalid bodies remain claimable.
    // Keep every other task's existing corrupt-persistence failure unchanged.
    private JsonNode taskPayload(Map<String,Object> row) throws java.io.IOException {
        boolean rawHandler=java.util.Set.of("manual:process","image:multimodal","knowledge:list_reparse","kb:delete").contains(row.get("type"));
        try { var parsed=json.readTree(row.get("payload").toString());return parsed==null&&rawHandler?com.fasterxml.jackson.databind.node.NullNode.getInstance():parsed; }
        catch(java.io.IOException error) {
            if(rawHandler) return com.fasterxml.jackson.databind.node.NullNode.getInstance();
            throw error;
        }
    }
    private TaskRecord map(Map<String,Object> row){try{return new TaskRecord(row.get("id").toString(),((Number)row.get("tenant_id")).longValue(),row.get("type").toString(),row.get("idempotency_key").toString(),taskPayload(row),row.get("status").toString(),((Number)row.get("attempts")).intValue(),((Number)row.get("max_attempts")).intValue(),((Number)row.get("available_at")).longValue(),Objects.toString(row.get("lease_owner"),null),row.get("lease_until")==null?null:((Number)row.get("lease_until")).longValue(),Boolean.TRUE.equals(row.get("cancel_requested"))||Objects.toString(row.get("cancel_requested"),"").equals("1"),row.get("result")==null?null:json.readTree(row.get("result").toString()),Objects.toString(row.get("last_error"),null),((Number)row.get("created_at")).longValue(),((Number)row.get("updated_at")).longValue(),row.get("queue").toString(),((Number)row.get("timeout_millis")).longValue());}catch(java.io.IOException ex){throw new IllegalStateException("Invalid persisted Java task JSON",ex);}}
    private static String sanitize(String error){String safe=error==null?"Task failed":error.replaceAll("[\\r\\n\\t]"," ");return safe.substring(0,Math.min(safe.length(),4096));}
}
