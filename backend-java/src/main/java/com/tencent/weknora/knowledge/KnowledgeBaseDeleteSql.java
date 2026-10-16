package com.tencent.weknora.knowledge;

import com.tencent.weknora.model.client.CallContext;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.sqlite.ProgressHandler;
import org.sqlite.SQLiteConnection;

/** Bind cleanup SQL cancellation/deadlines to the actual statement, retaining Spring transactions. */
final class KnowledgeBaseDeleteSql {
  @FunctionalInterface interface Prepare { void run(Connection connection)throws SQLException; }
  private final JdbcTemplate jdbc;
  private final Prepare prepare;
  KnowledgeBaseDeleteSql(JdbcTemplate jdbc){this(jdbc,connection->{});}
  KnowledgeBaseDeleteSql(JdbcTemplate jdbc,Prepare prepare){this.jdbc=jdbc;this.prepare=prepare;}
  List<Map<String,Object>> query(CallContext call,String sql,Object...args) {
    return execute(call,sql,args,statement->{
      var rows=new ArrayList<Map<String,Object>>();var mapper=new ColumnMapRowMapper();
      try(var results=statement.executeQuery()){for(int n=0;results.next();n++)rows.add(mapper.mapRow(results,n));}return rows;
    });
  }
  int update(CallContext call,String sql,Object...args){return execute(call,sql,args,PreparedStatement::executeUpdate);}
  private <T>T execute(CallContext call,String sql,Object[] args,PreparedStatementCallback<T> work) {
    call.check();
    return jdbc.execute((PreparedStatementCreator)connection->{call.check();prepare.run(connection);return connection.prepareStatement(sql);},(PreparedStatementCallback<T>)statement->{
      call.check();
      SQLiteConnection sqlite=statement.getConnection().isWrapperFor(SQLiteConnection.class)?statement.getConnection().unwrap(SQLiteConnection.class):null;
      int busyTimeout=sqlite==null?0:sqlite.getBusyTimeout();
      try {
        for(int i=0;i<args.length;i++)StatementCreatorUtils.setParameterValue(statement,i+1,SqlTypeValue.TYPE_UNKNOWN,args[i]);
        if(call.deadline()!=null){long millis=Math.max(1,Duration.between(Instant.now(),call.deadline()).toMillis());statement.setQueryTimeout((int)Math.min(Integer.MAX_VALUE,Math.max(1,(millis+999)/1000)));}
        if(sqlite!=null)ProgressHandler.setHandler(sqlite,1000,new ProgressHandler(){protected int progress(){try{call.check();return 0;}catch(RuntimeException expired){return 1;}}});
        try(var registration=call.onCancel(()->{try{statement.cancel();}catch(SQLException ignored){}})) {
          call.check();T result=work.doInPreparedStatement(statement);call.check();return result;
        }catch(SQLException error){call.check();throw error;}
        catch(RuntimeException error){throw error;}
        catch(Exception error){throw new SQLException("Cannot detach cleanup SQL cancellation",error);}
      }finally{if(sqlite!=null){ProgressHandler.clearHandler(sqlite);sqlite.setBusyTimeout(busyTimeout);}}
    });
  }
}
