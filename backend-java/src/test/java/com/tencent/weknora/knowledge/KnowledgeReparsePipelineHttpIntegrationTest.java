package com.tencent.weknora.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import com.tencent.weknora.retrieval.*;
import com.tencent.weknora.security.AuthFilter;
import com.tencent.weknora.task.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Real JWT/API-key filters, owner/shared grants, SQL queue, workers and physical vec/FTS with pool one. */
@org.springframework.boot.test.context.SpringBootTest(properties={"weknora.jwt-secret=isolated-native-pipeline-trace-secret","weknora.bridge.enabled=false","weknora.knowledge.processing.enabled=true","weknora.knowledge.upload.enabled=true","weknora.tasks.enabled=true","weknora.tasks.poll-millis=3600000","weknora.retrieve-driver=sqlite","SSRF_WHITELIST=127.0.0.1","SSRF_DNS_WHITELIST_ONLY=true","DOCREADER_TRANSPORT=http","NEO4J_ENABLE=false","logging.level.root=WARN","debug=false"})
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class KnowledgeReparsePipelineHttpIntegrationTest {
  static final Path DIRECTORY,DATABASE;
  static {
    try {
      NativeDatabaseRuntime.initialize();DIRECTORY=Files.createTempDirectory("weknora-reparse-pipeline-");DATABASE=DIRECTORY.resolve("db.sqlite");
      try(var c=java.sql.DriverManager.getConnection("jdbc:sqlite:"+DATABASE)){
        for(String m:List.of("000000_init","000002_knowledge_folder_path","000003_knowledge_base_auto_tag_config","000006_task_queue_and_dead_letters","000007_system_admin_and_settings","000008_processing_spans_and_pending_subtasks","000010_knowledge_multi_tags","000011_principal_model","000021_knowledge_profiles","000031_model_catalog_config","000033_chunk_source_locators"))
          ScriptUtils.executeSqlScript(c,new FileSystemResource("../migrations/sqlite/"+m+".up.sql"));
        ScriptUtils.executeSqlScript(c,new FileSystemResource("migration/sql/java-tasks.sql"));
      }
    }catch(Exception e){throw new ExceptionInInitializerError(e);}
  }
  @org.springframework.test.context.DynamicPropertySource static void properties(org.springframework.test.context.DynamicPropertyRegistry p){
    p.add("spring.datasource.url",()->"jdbc:sqlite:"+DATABASE);p.add("spring.datasource.driver-class-name",()->"org.sqlite.JDBC");p.add("spring.datasource.hikari.maximum-pool-size",()->1);
    p.add("LOCAL_STORAGE_BASE_DIR",()->DIRECTORY.resolve("files").toString());p.add("weknora.sqlite.vec.extension",()->System.getProperty("weknora.sqlite.vec.extension"));
  }
  @Autowired AutowireCapableBeanFactory beans;
  @Autowired ManualProcessingHandler manual;
  @Autowired KnowledgeListReparseHandler batch;
  @Autowired com.tencent.weknora.docreader.RemoteDocumentReader reader;
  final KnowledgeProcessingSpanPipelineHttpIntegrationTest f=new KnowledgeProcessingSpanPipelineHttpIntegrationTest();
  HttpServer model;final List<Long> keys=new ArrayList<>();
  @BeforeEach void setup()throws Exception{
    beans.autowireBean(f);f.setup();
    f.jdbc.update("UPDATE knowledge_bases SET tenant_id=8,deleted_at=NULL,summary_model_id='',auto_tag_config='{}',profile_config='{}',question_generation_config='{}',vlm_config='{}',extract_config='{}' WHERE id='trace-kb'");
    f.jdbc.update("UPDATE tenant_members SET role='admin' WHERE user_id IN ('caller','owner')");f.jdbc.update("UPDATE kb_shares SET deleted_at=NULL,permission='editor' WHERE id='trace-share'");
    model=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);model.createContext("/v1/embeddings",e->{try{
      var input=f.json.readTree(e.getRequestBody()).path("input");var data=f.json.createArrayNode();
      for(int i=0;i<input.size();i++)data.add(f.json.createObjectNode().put("index",i).set("embedding",f.json.createArrayNode().add(input.get(i).asText().length()+1).add(1).add(2)));
      byte[] bytes=f.json.writeValueAsBytes(f.json.createObjectNode().set("data",data));e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);
    }finally{e.close();}});model.start();
    var parameters=f.json.createObjectNode().put("provider","generic").put("base_url","http://127.0.0.1:"+model.getAddress().getPort()+"/v1");parameters.set("embedding_parameters",f.json.createObjectNode().put("dimension",3));
    f.jdbc.update("UPDATE models SET parameters=? WHERE id='trace-embedding'",parameters.toString());
  }
  @AfterEach void cleanup()throws Exception{reader.reconnect("");f.jdbc.execute("DROP TRIGGER IF EXISTS reparse_queue_failure");for(long key:keys)f.jdbc.update("DELETE FROM tenant_api_keys WHERE id=?",key);model.stop(0);}
  JsonNode request(String path,String token,String body,int status)throws Exception{
    var response=f.mvc.perform(post(path).header("Authorization","Bearer "+token).contentType("application/json").content(body)).andReturn().getResponse();
    assertEquals(status,response.getStatus(),response.getContentAsString());return f.json.readTree(response.getContentAsByteArray());
  }
  @Test void collectionReadsRetainSharedOwnerAndRecheckRevocation()throws Exception {
    String id=f.upload("Collection read source");f.jdbc.update("UPDATE knowledges SET title=? WHERE id=?",id,id);
    for(String path:List.of("/api/v1/knowledge/batch?ids="+id,"/api/v1/knowledge/search?keyword="+id)) {
      var response=f.mvc.perform(get(path).header("Authorization","Bearer "+f.callerToken)).andReturn().getResponse();
      assertEquals(200,response.getStatus(),response.getContentAsString());var data=f.json.readTree(response.getContentAsByteArray()).path("data");
      assertEquals(1,data.size());assertEquals(id,data.get(0).path("id").asText());assertEquals(8,data.get(0).path("tenant_id").asInt());
      var denied=f.mvc.perform(get(path).header("Authorization","Bearer "+f.foreignToken)).andReturn().getResponse();assertEquals(200,denied.getStatus());assertTrue(f.json.readTree(denied.getContentAsByteArray()).path("data").isEmpty());
    }
    f.jdbc.update("UPDATE kb_shares SET deleted_at=CURRENT_TIMESTAMP WHERE id='trace-share'");
    for(String path:List.of("/api/v1/knowledge/batch?ids="+id,"/api/v1/knowledge/search?keyword="+id)) {
      var response=f.mvc.perform(get(path).header("Authorization","Bearer "+f.callerToken)).andReturn().getResponse();assertEquals(200,response.getStatus());assertTrue(f.json.readTree(response.getContentAsByteArray()).path("data").isEmpty());
    }
  }
  @Test void collectionApiKeysUseRetrieveAndIntersectScopesEvenWithFullAccess()throws Exception {
    String id=f.upload("API key collection source");f.jdbc.update("UPDATE knowledges SET title=? WHERE id=?",id,id);
    for(int mode=0;mode<4;mode++) {
      String key="collection-"+UUID.randomUUID();boolean allowed=mode==3;
      f.jdbc.update("INSERT INTO tenant_api_keys(tenant_id,name,key_hash,api_key,full_access,capabilities,knowledge_base_ids) VALUES(8,'Collection reader',?,?,?,?,?)",AuthFilter.hashApiKey(key),key,mode==2,mode==0?"[\"ingest\"]":"[\"retrieve\"]",mode==1||mode==2?"[\"other-kb\"]":"[\"trace-kb\"]");
      keys.add(f.jdbc.queryForObject("SELECT id FROM tenant_api_keys WHERE key_hash=?",Long.class,AuthFilter.hashApiKey(key)));
      for(String path:List.of("/api/v1/knowledge/batch?ids="+id,"/api/v1/knowledge/search?keyword="+id)) {
        var response=f.mvc.perform(get(path).header("X-API-Key",key)).andReturn().getResponse();assertEquals(mode==0?403:200,response.getStatus(),response.getContentAsString());
        if(mode!=0){var data=f.json.readTree(response.getContentAsByteArray()).path("data");assertEquals(allowed?1:0,data.size());if(allowed)assertEquals(id,data.get(0).path("id").asText());}
      }
    }
  }
  @Test void originalFileDownloadsKeepResourceOwnerAndStricterShareBoundary()throws Exception {
    String body="Original CRLF\r\n中文 source",id=f.upload(body),path="/api/v1/knowledge/"+id;
    assertTrue(f.jdbc.queryForObject("SELECT file_path FROM knowledges WHERE id=?",String.class,id).startsWith("resource://"));
    f.jdbc.update("UPDATE tenant_members SET role='contributor' WHERE user_id='caller'");
    var allowed=f.mvc.perform(get(path+"/download").header("Authorization","Bearer "+f.callerToken)).andReturn().getResponse();
    assertEquals(200,allowed.getStatus(),allowed.getContentAsString());assertArrayEquals(body.getBytes(java.nio.charset.StandardCharsets.UTF_8),allowed.getContentAsByteArray());assertEquals("private, no-store",allowed.getHeader("Cache-Control"));assertEquals("bytes",allowed.getHeader("Accept-Ranges"));
    f.jdbc.update("UPDATE kb_shares SET permission='viewer' WHERE id='trace-share'");
    assertEquals(403,f.mvc.perform(get(path+"/download").header("Authorization","Bearer "+f.callerToken)).andReturn().getResponse().getStatus());
    assertEquals(200,f.mvc.perform(get(path+"/preview").header("Authorization","Bearer "+f.callerToken)).andReturn().getResponse().getStatus());
    assertEquals(403,f.mvc.perform(get(path+"/preview").header("Authorization","Bearer "+f.foreignToken)).andReturn().getResponse().getStatus());
    f.jdbc.update("UPDATE tenant_members SET role='viewer' WHERE user_id='owner'");String owner=f.token("owner",8);
    assertEquals(403,f.mvc.perform(get(path+"/download").header("Authorization","Bearer "+owner)).andReturn().getResponse().getStatus());
    assertEquals(200,f.mvc.perform(get(path+"/preview").header("Authorization","Bearer "+owner)).andReturn().getResponse().getStatus());
    f.jdbc.update("UPDATE tenant_members SET role='contributor' WHERE user_id='owner'");
    assertEquals(200,f.mvc.perform(get(path+"/download").header("Authorization","Bearer "+owner)).andReturn().getResponse().getStatus());
  }
  @Test void originalFileApiKeysNeedRetrieveAndEnforceKnowledgeBaseScope()throws Exception {
    String body="Scoped original source",id=f.upload(body);
    for(int mode=0;mode<3;mode++){
      String key="file-read-"+UUID.randomUUID(),capability=mode==0?"ingest":"retrieve";
      f.jdbc.update("INSERT INTO tenant_api_keys(tenant_id,name,key_hash,api_key,full_access,capabilities,knowledge_base_ids) VALUES(8,'File reader',?,?,false,?,?)",AuthFilter.hashApiKey(key),key,f.json.writeValueAsString(List.of(capability)),mode==1?"[\"other-kb\"]":"[\"trace-kb\"]");
      keys.add(f.jdbc.queryForObject("SELECT id FROM tenant_api_keys WHERE key_hash=?",Long.class,AuthFilter.hashApiKey(key)));
      for(String operation:List.of("download","preview")){
        var response=f.mvc.perform(get("/api/v1/knowledge/"+id+"/"+operation).header("X-API-Key",key)).andReturn().getResponse();
        assertEquals(mode==2?200:403,response.getStatus(),response.getContentAsString());if(mode==2)assertArrayEquals(body.getBytes(java.nio.charset.StandardCharsets.UTF_8),response.getContentAsByteArray());
      }
    }
  }
  @Test void originalFileRangesConditionalsAndHeadUseGoTransportRules()throws Exception {
    String id=f.upload("0123456789abcdef"),path="/api/v1/knowledge/"+id+"/preview";
    var partial=f.mvc.perform(get(path).header("Authorization","Bearer "+f.callerToken).header("Range","bytes=2-5")).andReturn().getResponse();assertEquals(206,partial.getStatus());assertEquals("2345",partial.getContentAsString());assertEquals("bytes 2-5/16",partial.getHeader("Content-Range"));
    var invalid=f.mvc.perform(get(path).header("Authorization","Bearer "+f.callerToken).header("Range","bytes=999-")).andReturn().getResponse();assertEquals(416,invalid.getStatus());assertEquals("bytes */16",invalid.getHeader("Content-Range"));assertNull(invalid.getHeader("Cache-Control"));
    var none=f.mvc.perform(get(path).header("Authorization","Bearer "+f.callerToken).header("If-None-Match","*")).andReturn().getResponse();assertEquals(304,none.getStatus());assertNull(none.getHeader("Content-Type"));assertEquals(0,none.getContentAsByteArray().length);
    var failed=f.mvc.perform(get(path).header("Authorization","Bearer "+f.callerToken).header("If-Match","\"unknown\"")).andReturn().getResponse();assertEquals(412,failed.getStatus());assertEquals(0,failed.getContentAsByteArray().length);
    assertEquals(404,f.mvc.perform(head(path).header("Authorization","Bearer "+f.callerToken)).andReturn().getResponse().getStatus());
    assertEquals(404,f.mvc.perform(get("/api/v1/knowledge/absent/preview").header("Authorization","Bearer "+f.callerToken)).andReturn().getResponse().getStatus());
  }
  @Test void manualMarkdownAndActiveSourcePreviewKeepDifferentDispositions()throws Exception {
    String body="<svg onload='alert(1)'>Original</svg>",id=f.upload(body);f.jdbc.update("UPDATE knowledges SET file_name='危险.svg' WHERE id=?",id);
    var active=f.mvc.perform(get("/api/v1/knowledge/"+id+"/preview").header("Authorization","Bearer "+f.callerToken)).andReturn().getResponse();assertEquals(200,active.getStatus());assertEquals("application/octet-stream",active.getContentType());assertTrue(active.getHeader("Content-Disposition").startsWith("attachment; filename*=utf-8''"));assertArrayEquals(body.getBytes(java.nio.charset.StandardCharsets.UTF_8),active.getContentAsByteArray());
    String manual="file-manual-"+UUID.randomUUID(),markdown="# Full manual\n中文";f.jdbc.update("INSERT INTO knowledges(id,tenant_id,knowledge_base_id,type,title,source,parse_status,metadata) VALUES(?,8,'trace-kb','manual','手工/说明','manual','completed',?)",manual,f.json.writeValueAsString(Map.of("content",markdown)));
    var preview=f.mvc.perform(get("/api/v1/knowledge/"+manual+"/preview").header("Authorization","Bearer "+f.callerToken).header("Range","bytes=0-2")).andReturn().getResponse();assertEquals(200,preview.getStatus());assertEquals("none",preview.getHeader("Accept-Ranges"));assertEquals("text/markdown; charset=utf-8",preview.getContentType());assertArrayEquals(markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8),preview.getContentAsByteArray());
  }
  java.util.LinkedHashMap<String,byte[]> unzip(byte[] bytes)throws Exception {
    var result=new java.util.LinkedHashMap<String,byte[]>();var path=java.nio.file.Files.createTempFile("batch-http-", ".zip");try{java.nio.file.Files.write(path,bytes);try(var zip=new java.util.zip.ZipFile(path.toFile())){var entries=zip.entries();while(entries.hasMoreElements()){var entry=entries.nextElement();assertEquals(java.util.zip.ZipEntry.STORED,entry.getMethod());try(var in=zip.getInputStream(entry)){result.put(entry.getName(),in.readAllBytes());}}}}finally{java.nio.file.Files.deleteIfExists(path);}return result;
  }
  @Test void sharedBatchDownloadKeepsCompleteSourceOrderFoldersManualAndReadOnlyState()throws Exception {
    String first=f.upload("First source\r\n中文"),second=f.upload("Second source\nbytes");
    f.jdbc.update("UPDATE knowledges SET file_name='same.txt',folder_path='目录' WHERE id IN (?,?)",first,second);
    String manual="batch-manual-"+UUID.randomUUID();f.jdbc.update("INSERT INTO knowledges(id,tenant_id,knowledge_base_id,type,title,source,parse_status,metadata,folder_path) VALUES(?,8,'trace-kb','manual','手记','manual','completed',?,'目录')",manual,f.json.writeValueAsString(Map.of("content","# Manual\n中文")));
    var before=f.jdbc.queryForList("SELECT * FROM knowledges ORDER BY id");var quota=f.jdbc.queryForList("SELECT id,storage_used FROM tenants ORDER BY id");var jobs=f.jdbc.queryForList("SELECT * FROM java_task_jobs ORDER BY id");
    var response=f.mvc.perform(post("/api/v1/knowledge-bases/trace-kb/knowledge/batch-download").header("Authorization","Bearer "+f.callerToken).contentType("application/json").content(f.json.writeValueAsBytes(Map.of("ids",List.of(second,first,manual,first))))).andReturn().getResponse();
    assertEquals(200,response.getStatus(),response.getContentAsString());assertEquals("application/zip",response.getContentType());assertNull(response.getHeader("Content-Description"));var entries=unzip(response.getContentAsByteArray());assertEquals(List.of("目录/same.txt","目录/same (2).txt","目录/手记.md"),new ArrayList<>(entries.keySet()));assertArrayEquals("Second source\nbytes".getBytes(java.nio.charset.StandardCharsets.UTF_8),entries.get("目录/same.txt"));assertArrayEquals("First source\r\n中文".getBytes(java.nio.charset.StandardCharsets.UTF_8),entries.get("目录/same (2).txt"));assertArrayEquals("# Manual\n中文".getBytes(java.nio.charset.StandardCharsets.UTF_8),entries.get("目录/手记.md"));
    assertEquals(before,f.jdbc.queryForList("SELECT * FROM knowledges ORDER BY id"));assertEquals(quota,f.jdbc.queryForList("SELECT id,storage_used FROM tenants ORDER BY id"));assertEquals(jobs,f.jdbc.queryForList("SELECT * FROM java_task_jobs ORDER BY id"));
    f.jdbc.update("UPDATE kb_shares SET permission='viewer' WHERE id='trace-share'");request("/api/v1/knowledge-bases/trace-kb/knowledge/batch-download",f.callerToken,"{",403);
    f.jdbc.update("UPDATE tenant_members SET role='viewer' WHERE user_id='owner'");request("/api/v1/knowledge-bases/trace-kb/knowledge/batch-download",f.token("owner",8),"{",403);
  }
  @Test void batchDownloadUsesRealRetrieveKeyGateAndFullKeyStillHasKbScope()throws Exception {
    String id=f.upload("API source");String path="/api/v1/knowledge-bases/trace-kb/knowledge/batch-download";
    for(int mode=0;mode<4;mode++){
      String key="batch-key-"+UUID.randomUUID();f.jdbc.update("INSERT INTO tenant_api_keys(tenant_id,name,key_hash,api_key,full_access,capabilities,knowledge_base_ids) VALUES(8,'Batch reader',?,?,?,?,?)",AuthFilter.hashApiKey(key),key,mode==3,mode==0?"[\"ingest\"]":"[\"retrieve\"]",mode==1||mode==3?"[\"other\"]":"[\"trace-kb\"]");keys.add(f.jdbc.queryForObject("SELECT id FROM tenant_api_keys WHERE key_hash=?",Long.class,AuthFilter.hashApiKey(key)));
      var response=f.mvc.perform(post(path).header("X-API-Key",key).contentType("application/json").content(mode==0?"{":f.json.writeValueAsString(Map.of("ids",List.of(id))))).andReturn().getResponse();assertEquals(mode==2?200:403,response.getStatus(),response.getContentAsString());if(mode==2)assertArrayEquals("API source".getBytes(java.nio.charset.StandardCharsets.UTF_8),unzip(response.getContentAsByteArray()).values().iterator().next());
    }
  }
  @Test void batchDownloadPrevalidatesEveryKbBeforeOpeningAndNeverReturnsPartialZip()throws Exception {
    String first=f.upload("First"),second=f.upload("Second");f.jdbc.update("UPDATE knowledges SET knowledge_base_id='other-kb' WHERE id=?",second);f.jdbc.update("UPDATE knowledges SET file_path='local://8/missing.txt' WHERE id=?",first);
    String path="/api/v1/knowledge-bases/trace-kb/knowledge/batch-download",body=f.json.writeValueAsString(Map.of("ids",List.of(first,second)));request(path,f.callerToken,body,404);
    f.jdbc.update("UPDATE knowledges SET knowledge_base_id='trace-kb' WHERE id=?",second);var response=f.mvc.perform(post(path).header("Authorization","Bearer "+f.callerToken).contentType("application/json").content(body)).andReturn().getResponse();assertEquals(500,response.getStatus());assertNull(response.getHeader("Content-Disposition"));assertEquals(first,f.json.readTree(response.getContentAsByteArray()).path("error").path("details").path("knowledge_id").asText());
  }
  String reparsePath(String id){return "/api/v1/knowledge/"+id+"/reparse";}
  String state(String id){return f.jdbc.queryForObject("SELECT parse_status FROM knowledges WHERE id=?",String.class,id);}
  void finishDocument()throws Exception{var doc=f.claim("default");assertTrue(f.document.execute(doc,f.cancellation(doc)).path("indexed").asBoolean());assertTrue(f.tasks.complete(doc,f.json.createObjectNode()));var post=f.claim("postprocess");f.postprocess.execute(post,f.cancellation(post));assertTrue(f.tasks.complete(post,f.json.createObjectNode()));}
  long physical(String id){return f.jdbc.execute((ConnectionCallback<Long>)c->{new SQLiteVectorExtension(Path.of(System.getProperty("weknora.sqlite.vec.extension"))).load(c);try(var p=c.prepareStatement("SELECT COUNT(*) FROM vec_embeddings_3 v JOIN lite_embeddings e ON e.id=v.rowid WHERE e.knowledge_id=?")){p.setString(1,id);try(var r=p.executeQuery()){return r.getLong(1);}}});}
  String manualDraft(String text)throws Exception{return request("/api/v1/knowledge-bases/trace-kb/knowledge/manual",f.callerToken,f.json.writeValueAsString(Map.of("content",text,"status","draft")),200).path("data").path("id").asText();}

  String urlPath(){return "/api/v1/knowledge-bases/trace-kb/knowledge/url";}
  String importURL(String url,String token)throws Exception {
    return request(urlPath(),token,f.json.writeValueAsString(Map.of("url",url,"process_config",Map.of("summary_enabled",false))),201).path("data").path("id").asText();
  }

  @Test void publicSharedURLImportPublishesReaderContentAndOriginalActorAudit()throws Exception {
    String origin="http://127.0.0.1:"+model.getAddress().getPort();var requests=new ArrayList<JsonNode>();
    model.createContext("/read",e->{try {
      requests.add(f.json.readTree(e.getRequestBody()));byte[] bytes=f.json.writeValueAsBytes(Map.of("markdown_content","Public admission writes remote evidence to native indexes.","metadata",Map.of("title","Public remote title")));
      e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);
    }finally{e.close();}});reader.reconnect(origin);
    String id=importURL(origin+"/page-"+UUID.randomUUID(),f.callerToken);
    assertEquals("pending",state(id));assertEquals(8,f.jdbc.queryForObject("SELECT tenant_id FROM knowledges WHERE id=?",Long.class,id));
    assertEquals(0,f.count("chunks",id));finishDocument();assertEquals("completed",state(id));assertTrue(physical(id)>0);
    assertEquals(1,requests.size());assertEquals("Public remote title",f.jdbc.queryForObject("SELECT title FROM knowledges WHERE id=?",String.class,id));
    assertTrue(f.jdbc.queryForList("SELECT content FROM lite_embeddings WHERE knowledge_id=?",String.class,id).stream().anyMatch(s->s.contains("Public admission writes remote evidence")));
    var audit=f.jdbc.queryForMap("SELECT tenant_id,actor_user_id,details FROM audit_logs WHERE action='knowledge.created' AND target_id=?",id);
    assertEquals(8,((Number)audit.get("tenant_id")).longValue());assertEquals("caller",audit.get("actor_user_id"));
    var details=f.json.readTree(audit.get("details").toString());assertEquals("url",details.path("source_type").asText());
    assertEquals("succeeded",f.jdbc.queryForObject("SELECT status FROM java_task_jobs WHERE id=?",String.class,details.path("task_id").asText()));
    assertEquals(1,f.tracker.latest(8,id));
  }

  @Test void publicFileURLImportDownloadsAndIndexesOwnerResourceWithOneConnection()throws Exception {
    byte[] bytes="Public file admission downloads original source and publishes searchable chunks.".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String filename="public-"+UUID.randomUUID()+".txt";
    model.createContext("/"+filename,e->{try{e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);}finally{e.close();}});
    String url="http://127.0.0.1:"+model.getAddress().getPort()+"/"+filename;
    String id=importURL(url,f.callerToken);assertEquals("file_url",f.jdbc.queryForObject("SELECT type FROM knowledges WHERE id=?",String.class,id));
    finishDocument();assertEquals("completed",state(id));assertTrue(physical(id)>0);assertTrue(f.count("lite_embeddings",id)>0);
    var resources=f.jdbc.queryForList("SELECT handle FROM resources WHERE tenant_id=8 AND original_name=? AND lifecycle='temporary' AND state='active'",filename);assertEquals(1,resources.size());
    try(var in=f.storage.forKnowledgeBase(8,"trace-kb").files().open("resource://"+resources.getFirst().get("handle"))){assertArrayEquals(bytes,in.readAllBytes());}
    assertEquals(url,f.jdbc.queryForObject("SELECT source FROM knowledges WHERE id=?",String.class,id));
    assertEquals("",f.jdbc.queryForObject("SELECT file_path FROM knowledges WHERE id=?",String.class,id));
  }

  @Test void publicURLQueueFailureReturnsCreatedButFailedAndTagsFailureKeepsPendingRow()throws Exception {
    f.jdbc.execute("CREATE TRIGGER reparse_queue_failure BEFORE INSERT ON java_task_jobs WHEN NEW.type='document:process' BEGIN SELECT RAISE(FAIL,'controlled queue failure');END");
    String url="http://127.0.0.1:"+model.getAddress().getPort()+"/failed-"+UUID.randomUUID();
    var body=request(urlPath(),f.callerToken,f.json.writeValueAsString(Map.of("url",url)),201);String id=body.path("data").path("id").asText();
    assertTrue(body.path("success").asBoolean());assertEquals("failed",state(id));assertEquals("Failed to enqueue processing task",body.path("data").path("error_message").asText());
    assertEquals("failed",f.jdbc.queryForObject("SELECT outcome FROM audit_logs WHERE action='knowledge.created' AND target_id=?",String.class,id));
    assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM java_task_jobs WHERE payload->>'knowledge_id'=?",Integer.class,id));
    f.jdbc.execute("DROP TRIGGER reparse_queue_failure");String tagURL=url+"-tag";
    request(urlPath(),f.callerToken,f.json.writeValueAsString(Map.of("url",tagURL,"tag_ids",List.of("missing-url-tag"))),400);
    var row=f.jdbc.queryForMap("SELECT id,parse_status FROM knowledges WHERE source=?",tagURL);assertEquals("pending",row.get("parse_status"));
    assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE target_id=?",Integer.class,row.get("id")));
    assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM java_task_jobs WHERE payload->>'knowledge_id'=?",Integer.class,row.get("id")));
  }

  @Test void publicURLRealApiScopeGateRunsBeforeBindingAndCreatorViewerCanImport()throws Exception {
    for(int mode=0;mode<3;mode++) {
      String key="url-"+UUID.randomUUID();String capability=mode==0?"retrieve":"ingest";
      f.jdbc.update("INSERT INTO tenant_api_keys(tenant_id,name,key_hash,api_key,full_access,capabilities,knowledge_base_ids) VALUES(8,'URL automation',?,?,false,?,?)",AuthFilter.hashApiKey(key),key,f.json.writeValueAsString(List.of(capability)),mode==1?"[\"other-kb\"]":"[\"trace-kb\"]");
      long keyId=f.jdbc.queryForObject("SELECT id FROM tenant_api_keys WHERE key_hash=?",Long.class,AuthFilter.hashApiKey(key));keys.add(keyId);
      var result=f.mvc.perform(post(urlPath()).header("X-API-Key",key).contentType("application/json").content(mode==2?f.json.writeValueAsString(Map.of("url","http://127.0.0.1:"+model.getAddress().getPort()+"/key-"+keyId)):"invalid")).andReturn().getResponse();
      assertEquals(mode==2?201:403,result.getStatus(),result.getContentAsString());
      if(mode==2){String id=f.json.readTree(result.getContentAsByteArray()).path("data").path("id").asText();var details=f.json.readTree(f.jdbc.queryForObject("SELECT details FROM audit_logs WHERE action='knowledge.created' AND target_id=?",String.class,id));assertEquals(keyId,details.path("api_key_id").asLong());assertEquals("URL automation",details.path("api_key_name").asText());}
    }
    f.jdbc.update("UPDATE tenant_members SET role='viewer' WHERE user_id='owner' AND tenant_id=8");
    assertFalse(importURL("http://127.0.0.1:"+model.getAddress().getPort()+"/creator-"+UUID.randomUUID(),f.token("owner",8)).isEmpty());
    f.jdbc.update("UPDATE tenant_members SET role='viewer' WHERE user_id='caller' AND tenant_id=7");
    request(urlPath(),f.callerToken,"invalid",403);
  }

  @Test void urlReparseUsesRemoteReaderAndExtractedTitleInPhysicalIndexes()throws Exception{
    String origin="http://127.0.0.1:"+model.getAddress().getPort();var requests=new ArrayList<JsonNode>();
    model.createContext("/read",e->{try{
      requests.add(f.json.readTree(e.getRequestBody()));byte[] body=f.json.writeValueAsBytes(Map.of("markdown_content","Remote page content enters native vectors and keywords.","metadata",Map.of("title","Extracted remote page title")));
      e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);
    }finally{e.close();}});reader.reconnect(origin);
    String id=manualDraft("Pending remote source.");f.jdbc.update("UPDATE knowledges SET type='url',source=?,title=?,file_name='',file_type='',file_path='' WHERE id=?",origin+"/page",origin+"/page",id);
    request(reparsePath(id),f.callerToken,"{\"process_config\":{\"summary_enabled\":false}}",200);finishDocument();
    assertEquals("completed",state(id));assertTrue(physical(id)>0);assertEquals(1,requests.size());
    JsonNode sent=requests.getFirst();assertEquals(origin+"/page",sent.path("url").asText());assertEquals(origin+"/page",sent.path("title").asText());
    for(String field:List.of("file_content","file_name","file_type"))assertFalse(sent.has(field),sent.toString());
    assertEquals("Extracted remote page title",f.jdbc.queryForObject("SELECT title FROM knowledges WHERE id=?",String.class,id));
    assertTrue(f.jdbc.queryForList("SELECT content FROM lite_embeddings WHERE knowledge_id=?",String.class,id).stream().anyMatch(content->content.startsWith("Extracted remote page title\n")));
  }

  @Test void fileUrlReparseDownloadsTemporaryResourceAndPreservesStoredSourcePath()throws Exception{
    byte[] bytes="Downloaded source content enters native vectors and keywords.".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    model.createContext("/download",e->{try{e.getResponseHeaders().set("Content-Disposition","attachment; filename=\"reparse-remote.TXT\"");e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);}finally{e.close();}});
    String url="http://127.0.0.1:"+model.getAddress().getPort()+"/download";
    String id=manualDraft("Pending remote file.");f.jdbc.update("UPDATE knowledges SET type='file_url',source=?,file_name='',file_type='',file_path='' WHERE id=?",url,id);
    request(reparsePath(id),f.callerToken,"{\"process_config\":{\"summary_enabled\":false}}",200);finishDocument();
    assertEquals("completed",state(id));assertTrue(physical(id)>0);
    var row=f.jdbc.queryForMap("SELECT source,file_path,file_name,file_type FROM knowledges WHERE id=?",id);
    assertEquals(url,row.get("source"));assertEquals("",row.get("file_path"));assertEquals("reparse-remote.TXT",row.get("file_name"));assertEquals("TXT",row.get("file_type"));
    var resources=f.jdbc.queryForList("SELECT handle FROM resources WHERE tenant_id=8 AND original_name='reparse-remote.TXT' AND lifecycle='temporary' AND state='active'");assertEquals(1,resources.size());
    try(var in=f.storage.forKnowledgeBase(8,"trace-kb").files().open("resource://"+resources.getFirst().get("handle"))){assertArrayEquals(bytes,in.readAllBytes());}
  }

  @Test void sharedFileReparseCleansPhysicalIndexesRetainsSourceAndRebuildsOwnerAttempt()throws Exception{
    String id=f.upload("Reparse source retains its file while replacing indexed controls.");finishDocument();assertTrue(physical(id)>0);
    String path=f.jdbc.queryForObject("SELECT file_path FROM knowledges WHERE id=?",String.class,id);
    byte[] original;try(var in=f.storage.forKnowledgeBase(8,"trace-kb").files().open(path)){original=in.readAllBytes();}
    request(reparsePath(id),f.callerToken,"",200);assertEquals("pending",state(id));assertEquals(0,physical(id));assertEquals(0,f.count("lite_embeddings",id));
    assertEquals(2,f.tracker.latest(8,id));assertEquals(0,f.jdbc.queryForObject("SELECT pending_subtasks_count FROM knowledges WHERE id=?",Integer.class,id));
    var audit=f.jdbc.queryForMap("SELECT tenant_id,actor_user_id FROM audit_logs WHERE action='knowledge.reparse_started' AND target_id=? ORDER BY id DESC LIMIT 1",id);
    assertEquals(8,((Number)audit.get("tenant_id")).longValue());assertEquals("caller",audit.get("actor_user_id"));
    try(var in=f.storage.forKnowledgeBase(8,"trace-kb").files().open(path)){assertArrayEquals(original,in.readAllBytes());}
    finishDocument();assertEquals("completed",state(id));assertTrue(physical(id)>0);assertEquals(2,f.tracker.latest(8,id));
  }

  @Test void manualReparseDefersCleanupUntilWorkerAndScrubsOnlyStaleWikiIngest()throws Exception{
    String id=request("/api/v1/knowledge-bases/trace-kb/knowledge/manual",f.callerToken,
        f.json.writeValueAsString(Map.of("content","Manual body with retained publication until the reparse worker.","status","publish","process_config",Map.of("summary_enabled",false))),200).path("data").path("id").asText();
    var initial=f.claim("default");assertEquals("indexed",manual.execute(initial,f.cancellation(initial)).path("status").asText());f.tasks.complete(initial,f.json.createObjectNode());
    var initialPost=f.claim("postprocess");f.postprocess.execute(initialPost,f.cancellation(initialPost));f.tasks.complete(initialPost,f.json.createObjectNode());
    long prior=physical(id);assertTrue(prior>0);
    f.jdbc.update("INSERT INTO task_pending_ops(tenant_id,task_type,scope,scope_id,op,dedup_key) VALUES(8,'wiki:ingest','knowledge_base','trace-kb','ingest',?),(8,'wiki:ingest','knowledge_base','trace-kb','retract',?)",id,id);
    request(reparsePath(id),f.callerToken,"",200);var job=f.claim("default");assertEquals("manual:process",job.type());assertTrue(job.payload().path("need_cleanup").asBoolean());assertEquals(8,job.tenantId());
    assertEquals(prior,physical(id),"manual cleanup is deferred until the worker");
    assertEquals(List.of("retract"),f.jdbc.queryForList("SELECT op FROM task_pending_ops WHERE dedup_key=? ORDER BY id",String.class,id));
    assertEquals("indexed",manual.execute(job,f.cancellation(job)).path("status").asText());f.tasks.complete(job,f.json.createObjectNode());var post=f.claim("postprocess");f.postprocess.execute(post,f.cancellation(post));f.tasks.complete(post,f.json.createObjectNode());
    assertEquals("completed",state(id));assertTrue(physical(id)>0);
  }

  @Test void batchRevalidatesAllBindingsBeforeAnyChildSubmission()throws Exception{
    String first=manualDraft("First selection stays untouched."),second=manualDraft("Second selection moves while queued.");
    request("/api/v1/knowledge/batch-reparse",f.callerToken,f.json.writeValueAsString(Map.of("kb_id","trace-kb","ids",List.of(first,second))),200);
    var job=f.claim("low");f.jdbc.update("UPDATE knowledges SET tenant_id=9 WHERE id=?",second);
    var failure=assertThrows(TaskFailure.class,()->batch.execute(job,f.cancellation(job)));assertFalse(failure.retryable());
    assertEquals("draft",state(first));assertEquals(0,f.tracker.latest(8,first));
    assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM java_task_jobs WHERE type IN ('manual:process','document:process') AND json_extract(payload,'$.knowledge_id')=?",Long.class,first));
  }

  @Test void batchPartialFailureContinuesAndPreventsWrapperRetry()throws Exception{
    String first=manualDraft("First valid manual body."),bad=manualDraft("Bad source."),last=manualDraft("Last valid manual body.");
    f.jdbc.update("UPDATE knowledges SET type='file',source='',file_path='' WHERE id=?",bad);
    request("/api/v1/knowledge/batch-reparse",f.callerToken,f.json.writeValueAsString(Map.of("kb_id","trace-kb","ids",List.of(first,bad,last))),200);
    var job=f.claim("low");var failure=assertThrows(TaskFailure.class,()->batch.execute(job,f.cancellation(job)));
    assertFalse(failure.retryable());assertTrue(failure.getMessage().contains("submitted 2 item(s) and failed 1"));assertTrue(failure.getMessage().contains(bad));
    assertEquals("pending",state(first));assertEquals("failed",state(bad));assertEquals("pending",state(last));
    assertEquals(2,f.jdbc.queryForObject("SELECT COUNT(*) FROM java_task_jobs WHERE type='manual:process' AND json_extract(payload,'$.knowledge_id') IN (?,?)",Long.class,first,last));
    assertEquals(2,f.jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action='knowledge.reparse_started' AND actor_user_id='caller' AND target_id IN (?,?)",Long.class,first,last));
  }

  @Test void queueFailureRetainsCleanupAndNewAttemptInsteadOfRollingBack()throws Exception{
    String id=f.upload("Enqueue failure retains the already committed cleanup checkpoints.");finishDocument();assertTrue(physical(id)>0);
    f.jdbc.execute("CREATE TRIGGER reparse_queue_failure BEFORE INSERT ON java_task_jobs WHEN NEW.type='document:process' BEGIN SELECT RAISE(FAIL,'controlled queue failure');END");
    request(reparsePath(id),f.callerToken,"",500);assertEquals("failed",state(id));assertEquals(2,f.tracker.latest(8,id));assertEquals(0,physical(id));
    assertEquals("Failed to enqueue processing task",f.jdbc.queryForObject("SELECT error_message FROM knowledges WHERE id=?",String.class,id));
  }

  @Test void realApiKeyGatePrecedesOptionalBodyAndPreservesOwnerAudit()throws Exception{
    String id=manualDraft("API scoped manual reparse.");
    for(String capability:List.of("retrieve","ingest")){
      String key="reparse-"+UUID.randomUUID();f.jdbc.update("INSERT INTO tenant_api_keys(tenant_id,name,key_hash,api_key,full_access,capabilities,knowledge_base_ids) VALUES(8,'Reparse operator',?,?,false,?,?)",AuthFilter.hashApiKey(key),key,f.json.writeValueAsString(List.of(capability)),"[\"trace-kb\"]");
      keys.add(f.jdbc.queryForObject("SELECT id FROM tenant_api_keys WHERE key_hash=?",Long.class,AuthFilter.hashApiKey(key)));
      var response=f.mvc.perform(post(reparsePath(id)).header("X-API-Key",key).contentType("application/json").content(capability.equals("ingest")?"{}":"invalid")).andReturn().getResponse();
      assertEquals(capability.equals("ingest")?200:403,response.getStatus(),response.getContentAsString());
    }
    assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action='knowledge.reparse_started' AND tenant_id=8 AND target_id=?",Integer.class,id));
  }

  @Test void lostBatchLeaseCannotSubmitChildren()throws Exception{
    String id=manualDraft("Lost batch lease must publish no child task.");request("/api/v1/knowledge/batch-reparse",f.callerToken,f.json.writeValueAsString(Map.of("kb_id","trace-kb","ids",List.of(id))),200);
    var job=f.claim("low");f.jdbc.update("UPDATE java_task_jobs SET lease_owner='successor' WHERE id=?",job.id());
    assertThrows(java.util.concurrent.CancellationException.class,()->batch.execute(job,f.cancellation(job)));assertEquals("draft",state(id));assertEquals(0,f.tracker.latest(8,id));
  }
}
