package com.tencent.weknora.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import com.tencent.weknora.model.client.*;
import com.tencent.weknora.security.AuthContext;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.*;

/** Production beans, real Neo4j/model HTTP and Hikari1; session routing is not claimed. */
@SpringBootTest(properties={"weknora.jwt-secret=isolated-graph-query-secret","weknora.bridge.enabled=false","weknora.knowledge.processing.enabled=false","weknora.tasks.enabled=false","spring.datasource.driver-class-name=org.sqlite.JDBC","spring.datasource.hikari.maximum-pool-size=1","NEO4J_ENABLE=true","NEO4J_USERNAME=neo4j","NEO4J_PASSWORD=isolated-test","SSRF_WHITELIST=127.0.0.1","SSRF_DNS_WHITELIST_ONLY=true","logging.level.root=WARN"})
class GraphQueryPipelineIntegrationIT {
  static final Path directory;static {try{directory=Files.createTempDirectory("weknora-java-graph-query-spring-");}catch(Exception e){throw new ExceptionInInitializerError(e);}}
  @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){registry.add("spring.datasource.url",()->"jdbc:sqlite:"+directory.resolve("fixture.db"));registry.add("NEO4J_URI",()->Objects.requireNonNull(System.getProperty("weknora.graph.test.uri"),"isolated Neo4j required"));}
  @Autowired JdbcTemplate jdbc;@Autowired GraphQuerySearch queries;@Autowired Neo4jGraphRepository graph;@Autowired ObjectMapper json;
  String kb,doc,chunk,model;HttpServer server;AtomicInteger requests;Runnable during;CallContext active;
  @BeforeAll static void schema(@Autowired JdbcTemplate jdbc)throws Exception{try(var connection=jdbc.getDataSource().getConnection()){for(var migration:List.of("000000_init","000002_knowledge_folder_path","000003_knowledge_base_auto_tag_config","000004_memory","000007_system_admin_and_settings","000008_processing_spans_and_pending_subtasks","000010_knowledge_multi_tags","000011_principal_model","000021_knowledge_profiles","000031_model_catalog_config","000033_chunk_source_locators"))ScriptUtils.executeSqlScript(connection,new FileSystemResource("../migrations/sqlite/"+migration+".up.sql"));}jdbc.update("INSERT INTO tenants(id,name,business,storage_engine_config) VALUES(1,'caller','fixture','{}'),(2,'shared','fixture','{}')");}
  @BeforeEach void setup()throws Exception{kb="graph-query-spring-"+UUID.randomUUID();doc=kb+"-doc";chunk=doc+"-chunk";model=kb+"-model";requests=new AtomicInteger();during=()->{};
    jdbc.update("INSERT INTO knowledge_bases(id,tenant_id,name,type,embedding_model_id,summary_model_id,extract_config) VALUES(?,1,'Query graph','document','','','{\"enabled\":true}')",kb);
    jdbc.update("INSERT INTO knowledges(id,tenant_id,knowledge_base_id,title,type,source,custom_metadata) VALUES(?,1,?,'Source','file','manual','{}')",doc,kb);
    jdbc.update("INSERT INTO chunks(id,tenant_id,knowledge_id,knowledge_base_id,content,chunk_type,is_enabled,chunk_index,start_at,end_at,seq_id) VALUES(?,1,?,?,'Alice owns release','text',true,0,0,18,COALESCE((SELECT MAX(seq_id)+1 FROM chunks),1))",chunk,doc,kb);
    try(var call=CallContext.timeout(Duration.ofSeconds(20))){graph.add(kb,doc,new GraphExtractionText.Graph(List.of(new GraphExtractionText.Node("Alice",List.of(),List.of(chunk)),new GraphExtractionText.Node("Release",List.of(),List.of(chunk))),List.of(new GraphExtractionText.Relation("Alice","Release","owns"))),call,()->{});}
    server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/v1/",exchange->{try{json.readTree(exchange.getRequestBody());requests.incrementAndGet();during.run();var response=json.createObjectNode().set("choices",json.createArrayNode().add(json.createObjectNode().set("message",json.createObjectNode().put("role","assistant").put("content","[{\"entity\":\"Alice\"}]"))));byte[] body=json.writeValueAsBytes(response);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);try(var out=exchange.getResponseBody()){out.write(body);}}finally{exchange.close();}});server.start();var parameters=json.createObjectNode().put("base_url","http://127.0.0.1:"+server.getAddress().getPort()+"/v1").put("provider","generic").put("api_key","synthetic-key");jdbc.update("INSERT INTO models(id,tenant_id,name,type,source,parameters,status) VALUES(?,1,'graph-query-fixture','KnowledgeQA','remote',?,'active')",model,parameters.toString());}
  @AfterEach void cleanup(){if(active!=null)active.close();if(server!=null)server.stop(0);try(var call=CallContext.timeout(Duration.ofSeconds(20))){graph.delete(kb,doc,call,()->{});}}
  private AuthContext caller(Set<String> capabilities){return new AuthContext("caller",1,"viewer",false,7L,false,capabilities,Set.of(kb));}
  private GraphQuerySearch.Request request(long owner){return new GraphQuerySearch.Request("What does Alice own?","","kb_search",false,model,List.of(new GraphQueryScope.Target(kb,owner,false,Set.of(doc),Set.of())),"","");}
  private GraphQuerySearch.Found run(AuthContext caller,long owner){active=CallContext.timeout(Duration.ofSeconds(20));var input=request(owner);return queries.search(caller,input,queries.extract(caller,input,active),null,active);}
  private void shared(){jdbc.update("UPDATE knowledge_bases SET tenant_id=2 WHERE id=?",kb);jdbc.update("UPDATE knowledges SET tenant_id=2 WHERE id=?",doc);jdbc.update("UPDATE chunks SET tenant_id=2 WHERE id=?",chunk);jdbc.update("INSERT INTO organizations(id,name,owner_id,owner_tenant_id) VALUES(?,'Sharing','owner',2)",kb);for(int tenant:List.of(1,2))jdbc.update("INSERT INTO organization_tenant_members(id,organization_id,tenant_id,role) VALUES(?,?,?,'admin')",kb+tenant,kb,tenant);jdbc.update("INSERT INTO kb_shares(id,knowledge_base_id,organization_id,shared_by_user_id,source_tenant_id,permission) VALUES(?,?,?,'owner',2,'admin')",kb,kb,kb);}
  @Test void chatOnlyApiKeyUsesProductionGraphAndSqlBeans(){var found=run(caller(Set.of("chat")),1);assertEquals(1,requests.get());assertEquals(chunk,found.results().getFirst().path("id").asText());assertEquals(6,found.results().getFirst().path("match_type").asInt());assertFalse(found.searchNothing());}
  @Test void sharedChatOnlyRetainsOwnerRowsWithoutMintingWriteGrant(){shared();var found=run(caller(Set.of("chat")),2);assertEquals(1,requests.get());assertEquals(chunk,found.results().getFirst().path("id").asText());}
  @Test void retrieveOnlyCannotPayForGraphChat(){var found=run(caller(Set.of("retrieve")),1);assertEquals(0,requests.get());assertNull(found.graph());assertNull(found.results());}
  @Test void shareRevokedDuringActualChatReturnsNoGraphContent(){shared();during=()->jdbc.update("UPDATE kb_shares SET deleted_at=CURRENT_TIMESTAMP WHERE id=?",kb);var found=run(caller(Set.of("chat")),2);assertEquals(1,requests.get());assertNull(found.graph());assertNull(found.results());}
  @Test void canceledCallerStopsBeforeProvider(){active=new CallContext();active.cancel();assertThrows(CancellationException.class,()->queries.extract(caller(Set.of("chat")),request(1),active));assertEquals(0,requests.get());}
  @Test void expiredDeadlineStopsBeforeProvider(){active=new CallContext(Instant.now().minusSeconds(1));assertThrows(ModelClientException.class,()->queries.extract(caller(Set.of("chat")),request(1),active));assertEquals(0,requests.get());}
  @Test void actualProviderCancellationCannotPublishEntityOrChunk(){during=()->active.cancel();assertThrows(CancellationException.class,()->run(caller(Set.of("chat")),1));assertEquals(1,requests.get());}
}
