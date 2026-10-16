package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;

/** Internal owner-fenced writes to Go's existing span schema; no public write route. */
@Repository
public class KnowledgeProcessingSpanWriteRepository {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final Clock clock;
  private final KnowledgeProcessingSpanRepository reads;
  private Boolean postgres;
  @org.springframework.beans.factory.annotation.Autowired
  public KnowledgeProcessingSpanWriteRepository(JdbcTemplate jdbc,ObjectMapper json,KnowledgeProcessingSpanRepository reads) {this(jdbc,json,Clock.systemUTC(),reads);}
  public KnowledgeProcessingSpanWriteRepository(JdbcTemplate jdbc,ObjectMapper json,Clock clock,KnowledgeProcessingSpanRepository reads) {this.jdbc=jdbc;this.json=json;this.clock=clock;this.reads=reads;}
  public long next(long owner,String knowledge) {scope(owner,knowledge);return reads.latest(knowledge)+1;}
  public long latest(long owner,String knowledge) {scope(owner,knowledge);return reads.latest(knowledge);}
  public List<ObjectNode> list(long owner,String knowledge,long attempt) {scope(owner,knowledge);return reads.list(knowledge,attempt);}
  public void upsert(long owner,ObjectNode row) {
    String knowledge=row.path("knowledge_id").asText(),id=row.path("span_id").asText();
    if(knowledge.isEmpty()||id.isEmpty())throw new IllegalArgumentException("knowledge_id and span_id required");
    scope(owner,knowledge);long attempt=row.path("attempt").asLong();if(attempt==0)attempt=1;
    var fields=new ArrayList<>(List.of("knowledge_id","attempt","span_id","parent_span_id","name","kind","status","error_code","error_message","error_detail","started_at","finished_at","duration_ms","created_at","updated_at"));
    var values=new ArrayList<Object>();
    values.add(knowledge);values.add(attempt);values.add(id);
    for(String key:List.of("parent_span_id","name","kind","status","error_code","error_message","error_detail"))values.add(key.startsWith("error_")?KnowledgeProcessingSpanTracker.clean(row.path(key).asText("")):row.path(key).asText(""));
    values.add(time(row.get("started_at")));values.add(time(row.get("finished_at")));values.add(row.path("duration_ms").asLong());
    values.add(time(row.hasNonNull("created_at")?row.get("created_at"):json.getNodeFactory().textNode(clock.instant().toString())));
    values.add(time(row.hasNonNull("updated_at")?row.get("updated_at"):json.getNodeFactory().textNode(clock.instant().toString())));
    var update=new ArrayList<>(List.of("status","error_code","error_message","error_detail","started_at","finished_at","duration_ms","updated_at"));
    var slots=new ArrayList<>(Collections.nCopies(fields.size(),"?"));
    for(String key:List.of("input","output","metadata")) {
      JsonNode value=row.get(key);fields.add(key);slots.add(postgres()?"?::jsonb":"?");
      if(value==null||value.isNull())values.add(null);
      else {if(!value.isObject())throw new IllegalArgumentException("Span JSON must be a map");String encoded=encodedMap(value);values.add(postgres()?encoded:encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8));update.add(key);}
    }
    values.add(owner);values.add(knowledge);
    int affected=jdbc.update("INSERT INTO knowledge_processing_spans ("+String.join(",",fields)+") SELECT "+String.join(",",slots)+" WHERE "+ownerPredicate()+" ON CONFLICT(knowledge_id,attempt,span_id) DO UPDATE SET "+String.join(",",update.stream().map(key->key+"=excluded."+key).toList()),values.toArray());
    if(affected==0)throw new IllegalArgumentException("Invalid knowledge processing span owner");
  }
  public int cancelOpen(long owner,String knowledge,long attempt,String name,String code,String reason) {
    if(knowledge.isEmpty()||attempt<=0||name!=null&&name.isEmpty())return 0;
    scope(owner,knowledge);Object now=time(json.getNodeFactory().textNode(clock.instant().toString()));
    var args=new ArrayList<Object>(List.of(code,reason,now,now,knowledge,attempt));if(name!=null)args.add(name);args.add(owner);args.add(knowledge);
    return jdbc.update("UPDATE knowledge_processing_spans SET status='cancelled',error_code=?,error_message=?,finished_at=?,updated_at=? WHERE knowledge_id=? AND attempt=?"+(name==null?"":" AND name=?")+" AND status IN ('pending','running') AND "+ownerPredicate(),args.toArray());
  }
  public int cancelDescendants(long owner,String knowledge,long attempt,String parent,String reason) {
    scope(owner,knowledge);var frontier=List.of(parent);int affected=0;
    for(int depth=0;depth<16&&!frontier.isEmpty();depth++) {
      var args=new ArrayList<Object>(List.of(knowledge,attempt));args.addAll(frontier);
      var children=jdbc.queryForList("SELECT span_id FROM knowledge_processing_spans WHERE knowledge_id=? AND attempt=? AND parent_span_id IN ("+String.join(",",Collections.nCopies(frontier.size(),"?"))+") AND status IN ('pending','running')",String.class,args.toArray());
      if(children.isEmpty())break;
      var update=new ArrayList<Object>(List.of(reason,time(json.getNodeFactory().textNode(clock.instant().toString())),knowledge,attempt));update.addAll(children);update.add(owner);update.add(knowledge);
      affected+=jdbc.update("UPDATE knowledge_processing_spans SET status='cancelled',error_code='UPSTREAM_FAILED',error_message=?,updated_at=? WHERE knowledge_id=? AND attempt=? AND span_id IN ("+String.join(",",Collections.nCopies(children.size(),"?"))+") AND "+ownerPredicate(),update.toArray());frontier=children;
    }return affected;
  }
  public void heartbeat(long owner,String knowledge,String kind) {
    if(!Set.of("root","stage").contains(kind))return;
    jdbc.update("UPDATE knowledges SET updated_at=? WHERE tenant_id=? AND id=? AND deleted_at IS NULL",time(json.getNodeFactory().textNode(clock.instant().toString())),owner,knowledge);
  }
  private void scope(long owner,String knowledge) {
    if(owner<=0||jdbc.queryForList("SELECT id FROM knowledges WHERE tenant_id=? AND id=? AND deleted_at IS NULL",owner,knowledge).isEmpty())throw new IllegalArgumentException("Invalid knowledge processing span owner");
  }
  private static String ownerPredicate() {return "EXISTS (SELECT 1 FROM knowledges WHERE tenant_id=? AND id=? AND deleted_at IS NULL)";}
  private String encodedMap(JsonNode value) {
    // Go JSONMap.Value uses encoding/json: sorted map keys, HTML and U+2028/2029 escaping.
    return sorted(value).toString().replace("<","\\u003c").replace(">","\\u003e").replace("&","\\u0026").replace("\u2028","\\u2028").replace("\u2029","\\u2029");
  }
  private JsonNode sorted(JsonNode value) {
    if(value.isObject()) {var out=json.createObjectNode();var keys=new TreeSet<String>((a,b)->Arrays.compareUnsigned(a.getBytes(java.nio.charset.StandardCharsets.UTF_8),b.getBytes(java.nio.charset.StandardCharsets.UTF_8)));value.fieldNames().forEachRemaining(keys::add);for(String key:keys)out.set(key,sorted(value.get(key)));return out;}
    if(value.isArray()) {var out=json.createArrayNode();value.forEach(item->out.add(sorted(item)));return out;}
    return value;
  }
  private Object time(JsonNode value) {if(value==null||value.isNull())return null;Instant instant=Instant.parse(value.asText());return postgres()?Timestamp.from(instant):GoSqliteTimestamp.write(instant);}
  private boolean postgres() {if(postgres==null)postgres=jdbc.execute((ConnectionCallback<Boolean>)connection->connection.getMetaData().getDatabaseProductName().equals("PostgreSQL"));return Boolean.TRUE.equals(postgres);}
}
