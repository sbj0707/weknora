package com.tencent.weknora.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.security.*;
import com.tencent.weknora.task.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Replays actual production Go cancellation SQL, tracker and audit side effects. */
class KnowledgeParseCancelGoParityTest {
  static final ObjectMapper JSON=new ObjectMapper();
  static Stream<JsonNode> cases()throws Exception {
    try(var in=KnowledgeParseCancelGoParityTest.class.getResourceAsStream("/go-knowledge-parse-cancel.json")) {
      JsonNode corpus=JSON.readTree(Objects.requireNonNull(in));
      assertEquals(54,corpus.size(),"frozen actual Go oracle must not silently omit cases");
      return java.util.stream.StreamSupport.stream(corpus.spliterator(),false);
    }
  }
  @BeforeAll static void nativeOrder()throws Exception{com.tencent.weknora.retrieval.NativeDatabaseRuntime.initialize();}
  @ParameterizedTest(name="{index} {0}") @MethodSource("cases")
  void actualGoCancelEffects(JsonNode oracle)throws Exception{
    var path=Files.createTempFile("weknora-java-cancel-parity-",".db");
    try(var ds=new SingleConnectionDataSource("jdbc:sqlite:"+path,true)){
      try(var connection=ds.getConnection()){
        for(var migration:oracle.path("scripts"))ScriptUtils.executeSqlScript(connection,new FileSystemResource("../migrations/sqlite/"+migration.asText()+".up.sql"));
        ScriptUtils.executeSqlScript(connection,new FileSystemResource("migration/sql/java-tasks.sql"));
      }
      var jdbc=new JdbcTemplate(ds);for(var seed:oracle.path("seed"))jdbc.execute(seed.asText());
      var manager=new DataSourceTransactionManager(ds);var reads=new KnowledgeContentReadRepository(jdbc,JSON);var spanReads=new KnowledgeProcessingSpanRepository(jdbc,JSON);
      var tracker=new KnowledgeProcessingSpanTracker(new KnowledgeProcessingSpanWriteRepository(jdbc,JSON,spanReads),JSON);
      var tasks=new TaskRepository(jdbc,JSON,manager);var service=new KnowledgeParseCancelService(jdbc,reads,tracker,tasks,JSON,manager);
      var caller=new AuthContext("caller",7,"admin",false,null,false,Set.of(),Set.of());Exception failure=null;
      try{service.cancel(caller,8,"kb","doc");if(oracle.path("name").asText().equals("repeat"))service.cancel(caller,8,"kb","doc");}catch(Exception e){failure=e;}
      assertEquals(!oracle.path("error_message").asText().isEmpty(),failure!=null,"failure decision");
      if(oracle.path("error_code").asInt()!=0){assertInstanceOf(ApiException.class,failure);assertEquals(oracle.path("error_code").asInt(),((ApiException)failure).appCode());assertEquals(oracle.path("error_message").asText(),failure.getMessage());}
      var actual=JSON.createObjectNode();
      var queries=new LinkedHashMap<String,String>();
      queries.put("knowledge","SELECT id,tenant_id,knowledge_base_id,parse_status,summary_status,error_message,pending_subtasks_count,description,profile,deleted_at FROM knowledges ORDER BY id");
      queries.put("chunks","SELECT id,tenant_id,knowledge_id,content,chunk_type FROM chunks ORDER BY id");
      queries.put("pending","SELECT tenant_id,task_type,scope,scope_id,dedup_key,op FROM task_pending_ops ORDER BY id");
      queries.put("spans","SELECT knowledge_id,attempt,span_id,parent_span_id,name,kind,status,error_code,error_message FROM knowledge_processing_spans ORDER BY attempt,span_id");
      queries.put("audit","SELECT tenant_id,actor_user_id,actor_role,action,target_type,target_id,scope_type,scope_id,outcome,details FROM audit_logs ORDER BY id");
      for(var entry:queries.entrySet())try{actual.set(entry.getKey(),JSON.valueToTree(jdbc.queryForList(entry.getValue())));}catch(org.springframework.dao.DataAccessException e){actual.putNull(entry.getKey());}
      assertEquals(normalize(oracle.path("projection")),normalize(actual),oracle.path("name").asText());
    }finally{Files.deleteIfExists(path);}
  }
  static JsonNode normalize(JsonNode value)throws Exception{
    if(value.isNumber())return DecimalNode.valueOf(value.decimalValue().stripTrailingZeros());
    if(value.isArray()){var array=JSON.createArrayNode();for(var item:value)array.add(normalize(item));return array;}
    if(value.isObject()){var obj=JSON.createObjectNode();var it=value.fields();while(it.hasNext()){var e=it.next();if(Set.of("details","profile").contains(e.getKey())&&e.getValue().isTextual())obj.set(e.getKey(),normalize(JSON.readTree(e.getValue().asText())));else obj.set(e.getKey(),normalize(e.getValue()));}return obj;}
    return value;
  }
}
