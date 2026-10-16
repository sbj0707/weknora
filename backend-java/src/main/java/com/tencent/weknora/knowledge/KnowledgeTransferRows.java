package com.tencent.weknora.knowledge;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.jdbc.core.*;

/** SQL types.JSON Scan validates syntax before business checks and reports the actual column ordinal. */
final class KnowledgeTransferRows {
  private static final Set<String> RAW_JSON=Set.of("metadata","custom_metadata","last_faq_import_result","relation_chunks","indirect_relation_chunks");
  private KnowledgeTransferRows() {}
  static List<Map<String,Object>> query(JdbcTemplate jdbc,String sql,Object... args) {
    return jdbc.query(sql,(rs,n)->{
      var row=new ColumnMapRowMapper().mapRow(rs,n);var columns=rs.getMetaData();
      for(int i=1;i<=columns.getColumnCount();i++) {
        String name=columns.getColumnLabel(i);Object value=row.get(name);
        if(!RAW_JSON.contains(name)||value==null)continue;
        byte[] raw=value instanceof byte[] bytes?bytes:value.toString().getBytes(StandardCharsets.UTF_8);
        if(raw.length==0)continue;
        try{GoUploadJson.valid(raw);}
        catch(java.io.IOException error){throw new IllegalStateException("sql: Scan error on column index "+(i-1)+", name \""+name+"\": "+error.getMessage(),error);}
      }
      return row;
    },args);
  }
}
