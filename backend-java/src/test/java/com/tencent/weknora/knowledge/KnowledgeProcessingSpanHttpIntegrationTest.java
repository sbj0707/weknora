package com.tencent.weknora.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.security.*;
import com.tencent.weknora.retrieval.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.web.servlet.MockMvc;

@org.springframework.boot.test.context.SpringBootTest(properties={"weknora.jwt-secret=isolated-processing-span-secret","weknora.bridge.enabled=false","weknora.retrieve-driver=sqlite","logging.level.root=WARN","debug=false"})
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class KnowledgeProcessingSpanHttpIntegrationTest {
  static final Path DATABASE;
  static {try {DATABASE=Files.createTempFile("weknora-processing-spans-",".db");DATABASE.toFile().deleteOnExit();}catch(Exception ex){throw new ExceptionInInitializerError(ex);}}
  @org.springframework.test.context.DynamicPropertySource static void properties(org.springframework.test.context.DynamicPropertyRegistry p) {
    p.add("spring.datasource.url",()->"jdbc:sqlite:"+DATABASE);p.add("spring.datasource.driver-class-name",()->"org.sqlite.JDBC");p.add("spring.datasource.hikari.maximum-pool-size",()->2);
    p.add("weknora.sqlite.vec.extension",()->System.getProperty("weknora.sqlite.vec.extension",System.getenv("WEKNORA_SQLITE_VEC_EXTENSION")));
  }
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired javax.sql.DataSource datasource;
  @Autowired JwtCodec jwt;
  @Autowired ObjectMapper json;
  @Autowired KnowledgeContentReadRepository content;
  @Autowired KnowledgeProcessingSpanController spanController;
  @Autowired @org.springframework.beans.factory.annotation.Qualifier("nativeRoleGuard") org.springframework.web.servlet.HandlerInterceptor roleGuard;
  String token;

  @BeforeEach void setup() throws Exception {
    for(String table:jdbc.queryForList("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'vec_%' AND name NOT LIKE 'lite_embeddings_fts%'",String.class))jdbc.execute("DROP TABLE \""+table.replace("\"","\"\"")+"\"");
    // Drop virtual tables through a loaded connection before resetting schemas.
    try(var c=datasource.getConnection()) {
      var ext=new SQLiteVectorExtension(Path.of(System.getProperty("weknora.sqlite.vec.extension")));ext.load(c);
      try(var s=c.createStatement()) {s.execute("DROP TABLE IF EXISTS vec_embeddings_2");s.execute("DROP TABLE IF EXISTS lite_embeddings_fts");}
      for(String script:List.of("000000_init.up.sql","000007_system_admin_and_settings.up.sql","000008_processing_spans_and_pending_subtasks.up.sql","000002_knowledge_folder_path.up.sql","000021_knowledge_profiles.up.sql","000033_chunk_source_locators.up.sql"))ScriptUtils.executeSqlScript(c,new FileSystemResource("../migrations/sqlite/"+script));
    }
    jdbc.update("INSERT INTO tenants(id,name,business,retriever_engines) VALUES(7,'Caller','test',?), (8,'Owner','test',?)","[]".getBytes(java.nio.charset.StandardCharsets.UTF_8),"{\"engines\":[{\"retriever_type\":\"vector\",\"retriever_engine_type\":\"sqlite\"},{\"retriever_type\":\"keywords\",\"retriever_engine_type\":\"sqlite\"}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    jdbc.update("INSERT INTO users(id,username,email,password_hash,tenant_id) VALUES('caller','Caller','index-reader@example.com','unused',7)");
    jdbc.update("INSERT INTO tenant_members(user_id,tenant_id,role,status) VALUES('caller',7,'viewer','active')");
    jdbc.update("INSERT INTO knowledge_bases(id,name,tenant_id,creator_id,type,embedding_model_id,summary_model_id,indexing_strategy,question_generation_config) VALUES('kb','KB',7,'caller','document','','',?,?)","{\"vector_enabled\":true,\"keyword_enabled\":true,\"wiki_enabled\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8),"{\"enabled\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    jdbc.update("INSERT INTO knowledges(id,tenant_id,knowledge_base_id,type,title,source,parse_status,embedding_model_id) VALUES('doc',7,'kb','file','Document','test','completed','embedding')");
    jdbc.update("INSERT INTO models(id,tenant_id,name,display_name,type,source,status,parameters) VALUES('embedding',7,'embed','Visible embedding','Embedding','remote','active',?)","{\"api_key\":\"NEVER-EXPOSE-SECRET\"}");
    jdbc.update("INSERT INTO chunks(id,tenant_id,knowledge_base_id,knowledge_id,content,source_content,context_header,content_revision,chunk_type,index_status,metadata,chunk_index,start_at,end_at) VALUES('chunk',7,'kb','doc','Current body','Parser body','Heading',2,'text','ready',?,0,0,12)","{\"generated_questions\":[{\"id\":\"q\",\"question\":\"Why?\",\"content_revision\":2}]}");
    jdbc.update("INSERT INTO chunks(id,tenant_id,knowledge_base_id,knowledge_id,content,parent_chunk_id,chunk_type,chunk_index,start_at,end_at) VALUES('ocr',7,'kb','doc','OCR body','chunk','image_ocr',1,0,8)");
    var repo=new SQLiteIndexRepository(datasource,new SQLiteVectorExtension(Path.of(System.getProperty("weknora.sqlite.vec.extension"))));repo.initialize();repo.prepare(Set.of(2));
    try(var c=datasource.getConnection()){c.setAutoCommit(false);repo.batchSave(c,List.of(new IndexEntry("chunk",0,"chunk","doc","kb","","Actual index text",true),new IndexEntry("chunk-q",0,"chunk","doc","kb","","Why?",false)),Map.of("chunk",new float[]{1,0},"chunk-q",new float[]{0,1}));c.commit();}
    jdbc.update("INSERT INTO knowledge_processing_spans(knowledge_id,attempt,span_id,name,kind,status,input,output,finished_at,updated_at) VALUES('doc',2,'embedding','embedding','stage','done',?,?,?,?)","{\"vector_enabled\":true,\"keyword_enabled\":true,\"model_id\":\"embedding\"}","{\"vectors_written\":1}","2026-10-14 01:00:00","2026-10-14 01:00:00");
    jdbc.update("INSERT INTO knowledge_processing_spans(knowledge_id,attempt,span_id,name,kind,status,input,output,finished_at,updated_at) VALUES('doc',2,'question','postprocess.question.batch[0]','subspan','done','{}','{}','2026-10-14 01:01:00','2026-10-14 01:01:00')");
    wiki("cited","[\"doc|Document\"]","[\"chunk\"]",7);wiki("summary","[\"doc\"]","[]",7);wiki("other-chunk","[\"doc\"]","[\"ocr\"]",7);wiki("foreign","[\"doc\"]","[\"chunk\"]",8);
    token=jwt.sign(Map.of("user_id","caller","tenant_id",7,"type","access","exp",Instant.now().plusSeconds(3600).getEpochSecond()));
    jdbc.update("INSERT INTO auth_tokens(id,user_id,token,token_type,expires_at,is_revoked) VALUES(?,'caller',?,'access_token',?,false)",UUID.randomUUID().toString(),token,Instant.now().plusSeconds(3600).toString());
  }

  void wiki(String slug,String sources,String chunks,long tenant) {jdbc.update("INSERT INTO wiki_pages(id,tenant_id,knowledge_base_id,slug,title,content,source_refs,chunk_refs) VALUES(?,?,'kb',?,?,?, ?,?)",slug,tenant,slug,"Title "+slug,"Page "+slug,sources.getBytes(java.nio.charset.StandardCharsets.UTF_8),chunks.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
  JsonNode request(String path,int expected) throws Exception {var r=mvc.perform(get(path).header("Authorization","Bearer "+token)).andReturn().getResponse();assertEquals(expected,r.getStatus(),r.getContentAsString());return json.readTree(r.getContentAsByteArray());}
  JsonNode index(JsonNode report,String kind) {for(var row:report.path("indexes"))if(row.path("kind").asText().equals(kind))return row;throw new AssertionError("missing "+kind);}
  String statusPath(){return "/api/v1/knowledge/doc/index-status";}
  String detailPath(){return "/api/v1/knowledge/doc/chunks/chunk/details";}

  @Test void bothAliasesReturnSameTreeAndSecretDetailNeverEscapes() throws Exception {
    jdbc.update("UPDATE knowledge_processing_spans SET error_detail='PRIVATE_STACK_SECRET'");
    var spans=request("/api/v1/knowledge/doc/spans",200).path("data");
    var stages=request("/api/v1/knowledge/doc/stages",200).path("data");
    // Real nodes are equal; synthetic wall-clock timestamps are request-specific.
    assertEquals(spans.path("attempt"),stages.path("attempt"));assertEquals(2,spans.path("attempt").asInt());
    assertFalse(spans.toString().contains("PRIVATE_STACK_SECRET"));assertFalse(spans.toString().contains("error_detail"));
    assertEquals(5,spans.path("trace").path("children").size()-1);
  }
  @Test void realApiKeyRetrieveAndKbScopesAndForbiddenCapability() throws Exception {
    String key="sk-span-reader";
    jdbc.update("INSERT INTO tenant_api_keys(tenant_id,name,key_hash,api_key,full_access,capabilities,knowledge_base_ids) VALUES(7,'Reader',?,?,false,?,?)",AuthFilter.hashApiKey(key),key,"[\"retrieve\"]","[\"kb\"]");
    for(String suffix:List.of("spans","stages"))assertEquals(200,mvc.perform(get("/api/v1/knowledge/doc/"+suffix).header("X-API-Key",key)).andReturn().getResponse().getStatus());
    jdbc.update("UPDATE tenant_api_keys SET capabilities='[\"ingest\"]'");
    for(String suffix:List.of("spans","stages"))assertEquals(403,mvc.perform(get("/api/v1/knowledge/doc/"+suffix).header("X-API-Key",key)).andReturn().getResponse().getStatus());
    jdbc.update("UPDATE tenant_api_keys SET capabilities='[\"retrieve\"]',knowledge_base_ids='[\"other\"]'");
    for(String suffix:List.of("spans","stages"))assertEquals(403,mvc.perform(get("/api/v1/knowledge/doc/"+suffix).header("X-API-Key",key)).andReturn().getResponse().getStatus());
  }
  @Test void viewerRoleGuardIsRegisteredForBothAliases() throws Exception {
    var roles=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(spanController).addInterceptors(roleGuard).setControllerAdvice(new com.tencent.weknora.api.ApiErrorAdvice()).build();
    var caller=new AuthContext("caller",7,"unknown",false,null,false,Set.of(),Set.of());
    for(String suffix:List.of("spans","stages")) {
      var response=roles.perform(get("/api/v1/knowledge/doc/"+suffix).requestAttr(CurrentAuth.ATTRIBUTE,caller)).andReturn().getResponse();
      assertEquals(403,response.getStatus());assertEquals("Forbidden: insufficient workspace role",json.readTree(response.getContentAsByteArray()).path("error").asText());
    }
  }

  @Test void bridgeSelectorOwnsOnlyExactGetSpansAndStages() {
    for(String suffix:List.of("spans","stages")) {
      var request=new org.springframework.mock.web.MockHttpServletRequest("GET","/api/v1/knowledge/doc/"+suffix);
      assertTrue(com.tencent.weknora.bridge.GoCompatibilityBridge.ownsNative(request));
      request.setMethod("POST");assertFalse(com.tencent.weknora.bridge.GoCompatibilityBridge.ownsNative(request));
      request.setMethod("GET");request.setRequestURI("/api/v1/knowledge/doc/"+suffix+"/extra");assertFalse(com.tencent.weknora.bridge.GoCompatibilityBridge.ownsNative(request));
    }
  }
  @Test void missingHistoryProducesCompletedLegacyTimelineAndKeepsHistoricalAttempt() throws Exception {
    jdbc.execute("DELETE FROM knowledge_processing_spans");
    var result=request("/api/v1/knowledge/doc/spans?attempt=2147483648",200).path("data");
    assertEquals(2147483648L,result.path("attempt").asLong());assertEquals("done",result.path("trace").path("status").asText());assertEquals(5,result.path("trace").path("children").size());
  }
}
