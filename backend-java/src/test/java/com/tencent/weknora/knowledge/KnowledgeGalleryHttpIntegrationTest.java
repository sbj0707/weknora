package com.tencent.weknora.knowledge;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.security.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Real JWT/API key filter, original shared caller preferences and live SQL projection triggers. */
@org.springframework.boot.test.context.SpringBootTest(
    properties = {
      "weknora.jwt-secret=isolated-gallery-http-secret",
      "weknora.bridge.enabled=false",
      "weknora.knowledge.processing.enabled=false",
      "weknora.tasks.enabled=false",
      "logging.level.root=WARN",
      "debug=false"
    })
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class KnowledgeGalleryHttpIntegrationTest {
  private static final Path DB;

  static {
    try {
      DB = Files.createTempFile("weknora-gallery-http-", ".db");
      DB.toFile().deleteOnExit();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @org.springframework.test.context.DynamicPropertySource
  static void properties(org.springframework.test.context.DynamicPropertyRegistry p) {
    p.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB);
    p.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    p.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired javax.sql.DataSource datasource;
  @Autowired MockMvc mvc;
  @Autowired JwtCodec jwt;
  @Autowired ObjectMapper json;
  private String owner, shared;

  @BeforeEach
  void seed() throws Exception {
    for (String table :
        jdbc.queryForList(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'",
            String.class)) jdbc.execute("DROP TABLE \"" + table.replace("\"", "\"\"") + "\"");
    try (var c = datasource.getConnection();
        var st = c.createStatement()) {
      for (String name :
          List.of(
              "000000_init",
              "000002_knowledge_folder_path",
              "000003_knowledge_base_auto_tag_config",
              "000004_memory",
              "000007_system_admin_and_settings",
              "000008_processing_spans_and_pending_subtasks",
              "000010_knowledge_multi_tags",
              "000011_principal_model",
              "000021_knowledge_profiles",
              "000031_model_catalog_config",
              "000032_chunk_images",
              "000033_chunk_source_locators"))
        st.executeUpdate(Files.readString(Path.of("../migrations/sqlite/" + name + ".up.sql")));
      st.executeUpdate(Files.readString(Path.of("migration/sql/java-tasks.sql")));
    }
    JsonNode oracle;
    try (var in = getClass().getResourceAsStream("/go-knowledge-gallery-http.json")) {
      oracle = json.readTree(in).get(0);
    }
    for (var statement : oracle.path("seed_statements")) jdbc.execute(statement.asText());
    jdbc.update(
        "INSERT INTO users(id,username,email,password_hash,tenant_id,is_active,preferences)"
            + " VALUES('shared','Shared','shared@example.test','unused',2,true,'{\"gallery\":{\"mode\":\"custom\",\"status\":{\"builtin:caption\":\"off\"}}}')");
    jdbc.update(
        "INSERT INTO tenant_members(user_id,tenant_id,role,status)"
            + " VALUES('caller',1,'viewer','active'),('shared',2,'viewer','active')");
    jdbc.update(
        "INSERT INTO organizations(id,name,owner_tenant_id,owner_id)"
            + " VALUES('org','Org',1,'caller')");
    jdbc.update(
        "INSERT INTO organization_tenant_members(id,organization_id,tenant_id,role)"
            + " VALUES('src','org',1,'admin'),('dst','org',2,'viewer')");
    jdbc.update(
        "INSERT INTO"
            + " kb_shares(id,knowledge_base_id,organization_id,source_tenant_id,permission,shared_by_user_id)"
            + " VALUES('share','kb','org',1,'viewer','caller')");
    owner = token("caller", 1);
    shared = token("shared", 2);
  }

  private String token(String user, long tenant) {
    String value =
        jwt.sign(
            Map.of(
                "user_id",
                user,
                "tenant_id",
                tenant,
                "type",
                "access",
                "exp",
                Instant.now().plusSeconds(3600).getEpochSecond()));
    jdbc.update(
        "INSERT INTO auth_tokens(id,user_id,token,token_type,expires_at,is_revoked)"
            + " VALUES(?,?,?,'access_token',?,false)",
        UUID.randomUUID().toString(),
        user,
        value,
        Instant.now().plusSeconds(3600).toString());
    return value;
  }

  private String key(String capability, String kb, boolean full) {
    String value = "gallery-key-" + UUID.randomUUID();
    jdbc.update(
        "INSERT INTO"
            + " tenant_api_keys(tenant_id,name,key_hash,api_key,full_access,capabilities,knowledge_base_ids)"
            + " VALUES(1,'Gallery reader',?,?,?,?,?)",
        AuthFilter.hashApiKey(value),
        value,
        full,
        "[\"" + capability + "\"]",
        "[\"" + kb + "\"]");
    return value;
  }

  private JsonNode get(String endpoint, String credential, boolean machine, int expected)
      throws Exception {
    var builder = MockMvcRequestBuilders.get("/api/v1/knowledge-bases/kb/" + endpoint);
    builder.header(
        machine ? "X-API-Key" : "Authorization", machine ? credential : "Bearer " + credential);
    var response = mvc.perform(builder).andReturn().getResponse();
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(expected);
    return json.readTree(response.getContentAsByteArray());
  }

  @Test
  void sharedViewerUsesOwnerProjectionButOriginalUsersPreferencesAndRevocationIsImmediate()
      throws Exception {
    assertThat(get("gallery-config", owner, false, 200).path("data").path("mode").asText())
        .isEqualTo("all");
    var config = get("gallery-config", shared, false, 200).path("data");
    assertThat(config.path("mode").asText()).isEqualTo("custom");
    assertThat(config.path("status").path("builtin:caption").asText()).isEqualTo("off");
    var mine = get("images", owner, false, 200);
    assertThat(get("images", shared, false, 200)).isEqualTo(mine);
    assertThat(mine.path("total").asInt()).isEqualTo(5);
    jdbc.update("UPDATE kb_shares SET deleted_at=CURRENT_TIMESTAMP WHERE id='share'");
    get("images", shared, false, 403);
    get("gallery-config", shared, false, 403);
  }

  @Test
  void realApiKeysRequireRetrieveAndEnforceAllowlistEvenWithFullAccess() throws Exception {
    for (String endpoint : List.of("images", "gallery-config")) {
      get(endpoint, key("retrieve", "kb", false), true, 200);
      get(endpoint, key("ingest", "kb", false), true, 403);
      get(endpoint, key("retrieve", "other-kb", false), true, 403);
      get(endpoint, key("retrieve", "other-kb", true), true, 403);
    }
  }

  @Test
  void policyAndPreferencesAreReReadWithoutRestartAndIgnoredOnTypedErrors() throws Exception {
    jdbc.update(
        "INSERT INTO system_settings(key,value,value_type,category)"
            + " VALUES('gallery.policy',?,'string','gallery')",
        json.writeValueAsString("{\"sources\":{\"system\":false}}"));
    assertThat(
            get("gallery-config", owner, false, 200)
                .path("data")
                .path("attribute_sources")
                .toString())
        .isEqualTo("[\"builtin\"]");
    jdbc.update(
        "UPDATE system_settings SET value=? WHERE key='gallery.policy'",
        json.writeValueAsString("{\"sources\":{\"builtin\":false,\"system\":false}}"));
    assertThat(get("gallery-config", owner, false, 200).path("data").path("attributes")).isEmpty();
    jdbc.update(
        "UPDATE system_settings SET value=? WHERE key='gallery.policy'",
        json.writeValueAsString("{\"sources\":{\"system\":false},\"mode\":1}"));
    assertThat(
            get("gallery-config", owner, false, 200).path("data").path("attribute_sources").size())
        .isEqualTo(2);
    jdbc.update(
        "UPDATE users SET preferences='{\"gallery\":{\"mode\":\"custom\"}}' WHERE id='caller'");
    assertThat(get("gallery-config", owner, false, 200).path("data").path("mode").asText())
        .isEqualTo("custom");
  }

  @Test
  void chunkProjectionTracksUpdatesSoftDeletesAndPhysicalDeletesWithoutGalleryWrites()
      throws Exception {
    var quota = jdbc.queryForList("SELECT id,storage_used FROM tenants ORDER BY id");
    jdbc.update("UPDATE chunks SET image_info='[]' WHERE id='c-b'");
    var images = get("images", owner, false, 200);
    assertThat(images.path("total").asInt()).isEqualTo(4);
    boolean tiedCopy = false;
    for (var image : images.path("data"))
      if (image.path("url").asText().equals("same")) {
        assertThat(image.path("chunk_id").asText()).isEqualTo("c-e");
        tiedCopy = true;
      }
    assertThat(tiedCopy).isTrue();
    jdbc.update("UPDATE chunks SET deleted_at=CURRENT_TIMESTAMP WHERE id='c-e'");
    jdbc.update("DELETE FROM chunks WHERE id='c-a'");
    assertThat(get("images", owner, false, 200).path("total").asInt()).isEqualTo(3);
    assertThat(jdbc.queryForList("SELECT id,storage_used FROM tenants ORDER BY id"))
        .isEqualTo(quota);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM java_task_jobs", Integer.class)).isZero();
  }
}
