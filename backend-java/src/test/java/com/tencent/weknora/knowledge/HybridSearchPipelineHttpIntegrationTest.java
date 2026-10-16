package com.tencent.weknora.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import com.tencent.weknora.retrieval.*;
import com.tencent.weknora.security.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** AuthFilter + MVC Viewer + production beans + actual Hikari1 SQLite vec/FTS/provider I/O. */
@SpringBootTest(properties={"weknora.jwt-secret=isolated-public-hybrid-secret","weknora.bridge.enabled=false","weknora.knowledge.processing.enabled=false","weknora.tasks.enabled=false","spring.datasource.driver-class-name=org.sqlite.JDBC","spring.datasource.hikari.maximum-pool-size=1","NEO4J_ENABLE=false","RETRIEVE_DRIVER=sqlite","SSRF_WHITELIST=127.0.0.1","SSRF_DNS_WHITELIST_ONLY=true","logging.level.root=WARN"})
@AutoConfigureMockMvc
class HybridSearchPipelineHttpIntegrationTest {
  static final Path directory;static{try{NativeDatabaseRuntime.initialize();directory=Files.createTempDirectory("weknora-java-public-hybrid-spring-");try(var c=java.sql.DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("test.db"))){for(var m:List.of("000000_init","000002_knowledge_folder_path","000003_knowledge_base_auto_tag_config","000007_system_admin_and_settings","000008_processing_spans_and_pending_subtasks","000010_knowledge_multi_tags","000011_principal_model","000021_knowledge_profiles","000031_model_catalog_config","000033_chunk_source_locators"))ScriptUtils.executeSqlScript(c,new FileSystemResource("../migrations/sqlite/"+m+".up.sql"));}}catch(Exception e){throw new ExceptionInInitializerError(e);}}
  @DynamicPropertySource static void properties(DynamicPropertyRegistry p){p.add("spring.datasource.url",()->"jdbc:sqlite:"+directory.resolve("test.db"));p.add("weknora.sqlite.vec.extension",()->System.getProperty("weknora.sqlite.vec.extension"));}
  @Autowired JdbcTemplate jdbc;@Autowired MockMvc mvc;@Autowired JwtCodec jwt;@Autowired ObjectMapper json;@Autowired javax.sql.DataSource source;
  String kb,doc,chunk,ownerToken,callerToken,foreignToken;HttpServer server;AtomicInteger calls;
  @BeforeEach void setup()throws Exception{kb="hybrid-spring-"+UUID.randomUUID();doc=kb+"-doc";chunk=doc+"-chunk";calls=new AtomicInteger();for(long tenant:List.of(1L,2L,9L))jdbc.update("INSERT INTO tenants(id,name,business,storage_engine_config) VALUES(?,?,'fixture','{}') ON CONFLICT(id) DO NOTHING",tenant,"Tenant"+tenant);for(var entry:Map.of("hybrid-caller",1L,"hybrid-owner",2L,"hybrid-foreign",9L).entrySet()){jdbc.update("INSERT INTO users(id,username,email,password_hash,tenant_id) VALUES(?,?,?,'unused',?) ON CONFLICT(id) DO NOTHING",entry.getKey(),entry.getKey(),entry.getKey()+"@example.invalid",entry.getValue());jdbc.update("INSERT INTO tenant_members(user_id,tenant_id,role,status) VALUES(?,?,'viewer','active') ON CONFLICT(user_id,tenant_id) DO UPDATE SET role='viewer'",entry.getKey(),entry.getValue());}
    jdbc.update("INSERT INTO knowledge_bases(id,tenant_id,name,type,embedding_model_id,summary_model_id) VALUES(?,2,'Hybrid KB','document',?,'')",kb,kb+"-model");jdbc.update("INSERT INTO knowledges(id,tenant_id,knowledge_base_id,title,type,source,custom_metadata) VALUES(?,2,?,'Public source','file','manual','{}')",doc,kb);jdbc.update("INSERT INTO chunks(id,tenant_id,knowledge_id,knowledge_base_id,chunk_type,content,is_enabled,chunk_index,start_at,end_at,seq_id) VALUES(?,2,?,?,'text','refund policy details',true,0,0,21,COALESCE((SELECT MAX(seq_id)+1 FROM chunks),1))",chunk,doc,kb);
    jdbc.update("INSERT INTO organizations(id,name,owner_id,owner_tenant_id) VALUES(?,'Sharing','hybrid-owner',2)",kb);for(int tenant:List.of(1,2))jdbc.update("INSERT INTO organization_tenant_members(id,organization_id,tenant_id,role) VALUES(?,?,?,'viewer')",kb+tenant,kb,tenant);jdbc.update("INSERT INTO kb_shares(id,knowledge_base_id,organization_id,source_tenant_id,permission,shared_by_user_id) VALUES(?,?,?,2,'viewer','hybrid-owner')",kb,kb,kb);
    server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/v1/embeddings",exchange->{try{json.readTree(exchange.getRequestBody());calls.incrementAndGet();byte[] bytes="{\"data\":[{\"index\":0,\"embedding\":[1,0]}]}".getBytes();exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}}finally{exchange.close();}});server.start();var params=json.createObjectNode().put("provider","generic").put("base_url","http://127.0.0.1:"+server.getAddress().getPort()+"/v1");params.set("embedding_parameters",json.createObjectNode().put("dimension",2));jdbc.update("INSERT INTO models(id,tenant_id,name,type,source,parameters,status) VALUES(?,2,'unknown','Embedding','remote',?,'active')",kb+"-model",params.toString());
    var index=new SQLiteIndexRepository(source,new SQLiteVectorExtension(Path.of(System.getProperty("weknora.sqlite.vec.extension"))));index.initialize();index.prepare(Set.of(2));try(var c=source.getConnection()){c.setAutoCommit(false);index.batchSave(c,List.of(new IndexEntry(chunk,0,chunk,doc,kb,"","refund policy details",true)),Map.of(chunk,new float[]{1,0}));c.commit();}ownerToken=token("hybrid-owner",2);callerToken=token("hybrid-caller",1);foreignToken=token("hybrid-foreign",9);
  }
  @AfterEach void cleanup(){if(server!=null)server.stop(0);}
  String token(String user,long tenant){String token=jwt.sign(Map.of("user_id",user,"tenant_id",tenant,"type","access","exp",Instant.now().plusSeconds(3600).getEpochSecond()));jdbc.update("INSERT INTO auth_tokens(id,user_id,token,token_type,expires_at,is_revoked) VALUES(?,?,?,'access_token',?,false)",UUID.randomUUID().toString(),user,token,Instant.now().plusSeconds(3600).toString());return token;}
  String key(String capability,String scope,boolean full){String key="hybrid-key-"+UUID.randomUUID();jdbc.update("INSERT INTO tenant_api_keys(tenant_id,name,key_hash,api_key,full_access,capabilities,knowledge_base_ids) VALUES(1,'Hybrid key',?,?,?,?,?)",AuthFilter.hashApiKey(key),key,full,"[\""+capability+"\"]","[\""+scope+"\"]");return key;}
  JsonNode request(String method,String credential,boolean machine,String body,String query,int status)throws Exception{var builder=MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method),URI.create("/api/v1/knowledge-bases/"+kb+"/hybrid-search"+query)).contentType("application/json").content(body).header(machine?"X-API-Key":"Authorization",machine?credential:"Bearer "+credential);var response=mvc.perform(builder).andReturn().getResponse();assertEquals(status,response.getStatus(),response.getContentAsString());return json.readTree(response.getContentAsByteArray());}
  @Test void actualSharedViewerAndOwnViewerRetrievePublicDtoUsingOwnerModel()throws Exception{for(String token:List.of(ownerToken,callerToken)){var data=request("POST",token,false,"{\"query_text\":\"refund\"}","",200).path("data");assertEquals(chunk,data.get(0).path("id").asText());assertEquals(kb,data.get(0).path("knowledge_base_id").asText());assertFalse(data.get(0).has("context_header"));}assertEquals(2,calls.get());}
  @Test void getReadsJsonBodyAndQueryOnlyFailsBeforeModel()throws Exception{request("GET",callerToken,false,"{\"query_text\":\"refund\"}","",200);request("GET",callerToken,false,"","?query_text=refund",400);assertEquals(1,calls.get());}
  @Test void actualRetrieveChatFullCapabilitiesAndKbAllowlistRemainDistinct()throws Exception{request("POST",key("retrieve",kb,false),true,"{\"query_text\":\"refund\"}","",200);request("POST",key("chat",kb,false),true,"{","",403);request("POST",key("chat",kb,true),true,"{\"query_text\":\"refund\"}","",200);var denied=request("POST",key("retrieve","foreign",false),true,"{","",403);assertEquals(1002,denied.path("error").path("code").asInt());assertEquals(2,calls.get());}
  @Test void foreignAndRevokedSharedGrantRejectBeforeMalformedBody()throws Exception{request("POST",foreignToken,false,"{","",403);jdbc.update("UPDATE kb_shares SET deleted_at=CURRENT_TIMESTAMP WHERE id=?",kb);request("GET",callerToken,false,"{","",403);assertEquals(0,calls.get());}
  @Test void scopedPublicUrlsRejectBeforeProvider()throws Exception{var denied=request("GET",key("retrieve",kb,false),true,"{\"query_text\":\"refund\"}","?resource_urls=public",403);assertEquals(1002,denied.path("error").path("code").asInt());assertEquals(0,calls.get());}
  @Test void missingBoundStoreRejectsAndDoesNotQueryTheUnboundIndex()throws Exception{jdbc.update("UPDATE knowledge_bases SET vector_store_id='not-native' WHERE id=?",kb);var denied=request("POST",callerToken,false,"{\"query_text\":\"refund\"}","",400);assertEquals(2200,denied.path("error").path("code").asInt());assertEquals("vector store bound to the knowledge base is not available",denied.path("error").path("message").asText());}
}
