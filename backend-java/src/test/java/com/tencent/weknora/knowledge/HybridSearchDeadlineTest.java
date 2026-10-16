package com.tencent.weknora.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import com.tencent.weknora.model.client.CallContext;
import com.zaxxer.hikari.*;
import java.sql.*;
import java.time.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

class HybridSearchDeadlineTest {
  private static final String EXPENSIVE="WITH RECURSIVE numbers(n) AS (VALUES(1) UNION ALL SELECT n+1 FROM numbers WHERE n<1000000000) SELECT SUM(n) FROM numbers";
  @Test void actualSqliteProgressDeadlineStopsQueryAndReturnsUsablePoolConnection()throws Exception{
    try(var pool=pool();var call=new CallContext()){
      try(var budget=HybridSearchDeadline.start(call,Duration.ofMillis(40));var c=HybridSearchDeadline.source(pool).getConnection();var statement=c.createStatement()){assertThrows(SQLException.class,()->statement.executeQuery(EXPENSIVE));}
      try(var c=pool.getConnection();var s=c.createStatement();var result=s.executeQuery("SELECT 42")){assertTrue(result.next());assertEquals(42,result.getInt(1));}
    }
  }
  @Test void callerCancellationInterruptsActualInFlightStatementAndCleansProgressHandler()throws Exception{
    try(var pool=pool();var call=new CallContext()){
      var running=new CountDownLatch(1);var executor=Executors.newSingleThreadExecutor();try{var result=executor.submit(()->{try(var budget=HybridSearchDeadline.start(call,Duration.ofSeconds(5));var c=HybridSearchDeadline.source(pool).getConnection();var s=c.createStatement()){running.countDown();s.executeQuery(EXPENSIVE);return false;}catch(SQLException canceled){return true;}});assertTrue(running.await(2,TimeUnit.SECONDS));call.cancel();assertTrue(result.get(2,TimeUnit.SECONDS));}finally{executor.shutdownNow();}
      try(var c=pool.getConnection();var s=c.createStatement();var result=s.executeQuery("SELECT 7")){assertTrue(result.next());assertEquals(7,result.getInt(1));}
    }
  }
  @Test void deadlineCancelsOneConnectionPoolBorrowAndDoesNotLeakLateConnection()throws Exception{
    try(var pool=pool();var call=new CallContext()){
      try(var held=pool.getConnection();var budget=HybridSearchDeadline.start(call,Duration.ofMillis(80))){long started=System.nanoTime();assertThrows(SQLTimeoutException.class,()->HybridSearchDeadline.source(pool).getConnection());assertTrue(Duration.ofNanos(System.nanoTime()-started).toMillis()<800,"must use caller budget, not pool's one-second timeout");}
      try(var c=pool.getConnection();var statement=c.createStatement();var result=statement.executeQuery("SELECT 9")){assertTrue(result.next());assertEquals(9,result.getInt(1));}
    }
  }
  private HikariDataSource pool(){var config=new HikariConfig();config.setJdbcUrl("jdbc:sqlite::memory:");config.setMaximumPoolSize(1);config.setMinimumIdle(1);config.setConnectionTimeout(1000);return new HikariDataSource(config);}
}
