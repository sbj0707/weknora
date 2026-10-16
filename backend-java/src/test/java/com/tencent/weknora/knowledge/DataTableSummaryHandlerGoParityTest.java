package com.tencent.weknora.knowledge;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import com.tencent.weknora.model.*;
import com.tencent.weknora.model.client.*;
import com.tencent.weknora.retrieval.*;
import com.tencent.weknora.security.*;
import com.tencent.weknora.task.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class DataTableSummaryHandlerGoParityTest {
  @TempDir Path temp;
  final ObjectMapper json=new ObjectMapper();
  JsonNode fixture(String name)throws Exception {
    try(var in=getClass().getResourceAsStream("/"+name)){assertThat(in).isNotNull();return json.readTree(in);}
  }
  @TestFactory Stream<DynamicTest> actualGoFullHandlerWithRealDuckDbModelsAndSQLite()throws Exception {
    var rows=fixture("go-datatable-summary.json");var loaders=fixture("go-datatable-loader.json");
    assertThat(rows.size()).isEqualTo(37);
    AtomicInteger sequence=new AtomicInteger();
    return StreamSupport.stream(rows.spliterator(),false).map(row->DynamicTest.dynamicTest(row.path("name").asText(),
        ()->compare(row,loaders.get(row.path("load_case").asInt()),sequence.incrementAndGet())));
  }

  void compare(JsonNode oracle,JsonNode input,int number)throws Exception {
    compare(oracle,input,number,false);
  }
  @Test void onePhysicalConnectionSupportsSummaryAndIndependentCleanup()throws Exception {
    var rows=fixture("go-datatable-summary.json");var loaders=fixture("go-datatable-loader.json");int index=100;
    for(String name:List.of("success","index-error","cleanup-delete-error","cleanup-index-error")){
      JsonNode row=StreamSupport.stream(rows.spliterator(),false).filter(r->r.path("name").asText().equals(name)).findFirst().orElseThrow();
      compare(row,loaders.get(row.path("load_case").asInt()),index++,true);
    }
  }
  @Test void canceledTaskMakesNoResourceOrModelCalls()throws Exception {
    var f=new DocumentProcessingCompatibilityTest();f.temp=temp;f.database(200,true);
    ObjectNode payload=json.createObjectNode().put("tenant_id",1).put("knowledge_id","knowledge");
    f.tasks.enqueue(1,"datatable:summary","canceled-summary",payload,4,Duration.ZERO,"summary",Duration.ofMinutes(30));
    var job=f.tasks.claim("worker",Duration.ofMinutes(5),List.of("summary")).orElseThrow();
    assertThat(f.tasks.cancel(1,job.id())).isTrue();
    var handler=new DataTableSummaryHandler(new DataTableSummaryRepository(f.jdbc,json,f.knowledge,f.manager,java.time.Clock.systemUTC()),json,
        new DataTableSummaryHandler.Models(){public ChatModel chat(long owner,String id){throw new AssertionError("canceled task must not load models");}public EmbeddingModel embedding(long owner,String id){throw new AssertionError("canceled task must not load models");}},
        (owner,kb,path)->{throw new AssertionError("canceled task must not load storage");},(owner,kb)->{throw new AssertionError("canceled task must not initialize indexes");},
        ()->{throw new AssertionError("canceled task must not create DuckDB");},1);
    assertThatThrownBy(()->handler.execute(job,f.cancellation(job))).isInstanceOf(java.util.concurrent.CancellationException.class);
    assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM chunks",Integer.class)).isZero();
  }
  void compare(JsonNode oracle,JsonNode input,int number,boolean oneConnection)throws Exception {
    compare(oracle,input,number,oneConnection,null);
  }
  @FunctionalInterface interface AfterSummary {void run(DocumentProcessingCompatibilityTest f)throws Exception;}
  @Test void tableTaskFollowedByDocumentPublicationOnSameWorkerAndConnection()throws Exception {
    var rows=fixture("go-datatable-summary.json");var loaders=fixture("go-datatable-loader.json");
    JsonNode oracle=StreamSupport.stream(rows.spliterator(),false).filter(r->r.path("name").asText().equals("success")).findFirst().orElseThrow();
    compare(oracle,loaders.get(oracle.path("load_case").asInt()),400,true,f->{
      f.processing=new DocumentProcessingRepository(f.jdbc,json,f.knowledge,f.manager,java.time.Clock.systemUTC());
      f.jdbc.update("UPDATE knowledges SET parse_status='pending',metadata=? WHERE id='knowledge'","{\"process_overrides\":{\"summary_enabled\":false}}");
      var job=f.job(f.knowledge.get(1,"knowledge").orElseThrow(),"document:process");
      var model=new EmbeddingModel(){
        public String modelId(){return "embedding";}public String modelName(){return "test";}public int dimensions(){return 3;}
        public float[][] embed(CallContext context,List<String> texts,boolean query) {
          float[][] values=new float[texts.size()][];for(int i=0;i<values.length;i++)values[i]=new float[]{1,0,0};return values;
        }
      };
      var result=f.handler((owner,kb,path)->Files.newInputStream(Path.of(path)),(owner,id)->model,f.index).execute(job,f.cancellation(job));
      assertThat(result.path("indexed").asBoolean()).isTrue();
      assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM chunks WHERE knowledge_id='knowledge'"
          +" AND chunk_type IN ('table_summary','table_column') AND deleted_at IS NULL",Integer.class)).isEqualTo(2);
      assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM lite_embeddings e JOIN chunks c ON c.id=e.source_id"
          +" WHERE c.chunk_type IN ('table_summary','table_column') AND c.deleted_at IS NULL",Integer.class)).isEqualTo(2);
    });
  }
  void compare(JsonNode oracle,JsonNode input,int number,boolean oneConnection,AfterSummary after)throws Exception {
    String name=oracle.path("name").asText();
    var f=new DocumentProcessingCompatibilityTest();f.temp=temp;f.database(number,true);
    Path file=temp.resolve("source-"+number+"."+input.path("file_type").asText());
    Files.write(file,Base64.getDecoder().decode(input.path("input_base64").asText()));
    ObjectNode k=f.knowledge.blank();
    k.put("id","knowledge").put("tenant_id",1).put("knowledge_base_id","kb").put("type","file")
        .put("file_name","source."+input.path("file_type").asText()).put("file_type",input.path("file_type").asText())
        .put("file_path",file.toString()).put("file_hash","hash")
        .put("parse_status",name.startsWith("state-")?name.substring(6):"completed")
        .put("enable_status","enabled").put("summary_status","none").put("error_message","old error")
        .put("storage_size",11).put("created_at","2026-01-01T00:00:00Z").put("updated_at","2026-01-01T00:00:00Z");
    if(name.equals("override"))k.set("metadata",json.createObjectNode().set("process_overrides",
        json.createObjectNode().set("chunking_config",json.createObjectNode().put("table_metadata_instructions"," Override instruction \n test "))));
    f.knowledge.create(1,k);
    com.zaxxer.hikari.HikariDataSource pool=null;
    if(oneConnection){
      pool=new com.zaxxer.hikari.HikariDataSource();pool.setJdbcUrl(f.source.getUrl());pool.setMaximumPoolSize(1);pool.setConnectionTimeout(1000);
      f.jdbc=new org.springframework.jdbc.core.JdbcTemplate(pool);f.manager=new org.springframework.jdbc.datasource.DataSourceTransactionManager(pool);
      f.knowledge=new KnowledgeRepository(f.jdbc,json,f.manager);f.tasks=new TaskRepository(f.jdbc,json,f.manager);
      f.index=new SQLiteIndexRepository(pool,new SQLiteVectorExtension(Path.of(System.getProperty("weknora.sqlite.vec.extension"))));f.index.initialize();
    }
    if(name.equals("custom")||name.equals("override"))f.jdbc.update("UPDATE knowledge_bases SET chunking_config=? WHERE id='kb'",
        json.createObjectNode().put("table_metadata_instructions","  KB business <>& 中文  ").toString());
    f.jdbc.update("INSERT INTO chunks(id,tenant_id,knowledge_base_id,knowledge_id,content,chunk_type,status,is_enabled,chunk_index,start_at,end_at)"
        +" VALUES('old',1,'kb','knowledge','old body','text',2,true,0,0,0)");
    List<ObjectNode> requests=Collections.synchronizedList(new ArrayList<>());AtomicInteger chats=new AtomicInteger();
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/v1/",exchange->{
      try {
        ObjectNode body=(ObjectNode)json.readTree(exchange.getRequestBody());String path=exchange.getRequestURI().getPath();
        requests.add(json.createObjectNode().put("path",path).put("authorization",exchange.getRequestHeaders().getFirst("Authorization")).set("body",body));
        int status=200;ObjectNode response;
        if(path.equals("/v1/chat/completions")) {
          int chat=chats.incrementAndGet();
          if(name.equals("chat-table-error")&&chat==1 || name.equals("chat-column-error")&&chat==2){status=503;response=json.createObjectNode().set("error",json.createObjectNode().put("message","synthetic provider unavailable"));}
          else {
            String content=chat==1?"  Table metadata 中文 <>&\n ":"\n Column metadata 中文 \t";
            if(name.equals("table-empty")&&chat==1||name.equals("column-empty")&&chat==2)content=" \n\t\u00a0";
            if(name.equals("knowledge-changed-cleanup")&&chat==2)f.jdbc.update("UPDATE knowledges SET updated_at='2030-01-01 00:00:00' WHERE id='knowledge'");
            response=json.createObjectNode().set("choices",json.createArrayNode().add(json.createObjectNode()
                .put("finish_reason","stop").set("message",json.createObjectNode().put("role","assistant").put("content",content))));
          }
        } else if(name.equals("embedding-error")) {status=400;response=json.createObjectNode().set("error",json.createObjectNode().put("message","synthetic embedding unavailable"));}
        else {
          var data=json.createArrayNode();int i=0;
          for(var value:body.path("input")){String s=value.asText();data.add(json.createObjectNode().put("index",i)
              .set("embedding",json.createArrayNode().add(s.codePointCount(0,s.length())).add(i++).add(.12345678f)));}
          response=json.createObjectNode().set("data",data);
        }
        byte[] bytes=json.writeValueAsBytes(response);exchange.getResponseHeaders().set("Content-Type","application/json");
        exchange.sendResponseHeaders(status,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}
      } finally {exchange.close();}
    });server.start();
    MockEnvironment env=new MockEnvironment().withProperty("SSRF_WHITELIST","127.0.0.1").withProperty("SSRF_DNS_WHITELIST_ONLY","true");
    var models=new ModelClientFactory(f.jdbc,json,new GoSecretCodec(""),new ModelCatalogRuntime(f.jdbc,"","/nonexistent"),new ModelEgressPolicy(f.jdbc,json,env),env);
    try {
      var parameters=json.createObjectNode().put("provider","generic").put("base_url","http://127.0.0.1:"+server.getAddress().getPort()+"/v1").put("api_key","synthetic-key");
      parameters.set("embedding_parameters",json.createObjectNode().put("dimension",3));
      for(String id:List.of("summary","embedding"))f.jdbc.update("INSERT INTO models(id,tenant_id,name,type,source,parameters,is_builtin,status)"
          +" VALUES(?,1,'unknown',?,'remote',?,false,'active')",id,id.equals("summary")?"KnowledgeQA":"Embedding",json.writeValueAsBytes(parameters));
      var payload=(ObjectNode)oracle.path("payload").deepCopy();
      if(name.equals("deleted"))f.jdbc.update("UPDATE knowledges SET deleted_at='2026-01-01' WHERE id='knowledge'");
      if(name.equals("kb-foreign"))f.jdbc.update("UPDATE knowledge_bases SET tenant_id=2 WHERE id='kb'");
      if(name.equals("kb-missing"))f.jdbc.update("UPDATE knowledge_bases SET deleted_at='2026-01-01' WHERE id='kb'");
      if(name.equals("tenant-missing"))f.jdbc.update("UPDATE tenants SET deleted_at='2026-01-01' WHERE id=1");
      if(name.equals("unsupported"))f.jdbc.update("UPDATE knowledges SET file_type=' TXT ' WHERE id='knowledge'");
      if(name.equals("file-missing"))Files.delete(file);
      triggers(f,name);
      long tenant=payload.path("tenant_id").asLong();
      f.tasks.enqueue(tenant,"datatable:summary",UUID.randomUUID().toString(),payload,4,Duration.ZERO,"summary",Duration.ofMinutes(30));
      var job=f.tasks.claim("worker",Duration.ofMinutes(5),List.of("summary")).orElseThrow();
      var repo=new DataTableSummaryRepository(f.jdbc,json,f.knowledge,f.manager,java.time.Clock.systemUTC());
      var handler=new DataTableSummaryHandler(repo,json,new DataTableSummaryHandler.Models(){
        AuthContext ctx(long owner){return new AuthContext("system-task",owner,"viewer",false,null,false,Set.of(),Set.of());}
        public ChatModel chat(long owner,String id){return models.chat(ctx(owner),id);}
        public EmbeddingModel embedding(long owner,String id){return models.embedding(ctx(owner),id);}
      },(owner,kb,path)->Files.newInputStream(Path.of(path)),(owner,kb)->f.index,
          ()->KnowledgeProcessingConfiguration.openTableDatabase(env),50L*1024*1024);
      Exception failure=null;
      try {handler.execute(job,f.cancellation(job));}catch(Exception e){failure=e;}
      if(oracle.path("error").asText().isEmpty())assertThat(failure).isNull();
      else {
        assertThat(failure).isNotNull();String actual=DataTableSummaryHandler.diagnostic(failure),expected=oracle.path("error").asText();
        if(name.equals("file-missing"))assertThat(failure).isInstanceOf(java.io.IOException.class);
        else if(name.equals("kb-foreign"))assertThat(actual).isEqualTo("knowledge does not belong to its knowledge base");
        else if(name.endsWith("model-missing"))assertThat(actual.toLowerCase(Locale.ROOT)).isEqualTo(expected);
        else assertThat(actual).isEqualTo(expected);
      }
      assertThat(normalizeRequests(requests)).isEqualTo(normalizeRequests(oracle.path("requests")));
      ObjectNode actual=projection(f,requests);
      ObjectNode expected=(ObjectNode)oracle.deepCopy();normalizeFixtureVectors(expected);
      for(String key:List.of("chunks","indexes","knowledge","soft_deleted_chunks"))assertThat(numbers(actual.get(key))).as(name+" "+key).isEqualTo(numbers(expected.get(key)));
      assertThat(Files.exists(file)).isEqualTo(!name.equals("file-missing"));
      if(after!=null)after.run(f);
    } finally {models.close();server.stop(0);if(pool!=null)pool.close();}
  }

  void triggers(DocumentProcessingCompatibilityTest f,String name) {
    if(name.equals("create-error"))f.jdbc.execute("CREATE TRIGGER fail_chunks BEFORE INSERT ON chunks BEGIN SELECT RAISE(FAIL,'synthetic create failure');END");
    if(Set.of("index-error","cleanup-delete-error","knowledge-changed-cleanup").contains(name))f.jdbc.execute("CREATE TRIGGER fail_index BEFORE INSERT ON lite_embeddings BEGIN SELECT RAISE(FAIL,'synthetic index failure');END");
    if(Set.of("update-error","cleanup-index-error").contains(name))f.jdbc.execute("CREATE TRIGGER fail_status BEFORE UPDATE OF status ON chunks BEGIN SELECT RAISE(FAIL,'synthetic status failure');END");
    if(name.equals("cleanup-delete-error"))f.jdbc.execute("CREATE TRIGGER fail_delete BEFORE UPDATE OF deleted_at ON chunks BEGIN SELECT RAISE(FAIL,'synthetic delete failure');END");
    if(name.equals("cleanup-index-error"))f.jdbc.execute("CREATE TRIGGER fail_index_delete BEFORE DELETE ON lite_embeddings BEGIN SELECT RAISE(FAIL,'synthetic index delete failure');END");
  }

  ArrayNode normalizeRequests(Object requests)throws Exception {
    JsonNode rows=requests instanceof JsonNode n?n:json.valueToTree(requests);var out=json.createArrayNode();
    for(var row:rows){ObjectNode copy=(ObjectNode)row.deepCopy();
      if(copy.path("path").asText().equals("/v1/embeddings")){
        var values=new ArrayList<String>();copy.path("body").path("input").forEach(v->values.add(v.asText()));
        Collections.sort(values);((ObjectNode)copy.get("body")).set("input",json.valueToTree(values));
      }out.add(copy);
    }return out;
  }
  void normalizeFixtureVectors(ObjectNode fixture) {
    for(var e:fixture.path("indexes"))if(e.has("vector"))((ArrayNode)e.get("vector")).set(1,TextNode.valueOf("$captured_batch_position"));
  }
  JsonNode numbers(JsonNode value) {
    if(value.isNumber())return DecimalNode.valueOf(value.decimalValue().stripTrailingZeros());
    if(value.isArray()){var out=json.createArrayNode();value.forEach(v->out.add(numbers(v)));return out;}
    if(value.isObject()){var out=json.createObjectNode();value.fields().forEachRemaining(e->out.set(e.getKey(),numbers(e.getValue())));return out;}
    return value;
  }
  ObjectNode projection(DocumentProcessingCompatibilityTest f,List<ObjectNode> requests)throws Exception {
    var out=json.createObjectNode();var all=f.jdbc.queryForList("SELECT * FROM chunks WHERE knowledge_id='knowledge' ORDER BY chunk_type");
    var ids=new HashMap<String,String>();ids.put("","");ids.put("old","old");for(var c:all)if(!c.get("id").equals("old"))ids.put(c.get("id").toString(),c.get("chunk_type").toString());
    var chunks=out.putArray("chunks");for(var c:all){if(c.get("deleted_at")!=null)continue;
      chunks.addObject().put("id",ids.get(c.get("id"))).put("tenant",((Number)c.get("tenant_id")).longValue())
          .put("kb",c.get("knowledge_base_id").toString()).put("knowledge",c.get("knowledge_id").toString())
          .put("content",c.get("content").toString()).put("type",c.get("chunk_type").toString()).put("index",((Number)c.get("chunk_index")).intValue())
          .put("parent",ids.get(Objects.toString(c.get("parent_chunk_id"),""))).put("previous",ids.get(Objects.toString(c.get("pre_chunk_id"),"")))
          .put("next",ids.get(Objects.toString(c.get("next_chunk_id"),""))).put("start",((Number)c.get("start_at")).intValue())
          .put("end",((Number)c.get("end_at")).intValue()).put("status",((Number)c.get("status")).intValue())
          .put("enabled",!c.get("is_enabled").toString().equals("0")).put("context_header",Objects.toString(c.get("context_header"),""));
    }
    var indexes=out.putArray("indexes");
    var entries=f.jdbc.queryForList("SELECT * FROM lite_embeddings ORDER BY source_id");entries.sort(Comparator.comparing(e->ids.get(e.get("source_id"))));
    for(var e:entries){var value=indexes.addObject();for(String key:List.of("source_type","knowledge_id","knowledge_base_id","content","dimension","is_enabled"))value.set(key,json.valueToTree(e.get(key)));
      value.put("source_id",ids.get(e.get("source_id"))).put("chunk_id",ids.get(e.get("chunk_id")));
      byte[] bytes=f.jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<byte[]>) connection->{
        new SQLiteVectorExtension(Path.of(System.getProperty("weknora.sqlite.vec.extension"))).load(connection);
        try(var statement=connection.prepareStatement("SELECT embedding FROM vec_embeddings_3 WHERE rowid=?")){
          statement.setLong(1,((Number)e.get("id")).longValue());try(var r=statement.executeQuery()){return r.next()?r.getBytes(1):null;}
        }
      });
      if(bytes==null)value.put("vector_error","sql: no rows in result set");
      else {
        float[] vector=FloatVectors.decode(bytes,3);
        var request=requests.stream().filter(r->r.path("path").asText().equals("/v1/embeddings")).findFirst().orElseThrow().path("body").path("input");
        int position=-1;for(int i=0;i<request.size();i++)if(request.get(i).asText().equals(IndexText.embeddingInput(e.get("content").toString())))position=i;
        assertThat(position).isGreaterThanOrEqualTo(0);assertThat(Float.floatToRawIntBits(vector[1])).isEqualTo(Float.floatToRawIntBits((float)position));
        assertThat(Float.floatToRawIntBits(vector[2])).isEqualTo(Float.floatToRawIntBits(.12345678f));
        value.set("vector",json.createArrayNode().add(vector[0]).add("$captured_batch_position").add(vector[2]));
      }
    }
    var stored=f.jdbc.queryForMap("SELECT * FROM knowledges WHERE id='knowledge'");
    var state=out.putObject("knowledge");for(String key:List.of("parse_status","enable_status","summary_status","storage_size"))state.set(key,json.valueToTree(stored.get(key)));state.put("error",stored.get("error_message").toString());
    out.put("soft_deleted_chunks",f.jdbc.queryForObject("SELECT COUNT(*) FROM chunks WHERE knowledge_id='knowledge' AND deleted_at IS NOT NULL",Integer.class));
    return (ObjectNode)json.readTree(json.writeValueAsBytes(out));
  }
}
