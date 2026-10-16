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
class ChunkEditGoHttpParityTest {
 @TempDir Path temp;final ObjectMapper json=new ObjectMapper();
 static{try{com.tencent.weknora.retrieval.NativeDatabaseRuntime.initialize();}catch(java.sql.SQLException e){throw new ExceptionInInitializerError(e);}}
 @TestFactory Stream<DynamicTest> goChunkEdit()throws Exception{JsonNode cases;try(var in=getClass().getResourceAsStream(fixtureResource())){cases=json.readTree(in);}assertThat(cases.size()).isEqualTo(fixtureCount());var seq=new AtomicInteger();return StreamSupport.stream(cases.spliterator(),false).map(row->DynamicTest.dynamicTest(row.path("name").asText(),()->compare(row,seq.incrementAndGet())));}
 void compare(JsonNode oracle,int no)throws Exception{
  var f=database(no);try{String variant=oracle.path("variant").asText();
   f.jdbc.update("UPDATE knowledge_bases SET creator_id='caller',embedding_model_id='',summary_model_id='' WHERE id='kb'");f.jdbc.update("INSERT INTO knowledge_bases(id,tenant_id,creator_id,name,type,embedding_model_id,summary_model_id) VALUES('other-kb',1,'other','Other','document','','')");
   for(String id:List.of("doc","other"))f.jdbc.update("INSERT INTO knowledges(id,tenant_id,knowledge_base_id,type,title,source,parse_status,created_at,updated_at) VALUES(?,1,'kb','file','Document','manual','completed',?,?)",id,seedTime(),seedTime());
   int i=0;for(String id:List.of("chunk","child","other","foreign","wrong-kb"))f.jdbc.update("INSERT INTO chunks(id,tenant_id,knowledge_id,knowledge_base_id,content,chunk_index,start_at,end_at,is_enabled,chunk_type,parent_chunk_id,status,content_revision,created_at,updated_at) VALUES(?,?,?,?,?,?,0,20,true,?,?,0,3,?,?)",id,id.equals("foreign")?2:1,id.equals("other")?"other":"doc",id.equals("wrong-kb")?"other-kb":"kb","Chunk content "+id,i++,id.equals("child")?"image_caption":"text",id.equals("child")?"chunk":"",seedTime(),seedTime());
   for(i=1;i<=2;i++)f.jdbc.update("INSERT INTO chunk_revisions(id,tenant_id,knowledge_base_id,knowledge_id,chunk_id,revision,content,is_enabled,editor_id,edit_source,edited_at,created_at) VALUES(?,1,'kb','doc','chunk',?,?,?,'caller','manual',?,?)","revision-"+i,i,"Original "+i+" 中文",i==2,seedTime(),seedTime());
   seedPhysicalIndex(f);
   f.jdbc.update("UPDATE knowledge_bases SET embedding_model_id='embedding',indexing_strategy='{\"vector_enabled\":true,\"keyword_enabled\":true,\"graph_enabled\":false,\"wiki_enabled\":false}' WHERE id='kb'");
   f.jdbc.update("UPDATE chunks SET metadata='{\"generated_questions\":[{\"id\":\"old-q\",\"question\":\"Old question 中文\",\"content_revision\":1}]}',context_header='## Header' WHERE id='chunk'");
   seedVectors(f);
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
    case "non-text"->f.jdbc.update("UPDATE chunks SET chunk_type='image_ocr' WHERE id='chunk'");
    case "invalid-parent"->f.jdbc.update("UPDATE chunks SET parent_chunk_id='absent' WHERE id='chunk'");
    case "foreign-child"->f.jdbc.update("UPDATE chunks SET knowledge_id='other' WHERE id='child'");
    case "wrong-child-kb"->f.jdbc.update("UPDATE chunks SET knowledge_base_id='other-kb' WHERE id='child'");
    case "no-index"->f.jdbc.update("UPDATE knowledge_bases SET indexing_strategy='{\"vector_enabled\":false,\"keyword_enabled\":false,\"graph_enabled\":false,\"wiki_enabled\":false}' WHERE id='kb'");
    case "model-missing"->f.jdbc.update("UPDATE knowledge_bases SET embedding_model_id='absent' WHERE id='kb'");
    case "index-error"->indexFailure(f);
    case "revision-error"->revisionFailure(f);
    case "existing-source"->f.jdbc.update("UPDATE chunks SET source_content='Immutable original content' WHERE id='chunk'");
    case "index-failed-noop"->f.jdbc.update("UPDATE chunks SET index_status='failed' WHERE id='chunk'");
    case "image-removed"->{f.jdbc.update("UPDATE chunks SET content='Body ![img](page.png)' WHERE id='chunk'");f.jdbc.update("UPDATE chunks SET image_info='[{\"url\":\"page.png\",\"ocr_text\":\"OCR\"}]' WHERE id='child'");}
    case "disabled"->f.jdbc.update("UPDATE chunks SET is_enabled=false WHERE id='chunk'");
    case "summary-existing"->f.jdbc.update("UPDATE knowledges SET summary_status='completed',description='Previous description' WHERE id='doc'");
    case "parent-error"->{f.jdbc.update("INSERT INTO chunks(id,tenant_id,knowledge_id,knowledge_base_id,content,chunk_index,start_at,end_at,chunk_type) VALUES('parent',1,'doc','kb','Original parent source',50,0,20,'text')");f.jdbc.update("UPDATE chunks SET parent_chunk_id='parent' WHERE id='chunk'");parentFailure(f);}
    case "empty-revisions"->f.jdbc.update("DELETE FROM chunk_revisions");
    case "revision-table-missing"->f.jdbc.execute("DROP TABLE chunk_revisions");
    case "write-error"->writeFailure(f);
   }
   extraVariants(f,variant);
   if(variant.startsWith("shared-")){f.jdbc.update("INSERT INTO organizations(id,name,owner_tenant_id,owner_id) VALUES('org','Org',1,'caller')");f.jdbc.update("INSERT INTO organization_tenant_members(id,organization_id,tenant_id,role) VALUES('source','org',1,'admin'),('member','org',2,'editor')");f.jdbc.update("INSERT INTO kb_shares(id,knowledge_base_id,organization_id,source_tenant_id,permission,shared_by_user_id) VALUES('share','kb','org',1,?,'caller')",variant.equals("shared-viewer")?"viewer":"editor");if(variant.equals("shared-revoked"))f.jdbc.update("UPDATE kb_shares SET deleted_at=CURRENT_TIMESTAMP");}
   var beans=new DefaultListableBeanFactory();beans.registerSingleton("jdbc",f.jdbc);beans.registerSingleton("json",json);beans.registerSingleton("tx",f.manager);beans.registerSingleton("authRepository",new AuthRepository(f.jdbc,json));beans.registerSingleton("codec",new KnowledgeBaseCodec(json));var audit=new RootBeanDefinition(Class.forName("com.tencent.weknora.security.DeniedRoleAuditService"));audit.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);beans.registerBeanDefinition("audit",audit);var writes=beans.createBean(KnowledgeBaseWriteAuthorization.class);beans.registerSingleton("writes",writes);var guard=new RootBeanDefinition(Class.forName("com.tencent.weknora.security.NativeRoleGuard"));guard.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);beans.registerBeanDefinition("guard",guard);
   var reads=new KnowledgeBaseReadAuthorization(f.jdbc,new KnowledgeBaseCodec(json),json,Set.of());
   var scope=oracle.path("scope");var caps=new HashSet<String>();scope.path("Capabilities").forEach(n->caps.add(n.asText()));var allowed=new HashSet<String>();scope.path("KnowledgeBaseIDs").forEach(n->allowed.add(n.asText()));var caller=new AuthContext(oracle.path("user").asText(),oracle.path("caller_tenant").asLong(),oracle.path("effective_role").asText(),false,oracle.path("machine").asBoolean()?1L:null,scope.path("FullAccess").asBoolean(),caps,allowed);
   var queued=new ArrayList<Map<String,Object>>();if(refreshVariant(variant)){f.jdbc.update("UPDATE knowledges SET summary_status='completed',description='Previous description' WHERE id='doc'");if(!variant.equals("summary-no-model"))f.jdbc.update("UPDATE knowledge_bases SET summary_model_id='summary' WHERE id='kb'");beans.registerSingleton("producer",(KnowledgeFileUpload.Enqueuer)(owner,type,payload,options)->{if(variant.equals("summary-queue-error"))throw new java.io.IOException("controlled enqueue failure");queued.add(Map.of("type",type,"payload",payload.deepCopy(),"queue",options.queue(),"max_retry",options.maxRetries(),"timeout",options.timeout().toMillis()));return UUID.randomUUID().toString();});}
   var requests=new ArrayList<JsonNode>();var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
   server.createContext("/v1/embeddings",exchange->{var input=json.readTree(exchange.getRequestBody());embeddingMutation(f,variant);requests.add(json.createObjectNode().put("path",exchange.getRequestURI().getPath()).set("body",input));
    switch(variant){
     case "edit-during-provider"->f.jdbc.update("UPDATE chunks SET content='Successor edit',content_revision=5,last_editor_id='successor',index_status='processing' WHERE id='chunk'");
     case "disable-during-provider"->f.jdbc.update("UPDATE chunks SET is_enabled=false,content_revision=5,last_editor_id='successor',index_status='processing' WHERE id='chunk'");
     case "metadata-during-provider"->f.jdbc.update("UPDATE chunks SET metadata='{\"generated_questions\":[{\"id\":\"successor-q\",\"question\":\"Successor question 中文\"}]}' WHERE id='chunk'");
     case "doc-owner-during-provider"->f.jdbc.update("UPDATE knowledges SET tenant_id=2 WHERE id='doc'");
     case "kb-owner-during-provider"->f.jdbc.update("UPDATE knowledge_bases SET tenant_id=2 WHERE id='kb'");
     case "chunk-owner-during-provider"->f.jdbc.update("UPDATE chunks SET tenant_id=2 WHERE id='chunk'");
    }
    byte[] bytes;int status=200;if(embeddingFails(variant)){status=400;bytes="{\"error\":{\"message\":\"controlled edit embedding failure\"}}".getBytes();}else{var data=json.createArrayNode();for(int n=input.path("input").size()-1;n>=0;n--)data.add(json.createObjectNode().put("index",n).set("embedding",json.valueToTree(vector(input.path("input").get(n).asText()))));bytes=json.writeValueAsBytes(json.createObjectNode().set("data",data));}exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}exchange.close();});modelServer(f,variant,server,requests);server.start();
   try {
    var env=new org.springframework.mock.env.MockEnvironment().withProperty("SSRF_WHITELIST","127.0.0.1").withProperty("SSRF_DNS_WHITELIST_ONLY","true");var params=json.createObjectNode().put("provider","generic").put("base_url","http://127.0.0.1:"+server.getAddress().getPort()+"/v1");params.set("embedding_parameters",json.createObjectNode().put("dimension",3).put("truncate_prompt_tokens",511));f.jdbc.update("INSERT INTO models(id,tenant_id,name,type,source,parameters,is_builtin,status) VALUES('embedding',1,'unknown','Embedding','remote',?,false,'active')",params.toString());
    modelRecord(f,params,variant);
    var models=new com.tencent.weknora.model.ModelClientFactory(f.jdbc,json,new GoSecretCodec(""),new com.tencent.weknora.model.ModelCatalogRuntime(f.jdbc,"","/nonexistent"),new com.tencent.weknora.model.ModelEgressPolicy(f.jdbc,json,env),env);
    var refresh=new KnowledgeSummaryRefresh(f.jdbc,json,beans.getBeanProvider(KnowledgeFileUpload.Enqueuer.class),null);
    var service=new ChunkEditService(f.jdbc,json,writes,new KnowledgeContentReadRepository(f.jdbc,json),refresh,models,(owner,kb)->writer(f),f.manager);
    var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller(service,writes,f,env)).addInterceptors((org.springframework.web.servlet.HandlerInterceptor)beans.getBean("guard")).setControllerAdvice(new com.tencent.weknora.api.ApiErrorAdvice()).build();
    var response=mvc.perform(MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(oracle.path("method").asText()),java.net.URI.create(oracle.path("target").asText())).contentType("application/json").content(oracle.path("request").asText()).requestAttr(CurrentAuth.ATTRIBUTE,caller)).andReturn().getResponse();
    assertThat(response.getStatus()).as("actual Go HTTP status").isEqualTo(oracle.path("status").asInt());assertThat(canonical(json.readTree(response.getContentAsByteArray()))).as("complete Go response").isEqualTo(canonical(oracle.path("body")));
    assertThat(canonical(json.valueToTree(sqlRows(f,"SELECT id,tenant_id,knowledge_base_id,knowledge_id,content,source_content,status,content_revision,index_status,last_editor_id,is_enabled,parent_chunk_id,metadata,source_locators,CASE WHEN deleted_at IS NOT NULL THEN 1 ELSE 0 END AS deleted FROM chunks ORDER BY id")))).as("all current row state").isEqualTo(canonical(oracle.path("chunks")));
    assertThat(canonical(json.valueToTree(sqlRows(f,"SELECT * FROM chunk_revisions ORDER BY revision DESC")))).as("complete immutable versions").isEqualTo(canonical(oracle.path("revisions")));
    assertThat(canonical(json.valueToTree(sqlRows(f,"SELECT source_id,source_type,chunk_id,knowledge_id,knowledge_base_id,content,dimension,is_enabled FROM lite_embeddings ORDER BY source_id")))).as("physical source identity and content").isEqualTo(canonical(oracle.path("physical_indexes")));
    var raw=new ArrayList<Map<String,Object>>();for(var row:vecQuery(f,"SELECT e.source_id,e.source_type,v.embedding AS vector FROM lite_embeddings e JOIN vec_embeddings_3 v ON e.id=v.rowid ORDER BY e.source_id,e.source_type")){var bits=new ArrayList<Long>();var b=java.nio.ByteBuffer.wrap((byte[])row.get("vector")).order(java.nio.ByteOrder.LITTLE_ENDIAN);while(b.hasRemaining())bits.add(Integer.toUnsignedLong(b.getInt()));row.put("vector",bits);raw.add(row);}assertThat(canonical(json.valueToTree(raw))).as("exact float32 bits per source/type").isEqualTo(canonical(oracle.path("raw_vectors")));
    assertThat(((Number)vecQuery(f,"SELECT COUNT(*) AS count FROM vec_embeddings_3").getFirst().get("count")).intValue()).isEqualTo(oracle.path("counts").path("Vectors").asInt());assertThat(keywordCount(f)).isEqualTo(oracle.path("counts").path("Keywords").asInt());
    assertThat(canonical(json.valueToTree(queued))).as("real Go Asynq refresh admission payload and options").isEqualTo(canonical(oracle.path("tasks")));
    assertThat(canonical(json.valueToTree(requests))).as("model calls, options and unordered Go map input identity").isEqualTo(canonical(oracle.path("requests")));
    assertThat(canonical(json.valueToTree(sqlRows(f,"SELECT id,tenant_id,knowledge_base_id,parse_status,description,summary_status FROM knowledges ORDER BY id")))).isEqualTo(canonical(oracle.path("documents")));assertThat(canonical(json.valueToTree(physicalIndex(f)))).isEqualTo(canonical(oracle.path("physical_fts")));
   }finally{server.stop(0);}

  }finally{release(f);}
 }
 String fixtureResource(){return "/go-chunk-edit-http.json";}
 int fixtureCount(){return 196;}
 Object controller(ChunkEditService service,KnowledgeBaseWriteAuthorization writes,DocumentProcessingCompatibilityTest f,org.springframework.core.env.Environment env)throws Exception{return new ChunkEditController(service,json,new KnowledgeProcessingLanguage(env));}
 boolean refreshVariant(String variant){return variant.contains("summary-")&&!variant.equals("summary-existing");}
 void embeddingMutation(DocumentProcessingCompatibilityTest f,String variant){}
 boolean embeddingFails(String variant){return variant.equals("provider-error");}
 void modelServer(DocumentProcessingCompatibilityTest f,String variant,com.sun.net.httpserver.HttpServer server,List<JsonNode> requests){}
 void modelRecord(DocumentProcessingCompatibilityTest f,com.fasterxml.jackson.databind.node.ObjectNode params,String variant){}
 DocumentProcessingCompatibilityTest database(int no)throws Exception{var f=new DocumentProcessingCompatibilityTest();f.temp=temp;f.database(no,true);return f;}
 Object seedTime(){return "2026-01-01T00:00:00Z";}
 void metadata(DocumentProcessingCompatibilityTest f,String raw){f.jdbc.update("UPDATE knowledges SET metadata=? WHERE id='doc'",raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
 void seedPhysicalIndex(DocumentProcessingCompatibilityTest f){f.jdbc.execute("CREATE VIRTUAL TABLE chunk_delete_fts USING fts5(chunk_id UNINDEXED,content)");f.jdbc.update("INSERT INTO chunk_delete_fts(chunk_id,content) VALUES('chunk','Original physical content'),('child','Image content')");}
 List<Map<String,Object>> physicalIndex(DocumentProcessingCompatibilityTest f){return f.jdbc.queryForList("SELECT chunk_id,content FROM chunk_delete_fts ORDER BY chunk_id");}
 void writeFailure(DocumentProcessingCompatibilityTest f){f.jdbc.execute("CREATE TRIGGER block_chunk_delete BEFORE UPDATE OF deleted_at ON chunks BEGIN SELECT RAISE(ABORT,'controlled chunk write failure'); END");}
 void release(DocumentProcessingCompatibilityTest f){}
 void extraVariants(DocumentProcessingCompatibilityTest f,String variant){
  if(variant.endsWith("-blob")){String strategy=switch(variant){case "vector-only-blob"->"{\"vector_enabled\":true}";case "keyword-only-blob"->"{\"keyword_enabled\":true}";case "wiki-only-blob"->"{\"wiki_enabled\":true}";case "invalid-index-blob"->"{\"vector_enabled\":4}";default->"{\"vector_enabled\":false,\"keyword_enabled\":false,\"graph_enabled\":false,\"wiki_enabled\":false}";};f.jdbc.update("UPDATE knowledge_bases SET indexing_strategy=? WHERE id='kb'",strategy.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
  if(variant.startsWith("locators-"))f.jdbc.update("UPDATE chunks SET source_locators='[{\"type\":\"text\",\"source_hash\":\"original\",\"start\":2,\"end\":8,\"quote\":\"original quote\"}]' WHERE id='chunk'");
  if(variant.startsWith("parent-")&&!variant.equals("parent-error")){
   f.jdbc.update("INSERT INTO chunks(id,tenant_id,knowledge_id,knowledge_base_id,content,chunk_index,start_at,end_at,chunk_type,source_locators,created_at,updated_at) VALUES('parent',1,'doc','kb','前😀甲乙丙丁后abcdefghijklmnop',50,100,123,'text','[{\"type\":\"text\",\"quote\":\"parent evidence\"}]',?,?)",seedTime(),seedTime());
   f.jdbc.update("UPDATE chunks SET parent_chunk_id='parent',start_at=102,end_at=105 WHERE id='chunk'");
   if(variant.equals("parent-offset-invalid"))f.jdbc.update("UPDATE chunks SET start_at=200,end_at=205 WHERE id='chunk'");
   if(variant.equals("parent-overlap"))f.jdbc.update("INSERT INTO chunks(id,tenant_id,knowledge_id,knowledge_base_id,content,chunk_index,start_at,end_at,chunk_type,parent_chunk_id,content_revision,created_at,updated_at) VALUES('sibling',1,'doc','kb','旧编辑冲突内容 twelve chars more',51,103,107,'text','parent',2,?,?)",seedTime(),seedTime());
  }
  if(variant.startsWith("image-")&&!Set.of("image-added","image-removed").contains(variant)){
   f.jdbc.update("UPDATE chunks SET content='Body ![img](page.png)',source_content='Body ![img](page.png) <img src=\"original.png\">' WHERE id='chunk'");
   f.jdbc.update("UPDATE chunks SET image_info='[{\"url\":\"page.png\",\"original_url\":\"original.png\",\"caption\":\"Caption\"}]' WHERE id='child'");
  }
  switch(variant){
   case "image-invalid-info"->f.jdbc.update("UPDATE chunks SET image_info=? WHERE id=?","[{\"url\":\"page.png\",\"start_pos\":\"wrong\"}]","child");
   case "image-fold-info"->f.jdbc.update("UPDATE chunks SET image_info=? WHERE id=?","[{\"URL\":\"page.png\",\"ORIGINAL_URL\":\"original.png\"}]","child");
   case "metadata-array"->f.jdbc.update("UPDATE chunks SET metadata=? WHERE id=?","[]","chunk");
   case "question-fold"->f.jdbc.update("UPDATE chunks SET metadata=? WHERE id=?","{\"GENERATED_QUESTIONS\":[{\"ID\":\"fold\",\"QUESTION\":\"Case folded question 中文\",\"CONTENT_REVISION\":1}]}","chunk");
   case "question-duplicate"->f.jdbc.update("UPDATE chunks SET metadata=? WHERE id=?","{\"generated_questions\":[{\"id\":\"same\",\"question\":\"First question 中文\"},{\"id\":\"same\",\"question\":\"Second question 中文\"},{\"id\":\"empty\",\"question\":\"  \\n \"}]}","chunk");
   case "question-long-id"->f.jdbc.update("UPDATE chunks SET metadata=? WHERE id=?","{\"generated_questions\":[{\"id\":\"长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长\",\"question\":\"Long id question 中文\"}]}","chunk");
   case "question-whitespace"->f.jdbc.update("UPDATE chunks SET metadata=? WHERE id=?","{\"generated_questions\":[{\"id\":\"spaces\",\"question\":\"  Question 中文  \\n\"}]}","chunk");
   case "context-header-spaces"->f.jdbc.update("UPDATE chunks SET context_header=? WHERE id=?","  Context Header  ","chunk");
   case "image-restored"->f.jdbc.update("UPDATE chunks SET is_enabled=false WHERE id='child'");
   case "title-spaces"->f.jdbc.update("UPDATE knowledges SET title='  Spaced title  ' WHERE id='doc'");
   case "keyword-only"->f.jdbc.update("UPDATE knowledge_bases SET indexing_strategy='{\"keyword_enabled\":true}' WHERE id='kb'");
   case "vector-only"->f.jdbc.update("UPDATE knowledge_bases SET indexing_strategy='{\"vector_enabled\":true}' WHERE id='kb'");
   case "wiki-only"->f.jdbc.update("UPDATE knowledge_bases SET indexing_strategy='{\"wiki_enabled\":true}' WHERE id='kb'");
  }
 }
 com.tencent.weknora.retrieval.IndexWriter writer(DocumentProcessingCompatibilityTest f){return f.index;}
 List<Map<String,Object>> sqlRows(DocumentProcessingCompatibilityTest f,String sql){return f.jdbc.queryForList(sql);}
 int keywordCount(DocumentProcessingCompatibilityTest f){return f.jdbc.queryForObject("SELECT COUNT(*) FROM lite_embeddings_fts",Integer.class);}
 void indexFailure(DocumentProcessingCompatibilityTest f){f.jdbc.execute("CREATE TRIGGER block_edit_index BEFORE INSERT ON lite_embeddings BEGIN SELECT RAISE(ABORT,'controlled edit index failure'); END");}
 void revisionFailure(DocumentProcessingCompatibilityTest f){f.jdbc.execute("CREATE TRIGGER block_edit_revision BEFORE INSERT ON chunk_revisions BEGIN SELECT RAISE(ABORT,'controlled edit revision failure'); END");}
 void parentFailure(DocumentProcessingCompatibilityTest f){f.jdbc.execute("CREATE TRIGGER block_parent_edit BEFORE UPDATE OF content ON chunks WHEN OLD.id='parent' BEGIN SELECT RAISE(ABORT,'controlled parent edit failure'); END");}
 List<Map<String,Object>> vecQuery(DocumentProcessingCompatibilityTest f,String sql)throws Exception{try(var c=f.source.getConnection()){new com.tencent.weknora.retrieval.SQLiteVectorExtension(Path.of(System.getProperty("weknora.sqlite.vec.extension"))).load(c);return new org.springframework.jdbc.core.JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(c,true)).queryForList(sql);}}
 void seedVectors(DocumentProcessingCompatibilityTest f)throws Exception{var entries=new ArrayList<com.tencent.weknora.retrieval.IndexEntry>();var vectors=new LinkedHashMap<String,float[]>();for(String id:List.of("chunk","child","other","foreign","wrong-kb")){String doc=id.equals("other")?"other":"doc",kb=id.equals("wrong-kb")?"other-kb":"kb";entries.add(new com.tencent.weknora.retrieval.IndexEntry(id,0,id,doc,kb,"","Old physical "+id,true));vectors.put(id,vector("Old physical "+id));}entries.add(new com.tencent.weknora.retrieval.IndexEntry("chunk-old-q",0,"chunk","doc","kb","","Old physical question",true));vectors.put("chunk-old-q",vector("Old physical question"));writer(f).prepare(Set.of(3));new org.springframework.transaction.support.TransactionTemplate(f.manager).executeWithoutResult(status->f.jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>)c->{writer(f).batchSave(c,entries,vectors);return null;}));}
 static float[] vector(String text){long hash=2166136261L;for(byte b:text.getBytes(java.nio.charset.StandardCharsets.UTF_8))hash=((hash^Byte.toUnsignedInt(b))*16777619)&0xffffffffL;return new float[]{(hash%997+1)/1000f,(hash/997%991+1)/1000f,-(hash/988027%983+1)/1000f};}
 JsonNode canonical(JsonNode value){if(value.isNumber())return DecimalNode.valueOf(value.decimalValue().stripTrailingZeros());if(value.isArray()){var out=json.createArrayNode();value.forEach(n->out.add(canonical(n)));return out;}if(value.isObject()){var out=json.createObjectNode();value.fields().forEachRemaining(e->{String key=e.getKey();JsonNode n=e.getValue();if((key.equals("updated_at")||key.equals("created_at"))&&n.isTextual()&&!n.asText().equals("2026-01-01T00:00:00Z")&&!n.asText().equals("0001-01-01T00:00:00Z")){java.time.Instant.parse(n.asText());n=json.getNodeFactory().textNode("<valid-current-time>");}else if(key.equals("id")&&value.has("edit_source")&&value.path("edit_source").asText().equals("user")){UUID.fromString(n.asText());n=json.getNodeFactory().textNode("<version-uuid>");}else if((key.equals("metadata")||key.equals("source_locators"))&&n.isTextual()){try{n=json.readTree(n.asText());}catch(Exception ignored){}}else if(key.equals("is_enabled")&&n.isNumber())n=json.getNodeFactory().booleanNode(n.asInt()!=0);else if(key.equals("input")&&n.isArray()){var list=new ArrayList<JsonNode>();n.forEach(list::add);list.sort(Comparator.comparing(JsonNode::toString));n=json.valueToTree(list);}out.set(key,canonical(n));});return out;}return value;}
}
