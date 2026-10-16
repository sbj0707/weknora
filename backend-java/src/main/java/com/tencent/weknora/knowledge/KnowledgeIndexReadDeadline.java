package com.tencent.weknora.knowledge;

import java.lang.reflect.*;
import java.sql.*;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.sqlite.ProgressHandler;
import org.sqlite.SQLiteConnection;

/** Same post-authorization 8s/12s budget as Go, shared by every inspection query. */
final class KnowledgeIndexReadDeadline implements AutoCloseable {
  private static final ThreadLocal<Long> END=new ThreadLocal<>();
  private final Long previous=END.get();
  private KnowledgeIndexReadDeadline(Duration budget) {END.set(System.nanoTime()+budget.toNanos());}
  static KnowledgeIndexReadDeadline start(Duration budget) {return new KnowledgeIndexReadDeadline(budget);}
  static int seconds(int fallback) throws SQLException {
    Long end=END.get();if(end==null)return fallback;
    long left=end-System.nanoTime();if(left<=0)throw new SQLTimeoutException("Document inspection deadline exceeded");
    return Math.max(1,(int)Math.ceil(left/1_000_000_000d));
  }
  static JdbcTemplate jdbc(DataSource source) {
    return new JdbcTemplate(new DelegatingDataSource(source) {
      @Override public Connection getConnection() throws SQLException {return wrap(super.getConnection());}
      @Override public Connection getConnection(String user,String password) throws SQLException {return wrap(super.getConnection(user,password));}
    }) {
      @Override protected void applyStatementSettings(Statement statement) throws SQLException {
        super.applyStatementSettings(statement);statement.setQueryTimeout(seconds(12));
      }
    };
  }
  private static Connection wrap(Connection connection) throws SQLException {
    Long end=END.get();if(end==null)return connection;
    try {seconds(12);}catch(SQLException ex) {connection.close();throw ex;}
    SQLiteConnection sqlite=connection.isWrapperFor(SQLiteConnection.class)?connection.unwrap(SQLiteConnection.class):null;
    int busyTimeout=sqlite==null?0:sqlite.getBusyTimeout();
    if(sqlite!=null)ProgressHandler.setHandler(sqlite,1000,new ProgressHandler() {
      @Override protected int progress() {return System.nanoTime()>=end||Thread.currentThread().isInterrupted()?1:0;}
    });
    return (Connection)Proxy.newProxyInstance(KnowledgeIndexReadDeadline.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
      if(method.getName().equals("close")&&sqlite!=null) {
        try {ProgressHandler.clearHandler(sqlite);sqlite.setBusyTimeout(busyTimeout);}
        catch(SQLException ex) {try {connection.close();}catch(SQLException close){ex.addSuppressed(close);}throw ex;}
      }
      try {return method.invoke(connection,args);}catch(InvocationTargetException ex){throw ex.getCause();}
    });
  }
  @Override public void close() {if(previous==null)END.remove();else END.set(previous);}
}
