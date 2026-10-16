package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads production tracing rows without creating/repairing the tracing table. */
@Repository
public class KnowledgeProcessingSpanRepository {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  public KnowledgeProcessingSpanRepository(JdbcTemplate jdbc,ObjectMapper json) {this.jdbc=jdbc;this.json=json;}
  public long latest(String knowledge) {
    Long latest=jdbc.queryForObject("SELECT COALESCE(MAX(attempt),0) FROM knowledge_processing_spans WHERE knowledge_id=?",Long.class,knowledge);
    return latest==null?0:latest;
  }
  public List<ObjectNode> list(String knowledge,long attempt) {
    var rows=attempt>0?jdbc.queryForList("SELECT * FROM knowledge_processing_spans WHERE knowledge_id=? AND attempt=? ORDER BY id ASC",knowledge,attempt):jdbc.queryForList("SELECT * FROM knowledge_processing_spans WHERE knowledge_id=? ORDER BY id ASC",knowledge);
    var out=new ArrayList<ObjectNode>();
    for(var row:rows)out.add(project(row));
    return out;
  }
  private ObjectNode project(Map<String,Object> row) {
    var out=json.createObjectNode();
    for(String key:List.of("knowledge_id","span_id","name","kind","status"))out.put(key,Objects.toString(row.get(key),""));
    out.put("attempt",row.get("attempt") instanceof Number number?number.longValue():0);
    for(String key:List.of("parent_span_id","error_code","error_message")) {
      String value=Objects.toString(row.get(key),"");if(!value.isEmpty())out.put(key,value);
    }
    for(String key:List.of("input","output","metadata")) {
      JsonNode value=map(row.get(key));if(value!=null&&!value.isNull()&&!value.isEmpty())out.set(key,value);
    }
    for(String key:List.of("created_at","updated_at"))out.put(key,row.get(key)==null?"0001-01-01T00:00:00Z":timestamp(row.get(key)));
    for(String key:List.of("started_at","finished_at"))if(row.get(key)!=null)out.put(key,timestamp(row.get(key)));
    if(row.get("duration_ms") instanceof Number number&&number.longValue()!=0)out.put("duration_ms",number.longValue());
    // id and error_detail are deliberately excluded by Go's public DTO.
    return out;
  }
  static String timestamp(Object value) {
    var formatter=new java.time.format.DateTimeFormatterBuilder().appendPattern("uuuu-MM-dd'T'HH:mm:ss").appendFraction(java.time.temporal.ChronoField.NANO_OF_SECOND,0,9,true).appendOffsetId().toFormatter(java.util.Locale.ROOT);
    String raw=value instanceof String text?text.replace(' ','T'):KnowledgeBaseRepository.timestamp(value);
    java.time.OffsetDateTime time;
    try {time=java.time.OffsetDateTime.parse(raw);}catch(java.time.format.DateTimeParseException ex){time=java.time.LocalDateTime.parse(raw).atOffset(java.time.ZoneOffset.UTC);}
    return formatter.format(time);
  }
  private JsonNode map(Object value) {
    if(value==null)return null;
    String raw=value instanceof byte[] bytes?new String(bytes,StandardCharsets.UTF_8):value.toString();
    if(raw.isEmpty())return null;
    try {
      JsonNode parsed=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw);
      if(!parsed.isNull()&&!parsed.isObject())throw new IllegalArgumentException("Invalid processing span JSON map");
      return parsed;
    }catch(java.io.IOException ex){throw new IllegalArgumentException("Invalid persisted processing span JSON",ex);}
  }
}
