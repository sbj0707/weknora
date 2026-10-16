package com.tencent.weknora.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.security.AuthFilter;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Actual authentication/production tag beans/SQL queue/physical cleanup with Hikari pool one. */
@org.springframework.boot.test.context.SpringBootTest(properties={"weknora.jwt-secret=isolated-native-pipeline-trace-secret","weknora.bridge.enabled=false","weknora.knowledge.processing.enabled=true","weknora.knowledge.upload.enabled=true","weknora.tasks.enabled=true","weknora.tasks.poll-millis=3600000","weknora.retrieve-driver=sqlite","SSRF_WHITELIST=127.0.0.1","SSRF_DNS_WHITELIST_ONLY=true","NEO4J_ENABLE=false","logging.level.root=WARN","debug=false"})
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class KnowledgeTagPipelineHttpIntegrationTest {
 @org.springframework.test.context.DynamicPropertySource static void properties(org.springframework.test.context.DynamicPropertyRegistry p){IndexDeletePipelineHttpIntegrationTest.properties(p);}
 @Autowired AutowireCapableBeanFactory beans;
 @Autowired KnowledgeListDeleteHandler documentCleanup;
 final IndexDeletePipelineHttpIntegrationTest f=new IndexDeletePipelineHttpIntegrationTest();final ObjectMapper json=new ObjectMapper();final List<String> keys=new ArrayList<>();
 @BeforeEach void setup()throws Exception{beans.autowireBean(f);f.setup();f.f.jdbc.update("UPDATE tenant_members SET role='admin' WHERE user_id IN ('owner','caller')");f.f.jdbc.update("UPDATE kb_shares SET permission='editor',deleted_at=NULL WHERE id='trace-share'");f.f.jdbc.update("UPDATE organization_tenant_members SET role='editor' WHERE id='trace-target'");}
 @AfterEach void release(){for(String key:keys)f.f.jdbc.update("DELETE FROM tenant_api_keys WHERE key_hash=?",AuthFilter.hashApiKey(key));f.f.jdbc.update("UPDATE tenant_members SET role='admin' WHERE user_id IN ('owner','caller')");f.f.jdbc.update("UPDATE kb_shares SET permission='editor',deleted_at=NULL WHERE id='trace-share'");f.f.jdbc.update("UPDATE knowledge_bases SET type='document' WHERE id='trace-kb'");f.f.jdbc.update("UPDATE users SET tenant_id=8 WHERE id='owner'");f.release();}
 JsonNode request(String method,String target,String body,String credential,boolean machine,int status)throws Exception{var builder=MockMvcRequestBuilders.request(HttpMethod.valueOf(method),"/api/v1/knowledge-bases/trace-kb/tags"+target).contentType("application/json").content(body);if(machine)builder.header("X-API-Key",credential);else builder.header("Authorization","Bearer "+credential);var response=f.f.mvc.perform(builder).andReturn().getResponse();assertEquals(status,response.getStatus(),response.getContentAsString());return json.readTree(response.getContentAsByteArray());}
 String create(String token)throws Exception{return request("POST","","{\"name\":\"Public "+UUID.randomUUID()+"\"}",token,false,200).path("data").path("id").asText();}
 String key(long owner,String capability,String scope,boolean full){String value="tag-key-"+UUID.randomUUID();keys.add(value);f.f.jdbc.update("INSERT INTO tenant_api_keys(tenant_id,name,key_hash,api_key,full_access,capabilities,knowledge_base_ids) VALUES(?,'Tag operator',?,?,?,?,?)",owner,AuthFilter.hashApiKey(value),value,full,"[\""+capability+"\"]","[\""+scope+"\"]");return value;}
 @Test void sharedPublicMutationProducesOwnerCleanupTaskAndDeletesEveryPhysicalChunkSource()throws Exception{
  String doc=f.prepared(),id=create(f.f.callerToken);var chunks=f.ids(doc);assertFalse(chunks.isEmpty());long physical=f.f.count("lite_embeddings",doc),storage=f.f.jdbc.queryForObject("SELECT storage_used FROM tenants WHERE id=8",Long.class);int calls=f.calls.get();
  f.f.jdbc.update("UPDATE knowledge_bases SET type='faq' WHERE id='trace-kb'");f.f.jdbc.update("UPDATE chunks SET chunk_type='faq',tag_id=? WHERE knowledge_id=?",id,doc);
  request("PUT","/"+id,"{\"name\":\"Public renamed\"}",f.f.callerToken,false,200);
  var listed=request("GET","","",f.f.callerToken,false,200).path("data").path("data");assertTrue(listed.isArray());boolean found=false;for(var row:listed)if(row.path("id").asText().equals(id)){found=true;assertEquals(chunks.size(),row.path("chunk_count").asInt());}assertTrue(found);
  request("DELETE","/"+id+"?force=true","",f.f.callerToken,false,200);
  assertEquals(0,f.f.jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_tags WHERE id=?",Integer.class,id));assertEquals(chunks.size(),f.f.jdbc.queryForObject("SELECT COUNT(*) FROM chunks WHERE knowledge_id=? AND deleted_at IS NOT NULL",Integer.class,doc));assertEquals(physical,f.f.count("lite_embeddings",doc));
  var job=f.f.claim("low");assertEquals("index:delete",job.type());assertEquals(8,job.tenantId());assertEquals(11,job.maxAttempts());assertEquals(3600000,job.timeoutMillis());assertEquals("faq",job.payload().path("kb_type").asText());assertEquals(chunks.size(),job.payload().path("chunk_ids").size());assertEquals(chunks.size(),f.cleanup.execute(job,f.f.cancellation(job)).path("chunk_ids").asInt());assertTrue(f.f.tasks.complete(job,json.createObjectNode()));assertEquals(0,f.f.count("lite_embeddings",doc));assertEquals(calls,f.calls.get());assertEquals(storage,f.f.jdbc.queryForObject("SELECT storage_used FROM tenants WHERE id=8",Long.class));
  var audit=f.f.jdbc.queryForMap("SELECT tenant_id,actor_user_id,actor_role,scope_id FROM audit_logs WHERE action='tag.deleted' AND target_id=? ORDER BY id DESC LIMIT 1",id);assertEquals(8L,((Number)audit.get("tenant_id")).longValue());assertEquals("caller",audit.get("actor_user_id"));assertEquals("admin",audit.get("actor_role"));assertEquals("trace-kb",audit.get("scope_id"));
 }
 @Test void actualApiKeysRequireIngestForWritesRetrieveForReadsAndKeepKbAllowlist()throws Exception{
  String retrieve=key(8,"retrieve","trace-kb",false),ingest=key(8,"ingest","trace-kb",false),outside=key(8,"ingest","other",false);
  request("GET","","",retrieve,true,200);request("POST","","{",retrieve,true,403);request("POST","","{\"name\":\"Machine "+UUID.randomUUID()+"\"}",ingest,true,200);request("GET","","",ingest,true,403);var denied=request("POST","","{",outside,true,403);assertEquals(1002,denied.path("error").path("code").asInt());
 }
 @Test void ownCreatorViewerCanMutateButNonCreatorContributorIsDeniedBeforeBinding()throws Exception{
  f.f.jdbc.update("UPDATE tenant_members SET role='viewer' WHERE user_id='owner' AND tenant_id=8");create(f.f.token("owner",8));f.f.jdbc.update("INSERT INTO tenant_members(user_id,tenant_id,role,status) VALUES('caller',8,'contributor','active') ON CONFLICT(user_id,tenant_id) DO UPDATE SET role='contributor'");try{var denied=request("POST","","{",f.f.token("caller",8),false,403);assertEquals("Forbidden: must own the resource or have the required role",denied.path("error").asText());}finally{f.f.jdbc.update("DELETE FROM tenant_members WHERE user_id='caller' AND tenant_id=8");}
 }
 @Test void scopedSharedMachineRemainsViewerAndFullKeyRequiresCurrentSharedEditorGrant()throws Exception{
  String scoped=key(7,"ingest","trace-kb",false),full=key(7,"ingest","trace-kb",true);request("POST","","{",scoped,true,403);request("POST","","{\"name\":\"Full "+UUID.randomUUID()+"\"}",full,true,200);f.f.jdbc.update("UPDATE kb_shares SET deleted_at=CURRENT_TIMESTAMP WHERE id='trace-share'");request("POST","","{",full,true,403);
 }
 @Test void foreignCallerCannotReadOrMutateAndSharedViewerCannotWrite()throws Exception{
  request("GET","","",f.f.foreignToken,false,403);request("POST","","{",f.f.foreignToken,false,403);f.f.jdbc.update("UPDATE organization_tenant_members SET role='viewer' WHERE id='trace-target'");request("GET","","",f.f.callerToken,false,200);request("POST","","{",f.f.callerToken,false,403);
 }
 @Test void sharedDocumentContentDeletionRunsOwnerWorkerAndKeepsTag()throws Exception{deleteDocument(true,true);}
 @Test void ownDocumentForceDeletionRunsWorkerAndRemovesTag()throws Exception{deleteDocument(false,false);}
 @Test void actualMachineDocumentDeletionCarriesKeyAuditWithoutInventingHumanActor()throws Exception{deleteDocument(false,true,true);}
 void deleteDocument(boolean shared,boolean contentOnly)throws Exception{
  deleteDocument(shared,contentOnly,false);
 }
 void deleteDocument(boolean shared,boolean contentOnly,boolean machine)throws Exception{
  String doc=f.prepared();if(machine)f.f.jdbc.update("UPDATE users SET tenant_id=0 WHERE id='owner' AND tenant_id=8");String token=machine?key(8,"ingest","trace-kb",false):shared?f.f.callerToken:f.f.token("owner",8),id=machine?request("POST","","{\"name\":\"Machine delete "+UUID.randomUUID()+"\"}",token,true,200).path("data").path("id").asText():create(token);f.f.jdbc.update("INSERT INTO knowledge_tag_relations(knowledge_id,tag_id) VALUES(?,?)",doc,id);
  long storage=f.f.jdbc.queryForObject("SELECT storage_used FROM tenants WHERE id=8",Long.class),size=f.f.jdbc.queryForObject("SELECT storage_size FROM knowledges WHERE id=?",Long.class,doc);int calls=f.calls.get();long physical=f.f.count("lite_embeddings",doc);
  request("DELETE","/"+id+(contentOnly?"?content_only=true":"?force=true"),"",token,machine,200);
  assertEquals(contentOnly?1:0,f.f.jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_tags WHERE id=?",Integer.class,id));assertEquals(physical,f.f.count("lite_embeddings",doc));assertEquals("completed",f.f.jdbc.queryForObject("SELECT parse_status FROM knowledges WHERE id=?",String.class,doc));
  var job=f.f.claim("low");assertEquals("knowledge:list_delete",job.type());assertEquals(8,job.tenantId());assertEquals(4,job.maxAttempts());assertEquals(7200000,job.timeoutMillis());assertEquals(machine?"":shared?"caller":"owner",job.payload().path("initiator").path("user_id").asText());assertEquals("trace-kb",job.payload().path("knowledge_base_id").asText());assertEquals(doc,job.payload().path("knowledge_ids").get(0).asText());if(machine){assertTrue(job.payload().path("initiator").path("api_key_id").asLong()>0);assertEquals("Tag operator",job.payload().path("initiator").path("api_key_name").asText());}
  assertEquals(1,documentCleanup.execute(job,f.f.cancellation(job)).path("deleted").asInt());assertTrue(f.f.tasks.complete(job,json.createObjectNode()));assertEquals(0,f.f.count("lite_embeddings",doc));assertEquals(calls,f.calls.get());assertEquals(Math.max(0,storage-size),f.f.jdbc.queryForObject("SELECT storage_used FROM tenants WHERE id=8",Long.class));assertEquals(0,f.f.jdbc.queryForObject("SELECT COUNT(*) FROM chunks WHERE knowledge_id=? AND deleted_at IS NULL",Integer.class,doc));assertEquals(1,f.f.jdbc.queryForObject("SELECT COUNT(*) FROM knowledges WHERE id=? AND deleted_at IS NOT NULL",Integer.class,doc));assertEquals(0,f.f.jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_tag_relations WHERE knowledge_id=?",Integer.class,doc));
  var audit=f.f.jdbc.queryForMap("SELECT tenant_id,actor_user_id,details FROM audit_logs WHERE action='knowledge.batch_deleted' AND scope_id='trace-kb' ORDER BY id DESC LIMIT 1");assertEquals(8L,((Number)audit.get("tenant_id")).longValue());assertEquals(machine?"":shared?"caller":"owner",audit.get("actor_user_id"));var detail=json.readTree(audit.get("details").toString());assertEquals(job.id(),detail.path("task_id").asText());if(machine){assertEquals(job.payload().path("initiator").path("api_key_id"),detail.path("api_key_id"));assertEquals("Tag operator",detail.path("api_key_name").asText());var tagAudit=f.f.jdbc.queryForMap("SELECT actor_user_id,actor_role,details FROM audit_logs WHERE action='tag.updated' AND target_id=? ORDER BY id DESC LIMIT 1",id);assertEquals("system-8",tagAudit.get("actor_user_id"));assertEquals("viewer",tagAudit.get("actor_role"));assertEquals("Tag operator",json.readTree(tagAudit.get("details").toString()).path("api_key_name").asText());}
 }
 JsonNode batchTags(String credential,boolean machine,String body,int status)throws Exception{
  var request=MockMvcRequestBuilders.put("/api/v1/knowledge/tags").contentType("application/json").content(body);if(machine)request.header("X-API-Key",credential);else request.header("Authorization","Bearer "+credential);var response=f.f.mvc.perform(request).andReturn().getResponse();assertEquals(status,response.getStatus(),response.getContentAsString());return json.readTree(response.getContentAsByteArray());
 }
 @Test void batchDocumentTagsKeepSourceIndexesQuotasJobsAndSharedExecutionOwner()throws Exception{
  String doc=f.prepared(),tag=create(f.f.callerToken);var row=json.valueToTree(f.f.jdbc.queryForMap("SELECT * FROM knowledges WHERE id=?",doc));long quota=f.f.jdbc.queryForObject("SELECT storage_used FROM tenants WHERE id=8",Long.class),jobs=f.f.jdbc.queryForObject("SELECT COUNT(*) FROM java_task_jobs",Long.class),physical=f.f.count("lite_embeddings",doc);int calls=f.calls.get();
  batchTags(f.f.callerToken,false,json.createObjectNode().put("kb_id","trace-kb").set("updates",json.createObjectNode().set(doc,json.createArrayNode().add(tag).add(tag).add(""))).toString(),200);
  assertEquals(List.of(tag),f.f.jdbc.queryForList("SELECT tag_id FROM knowledge_tag_relations WHERE knowledge_id=?",String.class,doc));
  batchTags(f.f.callerToken,false,json.createObjectNode().set("updates",json.createObjectNode().putNull(doc)).toString(),200);assertEquals(0,f.f.jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_tag_relations WHERE knowledge_id=?",Integer.class,doc));
  f.f.jdbc.update("UPDATE kb_shares SET deleted_at=CURRENT_TIMESTAMP WHERE id='trace-share'");batchTags(f.f.callerToken,false,json.createObjectNode().put("kb_id","trace-kb").set("updates",json.createObjectNode().set(doc,json.createArrayNode().add(tag))).toString(),403);
  assertEquals(row,json.valueToTree(f.f.jdbc.queryForMap("SELECT * FROM knowledges WHERE id=?",doc)));assertEquals(quota,f.f.jdbc.queryForObject("SELECT storage_used FROM tenants WHERE id=8",Long.class));assertEquals(jobs,f.f.jdbc.queryForObject("SELECT COUNT(*) FROM java_task_jobs",Long.class));assertEquals(physical,f.f.count("lite_embeddings",doc));assertEquals(calls,f.calls.get());
 }
 @Test void batchDocumentTagsUseProductionApiKeyScopeAndContributorFloorBeforeJson()throws Exception{
  String doc=f.prepared(),tag=create(f.f.callerToken);String body=json.createObjectNode().put("kb_id","trace-kb").set("updates",json.createObjectNode().set(doc,json.createArrayNode().add(tag))).toString();
  batchTags(key(8,"retrieve","trace-kb",false),true,"{",403);batchTags(key(8,"ingest","other",true),true,body,403);batchTags(key(8,"ingest","trace-kb",false),true,body,200);batchTags(key(7,"ingest","trace-kb",false),true,body,403);batchTags(key(7,"ingest","trace-kb",true),true,body,200);
  f.f.jdbc.update("UPDATE tenant_members SET role='viewer' WHERE user_id='owner' AND tenant_id=8");var denied=batchTags(f.f.token("owner",8),false,"{",403);assertEquals("Forbidden: insufficient workspace role",denied.path("error").asText());
 }

}
