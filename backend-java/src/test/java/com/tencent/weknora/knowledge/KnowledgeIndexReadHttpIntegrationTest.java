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

@org.springframework.boot.test.context.SpringBootTest(properties={"weknora.jwt-secret=isolated-index-read-secret","weknora.bridge.enabled=false","weknora.retrieve-driver=sqlite","logging.level.root=WARN","debug=false"})
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class KnowledgeIndexReadHttpIntegrationTest {
  static final Path DATABASE;
  static {try {DATABASE=Files.createTempFile("weknora-index-read-",".db");DATABASE.toFile().deleteOnExit();}catch(Exception ex){throw new ExceptionInInitializerError(ex);}}
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

  @Test void completeContentActualVectorsAliasesAndScopedWiki() throws Exception {
    var detail=request(detailPath(),200).path("data");assertEquals("Current body",detail.path("chunk").path("content").asText());assertEquals("Parser body",detail.path("source_content").asText());assertEquals("Heading",detail.path("context_header").asText());assertEquals("Visible embedding",detail.path("model_name").asText());assertFalse(detail.toString().contains("NEVER-EXPOSE-SECRET"));assertEquals(1,detail.path("children").size());
    var vector=index(detail,"vector");assertEquals("completed",vector.path("status").asText());assertEquals(2,vector.path("records").size());assertEquals(1,vector.path("records").get(0).path("embedding").get(0).asDouble());assertEquals("Actual index text",vector.path("records").get(0).path("content").asText());assertFalse(vector.path("records").get(1).path("is_enabled").asBoolean());
    var questions=index(detail,"questions");assertEquals("completed",questions.path("status").asText());assertTrue(questions.path("questions").get(0).path("keyword_indexed").asBoolean());
    assertEquals(2,index(detail,"wiki").path("pages").size());assertEquals("chunk",index(detail,"wiki").path("pages").get(0).path("scope").asText());assertEquals("document",index(detail,"wiki").path("pages").get(1).path("scope").asText());assertEquals("unknown",index(detail,"graph").path("status").asText());
    var status=request(statusPath(),200).path("data");assertEquals(2,status.path("attempt").asInt());assertEquals("completed",index(status,"vector").path("status").asText());assertEquals(1,index(status,"vector").path("count").asInt());assertEquals(1,index(status,"questions").path("indexed_count").asInt());assertEquals(3,index(status,"wiki").path("count").asInt());assertTrue(index(status,"graph").path("count").isNull());
  }

  @Test void absentPhysicalIndexesAreNotCountedAndMissingAliasesArePartialFailure() throws Exception {
    jdbc.execute("DELETE FROM lite_embeddings_fts WHERE rowid=(SELECT id FROM lite_embeddings WHERE source_id='chunk-q')");
    var detail=request(detailPath(),200).path("data");assertEquals("partial_failed",index(detail,"questions").path("status").asText());assertFalse(index(detail,"questions").path("questions").get(0).path("keyword_indexed").asBoolean());
    var report=request(statusPath(),200).path("data");assertEquals(0,index(report,"questions").path("indexed_count").asInt());
    try(var c=datasource.getConnection()) {new SQLiteVectorExtension(Path.of(System.getProperty("weknora.sqlite.vec.extension"))).load(c);try(var s=c.createStatement()){s.execute("DELETE FROM vec_embeddings_2 WHERE rowid=(SELECT id FROM lite_embeddings WHERE source_id='chunk')");}}
    assertEquals("stale",index(request(detailPath(),200).path("data"),"vector").path("status").asText());assertEquals(0,index(request(statusPath(),200).path("data"),"vector").path("count").asInt());
  }

  @Test void statsFailuresRemainUnknownAndHistoryCannotProveSuccess() throws Exception {
    jdbc.execute("DROP TABLE lite_embeddings_fts");var detail=request(detailPath(),200).path("data");assertFalse(index(detail,"keywords").path("available").asBoolean());assertEquals("unknown",index(detail,"questions").path("status").asText());
    var report=request(statusPath(),200).path("data");assertTrue(index(report,"keywords").path("count").isNull());assertEquals("unknown",index(report,"keywords").path("status").asText());
    jdbc.execute("DROP TABLE knowledge_processing_spans");report=request(statusPath(),200).path("data");assertEquals(0,report.path("attempt").asInt());assertEquals("unknown",index(report,"vector").path("status").asText());
  }

  @Test void questionContentRevisionAndTypedMetadataErrors() throws Exception {
    jdbc.update("UPDATE chunks SET content_revision=3 WHERE id='chunk'");assertEquals("stale",index(request(detailPath(),200).path("data"),"questions").path("status").asText());assertEquals("stale",index(request(statusPath(),200).path("data"),"questions").path("status").asText());
    jdbc.update("UPDATE chunks SET metadata=? WHERE id='chunk'","{\"generated_questions\":[{\"id\":1,\"question\":\"bad\"}]}");assertFalse(index(request(detailPath(),200).path("data"),"questions").path("available").asBoolean());assertTrue(index(request(statusPath(),200).path("data"),"questions").path("count").isNull());
  }

  void share() {
    jdbc.update("UPDATE knowledge_bases SET tenant_id=8 WHERE id='kb'");jdbc.update("UPDATE knowledges SET tenant_id=8 WHERE id='doc'");jdbc.update("UPDATE chunks SET tenant_id=8 WHERE knowledge_id='doc'");jdbc.update("UPDATE models SET tenant_id=8 WHERE id='embedding'");jdbc.update("UPDATE wiki_pages SET tenant_id=8 WHERE tenant_id=7");
    jdbc.update("INSERT INTO organizations(id,name,owner_tenant_id,owner_id) VALUES('org','Org',8,'unused')");jdbc.update("INSERT INTO organization_tenant_members(id,organization_id,tenant_id,role) VALUES('source','org',8,'viewer'),('target','org',7,'viewer')");jdbc.update("INSERT INTO kb_shares(id,knowledge_base_id,organization_id,source_tenant_id,permission,shared_by_user_id) VALUES('share','kb','org',8,'viewer','unused')");
    jdbc.update("UPDATE tenants SET retriever_engines=? WHERE id=7","[{\"retriever_type\":\"vector\",\"retriever_engine_type\":\"qdrant\"},{\"retriever_type\":\"keywords\",\"retriever_engine_type\":\"qdrant\"}]".getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }
  @Test void sharedViewerUsesStorageOwnerAndDoesNotPromoteWritePermission() throws Exception {
    share();assertEquals("completed",index(request(detailPath(),200).path("data"),"vector").path("status").asText());assertEquals(1,index(request(statusPath(),200).path("data"),"vector").path("count").asInt());
    jdbc.update("DELETE FROM organization_tenant_members WHERE id='source'");request(statusPath(),403);request(detailPath(),403);
  }

  @Test void chunkAndDocumentOwnerKbAndSoftDeletionIsolation() throws Exception {
    jdbc.update("UPDATE chunks SET tenant_id=8 WHERE id='chunk'");request(detailPath(),404);
    jdbc.update("UPDATE chunks SET tenant_id=7,knowledge_id='another' WHERE id='chunk'");request(detailPath(),404);
    jdbc.update("UPDATE chunks SET knowledge_id='doc',knowledge_base_id='another' WHERE id='chunk'");request(detailPath(),404);
    jdbc.update("UPDATE chunks SET knowledge_base_id='kb',deleted_at=CURRENT_TIMESTAMP WHERE id='chunk'");request(detailPath(),404);
    jdbc.update("UPDATE knowledges SET tenant_id=8 WHERE id='doc'");request(statusPath(),403);request(detailPath(),403);
  }

  @Test void apiKeyScopeCapabilityAndSharedParentAuthorization() throws Exception {
    String key="sk-isolated-index-read";jdbc.update("INSERT INTO tenant_api_keys(tenant_id,name,key_hash,api_key,full_access,capabilities,knowledge_base_ids) VALUES(7,'Reader',?,?,false,?,?)",AuthFilter.hashApiKey(key),key,"[\"retrieve\"]","[\"kb\"]");
    for(String path:List.of(statusPath(),detailPath()))assertEquals(200,mvc.perform(get(path).header("X-API-Key",key)).andReturn().getResponse().getStatus());
    jdbc.update("UPDATE tenant_api_keys SET capabilities='[\"ingest\"]'");for(String path:List.of(statusPath(),detailPath()))assertEquals(403,mvc.perform(get(path).header("X-API-Key",key)).andReturn().getResponse().getStatus());
    jdbc.update("UPDATE tenant_api_keys SET capabilities='[\"retrieve\"]',knowledge_base_ids='[\"other\"]'");for(String path:List.of(statusPath(),detailPath()))assertEquals(403,mvc.perform(get(path).header("X-API-Key",key)).andReturn().getResponse().getStatus());
    jdbc.update("UPDATE tenant_api_keys SET knowledge_base_ids='[\"kb\"]'");share();for(String path:List.of(statusPath(),detailPath()))assertEquals(200,mvc.perform(get(path).header("X-API-Key",key)).andReturn().getResponse().getStatus());
  }

  @Test void unknownExternalAndForeignBoundStoresDoNotFallBackToDefaultDatabase() throws Exception {
    jdbc.update("UPDATE tenants SET retriever_engines=? WHERE id=7","[{\"retriever_type\":\"vector\",\"retriever_engine_type\":\"qdrant\"},{\"retriever_type\":\"keywords\",\"retriever_engine_type\":\"qdrant\"}]".getBytes(java.nio.charset.StandardCharsets.UTF_8));assertTrue(index(request(statusPath(),200).path("data"),"vector").path("count").isNull());assertFalse(index(request(detailPath(),200).path("data"),"vector").path("available").asBoolean());
    jdbc.update("INSERT INTO vector_stores(id,tenant_id,name,engine_type,connection_config) VALUES('foreign',8,'Foreign','postgres',?)","{\"use_default_connection\":true}");jdbc.update("UPDATE knowledge_bases SET vector_store_id='foreign' WHERE id='kb'");assertFalse(index(request(detailPath(),200).path("data"),"keywords").path("available").asBoolean());
    jdbc.update("UPDATE knowledge_bases SET vector_store_id='__env_sqlite__' WHERE id='kb'");assertEquals("completed",index(request(detailPath(),200).path("data"),"vector").path("status").asText());
  }

  @Test void buildingAndConfigurationOverridesUseEvidenceRatherThanParseCompletion() throws Exception {
    jdbc.update("UPDATE chunks SET index_status='processing' WHERE id='chunk'");assertTrue(request(detailPath(),200).path("data").path("refreshing").asBoolean());assertEquals("building",index(request(detailPath(),200).path("data"),"vector").path("status").asText());
    jdbc.update("UPDATE knowledges SET parse_status='finalizing' WHERE id='doc'");jdbc.update("UPDATE knowledge_processing_spans SET status='running' WHERE name='embedding'");assertEquals("building",index(request(statusPath(),200).path("data"),"vector").path("status").asText());
    jdbc.update("UPDATE knowledges SET metadata=? WHERE id='doc'","{\"process_overrides\":{\"question_generation_config\":{\"enabled\":false}}}");assertFalse(index(request(detailPath(),200).path("data"),"questions").path("enabled").asBoolean());
  }

  @Test void missingOperatorVectorLibraryDoesNotSuppressAvailableKeywordStatistics() {
    var reader=new KnowledgeIndexReadRepository(jdbc,json,new org.springframework.mock.env.MockEnvironment(),content);
    assertThrows(RuntimeException.class,()->reader.counts("sqlite","kb","doc","vector"));
    assertEquals(1,reader.counts("sqlite","kb","doc","keywords").chunks());
    assertEquals(2,reader.records("sqlite","kb","doc","chunk","keywords").size());
  }
}
