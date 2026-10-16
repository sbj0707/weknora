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
    "weknora.jwt-secret=isolated-postgres-processing-secret", "weknora.bridge.enabled=false",
    "weknora.knowledge.processing.enabled=true", "weknora.knowledge.upload.enabled=true",
    "weknora.tasks.enabled=true", "weknora.tasks.poll-millis=3600000",
    "weknora.retrieve-driver=postgres", "RETRIEVE_DRIVER=postgres", "SSRF_WHITELIST=127.0.0.1",
    "SSRF_DNS_WHITELIST_ONLY=true", "logging.level.root=WARN", "debug=false"})
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class PostgresProcessingPipelineIT {
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
          } else if(request.path("messages").get(0).path("content").asText().startsWith("You classify one document")) {
            CHATS.incrementAndGet();assertThat(request.path("temperature").asDouble()).isEqualTo(.1);assertThat(request.path("max_tokens").asInt()).isEqualTo(1024);
            response=JSON.createObjectNode().set("choices",JSON.createArrayNode().add(JSON.createObjectNode().set("message",JSON.createObjectNode().put("role","assistant").put("content","{\"matches\":[{\"index\":1}]}"))));
          } else if(request.path("max_tokens").asInt()==800) {
            CHATS.incrementAndGet();assertThat(request.path("temperature").asDouble()).isEqualTo(.2);assertThat(request.path("messages").get(1).path("content").asText()).contains("Documents: 8 (with document profile: 6)","Native profile");
            response=JSON.createObjectNode().set("choices",JSON.createArrayNode().add(JSON.createObjectNode().set("message",JSON.createObjectNode().put("role","assistant").put("content","{\"gist\":\"PostgreSQL knowledge collection\",\"topics\":[\"migration\"]}"))));
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
  @Autowired IndexDeleteHandler indexDelete;
  @Autowired KnowledgeListDeleteHandler documentDelete;
  @Autowired com.tencent.weknora.storage.StorageResolver storage;
  @Autowired KnowledgePostProcessHandler postprocess;
  @Autowired SummaryGenerationHandler summary;
  @Autowired QuestionGenerationHandler question;
  @Autowired KnowledgeAutoTagHandler autoTag;
  @Autowired KnowledgeBaseProfileHandler baseProfile;

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

  @Test void nativeOwnAndSharedUploadsWithAndWithoutSummaryUseRealHalfvecJsonbAndTrace() throws Exception {
    assertThat(jdbc.queryForObject("SELECT current_database()", String.class))
        .isEqualTo("weknora_processing_parity");
    jdbc.update("INSERT INTO tenants(id,name,business,storage_engine_config)"
        + " VALUES(7,'Caller','test', '{}'::jsonb),(8,'Owner','test','{}'::jsonb)");
    for (var entry : Map.of("caller", 7L, "owner", 8L).entrySet()) {
      jdbc.update("INSERT INTO users(id,username,email,password_hash,tenant_id) VALUES(?,?,?,'unused',?)",
          entry.getKey(), entry.getKey(), entry.getKey()+"@pg.test.invalid", entry.getValue());
      jdbc.update("INSERT INTO tenant_members(user_id,tenant_id,role,status) VALUES(?,?,?,'active')",
          entry.getKey(), entry.getValue(), entry.getKey().equals("owner") ? "owner" : "contributor");
    }
    jdbc.update("INSERT INTO knowledge_bases(id,tenant_id,name,type,creator_id,embedding_model_id,summary_model_id,indexing_strategy,chunking_config)"
        + " VALUES('pg-kb',8,'PG','document','owner','pg-embedding','pg-summary',?::jsonb,?::jsonb)",
        "{\"vector_enabled\":true,\"keyword_enabled\":true,\"graph_enabled\":false,\"wiki_enabled\":false}",
        "{\"chunk_size\":512,\"chunk_overlap\":10}");
    String parameters = "{\"provider\":\"generic\",\"base_url\":\"http://127.0.0.1:"
        + SERVER.getAddress().getPort()+"/v1\",\"embedding_parameters\":{\"dimension\":3}}";
    for (String kind : List.of("Embedding", "KnowledgeQA"))
      jdbc.update("INSERT INTO models(id,tenant_id,name,type,source,parameters,is_builtin,status)"
          + " VALUES(?,8,'unknown',?,'remote',?::jsonb,false,'active')",
          kind.equals("Embedding") ? "pg-embedding" : "pg-summary", kind, parameters);
    jdbc.update("INSERT INTO organizations(id,name,owner_tenant_id,owner_id) VALUES('pg-org','PG',8,'owner')");
    jdbc.update("INSERT INTO organization_tenant_members(id,organization_id,tenant_id,role)"
        + " VALUES('pg-owner','pg-org',8,'admin'),('pg-caller','pg-org',7,'editor')");
    jdbc.update("INSERT INTO kb_shares(id,knowledge_base_id,organization_id,source_tenant_id,permission,shared_by_user_id)"
        + " VALUES('pg-share','pg-kb','pg-org',8,'editor','owner')");
    Map<String, String> tokens = Map.of("owner", token("owner",8), "caller", token("caller",7));
    jdbc.update("INSERT INTO knowledge_tags(id,tenant_id,knowledge_base_id,name,sort_order,created_at) VALUES('pg-auto-tag',8,'pg-kb','Migration',0,CURRENT_TIMESTAMP)");
    for (String mode : List.of("plain","summary","summary-question","summary-question-tag")) for (String actor : List.of("owner", "caller")) {
      boolean enabled=!mode.equals("plain"),questions=mode.startsWith("summary-question"),tagging=mode.equals("summary-question-tag");
      jdbc.update("UPDATE knowledge_bases SET auto_tag_config=?::jsonb WHERE id='pg-kb'",tagging?"{\"enabled\":true}":"{}");
      jdbc.update("UPDATE knowledge_bases SET question_generation_config=?::jsonb WHERE id='pg-kb'",questions?"{\"enabled\":true,\"question_count\":3}":"{}");
      byte[] body = ("--pg-boundary\r\nContent-Disposition: form-data; name=\"process_config\"\r\n\r\n"
          + "{\"summary_enabled\":"+enabled+"}\r\n--pg-boundary\r\n"
          + "Content-Disposition: form-data; name=\"file\"; filename=\""+actor+"-"+mode+".txt\"\r\n"
          + "Content-Type: text/plain\r\n\r\nPostgreSQL document from "+actor+" with mode="+mode
          + ".\nNative index publication.\r\n--pg-boundary--\r\n").getBytes(StandardCharsets.UTF_8);
      var response = mvc.perform(post("/api/v1/knowledge-bases/pg-kb/knowledge/file")
          .contentType("multipart/form-data; boundary=pg-boundary").content(body)
          .header("Authorization", "Bearer "+tokens.get(actor)).header("Accept-Language", "en-US"))
          .andReturn().getResponse();
      assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
      String id = JSON.readTree(response.getContentAsString()).path("data").path("id").asText();
      TaskRecord doc = claim("default");
      assertThat(doc.tenantId()).isEqualTo(8);
      assertThat(document.execute(doc,cancellation(doc)).path("indexed").asBoolean()).isTrue();
      assertThat(tasks.complete(doc,JSON.createObjectNode())).isTrue();
      TaskRecord post = claim("postprocess");
      var result = postprocess.execute(post,cancellation(post));
      assertThat(result.path(enabled ? "enqueued_summary" : "completed").asBoolean()).isTrue();
      assertThat(tasks.complete(post,JSON.createObjectNode())).isTrue();
      if(tagging){
        String tagJob=jdbc.queryForObject("SELECT id FROM java_task_jobs WHERE type='knowledge:auto_tag' AND payload::jsonb->>'knowledge_id'=?",String.class,id);jdbc.update("UPDATE java_task_jobs SET available_at=0 WHERE id=?",tagJob);var tag=claim("summary");assertThat(tag.type()).isEqualTo("knowledge:auto_tag");assertThat(tag.maxAttempts()).isEqualTo(3);assertThat(tag.timeoutMillis()).isEqualTo(120000);
        assertThat(autoTag.execute(tag,cancellation(tag)).path("added_tag_count").asInt()).isEqualTo(1);assertThat(tasks.complete(tag,JSON.createObjectNode())).isTrue();assertThat(jdbc.queryForObject("SELECT pending_subtasks_count FROM knowledges WHERE id=?",Integer.class,id)).isEqualTo(2);assertThat(jdbc.queryForList("SELECT tag_id FROM knowledge_tag_relations WHERE knowledge_id=?",String.class,id)).containsExactly("pg-auto-tag");
      }
      if (enabled) {
        TaskRecord enrichment = claim("summary");
        assertThat(summary.execute(enrichment,cancellation(enrichment)).path("status").asText()).isEqualTo("completed");
        assertThat(tasks.complete(enrichment,JSON.createObjectNode())).isTrue();
        assertThat(jdbc.queryForObject("SELECT description FROM knowledges WHERE id=?",String.class,id)).isEqualTo("PostgreSQL summary 中文");
        assertThat(jdbc.queryForObject("SELECT profile->>'gist' FROM knowledges WHERE id=?",String.class,id)).isEqualTo("Native profile");
      }
      if(questions){
        assertThat(jdbc.queryForObject("SELECT pending_subtasks_count FROM knowledges WHERE id=?",Integer.class,id)).isEqualTo(1);
        TaskRecord batch=claim("question");assertThat(question.execute(batch,cancellation(batch)).path("questions_total").asInt()).isEqualTo(3);assertThat(tasks.complete(batch,JSON.createObjectNode())).isTrue();
        assertThat(jdbc.queryForObject("SELECT jsonb_array_length(metadata->'generated_questions') FROM chunks WHERE knowledge_id=? AND chunk_type='text'",Integer.class,id)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM java_question_task_drains WHERE tenant_id=8 AND knowledge_id=?",Integer.class,id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM embeddings e JOIN chunks c ON c.id=e.chunk_id WHERE c.knowledge_id=? AND c.chunk_type='text' AND e.source_id<>c.id",Integer.class,id)).isEqualTo(3);
      }
      assertThat(jdbc.queryForObject("SELECT parse_status FROM knowledges WHERE id=?",String.class,id)).isEqualTo("completed");
      assertThat(jdbc.queryForObject("SELECT pending_subtasks_count FROM knowledges WHERE id=?",Integer.class,id)).isZero();
      assertThat(jdbc.queryForObject("SELECT count(*) FROM embeddings WHERE knowledge_id=? AND dimension=3 AND embedding::text='[1,0,0]'",Integer.class,id)).isEqualTo(questions ? 5 : enabled ? 2 : 1);
      assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_processing_spans WHERE knowledge_id=? AND kind='root' AND status='done'",Integer.class,id)).isEqualTo(1);
      assertThat(jdbc.queryForObject("SELECT actor_user_id FROM audit_logs WHERE action='knowledge.created' AND target_id=?",String.class,id)).isEqualTo(actor);
      for (String endpoint : List.of("spans", "stages", "index-status")) {
        var read = mvc.perform(get("/api/v1/knowledge/"+id+"/"+endpoint)
            .header("Authorization", "Bearer "+tokens.get("caller"))).andReturn().getResponse();
        assertThat(read.getStatus()).as(read.getContentAsString()).isEqualTo(200);
      }
    }
    assertThat(EMBEDDINGS.get()).isEqualTo(18);
    assertThat(CHATS.get()).isEqualTo(12);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM java_task_jobs WHERE status='succeeded'",Integer.class)).isEqualTo(28);
    var enable=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/knowledge-bases/pg-kb").header("Authorization","Bearer "+tokens.get("caller")).header("Accept-Language","en-US").contentType("application/json").content("{\"name\":\"PG\",\"config\":{\"profile_config\":{\"enabled\":true}}}")).andReturn().getResponse();
    assertThat(enable.getStatus()).as(enable.getContentAsString()).isEqualTo(200);
    var pendingProfile=jdbc.queryForMap("SELECT id,tenant_id,queue,max_attempts,timeout_millis,available_at-created_at AS delay FROM java_task_jobs WHERE type='kb:profile'");
    assertThat(((Number)pendingProfile.get("tenant_id")).longValue()).isEqualTo(8);assertThat(pendingProfile.get("queue")).isEqualTo("summary");assertThat(((Number)pendingProfile.get("max_attempts")).intValue()).isEqualTo(3);assertThat(((Number)pendingProfile.get("timeout_millis")).intValue()).isEqualTo(300000);assertThat(((Number)pendingProfile.get("delay")).intValue()).isEqualTo(30000);
    jdbc.update("UPDATE java_task_jobs SET available_at=0 WHERE id=?",pendingProfile.get("id"));var profileJob=claim("summary");assertThat(baseProfile.execute(profileJob,cancellation(profileJob)).path("status").asText()).isEqualTo("ready");assertThat(tasks.complete(profileJob,JSON.createObjectNode())).isTrue();
    for(String actor:List.of("owner","caller")){var generated=mvc.perform(post("/api/v1/knowledge-bases/pg-kb/profile/generate").header("Authorization","Bearer "+tokens.get(actor)).header("Accept-Language","en-US")).andReturn().getResponse();assertThat(generated.getStatus()).as(generated.getContentAsString()).isEqualTo(200);assertThat(JSON.readTree(generated.getContentAsString()).path("data").path("gist").asText()).isEqualTo("PostgreSQL knowledge collection");}
    assertThat(jdbc.queryForObject("SELECT generated_profile->>'status' FROM knowledge_bases WHERE id='pg-kb'",String.class)).isEqualTo("ready");assertThat(jdbc.queryForObject("SELECT (generated_profile->'stats'->>'document_count')::int FROM knowledge_bases WHERE id='pg-kb'",Integer.class)).isEqualTo(8);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM java_task_jobs WHERE type='kb:profile'",Integer.class)).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT sum(pending_subtasks_count) FROM knowledges",Integer.class)).isZero();
    jdbc.update("UPDATE knowledge_bases SET profile_config='{}'::jsonb WHERE id='pg-kb'");
    jdbc.update("UPDATE knowledge_bases SET question_generation_config='{}'::jsonb,auto_tag_config='{}'::jsonb WHERE id='pg-kb'");
    for(String actor:List.of("owner","caller")){
      byte[] body=("--pg-cancel\r\nContent-Disposition: form-data; name=\"process_config\"\r\n\r\n{\"summary_enabled\":true}\r\n--pg-cancel\r\nContent-Disposition: form-data; name=\"file\"; filename=\"cancel-"+actor+".txt\"\r\nContent-Type: text/plain\r\n\r\nPreserved PostgreSQL content for cancellation from "+actor+".\r\n--pg-cancel--\r\n").getBytes(StandardCharsets.UTF_8);
      var upload=mvc.perform(post("/api/v1/knowledge-bases/pg-kb/knowledge/file").contentType("multipart/form-data; boundary=pg-cancel").content(body).header("Authorization","Bearer "+tokens.get(actor))).andReturn().getResponse();assertThat(upload.getStatus()).as(upload.getContentAsString()).isEqualTo(200);
      String id=JSON.readTree(upload.getContentAsString()).path("data").path("id").asText();var doc=claim("default");document.execute(doc,cancellation(doc));tasks.complete(doc,JSON.createObjectNode());var post=claim("postprocess");postprocess.execute(post,cancellation(post));tasks.complete(post,JSON.createObjectNode());var job=claim("summary");
      jdbc.update("INSERT INTO task_pending_ops(tenant_id,task_type,scope,scope_id,dedup_key,op) VALUES(8,'wiki:ingest','knowledge_base','pg-kb',?,'ingest'),(8,'wiki:ingest','knowledge_base','pg-kb',?,'retract')",id,id);
      duringChat=()->{try{var response=mvc.perform(post("/api/v1/knowledge/"+id+"/cancel-parse").header("Authorization","Bearer "+tokens.get(actor))).andReturn().getResponse();assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);}catch(Exception e){throw new RuntimeException(e);}};
      try{org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.CancellationException.class,()->summary.execute(job,cancellation(job)));}finally{duringChat=()->{};}
      assertThat(tasks.acknowledgeCancellation(job)).isTrue();assertThat(tasks.get(8,job.id()).orElseThrow().status()).isEqualTo("canceled");
      assertThat(jdbc.queryForObject("SELECT parse_status FROM knowledges WHERE id=?",String.class,id)).isEqualTo("cancelled");assertThat(jdbc.queryForObject("SELECT pending_subtasks_count FROM knowledges WHERE id=?",Integer.class,id)).isZero();assertThat(jdbc.queryForObject("SELECT summary_status FROM knowledges WHERE id=?",String.class,id)).isEqualTo("none");
      assertThat(jdbc.queryForObject("SELECT count(*) FROM embeddings WHERE knowledge_id=?",Integer.class,id)).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT count(*) FROM chunks WHERE knowledge_id=? AND chunk_type='summary'",Integer.class,id)).isZero();
      assertThat(jdbc.queryForObject("SELECT count(*) FROM task_pending_ops WHERE dedup_key=? AND op='ingest'",Integer.class,id)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM task_pending_ops WHERE dedup_key=? AND op='retract'",Integer.class,id)).isEqualTo(1);
      assertThat(jdbc.queryForObject("SELECT actor_user_id FROM audit_logs WHERE target_id=? AND action='knowledge.parse_canceled'",String.class,id)).isEqualTo(actor);
      assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_processing_spans WHERE knowledge_id=? AND kind='root' AND status='done'",Integer.class,id)).isEqualTo(1);
    }
    // Go closes the parse root at postprocess dispatch; cancellation closes open children
    // and preserves that already-terminal root rather than rewriting its successful parse.
    assertThat(EMBEDDINGS.get()).isEqualTo(20);assertThat(CHATS.get()).isEqualTo(17);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM java_task_jobs",Integer.class)).isEqualTo(35);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM java_task_jobs WHERE status='canceled'",Integer.class)).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT storage_used FROM tenants WHERE id=8",Long.class))
        .isEqualTo(jdbc.queryForObject("SELECT sum(storage_size) FROM knowledges WHERE tenant_id=8",Long.class));
    // Public metadata update uses the native durable refresh producer after the saved edit.
    for(String actor:List.of("owner","caller")){
      String id=jdbc.queryForObject("SELECT id FROM knowledges WHERE file_name=?",String.class,actor+"-summary.txt");
      String oldSummary=jdbc.queryForObject("SELECT id FROM chunks WHERE knowledge_id=? AND chunk_type='summary'",String.class,id);
      var textRows=jdbc.queryForList("SELECT e.* FROM embeddings e JOIN chunks c ON c.id=e.chunk_id WHERE e.knowledge_id=? AND c.chunk_type<>'summary' ORDER BY e.id",id);
      var textChunks=jdbc.queryForList("SELECT * FROM chunks WHERE knowledge_id=? AND chunk_type<>'summary' ORDER BY id",id);
      String file=jdbc.queryForObject("SELECT file_path FROM knowledges WHERE id=?",String.class,id);byte[] bytes;try(var in=storage.forKnowledgeBase(8,"pg-kb").files().open(file)){bytes=in.readAllBytes();}
      long quota=jdbc.queryForObject("SELECT storage_used FROM tenants WHERE id=8",Long.class);int calls=CHATS.get();
      if(actor.equals("owner"))jdbc.update("UPDATE tenant_members SET role='viewer' WHERE tenant_id=8 AND user_id='owner'");
      var updated=mvc.perform(put("/api/v1/knowledge/"+id).header("Authorization","Bearer "+tokens.get(actor)).header("Accept-Language","en-US").contentType("application/json").content("{\"title\":\"PG updated "+actor+"\",\"custom_metadata\":{\"reviewer\":\"PG refresh "+actor+"\"}}")).andReturn().getResponse();
      assertThat(updated.getStatus()).as(updated.getContentAsString()).isEqualTo(200);var dto=JSON.readTree(updated.getContentAsByteArray()).path("data");assertThat(dto.path("tenant_id").asLong()).isEqualTo(8);assertThat(dto.path("summary_status").asText()).isEqualTo("pending");assertThat(dto.path("custom_metadata").path("reviewer").asText()).isEqualTo("PG refresh "+actor);
      var refresh=claim("summary");assertThat(refresh.type()).isEqualTo("summary:generation");assertThat(refresh.payload().path("refresh").asBoolean()).isTrue();assertThat(refresh.payload().path("tenant_id").asLong()).isEqualTo(8);assertThat(refresh.payload().path("attempt").asLong()).isEqualTo(1);assertThat(refresh.payload().path("language").asText()).isEqualTo("en-US");assertThat(refresh.maxAttempts()).isEqualTo(4);assertThat(refresh.timeoutMillis()).isEqualTo(1800000);
      assertThat(summary.execute(refresh,cancellation(refresh)).path("status").asText()).isEqualTo("completed");assertThat(tasks.complete(refresh,JSON.createObjectNode())).isTrue();assertThat(CHATS.get()).isEqualTo(calls+1);
      assertThat(jdbc.queryForObject("SELECT summary_status FROM knowledges WHERE id=?",String.class,id)).isEqualTo("completed");assertThat(jdbc.queryForObject("SELECT pending_subtasks_count FROM knowledges WHERE id=?",Integer.class,id)).isZero();assertThat(jdbc.queryForObject("SELECT id FROM chunks WHERE knowledge_id=? AND chunk_type='summary'",String.class,id)).isEqualTo(oldSummary);
      assertThat(jdbc.queryForList("SELECT e.* FROM embeddings e JOIN chunks c ON c.id=e.chunk_id WHERE e.knowledge_id=? AND c.chunk_type<>'summary' ORDER BY e.id",id)).isEqualTo(textRows);assertThat(jdbc.queryForList("SELECT * FROM chunks WHERE knowledge_id=? AND chunk_type<>'summary' ORDER BY id",id)).isEqualTo(textChunks);assertThat(jdbc.queryForObject("SELECT storage_used FROM tenants WHERE id=8",Long.class)).isEqualTo(quota);
      try(var in=storage.forKnowledgeBase(8,"pg-kb").files().open(file)){assertThat(in.readAllBytes()).isEqualTo(bytes);}
      assertThat(jdbc.queryForObject("SELECT count(*) FROM embeddings WHERE knowledge_id=? AND dimension=3 AND embedding::text='[1,0,0]'",Integer.class,id)).isEqualTo(2);
      // Native public edit/revert after a real upload, including actual BM25 and halfvec replacement.
      String chunk=jdbc.queryForObject("SELECT id FROM chunks WHERE knowledge_id=? AND chunk_type='text' ORDER BY chunk_index LIMIT 1",String.class,id);
      String original=jdbc.queryForObject("SELECT content FROM chunks WHERE id=?",String.class,chunk),marker="chunkeditmarker"+actor,replacement=marker+" PostgreSQL edited evidence 中文";
      String target="/api/v1/chunks/"+id+"/"+chunk;
      for(int revision:List.of(0,1)){
        int embeddingsBefore=EMBEDDINGS.get(),chatsBefore=CHATS.get();long jobsBefore=jdbc.queryForObject("SELECT count(*) FROM java_task_jobs",Long.class);
        String wanted=revision==0?replacement:original;
        var editRequest=revision==0?put(target).content(JSON.writeValueAsString(Map.of("content",replacement,"expected_revision",0))):post(target+"/revert").content("{\"revision\":0,\"expected_revision\":1}");
        var edited=mvc.perform(editRequest.header("Authorization","Bearer "+tokens.get(actor)).header("Accept-Language","en-US").contentType("application/json")).andReturn().getResponse();
        assertThat(edited.getStatus()).as(edited.getContentAsString()).isEqualTo(200);var editedBody=JSON.readTree(edited.getContentAsByteArray());
        assertThat(editedBody.path("data").path("content").asText()).isEqualTo(wanted);assertThat(editedBody.path("data").path("content_revision").asInt()).isEqualTo(revision+1);assertThat(editedBody.path("data").path("index_status").asText()).isEqualTo("ready");assertThat(editedBody.path("summary_status").asText()).isEqualTo("pending");
        assertThat(jdbc.queryForObject("SELECT content FROM chunks WHERE id=?",String.class,chunk)).isEqualTo(wanted);assertThat(jdbc.queryForObject("SELECT source_content FROM chunks WHERE id=?",String.class,chunk)).isEqualTo(original);assertThat(jdbc.queryForObject("SELECT source_locators IS NULL FROM chunks WHERE id=?",Boolean.class,chunk)).isTrue();assertThat(jdbc.queryForObject("SELECT last_editor_id FROM chunks WHERE id=?",String.class,chunk)).isEqualTo(actor);
        var indexed=jdbc.queryForList("SELECT source_id,chunk_id,knowledge_id,knowledge_base_id,content,embedding::text AS vector,embedding::halfvec(3) <=> '[1,0,0]'::halfvec(3) AS distance FROM embeddings WHERE chunk_id=?",chunk);
        assertThat(indexed).hasSize(1);var entry=indexed.getFirst();assertThat(entry.get("source_id")).isEqualTo(chunk);assertThat(entry.get("knowledge_id")).isEqualTo(id);assertThat(entry.get("knowledge_base_id")).isEqualTo("pg-kb");assertThat(entry.get("content").toString()).endsWith(wanted);assertThat(entry.get("vector")).isEqualTo("[1,0,0]");assertThat(((Number)entry.get("distance")).doubleValue()).isZero();
        var matches=jdbc.queryForList("SELECT source_id FROM embeddings WHERE content ||| ? AND chunk_id=?",String.class,marker,chunk);assertThat(matches).hasSize(revision==0?1:0);
        assertThat(EMBEDDINGS.get()).isEqualTo(embeddingsBefore+1);assertThat(CHATS.get()).isEqualTo(chatsBefore);assertThat(jdbc.queryForObject("SELECT count(*) FROM java_task_jobs",Long.class)).isEqualTo(jobsBefore+1);
        var chunkRefresh=claim("summary");assertThat(chunkRefresh.type()).isEqualTo("summary:generation");assertThat(chunkRefresh.payload()).isEqualTo(JSON.valueToTree(Map.of("tenant_id",8,"knowledge_base_id","pg-kb","knowledge_id",id,"language","en-US","attempt",1,"refresh",true)));assertThat(chunkRefresh.maxAttempts()).isEqualTo(4);assertThat(chunkRefresh.timeoutMillis()).isEqualTo(1800000);assertThat(summary.execute(chunkRefresh,cancellation(chunkRefresh)).path("status").asText()).isEqualTo("completed");assertThat(tasks.complete(chunkRefresh,JSON.createObjectNode())).isTrue();
        assertThat(EMBEDDINGS.get()).isEqualTo(embeddingsBefore+2);assertThat(CHATS.get()).isEqualTo(chatsBefore+1);assertThat(jdbc.queryForObject("SELECT id FROM chunks WHERE knowledge_id=? AND chunk_type='summary'",String.class,id)).isEqualTo(oldSummary);assertThat(jdbc.queryForObject("SELECT storage_used FROM tenants WHERE id=8",Long.class)).isEqualTo(quota);assertThat(jdbc.queryForObject("SELECT parse_status FROM knowledges WHERE id=?",String.class,id)).isEqualTo("completed");assertThat(jdbc.queryForObject("SELECT pending_subtasks_count FROM knowledges WHERE id=?",Integer.class,id)).isZero();try(var in=storage.forKnowledgeBase(8,"pg-kb").files().open(file)){assertThat(in.readAllBytes()).isEqualTo(bytes);}
      }
      var historyResponse=mvc.perform(get(target+"/revisions").header("Authorization","Bearer "+tokens.get(actor))).andReturn().getResponse();assertThat(historyResponse.getStatus()).isEqualTo(200);var history=JSON.readTree(historyResponse.getContentAsByteArray()).path("data");assertThat(history.size()).isEqualTo(2);assertThat(history.get(0).path("revision").asInt()).isEqualTo(1);assertThat(history.get(0).path("content").asText()).isEqualTo(replacement);assertThat(history.get(0).path("editor_id").asText()).isEqualTo(actor);assertThat(history.get(1).path("revision").asInt()).isZero();assertThat(history.get(1).path("content").asText()).isEqualTo(original);
      long noOpJobs=jdbc.queryForObject("SELECT count(*) FROM java_task_jobs",Long.class);int noOpModels=EMBEDDINGS.get()+CHATS.get();
      assertThat(mvc.perform(put(target).header("Authorization","Bearer "+tokens.get(actor)).contentType("application/json").content("{\"content\":\"stale overwrite\",\"expected_revision\":1}")).andReturn().getResponse().getStatus()).isEqualTo(409);
      assertThat(mvc.perform(put(target).header("Authorization","Bearer "+tokens.get(actor)).contentType("application/json").content(JSON.writeValueAsString(Map.of("content",original,"expected_revision",2)))).andReturn().getResponse().getStatus()).isEqualTo(200);
      assertThat(jdbc.queryForObject("SELECT count(*) FROM java_task_jobs",Long.class)).isEqualTo(noOpJobs);assertThat(EMBEDDINGS.get()+CHATS.get()).isEqualTo(noOpModels);
      publicChunkQuestions(actor,id,chunk,tokens.get(actor));
      jdbc.update("UPDATE tenant_members SET role='owner' WHERE tenant_id=8 AND user_id='owner'");
    }
    long retained=jdbc.queryForObject("SELECT count(*) FROM embeddings",Long.class);
    var cleanupDocuments=jdbc.queryForList("SELECT id FROM knowledges WHERE tenant_id=8 AND file_name IN ('owner-plain.txt','caller-plain.txt') ORDER BY file_name",String.class);
    assertThat(cleanupDocuments).hasSize(2);
    var firstIds=jdbc.queryForList("SELECT id FROM chunks WHERE tenant_id=8 AND knowledge_id=? ORDER BY chunk_index",String.class,cleanupDocuments.getFirst());
    var deniedPayload=JSON.createObjectNode().put("tenant_id",8).put("knowledge_base_id","ungranted-pg-kb").put("embedding_model_id","pg-embedding").put("kb_type","document");
    deniedPayload.set("chunk_ids",JSON.valueToTree(firstIds));deniedPayload.set("effective_engines",JSON.createArrayNode().add(JSON.createObjectNode().put("retriever_engine_type","postgres").put("retriever_type","vector")));
    tasks.enqueue(8,"index:delete",null,deniedPayload,11,Duration.ZERO,"low",Duration.ofHours(1));var deniedCleanup=claim("low");
    assertThat(indexDelete.execute(deniedCleanup,cancellation(deniedCleanup)).path("chunk_ids").asInt()).isZero();assertThat(tasks.complete(deniedCleanup,JSON.createObjectNode())).isTrue();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM embeddings",Long.class)).isEqualTo(retained);
    for(String id:cleanupDocuments){
      var ids=jdbc.queryForList("SELECT id FROM chunks WHERE tenant_id=8 AND knowledge_id=? ORDER BY chunk_index",String.class,id);
      var payload=JSON.createObjectNode().put("tenant_id",8).put("knowledge_base_id","pg-kb").put("embedding_model_id","pg-embedding").put("kb_type","document");
      payload.set("chunk_ids",JSON.valueToTree(ids));payload.set("effective_engines",JSON.createArrayNode().add(JSON.createObjectNode().put("retriever_engine_type","postgres").put("retriever_type","vector")).add(JSON.createObjectNode().put("retriever_engine_type","postgres").put("retriever_type","keywords")));
      String actor=jdbc.queryForObject("SELECT file_name FROM knowledges WHERE id=?",String.class,id).startsWith("owner-")?"owner":"caller",chunk=ids.getFirst();
      var oldIndexes=jdbc.queryForList("SELECT id,source_id,content,dimension,embedding::text,is_enabled,updated_at FROM embeddings WHERE knowledge_id=? ORDER BY id",id);assertThat(oldIndexes).isNotEmpty();
      var oldFts=jdbc.queryForList("SELECT source_id FROM embeddings WHERE content ||| 'PostgreSQL' AND knowledge_id=? ORDER BY source_id",String.class,id);assertThat(oldFts).isNotEmpty();
      var oldVectors=jdbc.queryForList("SELECT source_id,embedding::halfvec(3) <=> '[1,0,0]'::halfvec(3) AS distance FROM embeddings WHERE knowledge_id=? ORDER BY source_id",id);
      long quota=jdbc.queryForObject("SELECT storage_used FROM tenants WHERE id=8",Long.class),jobsBefore=jdbc.queryForObject("SELECT count(*) FROM java_task_jobs",Long.class);String reference=jdbc.queryForObject("SELECT file_path FROM knowledges WHERE id=?",String.class,id);byte[] sourceBefore;try(var in=storage.forKnowledgeBase(8,"pg-kb").files().open(reference)){sourceBefore=in.readAllBytes();}assertThat(sourceBefore).isNotEmpty();
      for(int revision:List.of(1,2))jdbc.update("INSERT INTO chunk_revisions(id,tenant_id,knowledge_base_id,knowledge_id,chunk_id,revision,content,is_enabled,editor_id,edit_source,edited_at,created_at) VALUES(?,8,'pg-kb',?,?,?, ?,true,?,'manual',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",UUID.randomUUID().toString(),id,chunk,revision,"Original PG revision "+revision,actor);
      String folder="迁移/"+actor;
      var refile=mvc.perform(post("/api/v1/knowledge/folder").header("Authorization","Bearer "+tokens.get(actor)).contentType("application/json").content(JSON.writeValueAsString(Map.of("kb_id","pg-kb","knowledge_ids",List.of(" "+id+" ",id),"folder_path",folder)))).andReturn().getResponse();assertThat(refile.getStatus()).as(refile.getContentAsString()).isEqualTo(200);assertThat(JSON.readTree(refile.getContentAsByteArray()).path("data").path("moved_count").asInt()).isEqualTo(1);
      if(actor.equals("owner"))jdbc.update("UPDATE tenant_members SET role='viewer' WHERE user_id='owner' AND tenant_id=8");
      var rename=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/knowledge-bases/pg-kb/knowledge/folders").header("Authorization","Bearer "+tokens.get(actor)).contentType("application/json").content(JSON.writeValueAsString(Map.of("from",folder,"to","目录/"+actor)))).andReturn().getResponse();assertThat(rename.getStatus()).as(rename.getContentAsString()).isEqualTo(200);assertThat(JSON.readTree(rename.getContentAsByteArray()).path("data").path("moved_count").asInt()).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT folder_path FROM knowledges WHERE id=?",String.class,id)).isEqualTo("目录/"+actor);
      var tree=mvc.perform(get("/api/v1/knowledge-bases/pg-kb/knowledge/folders").header("Authorization","Bearer "+tokens.get(actor))).andReturn().getResponse();assertThat(tree.getStatus()).as(tree.getContentAsString()).isEqualTo(200);assertThat(JSON.readTree(tree.getContentAsByteArray()).path("data").path("folders").toString()).contains("目录/"+actor);
      var history=mvc.perform(get("/api/v1/chunks/"+id+"/"+chunk+"/revisions").header("Authorization","Bearer "+tokens.get("caller"))).andReturn().getResponse();assertThat(history.getStatus()).as(history.getContentAsString()).isEqualTo(200);var items=JSON.readTree(history.getContentAsByteArray()).path("data");assertThat(items.size()).isEqualTo(2);assertThat(items.get(0).path("revision").asInt()).isEqualTo(2);assertThat(items.get(0).path("tenant_id").asLong()).isEqualTo(8);assertThat(items.get(0).path("content").asText()).isEqualTo("Original PG revision 2");
      if(actor.equals("owner"))jdbc.update("UPDATE tenant_members SET role='viewer' WHERE user_id='owner' AND tenant_id=8");
      var chunkDelete=mvc.perform(delete("/api/v1/chunks/"+id+(actor.equals("owner")?"/"+chunk:"")).header("Authorization","Bearer "+tokens.get(actor))).andReturn().getResponse();assertThat(chunkDelete.getStatus()).as(chunkDelete.getContentAsString()).isEqualTo(200);
      if(actor.equals("owner"))jdbc.update("UPDATE tenant_members SET role='owner' WHERE user_id='owner' AND tenant_id=8");
      assertThat(jdbc.queryForObject("SELECT count(*) FROM chunks WHERE knowledge_id=? AND deleted_at IS NULL",Integer.class,id)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM chunk_revisions WHERE chunk_id=?",Integer.class,chunk)).isEqualTo(2);assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledges WHERE id=? AND deleted_at IS NULL AND parse_status='completed'",Integer.class,id)).isEqualTo(1);
      assertThat(jdbc.queryForList("SELECT id,source_id,content,dimension,embedding::text,is_enabled,updated_at FROM embeddings WHERE knowledge_id=? ORDER BY id",id)).isEqualTo(oldIndexes);assertThat(jdbc.queryForList("SELECT source_id FROM embeddings WHERE content ||| 'PostgreSQL' AND knowledge_id=? ORDER BY source_id",String.class,id)).isEqualTo(oldFts);assertThat(jdbc.queryForList("SELECT source_id,embedding::halfvec(3) <=> '[1,0,0]'::halfvec(3) AS distance FROM embeddings WHERE knowledge_id=? ORDER BY source_id",id)).isEqualTo(oldVectors);
      assertThat(jdbc.queryForObject("SELECT storage_used FROM tenants WHERE id=8",Long.class)).isEqualTo(quota);assertThat(jdbc.queryForObject("SELECT count(*) FROM java_task_jobs",Long.class)).isEqualTo(jobsBefore);try(var in=storage.forKnowledgeBase(8,"pg-kb").files().open(reference)){assertThat(in.readAllBytes()).isEqualTo(sourceBefore);}
      var gone=mvc.perform(get("/api/v1/chunks/"+id+"/"+chunk+"/revisions").header("Authorization","Bearer "+tokens.get("caller"))).andReturn().getResponse();assertThat(gone.getStatus()).isEqualTo(404);

      tasks.enqueue(8,"index:delete",null,payload,11,Duration.ZERO,"low",Duration.ofHours(1));var cleanup=claim("low");
      assertThat(cleanup.maxAttempts()).isEqualTo(11);assertThat(cleanup.timeoutMillis()).isEqualTo(3600000);
      assertThat(indexDelete.execute(cleanup,cancellation(cleanup)).path("chunk_ids").asInt()).isEqualTo(ids.size());assertThat(tasks.complete(cleanup,JSON.createObjectNode())).isTrue();
      assertThat(jdbc.queryForObject("SELECT count(*) FROM embeddings WHERE knowledge_id=?",Integer.class,id)).isZero();
      assertThat(jdbc.queryForObject("SELECT pending_subtasks_count FROM knowledges WHERE id=?",Integer.class,id)).isZero();
    }
    assertThat(jdbc.queryForObject("SELECT count(*) FROM embeddings",Long.class)).isEqualTo(retained-2);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM java_task_jobs",Integer.class)).isEqualTo(44);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM java_task_jobs WHERE status='succeeded'",Integer.class)).isEqualTo(42);
    assertThat(EMBEDDINGS.get()).isEqualTo(38);assertThat(CHATS.get()).isEqualTo(27);
    assertThat(jdbc.queryForObject("SELECT storage_used FROM tenants WHERE id=8",Long.class))
        .isEqualTo(jdbc.queryForObject("SELECT sum(storage_size) FROM knowledges WHERE tenant_id=8",Long.class));
    for(String actor:List.of("owner","caller")){
      String id=jdbc.queryForObject("SELECT id FROM knowledges WHERE tenant_id=8 AND file_name=?",String.class,actor+"-summary-question.txt");String reference=jdbc.queryForObject("SELECT file_path FROM knowledges WHERE id=?",String.class,id);String physical=storage.catalog().physical(8,reference);assertThat(Files.exists(FILES.resolve(physical.substring("local://".length())))).isTrue();
      long before=jdbc.queryForObject("SELECT storage_used FROM tenants WHERE id=8",Long.class),size=jdbc.queryForObject("SELECT storage_size FROM knowledges WHERE id=?",Long.class,id);
      String tag="delete-"+actor;jdbc.update("INSERT INTO knowledge_tags(id,tenant_id,knowledge_base_id,name) VALUES(?,8,'pg-kb',?)",tag,tag);jdbc.update("INSERT INTO knowledge_tag_relations(knowledge_id,tag_id) VALUES(?,?)",id,tag);
      var response=mvc.perform(delete("/api/v1/knowledge-bases/pg-kb/tags/"+tag+"?"+(actor.equals("owner")?"force=true":"content_only=true")).header("Authorization","Bearer "+tokens.get(actor))).andReturn().getResponse();assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
      var cleanup=claim("low");assertThat(cleanup.type()).isEqualTo("knowledge:list_delete");assertThat(cleanup.tenantId()).isEqualTo(8);assertThat(cleanup.maxAttempts()).isEqualTo(4);assertThat(cleanup.timeoutMillis()).isEqualTo(7200000);assertThat(cleanup.payload().path("initiator").path("user_id").asText()).isEqualTo(actor);
      assertThat(documentDelete.execute(cleanup,cancellation(cleanup)).path("deleted").asInt()).isEqualTo(1);assertThat(tasks.complete(cleanup,JSON.createObjectNode())).isTrue();assertThat(jdbc.queryForObject("SELECT count(*) FROM embeddings WHERE knowledge_id=?",Integer.class,id)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM chunks WHERE knowledge_id=? AND deleted_at IS NULL",Integer.class,id)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledges WHERE id=? AND deleted_at IS NOT NULL",Integer.class,id)).isEqualTo(1);assertThat(Files.exists(FILES.resolve(physical.substring("local://".length())))).isFalse();assertThat(jdbc.queryForObject("SELECT storage_used FROM tenants WHERE id=8",Long.class)).isEqualTo(Math.max(0,before-size));assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_tags WHERE id=?",Integer.class,tag)).isEqualTo(actor.equals("owner")?0:1);
      assertThat(jdbc.queryForObject("SELECT actor_user_id FROM audit_logs WHERE action='knowledge.batch_deleted' AND details->>'task_id'=?",String.class,cleanup.id())).isEqualTo(actor);
    }
    assertThat(jdbc.queryForObject("SELECT count(*) FROM java_task_jobs",Integer.class)).isEqualTo(46);assertThat(jdbc.queryForObject("SELECT count(*) FROM java_task_jobs WHERE status='succeeded'",Integer.class)).isEqualTo(44);assertThat(EMBEDDINGS.get()).isEqualTo(38);assertThat(CHATS.get()).isEqualTo(27);
    for(String actor:List.of("owner","caller")){
      String id=jdbc.queryForObject("SELECT id FROM knowledges WHERE tenant_id=8 AND file_name=?",String.class,actor+"-plain.txt"),reference=jdbc.queryForObject("SELECT file_path FROM knowledges WHERE id=?",String.class,id),physical=storage.catalog().physical(8,reference);
      assertThat(Files.exists(FILES.resolve(physical.substring("local://".length())))).isTrue();long before=jdbc.queryForObject("SELECT storage_used FROM tenants WHERE id=8",Long.class),size=jdbc.queryForObject("SELECT storage_size FROM knowledges WHERE id=?",Long.class,id);
      var request=actor.equals("owner")?delete("/api/v1/knowledge/"+id):post("/api/v1/knowledge/batch-delete").contentType("application/json").content(JSON.writeValueAsString(Map.of("kb_id","pg-kb","ids",List.of(" "+id+" ",id))));
      var response=mvc.perform(request.header("Authorization","Bearer "+tokens.get(actor))).andReturn().getResponse();assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);var admitted=JSON.readTree(response.getContentAsByteArray());var cleanup=claim("low");assertThat(admitted.path("data").path("task_id").asText()).isEqualTo(cleanup.id());if(actor.equals("caller"))assertThat(admitted.path("data").path("deleted_count").asInt()).isEqualTo(1);
      assertThat(cleanup.type()).isEqualTo("knowledge:list_delete");assertThat(cleanup.tenantId()).isEqualTo(8);assertThat(cleanup.maxAttempts()).isEqualTo(4);assertThat(cleanup.timeoutMillis()).isEqualTo(7200000);assertThat(cleanup.payload().path("knowledge_ids")).isEqualTo(JSON.createArrayNode().add(id));assertThat(cleanup.payload().path("initiator").path("user_id").asText()).isEqualTo(actor);
      assertThat(documentDelete.execute(cleanup,cancellation(cleanup)).path("deleted").asInt()).isEqualTo(1);assertThat(tasks.complete(cleanup,JSON.createObjectNode())).isTrue();assertThat(Files.exists(FILES.resolve(physical.substring("local://".length())))).isFalse();assertThat(jdbc.queryForObject("SELECT storage_used FROM tenants WHERE id=8",Long.class)).isEqualTo(Math.max(0,before-size));assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledges WHERE id=? AND deleted_at IS NOT NULL",Integer.class,id)).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT actor_user_id FROM audit_logs WHERE action='knowledge.batch_deleted' AND details->>'task_id'=?",String.class,cleanup.id())).isEqualTo(actor);
    }
    jdbc.update("INSERT INTO knowledge_bases(id,tenant_id,name,type,creator_id,embedding_model_id,summary_model_id) VALUES('pg-empty-delete-kb',8,'Empty','document','owner','','')");
    var empty=mvc.perform(delete("/api/v1/knowledge-bases/pg-empty-delete-kb/knowledge").header("Authorization","Bearer "+tokens.get("owner"))).andReturn().getResponse();assertThat(empty.getStatus()).as(empty.getContentAsString()).isEqualTo(200);assertThat(JSON.readTree(empty.getContentAsByteArray()).path("data").path("deleted_count").asInt()).isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM java_task_jobs",Integer.class)).isEqualTo(48);assertThat(jdbc.queryForObject("SELECT count(*) FROM java_task_jobs WHERE status='succeeded'",Integer.class)).isEqualTo(46);assertThat(EMBEDDINGS.get()).isEqualTo(38);assertThat(CHATS.get()).isEqualTo(27);

  }
  private void publicChunkQuestions(String actor,String document,String chunk,String token)throws Exception {
    String target="/api/v1/chunks/by-id/"+chunk+"/questions";int embeddingsBefore=EMBEDDINGS.get(),chatsBefore=CHATS.get();long jobsBefore=jdbc.queryForObject("SELECT count(*) FROM java_task_jobs",Long.class);
    var body=jdbc.queryForList("SELECT content,source_content,source_locators,content_revision,index_status,last_editor_id FROM chunks WHERE id=?",chunk);var history=jdbc.queryForList("SELECT * FROM chunk_revisions WHERE chunk_id=? ORDER BY revision",chunk);var documentState=jdbc.queryForList("SELECT tenant_id,parse_status,pending_subtasks_count,summary_status,description,file_path,file_hash,storage_size FROM knowledges WHERE id=?",document);long quota=jdbc.queryForObject("SELECT storage_used FROM tenants WHERE id=8",Long.class);String path=jdbc.queryForObject("SELECT file_path FROM knowledges WHERE id=?",String.class,document);byte[] original;try(var in=storage.forKnowledgeBase(8,"pg-kb").files().open(path)){original=in.readAllBytes();}
    var created=mvc.perform(put(target).header("Authorization","Bearer "+token).contentType("application/json").content("{\"question\":\"questionpgaliasmarker"+actor+" Created 中文\"}")).andReturn().getResponse();assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(200);String question=JSON.readTree(created.getContentAsByteArray()).path("data").path("id").asText();assertThat(UUID.fromString(question).toString()).isEqualTo(question);
    var updated=mvc.perform(put(target).header("Authorization","Bearer "+token).contentType("application/json").content(JSON.writeValueAsString(Map.of("question_id",question,"question","questionpgaliasmarker"+actor+" Updated 中文")))).andReturn().getResponse();assertThat(updated.getStatus()).as(updated.getContentAsString()).isEqualTo(200);assertThat(JSON.readTree(updated.getContentAsByteArray()).path("data").path("content_revision").asInt()).isEqualTo(2);
    String alias=chunk+"-q"+HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(question.getBytes(StandardCharsets.UTF_8)),0,12);assertThat(jdbc.queryForList("SELECT source_id FROM embeddings WHERE chunk_id=? AND content ||| ?",String.class,chunk,"questionpgaliasmarker"+actor)).containsExactly(alias);assertThat(jdbc.queryForObject("SELECT embedding::halfvec(3) <=> '[1,0,0]'::halfvec(3) FROM embeddings WHERE source_id=?",Double.class,alias)).isZero();
    var removed=mvc.perform(delete(target).header("Authorization","Bearer "+token).contentType("application/json").content(JSON.writeValueAsString(Map.of("question_id",question)))).andReturn().getResponse();assertThat(removed.getStatus()).as(removed.getContentAsString()).isEqualTo(200);assertThat(jdbc.queryForObject("SELECT count(*) FROM embeddings WHERE source_id=?",Integer.class,alias)).isZero();assertThat(EMBEDDINGS.get()).isEqualTo(embeddingsBefore+2);assertThat(CHATS.get()).isEqualTo(chatsBefore);
    Set<String> previous=Set.of();for(int attempt=1;attempt<=2;attempt++){
      var response=mvc.perform(post(target+"/regenerate").header("Authorization","Bearer "+token).header("Accept-Language","en-US").contentType("application/json").content("ignored invalid JSON")).andReturn().getResponse();assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);var questions=JSON.readTree(response.getContentAsByteArray()).path("data");assertThat(questions.size()).isEqualTo(3);Set<String> current=new HashSet<>();for(var item:questions){String id=item.path("id").asText();assertThat(UUID.fromString(id).toString()).isEqualTo(id);assertThat(item.path("content_revision").asInt()).isEqualTo(2);current.add(chunk+"-q"+HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8)),0,12));}
      assertThat(current).hasSize(3);if(!previous.isEmpty())assertThat(current).doesNotContainAnyElementsOf(previous);assertThat(new HashSet<>(jdbc.queryForList("SELECT source_id FROM embeddings WHERE chunk_id=? AND source_id<>? AND content ||| 'document'",String.class,chunk,chunk))).isEqualTo(current);assertThat(jdbc.queryForObject("SELECT count(*) FROM embeddings WHERE chunk_id=? AND embedding::halfvec(3) <=> '[1,0,0]'::halfvec(3)=0",Integer.class,chunk)).isEqualTo(4);assertThat(jdbc.queryForObject("SELECT (metadata->>'generated_questions_revision')::int FROM chunks WHERE id=?",Integer.class,chunk)).isEqualTo(2);for(String old:previous)assertThat(jdbc.queryForObject("SELECT count(*) FROM embeddings WHERE source_id=?",Integer.class,old)).isZero();previous=current;
    }
    assertThat(EMBEDDINGS.get()).isEqualTo(embeddingsBefore+4);assertThat(CHATS.get()).isEqualTo(chatsBefore+2);assertThat(jdbc.queryForObject("SELECT count(*) FROM java_task_jobs",Long.class)).isEqualTo(jobsBefore);assertThat(jdbc.queryForList("SELECT content,source_content,source_locators,content_revision,index_status,last_editor_id FROM chunks WHERE id=?",chunk)).isEqualTo(body);assertThat(jdbc.queryForList("SELECT * FROM chunk_revisions WHERE chunk_id=? ORDER BY revision",chunk)).isEqualTo(history);assertThat(jdbc.queryForList("SELECT tenant_id,parse_status,pending_subtasks_count,summary_status,description,file_path,file_hash,storage_size FROM knowledges WHERE id=?",document)).isEqualTo(documentState);assertThat(jdbc.queryForObject("SELECT storage_used FROM tenants WHERE id=8",Long.class)).isEqualTo(quota);try(var in=storage.forKnowledgeBase(8,"pg-kb").files().open(path)){assertThat(in.readAllBytes()).isEqualTo(original);}
  }

}
