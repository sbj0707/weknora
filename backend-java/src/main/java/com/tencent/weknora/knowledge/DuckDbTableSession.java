package com.tencent.weknora.knowledge;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
/** Native DuckDB table/schema/sample lifecycle; task orchestration is implemented separately. */
public final class DuckDbTableSession implements AutoCloseable {
 private final Connection db;private final String session;private final Runnable check;
 private final Set<String> created=new LinkedHashSet<>();private final ObjectMapper json=new ObjectMapper();
 private final Set<Statement> statements=java.util.concurrent.ConcurrentHashMap.newKeySet();
 public DuckDbTableSession(Connection db,String session,Runnable check){this.db=db;this.session=session;this.check=check;}
 public ObjectNode load(String id,String type,Path filename)throws SQLException{
  String table="k_"+id.replace('-','_');type=type.toLowerCase(Locale.ROOT);
  if(created.add(table)){
   String file=literal(filename.toString()),sql;
   if(type.equals("csv"))sql="CREATE TABLE \""+table+"\" AS SELECT * FROM read_csv_auto('"+file+"', header=true, all_varchar=true)";
   else if(type.equals("xlsx")||type.equals("xls")){
    List<String> sheets=new ArrayList<>();
    try(var s=statement();var r=s.executeQuery("SELECT UNNEST(layers).name FROM st_read_meta('"+file+"')")){
     while(r.next()){check.run();String name=r.getString(1);if(!com.tencent.weknora.security.AuthPreferences.trimGoSpace(name).isEmpty())sheets.add(name);}
    }catch(SQLException error){sheets.clear();}
    if(sheets.isEmpty())sql="CREATE TABLE \""+table+"\" AS SELECT * FROM read_xlsx('"+file+"', header=true, all_varchar=true)";
    else{
     var parts=new ArrayList<String>();for(String name:sheets){String sheet=literal(name);parts.add("SELECT *, '"+sheet+"' AS __sheet_name FROM read_xlsx('"+file+"', sheet = '"+sheet+"', header=true, all_varchar=true)");}
     sql="CREATE TABLE \""+table+"\" AS "+String.join("\nUNION ALL BY NAME\n",parts);
    }
   }else throw new SQLException("unsupported file type: "+type+" (supported types: csv, xlsx, xls)");
   execute(sql);
  }
  ObjectNode schema=json.createObjectNode().put("table_name",table);ArrayNode columns=schema.putArray("columns");
  try(var s=statement();var r=s.executeQuery("DESCRIBE \""+table+"\"")){
   while(r.next()){check.run();columns.addObject().put("name",r.getString(1)).put("type",r.getString(2)).put("nullable",r.getString(3));}
  }
  try(var s=statement();var r=s.executeQuery("SELECT COUNT(*) FROM \""+table+"\"")){check.run();r.next();schema.put("row_count",r.getLong(1));}
  schema.putObject("metadata").put("column_count",columns.size()).put("session_id",session);return schema;
 }
 public ArrayNode sample(String table)throws SQLException{
  execute("CREATE OR REPLACE TEMPORARY VIEW \"dataset\" AS SELECT * FROM \""+table+"\"");
  try(var s=statement();var r=s.executeQuery("SELECT * FROM \"dataset\" LIMIT 10")){
   var rows=json.createArrayNode();var metadata=r.getMetaData();
   while(r.next()){check.run();ObjectNode row=rows.addObject();for(int i=1;i<=metadata.getColumnCount();i++){Object value=r.getObject(i);row.put(metadata.getColumnName(i),value==null?"<nil>":value instanceof byte[] b?new String(b,StandardCharsets.UTF_8):value.toString());}}
   return rows;
  }finally{try(var s=db.createStatement()){s.execute("DROP VIEW IF EXISTS \"dataset\"");}}
 }
 public static String description(JsonNode schema){
  var out=new StringBuilder("Table name: dataset\nColumns: ").append(schema.path("columns").size()).append("\nRows: ").append(schema.path("row_count").asLong()).append("\n\nColumn info:\n");
  for(var c:schema.path("columns"))out.append("- ").append(c.path("name").asText()).append(" (").append(c.path("type").asText()).append(")\n");return out.toString();
 }
 public String sampleDescription(ArrayNode rows)throws Exception{
  var out=new StringBuilder("Sample data (first 10 rows):\n");
  int index=0;for(var row:rows){if(index++>=10)break;
   var sorted=new TreeMap<String,JsonNode>((a,b)->Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8)));
   row.fields().forEachRemaining(e->sorted.put(e.getKey(),e.getValue()));
   out.append(json.writeValueAsString(sorted).replace("&","\\u0026").replace("<","\\u003c").replace(">","\\u003e").replace("\u2028","\\u2028").replace("\u2029","\\u2029")).append('\n');
  }return out.toString();
 }
 private Statement statement()throws SQLException{check.run();Statement s=db.createStatement();statements.add(s);return s;}
 public void cancel(){for(var s:statements)try{s.cancel();}catch(SQLException ignored){}}
 private void execute(String sql)throws SQLException{check.run();try(var s=statement()){s.execute(sql);}check.run();}
 private static String literal(String value){return value.replace("'","''");}
 @Override public void close(){for(String table:created)try(var s=db.createStatement()){s.execute("DROP TABLE IF EXISTS \""+table+"\"");}catch(SQLException ignored){}created.clear();statements.clear();}
}
