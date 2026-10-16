package com.tencent.weknora.knowledge;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.security.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.*;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Independently exported actual Go HTTP, middleware, services, SQL and denied-role audits. */
class KnowledgeFolderGoHttpParityTest {
 @TempDir Path temp;final ObjectMapper json=new ObjectMapper();
 static {try{com.tencent.weknora.retrieval.NativeDatabaseRuntime.initialize();}catch(java.sql.SQLException error){throw new ExceptionInInitializerError(error);}}
 @TestFactory Stream<DynamicTest> folders()throws Exception{JsonNode cases;try(var in=getClass().getResourceAsStream("/go-folder-http.json")){cases=json.readTree(in);}assertThat(cases.size()).isEqualTo(151);var sequence=new AtomicInteger();return StreamSupport.stream(cases.spliterator(),false).map(row->DynamicTest.dynamicTest(row.path("name").asText(),()->compare(row,sequence.incrementAndGet())));}
 void compare(JsonNode oracle,int no)throws Exception {
  var f=database(no);try {
   String variant=oracle.path("variant").asText();
   f.jdbc.update("UPDATE knowledge_bases SET creator_id='caller',embedding_model_id='',summary_model_id='' WHERE id='kb'");f.jdbc.update("INSERT INTO knowledge_bases(id,tenant_id,creator_id,name,type,embedding_model_id,summary_model_id) VALUES('other-kb',1,'other','Other','document','','')");
   var paths=List.of("","A","A/spec","A/spec/deep","B","a%_/sub","C\\windows","中文/说明","e\u0301","é","Z","../A./spec ","A/./spec","aX/sub");
   for(int i=0;i<paths.size();i++)document(f,"d"+i,1,"kb","completed",paths.get(i));
   document(f,"foreign",2,"kb","completed","A");document(f,"other",1,"other-kb","completed","A");document(f,"deleting",1,"kb","deleting","A");document(f,"deleted",1,"kb","completed","A");f.jdbc.update("UPDATE knowledges SET deleted_at=CURRENT_TIMESTAMP WHERE id='deleted'");
   f.jdbc.update("INSERT INTO chunks(id,tenant_id,knowledge_id,knowledge_base_id,content,chunk_index,start_at,end_at,is_enabled,chunk_type,status) VALUES('physical',1,'d1','kb','Unchanged source',0,0,16,true,'text',0)");
   seedPhysicalIndex(f);
   switch(variant){
    case "missing-kb"->f.jdbc.update("DELETE FROM knowledge_bases WHERE id='kb'");
    case "deleted-kb"->f.jdbc.update("UPDATE knowledge_bases SET deleted_at=CURRENT_TIMESTAMP WHERE id='kb'");
    case "moving"->metadata(f,"{\"_knowledge_transfer\":{\"operation\":\"move\",\"phase\":\"moving\"}}");
    case "deleted-doc"->f.jdbc.update("UPDATE knowledges SET deleted_at=CURRENT_TIMESTAMP WHERE id='d1'");
    case "deleted-all"->f.jdbc.update("UPDATE knowledges SET deleted_at=CURRENT_TIMESTAMP");
    case "empty-table"->f.jdbc.update("DELETE FROM knowledges");
    case "empty"->f.jdbc.update("UPDATE knowledges SET folder_path=''");
    case "normalized"->f.jdbc.update("UPDATE knowledges SET folder_path='../A./spec ' WHERE tenant_id=1 AND knowledge_base_id='kb'");
    case "count-table-missing"->f.jdbc.execute("ALTER TABLE knowledges RENAME TO hidden_knowledges");
    case "write-error","partial-failure"->writeFailure(f);
   }
   if(variant.startsWith("shared-")){f.jdbc.update("INSERT INTO organizations(id,name,owner_tenant_id,owner_id) VALUES('org','Org',1,'caller')");f.jdbc.update("INSERT INTO organization_tenant_members(id,organization_id,tenant_id,role) VALUES('source','org',1,'admin'),('member','org',2,'editor')");f.jdbc.update("INSERT INTO kb_shares(id,knowledge_base_id,organization_id,source_tenant_id,permission,shared_by_user_id) VALUES('share','kb','org',1,?,'caller')",variant.equals("shared-viewer")?"viewer":"editor");if(variant.equals("shared-revoked"))f.jdbc.update("UPDATE kb_shares SET deleted_at=CURRENT_TIMESTAMP");}
   String table=variant.equals("count-table-missing")?"hidden_knowledges":"knowledges";
   var before=unchanged(f,table);var chunks=json.<JsonNode>valueToTree(f.jdbc.queryForList("SELECT * FROM chunks ORDER BY id"));var tenant=json.<JsonNode>valueToTree(f.jdbc.queryForList("SELECT * FROM tenants ORDER BY id"));
   var beans=new DefaultListableBeanFactory();beans.registerSingleton("jdbc",f.jdbc);beans.registerSingleton("json",json);beans.registerSingleton("tx",f.manager);beans.registerSingleton("authRepository",new AuthRepository(f.jdbc,json));beans.registerSingleton("codec",new KnowledgeBaseCodec(json));var audit=new RootBeanDefinition(Class.forName("com.tencent.weknora.security.DeniedRoleAuditService"));audit.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);beans.registerBeanDefinition("audit",audit);var writes=beans.createBean(KnowledgeBaseWriteAuthorization.class);beans.registerSingleton("writes",writes);var guard=new RootBeanDefinition(Class.forName("com.tencent.weknora.security.NativeRoleGuard"));guard.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);beans.registerBeanDefinition("guard",guard);
   var reads=new KnowledgeBaseReadAuthorization(f.jdbc,new KnowledgeBaseCodec(json),json,Set.of());var scope=oracle.path("scope");var caps=new HashSet<String>();scope.path("Capabilities").forEach(n->caps.add(n.asText()));var allowed=new HashSet<String>();scope.path("KnowledgeBaseIDs").forEach(n->allowed.add(n.asText()));var caller=new AuthContext(oracle.path("user").asText(),oracle.path("caller_tenant").asLong(),oracle.path("effective_role").asText(),false,oracle.path("machine").asBoolean()?1L:null,scope.path("FullAccess").asBoolean(),caps,allowed);
   var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new KnowledgeFolderController(new KnowledgeFolderService(f.jdbc,reads,writes,f.manager,json),json)).addInterceptors((org.springframework.web.servlet.HandlerInterceptor)beans.getBean("guard")).setControllerAdvice(new com.tencent.weknora.api.ApiErrorAdvice()).build();
   var response=mvc.perform(MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(oracle.path("method").asText()),java.net.URI.create(oracle.path("target").asText())).content(oracle.path("request_body").asText()).contentType("application/json").requestAttr(CurrentAuth.ATTRIBUTE,caller)).andReturn().getResponse();
   assertThat(response.getStatus()).as("actual Go status").isEqualTo(oracle.path("status").asInt());assertThat(canonical(json.readTree(response.getContentAsByteArray()))).as("complete actual Go HTTP/tree/binder/error DTO").isEqualTo(canonical(oracle.path("body")));
   var rows=f.jdbc.queryForList("SELECT id,tenant_id,knowledge_base_id,folder_path,parse_status,CASE WHEN deleted_at IS NOT NULL THEN 1 ELSE 0 END AS deleted,CASE WHEN updated_at=? THEN 0 ELSE 1 END AS updated FROM "+table+" ORDER BY id",seedTime());
   assertThat(canonical(json.valueToTree(rows.isEmpty()?null:rows))).as("exact folder write boundaries, update timestamps, rollback (Go nil export for no rows)").isEqualTo(canonical(oracle.path("documents")));
   var audits=f.jdbc.queryForList("SELECT tenant_id,actor_user_id,actor_role,action,request_path,request_method,outcome,details FROM audit_logs ORDER BY id");for(var entry:audits)entry.put("details",json.readTree(Objects.toString(entry.get("details"))));assertThat(canonical(json.valueToTree(audits.isEmpty()?null:audits))).as("original actor and actual Go denial audit route").isEqualTo(canonical(oracle.path("audits")));
   assertThat(unchanged(f,table)).as("all other document columns unchanged").isEqualTo(before);assertThat(json.<JsonNode>valueToTree(f.jdbc.queryForList("SELECT * FROM chunks ORDER BY id"))).isEqualTo(chunks);assertThat(json.<JsonNode>valueToTree(f.jdbc.queryForList("SELECT * FROM tenants ORDER BY id"))).as("quota unchanged").isEqualTo(tenant);assertThat(f.jdbc.queryForList("SELECT chunk_id,content FROM folder_physical_fts")).containsExactly(Map.of("chunk_id","physical","content","Unchanged source"));assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM java_task_jobs",Integer.class)).as("folder operations never enqueue processing").isZero();
  }finally{release(f);}
 }
 DocumentProcessingCompatibilityTest database(int no)throws Exception{var f=new DocumentProcessingCompatibilityTest();f.temp=temp;f.database(no,false);return f;}
 Object seedTime(){return "2026-01-01T00:00:00Z";}
 void metadata(DocumentProcessingCompatibilityTest f,String raw){f.jdbc.update("UPDATE knowledges SET metadata=? WHERE id='d1'",raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
 void seedPhysicalIndex(DocumentProcessingCompatibilityTest f){f.jdbc.execute("CREATE VIRTUAL TABLE folder_physical_fts USING fts5(chunk_id UNINDEXED,content)");f.jdbc.update("INSERT INTO folder_physical_fts(chunk_id,content) VALUES('physical','Unchanged source')");}
 void document(DocumentProcessingCompatibilityTest f,String id,long owner,String kb,String status,String path){f.jdbc.update("INSERT INTO knowledges(id,tenant_id,knowledge_base_id,type,title,source,parse_status,folder_path,created_at,updated_at) VALUES(?,?,?,'file','Document','manual',?,?,?,?)",id,owner,kb,status,path,seedTime(),seedTime());}
 void writeFailure(DocumentProcessingCompatibilityTest f){f.jdbc.execute("CREATE TRIGGER block_folder BEFORE UPDATE OF folder_path ON knowledges WHEN OLD.id='d2' BEGIN SELECT RAISE(ABORT,'controlled folder write failure'); END");}
 void release(DocumentProcessingCompatibilityTest f){}
 JsonNode unchanged(DocumentProcessingCompatibilityTest f,String table){var rows=f.jdbc.queryForList("SELECT * FROM "+table+" ORDER BY id");rows.forEach(r->{r.remove("folder_path");r.remove("updated_at");});return json.valueToTree(rows);}
 JsonNode canonical(JsonNode value){if(value.isNumber())return DecimalNode.valueOf(value.decimalValue().stripTrailingZeros());if(value.isArray()){var out=json.createArrayNode();value.forEach(n->out.add(canonical(n)));return out;}if(value.isObject()){var out=json.createObjectNode();value.fields().forEachRemaining(e->out.set(e.getKey(),canonical(e.getValue())));return out;}return value;}
}
