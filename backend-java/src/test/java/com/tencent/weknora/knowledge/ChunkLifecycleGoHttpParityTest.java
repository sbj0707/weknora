package com.tencent.weknora.knowledge;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.tencent.weknora.security.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.*;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Actual Go handlers/services/middleware, injected identities, and real physical FTS retention. */
class ChunkLifecycleGoHttpParityTest {
 @TempDir Path temp;final ObjectMapper json=new ObjectMapper();
 static{try{com.tencent.weknora.retrieval.NativeDatabaseRuntime.initialize();}catch(java.sql.SQLException e){throw new ExceptionInInitializerError(e);}}
 @TestFactory Stream<DynamicTest> goChunkLifecycle()throws Exception{JsonNode cases;try(var in=getClass().getResourceAsStream("/go-chunk-delete-http.json")){cases=json.readTree(in);}assertThat(cases.size()).isEqualTo(117);var seq=new AtomicInteger();return StreamSupport.stream(cases.spliterator(),false).map(row->DynamicTest.dynamicTest(row.path("name").asText(),()->compare(row,seq.incrementAndGet())));}
 void compare(JsonNode oracle,int no)throws Exception{
  var f=database(no);try{String variant=oracle.path("variant").asText();
   f.jdbc.update("UPDATE knowledge_bases SET creator_id='caller',embedding_model_id='',summary_model_id='' WHERE id='kb'");f.jdbc.update("INSERT INTO knowledge_bases(id,tenant_id,creator_id,name,type,embedding_model_id,summary_model_id) VALUES('other-kb',1,'other','Other','document','','')");
   for(String id:List.of("doc","other"))f.jdbc.update("INSERT INTO knowledges(id,tenant_id,knowledge_base_id,type,title,source,parse_status,created_at,updated_at) VALUES(?,1,'kb','file','Document','manual','completed',?,?)",id,seedTime(),seedTime());
   int i=0;for(String id:List.of("chunk","child","other","foreign","wrong-kb"))f.jdbc.update("INSERT INTO chunks(id,tenant_id,knowledge_id,knowledge_base_id,content,chunk_index,start_at,end_at,is_enabled,chunk_type,parent_chunk_id,status,content_revision,created_at,updated_at) VALUES(?,?,?,?,?,?,0,20,true,?,?,0,3,?,?)",id,id.equals("foreign")?2:1,id.equals("other")?"other":"doc",id.equals("wrong-kb")?"other-kb":"kb","Chunk content "+id,i++,id.equals("child")?"image_caption":"text",id.equals("child")?"chunk":"",seedTime(),seedTime());
   for(i=1;i<=2;i++)f.jdbc.update("INSERT INTO chunk_revisions(id,tenant_id,knowledge_base_id,knowledge_id,chunk_id,revision,content,is_enabled,editor_id,edit_source,edited_at,created_at) VALUES(?,1,'kb','doc','chunk',?,?,?,'caller','manual',?,?)","revision-"+i,i,"Original "+i+" 中文",i==2,seedTime(),seedTime());
   seedPhysicalIndex(f);
   var metadata=Map.ofEntries(Map.entry("moving","{\"_knowledge_transfer\":{\"operation\":\"move\",\"phase\":\"moving\"}}"),Map.entry("moving-copy","{\"_knowledge_transfer\":{\"operation\":\"copy\",\"phase\":\"moving\"}}"),Map.entry("moving-phase-only","{\"_knowledge_transfer\":{\"phase\":\"moving\"}}"),Map.entry("moving-casefold","{\"_knowledge_transfer\":{\"OPERATION\":\"move\",\"PHASE\":\"moving\"}}"),Map.entry("moving-null-operation","{\"_knowledge_transfer\":{\"operation\":null,\"phase\":\"moving\"}}"),Map.entry("moving-duplicate-null","{\"_knowledge_transfer\":{\"operation\":\"move\",\"operation\":null,\"phase\":\"moving\"}}"),Map.entry("moving-invalid-operation","{\"_knowledge_transfer\":{\"operation\":4,\"operation\":\"move\",\"phase\":\"moving\"}}"),Map.entry("moving-invalid-metadata","{\"_knowledge_transfer\":4}"),Map.entry("moving-root-null","null"),Map.entry("moving-root-array","[]"),Map.entry("moving-outer-casefold","{\"_KNOWLEDGE_TRANSFER\":{\"operation\":\"move\",\"phase\":\"moving\"}}"));
   if(metadata.containsKey(variant))metadata(f,metadata.get(variant));
   switch(variant){
    case "missing-kb"->f.jdbc.update("DELETE FROM knowledge_bases WHERE id='kb'");
    case "deleted-kb"->f.jdbc.update("UPDATE knowledge_bases SET deleted_at=CURRENT_TIMESTAMP WHERE id='kb'");
    case "foreign-document"->f.jdbc.update("UPDATE knowledges SET tenant_id=2 WHERE id='doc'");
    case "deleted-document"->f.jdbc.update("UPDATE knowledges SET deleted_at=CURRENT_TIMESTAMP WHERE id='doc'");
    case "foreign-chunk"->f.jdbc.update("UPDATE chunks SET tenant_id=2 WHERE id='chunk'");
    case "deleted-chunk"->f.jdbc.update("UPDATE chunks SET deleted_at=CURRENT_TIMESTAMP WHERE id='chunk'");
    case "wrong-document"->f.jdbc.update("UPDATE chunks SET knowledge_id='other' WHERE id='chunk'");
    case "wrong-chunk-kb"->f.jdbc.update("UPDATE chunks SET knowledge_base_id='other-kb' WHERE id='chunk'");
    case "empty-revisions"->f.jdbc.update("DELETE FROM chunk_revisions");
    case "revision-table-missing"->f.jdbc.execute("DROP TABLE chunk_revisions");
    case "write-error"->writeFailure(f);
   }
   if(variant.startsWith("shared-")){f.jdbc.update("INSERT INTO organizations(id,name,owner_tenant_id,owner_id) VALUES('org','Org',1,'caller')");f.jdbc.update("INSERT INTO organization_tenant_members(id,organization_id,tenant_id,role) VALUES('source','org',1,'admin'),('member','org',2,'editor')");f.jdbc.update("INSERT INTO kb_shares(id,knowledge_base_id,organization_id,source_tenant_id,permission,shared_by_user_id) VALUES('share','kb','org',1,?,'caller')",variant.equals("shared-viewer")?"viewer":"editor");if(variant.equals("shared-revoked"))f.jdbc.update("UPDATE kb_shares SET deleted_at=CURRENT_TIMESTAMP");}
   var beans=new DefaultListableBeanFactory();beans.registerSingleton("jdbc",f.jdbc);beans.registerSingleton("json",json);beans.registerSingleton("tx",f.manager);beans.registerSingleton("authRepository",new AuthRepository(f.jdbc,json));beans.registerSingleton("codec",new KnowledgeBaseCodec(json));var audit=new RootBeanDefinition(Class.forName("com.tencent.weknora.security.DeniedRoleAuditService"));audit.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);beans.registerBeanDefinition("audit",audit);var writes=beans.createBean(KnowledgeBaseWriteAuthorization.class);beans.registerSingleton("writes",writes);var guard=new RootBeanDefinition(Class.forName("com.tencent.weknora.security.NativeRoleGuard"));guard.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);beans.registerBeanDefinition("guard",guard);
   var reads=new KnowledgeBaseReadAuthorization(f.jdbc,new KnowledgeBaseCodec(json),json,Set.of());
   var scope=oracle.path("scope");var caps=new HashSet<String>();scope.path("Capabilities").forEach(n->caps.add(n.asText()));var allowed=new HashSet<String>();scope.path("KnowledgeBaseIDs").forEach(n->allowed.add(n.asText()));var caller=new AuthContext(oracle.path("user").asText(),oracle.path("caller_tenant").asLong(),oracle.path("effective_role").asText(),false,oracle.path("machine").asBoolean()?1L:null,scope.path("FullAccess").asBoolean(),caps,allowed);
   var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new ChunkLifecycleController(new ChunkLifecycleService(f.jdbc,writes,reads,f.manager,json))).addInterceptors((org.springframework.web.servlet.HandlerInterceptor)beans.getBean("guard")).setControllerAdvice(new com.tencent.weknora.api.ApiErrorAdvice()).build();
   var response=mvc.perform(MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(oracle.path("method").asText()),java.net.URI.create(oracle.path("target").asText())).requestAttr(CurrentAuth.ATTRIBUTE,caller)).andReturn().getResponse();
   assertThat(response.getStatus()).as("actual Go HTTP status").isEqualTo(oracle.path("status").asInt());assertThat(canonical(json.readTree(response.getContentAsByteArray()))).as("complete actual Go envelope / DTO / diagnostics").isEqualTo(canonical(oracle.path("body")));
   assertThat(canonical(json.valueToTree(f.jdbc.queryForList("SELECT id,tenant_id,knowledge_base_id,knowledge_id,content,status,content_revision,CASE WHEN deleted_at IS NOT NULL THEN 1 ELSE 0 END AS deleted FROM chunks ORDER BY id")))).as("exact tombstones, tenant/document/KB/type boundaries").isEqualTo(canonical(oracle.path("chunks")));
   assertThat(canonical(json.valueToTree(physicalIndex(f)))).as("physical FTS remains intact after direct tombstones").isEqualTo(canonical(oracle.path("physical_fts")));
   assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM java_task_jobs",Integer.class)).as("direct chunk API must not enqueue document/index cleanup").isZero();
  }finally{release(f);}
 }
 DocumentProcessingCompatibilityTest database(int no)throws Exception{var f=new DocumentProcessingCompatibilityTest();f.temp=temp;f.database(no,false);return f;}
 Object seedTime(){return "2026-01-01T00:00:00Z";}
 void metadata(DocumentProcessingCompatibilityTest f,String raw){f.jdbc.update("UPDATE knowledges SET metadata=? WHERE id='doc'",raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
 void seedPhysicalIndex(DocumentProcessingCompatibilityTest f){f.jdbc.execute("CREATE VIRTUAL TABLE chunk_delete_fts USING fts5(chunk_id UNINDEXED,content)");f.jdbc.update("INSERT INTO chunk_delete_fts(chunk_id,content) VALUES('chunk','Original physical content'),('child','Image content')");}
 List<Map<String,Object>> physicalIndex(DocumentProcessingCompatibilityTest f){return f.jdbc.queryForList("SELECT chunk_id,content FROM chunk_delete_fts ORDER BY chunk_id");}
 void writeFailure(DocumentProcessingCompatibilityTest f){f.jdbc.execute("CREATE TRIGGER block_chunk_delete BEFORE UPDATE OF deleted_at ON chunks BEGIN SELECT RAISE(ABORT,'controlled chunk write failure'); END");}
 void release(DocumentProcessingCompatibilityTest f){}
 JsonNode canonical(JsonNode value){if(value.isNumber())return DecimalNode.valueOf(value.decimalValue().stripTrailingZeros());if(value.isArray()){var out=json.createArrayNode();value.forEach(n->out.add(canonical(n)));return out;}if(value.isObject()){var out=json.createObjectNode();value.fields().forEachRemaining(e->out.set(e.getKey(),canonical(e.getValue())));return out;}return value;}
}
