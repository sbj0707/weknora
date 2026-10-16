package com.tencent.weknora.security;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

@org.springframework.boot.test.context.SpringBootTest(
    properties = {
      "weknora.jwt-secret=isolated-kb-read-http-secret",
      "weknora.bridge.enabled=false",
      "logging.level.root=WARN",
      "debug=false",
      "WEKNORA_TENANT_ENABLE_CROSS_TENANT_ACCESS=true"
    })
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KnowledgeContentReadHttpIntegrationTest {
  private static final Path DATABASE;

  static {
    try {
      DATABASE = Files.createTempFile("weknora-read-http-", ".db");
      DATABASE.toFile().deleteOnExit();
    } catch (Exception ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  @org.springframework.test.context.DynamicPropertySource
  static void properties(org.springframework.test.context.DynamicPropertyRegistry p) {
    p.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE);
    p.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    p.add("spring.datasource.hikari.maximum-pool-size", () -> 2);
  }

  @org.springframework.beans.factory.annotation.Autowired
  org.springframework.test.web.servlet.MockMvc mvc;

  @org.springframework.beans.factory.annotation.Autowired JdbcTemplate database;
  @org.springframework.beans.factory.annotation.Autowired javax.sql.DataSource datasource;
  @org.springframework.beans.factory.annotation.Autowired JwtCodec jwt;

  private void resetSchema() throws Exception {
    for (String table :
        database.queryForList(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'",
            String.class)) database.execute("DROP TABLE \"" + table.replace("\"", "\"\"") + "\"");
    try (var c = datasource.getConnection()) {
      for (String script :
          List.of(
              "000000_init.up.sql",
              "000007_system_admin_and_settings.up.sql",
              "000003_knowledge_base_auto_tag_config.up.sql",
              "000021_knowledge_profiles.up.sql",
              "000002_knowledge_folder_path.up.sql",
              "000008_processing_spans_and_pending_subtasks.up.sql",
              "000010_knowledge_multi_tags.up.sql",
              "000033_chunk_source_locators.up.sql"))
        ScriptUtils.executeSqlScript(c, new FileSystemResource("../migrations/sqlite/" + script));
    }
  }

  @Test
  void reservedStaticKnowledgeRoutesAreNotCapturedAsDocumentIds() throws Exception {
    var json = new ObjectMapper();
    var fixture =
        json.readTree(new java.util.zip.GZIPInputStream(getClass().getResourceAsStream("/go-knowledge-content-read.json.gz")));
    run("knowledge-detail/viewer-creator", fixture.path("knowledge-detail/viewer-creator"), json);
    String token =
        database.queryForObject(
            "SELECT token FROM auth_tokens WHERE user_id='caller' AND token_type='access_token'",
            String.class);
    for (String id : List.of("batch", "search")) {
      database.update(
          "INSERT INTO knowledges(id,tenant_id,knowledge_base_id,type,title,source)"
              + " VALUES(?,7,'kb','file','Reserved','test')",
          id);
      mvc.perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                      "/api/v1/knowledge/" + id)
                  .header("Authorization", "Bearer " + token))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                  .isBadRequest())
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                  .string(
                      org.hamcrest.Matchers.containsString(
                          id.equals("batch")
                              ? "Invalid request parameters"
                              : "missing search keyword")));
    }
  }

  @TestFactory
  Stream<DynamicTest> actualGoKBDetailHttpProfiles() throws Exception {
    var json = new ObjectMapper();
    var fixture =
        json.readTree(new java.util.zip.GZIPInputStream(getClass().getResourceAsStream("/go-knowledge-content-read.json.gz")));
    List<DynamicTest> out = new ArrayList<>();
    fixture
        .fields()
        .forEachRemaining(
            entry ->
                out.add(
                    DynamicTest.dynamicTest(
                        entry.getKey(), () -> run(entry.getKey(), entry.getValue(), json))));
    return out.stream();
  }

  private void run(String key, JsonNode expected, ObjectMapper json) throws Exception {
    String endpoint = key.substring(0, key.indexOf("/")),
        mode = key.substring(key.indexOf("/") + 1);
    resetSchema();
    var jdbc = database;
    String role = "viewer", creator = "caller";
    long owner = 7;
    boolean enabled = true, cross = false, system = false, machine = mode.startsWith("api-key");
    boolean full = mode.equals("api-key-full");
    Set<String> caps = Set.of("retrieve"), ids = Set.of();
    String method = "GET",
        template = "/api/v1/knowledge-bases/:id",
        raw = "/api/v1/knowledge-bases/kb";
    switch (mode) {
      case "viewer-other", "system-admin-viewer", "rbac-off-own" -> creator = "other";
      case "contributor-other" -> {
        role = "contributor";
        creator = "other";
      }
      case "contributor-creator" -> role = "contributor";
      case "admin-other", "missing-admin", "lookup-failure-admin" -> {
        role = "admin";
        creator = "other";
      }
      case "owner-blank" -> {
        role = "owner";
        creator = "";
      }
      case "viewer-blank" -> creator = "";
    }
    system = mode.equals("system-admin-viewer");
    cross = mode.equals("cross-superuser-own") || mode.equals("cross-superuser-foreign");
    enabled = !Set.of("rbac-off-own", "rbac-off-foreign", "rbac-off-tenant-viewer").contains(mode);
    if (machine) creator = "other";
    if (mode.equals("api-key-ingest")) caps = Set.of("ingest");
    if (mode.equals("api-key-scope")) ids = Set.of("other");
    if (mode.equals("api-key-update")) {
      caps = Set.of("manage_kbs");
      method = "PUT";
      template = "/api/v1/knowledge-bases/:id";
      raw = "/api/v1/knowledge-bases/kb";
    }
    boolean shared = false;
    if (Set.of("foreign-no-share", "cross-superuser-foreign", "rbac-off-foreign").contains(mode)) {
      owner = 8;
      role = "contributor";
    } else if (mode.startsWith("share-")
        || Set.of(
                "member-viewer",
                "tenant-viewer",
                "rbac-off-tenant-viewer",
                "source-gone",
                "target-gone",
                "org-deleted",
                "multiple-shares",
                "empty-share-permission",
                "empty-member-role",
                "api-key-shared-editor")
            .contains(mode)) {
      owner = 8;
      shared = true;
      role = "contributor";
    }
    if (mode.equals("tenant-viewer") || mode.equals("rbac-off-tenant-viewer")) role = "viewer";
    if (Set.of("org-invalid-source-ignored", "shared-unbound", "shared-faq", "shared-count-failure")
        .contains(mode)) {
      owner = 8;
      shared = true;
      role = "viewer";
    }
    if (shared) {
      String permission =
          mode.equals("share-admin")
              ? "admin"
              : mode.equals("share-viewer")
                  ? "viewer"
                  : mode.equals("empty-share-permission") ? "" : "editor";
      String member =
          mode.equals("share-admin")
              ? "admin"
              : mode.equals("member-viewer")
                  ? "viewer"
                  : mode.equals("empty-member-role") ? "" : "editor";
      long sourceTenant = mode.equals("share-foreign-source-member") ? 9 : 8;
      jdbc.update(
          "INSERT INTO organizations(id,name,owner_tenant_id,owner_id)"
              + " VALUES('org','Org',8,'unused')");
      jdbc.update(
          "INSERT INTO organization_tenant_members(id,organization_id,tenant_id,role)"
              + " VALUES('source','org',?,'admin'),('target','org',7,?)",
          sourceTenant,
          member);
      jdbc.update(
          "INSERT INTO"
              + " kb_shares(id,knowledge_base_id,organization_id,source_tenant_id,permission,shared_by_user_id)"
              + " VALUES('share','kb','org',?,?,'unused')",
          sourceTenant,
          permission);
      if (mode.equals("source-gone"))
        jdbc.update("DELETE FROM organization_tenant_members WHERE id='source'");
      if (mode.equals("target-gone"))
        jdbc.update("DELETE FROM organization_tenant_members WHERE id='target'");
      if (mode.equals("org-deleted"))
        jdbc.update("UPDATE organizations SET deleted_at=CURRENT_TIMESTAMP WHERE id='org'");
      if (mode.equals("share-deleted"))
        jdbc.update("UPDATE kb_shares SET deleted_at=CURRENT_TIMESTAMP WHERE id='share'");
      if (mode.equals("multiple-shares")) {
        jdbc.update("UPDATE kb_shares SET permission='viewer'");
        jdbc.update(
            "INSERT INTO organizations(id,name,owner_tenant_id,owner_id)"
                + " VALUES('org2','Second',8,'unused')");
        jdbc.update(
            "INSERT INTO organization_tenant_members(id,organization_id,tenant_id,role)"
                + " VALUES('source2','org2',8,'viewer'),('target2','org2',7,'admin')");
        jdbc.update(
            "INSERT INTO"
                + " kb_shares(id,knowledge_base_id,organization_id,source_tenant_id,permission,shared_by_user_id)"
                + " VALUES('share2','kb','org2',8,'editor','unused')");
      }
      if (mode.equals("share-sql-failure") || mode.equals("share-sql-failure-agent-fallback"))
        jdbc.execute("DROP TABLE kb_shares");
    }
    String agentId = "", sourceSelector = "";
    boolean hasAgent = mode.startsWith("agent-") || mode.equals("share-sql-failure-agent-fallback");
    if (hasAgent) {
      owner = 8;
      role = "viewer";
      if (!shared) {
        jdbc.update(
            "INSERT INTO organizations(id,name,owner_tenant_id,owner_id)"
                + " VALUES('org','Org',8,'unused')");
        jdbc.update(
            "INSERT INTO organization_tenant_members(id,organization_id,tenant_id,role)"
                + " VALUES('source','org',8,'viewer'),('target','org',7,'viewer')");
      }
      String selection = "all";
      List<String> selected = List.of("kb");
      if (mode.equals("agent-selected-kb") || mode.equals("agent-selected-other"))
        selection = "selected";
      if (mode.equals("agent-selected-other")) selected = List.of("other");
      if (mode.equals("agent-none")) selection = "none";
      if (mode.equals("agent-unknown-mode")) selection = "unknown";
      long agentTenant = 8;
      if (mode.equals("agent-wrong-owner")) {
        agentTenant = 9;
        jdbc.update(
            "INSERT INTO organization_tenant_members(id,organization_id,tenant_id,role)"
                + " VALUES('source9','org',9,'viewer')");
      }
      String agentKey = mode.equals("agent-builtin") ? "builtin-quick-answer" : "agent";
      jdbc.update(
          "INSERT INTO custom_agents(id,tenant_id,name,is_builtin,config) VALUES(?,?,'Agent',?,?)",
          agentKey,
          agentTenant,
          mode.equals("agent-is-builtin"),
          json.writeValueAsString(
              Map.of("kb_selection_mode", selection, "knowledge_bases", selected)));
      jdbc.update(
          "INSERT INTO"
              + " agent_shares(id,agent_id,organization_id,source_tenant_id,permission,shared_by_user_id)"
              + " VALUES('share-agent',?,'org',?,'viewer','unused')",
          agentKey,
          agentTenant);
      if (!Set.of(
              "agent-implicit-all",
              "agent-implicit-multiple",
              "agent-disabled",
              "share-sql-failure-agent-fallback")
          .contains(mode)) agentId = agentKey;
      if (mode.equals("agent-source-gone"))
        jdbc.update("DELETE FROM organization_tenant_members WHERE id='source'");
      if (mode.equals("agent-target-gone"))
        jdbc.update("DELETE FROM organization_tenant_members WHERE id='target'");
      if (mode.equals("agent-org-deleted"))
        jdbc.update("UPDATE organizations SET deleted_at=CURRENT_TIMESTAMP WHERE id='org'");
      if (mode.equals("agent-share-deleted"))
        jdbc.update("UPDATE agent_shares SET deleted_at=CURRENT_TIMESTAMP WHERE id='share-agent'");
      if (mode.equals("agent-deleted"))
        jdbc.update("UPDATE custom_agents SET deleted_at=CURRENT_TIMESTAMP WHERE id='agent'");
      if (mode.equals("agent-disabled"))
        jdbc.update(
            "INSERT INTO tenant_disabled_shared_agents(tenant_id,agent_id,source_tenant_id)"
                + " VALUES(7,'agent',8)");
      if (mode.equals("agent-sql-failure")) jdbc.execute("DROP TABLE agent_shares");
      if (Set.of(
              "agent-ambiguous-source",
              "agent-explicit-correct-source",
              "agent-explicit-wrong-source",
              "agent-implicit-multiple")
          .contains(mode)) {
        jdbc.update(
            "INSERT INTO organization_tenant_members(id,organization_id,tenant_id,role)"
                + " VALUES('source9','org',9,'viewer')");
        jdbc.update(
            "INSERT INTO custom_agents(id,tenant_id,name,config) VALUES('agent',9,'Wrong"
                + " owner','{\"kb_selection_mode\":\"all\"}')");
        jdbc.update(
            "INSERT INTO"
                + " agent_shares(id,agent_id,organization_id,source_tenant_id,permission,shared_by_user_id)"
                + " VALUES('first-lexicographic','agent','org',9,'viewer','unused')");
      }
      if (mode.equals("agent-config-invalid-json"))
        jdbc.update("UPDATE custom_agents SET config='{' WHERE id='agent'");
      if (mode.equals("agent-config-wrong-type"))
        jdbc.update(
            "UPDATE custom_agents SET config=? WHERE id='agent'", "{\"kb_selection_mode\":1}");
      if (mode.equals("agent-config-object-list"))
        jdbc.update(
            "UPDATE custom_agents SET config=? WHERE id='agent'",
            "{\"kb_selection_mode\":\"selected\",\"knowledge_bases\":{\"x\":\"kb\"}}");
      if (mode.equals("agent-config-invalid-list-even-all"))
        jdbc.update(
            "UPDATE custom_agents SET config=? WHERE id='agent'",
            "{\"kb_selection_mode\":\"all\",\"knowledge_bases\":{\"x\":\"kb\"}}");
      if (mode.equals("agent-config-null"))
        jdbc.update("UPDATE custom_agents SET config='null' WHERE id='agent'");
      if (mode.equals("agent-other-malformed-any")) {
        agentId = "";
        jdbc.update(
            "INSERT INTO custom_agents(id,tenant_id,name,config) VALUES('bad',8,'Bad','{')");
        jdbc.update(
            "INSERT INTO"
                + " agent_shares(id,agent_id,organization_id,source_tenant_id,permission,shared_by_user_id)"
                + " VALUES('share-bad','bad','org',8,'viewer','unused')");
      }
      if (mode.equals("agent-explicit-missing-no-fallback")) agentId = "missing";
      switch (mode) {
        case "agent-invalid-source" -> sourceSelector = "nope";
        case "agent-negative-source" -> sourceSelector = "-8";
        case "agent-overflow-source" -> sourceSelector = "18446744073709551616";
        case "agent-own-source" -> sourceSelector = "7";
        case "agent-explicit-correct-source" -> sourceSelector = "8";
        case "agent-explicit-wrong-source" -> sourceSelector = "9";
      }
    }
    if (mode.equals("own-invalid-source-ignored") || mode.equals("org-invalid-source-ignored")) {
      agentId = "missing";
      sourceSelector = "invalid";
    }
    if (!mode.startsWith("missing-"))
      jdbc.update(
          "INSERT INTO"
              + " knowledge_bases(id,name,tenant_id,creator_id,type,embedding_model_id,summary_model_id,vector_store_id,cos_config,vlm_config)"
              + " VALUES('kb','KB',?,?"
              + " ,?,'','',?,'{\"secret_id\":\"owner-key-id\",\"secret_key\":\"owner-secret\"}','{\"api_key\":\"owner-vlm-key\"}')",
          owner,
          creator,
          Set.of("own-faq", "shared-faq", "faq-count-failure").contains(mode) ? "faq" : "document",
          Set.of("own-unbound", "shared-unbound").contains(mode)
              ? null
              : "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    jdbc.update(
        "INSERT INTO vector_stores(id,name,engine_type,tenant_id)"
            + " VALUES('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa','Owner private storage','qdrant',?)",
        owner);
    for (var entry :
        Map.of(
                "done",
                "completed",
                "pending",
                "pending",
                "processing",
                "processing",
                "deleting",
                "deleting",
                "soft-deleted",
                "pending")
            .entrySet()) {
      jdbc.update(
          "INSERT INTO knowledges(id,tenant_id,knowledge_base_id,type,title,source,parse_status)"
              + " VALUES(?,?,'kb','file','Doc','test',?)",
          entry.getKey(),
          owner,
          entry.getValue());
      if (entry.getKey().equals("soft-deleted"))
        jdbc.update("UPDATE knowledges SET deleted_at=CURRENT_TIMESTAMP WHERE id='soft-deleted'");
    }
    long wrongTenant = owner == 7 ? 8 : 7;
    jdbc.update(
        "INSERT INTO knowledges(id,tenant_id,knowledge_base_id,type,title,source,parse_status)"
            + " VALUES('foreign-doc',?,'kb','file','Foreign','test','pending')",
        wrongTenant);
    jdbc.update(
        "INSERT INTO"
            + " chunks(id,tenant_id,knowledge_base_id,knowledge_id,content,chunk_index,start_at,end_at)"
            + " VALUES('chunk',?,'kb','done','Owner"
            + " chunk',0,0,11),('foreign-chunk',?,'kb','foreign-doc','Foreign chunk',0,0,13)",
        owner,
        wrongTenant);
    if (mode.startsWith("lookup-failure-")) jdbc.execute("DROP TABLE knowledge_bases");

    jdbc.update(
        "UPDATE knowledges SET"
            + " enable_status='',file_size=0,storage_size=0,embedding_model_id='',error_message='',description=''");
    String[][] docRows = {
      {"done", "alpha.pdf", "Alpha 100%", "file", "pdf", "web", "docs"},
      {"pending", "BETA.md", "Beta_under", "file", "md", "api", "docs/spec"},
      {"processing", "", "Manual", "manual", "md", "web", ""},
      {"deleting", "gone.pdf", "Deleting", "file", "pdf", "web", "docs"},
      {"soft-deleted", "deleted.txt", "Deleted", "file", "txt", "web", ""},
      {"foreign-doc", "foreign.txt", "Foreign", "file", "txt", "web", ""}
    };
    for (int i = 0; i < docRows.length; i++) {
      var row = docRows[i];
      jdbc.update(
          "UPDATE knowledges SET"
              + " title=?,file_name=?,type=?,file_type=?,channel=?,folder_path=?,source=?,description=?,created_at=?,updated_at=?,custom_metadata=?"
              + " WHERE id=?",
          row[2],
          row[1],
          row[3],
          row[4],
          row[5],
          row[6],
          "source/" + row[0],
          "Description " + row[0],
          java.time.LocalDate.of(2026, 10, 1).plusDays(i) + " 00:00:00+00:00",
          java.time.LocalDate.of(2026, 10, 1).plusDays(5 - i) + " 00:00:00+00:00",
          "{\"public\":\"value\"}",
          row[0]);
    }
    jdbc.update(
        "UPDATE chunks SET source_content='PRIVATE source',context_header='PRIVATE"
            + " header',metadata='{\"x\":1}',last_editor_id='editor',start_at=0,end_at=0,created_at='2026-10-01"
            + " 00:00:00+00:00',updated_at='2026-10-01 00:00:00+00:00' WHERE id='chunk'");
    String[][] moreChunks = {
      {"chunk-next", "text", "1", "2"},
      {"chunk-disabled", "text", "2", "0"},
      {"chunk-image", "image_caption", "3", "0"},
      {"chunk-pending", "text", "4", "1"},
      {"chunk-deleted", "text", "5", "0"}
    };
    for (var row : moreChunks) {
      jdbc.update(
          "INSERT INTO"
              + " chunks(id,tenant_id,knowledge_base_id,knowledge_id,content,chunk_type,chunk_index,status,is_enabled,start_at,end_at,created_at,updated_at)"
              + " VALUES(?,?,'kb','done',?,?,?,?,?,0,0,'2026-10-01 00:00:00+00:00','2026-10-01"
              + " 00:00:00+00:00')",
          row[0],
          owner,
          row[0],
          row[1],
          Integer.parseInt(row[2]),
          Integer.parseInt(row[3]),
          !row[0].equals("chunk-disabled"));
      if (row[0].equals("chunk-deleted"))
        jdbc.update("UPDATE chunks SET deleted_at=CURRENT_TIMESTAMP WHERE id=?", row[0]);
    }
    jdbc.update(
        "UPDATE chunks SET knowledge_id='done',created_at='2026-10-01"
            + " 00:00:00+00:00',updated_at='2026-10-01 00:00:00+00:00' WHERE id='foreign-chunk'");
    jdbc.update(
        "INSERT INTO"
            + " knowledge_tags(id,seq_id,tenant_id,knowledge_base_id,name,color,sort_order,created_at,updated_at)"
            + " VALUES('tag-a',1,?,'kb','A','',0,'2026-10-01 00:00:00+00:00','2026-10-01"
            + " 00:00:00+00:00'),('tag-b',2,?,'kb','B','',0,'2026-10-01 00:00:00+00:00','2026-10-01"
            + " 00:00:00+00:00')",
        owner,
        owner);
    jdbc.update(
        "INSERT INTO knowledge_tag_relations(knowledge_id,tag_id,created_at)"
            + " VALUES('done','tag-a','2026-10-01 00:00:00+00:00'),('pending','tag-b','2026-10-01"
            + " 00:00:00+00:00')");
    if (mode.equals("tag-foreign"))
      jdbc.update("UPDATE knowledge_tags SET tenant_id=? WHERE id='tag-a'", wrongTenant);
    if (mode.equals("tag-table-failure")) jdbc.execute("DROP TABLE knowledge_tags");
    if (mode.equals("knowledge-owner-mismatch"))
      jdbc.update("UPDATE knowledges SET tenant_id=? WHERE id='done'", wrongTenant);
    if (mode.equals("chunk-owner-mismatch"))
      jdbc.update("UPDATE chunks SET tenant_id=? WHERE id='chunk'", wrongTenant);
    if (mode.equals("chunk-empty-kb"))
      jdbc.update("UPDATE chunks SET knowledge_base_id='' WHERE id='chunk'");
    if (mode.equals("chunk-orphan-doc"))
      jdbc.update("UPDATE chunks SET knowledge_id='missing' WHERE id='chunk'");
    if (mode.equals("knowledge-missing"))
      jdbc.update("UPDATE knowledges SET deleted_at=CURRENT_TIMESTAMP WHERE id='done'");
    if (mode.equals("chunk-missing"))
      jdbc.update("UPDATE chunks SET deleted_at=CURRENT_TIMESTAMP WHERE id='chunk'");
    var payloads =
        java.util.Map.ofEntries(
            java.util.Map.entry(
                "profile-typed",
                "{\"gist\":\"Summary\",\"topics\":[\"A\",\"B\"],\"doc_type\":\"\",\"typical_question\":\"Question\"}"),
            java.util.Map.entry("profile-null", "null"),
            java.util.Map.entry("profile-empty", ""),
            java.util.Map.entry("profile-wrong-type", "{\"gist\":1}"),
            java.util.Map.entry(
                "profile-unknown",
                "{\"unknown\":\"PRIVATE\",\"topics\":[],\"doc_type\":\"report\"}"),
            java.util.Map.entry("profile-duplicate-null", "{\"gist\":\"Summary\",\"gist\":null}"),
            java.util.Map.entry(
                "locators-typed",
                "[{\"type\":\"pdf\",\"page\":2,\"bbox\":[0,0.2,0.8,1],\"quote\":\"Evidence\",\"mapping\":\"exact\",\"partial\":false}]"),
            java.util.Map.entry("locators-null", "null"),
            java.util.Map.entry("locators-empty", ""),
            java.util.Map.entry(
                "locators-unknown",
                "[{\"type\":\"text\",\"start\":0,\"end\":12,\"unknown\":\"PRIVATE\",\"quote\":\"text\"}]"),
            java.util.Map.entry("locators-wrong-type", "[{\"type\":\"pdf\",\"page\":\"2\"}]"),
            java.util.Map.entry(
                "locators-duplicate-null",
                "[{\"type\":\"pdf\",\"page\":2,\"page\":null,\"quote\":null}]"));
    if (payloads.containsKey(mode)) {
      if (mode.startsWith("profile-"))
        jdbc.update("UPDATE knowledges SET profile=? WHERE id='done'", payloads.get(mode));
      else jdbc.update("UPDATE chunks SET source_locators=? WHERE id='chunk'", payloads.get(mode));
    }
    if (mode.equals("lookup-knowledge-failure")) jdbc.execute("DROP TABLE knowledges");
    if (mode.equals("lookup-chunk-failure")) jdbc.execute("DROP TABLE chunks");
    if (mode.equals("tag-relation-failure")) jdbc.execute("DROP TABLE knowledge_tag_relations");
    if (mode.equals("knowledge-missing-kb-link"))
      jdbc.update("UPDATE knowledges SET knowledge_base_id='missing' WHERE id='done'");
    if (mode.equals("chunk-kb-link-other"))
      jdbc.update("UPDATE chunks SET knowledge_base_id='missing' WHERE id='chunk'");
    jdbc.update(
        "INSERT INTO tenants(id,name,business)"
            + " VALUES(7,'Caller','test'),(8,'Owner','test'),(9,'Third','test')");
    jdbc.update(
        "INSERT INTO"
            + " users(id,username,email,password_hash,tenant_id,is_system_admin,can_access_all_tenants)"
            + " VALUES('caller','Caller','caller@example.com','unused',7,?,?)",
        system,
        cross);
    jdbc.update(
        "INSERT INTO tenant_members(user_id,tenant_id,role,status) VALUES('caller',7,?,'active')",
        role);
    String route =
        switch (endpoint) {
          case "knowledge-list" -> "/api/v1/knowledge-bases/kb/knowledge";
          case "knowledge-detail" -> "/api/v1/knowledge/done";
          case "chunk-list" -> "/api/v1/chunks/done";
          default -> "/api/v1/chunks/by-id/chunk";
        };
    var queries =
        java.util.Map.ofEntries(
            java.util.Map.entry("page2", "page=2&page_size=2"),
            java.util.Map.entry("zero", "page=0&page_size=0"),
            java.util.Map.entry("negative-page", "page=-1"),
            java.util.Map.entry("negative-size", "page_size=-1"),
            java.util.Map.entry("oversize", "page_size=1001"),
            java.util.Map.entry("huge", "page=9223372036854775808"),
            java.util.Map.entry("bad-page", "page=x"),
            java.util.Map.entry("bad-size", "page_size=x"),
            java.util.Map.entry("empty-page", "page=&page_size="),
            java.util.Map.entry("page-duplicate", "page=2&page=1&page_size=2"),
            java.util.Map.entry("size100", "page_size=100"),
            java.util.Map.entry("size101", "page_size=101"),
            java.util.Map.entry("size1000", "page_size=1000"),
            java.util.Map.entry("keyword", "keyword=ALPHA"),
            java.util.Map.entry("keyword-percent", "keyword=%25"),
            java.util.Map.entry("keyword-under", "keyword=_"),
            java.util.Map.entry("keyword-backslash", "keyword=%5C"),
            java.util.Map.entry("tags", "tag_ids=tag-a"),
            java.util.Map.entry("tag-many", "tag_ids=%20tag-a%20,tag-b,tag-a,,__untagged__"),
            java.util.Map.entry("untagged", "tag_ids=__untagged__"),
            java.util.Map.entry("file-pdf", "file_type=pdf"),
            java.util.Map.entry("file-manual", "file_type=manual"),
            java.util.Map.entry("file-url", "file_type=url"),
            java.util.Map.entry("source-api", "source=api"),
            java.util.Map.entry("source-manual", "source=manual"),
            java.util.Map.entry("parse-deleting", "parse_status=deleting"),
            java.util.Map.entry("parse-failed", "parse_status=failed"),
            java.util.Map.entry("sort-created", "sort_by=created_at&sort_order=asc"),
            java.util.Map.entry("sort-updated", "sort_by=updated_at&sort_order=desc"),
            java.util.Map.entry("sort-filename", "sort_by=file_name&sort_order=asc"),
            java.util.Map.entry("sort-injection", "sort_by=id%20desc"),
            java.util.Map.entry("sort-upper", "sort_order=ASC"),
            java.util.Map.entry("sort-empty", "sort_by=&sort_order="),
            java.util.Map.entry("date-from", "start_time=2026-10-02"),
            java.util.Map.entry("date-end", "end_time=2026-10-02"),
            java.util.Map.entry("rfc-from", "start_time=2026-10-02T00:00:00Z"),
            java.util.Map.entry("datetime-from", "start_time=2026-10-02%2000:00:00"),
            java.util.Map.entry("date-bad", "start_time=not-a-time"),
            java.util.Map.entry("date-both-bad", "start_time=bad&end_time=bad"),
            java.util.Map.entry("date-whitespace", "start_time=%20"),
            java.util.Map.entry("end-bad", "end_time=2026-13-01"),
            java.util.Map.entry("folder-root", "folder_path="),
            java.util.Map.entry("folder-exact", "folder_path=docs"),
            java.util.Map.entry("folder-recursive", "folder_path=docs&folder_recursive=true"),
            java.util.Map.entry("folder-normalized", "folder_path=%20/docs./../spec/%20"),
            java.util.Map.entry("folder-false", "folder_path=docs&folder_recursive=invalid"),
            java.util.Map.entry("folder-boolean", "folder_path=docs&folder_recursive=1"),
            java.util.Map.entry("chunk-image", "chunk_type=image_caption"),
            java.util.Map.entry("chunk-many", "chunk_type=text&chunk_type=image_caption"),
            java.util.Map.entry("chunk-unknown", "chunk_type=unknown"),
            java.util.Map.entry("chunk-empty", "chunk_type="),
            java.util.Map.entry("chunk-comma", "chunk_type=text,image_caption"));
    if (mode.startsWith("query-")) route += "?" + queries.get(mode.substring(6));
    var request =
        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                java.net.URI.create(route))
            .param("agent_id", agentId)
            .param("agent_source_tenant_id", sourceSelector);
    if (machine) {
      String rawKey = "sk-test-read-scope";
      jdbc.update(
          "INSERT INTO"
              + " tenant_api_keys(tenant_id,name,key_hash,api_key,full_access,capabilities,knowledge_base_ids)"
              + " VALUES(7,'Reader',?,?,?, ?,?)",
          AuthFilter.hashApiKey(rawKey),
          rawKey,
          full,
          json.writeValueAsString(caps),
          json.writeValueAsString(ids));
      request.header("X-API-Key", rawKey);
    } else {
      String token =
          jwt.sign(
              Map.of(
                  "user_id",
                  "caller",
                  "tenant_id",
                  7,
                  "type",
                  "access",
                  "exp",
                  java.time.Instant.now().plusSeconds(3600).getEpochSecond()));
      new AuthRepository(jdbc, json)
          .storeToken("caller", token, "access_token", java.time.Instant.now().plusSeconds(3600));
      request.header("Authorization", "Bearer " + token);
    }
    var response = mvc.perform(request).andReturn().getResponse();
    int status = response.getStatus();
    JsonNode body = json.readTree(response.getContentAsByteArray());
    if (Set.of("profile-wrong-type", "locators-wrong-type").contains(mode)
        && expected.path("status").asInt() == 500
        && expected.path("body").path("error").has("details")) {
      ((com.fasterxml.jackson.databind.node.ObjectNode) expected.path("body").path("error"))
          .put("message", "[driver-specific typed SQL scan error]");
      if (body.path("error").has("details"))
        ((com.fasterxml.jackson.databind.node.ObjectNode) body.path("error"))
            .put("message", "[driver-specific typed SQL scan error]");
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    for (var row :
        jdbc.queryForList(
            "SELECT"
                + " tenant_id,actor_user_id,actor_role,action,request_path,request_method,outcome,details"
                + " FROM audit_logs ORDER BY id")) {
      row.put("details", json.readTree(row.get("details").toString()));
      rows.add(row);
    }
    assertEquals(
        expected,
        json.readTree(
            json.writeValueAsBytes(Map.of("status", status, "body", body, "audits", rows))));
  }
}
