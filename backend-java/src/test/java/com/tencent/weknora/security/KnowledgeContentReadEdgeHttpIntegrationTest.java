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
class KnowledgeContentReadEdgeHttpIntegrationTest {
  private static final Path DATABASE;
  private static final TimeZone ORIGINAL_TIME_ZONE = TimeZone.getDefault();

  static {
    try {
      TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
      DATABASE = Files.createTempFile("weknora-read-http-", ".db");
      DATABASE.toFile().deleteOnExit();
    } catch (Exception ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  @AfterAll
  void restoreLocalTimeZone() {
    TimeZone.setDefault(ORIGINAL_TIME_ZONE);
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

  @TestFactory
  Stream<DynamicTest> actualGoContentReadStoredAndTimeHttpProfiles() throws Exception {
    var json = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    var fixture =
        json.readTree(
            new java.util.zip.GZIPInputStream(
                getClass().getResourceAsStream("/go-knowledge-content-read-edge.json.gz")));
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
    Map<String, List<String>> rawPayloads =
        Map.ofEntries(
            Map.entry("raw-metadata-empty", List.of("metadata", "")),
            Map.entry("raw-metadata-null", List.of("metadata", "null")),
            Map.entry("raw-metadata-space", List.of("metadata", " ")),
            Map.entry("raw-metadata-object", List.of("metadata", "{\"a\":1}")),
            Map.entry("raw-metadata-duplicate", List.of("metadata", "{\"a\":1,\"a\":2}")),
            Map.entry("raw-metadata-array", List.of("metadata", "[1,true,null,\"x\"]")),
            Map.entry("raw-metadata-string", List.of("metadata", "\"text\"")),
            Map.entry("raw-metadata-boolean", List.of("metadata", "true")),
            Map.entry("raw-metadata-zero", List.of("metadata", "-0")),
            Map.entry("raw-metadata-huge-float", List.of("metadata", "1e400")),
            Map.entry("raw-metadata-exact-integer", List.of("metadata", "9007199254740993")),
            Map.entry(
                "raw-metadata-fraction", List.of("metadata", "0.12345678901234567890123456789")),
            Map.entry("raw-metadata-trailing-value", List.of("metadata", "{} {}")),
            Map.entry("raw-metadata-trailing-garbage", List.of("metadata", "{} trailing")),
            Map.entry("raw-metadata-bad-array", List.of("metadata", "[1,]")),
            Map.entry("raw-metadata-bad-number", List.of("metadata", "01")),
            Map.entry("raw-metadata-empty-object", List.of("metadata", "{}")),
            Map.entry("raw-custom_metadata-empty", List.of("custom_metadata", "")),
            Map.entry("raw-custom_metadata-null", List.of("custom_metadata", "null")),
            Map.entry("raw-custom_metadata-space", List.of("custom_metadata", " ")),
            Map.entry("raw-custom_metadata-object", List.of("custom_metadata", "{\"a\":1}")),
            Map.entry(
                "raw-custom_metadata-duplicate", List.of("custom_metadata", "{\"a\":1,\"a\":2}")),
            Map.entry(
                "raw-custom_metadata-array", List.of("custom_metadata", "[1,true,null,\"x\"]")),
            Map.entry("raw-custom_metadata-string", List.of("custom_metadata", "\"text\"")),
            Map.entry("raw-custom_metadata-boolean", List.of("custom_metadata", "true")),
            Map.entry("raw-custom_metadata-zero", List.of("custom_metadata", "-0")),
            Map.entry("raw-custom_metadata-huge-float", List.of("custom_metadata", "1e400")),
            Map.entry(
                "raw-custom_metadata-exact-integer",
                List.of("custom_metadata", "9007199254740993")),
            Map.entry(
                "raw-custom_metadata-fraction",
                List.of("custom_metadata", "0.12345678901234567890123456789")),
            Map.entry("raw-custom_metadata-trailing-value", List.of("custom_metadata", "{} {}")),
            Map.entry(
                "raw-custom_metadata-trailing-garbage", List.of("custom_metadata", "{} trailing")),
            Map.entry("raw-custom_metadata-bad-array", List.of("custom_metadata", "[1,]")),
            Map.entry("raw-custom_metadata-bad-number", List.of("custom_metadata", "01")),
            Map.entry("raw-custom_metadata-empty-object", List.of("custom_metadata", "{}")),
            Map.entry("raw-last_faq_import_result-empty", List.of("last_faq_import_result", "")),
            Map.entry("raw-last_faq_import_result-null", List.of("last_faq_import_result", "null")),
            Map.entry("raw-last_faq_import_result-space", List.of("last_faq_import_result", " ")),
            Map.entry(
                "raw-last_faq_import_result-object",
                List.of("last_faq_import_result", "{\"a\":1}")),
            Map.entry(
                "raw-last_faq_import_result-duplicate",
                List.of("last_faq_import_result", "{\"a\":1,\"a\":2}")),
            Map.entry(
                "raw-last_faq_import_result-array",
                List.of("last_faq_import_result", "[1,true,null,\"x\"]")),
            Map.entry(
                "raw-last_faq_import_result-string", List.of("last_faq_import_result", "\"text\"")),
            Map.entry(
                "raw-last_faq_import_result-boolean", List.of("last_faq_import_result", "true")),
            Map.entry("raw-last_faq_import_result-zero", List.of("last_faq_import_result", "-0")),
            Map.entry(
                "raw-last_faq_import_result-huge-float",
                List.of("last_faq_import_result", "1e400")),
            Map.entry(
                "raw-last_faq_import_result-exact-integer",
                List.of("last_faq_import_result", "9007199254740993")),
            Map.entry(
                "raw-last_faq_import_result-fraction",
                List.of("last_faq_import_result", "0.12345678901234567890123456789")),
            Map.entry(
                "raw-last_faq_import_result-trailing-value",
                List.of("last_faq_import_result", "{} {}")),
            Map.entry(
                "raw-last_faq_import_result-trailing-garbage",
                List.of("last_faq_import_result", "{} trailing")),
            Map.entry(
                "raw-last_faq_import_result-bad-array", List.of("last_faq_import_result", "[1,]")),
            Map.entry(
                "raw-last_faq_import_result-bad-number", List.of("last_faq_import_result", "01")),
            Map.entry(
                "raw-last_faq_import_result-empty-object", List.of("last_faq_import_result", "{}")),
            Map.entry("raw-relation_chunks-empty", List.of("relation_chunks", "")),
            Map.entry("raw-relation_chunks-null", List.of("relation_chunks", "null")),
            Map.entry("raw-relation_chunks-space", List.of("relation_chunks", " ")),
            Map.entry("raw-relation_chunks-object", List.of("relation_chunks", "{\"a\":1}")),
            Map.entry(
                "raw-relation_chunks-duplicate", List.of("relation_chunks", "{\"a\":1,\"a\":2}")),
            Map.entry(
                "raw-relation_chunks-array", List.of("relation_chunks", "[1,true,null,\"x\"]")),
            Map.entry("raw-relation_chunks-string", List.of("relation_chunks", "\"text\"")),
            Map.entry("raw-relation_chunks-boolean", List.of("relation_chunks", "true")),
            Map.entry("raw-relation_chunks-zero", List.of("relation_chunks", "-0")),
            Map.entry("raw-relation_chunks-huge-float", List.of("relation_chunks", "1e400")),
            Map.entry(
                "raw-relation_chunks-exact-integer",
                List.of("relation_chunks", "9007199254740993")),
            Map.entry(
                "raw-relation_chunks-fraction",
                List.of("relation_chunks", "0.12345678901234567890123456789")),
            Map.entry("raw-relation_chunks-trailing-value", List.of("relation_chunks", "{} {}")),
            Map.entry(
                "raw-relation_chunks-trailing-garbage", List.of("relation_chunks", "{} trailing")),
            Map.entry("raw-relation_chunks-bad-array", List.of("relation_chunks", "[1,]")),
            Map.entry("raw-relation_chunks-bad-number", List.of("relation_chunks", "01")),
            Map.entry("raw-relation_chunks-empty-object", List.of("relation_chunks", "{}")),
            Map.entry(
                "raw-indirect_relation_chunks-empty", List.of("indirect_relation_chunks", "")),
            Map.entry(
                "raw-indirect_relation_chunks-null", List.of("indirect_relation_chunks", "null")),
            Map.entry(
                "raw-indirect_relation_chunks-space", List.of("indirect_relation_chunks", " ")),
            Map.entry(
                "raw-indirect_relation_chunks-object",
                List.of("indirect_relation_chunks", "{\"a\":1}")),
            Map.entry(
                "raw-indirect_relation_chunks-duplicate",
                List.of("indirect_relation_chunks", "{\"a\":1,\"a\":2}")),
            Map.entry(
                "raw-indirect_relation_chunks-array",
                List.of("indirect_relation_chunks", "[1,true,null,\"x\"]")),
            Map.entry(
                "raw-indirect_relation_chunks-string",
                List.of("indirect_relation_chunks", "\"text\"")),
            Map.entry(
                "raw-indirect_relation_chunks-boolean",
                List.of("indirect_relation_chunks", "true")),
            Map.entry(
                "raw-indirect_relation_chunks-zero", List.of("indirect_relation_chunks", "-0")),
            Map.entry(
                "raw-indirect_relation_chunks-huge-float",
                List.of("indirect_relation_chunks", "1e400")),
            Map.entry(
                "raw-indirect_relation_chunks-exact-integer",
                List.of("indirect_relation_chunks", "9007199254740993")),
            Map.entry(
                "raw-indirect_relation_chunks-fraction",
                List.of("indirect_relation_chunks", "0.12345678901234567890123456789")),
            Map.entry(
                "raw-indirect_relation_chunks-trailing-value",
                List.of("indirect_relation_chunks", "{} {}")),
            Map.entry(
                "raw-indirect_relation_chunks-trailing-garbage",
                List.of("indirect_relation_chunks", "{} trailing")),
            Map.entry(
                "raw-indirect_relation_chunks-bad-array",
                List.of("indirect_relation_chunks", "[1,]")),
            Map.entry(
                "raw-indirect_relation_chunks-bad-number",
                List.of("indirect_relation_chunks", "01")),
            Map.entry(
                "raw-indirect_relation_chunks-empty-object",
                List.of("indirect_relation_chunks", "{}")));
    if (rawPayloads.containsKey(mode)) {
      var p = rawPayloads.get(mode);
      String table = "knowledges", id = "done";
      if (Set.of("relation_chunks", "indirect_relation_chunks").contains(p.get(0))) {
        table = "chunks";
        id = "chunk";
      }
      jdbc.update("UPDATE " + table + " SET " + p.get(0) + "=? WHERE id=?", p.get(1), id);
      if (p.get(0).equals("metadata"))
        jdbc.update("UPDATE chunks SET metadata=? WHERE id='chunk'", p.get(1));
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
    Map<String, String> queries =
        Map.ofEntries(
            Map.entry("edge-time-0", "start_time="),
            Map.entry("edge-time-1", "start_time=%20"),
            Map.entry("edge-time-2", "start_time=%C2%A0%E2%80%80"),
            Map.entry("edge-time-3", "start_time=2026-10-02"),
            Map.entry("edge-time-4", "start_time=2026-10-02T00%3A00%3A00Z"),
            Map.entry("edge-time-5", "start_time=2026-10-02T0%3A00%3A00Z"),
            Map.entry("edge-time-6", "start_time=2026-10-02T09%3A08%3A07%2C123Z"),
            Map.entry("edge-time-7", "start_time=2026-10-02T09%3A08%3A07.123456789987Z"),
            Map.entry("edge-time-8", "start_time=2026-10-02T09%3A08%3A07.Z"),
            Map.entry("edge-time-9", "start_time=2026-10-02T09%3A08%3A07%2CZ"),
            Map.entry("edge-time-10", "start_time=2026-10-02T09%3A08%3A07%2B24%3A00"),
            Map.entry("edge-time-11", "start_time=2026-10-02T09%3A08%3A07%2B24%3A60"),
            Map.entry("edge-time-12", "start_time=2026-10-02T09%3A08%3A07-24%3A60"),
            Map.entry("edge-time-13", "start_time=2026-10-02T09%3A08%3A07%2B00%3A60"),
            Map.entry("edge-time-14", "start_time=2026-10-02T09%3A08%3A07%2B25%3A00"),
            Map.entry("edge-time-15", "start_time=2026-10-02T09%3A08%3A07%2B02%3A61"),
            Map.entry("edge-time-16", "start_time=2026-10-02T09%3A08%3A07%2B18%3A59"),
            Map.entry("edge-time-17", "start_time=2026-10-02T09%3A08%3A07z"),
            Map.entry("edge-time-18", "start_time=2026-10-02t09%3A08%3A07Z"),
            Map.entry("edge-time-19", "start_time=2026-10-02T09%3A08Z"),
            Map.entry("edge-time-20", "start_time=2026-10-02T24%3A00%3A00Z"),
            Map.entry("edge-time-21", "start_time=2026-10-02T09%3A8%3A07Z"),
            Map.entry("edge-time-22", "start_time=2026-10-02T09%3A08%3A7Z"),
            Map.entry("edge-time-23", "start_time=2026-10-02%209%3A08%3A07"),
            Map.entry("edge-time-24", "start_time=2026-10-02%20%20%2009%3A08%3A07"),
            Map.entry("edge-time-25", "start_time=2026-10-02%0909%3A08%3A07"),
            Map.entry("edge-time-26", "start_time=2026-10-02%2009%3A08%3A07.123456789987"),
            Map.entry("edge-time-27", "start_time=2026-10-02%2009%3A08%3A07%2C123"),
            Map.entry("edge-time-28", "start_time=2026-10-02%2009%3A08%3A07Z"),
            Map.entry("edge-time-29", "start_time=2026-10-02%2009%3A08%3A07%2B08%3A00"),
            Map.entry("edge-time-30", "start_time=2026-10-02%2024%3A00%3A00"),
            Map.entry("edge-time-31", "start_time=2026-10-02%2009%3A8%3A07"),
            Map.entry("edge-time-32", "start_time=2026-10-02%2009%3A08%3A7"),
            Map.entry("edge-time-33", "start_time=2026-10-02%2009%3A08%3A60"),
            Map.entry("edge-time-34", "start_time=0000-01-01"),
            Map.entry("edge-time-35", "start_time=0001-01-01"),
            Map.entry("edge-time-36", "start_time=9999-12-31"),
            Map.entry("edge-time-37", "start_time=10000-01-01"),
            Map.entry("edge-time-38", "start_time=-001-01-01"),
            Map.entry("edge-time-39", "start_time=2026-1-02"),
            Map.entry("edge-time-40", "start_time=2026-10-2"),
            Map.entry("edge-time-41", "start_time=2026-00-01"),
            Map.entry("edge-time-42", "start_time=2026-13-01"),
            Map.entry("edge-time-43", "start_time=2026-02-29"),
            Map.entry("edge-time-44", "start_time=2024-02-29"),
            Map.entry("edge-time-45", "start_time=2026-04-31"),
            Map.entry("edge-time-46", "start_time=2026-10-00"),
            Map.entry("edge-time-47", "start_time=2026-10-32"),
            Map.entry("edge-time-48", "start_time=2026-10-02extra"),
            Map.entry("edge-time-49", "start_time=2026-10-02%0Aextra"),
            Map.entry("edge-time-50", "start_time=2026-10-02%22"),
            Map.entry("edge-time-51", "start_time=2026-10-02%E2%80%A8extra"),
            Map.entry("edge-time-52", "start_time=2026-10"),
            Map.entry("edge-time-53", "start_time=2026"),
            Map.entry("edge-time-54", "start_time=x"),
            Map.entry("edge-time-55", "start_time=%C2%A0%202026-10-02%20%E3%80%80"),
            Map.entry("edge-time-56", "start_time=2026-10-02%E2%80%8B"),
            Map.entry("edge-time-57", "start_time=2026-03-08%2002%3A30%3A00"),
            Map.entry("edge-time-58", "start_time=2026-11-01%2001%3A30%3A00"),
            Map.entry("edge-time-59", "start_time=1919-01-01%2000%3A00%3A00"),
            Map.entry("edge-time-60", "start_time=1880-01-01%2000%3A00%3A00"),
            Map.entry("edge-time-61", "start_time=2026-03-29%2002%3A30%3A00"),
            Map.entry("edge-time-62", "start_time=2026-10-25%2002%3A30%3A00"),
            Map.entry("edge-time-63", "start_time=2026-10-02T09%3A08%3A07.0Z"),
            Map.entry("edge-time-64", "start_time=2026-10-02T09%3A08%3A07%2C0Z"),
            Map.entry("edge-time-65", "start_time=2026-10-02T09%3A08%3A07.000Z"),
            Map.entry("edge-time-66", "start_time=2026-10-02T09%3A08%3A07%2C000Z"),
            Map.entry("edge-time-67", "start_time=2026-10-02T09%3A08%3A07.1Z"),
            Map.entry("edge-time-68", "start_time=2026-10-02T09%3A08%3A07%2C1Z"),
            Map.entry("edge-time-69", "start_time=2026-10-02T09%3A08%3A07.123456789Z"),
            Map.entry("edge-time-70", "start_time=2026-10-02T09%3A08%3A07%2C123456789Z"),
            Map.entry("edge-time-71", "start_time=2026-10-02T09%3A08%3A07.1234567890Z"),
            Map.entry("edge-time-72", "start_time=2026-10-02T09%3A08%3A07%2C1234567890Z"),
            Map.entry(
                "edge-time-73", "start_time=2026-10-02T09%3A08%3A07.9999999999999999999999999999Z"),
            Map.entry(
                "edge-time-74",
                "start_time=2026-10-02T09%3A08%3A07%2C9999999999999999999999999999Z"));
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
    if ((Set.of("profile-wrong-type", "locators-wrong-type").contains(mode)
            || mode.startsWith("raw-"))
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
