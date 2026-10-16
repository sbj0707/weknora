package com.tencent.weknora.vectorstore;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.model.client.CallContext;
import com.tencent.weknora.security.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Full authentication filter, production admin/Viewer guard, ES8 registry and Hikari1 SQL. */
@SpringBootTest(
    properties = {
      "weknora.jwt-secret=isolated-vector-public-secret",
      "weknora.bridge.enabled=false",
      "weknora.tasks.enabled=false",
      "weknora.knowledge.processing.enabled=false",
      "spring.datasource.driver-class-name=org.sqlite.JDBC",
      "spring.datasource.hikari.maximum-pool-size=1",
      "NEO4J_ENABLE=false",
      "RETRIEVE_DRIVER=sqlite",
      "SSRF_WHITELIST=127.0.0.1",
      "WEKNORA_TENANT_ENABLE_RBAC=true",
      "SYSTEM_AES_KEY=0123456789abcdef0123456789abcdef",
      "logging.level.root=WARN"
    })
@AutoConfigureMockMvc
class VectorStorePublicPipelineHttpIT {
  static final Path directory;

  static {
    try {
      com.tencent.weknora.retrieval.NativeDatabaseRuntime.initialize();
      directory = Files.createTempDirectory("weknora-vector-public-spring-");
      try (var c =
          java.sql.DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("test.db"))) {
        for (var migration :
            List.of(
                "000000_init",
                "000002_knowledge_folder_path",
                "000003_knowledge_base_auto_tag_config",
                "000007_system_admin_and_settings",
                "000008_processing_spans_and_pending_subtasks",
                "000010_knowledge_multi_tags",
                "000011_principal_model",
                "000021_knowledge_profiles",
                "000031_model_catalog_config",
                "000033_chunk_source_locators"))
          ScriptUtils.executeSqlScript(
              c, new FileSystemResource("../migrations/sqlite/" + migration + ".up.sql"));
      }
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", () -> "jdbc:sqlite:" + directory.resolve("test.db"));
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired JwtCodec jwt;
  @Autowired ObjectMapper json;
  @Autowired VectorStoreRegistry registry;
  @Autowired VectorStoreMetadata metadata;
  String admin, viewer, foreign, namespace;
  List<String> stores;
  List<String> indexes;

  @BeforeEach
  void setup() {
    namespace = "vector-public-" + UUID.randomUUID();
    stores = new ArrayList<>();
    indexes = new ArrayList<>();
    for (long tenant : List.of(1L, 2L))
      jdbc.update(
          "INSERT INTO tenants(id,name,business) VALUES(?,?,'isolated') ON CONFLICT(id) DO NOTHING",
          tenant,
          "Tenant" + tenant);
    for (var role : Map.of("admin", "admin", "viewer", "viewer", "foreign", "admin").entrySet()) {
      String id = namespace + "-" + role.getKey();
      long tenant = role.getKey().equals("foreign") ? 2 : 1;
      jdbc.update(
          "INSERT INTO users(id,username,email,password_hash,tenant_id) VALUES(?,?,?,'unused',?)",
          id,
          id,
          id + "@example.invalid",
          tenant);
      jdbc.update(
          "INSERT INTO tenant_members(user_id,tenant_id,role,status) VALUES(?,?,?,'active')",
          id,
          tenant,
          role.getValue());
    }
    admin = token(namespace + "-admin", 1);
    viewer = token(namespace + "-viewer", 1);
    foreign = token(namespace + "-foreign", 2);
  }

  String token(String user, long tenant) {
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

  String key(String capability, boolean full) {
    String value = "vector-key-" + UUID.randomUUID();
    jdbc.update(
        "INSERT INTO"
            + " tenant_api_keys(tenant_id,name,key_hash,api_key,full_access,capabilities,knowledge_base_ids)"
            + " VALUES(1,'Vector key',?,?,?,?, '[\"unrelated-kb\"]')",
        AuthFilter.hashApiKey(value),
        value,
        full,
        "[\"" + capability + "\"]");
    return value;
  }

  JsonNode request(
      String method, String path, String token, boolean machine, String body, int status)
      throws Exception {
    var response =
        mvc.perform(
                MockMvcRequestBuilders.request(
                        org.springframework.http.HttpMethod.valueOf(method),
                        "/api/v1/vector-stores" + path)
                    .header(
                        machine ? "X-API-Key" : "Authorization",
                        machine ? token : "Bearer " + token)
                    .contentType("application/json")
                    .content(body))
            .andReturn()
            .getResponse();
    assertEquals(status, response.getStatus(), response.getContentAsString());
    return json.readTree(response.getContentAsByteArray());
  }

  JsonNode create(String credential, boolean machine) throws Exception {
    String index = namespace.replace("vector-public-", "vector-") + "-" + indexes.size();
    indexes.add(index);
    String body =
        json.createObjectNode()
            .put("name", index)
            .put("engine_type", "elasticsearch")
            .set(
                "connection_config",
                json.createObjectNode()
                    .put("addr", System.getProperty("weknora.es.url"))
                    .put("password", "only-synthetic-password")
                    .put("api_key", "only-synthetic-api"))
            .toString();
    var node = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(body);
    node.putObject("index_config").put("index_name", index);
    var data = request("POST", "", credential, machine, node.toString(), 201).path("data");
    stores.add(data.path("id").asText());
    assertEquals("***", data.path("connection_config").path("password").asText());
    assertEquals("8.14.0", data.path("connection_config").path("version").asText());
    return data;
  }

  @AfterEach
  void cleanup() throws Exception {
    for (String id : stores) registry.unregister(id);
    for (String index : indexes) {
      var request =
          java.net.http.HttpRequest.newBuilder(
                  java.net.URI.create(System.getProperty("weknora.es.url") + "/" + index))
              .DELETE()
              .build();
      java.net.http.HttpClient.newHttpClient()
          .send(request, java.net.http.HttpResponse.BodyHandlers.discarding());
    }
  }

  @Test
  void productionViewerReadsAndRejectsMalformedWritesBeforeBinder() throws Exception {
    var row = create(admin, false);
    request("GET", "", viewer, false, "", 200);
    request("GET", "/types", viewer, false, "", 200);
    request("GET", "/" + row.path("id").asText(), viewer, false, "", 200);
    for (String method : List.of("POST", "PUT", "DELETE")) {
      String path = method.equals("POST") ? "" : "/" + row.path("id").asText();
      assertEquals(
          "Forbidden: insufficient workspace role",
          request(method, path, viewer, false, "{", 403).path("error").asText());
    }
    request("POST", "/test", viewer, false, "{", 403);
    request("POST", "/" + row.path("id").asText() + "/test", viewer, false, "{", 403);
  }

  @Test
  void scopedManageCapabilityAndFullKeyHaveIndependentMachineAuthority() throws Exception {
    String manage = key("manage_vector_stores", false);
    var row = create(manage, true);
    request("GET", "/" + row.path("id").asText(), manage, true, "", 200);
    assertEquals(
        "Forbidden: API key scope does not allow this operation",
        request("POST", "", key("retrieve", false), true, "{", 403).path("error").asText());
    create(key("retrieve", true), true);
  }

  @Test
  void nameOnlyUpdatePreservesEncryptedCredentialsAndRuntimeEngine() throws Exception {
    var row = create(admin, false);
    String id = row.path("id").asText();
    var before = metadata.get(1, id);
    try (var call = new CallContext()) {
      var cached = registry.resolve(1, id, call);
      var updated =
          request(
              "PUT",
              "/" + id,
              admin,
              false,
              "{\"name\":\"Changed\",\"connection_config\":{\"password\":\"ignored\"},\"engine_type\":\"qdrant\"}",
              200);
      assertEquals("elasticsearch", updated.path("data").path("engine_type").asText());
      assertSame(cached, registry.resolve(1, id, call));
    }
    assertEquals(before.connection(), metadata.get(1, id).connection());
    String raw =
        new String(
            (byte[])
                jdbc.queryForObject(
                    "SELECT connection_config FROM vector_stores WHERE id=?", Object.class, id),
            java.nio.charset.StandardCharsets.UTF_8);
    assertFalse(raw.contains("only-synthetic-password"));
    assertTrue(raw.contains("enc:v1:"));
  }

  @Test
  void foreignOwnerCannotQueryUpdateTestOrDeleteOtherStores() throws Exception {
    String id = create(admin, false).path("id").asText();
    for (String method : List.of("GET", "PUT", "DELETE"))
      request(method, "/" + id, foreign, false, "{", 404);
    request("POST", "/" + id + "/test", foreign, false, "{", 404);
    assertNotNull(metadata.get(1, id));
  }

  @Test
  void boundKbBlocksDeletionUntilUnboundAndCommitThenUnregisters() throws Exception {
    String id = create(admin, false).path("id").asText();
    String kb = namespace + "-kb";
    jdbc.update(
        "INSERT INTO"
            + " knowledge_bases(id,tenant_id,name,type,vector_store_id,embedding_model_id,summary_model_id)"
            + " VALUES(?,1,'KB','document',?,'','')",
        kb,
        id);
    assertEquals(
        1000,
        request("DELETE", "/" + id, admin, false, "", 400).path("error").path("code").asInt());
    assertNotNull(metadata.get(1, id));
    jdbc.update("UPDATE knowledge_bases SET vector_store_id=NULL WHERE id=?", kb);
    request("DELETE", "/" + id, admin, false, "", 200);
    assertNull(metadata.get(1, id));
    assertFalse(registry.registered(id));
    request("GET", "/" + id, admin, false, "", 404);
  }

  @Test
  void probeSavedVersionAndEnvironmentReadonlyPathsUseProductionService() throws Exception {
    String id = create(admin, false).path("id").asText();
    var cc = metadata.get(1, id).connection().deepCopy();
    cc.put("version", "old");
    jdbc.update(
        "UPDATE vector_stores SET connection_config=? WHERE id=?",
        cc.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
        id);
    assertEquals(
        "8.14.0",
        request("POST", "/" + id + "/test", admin, false, "{", 200).path("version").asText());
    assertEquals("8.14.0", metadata.get(1, id).connection().path("version").asText());
    request("GET", "/__env_sqlite__", viewer, false, "", 200);
    assertEquals(
        "",
        request("POST", "/__env_sqlite__/test", admin, false, "{", 200).path("version").asText());
    request("PUT", "/__env_sqlite__", admin, false, "{", 400);
    request("DELETE", "/__env_sqlite__", admin, false, "", 400);
  }
}
