package com.tencent.weknora.knowledge;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.api.ApiErrorAdvice;
import com.tencent.weknora.security.*;
import com.tencent.weknora.storage.*;
import com.tencent.weknora.task.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class KnowledgeUploadHttpCompatibilityTest {
  @TempDir Path temp;
  final ObjectMapper json = new ObjectMapper();
  DocumentProcessingCompatibilityTest database;
  final AuthContext caller =
      new AuthContext("human", 1, "admin", false, null, false, Set.of(), Set.of());

  void database(boolean vectors) throws Exception {
    database = new DocumentProcessingCompatibilityTest();
    database.temp = temp;
    database.database(501, vectors);
    database.jdbc.update("UPDATE knowledge_bases SET creator_id='human' WHERE id='kb'");
  }

  KnowledgeBaseWriteAuthorization authorization() throws Exception {
    return (KnowledgeBaseWriteAuthorization)
        KnowledgeBaseWriteAuthorization.class.getConstructors()[0].newInstance(
            database.jdbc, new KnowledgeBaseCodec(json), null);
  }

  MockMvc mvc(KnowledgeUploadController.Creator creator, MockEnvironment env) throws Exception {
    return MockMvcBuilders.standaloneSetup(
            new KnowledgeUploadController(
                authorization(),
                new KnowledgeUploadForms(json),
                creator,
                database.jdbc,
                env,
                new KnowledgeUploadAudit(database.jdbc, json)))
        .setControllerAdvice(new ApiErrorAdvice())
        .build();
  }

  byte[] multipart(String filename, byte[] content, Map<String, String> fields) throws Exception {
    var out = new ByteArrayOutputStream();
    for (var field : fields.entrySet()) {
      out.write(
          ("--boundary\r\nContent-Disposition: form-data; name=\""
                  + field.getKey()
                  + "\"\r\n\r\n"
                  + field.getValue()
                  + "\r\n")
              .getBytes(StandardCharsets.UTF_8));
    }
    out.write(
        ("--boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                + filename.replace("\\", "\\\\").replace("\"", "\\\"")
                + "\"\r\nContent-Type: application/octet-stream\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8));
    out.write(content);
    out.write("\r\n--boundary--\r\n".getBytes(StandardCharsets.UTF_8));
    return out.toByteArray();
  }

  @Test
  void actualGoMultipartBindingAndJsonValidationMatchesSeventySixHttpCases() throws Exception {
    database(false);
    ObjectNode[] captured = {null};
    int[] creates = {0};
    MockMvc mvc =
        mvc(
            (command, quota, used, observer) -> {
              creates[0]++;
              var node =
                  json.createObjectNode()
                      .put("kb", command.kb())
                      .put("tenant", command.tenant())
                      .put("filename", command.filename())
                      .put(
                          "content",
                          new String(
                              command.content().open().readAllBytes(), StandardCharsets.UTF_8))
                      .put("size", command.size())
                      .put("custom", command.customFilename())
                      .put("channel", command.channel());
              node.set("metadata", json.valueToTree(command.metadata()));
              node.set("multimodel", json.valueToTree(command.multimodel()));
              node.set("overrides", json.valueToTree(command.processConfig()));
              node.set(
                  "tags",
                  command.tags().isEmpty() ? NullNode.instance : json.valueToTree(command.tags()));
              captured[0] = node;
              return json.createObjectNode();
            },
            new MockEnvironment().withProperty("MAX_FILE_SIZE_MB", "1"));
    int count = 0;
    for (JsonNode fixture : json.readTree(getClass().getResourceAsStream("/go-upload-http.json"))) {
      count++;
      captured[0] = null;
      var input = fixture.path("input");
      byte[] bytes =
          input.has("content_base64")
              ? Base64.getDecoder().decode(input.path("content_base64").asText())
              : input.path("content").asText().getBytes(StandardCharsets.UTF_8);
      Map<String, String> fields = new LinkedHashMap<>();
      input
          .path("fields")
          .fields()
          .forEachRemaining(e -> fields.put(e.getKey(), e.getValue().asText()));
      var response =
          mvc.perform(
                  post("/api/v1/knowledge-bases/kb/knowledge/file")
                      .requestAttr(CurrentAuth.ATTRIBUTE, caller)
                      .contentType("multipart/form-data; boundary=boundary")
                      .content(multipart(input.path("name").asText(), bytes, fields)))
              .andReturn()
              .getResponse();
      assertThat(response.getStatus())
          .as("Go upload HTTP %s", count)
          .isEqualTo(fixture.path("status").asInt());
      if (response.getStatus() == 200)
        assertThat(json.readTree(json.writeValueAsBytes(captured[0])))
            .as("Go decoded command %s", count)
            .isEqualTo(fixture.get("captured"));
      else {
        assertThat(captured[0]).isNull();
        assertThat(json.readTree(response.getContentAsByteArray()))
            .as("Go upload error %s", count)
            .isEqualTo(fixture.get("response"));
      }
    }
    assertThat(count).isEqualTo(76);
    assertThat(creates[0]).isGreaterThan(20);
  }

  @Test
  void unauthorizedUploadCannotConsumeBodyAndMultipartLimitsPrecedeInvalidFields()
      throws Exception {
    database(false);
    var denied =
        new AuthContext("system-1", 1, "admin", false, 1L, false, Set.of("retrieve"), Set.of());
    MockMvc mvc =
        mvc(
            (c, q, u, o) -> {
              throw new AssertionError("must not create");
            },
            new MockEnvironment().withProperty("MAX_FILE_SIZE_MB", "1"));
    var request =
        post("/api/v1/knowledge-bases/kb/knowledge/file")
            .requestAttr(CurrentAuth.ATTRIBUTE, denied)
            .contentType("multipart/form-data; boundary=boundary")
            .content(new byte[3 << 20]);
    var response = mvc.perform(request).andReturn();
    assertThat(response.getResponse().getStatus()).isEqualTo(403);
    assertThat(response.getRequest().getInputStream().read()).isZero();
    response =
        mvc.perform(
                post("/api/v1/knowledge-bases/kb/knowledge/file")
                    .requestAttr(CurrentAuth.ATTRIBUTE, caller)
                    .contentType("multipart/form-data; boundary=boundary")
                    .content(
                        multipart("large.txt", new byte[(1 << 20) + 1], Map.of("metadata", "{"))))
            .andReturn();
    assertThat(response.getResponse().getStatus()).isEqualTo(400);
    assertThat(
            json.readTree(response.getResponse().getContentAsByteArray())
                .path("error")
                .path("message")
                .asText())
        .isEqualTo("文件大小不能超过1MB");
    for (String type : List.of("application/json", "multipart/form-data")) {
      response =
          mvc.perform(
                  post("/api/v1/knowledge-bases/kb/knowledge/file")
                      .requestAttr(CurrentAuth.ATTRIBUTE, caller)
                      .contentType(type)
                      .content("{}"))
              .andReturn();
      assertThat(response.getResponse().getStatus()).isEqualTo(400);
      assertThat(
              json.readTree(response.getResponse().getContentAsByteArray())
                  .path("error")
                  .path("details")
                  .asText())
          .isEqualTo(
              type.equals("application/json")
                  ? "request Content-Type isn't multipart/form-data"
                  : "no multipart boundary param in Content-Type");
    }
  }

  @Test
  void actualMultipartPersistsLocalResourceAndNativeWorkerCompletesBothDurableTasks()
      throws Exception {
    database(false);
    var env =
        new MockEnvironment()
            .withProperty("MAX_FILE_SIZE_MB", "1")
            .withProperty("LOCAL_STORAGE_BASE_DIR", temp.resolve("files").toString());
    var storage = new StorageResolver(database.jdbc, json, env);
    var producer = new KnowledgeFileTaskEnqueuer(database.tasks);
    var upload =
        new KnowledgeFileUpload(
            database.knowledge,
            json,
            (owner, kb) -> storage.forKnowledgeBase(owner, kb).files(),
            producer,
            Clock.systemUTC());
    var mvc = mvc(upload::create, env);
    var response =
        mvc.perform(
                post("/api/v1/knowledge-bases/kb/knowledge/file")
                    .requestAttr(CurrentAuth.ATTRIBUTE, caller)
                    .header("Accept-Language", "en-US;q=0.8,zh-CN")
                    .contentType("multipart/form-data; boundary=boundary")
                    .content(
                        multipart(
                            "document.txt",
                            "First paragraph\r\n\r\nSecond paragraph"
                                .getBytes(StandardCharsets.UTF_8),
                            Map.of(
                                "process_config",
                                "{\"summary_enabled\":false}",
                                "fileName",
                                "folder/document.txt"))))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).isEqualTo(200);
    String id = json.readTree(response.getContentAsByteArray()).path("data").path("id").asText();
    assertThat(id).isNotEmpty();
    var row = database.knowledge.get(1, id).orElseThrow();
    assertThat(row.path("folder_path").asText()).isEqualTo("folder");
    assertThat(database.jdbc.queryForObject("SELECT COUNT(*) FROM resources", Integer.class))
        .isEqualTo(1);
    assertThat(
            database.jdbc.queryForObject("SELECT COUNT(*) FROM resource_bindings", Integer.class))
        .isEqualTo(1);
    assertThat(database.tasks.list(1, 10).getFirst().payload().path("language").asText())
        .isEqualTo("en-US");
    var handler =
        database.handler(
            (owner, kb, path) -> storage.forKnowledgeBase(owner, kb).files().open(path),
            (owner, model) -> {
              throw new AssertionError("graph-only KB does not embed");
            },
            database.index);
    try (var worker =
        new TaskWorker(
            database.tasks,
            List.of(handler, new KnowledgePostProcessHandler(database.processing, json)),
            1,
            Duration.ofSeconds(5),
            Map.of("default", 1, "postprocess", 1))) {
      long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
      while (System.nanoTime() < end) {
        worker.poll();
        if (database.tasks.list(1, 10).size() == 2
            && database.tasks.list(1, 10).stream()
                .allMatch(task -> task.status().equals("succeeded"))) break;
        Thread.sleep(20);
      }
      assertThat(database.tasks.list(1, 10))
          .hasSize(2)
          .allMatch(task -> task.status().equals("succeeded"));
    }
    assertThat(database.knowledge.get(1, id).orElseThrow().path("parse_status").asText())
        .isEqualTo("completed");
    assertThat(
            database.jdbc.queryForObject(
                "SELECT COUNT(*) FROM chunks WHERE knowledge_id=?", Integer.class, id))
        .isGreaterThan(0);
    assertThat(
            database.jdbc.queryForObject(
                "SELECT tenant_id FROM audit_logs WHERE action='knowledge.created'", Long.class))
        .isEqualTo(1L);
    assertThat(
            database.jdbc.queryForObject(
                "SELECT actor_user_id FROM audit_logs WHERE action='knowledge.created'",
                String.class))
        .isEqualTo("human");
  }

  @Test
  void sharedUploadWritesOnlyOwnerTenantAndRetainsForeignCallerAuditIdentity() throws Exception {
    database(false);
    database.jdbc.update(
        "INSERT INTO organizations(id,name,owner_tenant_id,owner_id)"
            + " VALUES('org','Org',1,'human')");
    database.jdbc.update(
        "INSERT INTO organization_tenant_members(id,organization_id,tenant_id,role)"
            + " VALUES('owner','org',1,'admin'),('caller','org',2,'editor')");
    database.jdbc.update(
        "INSERT INTO"
            + " kb_shares(id,knowledge_base_id,organization_id,source_tenant_id,permission,shared_by_user_id)"
            + " VALUES('share','kb','org',1,'editor','human')");
    var foreign =
        new AuthContext("foreign-human", 2, "contributor", false, null, false, Set.of(), Set.of());
    var env =
        new MockEnvironment()
            .withProperty("LOCAL_STORAGE_BASE_DIR", temp.resolve("shared-files").toString());
    var storage = new StorageResolver(database.jdbc, json, env);
    var upload =
        new KnowledgeFileUpload(
            database.knowledge,
            json,
            (o, k) -> storage.forKnowledgeBase(o, k).files(),
            new KnowledgeFileTaskEnqueuer(database.tasks),
            Clock.systemUTC());
    var mvc = mvc(upload::create, env);
    var response =
        mvc.perform(
                post("/api/v1/knowledge-bases/kb/knowledge/file")
                    .requestAttr(CurrentAuth.ATTRIBUTE, foreign)
                    .contentType("multipart/form-data; boundary=boundary")
                    .content(
                        multipart(
                            "shared.txt",
                            "shared body".getBytes(StandardCharsets.UTF_8),
                            Map.of(
                                "metadata",
                                "{\"tenant_id\":\"2\"}",
                                "process_config",
                                "{\"summary_enabled\":false}"))))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).isEqualTo(200);
    String id = json.readTree(response.getContentAsByteArray()).path("data").path("id").asText();
    assertThat(database.knowledge.get(1, id)).isPresent();
    assertThat(database.knowledge.get(2, id)).isEmpty();
    assertThat(database.tasks.list(2, 10)).isEmpty();
    assertThat(database.tasks.list(1, 10)).hasSize(1);
    assertThat(database.tasks.list(1, 10).getFirst().payload().path("tenant_id").asLong())
        .isEqualTo(1);
    assertThat(database.jdbc.queryForObject("SELECT tenant_id FROM resources", Long.class))
        .isEqualTo(1L);
    assertThat(
            database.jdbc.queryForObject(
                "SELECT tenant_id FROM audit_logs WHERE action='knowledge.created'", Long.class))
        .isEqualTo(1L);
    assertThat(
            database.jdbc.queryForObject(
                "SELECT actor_user_id FROM audit_logs WHERE action='knowledge.created'",
                String.class))
        .isEqualTo("foreign-human");
    assertThat(
            database.jdbc.queryForObject(
                "SELECT actor_role FROM audit_logs WHERE action='knowledge.created'", String.class))
        .isEqualTo("contributor");
  }
}
