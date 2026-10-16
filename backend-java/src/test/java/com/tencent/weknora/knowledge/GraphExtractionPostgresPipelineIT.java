package com.tencent.weknora.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import com.tencent.weknora.security.JwtCodec;
import com.tencent.weknora.task.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Explicit real-PostgreSQL acceptance; never silently skip or use an in-memory substitute. */
@org.springframework.boot.test.context.SpringBootTest(properties={
    "weknora.jwt-secret=isolated-postgres-graph-secret", "weknora.bridge.enabled=false",
    "weknora.knowledge.processing.enabled=true", "weknora.knowledge.upload.enabled=true",
    "weknora.tasks.enabled=true", "weknora.tasks.poll-millis=3600000",
    "weknora.retrieve-driver=postgres", "NEO4J_ENABLE=true", "NEO4J_USERNAME=neo4j", "NEO4J_PASSWORD=isolated-test", "SSRF_WHITELIST=127.0.0.1",
    "SSRF_DNS_WHITELIST_ONLY=true", "logging.level.root=WARN", "debug=false"})
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class GraphExtractionPostgresPipelineIT {
  static final ObjectMapper JSON = new ObjectMapper();
  static final HttpServer SERVER;
  static final Path FILES;
  static volatile Runnable duringChat=()->{};
  static final AtomicInteger EMBEDDINGS = new AtomicInteger(), CHATS = new AtomicInteger();
  static {
    try {
      com.tencent.weknora.retrieval.NativeDatabaseRuntime.initialize();
      FILES = Files.createTempDirectory("weknora-pg-pipeline-");
      SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      SERVER.createContext("/v1/", exchange -> {
        try {
          var request = JSON.readTree(exchange.getRequestBody());
          JsonNode response;
          if (exchange.getRequestURI().getPath().endsWith("embeddings")) {
            EMBEDDINGS.incrementAndGet();
            var values = JSON.createArrayNode();
            for (int i = request.path("input").size() - 1; i >= 0; i--)
              values.add(JSON.createObjectNode().put("index", i).set("embedding",
                  JSON.createArrayNode().add(1).add(0).add(0)));
            response = JSON.createObjectNode().set("data", values);
          } else if(request.path("max_tokens").asInt()==8192) {
            CHATS.incrementAndGet();duringChat.run();
            assertThat(request.path("temperature").asDouble()).isEqualTo(.3);
            response=JSON.createObjectNode().set("choices",JSON.createArrayNode().add(JSON.createObjectNode().set("message",JSON.createObjectNode().put("role","assistant").put("content","[{\"entity\":\"Alice\",\"entity_attributes\":[\"Owner\"]},{\"entity\":\"Release\"},{\"entity1\":\"Alice\",\"entity2\":\"Release\",\"relation\":\"owns\"}]"))));
          } else if(request.path("messages").get(0).path("content").asText().startsWith("You classify one document")) {
            CHATS.incrementAndGet();assertThat(request.path("temperature").asDouble()).isEqualTo(.1);assertThat(request.path("max_tokens").asInt()).isEqualTo(1024);
            response=JSON.createObjectNode().set("choices",JSON.createArrayNode().add(JSON.createObjectNode().set("message",JSON.createObjectNode().put("role","assistant").put("content","{\"matches\":[{\"index\":1}]}"))));
          } else if(request.path("messages").get(0).path("role").asText().equals("user")) {
            CHATS.incrementAndGet();
            assertThat(request.path("temperature").asDouble()).isEqualTo(.7);
            assertThat(request.path("max_tokens").asInt()).isEqualTo(512);
            response=JSON.createObjectNode().set("choices",JSON.createArrayNode().add(JSON.createObjectNode().set("message",JSON.createObjectNode().put("role","assistant").put("content","What does the PostgreSQL document explain?\nHow does the PostgreSQL document describe migration?\nWhy does the PostgreSQL document explain processing?"))));
          } else {
            CHATS.incrementAndGet();duringChat.run();
            assertThat(request.path("temperature").asDouble()).isEqualTo(.3);
            assertThat(request.path("max_tokens").asInt()).isEqualTo(1024);
            response = JSON.createObjectNode().set("choices", JSON.createArrayNode().add(
                JSON.createObjectNode().set("message", JSON.createObjectNode().put("role", "assistant")
                    .put("content", "{\"summary\":\"PostgreSQL summary 中文\",\"gist\":\"Native profile\"}"))));
          }
          byte[] bytes = JSON.writeValueAsBytes(response);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          try (var output = exchange.getResponseBody()) { output.write(bytes); }
        } finally { exchange.close(); }
      });
      SERVER.start();
    } catch (Exception e) { throw new ExceptionInInitializerError(e); }
  }
  @org.springframework.test.context.DynamicPropertySource
  static void properties(org.springframework.test.context.DynamicPropertyRegistry properties) {
    String url = Objects.requireNonNull(System.getProperty("weknora.parity.processing.pg.url"),
        "Explicit isolated PostgreSQL URL required");
    properties.add("spring.datasource.url", () -> url);
    properties.add("NEO4J_URI",()->Objects.requireNonNull(System.getProperty("weknora.graph.test.uri"),"Isolated actual Neo4j URI required"));
    properties.add("spring.datasource.username", () -> "postgres");
    properties.add("spring.datasource.password", () -> "local-parity-only");
    properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    properties.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
    properties.add("spring.datasource.hikari.connection-timeout", () -> 2000);
    properties.add("LOCAL_STORAGE_BASE_DIR", () -> FILES.toString());
  }
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired JwtCodec jwt;
  @Autowired TaskRepository tasks;
  @Autowired DocumentProcessingHandler document;
  @Autowired KnowledgePostProcessHandler postprocess;
  @Autowired SummaryGenerationHandler summary;
  @Autowired QuestionGenerationHandler question;
  @Autowired KnowledgeAutoTagHandler autoTag;
  @Autowired GraphExtractionHandler graphHandler;
  @Autowired Neo4jGraphRepository graph;
  final List<String> ownedGraphs=new ArrayList<>();

  @AfterAll static void closeServer() { SERVER.stop(0); }

  String token(String user, long tenant) {
    String token = jwt.sign(Map.of("user_id",user,"tenant_id",tenant,"type","access",
        "exp",Instant.now().plusSeconds(3600).getEpochSecond()));
    jdbc.update("INSERT INTO auth_tokens(id,user_id,token,token_type,expires_at,is_revoked)"
        + " VALUES(?,?,?,'access_token',CURRENT_TIMESTAMP+INTERVAL '1 hour',false)",
        UUID.randomUUID().toString(), user, token);
    return token;
  }
  TaskCancellation cancellation(TaskRecord job) throws Exception {
    var constructor = TaskCancellation.class.getDeclaredConstructor(TaskRepository.class, TaskRecord.class);
    constructor.setAccessible(true);
    return constructor.newInstance(tasks, job);
  }
  TaskRecord claim(String queue) {
    return tasks.claim("pg-worker", Duration.ofMinutes(5), List.of(queue)).orElseThrow();
  }


  JsonNode read(String doc,String suffix,String token,int status)throws Exception{var response=mvc.perform(get("/api/v1/knowledge/"+doc+"/"+suffix).header("Authorization","Bearer "+token)).andReturn().getResponse();assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);return JSON.readTree(response.getContentAsString()).path("data");}
  JsonNode index(JsonNode data,String kind){for(var n:data.path("indexes"))if(n.path("kind").asText().equals(kind))return n;throw new AssertionError("missing index "+kind);}
  int pending(String doc){return jdbc.queryForObject("SELECT pending_subtasks_count FROM knowledges WHERE id=?",Integer.class,doc);}
  String upload(String actor,String token,boolean enhanced)throws Exception{String config="{\"summary_enabled\":"+enhanced+",\"question_generation_config\":{\"enabled\":"+enhanced+",\"question_count\":3}}";
    byte[] body=("--graph-pg\r\nContent-Disposition: form-data; name=\"process_config\"\r\n\r\n"+config+"\r\n--graph-pg\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+actor+".txt\"\r\nContent-Type: text/plain\r\n\r\nAlice owns release access and native PostgreSQL graph policy. "+UUID.randomUUID()+"\r\n--graph-pg--\r\n").getBytes(StandardCharsets.UTF_8);
    var response=mvc.perform(post("/api/v1/knowledge-bases/pg-graph-kb/knowledge/file").contentType("multipart/form-data; boundary=graph-pg").content(body).header("Authorization","Bearer "+token)).andReturn().getResponse();assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);String id=JSON.readTree(response.getContentAsString()).path("data").path("id").asText();ownedGraphs.add(id);var doc=claim("default");assertThat(doc.tenantId()).isEqualTo(8);assertThat(document.execute(doc,cancellation(doc)).path("indexed").asBoolean()).isTrue();assertThat(tasks.complete(doc,JSON.createObjectNode())).isTrue();var post=claim("postprocess");assertThat(postprocess.execute(post,cancellation(post)).path("enqueued_graph_count").asInt()).isEqualTo(1);assertThat(tasks.complete(post,JSON.createObjectNode())).isTrue();return id;
  }
  @AfterEach void cleanupGraph(){try(var driver=org.neo4j.driver.GraphDatabase.driver(System.getProperty("weknora.graph.test.uri"),org.neo4j.driver.AuthTokens.none());var session=driver.session()){if(!ownedGraphs.isEmpty())session.run("MATCH(n) WHERE n.kg IN $docs DETACH DELETE n",Map.of("docs",ownedGraphs)).consume();}duringChat=()->{};}
  @Test void nativePostgresAndNeo4jOwnSharedFanoutAndFreshnessUseRealProductionBeans()throws Exception{
    assertThat(jdbc.queryForObject("SELECT current_database()",String.class)).isEqualTo("weknora_graph_processing_parity");
    jdbc.update("INSERT INTO tenants(id,name,business,storage_engine_config) VALUES(7,'Caller','test','{}'::jsonb),(8,'Owner','test','{}'::jsonb),(9,'Unshared','test','{}'::jsonb)");
    for(var e:Map.of("caller",7L,"owner",8L,"unshared",9L).entrySet()){jdbc.update("INSERT INTO users(id,username,email,password_hash,tenant_id) VALUES(?,?,?,'unused',?)",e.getKey(),e.getKey(),e.getKey()+"@graph.pg.invalid",e.getValue());jdbc.update("INSERT INTO tenant_members(user_id,tenant_id,role,status) VALUES(?,?,?,'active')",e.getKey(),e.getValue(),e.getKey().equals("caller")?"contributor":"owner");}
    jdbc.update("INSERT INTO knowledge_bases(id,tenant_id,name,type,creator_id,embedding_model_id,summary_model_id,indexing_strategy,chunking_config,extract_config) VALUES('pg-graph-kb',8,'Graph','document','owner','pg-embedding','pg-summary',?::jsonb,?::jsonb,?::jsonb)","{\"vector_enabled\":true,\"keyword_enabled\":true,\"graph_enabled\":true}","{\"chunk_size\":1024,\"chunk_overlap\":0}","{\"enabled\":true,\"tags\":[\"owns\"]}");
    String params="{\"provider\":\"generic\",\"base_url\":\"http://127.0.0.1:"+SERVER.getAddress().getPort()+"/v1\",\"embedding_parameters\":{\"dimension\":3}}";
    for(String kind:List.of("Embedding","KnowledgeQA"))jdbc.update("INSERT INTO models(id,tenant_id,name,type,source,parameters,is_builtin,status) VALUES(?,8,'unknown',?,'remote',?::jsonb,false,'active')",kind.equals("Embedding")?"pg-embedding":"pg-summary",kind,params);
    jdbc.update("INSERT INTO organizations(id,name,owner_tenant_id,owner_id) VALUES('pg-org','PG',8,'owner')");jdbc.update("INSERT INTO organization_tenant_members(id,organization_id,tenant_id,role) VALUES('pg-owner','pg-org',8,'admin'),('pg-caller','pg-org',7,'editor')");jdbc.update("INSERT INTO kb_shares(id,knowledge_base_id,organization_id,source_tenant_id,permission,shared_by_user_id) VALUES('pg-share','pg-graph-kb','pg-org',8,'editor','owner')");
    var tokens=Map.of("owner",token("owner",8),"caller",token("caller",7),"unshared",token("unshared",9));
    for(String actor:List.of("owner","caller")){String id=upload(actor,tokens.get(actor),true);assertThat(pending(id)).isEqualTo(3);var g=claim("graph");assertThat(graphHandler.execute(g,cancellation(g)).path("nodes_added").asInt()).isEqualTo(2);assertThat(tasks.complete(g,JSON.createObjectNode())).isTrue();assertThat(pending(id)).isEqualTo(2);var s=claim("summary");summary.execute(s,cancellation(s));tasks.complete(s,JSON.createObjectNode());assertThat(pending(id)).isEqualTo(1);var q=claim("question");question.execute(q,cancellation(q));tasks.complete(q,JSON.createObjectNode());assertThat(pending(id)).isZero();
      assertThat(jdbc.queryForObject("SELECT parse_status FROM knowledges WHERE id=?",String.class,id)).isEqualTo("completed");assertThat(jdbc.queryForObject("SELECT count(*) FROM embeddings WHERE knowledge_id=? AND dimension=3 AND embedding::text='[1,0,0]'",Integer.class,id)).isEqualTo(5);assertThat(jdbc.queryForObject("SELECT count(*) FROM java_graph_task_drains WHERE tenant_id=8 AND knowledge_id=?",Integer.class,id)).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT count(*) FROM java_question_task_drains WHERE tenant_id=8 AND knowledge_id=?",Integer.class,id)).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT profile->>'gist' FROM knowledges WHERE id=?",String.class,id)).isEqualTo("Native profile");
      String chunk=g.payload().path("chunk_id").asText();assertThat(jdbc.queryForObject("SELECT jsonb_array_length(metadata->'generated_questions') FROM chunks WHERE id=?",Integer.class,chunk)).isEqualTo(3);var state=index(read(id,"index-status",tokens.get(actor),200),"graph");assertThat(state.path("count").asInt()).isEqualTo(2);assertThat(state.path("relation_count").asInt()).isEqualTo(1);assertThat(state.path("status").asText()).isEqualTo("completed");var detail=index(read(id,"chunks/"+chunk+"/details",tokens.get(actor),200),"graph");assertThat(detail.path("available").asBoolean()).isTrue();assertThat(detail.path("graph").path("nodes").size()).isEqualTo(2);assertThat(detail.path("graph").path("relations").size()).isEqualTo(1);read(id,"index-status",tokens.get("unshared"),403);read(id,"chunks/"+chunk+"/details",tokens.get("unshared"),403);assertThat(read(id,"spans",tokens.get(actor),200).path("trace").path("status").asText()).isEqualTo("done");read(id,"stages",tokens.get(actor),200);assertThat(jdbc.queryForObject("SELECT actor_user_id FROM audit_logs WHERE target_id=? AND action='knowledge.created'",String.class,id)).isEqualTo(actor);
    }
    for(String edge:List.of("revision","cancel","lease")){String id=upload("caller",tokens.get("caller"),false);var g=claim("graph");assertThat(pending(id)).isEqualTo(1);duringChat=()->{if(edge.equals("revision"))jdbc.update("UPDATE chunks SET content_revision=1 WHERE id=?",g.payload().path("chunk_id").asText());else if(edge.equals("lease"))jdbc.update("UPDATE java_task_jobs SET lease_owner='successor' WHERE id=?",g.id());else try{var r=mvc.perform(post("/api/v1/knowledge/"+id+"/cancel-parse").header("Authorization","Bearer "+tokens.get("caller"))).andReturn().getResponse();assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);}catch(Exception e){throw new RuntimeException(e);}};
      try{if(edge.equals("revision")){assertThat(graphHandler.execute(g,cancellation(g)).path("skipped").asText()).isEqualTo("chunk_changed");tasks.complete(g,JSON.createObjectNode());assertThat(pending(id)).isZero();}else{org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.CancellationException.class,()->graphHandler.execute(g,cancellation(g)));if(edge.equals("cancel")){assertThat(tasks.acknowledgeCancellation(g)).isTrue();assertThat(pending(id)).isZero();assertThat(jdbc.queryForObject("SELECT parse_status FROM knowledges WHERE id=?",String.class,id)).isEqualTo("cancelled");}else assertThat(pending(id)).isEqualTo(1);}}finally{duringChat=()->{};}
      assertThat(graph.counts("pg-graph-kb",id).path("Entities").asInt()).isZero();assertThat(graph.counts("pg-graph-kb",id).path("Relations").asInt()).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM embeddings WHERE knowledge_id=?",Integer.class,id)).isEqualTo(1);
    }
    assertThat(EMBEDDINGS.get()).isEqualTo(9);assertThat(CHATS.get()).isEqualTo(9);assertThat(jdbc.queryForObject("SELECT count(*) FROM java_task_jobs",Integer.class)).isEqualTo(19);assertThat(jdbc.queryForObject("SELECT count(*) FROM java_task_jobs WHERE tenant_id<>8",Integer.class)).isZero();
  }
}
