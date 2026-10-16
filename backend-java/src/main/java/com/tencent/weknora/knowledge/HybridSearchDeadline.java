package com.tencent.weknora.knowledge;

import com.tencent.weknora.model.client.CallContext;
import java.lang.reflect.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.sqlite.*;

/** Go's 12-second resolution and per-store retrieval budgets, with actual JDBC cancellation. */
public final class HybridSearchDeadline implements AutoCloseable {
  private static final ScheduledExecutorService TIMERS=timers();
  private static ScheduledExecutorService timers(){var executor=new ScheduledThreadPoolExecutor(4,Thread.ofPlatform().daemon(true).name("hybrid-sql-deadline-",0).factory());executor.setRemoveOnCancelPolicy(true);return executor;}
  private record Budget(CallContext call,Instant end){}
  private static final ThreadLocal<Budget> CURRENT=new ThreadLocal<>();private final Budget previous;
  private HybridSearchDeadline(CallContext call,Duration duration){previous=CURRENT.get();Instant end=duration==null?Instant.MAX:Instant.now().plus(duration);if(previous!=null&&previous.end.isBefore(end))end=previous.end;if(call.deadline()!=null&&call.deadline().isBefore(end))end=call.deadline();CURRENT.set(new Budget(call,end));}
  public static HybridSearchDeadline start(CallContext call,Duration duration){return new HybridSearchDeadline(call,duration);}
  public static Instant deadline(CallContext call){var budget=CURRENT.get();Instant end=budget==null?call.deadline():budget.end();return end==null||end.equals(Instant.MAX)?null:end;}
  @Override public void close(){if(previous==null)CURRENT.remove();else CURRENT.set(previous);}
  public static DataSource source(DataSource source){return new DelegatingDataSource(source){@Override public Connection getConnection()throws SQLException{return borrow(()->super.getConnection());}@Override public Connection getConnection(String name,String password)throws SQLException{return borrow(()->super.getConnection(name,password));}};}
  @FunctionalInterface private interface Borrow {Connection get()throws SQLException;}
  /** A pool's own timeout must not outlive the request/group deadline; close late deliveries. */
  private static Connection borrow(Borrow operation)throws SQLException{
    var budget=CURRENT.get();if(budget==null)return operation.get();budget.call.check();
    var pending=new PendingBorrow();var promise=new CompletableFuture<Connection>();
    var worker=Thread.ofVirtual().start(()->{try{var c=operation.get();synchronized(pending){if(pending.abandoned){c.close();promise.completeExceptionally(new SQLTimeoutException("hybrid pool acquisition canceled"));}else{pending.connection=c;promise.complete(c);}}}catch(Throwable failure){promise.completeExceptionally(failure);}});
    boolean accepted=false;
    try(var cancellation=budget.call.onCancel(worker::interrupt)){
      while(true){budget.call.check();if(!Instant.now().isBefore(budget.end))throw new SQLTimeoutException("hybrid pool acquisition deadline exceeded");
        try{var connection=promise.get(25,TimeUnit.MILLISECONDS);budget.call.check();if(!Instant.now().isBefore(budget.end))throw new SQLTimeoutException("hybrid pool acquisition deadline exceeded");var wrapped=wrap(connection);accepted=true;return wrapped;}
        catch(TimeoutException waiting){}catch(ExecutionException failed){if(failed.getCause() instanceof SQLException sql)throw sql;throw new SQLException("hybrid pool acquisition failed",failed.getCause());}
      }
    }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new SQLException("hybrid pool acquisition interrupted",interrupted);}catch(SQLException sql){throw sql;}catch(RuntimeException canceled){throw canceled;}catch(Exception failure){throw new SQLException(failure);}
    finally{if(!accepted){synchronized(pending){pending.abandoned=true;if(pending.connection!=null)pending.connection.close();}worker.interrupt();}}
  }
  private static final class PendingBorrow {boolean abandoned;Connection connection;}
  private static void cancel(Statement statement){try{statement.cancel();}catch(SQLException ignored){}}
  private static Connection wrap(Connection connection)throws SQLException{
    var budget=CURRENT.get();if(budget==null)return connection;budget.call.check();var callbacks=new ArrayList<AutoCloseable>();var sqlite=connection.isWrapperFor(SQLiteConnection.class)?connection.unwrap(SQLiteConnection.class):null;int busy=sqlite==null?0:sqlite.getBusyTimeout();
    if(sqlite!=null)ProgressHandler.setHandler(sqlite,1000,new ProgressHandler(){@Override protected int progress(){try{budget.call.check();return !Instant.now().isBefore(budget.end)?1:0;}catch(RuntimeException canceled){return 1;}}});
    return (Connection)Proxy.newProxyInstance(HybridSearchDeadline.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
      if(method.getName().equals("close")){for(var callback:callbacks)callback.close();if(sqlite!=null){ProgressHandler.clearHandler(sqlite);sqlite.setBusyTimeout(busy);}}
      try{Object value=method.invoke(connection,args);if(value instanceof Statement statement){long millis=budget.end.equals(Instant.MAX)?Long.MAX_VALUE:Duration.between(Instant.now(),budget.end).toMillis();if(millis<=0){statement.close();throw new SQLTimeoutException("hybrid retrieval deadline exceeded");}if(millis!=Long.MAX_VALUE)statement.setQueryTimeout((int)Math.min(Integer.MAX_VALUE,Math.max(1,Math.ceil(millis/1000d))));callbacks.add(budget.call.onCancel(()->cancel(statement)));if(!budget.end.equals(Instant.MAX)){var timer=TIMERS.schedule(()->cancel(statement),Math.max(0,Duration.between(Instant.now(),budget.end).toNanos()),TimeUnit.NANOSECONDS);callbacks.add(()->timer.cancel(false));}}return value;}catch(InvocationTargetException error){throw error.getCause();}
    });
  }
}
