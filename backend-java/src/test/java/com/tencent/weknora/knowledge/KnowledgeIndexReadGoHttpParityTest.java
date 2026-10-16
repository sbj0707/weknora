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

/** Production Go HTTP oracle, including exact persisted SQL types and read side effects. */
class KnowledgeIndexReadGoHttpParityTest {
  private static final ObjectMapper JSON=new ObjectMapper();
  @TestFactory Stream<DynamicTest> productionHttpAndDatabaseSnapshots() throws Exception {
    JsonNode cases;
    try(var in=new GZIPInputStream(getClass().getResourceAsStream("/go-knowledge-index-http.json.gz"))) {cases=JSON.readTree(in);}
    assertEquals(160,cases.size(),"Production oracle corpus was truncated");
    // Ensure the oracle retains useful positive feature coverage. SQLite TEXT
    // fixtures alone silently disable Go's BLOB-backed configuration scanners.
    var normal=cases.get(0).path("body").path("data").path("indexes");
    assertEquals(1,normal.get(0).path("count").asInt());
    assertEquals(3,normal.get(3).path("count").asInt());
    assertTrue(normal.get(4).path("enabled").asBoolean());
    assertEquals(1,normal.get(4).path("indexed_count").asInt());
    var tests=new ArrayList<DynamicTest>();
    for(var scene:cases)tests.add(DynamicTest.dynamicTest(scene.path("name").asText(),()->replay(scene)));
    return tests.stream();
  }
  private void replay(JsonNode scene) throws Exception {
    Path path=Files.createTempFile("java-index-go-http-",".db");
    var source=new SingleConnectionDataSource("jdbc:sqlite:"+path,true);
    try {
      new SQLiteVectorExtension(Path.of(System.getProperty("weknora.sqlite.vec.extension"))).load(source.getConnection());
      var jdbc=new JdbcTemplate(source);restore(jdbc,scene.path("seed"));
      JsonNode before=snapshot(jdbc,scene.path("seed"));
      var content=new KnowledgeContentReadRepository(jdbc,JSON);
      var reads=new KnowledgeBaseReadAuthorization(jdbc,new KnowledgeBaseCodec(JSON),JSON,Set.of());
      var authz=new KnowledgeContentReadAuthorization(content,reads);
      var env=new MockEnvironment().withProperty("weknora.retrieve-driver","sqlite").withProperty("weknora.sqlite.vec.extension",System.getProperty("weknora.sqlite.vec.extension"));
      var repo=new KnowledgeIndexReadRepository(jdbc,JSON,env,content);
      var controller=new KnowledgeIndexReadController(authz,new KnowledgeIndexReadService(repo,JSON));
      var mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiErrorAdvice()).build();
      var auth=new AuthContext("caller",7,"viewer",false,scene.path("machine").asBoolean()?1L:null,scene.path("full_access").asBoolean(),strings(scene.path("capabilities")),strings(scene.path("kb_scope")));
      var response=mvc.perform(get(scene.path("path").asText()).requestAttr(CurrentAuth.ATTRIBUTE,auth)).andReturn().getResponse();
      assertEquals(scene.path("status").asInt(),response.getStatus(),response.getContentAsString());
      JsonNode expected=scene.path("body").deepCopy(),actual=JSON.readTree(response.getContentAsByteArray());
      assertEquals(normalize(expected),normalize(actual));
      assertEquals(before,snapshot(jdbc,scene.path("seed")),"Java read mutated business data");
      assertEquals(scene.path("seed"),scene.path("after"),"Go oracle read mutated business data");
    } finally {source.destroy();Files.deleteIfExists(path);}
  }
  private static Set<String> strings(JsonNode array) {var out=new HashSet<String>();for(var item:array)out.add(item.asText());return out;}
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
      String select=table.getKey().equals("vec_embeddings_2")?"rowid,embedding":table.getKey().equals("lite_embeddings_fts")?"rowid":"*";
      var rows=JSON.createArrayNode();
      for(var row:jdbc.queryForList("SELECT "+select+" FROM "+quote(table.getKey()))) {
        var projected=JSON.createObjectNode();row.forEach((key,value)->{
          if(value instanceof byte[] bytes)projected.set(key,JSON.createObjectNode().put("$blob",Base64.getEncoder().encodeToString(bytes)));
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
