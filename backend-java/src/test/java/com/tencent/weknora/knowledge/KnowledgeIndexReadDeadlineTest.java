package com.tencent.weknora.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.sql.SQLTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class KnowledgeIndexReadDeadlineTest {
  @Test void sqliteCpuQueryIsInterruptedAndPooledConnectionRemainsUsable() throws Exception {
    var source=new SingleConnectionDataSource("jdbc:sqlite::memory:",true);
    try {
      var physical=source.getConnection().unwrap(org.sqlite.SQLiteConnection.class);
      physical.setBusyTimeout(2345);
      var jdbc=KnowledgeIndexReadDeadline.jdbc(source);
      long started=System.nanoTime();
      try(var deadline=KnowledgeIndexReadDeadline.start(Duration.ofMillis(40))) {
        assertThrows(DataAccessException.class,()->jdbc.queryForObject("WITH RECURSIVE x(n) AS (VALUES(1) UNION ALL SELECT n+1 FROM x WHERE n<100000000) SELECT SUM(n) FROM x",Long.class));
      }
      assertEquals(2345,physical.getBusyTimeout(),"Inspection changed another consumer's pooled connection timeout");
      assertTrue(Duration.ofNanos(System.nanoTime()-started).compareTo(Duration.ofSeconds(3))<0);
      assertEquals(42,jdbc.queryForObject("SELECT 42",Integer.class));
    }finally {source.destroy();}
  }
  @Test void expiredBudgetRejectsFurtherReadsAndNestedBudgetRestoresOuterScope() throws Exception {
    try(var deadline=KnowledgeIndexReadDeadline.start(Duration.ZERO)) {
      assertThrows(SQLTimeoutException.class,()->KnowledgeIndexReadDeadline.seconds(12));
      try(var nested=KnowledgeIndexReadDeadline.start(Duration.ofSeconds(1))) {assertEquals(1,KnowledgeIndexReadDeadline.seconds(12));}
      assertThrows(SQLTimeoutException.class,()->KnowledgeIndexReadDeadline.seconds(12));
    }
    assertEquals(12,KnowledgeIndexReadDeadline.seconds(12));
  }
}
