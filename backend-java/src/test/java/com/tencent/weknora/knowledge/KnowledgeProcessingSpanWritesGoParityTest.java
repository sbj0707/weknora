package com.tencent.weknora.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.api.ApiErrorAdvice;
import com.tencent.weknora.retrieval.SQLiteVectorExtension;
import com.tencent.weknora.security.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Production Go repository oracle, including JSON BLOBs and all business-table side effects. */
class KnowledgeProcessingSpanWritesGoParityTest {
  private static final ObjectMapper JSON=new ObjectMapper();
  @TestFactory Stream<DynamicTest> productionUpsertAndDatabaseSnapshots() throws Exception {
    JsonNode cases;
    try(var in=new GZIPInputStream(getClass().getResourceAsStream("/go-knowledge-processing-span-writes.json.gz"))) {cases=JSON.readTree(in);}
    var tests=new ArrayList<DynamicTest>();
    for(var scene:cases)tests.add(DynamicTest.dynamicTest(scene.path("name").asText(),()->replay(scene)));
    return tests.stream();
  }
  private void replay(JsonNode scene) throws Exception {
    Path path=Files.createTempFile("java-spans-go-http-",".db");
    var source=new SingleConnectionDataSource("jdbc:sqlite:"+path,true);
    try {
      new SQLiteVectorExtension(Path.of(System.getProperty("weknora.sqlite.vec.extension"))).load(source.getConnection());
      var jdbc=new JdbcTemplate(source);restore(jdbc,scene.path("seed"));
      var clock=java.time.Clock.fixed(java.time.Instant.parse(scene.path("clock").asText()),java.time.ZoneOffset.UTC);
      var reads=new KnowledgeProcessingSpanRepository(jdbc,JSON);
      var writes=new KnowledgeProcessingSpanWriteRepository(jdbc,JSON,clock,reads);
      for(var operation:scene.path("ops"))writes.upsert(7,(ObjectNode)reverseFields(operation));
      assertEquals(scene.path("latest").asLong(),reads.latest("doc"));
      assertEquals(scene.path("next").asLong(),writes.next(7,"doc"));
      assertEquals(normalize(scene.path("after").path("tables")),snapshot(jdbc,scene.path("seed")));
    } finally {source.destroy();Files.deleteIfExists(path);}
  }
  private static Set<String> strings(JsonNode array) {var out=new HashSet<String>();for(var item:array)out.add(item.asText());return out;}
  private JsonNode reverseFields(JsonNode value) {
    // Input insertion order is irrelevant to a Go map; deliberately vary it for Java.
    if(value.isObject()) {var names=new ArrayList<String>();value.fieldNames().forEachRemaining(names::add);Collections.reverse(names);var out=JSON.createObjectNode();for(String name:names)out.set(name,reverseFields(value.get(name)));return out;}
    if(value.isArray()) {var out=JSON.createArrayNode();value.forEach(item->out.add(reverseFields(item)));return out;}
    return value.deepCopy();
  }
  private void restore(JdbcTemplate jdbc,JsonNode seed) {
    for(var schema:seed.path("schema"))if(schema.path("type").asText().equals("table"))jdbc.execute(schema.path("sql").asText());
    seed.path("tables").fields().forEachRemaining(table->{for(var row:table.getValue()) {
      var names=new ArrayList<String>();var args=new ArrayList<Object>();
      row.fields().forEachRemaining(field->{names.add(quote(field.getKey()));args.add(value(field.getValue()));});
      jdbc.update("INSERT INTO "+quote(table.getKey())+" ("+String.join(",",names)+") VALUES ("+String.join(",",Collections.nCopies(names.size(),"?"))+")",args.toArray());
    }});
    for(var schema:seed.path("schema"))if(!schema.path("type").asText().equals("table"))jdbc.execute(schema.path("sql").asText());
  }
  private JsonNode snapshot(JdbcTemplate jdbc,JsonNode seed) {
    var out=JSON.createObjectNode();
    seed.path("tables").fields().forEachRemaining(table->{
      // mattn/go-sqlite3 exposes declared BOOLEAN as bool and DATETIME/TIMESTAMP as time.Time.
      // JDBC returns integer/string, so reproduce those declared-column projections, retaining
      // all nulls, BLOB bytes and nanosecond date values from the actual Go snapshot.
      var booleans=new HashSet<String>();
      var timestamps=new HashSet<String>();
      for(var column:jdbc.queryForList("PRAGMA table_info("+quote(table.getKey())+")")) {
        String type=String.valueOf(column.get("type")),name=String.valueOf(column.get("name"));
        if("BOOLEAN".equalsIgnoreCase(type))booleans.add(name);
        if("DATETIME".equalsIgnoreCase(type)||"TIMESTAMP".equalsIgnoreCase(type))timestamps.add(name);
      }
      String select=table.getKey().equals("vec_embeddings_2")?"rowid,embedding":table.getKey().equals("lite_embeddings_fts")?"rowid":"*";
      var rows=JSON.createArrayNode();
      for(var row:jdbc.queryForList("SELECT "+select+" FROM "+quote(table.getKey()))) {
        var projected=JSON.createObjectNode();row.forEach((key,value)->{
          if(value instanceof byte[] bytes)projected.set(key,JSON.createObjectNode().put("$blob",Base64.getEncoder().encodeToString(bytes)));
          else if(booleans.contains(key)&&value instanceof Number number)projected.put(key,number.longValue()!=0);
          else if(timestamps.contains(key)&&value instanceof String)projected.put(key,KnowledgeProcessingSpanRepository.timestamp(value));
          else projected.set(key,JSON.valueToTree(value));
        });rows.add(projected);
      }out.set(table.getKey(),rows);
    });return normalize(out);
  }
  private static Object value(JsonNode n) {if(n.isNull())return null;if(n.has("$blob"))return Base64.getDecoder().decode(n.path("$blob").asText());if(n.isTextual())return n.asText();if(n.isBoolean())return n.asBoolean();if(n.isIntegralNumber())return n.asLong();if(n.isNumber())return n.asDouble();throw new IllegalArgumentException("Unsupported SQL fixture value");}
  private static String quote(String text) {return "\""+text.replace("\"","\"\"")+"\"";}
  private static JsonNode normalize(JsonNode n) {
    if(n.isNumber())return new DecimalNode(n.decimalValue().stripTrailingZeros());
    if(n.isObject()){var out=JSON.createObjectNode();n.fields().forEachRemaining(f->out.set(f.getKey(),normalize(f.getValue())));return out;}
    if(n.isArray()){var out=JSON.createArrayNode();n.forEach(v->out.add(normalize(v)));return out;}return n;
  }
}
