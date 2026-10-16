package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeProcessingSpanService {
  private final KnowledgeProcessingSpanRepository repository;
  private final ObjectMapper json;
  private final Clock clock;
  @org.springframework.beans.factory.annotation.Autowired
  public KnowledgeProcessingSpanService(KnowledgeProcessingSpanRepository repository,ObjectMapper json) {this(repository,json,Clock.systemDefaultZone());}
  public KnowledgeProcessingSpanService(KnowledgeProcessingSpanRepository repository,ObjectMapper json,Clock clock) {this.repository=repository;this.json=json;this.clock=clock;}
  public ObjectNode read(ObjectNode knowledge,String requested) {
    String id=knowledge.path("id").asText(),parse=knowledge.path("parse_status").asText();
    long latest=0;try {latest=repository.latest(id);}catch(RuntimeException ignored){}
    long current=attempt(requested);if(current<=0)current=latest;
    List<ObjectNode> rows=List.of();if(current>0)try {rows=repository.list(id,current);}catch(RuntimeException ignored){}
    var tree=new KnowledgeProcessingSpanTree(json).build(id,current,rows,parse,OffsetDateTime.now(clock));
    var out=json.createObjectNode().put("knowledge_id",id).put("attempt",current).put("latest_attempt",latest).put("parse_status",parse).put("current_attempt",current).put("current_stage",tree.currentStage());out.set("trace",tree.root());
    if(Set.of("pending","processing","finalizing").contains(parse)) {
      JsonNode last=knowledge.path("updated_at");
      for(var row:rows)if(Instant.parse(row.path("updated_at").asText()).isAfter(Instant.parse(last.asText())))last=row.path("updated_at");
      out.put("last_activity_at",KnowledgeProcessingSpanRepository.timestamp(last.asText()));
      // No native housekeeping/backlog probe exists yet. Unknown stays absent.
    }
    ObjectNode failure=tree.failure();
    if(failure!=null) {
      var error=error(failure.path("name").asText(),failure.path("error_code").asText(),failure.path("error_message").asText());
      error.set("finished_at",failure.has("finished_at")?failure.get("finished_at"):NullNode.instance);out.set("last_error",error);
    } else if(current==latest&&parse.equals("failed")&&!knowledge.path("error_message").asText().isEmpty()) {
      String message=knowledge.path("error_message").asText();
      var error=error("knowledge_processing",goTrim(message).equalsIgnoreCase("Task interrupted due to application restart")?"SERVER_RESTART":"UNKNOWN",message);error.put("finished_at",KnowledgeProcessingSpanRepository.timestamp(knowledge.path("updated_at").asText()));out.set("last_error",error);
    }
    return out;
  }
  private ObjectNode error(String name,String code,String message) {return json.createObjectNode().put("stage",name).put("code",code).put("message",message).put("name",name).put("error_code",code).put("error_message",message);}
  static long attempt(String value) {
    if(value==null)return 0;value=goTrim(value);if(!value.matches("[+-]?[0-9]+"))return 0;
    try {long number=Long.parseLong(value);return number>0?number:0;}catch(NumberFormatException ex){return 0;}
  }
  private static String goTrim(String text) {
    return text.replaceAll("^[\\x{0009}-\\x{000D}\\x{0020}\\x{0085}\\x{00A0}\\x{1680}\\x{2000}-\\x{200A}\\x{2028}\\x{2029}\\x{202F}\\x{205F}\\x{3000}]+|[\\x{0009}-\\x{000D}\\x{0020}\\x{0085}\\x{00A0}\\x{1680}\\x{2000}-\\x{200A}\\x{2028}\\x{2029}\\x{202F}\\x{205F}\\x{3000}]+$","");
  }
}
