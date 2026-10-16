package com.tencent.weknora.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import com.tencent.weknora.model.*;
import com.tencent.weknora.model.client.*;
import com.tencent.weknora.retrieval.*;
import com.tencent.weknora.security.*;
import com.tencent.weknora.storage.StorageResolver;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Real production Go public handler/SQL/model/vec/FTS oracle, not mocked retrieval results. */
class HybridSearchGoHttpParityTest {
  @TempDir Path temp;final ObjectMapper json=new ObjectMapper();static GoJiebaSegmenter segmenter;
  static {try{NativeDatabaseRuntime.initialize();}catch(Exception e){throw new ExceptionInInitializerError(e);}}
  @BeforeAll static void jieba(){segmenter=new GoJiebaSegmenter(Path.of(System.getProperty("weknora.jieba.library")),Path.of(System.getProperty("weknora.jieba.dict")));}
  @AfterAll static void close(){if(segmenter!=null)segmenter.close();}
  @TestFactory Stream<DynamicTest> actualGoPublicSearch()throws Exception {JsonNode cases;try(var in=getClass().getResourceAsStream("/go-hybrid-search.json")){cases=json.readTree(in);}assertEquals(143,cases.size());var sequence=new AtomicInteger();return StreamSupport.stream(cases.spliterator(),false).map(c->DynamicTest.dynamicTest(c.path("name").asText(),()->compare(c,sequence.incrementAndGet())));}
  void compare(JsonNode oracle,int sequence)throws Exception{
    var source=database(sequence);try{var jdbc=new JdbcTemplate(source);
    schema(source);
    for(String tableName:seedTables()){var table=Map.entry(tableName,oracle.path("rows").path(tableName));for(var row:table.getValue()){var keys=new ArrayList<String>();var args=new ArrayList<Object>();row.fields().forEachRemaining(e->{keys.add(e.getKey());boolean blob=strings(oracle.path("sql_blobs").path(table.getKey()).path(row.path("id").asText())).contains(e.getKey());args.add(seedValue(e.getKey(),e.getValue(),blob));});jdbc.update("INSERT INTO "+table.getKey()+"("+String.join(",",keys)+") VALUES("+String.join(",",Collections.nCopies(keys.size(),"?"))+")",args.toArray());}}
    var rc=oracle.path("tenant_context").path("retrieval_config");if(!rc.isNull()&&!rc.isMissingNode())jdbc.update("UPDATE tenants SET retrieval_config=? WHERE id=?",rc.toString(),oracle.path("caller").asLong());
    var engines=oracle.path("tenant_context").path("retriever_engines");if(!engines.path("engines").isNull()&&!engines.path("engines").isMissingNode())jdbc.update("UPDATE tenants SET retriever_engines=? WHERE id=?",engines.toString(),oracle.path("caller").asLong());
    String name=oracle.path("name").asText();var captured=Collections.synchronizedList(new ArrayList<JsonNode>());var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/v1/",exchange->{try{var body=json.readTree(exchange.getRequestBody());captured.add(json.createObjectNode().put("path",exchange.getRequestURI().getPath()).set("body",body));boolean embedding=exchange.getRequestURI().getPath().endsWith("embeddings");int status=name.equals(embedding?"embedding-error":"rerank-error")?400:200;JsonNode reply;
      if(status==400)reply=json.createObjectNode().set("error",json.createObjectNode().put("message","fixture model rejection"));
      else if(embedding)reply=json.createObjectNode().set("data",json.createArrayNode().add(json.createObjectNode().put("index",0).set("embedding",json.createArrayNode().add(1).add(.125))));
      else{var results=json.createArrayNode();int index=0;for(var document:body.path("documents")){String text=document.asText();results.add(json.createObjectNode().put("index",index++).put("relevance_score",text.contains("beta")?.88:text.contains("alpha")?.74:.63));}reply=json.createObjectNode().set("results",results);}
      byte[] bytes=json.writeValueAsBytes(reply);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}}finally{exchange.close();}});server.start();
    for(var row:jdbc.queryForList("SELECT id,parameters FROM models")){var parameters=(ObjectNode)json.readTree(HybridSearchHydration.text(row,"parameters"));parameters.put("base_url","http://127.0.0.1:"+server.getAddress().getPort()+"/v1");jdbc.update("UPDATE models SET parameters=? WHERE id=?",seedValue("parameters",TextNode.valueOf(parameters.toString()),true),row.get("id"));}
    var environment=new MockEnvironment().withProperty("SSRF_WHITELIST","127.0.0.1").withProperty("SSRF_DNS_WHITELIST_ONLY","true").withProperty("RETRIEVE_DRIVER",driver()).withProperty("RESOURCE_URL_MODE",oracle.path("resource_default").asText()).withProperty("MULTI_STORE_RETRIEVE_TIMEOUT_SEC",oracle.path("multi_store_timeout").asText("30")).withProperty("WEKNORA_SQLITE_VEC_EXTENSION",System.getProperty("weknora.sqlite.vec.extension",""));
    var models=new ModelClientFactory(jdbc,json,new GoSecretCodec(""),new ModelCatalogRuntime(jdbc,"","/nonexistent"),new ModelEgressPolicy(jdbc,json,environment),environment);
    try{
      var entries=new ArrayList<IndexEntry>();for(var e:oracle.path("entries"))entries.add(new IndexEntry(e.path("SourceID").asText(),e.path("SourceType").asInt(),e.path("ChunkID").asText(),e.path("KnowledgeID").asText(),e.path("KnowledgeBaseID").asText(),e.path("TagID").asText(),e.path("Content").asText(),e.path("IsEnabled").asBoolean()));var vectors=new HashMap<String,float[]>();oracle.path("vectors").fields().forEachRemaining(e->{float[] v=new float[e.getValue().size()];for(int i=0;i<v.length;i++)v[i]=(float)e.getValue().get(i).asDouble();vectors.put(e.getKey(),v);});seedIndex(source,entries,vectors,oracle);
      var authorization=new KnowledgeBaseReadAuthorization(jdbc,new KnowledgeBaseCodec(json),json,Set.of());var projection=new GraphQueryHydration(jdbc,json,new GraphQueryScope(authorization,jdbc));var hydration=new HybridSearchHydration(jdbc,json,projection);var nativeStore=new HybridSearchStore(source,environment);prepareStore(nativeStore,oracle);var search=new HybridSearchService(jdbc,json,authorization,models,nativeStore,hydration,new RerankStage(segmenter));var controller=controller(authorization,search,new HybridSearchResourceURLs(new StorageResolver(jdbc,json,environment),environment),oracle);var mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new com.tencent.weknora.api.ApiErrorAdvice()).build();
      try(var boundary=boundary(source,oracle)){
      var scope=oracle.path("scope");var caller=new AuthContext("caller",oracle.path("caller").asLong(),"viewer",false,oracle.path("machine").asBoolean()?1L:null,scope.path("FullAccess").asBoolean(),new HashSet<>(strings(scope.path("Capabilities"))),new HashSet<>(strings(scope.path("KnowledgeBaseIDs"))));
      var response=mvc.perform(MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(oracle.path("method").asText()),java.net.URI.create("/api/v1/knowledge-bases/kb/hybrid-search"+oracle.path("query").asText())).requestAttr(CurrentAuth.ATTRIBUTE,caller).contentType("application/json").content(oracle.path("request").asText())).andReturn().getResponse();
      assertEquals(oracle.path("status").asInt(),response.getStatus(),response.getContentAsString());assertEquals(numbers(oracle.path("body")),numbers(json.readTree(response.getContentAsByteArray())),"complete public HTTP JSON");assertEquals(numbers(oracle.path("model_http")),numbers(json.valueToTree(captured)),"actual model HTTP request/options/order");
      }
    }finally{models.close();server.stop(0);}
    }finally{release(source);}
  }
  List<String> seedTables(){return List.of("tenants","models","knowledge_bases","knowledges","chunks","organizations","organization_tenant_members","custom_agents","agent_shares","kb_shares");}
  void seedIndex(javax.sql.DataSource source,List<IndexEntry> entries,Map<String,float[]> vectors,JsonNode oracle)throws Exception{var index=index(source);index.prepare(Set.of(2));try(var connection=source.getConnection()){connection.setAutoCommit(false);index.batchSave(connection,entries,vectors);connection.commit();}}
  void prepareStore(HybridSearchStore store,JsonNode oracle){}
  HybridSearchController controller(KnowledgeBaseReadAuthorization auth,HybridSearchService search,HybridSearchResourceURLs resources,JsonNode oracle){return new HybridSearchController(auth,search,resources);}
  AutoCloseable boundary(javax.sql.DataSource source,JsonNode oracle)throws Exception{return ()->{};}
  javax.sql.DataSource database(int sequence){var source=new DriverManagerDataSource("jdbc:sqlite:"+temp.resolve("search-"+sequence+".db"));source.setDriverClassName("org.sqlite.JDBC");return source;}
  void schema(javax.sql.DataSource source)throws Exception {try(var connection=source.getConnection()){for(String migration:List.of("000000_init","000002_knowledge_folder_path","000003_knowledge_base_auto_tag_config","000004_memory","000007_system_admin_and_settings","000008_processing_spans_and_pending_subtasks","000010_knowledge_multi_tags","000011_principal_model","000021_knowledge_profiles","000031_model_catalog_config","000033_chunk_source_locators"))ScriptUtils.executeSqlScript(connection,new FileSystemResource("../migrations/sqlite/"+migration+".up.sql"));}}
  IndexWriter index(javax.sql.DataSource source)throws Exception{var index=new SQLiteIndexRepository(source,new SQLiteVectorExtension(Path.of(System.getProperty("weknora.sqlite.vec.extension"))));index.initialize();return index;}
  Object seedValue(String key,JsonNode value,boolean blob){return blob?value.asText().getBytes(StandardCharsets.UTF_8):value.isNull()?null:value.isBoolean()?value.booleanValue():value.isNumber()?value.numberValue():value.asText();}
  String driver(){return "sqlite";}
  void release(javax.sql.DataSource source){}
  static List<String> strings(JsonNode values){var out=new ArrayList<String>();values.forEach(v->out.add(v.asText()));return out;}
  JsonNode numbers(JsonNode value){if(value.isNumber())return DecimalNode.valueOf(value.decimalValue().stripTrailingZeros());if(value.isArray()){var out=json.createArrayNode();value.forEach(n->out.add(numbers(n)));return out;}if(value.isObject()){var out=json.createObjectNode();value.fields().forEachRemaining(e->out.set(e.getKey(),numbers(e.getValue())));return out;}return value;}
}
