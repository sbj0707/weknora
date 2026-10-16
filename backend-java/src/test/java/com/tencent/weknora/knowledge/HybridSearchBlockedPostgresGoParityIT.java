package com.tencent.weknora.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.model.client.CallContext;
import com.tencent.weknora.security.*;
import com.zaxxer.hikari.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;

/** Actual Go handler/PG lock oracle versus the same native HTTP logic with a caller context. */
class HybridSearchBlockedPostgresGoParityIT extends HybridSearchPostgresGoParityIT {
  @Override @TestFactory Stream<DynamicTest> actualGoPublicSearch()throws Exception{var rows=json.readTree(Files.readAllBytes(Path.of(Objects.requireNonNull(System.getProperty("weknora.hybrid.pg.fixture")))));assertEquals(6,rows.size());var sequence=new AtomicInteger();return StreamSupport.stream(rows.spliterator(),false).map(row->DynamicTest.dynamicTest("blocked-pg/"+row.path("name").asText(),()->compare(row,sequence.incrementAndGet())));}
  @Override javax.sql.DataSource database(int sequence){var pool=(HikariDataSource)super.database(sequence);pool.setMaximumPoolSize(Integer.parseInt(System.getProperty("weknora.hybrid.pg.pool","2")));return pool;}
  @Override void prepareStore(HybridSearchStore store,JsonNode oracle){try(var call=new CallContext()){store.resolve(call);}}
  @Override HybridSearchController controller(KnowledgeBaseReadAuthorization auth,HybridSearchService search,HybridSearchResourceURLs resources,JsonNode oracle){return new HybridSearchController(auth,search,resources,()->{String name=oracle.path("name").asText();var call=new CallContext(name.endsWith("parent-deadline")?Instant.now().plusMillis(300):null);if(name.endsWith("parent-cancel"))Thread.ofVirtual().start(()->{try{Thread.sleep(300);call.cancel();}catch(InterruptedException stop){Thread.currentThread().interrupt();}});return call;});}
  @Override AutoCloseable boundary(javax.sql.DataSource source,JsonNode oracle)throws Exception{
    // Pre-create the configured physical pool: this tests application fanout, not connection startup latency.
    var pids=new HashSet<Integer>();var warmed=new ArrayList<Connection>();try{for(int i=0;i<Integer.parseInt(System.getProperty("weknora.hybrid.pg.pool","2"));i++){var connection=source.getConnection();warmed.add(connection);try(var statement=connection.createStatement();var row=statement.executeQuery("SELECT pg_backend_pid()")){assertTrue(row.next());pids.add(row.getInt(1));}}}finally{for(var connection:warmed)connection.close();}
    String url;try(var connection=source.getConnection()){url=connection.getMetaData().getURL();}
    var lock=DriverManager.getConnection(url,"postgres","local-parity-only");lock.setAutoCommit(false);try(var statement=lock.createStatement()){statement.execute("LOCK TABLE embeddings IN ACCESS EXCLUSIVE MODE");}
    var observer=DriverManager.getConnection(url,"postgres","local-parity-only");var maximum=new AtomicInteger();var failures=new AtomicReference<Throwable>();var stopped=new AtomicBoolean();long started=System.nanoTime();
    var worker=Thread.ofVirtual().start(()->{try(var statement=observer.createStatement()){while(!stopped.get()){try(var result=statement.executeQuery("SELECT COUNT(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query ILIKE '%embeddings%' AND pid<>pg_backend_pid()")){assertTrue(result.next());maximum.accumulateAndGet(result.getInt(1),Math::max);}Thread.sleep(10);}}catch(Throwable failure){if(!stopped.get())failures.set(failure);}});
    return ()->{stopped.set(true);worker.join(1000);try{lock.rollback();}finally{lock.close();observer.close();}assertNull(failures.get());var after=new HashSet<Integer>();var returned=new ArrayList<Connection>();try{for(int i=0;i<pids.size();i++){var connection=source.getConnection();returned.add(connection);try(var statement=connection.createStatement();var row=statement.executeQuery("SELECT pg_backend_pid(),42")){assertTrue(row.next());after.add(row.getInt(1));assertEquals(42,row.getInt(2));}}}finally{for(var connection:returned)connection.close();}assertEquals(pids,after,"all physical pool connections must be reusable after statement/pool cancellation");int expected=Integer.parseInt(System.getProperty("weknora.hybrid.pg.pool","2"));assertTrue(maximum.get()>=Math.min(expected,oracle.path("name").asText().endsWith("store-cap")?4:oracle.path("name").asText().startsWith("single-owner-")?1:2),"actual physical queries blocked concurrently: "+maximum.get());long elapsed=Duration.ofNanos(System.nanoTime()-started).toMillis();assertTrue(elapsed<(oracle.path("name").asText().contains("parent-")?850:1900),"caller/group deadline must precede Hikari's 2-second borrow timeout: "+elapsed);System.out.println("native "+oracle.path("name").asText()+" pool="+expected+" blocked="+maximum.get()+" elapsedMillis="+elapsed);};
  }
}
