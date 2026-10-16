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

/** Full JWT/API-key filters, real uploaded source, sqlite-vec/FTS and pool-one folder operations. */
@org.springframework.boot.test.context.SpringBootTest(properties={"weknora.jwt-secret=isolated-native-pipeline-trace-secret","weknora.bridge.enabled=false","weknora.knowledge.processing.enabled=true","weknora.knowledge.upload.enabled=true","weknora.tasks.enabled=true","weknora.tasks.poll-millis=3600000","weknora.retrieve-driver=sqlite","SSRF_WHITELIST=127.0.0.1","SSRF_DNS_WHITELIST_ONLY=true","NEO4J_ENABLE=false","logging.level.root=WARN","debug=false"})
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class KnowledgeFolderPipelineHttpIntegrationTest {
 @org.springframework.test.context.DynamicPropertySource static void properties(org.springframework.test.context.DynamicPropertyRegistry p){IndexDeletePipelineHttpIntegrationTest.properties(p);}
 @Autowired AutowireCapableBeanFactory beans;@Autowired StorageResolver storage;
 final IndexDeletePipelineHttpIntegrationTest f=new IndexDeletePipelineHttpIntegrationTest();final ObjectMapper json=new ObjectMapper();final List<String> keys=new ArrayList<>();
 @BeforeEach void setup()throws Exception{beans.autowireBean(f);f.setup();f.f.jdbc.update("UPDATE tenant_members SET role='admin' WHERE user_id IN ('owner','caller')");f.f.jdbc.update("UPDATE kb_shares SET permission='editor',deleted_at=NULL WHERE id='trace-share'");f.f.jdbc.update("UPDATE organization_tenant_members SET role='editor' WHERE id='trace-target'");}
 @AfterEach void release(){for(String key:keys)f.f.jdbc.update("DELETE FROM tenant_api_keys WHERE key_hash=?",AuthFilter.hashApiKey(key));f.f.jdbc.update("UPDATE tenant_members SET role='admin' WHERE user_id IN ('owner','caller')");f.f.jdbc.update("UPDATE kb_shares SET permission='editor',deleted_at=NULL WHERE id='trace-share'");f.release();}
 JsonNode request(String method,String path,String credential,boolean machine,int status)throws Exception{var builder=MockMvcRequestBuilders.request(HttpMethod.valueOf(method),path);builder.header(machine?"X-API-Key":"Authorization",machine?credential:"Bearer "+credential);var response=f.f.mvc.perform(builder).andReturn().getResponse();assertEquals(status,response.getStatus(),response.getContentAsString());return json.readTree(response.getContentAsByteArray());}
 String key(String capability,boolean full,String kb)throws Exception{String value="folder-key-"+UUID.randomUUID();keys.add(value);f.f.jdbc.update("INSERT INTO tenant_api_keys(tenant_id,name,key_hash,api_key,full_access,capabilities,knowledge_base_ids) VALUES(8,'Chunk operator',?,?,?,?,?)",AuthFilter.hashApiKey(value),value,full,json.writeValueAsString(List.of(capability)),json.writeValueAsString(List.of(kb)));return value;}

 JsonNode request(String method,String path,String credential,boolean machine,int status,String body)throws Exception {var builder=MockMvcRequestBuilders.request(HttpMethod.valueOf(method),path).contentType("application/json").content(body);builder.header(machine?"X-API-Key":"Authorization",machine?credential:"Bearer "+credential);var response=f.f.mvc.perform(builder).andReturn().getResponse();assertEquals(status,response.getStatus(),response.getContentAsString());return json.readTree(response.getContentAsByteArray());}
 @Test void creatorViewerTreeAndSubtreeRenameRetainAllSourceAndIndexes()throws Exception {
  String doc=f.prepared();f.f.jdbc.update("UPDATE knowledges SET folder_path='A/说明' WHERE id=?",doc);var before=snapshot(doc);
  f.f.jdbc.update("UPDATE tenant_members SET role='viewer' WHERE user_id='owner' AND tenant_id=8");String token=f.f.token("owner",8),path="/api/v1/knowledge-bases/trace-kb/knowledge/folders";
  var tree=request("GET",path,token,false,200);assertTrue(tree.path("data").path("total_document_count").asLong()>0);assertTrue(tree.path("data").path("folders").toString().contains("A/说明"));
  var result=request("PUT",path,token,false,200,"{\"from\":\"A\",\"to\":\"新目录\"}");assertEquals(1,result.path("data").path("moved_count").asLong());assertEquals("新目录/说明",f.f.jdbc.queryForObject("SELECT folder_path FROM knowledges WHERE id=?",String.class,doc));assertEquals(before,snapshot(doc));
  request("POST","/api/v1/knowledge/folder",token,false,403,"{}");
 }
 @Test void sharedViewerReadAndContributorEditorRefileUseOriginalActorAndOwner()throws Exception {
  String doc=f.prepared();var before=snapshot(doc);String path="/api/v1/knowledge-bases/trace-kb/knowledge/folders";
  f.f.jdbc.update("UPDATE tenant_members SET role='viewer' WHERE user_id='caller'");request("GET",path,f.f.callerToken,false,200);request("PUT",path,f.f.callerToken,false,403,"invalid");request("POST","/api/v1/knowledge/folder",f.f.callerToken,false,403,"invalid");
  f.f.jdbc.update("UPDATE tenant_members SET role='contributor' WHERE user_id='caller'");var moved=request("POST","/api/v1/knowledge/folder",f.f.callerToken,false,200,json.writeValueAsString(Map.of("kb_id","trace-kb","knowledge_ids",List.of(" "+doc+" ",doc),"folder_path"," ./共享目录./子目录 ")));
  assertEquals(1,moved.path("data").path("moved_count").asLong());assertEquals("共享目录/子目录",f.f.jdbc.queryForObject("SELECT folder_path FROM knowledges WHERE id=?",String.class,doc));assertEquals(8,f.f.jdbc.queryForObject("SELECT tenant_id FROM knowledges WHERE id=?",Long.class,doc));assertEquals(before,snapshot(doc));
  var renamed=request("PUT",path,f.f.callerToken,false,200,"{\"from\":\"共享目录\",\"to\":\"重新归类\"}");assertEquals(1,renamed.path("data").path("moved_count").asLong());assertEquals(before,snapshot(doc));
 }
 @Test void apiCapabilitiesAndFullKeyAllowlistPrecedeBinderAndEveryWrite()throws Exception {
  String doc=f.prepared();var before=snapshot(doc);String path="/api/v1/knowledge-bases/trace-kb/knowledge/folders";
  String ingest=key("ingest",false,"trace-kb"),retrieve=key("retrieve",false,"trace-kb"),wrongFull=key("ingest",true,"other-kb");
  request("GET",path,ingest,true,403);request("GET",path,retrieve,true,200);request("PUT",path,retrieve,true,403,"invalid");request("GET",path,wrongFull,true,403);request("PUT",path,wrongFull,true,403,"invalid");
  String payload=json.writeValueAsString(Map.of("kb_id","trace-kb","knowledge_ids",List.of(doc),"folder_path","新增"));request("POST","/api/v1/knowledge/folder",retrieve,true,403,payload);request("POST","/api/v1/knowledge/folder",wrongFull,true,403,payload);request("POST","/api/v1/knowledge/folder",ingest,true,200,payload);
  assertEquals(before,snapshot(doc));request("PUT",path,ingest,true,200,"{\"from\":\"新增\",\"to\":\"完成归类\"}");assertEquals(before,snapshot(doc));
 }
 List<Object> snapshot(String doc)throws Exception{
  List<Map<String,Object>> vectors=f.f.jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<List<Map<String,Object>>>)connection->{new com.tencent.weknora.retrieval.SQLiteVectorExtension(java.nio.file.Path.of(System.getProperty("weknora.sqlite.vec.extension"))).load(connection);try(var statement=connection.prepareStatement("SELECT hex(v.embedding) AS vector,e.source_id,e.source_type FROM vec_embeddings_3 v JOIN lite_embeddings e ON e.id=v.rowid WHERE e.knowledge_id=? ORDER BY e.id")){statement.setString(1,doc);try(var rs=statement.executeQuery()){var out=new ArrayList<Map<String,Object>>();while(rs.next())out.add(Map.of("vector",rs.getString(1),"source_id",rs.getString(2),"source_type",rs.getInt(3)));return out;}}});long physical=vectors.size();

  long fts=f.f.jdbc.queryForObject("SELECT COUNT(*) FROM lite_embeddings_fts t JOIN lite_embeddings e ON e.id=t.rowid WHERE e.knowledge_id=?",Long.class,doc);assertTrue(physical>0);assertTrue(fts>0);
  String path=f.f.jdbc.queryForObject("SELECT file_path FROM knowledges WHERE id=?",String.class,doc);byte[] source;try(var in=storage.forKnowledgeBase(8,"trace-kb").files().open(path)){source=in.readAllBytes();}assertTrue(source.length>0);
  return List.of(vectors,physical,fts,f.f.count("lite_embeddings",doc),f.f.jdbc.queryForObject("SELECT storage_used FROM tenants WHERE id=8",Long.class),f.f.jdbc.queryForObject("SELECT COUNT(*) FROM java_task_jobs",Long.class),f.calls.get(),Base64.getEncoder().encodeToString(source),json.valueToTree(f.f.jdbc.queryForList("SELECT * FROM lite_embeddings WHERE knowledge_id=? ORDER BY id",doc)),json.valueToTree(f.f.jdbc.queryForList("SELECT * FROM chunks WHERE knowledge_id=? ORDER BY id",doc)),f.f.jdbc.queryForMap("SELECT parse_status,storage_size,CASE WHEN deleted_at IS NOT NULL THEN 1 ELSE 0 END AS deleted FROM knowledges WHERE id=?",doc));
 }
}
