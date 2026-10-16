package com.tencent.weknora.knowledge;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Actual production Spring JWT/key/shared filters, model HTTP and physical vec/FTS. */
@org.springframework.boot.test.context.SpringBootTest(
    properties = {
      "weknora.jwt-secret=isolated-native-pipeline-trace-secret",
      "weknora.bridge.enabled=false",
      "weknora.knowledge.processing.enabled=true",
      "weknora.knowledge.upload.enabled=true",
      "weknora.tasks.enabled=true",
      "weknora.tasks.poll-millis=3600000",
      "weknora.retrieve-driver=sqlite",
      "SSRF_WHITELIST=127.0.0.1",
      "SSRF_DNS_WHITELIST_ONLY=true",
      "NEO4J_ENABLE=false",
      "logging.level.root=WARN",
      "debug=false"
    })
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class KnowledgeImagePipelineHttpIntegrationTest {
  @org.springframework.test.context.DynamicPropertySource
  static void properties(org.springframework.test.context.DynamicPropertyRegistry p) {
    IndexDeletePipelineHttpIntegrationTest.properties(p);
  }

  @Autowired AutowireCapableBeanFactory beans;
  final KnowledgeUpdatePipelineHttpIntegrationTest f =
      new KnowledgeUpdatePipelineHttpIntegrationTest();
  final ObjectMapper json = new ObjectMapper();

  @BeforeEach
  void setup() throws Exception {
    beans.autowireBean(f);
    f.setup();
  }

  @AfterEach
  void stop() {
    f.s.f.jdbc.execute("DROP TRIGGER IF EXISTS reject_image_index");
    f.s.f.jdbc.execute("DROP TRIGGER IF EXISTS reject_image_parent");
    f.stop();
  }

  String parent(String doc) {
    return f.s.f.jdbc.queryForObject(
        "SELECT id FROM chunks WHERE knowledge_id=? AND chunk_type='text' ORDER BY chunk_index"
            + " LIMIT 1",
        String.class,
        doc);
  }

  String image(String caption, String ocr) {
    return json.createArrayNode()
        .add(
            json.createObjectNode()
                .put("url", "stored-image")
                .put("original_url", "original-image")
                .put("caption", caption)
                .put("ocr_text", ocr))
        .toString();
  }

  String body(String image) {
    return json.createObjectNode().put("image_info", image).toString();
  }

  JsonNode update(
      String doc, String chunk, String credential, boolean machine, int status, String body)
      throws Exception {
    var request =
        MockMvcRequestBuilders.put("/api/v1/knowledge/image/" + doc + "/" + chunk)
            .contentType("application/json")
            .content(body)
            .header(
                machine ? "X-API-Key" : "Authorization",
                machine ? credential : "Bearer " + credential);
    var response = f.s.f.mvc.perform(request).andReturn().getResponse();
    assertEquals(status, response.getStatus(), response.getContentAsString());
    return json.readTree(response.getContentAsByteArray());
  }

  List<Object> retained(String doc) throws Exception {
    return List.of(
        f.retained(doc).subList(0, 4),
        f.s.state(doc, "summary_status"),
        f.s.f.jdbc.queryForObject("SELECT COUNT(*) FROM java_task_jobs", Long.class),
        f.s.f.jdbc.queryForObject("SELECT COUNT(*) FROM chunk_revisions", Long.class));
  }

  void physical(String doc) throws Exception {
    var index = f.indexes(doc);
    assertTrue(index.path("rows").size() >= 4);
    assertEquals(index.path("rows").size(), index.path("vectors").size());
    assertEquals(index.path("rows").size(), index.path("fts").size());
  }

  @Test
  void sharedImageUpdateAddsAndReusesChildrenWithoutTasksRevisionsQuotaOrSummary()
      throws Exception {
    String doc = f.prepared(),
        parent = parent(doc),
        raw = image("Caption evidence 中文", "OCR evidence 中文");
    var retained = retained(doc);
    int calls = f.s.embeddings.get(), chats = f.s.chats.get();
    String hash = f.s.state(doc, "file_hash");
    f.s.f.jdbc.update("UPDATE tenant_members SET role='contributor' WHERE user_id='caller'");
    update(doc, parent, f.s.f.callerToken, false, 200, body(raw));
    assertEquals(calls + 1, f.s.embeddings.get());
    assertEquals(chats, f.s.chats.get());
    assertEquals(retained, retained(doc));
    assertEquals(
        java.util.HexFormat.of()
            .formatHex(
                java.security.MessageDigest.getInstance("MD5")
                    .digest((doc + hash + raw).getBytes(java.nio.charset.StandardCharsets.UTF_8))),
        f.s.state(doc, "file_hash"));
    var children =
        f.s.f.jdbc.queryForList(
            "SELECT id,chunk_type,is_enabled,content_revision,index_status,last_editor_id FROM"
                + " chunks WHERE parent_chunk_id=? AND chunk_type IN ('image_caption','image_ocr')"
                + " ORDER BY chunk_type",
            parent);
    assertEquals(2, children.size());
    for (var row : children) {
      assertEquals(1, ((Number) row.get("is_enabled")).intValue());
      assertEquals(0, ((Number) row.get("content_revision")).intValue());
      assertEquals("ready", row.get("index_status"));
      assertEquals("", row.get("last_editor_id"));
    }
    physical(doc);
    for (var row : children)
      assertTrue(
          f.s
              .f
              .jdbc
              .queryForObject(
                  "SELECT content FROM lite_embeddings WHERE chunk_id=?",
                  String.class,
                  row.get("id"))
              .contains(
                  row.get("chunk_type").equals("image_ocr")
                      ? "OCR evidence 中文"
                      : "Caption evidence 中文"));
    update(
        doc,
        parent,
        f.s.f.callerToken,
        false,
        200,
        body(image("Replacement caption", "Replacement OCR")));
    assertEquals(
        children,
        f.s.f.jdbc.queryForList(
            "SELECT id,chunk_type,is_enabled,content_revision,index_status,last_editor_id FROM"
                + " chunks WHERE parent_chunk_id=? AND chunk_type IN ('image_caption','image_ocr')"
                + " ORDER BY chunk_type",
            parent));
    assertEquals(retained, retained(doc));
    physical(doc);
  }

  @Test
  void creatorViewerAndMachineIngestWorkWhileScopeAndRevocationPrecedeBinding() throws Exception {
    String doc = f.prepared(), parent = parent(doc);
    f.s.f.jdbc.update(
        "UPDATE tenant_members SET role='viewer' WHERE user_id='owner' AND tenant_id=8");
    update(
        doc,
        parent,
        f.s.f.token("owner", 8),
        false,
        200,
        body(image("Viewer creator caption", "Viewer creator OCR")));
    String ingest = f.key("ingest", false, "trace-kb"),
        retrieve = f.key("retrieve", false, "trace-kb"),
        wrong = f.key("ingest", true, "other-kb");
    update(doc, parent, retrieve, true, 403, "invalid");
    update(doc, parent, wrong, true, 403, "invalid");
    update(doc, parent, ingest, true, 400, "invalid");
    update(doc, parent, ingest, true, 200, body(image("Machine caption", "Machine OCR")));
    physical(doc);
    f.s.f.jdbc.update("UPDATE kb_shares SET deleted_at=CURRENT_TIMESTAMP WHERE id='trace-share'");
    update(doc, parent, f.s.f.callerToken, false, 403, "invalid");
    update(doc, parent, f.s.f.foreignToken, false, 403, "invalid");
  }

  @Test
  void PhysicalIndexFailureKeepsSavedChildrenAndParentButNotNewHash() throws Exception {
    String doc = f.prepared(),
        parent = parent(doc),
        raw = image("Committed caption", "Committed OCR"),
        hash = f.s.state(doc, "file_hash");
    var retained = retained(doc);
    f.s.f.jdbc.execute(
        "CREATE TRIGGER reject_image_index BEFORE INSERT ON lite_embeddings BEGIN SELECT"
            + " RAISE(ABORT,'controlled image index failure'); END");
    var response = update(doc, parent, f.s.f.callerToken, false, 500, body(raw));
    assertEquals("controlled image index failure", response.path("error").path("message").asText());
    assertEquals(
        raw,
        f.s.f.jdbc.queryForObject(
            "SELECT image_info FROM chunks WHERE id=?", String.class, parent));
    assertEquals(
        2,
        f.s.f.jdbc.queryForObject(
            "SELECT COUNT(*) FROM chunks WHERE parent_chunk_id=? AND chunk_type IN"
                + " ('image_caption','image_ocr')",
            Integer.class,
            parent));
    assertEquals(hash, f.s.state(doc, "file_hash"));
    assertEquals(retained, retained(doc));
    assertEquals(
        0,
        f.s.f.jdbc.queryForObject(
            "SELECT COUNT(*) FROM lite_embeddings WHERE chunk_id=?", Integer.class, parent));
    f.s.f.jdbc.execute("DROP TRIGGER reject_image_index");
    update(doc, parent, f.s.f.callerToken, false, 200, body(raw));
    assertEquals(
        1,
        f.s.f.jdbc.queryForObject(
            "SELECT COUNT(*) FROM lite_embeddings WHERE chunk_id=?", Integer.class, parent));
    // Go's retry reindexes the parent only when the child image fields already match.
    assertEquals(
        0,
        f.s.f.jdbc.queryForObject(
            "SELECT COUNT(*) FROM lite_embeddings e JOIN chunks c ON c.id=e.chunk_id WHERE"
                + " c.parent_chunk_id=? AND c.chunk_type IN ('image_caption','image_ocr')",
            Integer.class,
            parent));
  }

  @Test
  void parentSaveFailureRetainsPreviouslyInsertedChildren() throws Exception {
    String doc = f.prepared(), parent = parent(doc), hash = f.s.state(doc, "file_hash");
    var retained = retained(doc);
    int calls = f.s.embeddings.get();
    f.s.f.jdbc.execute(
        "CREATE TRIGGER reject_image_parent BEFORE UPDATE ON chunks WHEN OLD.id='"
            + parent
            + "' BEGIN SELECT RAISE(ABORT,'controlled image parent failure'); END");
    update(
        doc, parent, f.s.f.callerToken, false, 500, body(image("Created caption", "Created OCR")));
    assertEquals(
        2,
        f.s.f.jdbc.queryForObject(
            "SELECT COUNT(*) FROM chunks WHERE parent_chunk_id=? AND chunk_type IN"
                + " ('image_caption','image_ocr')",
            Integer.class,
            parent));
    assertEquals(hash, f.s.state(doc, "file_hash"));
    assertEquals(calls, f.s.embeddings.get());
    assertEquals(retained, retained(doc));
  }
}
