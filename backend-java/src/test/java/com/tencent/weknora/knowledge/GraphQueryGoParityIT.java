package com.tencent.weknora.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import com.tencent.weknora.model.*;
import com.tencent.weknora.model.client.*;
import com.tencent.weknora.security.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.driver.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.env.MockEnvironment;

/** Production Go plugins + real SQL/model HTTP/Neo4j; these are internal RAG stages, not session SSE. */
class GraphQueryGoParityIT {
  @TempDir Path temp; final ObjectMapper json=new ObjectMapper(); static Driver driver;
  @BeforeAll static void connect(){String uri=System.getProperty("weknora.graph.test.uri","");assertFalse(uri.isBlank(),"explicit owned Neo4j required");driver=GraphDatabase.driver(uri,AuthTokens.none(),Config.builder().withConnectionTimeout(1,TimeUnit.SECONDS).withMaxTransactionRetryTime(0,TimeUnit.SECONDS).build());driver.verifyConnectivity();}
  @AfterAll static void close(){if(driver!=null)driver.close();}
  @TestFactory Stream<DynamicTest> actualGoQueryChain() throws Exception {
    JsonNode cases;try(var in=getClass().getResourceAsStream("/go-graph-query.json")){cases=json.readTree(in);}assertEquals(55,cases.size());var sequence=new AtomicInteger();
    return StreamSupport.stream(cases.spliterator(),false).map(c->DynamicTest.dynamicTest(c.path("name").asText(),()->compare(c,sequence.incrementAndGet())));
  }
  private void compare(JsonNode c,int number)throws Exception{
    String name=c.path("name").asText();var source=new DriverManagerDataSource("jdbc:sqlite:"+temp.resolve("query-"+number+".db"));source.setDriverClassName("org.sqlite.JDBC");var jdbc=new JdbcTemplate(source);
    try(var connection=source.getConnection()){for(String migration:List.of("000000_init","000002_knowledge_folder_path","000003_knowledge_base_auto_tag_config","000004_memory","000007_system_admin_and_settings","000008_processing_spans_and_pending_subtasks","000010_knowledge_multi_tags","000011_principal_model","000021_knowledge_profiles","000031_model_catalog_config","000033_chunk_source_locators"))ScriptUtils.executeSqlScript(connection,new FileSystemResource("../migrations/sqlite/"+migration+".up.sql"));}
    var rows=c.path("rows").fields();while(rows.hasNext()){var table=rows.next();for(var row:table.getValue()){var keys=new ArrayList<String>();var args=new ArrayList<Object>();row.fields().forEachRemaining(e->{keys.add(e.getKey());boolean blob=strings(c.path("sql_blobs").path(table.getKey()).path(row.path("id").asText())).contains(e.getKey());args.add(blob?e.getValue().asText().getBytes(java.nio.charset.StandardCharsets.UTF_8):e.getValue().isNull()?null:e.getValue().isBoolean()?e.getValue().booleanValue():e.getValue().isNumber()?e.getValue().numberValue():e.getValue().asText());});jdbc.update("INSERT INTO "+table.getKey()+"("+String.join(",",keys)+") VALUES("+String.join(",",Collections.nCopies(keys.size(),"?"))+")",args.toArray());}}
    jdbc.update("INSERT INTO tenants(id,name,business,storage_engine_config) VALUES(1,'caller','fixture','{}'),(2,'shared','fixture','{}')");
    var requests=Collections.synchronizedList(new ArrayList<JsonNode>());var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/v1/",exchange->{try{requests.add(json.readTree(exchange.getRequestBody()));String kb=c.path("graphs").get(0).path("kb").asText();switch(name){case "share-revoked-during-chat"->jdbc.update("UPDATE kb_shares SET deleted_at=CURRENT_TIMESTAMP");case "owner-changed-during-chat"->jdbc.update("UPDATE knowledge_bases SET tenant_id=9 WHERE id=?",kb);case "extract-disabled-during-chat"->jdbc.update("UPDATE knowledge_bases SET extract_config='{\"enabled\":false}' WHERE id=?",kb);}int status=name.equals("provider-failure")?400:200;JsonNode reply=status==400?json.createObjectNode().set("error",json.createObjectNode().put("message","fixture rejection")):json.createObjectNode().set("choices",json.createArrayNode().add(json.createObjectNode().set("message",json.createObjectNode().put("role","assistant").put("content",c.path("provider_output").asText()))));byte[] data=json.writeValueAsBytes(reply);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,data.length);try(var out=exchange.getResponseBody()){out.write(data);}}finally{exchange.close();}});server.start();
    var environment=new MockEnvironment().withProperty("SSRF_WHITELIST","127.0.0.1").withProperty("SSRF_DNS_WHITELIST_ONLY","true");var models=new ModelClientFactory(jdbc,json,new GoSecretCodec(""),new ModelCatalogRuntime(jdbc,"","/nonexistent"),new ModelEgressPolicy(jdbc,json,environment),environment);var graph=new Neo4jGraphRepository(driver,json);
    var ownedDocs=new ArrayList<String>();try{
      for(var seed:c.path("graphs")){String kb=seed.path("kb").asText(),doc=seed.path("doc").asText();ownedDocs.add(doc);try(var s=driver.session()){s.run("MATCH(n {kg:$doc}) DETACH DELETE n",Map.of("doc",doc)).consume();}var nodes=new ArrayList<GraphExtractionText.Node>();for(var n:seed.path("graph").path("node"))nodes.add(new GraphExtractionText.Node(n.path("name").asText(),strings(n.path("attributes")),strings(n.path("chunks"))));var relations=new ArrayList<GraphExtractionText.Relation>();for(var r:seed.path("graph").path("relation"))relations.add(new GraphExtractionText.Relation(r.path("node1").asText(),r.path("node2").asText(),r.path("type").asText()));try(var call=CallContext.timeout(Duration.ofSeconds(20))){graph.add(kb,doc,new GraphExtractionText.Graph(nodes,relations),call,()->{});}}
      String kb=c.path("graphs").get(0).path("kb").asText();var parameters=json.createObjectNode().put("provider","generic").put("base_url","http://127.0.0.1:"+server.getAddress().getPort()+"/v1").put("api_key","synthetic-key");jdbc.update("INSERT INTO models(id,tenant_id,name,type,source,parameters,is_builtin,status) VALUES('query-model',1,'graph-query-fixture','KnowledgeQA','remote',?,false,'active')",parameters.toString());
      boolean apiKey=name.contains("api-key-");var capabilities= name.equals("api-key-retrieve-only")?Set.of("retrieve"):name.endsWith("chat-only")?Set.of("chat"):name.equals("api-key-full-access")?Set.<String>of():Set.of("chat","retrieve");var caller=new AuthContext("caller",1,"viewer",false,apiKey?7L:null,name.equals("api-key-full-access"),apiKey?capabilities:Set.of(),apiKey?Set.of(name.equals("api-key-denied")?kb+"-other":kb):Set.of());
      var scope=new GraphQueryScope(new KnowledgeBaseReadAuthorization(jdbc,new KnowledgeBaseCodec(json),json,Set.of()),jdbc);var targets=new ArrayList<GraphQueryScope.Target>();for(var t:c.path("targets"))targets.add(new GraphQueryScope.Target(t.path("knowledge_base_id").asText(),t.path("tenant_id").asLong(),t.path("type").asText().equals("knowledge_base"),new HashSet<>(strings(t.path("knowledge_ids"))),new HashSet<>(strings(t.path("tag_ids")))));
      var request=new GraphQuerySearch.Request(c.path("query").asText(),c.path("rewrite").asText(),c.path("intent").asText(),c.path("web_search").asBoolean(),"query-model",targets,name.equals("agent-grant")?kb+"-agent":"","2");GraphExtractionStore effective=name.equals("neo4j-disabled")?new Neo4jGraphRepository(null,json):graph;
      var search=new GraphQuerySearch(effective,scope,models::chat,new GraphQueryText(json,c.path("template")),new GraphQueryHydration(jdbc,json,scope));
      List<ObjectNode> existing=null;if(!c.path("existing").isNull()){existing=new ArrayList<>();for(var row:c.path("existing"))existing.add((ObjectNode)row);}
      try(var call=CallContext.timeout(Duration.ofSeconds(20))){var entities=search.extract(caller,request,call);assertEquals(c.path("entities"),entities.extracted()?json.valueToTree(entities.names()):NullNode.instance,"entity parser and successful-empty vs skipped/null");var found=search.search(caller,request,entities,existing,call);assertEquals(numbers(c.path("model_http")),numbers(json.valueToTree(requests)),"actual provider messages/options");assertEquals(numbers(c.path("results")),numbers(json.valueToTree(found.results())),"complete hydrated SearchResult JSON incl null/metadata/locators/image_info");assertEquals(c.path("graph"),graphJson(found.graph()),"actual graph namespace/order/provenance");assertEquals(c.path("search_error").asText().contains("search_nothing"),found.searchNothing(),"Go soft error decision");}
    }finally{models.close();server.stop(0);try(var s=driver.session()){s.run("MATCH(n) WHERE n.kg IN $docs DETACH DELETE n",Map.of("docs",ownedDocs)).consume();}}
  }
  private JsonNode graphJson(GraphExtractionText.Graph graph){if(graph==null)return NullNode.instance;var out=json.createObjectNode();if(!graph.nodes().isEmpty()){var nodes=json.createArrayNode();for(var n:graph.nodes()){var row=json.createObjectNode().put("name",n.name());if(!n.attributes().isEmpty())row.set("attributes",json.valueToTree(n.attributes()));if(!n.chunks().isEmpty())row.set("chunks",json.valueToTree(n.chunks()));nodes.add(row);}out.set("node",nodes);}if(!graph.relations().isEmpty()){var rows=json.createArrayNode();for(var r:graph.relations())rows.add(json.createObjectNode().put("node1",r.source()).put("node2",r.target()).put("type",r.type()));out.set("relation",rows);}return out;}
  static List<String> strings(JsonNode values){var out=new ArrayList<String>();values.forEach(v->out.add(v.asText()));return out;}
  private JsonNode numbers(JsonNode n){if(n.isNumber())return DecimalNode.valueOf(n.decimalValue().stripTrailingZeros());if(n.isArray()){var out=json.createArrayNode();n.forEach(v->out.add(numbers(v)));return out;}if(n.isObject()){var out=json.createObjectNode();n.fields().forEachRemaining(e->out.set(e.getKey(),numbers(e.getValue())));return out;}return n;}
}
