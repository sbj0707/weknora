package com.tencent.weknora.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.security.AuthFilter;
import com.tencent.weknora.storage.StorageResolver;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Full JWT/API-key filters, real uploaded source, sqlite-vec/FTS and pool-one chunk operations. */
@org.springframework.boot.test.context.SpringBootTest(properties={"weknora.jwt-secret=isolated-native-pipeline-trace-secret","weknora.bridge.enabled=false","weknora.knowledge.processing.enabled=true","weknora.knowledge.upload.enabled=true","weknora.tasks.enabled=true","weknora.tasks.poll-millis=3600000","weknora.retrieve-driver=sqlite","SSRF_WHITELIST=127.0.0.1","SSRF_DNS_WHITELIST_ONLY=true","NEO4J_ENABLE=false","logging.level.root=WARN","debug=false"})
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class ChunkLifecyclePipelineHttpIntegrationTest {
 @org.springframework.test.context.DynamicPropertySource static void properties(org.springframework.test.context.DynamicPropertyRegistry p){IndexDeletePipelineHttpIntegrationTest.properties(p);}
 @Autowired AutowireCapableBeanFactory beans;@Autowired StorageResolver storage;
 final IndexDeletePipelineHttpIntegrationTest f=new IndexDeletePipelineHttpIntegrationTest();final ObjectMapper json=new ObjectMapper();final List<String> keys=new ArrayList<>();
 @BeforeEach void setup()throws Exception{beans.autowireBean(f);f.setup();f.f.jdbc.update("UPDATE tenant_members SET role='admin' WHERE user_id IN ('owner','caller')");f.f.jdbc.update("UPDATE kb_shares SET permission='editor',deleted_at=NULL WHERE id='trace-share'");f.f.jdbc.update("UPDATE organization_tenant_members SET role='editor' WHERE id='trace-target'");}
 @AfterEach void release(){for(String key:keys)f.f.jdbc.update("DELETE FROM tenant_api_keys WHERE key_hash=?",AuthFilter.hashApiKey(key));f.f.jdbc.update("UPDATE tenant_members SET role='admin' WHERE user_id IN ('owner','caller')");f.f.jdbc.update("UPDATE kb_shares SET permission='editor',deleted_at=NULL WHERE id='trace-share'");f.release();}
 JsonNode request(String method,String path,String credential,boolean machine,int status)throws Exception{var builder=MockMvcRequestBuilders.request(HttpMethod.valueOf(method),path);builder.header(machine?"X-API-Key":"Authorization",machine?credential:"Bearer "+credential);var response=f.f.mvc.perform(builder).andReturn().getResponse();assertEquals(status,response.getStatus(),response.getContentAsString());return json.readTree(response.getContentAsByteArray());}
 String key(String capability,boolean full,String kb)throws Exception{String value="chunk-key-"+UUID.randomUUID();keys.add(value);f.f.jdbc.update("INSERT INTO tenant_api_keys(tenant_id,name,key_hash,api_key,full_access,capabilities,knowledge_base_ids) VALUES(8,'Chunk operator',?,?,?,?,?)",AuthFilter.hashApiKey(value),value,full,json.writeValueAsString(List.of(capability)),json.writeValueAsString(List.of(kb)));return value;}
 void revision(String doc,String chunk,int no)throws Exception{f.f.jdbc.update("INSERT INTO chunk_revisions(id,tenant_id,knowledge_base_id,knowledge_id,chunk_id,revision,content,is_enabled,editor_id,edit_source,edited_at,created_at) VALUES(?,8,'trace-kb',?,?,?, ?,true,'caller','manual','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')","chunk-revision-"+UUID.randomUUID(),doc,chunk,no,"Revision "+no+" 中文");}
 @Test void creatorViewerSingleTombstoneRetainsChildrenSourceQuotaAndPhysicalIndexes()throws Exception{
  String doc=f.prepared(),chunk=f.ids(doc).getFirst();revision(doc,chunk,1);
  String child="chunk-child-"+UUID.randomUUID();f.f.jdbc.update("INSERT INTO chunks(id,tenant_id,knowledge_base_id,knowledge_id,content,chunk_index,start_at,end_at,chunk_type,parent_chunk_id) VALUES(?,8,'trace-kb',?,'Image child',99,0,10,'image_caption',?)",child,doc,chunk);
  var before=snapshot(doc);f.f.jdbc.update("UPDATE tenant_members SET role='viewer' WHERE user_id='owner' AND tenant_id=8");
  request("DELETE","/api/v1/chunks/"+doc+"/"+chunk,f.f.token("owner",8),false,200);
  assertEquals(1,f.f.jdbc.queryForObject("SELECT COUNT(*) FROM chunks WHERE id=? AND deleted_at IS NOT NULL",Integer.class,chunk));assertEquals(1,f.f.jdbc.queryForObject("SELECT COUNT(*) FROM chunks WHERE id=? AND deleted_at IS NULL",Integer.class,child));assertEquals(before,snapshot(doc));assertEquals(1,f.f.jdbc.queryForObject("SELECT COUNT(*) FROM chunk_revisions WHERE chunk_id=?",Integer.class,chunk));
  request("GET","/api/v1/chunks/"+doc+"/"+chunk+"/revisions",f.f.token("owner",8),false,404);
 }
 @Test void sharedEditorBulkRetainsPhysicalIndexesAndDoesNotScheduleCleanup()throws Exception{
  String doc=f.prepared();var before=snapshot(doc);f.f.jdbc.update("UPDATE kb_shares SET permission='viewer' WHERE id='trace-share'");request("DELETE","/api/v1/chunks/"+doc,f.f.callerToken,false,403);assertEquals(before,snapshot(doc));f.f.jdbc.update("UPDATE kb_shares SET permission='editor' WHERE id='trace-share'");request("DELETE","/api/v1/chunks/"+doc,f.f.callerToken,false,200);assertEquals(0,f.f.jdbc.queryForObject("SELECT COUNT(*) FROM chunks WHERE knowledge_id=? AND deleted_at IS NULL",Integer.class,doc));assertEquals(before,snapshot(doc));
 }
 @Test void revisionsRequireRetrieveAndBothCapabilitiesRetainKnowledgeBaseAllowList()throws Exception{
  String doc=f.prepared(),chunk=f.ids(doc).getFirst(),path="/api/v1/chunks/"+doc+"/"+chunk;revision(doc,chunk,1);revision(doc,chunk,2);var before=snapshot(doc);String ingest=key("ingest",false,"trace-kb"),retrieve=key("retrieve",false,"trace-kb"),wrongFull=key("ingest",true,"other-kb");
  request("GET",path+"/revisions",ingest,true,403);var result=request("GET",path+"/revisions",retrieve,true,200);assertEquals(2,result.path("data").size());assertEquals(2,result.path("data").get(0).path("revision").asInt());assertEquals("Revision 2 中文",result.path("data").get(0).path("content").asText());assertEquals(8,result.path("data").get(0).path("tenant_id").asLong());request("DELETE",path,retrieve,true,403);request("DELETE",path,wrongFull,true,403);request("GET",path+"/revisions",wrongFull,true,403);request("DELETE",path,ingest,true,200);assertEquals(before,snapshot(doc));
 }
 List<Object> snapshot(String doc)throws Exception{
  long physical=f.f.jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Long>)connection->{new com.tencent.weknora.retrieval.SQLiteVectorExtension(java.nio.file.Path.of(System.getProperty("weknora.sqlite.vec.extension"))).load(connection);try(var statement=connection.prepareStatement("SELECT COUNT(*) FROM vec_embeddings_3 v JOIN lite_embeddings e ON e.id=v.rowid WHERE e.knowledge_id=?")){statement.setString(1,doc);try(var rs=statement.executeQuery()){rs.next();return rs.getLong(1);}}});
  long fts=f.f.jdbc.queryForObject("SELECT COUNT(*) FROM lite_embeddings_fts t JOIN lite_embeddings e ON e.id=t.rowid WHERE e.knowledge_id=?",Long.class,doc);assertTrue(physical>0);assertTrue(fts>0);
  String path=f.f.jdbc.queryForObject("SELECT file_path FROM knowledges WHERE id=?",String.class,doc);byte[] source;try(var in=storage.forKnowledgeBase(8,"trace-kb").files().open(path)){source=in.readAllBytes();}assertTrue(source.length>0);
  return List.of(physical,fts,f.f.count("lite_embeddings",doc),f.f.jdbc.queryForObject("SELECT storage_used FROM tenants WHERE id=8",Long.class),f.f.jdbc.queryForObject("SELECT COUNT(*) FROM java_task_jobs",Long.class),f.calls.get(),Base64.getEncoder().encodeToString(source),f.f.jdbc.queryForMap("SELECT parse_status,storage_size,CASE WHEN deleted_at IS NOT NULL THEN 1 ELSE 0 END AS deleted FROM knowledges WHERE id=?",doc));
 }
}
