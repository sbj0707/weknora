package com.tencent.weknora.model;

import com.tencent.weknora.security.AuthFilter;
import com.tencent.weknora.security.JwtCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"weknora.jwt-secret=model-test-secret", "weknora.bridge.enabled=false", "SYSTEM_AES_KEY=", "debug=false", "logging.level.root=WARN"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModelIntegrationTest {
    static final Path DATABASE;
    static { try { DATABASE=Files.createTempFile("weknora-model-test-", ".db"); DATABASE.toFile().deleteOnExit(); } catch(Exception ex) { throw new ExceptionInInitializerError(ex); } }
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:"+DATABASE);
        registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource datasource;
    @Autowired JwtCodec jwt;
    @Autowired ObjectMapper json;
    @Autowired ModelCatalogRuntime catalog;
    final Map<String,String> tokens = new HashMap<>();
    @BeforeAll void initialize() throws Exception {
        try(var c=datasource.getConnection()) {
            ScriptUtils.executeSqlScript(c, new FileSystemResource("../migrations/sqlite/000000_init.up.sql"));
            ScriptUtils.executeSqlScript(c, new FileSystemResource("../migrations/sqlite/000007_system_admin_and_settings.up.sql"));
            ScriptUtils.executeSqlScript(c, new FileSystemResource("../migrations/sqlite/000011_principal_model.up.sql"));
            ScriptUtils.executeSqlScript(c, new FileSystemResource("../migrations/sqlite/000031_model_catalog_config.up.sql"));
        }
        jdbc.update("INSERT INTO tenants(id,name,business) VALUES(1,'one','test'),(2,'two','test')");
        for(String role: List.of("viewer","contributor","admin","owner")) {
            jdbc.update("INSERT INTO users(id,username,email,password_hash,tenant_id) VALUES(?,?,?,?,1)", role,role,role+"@test.invalid","unused");
            jdbc.update("INSERT INTO tenant_members(user_id,tenant_id,role,status) VALUES(?,1,?,'active')",role,role);
            String token=jwt.sign(Map.of("user_id",role,"tenant_id",1,"exp",Instant.now().plusSeconds(3600).getEpochSecond()));
            tokens.put(role,token);
            jdbc.update("INSERT INTO auth_tokens(id,user_id,token,token_type,expires_at,is_revoked) VALUES(?,?,?,'access_token','2099-01-01 00:00:00',false)",UUID.randomUUID().toString(),role,token);
        }
        String params="{\"base_url\":\"https://private.invalid\",\"provider\":\"openai\",\"api_key\":\"enc:v1:broken\",\"app_secret\":\"stored-secret\",\"app_id\":\"account\",\"extra_config\":{\"secret_key\":\"hidden-sk\",\"region\":\"cn\"},\"custom_headers\":{\"X-Routing\":\"private\"},\"spec\":{\"api\":\"openai-completions\"}}";
        for(String id: List.of("own","foreign","builtin","deleted","downloading","failed","abnormal")) {
            boolean builtin=id.equals("builtin");
            long tenant=id.equals("foreign") || builtin ? 2 : 1;
            String status=switch(id) {case "downloading" -> "downloading";case "failed" -> "download_failed";case "abnormal" -> "unknown";default -> "active";};
            jdbc.update("INSERT INTO models(id,tenant_id,name,display_name,type,source,parameters,is_builtin,status,deleted_at) VALUES(?,?,'embedding','Shown','Embedding','remote',?,?,?,?)",id,tenant,params,builtin,status,id.equals("deleted") ? "2026-10-01 00:00:00" : null);
        }
        jdbc.update("INSERT INTO tenant_api_keys(tenant_id,key_hash,name,full_access) VALUES(1,?,'test',true)",AuthFilter.hashApiKey("model-key"));
    }
    @Test void listIgnoresQueryFiltersAndIncludesBuiltinAndInactiveButNotForeignOrDeleted() throws Exception {
        mvc.perform(get("/api/v1/models").header("Authorization","Bearer "+tokens.get("viewer")).param("type","ASR").param("source","local").param("page","999"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.length()").value(5))
            .andExpect(jsonPath("$.data[?(@.id=='foreign')]").isEmpty())
            .andExpect(jsonPath("$.data[?(@.id=='deleted')]").isEmpty())
            .andExpect(jsonPath("$.data[?(@.id=='builtin')].tenant_id").value(org.hamcrest.Matchers.contains(2)))
            .andExpect(jsonPath("$.data[?(@.id=='downloading')].status").value(org.hamcrest.Matchers.contains("downloading")));
    }
    @Test void roleAndBuiltinRedactionAndCryptoPresenceMatchGo() throws Exception {
        for(String role:List.of("viewer","contributor")) {
            mvc.perform(get("/api/v1/models/own").header("Authorization","Bearer "+tokens.get(role)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.parameters.base_url").value(""))
                .andExpect(jsonPath("$.data.parameters.extra_config").doesNotExist())
                .andExpect(jsonPath("$.data.parameters.custom_headers").doesNotExist())
                .andExpect(jsonPath("$.data.parameters.spec").doesNotExist())
                .andExpect(jsonPath("$.data.parameters.app_id").value("account"))
                .andExpect(jsonPath("$.data.credentials.api_key.configured").value(false))
                .andExpect(jsonPath("$.data.credentials.app_secret.configured").value(true));
        }
        mvc.perform(get("/api/v1/models/own").header("Authorization","Bearer "+tokens.get("admin")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.parameters.base_url").value("https://private.invalid"))
            .andExpect(jsonPath("$.data.parameters.extra_config.region").value("cn"))
            .andExpect(jsonPath("$.data.parameters.extra_config.secret_key").doesNotExist())
            .andExpect(jsonPath("$.data.parameters.api_key").doesNotExist())
            .andExpect(jsonPath("$.data.parameters.app_secret").doesNotExist())
            .andExpect(jsonPath("$.data.credentials.secret_key.configured").value(true));
        mvc.perform(get("/api/v1/models/builtin").header("Authorization","Bearer "+tokens.get("owner")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.parameters.base_url").value(""))
            .andExpect(jsonPath("$.data.parameters.app_id").doesNotExist()).andExpect(jsonPath("$.data.credentials").doesNotExist());
    }
    @Test void getStatusAndMissingRowsMatchActualGoService() throws Exception {
        for(String id: List.of("foreign","deleted","missing")) mvc.perform(get("/api/v1/models/"+id).header("Authorization","Bearer "+tokens.get("viewer"))).andExpect(status().isNotFound());
        var errors=Map.of("downloading","model is currently downloading","failed","model download failed","abnormal","abnormal model status");
        for(var error:errors.entrySet()) mvc.perform(get("/api/v1/models/"+error.getKey()).header("Authorization","Bearer "+tokens.get("viewer")))
            .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.error.message").value(error.getValue()));
    }
    @Test void authenticationAndUnmigratedConnectivityFailClosed() throws Exception {
        mvc.perform(get("/api/v1/models")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/models").header("X-API-Key","model-key")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/models").header("Authorization","Bearer "+tokens.get("viewer")).header("X-Tenant-ID","2")).andExpect(status().isForbidden());
        for(String path:List.of("weknoracloud/status","own/credentials")) mvc.perform(get("/api/v1/models/"+path).header("Authorization","Bearer "+tokens.get("viewer"))).andExpect(status().isServiceUnavailable());
        jdbc.update("INSERT INTO models(id,tenant_id,name,type,source,parameters) VALUES('chat',1,'gpt-5','KnowledgeQA','remote','{}')");
        try {
            mvc.perform(get("/api/v1/models/chat").header("Authorization","Bearer "+tokens.get("viewer"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.capabilities.api").value("openai-completions")).andExpect(jsonPath("$.data.capabilities.provider").value("generic"));
            mvc.perform(get("/api/v1/models").header("Authorization","Bearer "+tokens.get("viewer"))).andExpect(status().isOk());
        } finally { jdbc.update("DELETE FROM models WHERE id='chat'"); }
    }
    @Test void realGoHandlerGoldenResponsesForProviderAndResolveReads() throws Exception {
        var fixtures=json.readTree(Files.readString(Path.of("src/test/java/com/tencent/weknora/model/go-catalog-http-fixtures.json")));
        for(var fixture:fixtures) {
            String path=fixture.path("path").asText(),role=fixture.path("role").asText();
            URI uri=URI.create(path);
            var request=fixture.path("method").asText().equals("GET")?get(uri.getPath()):post(uri.getPath()).contentType("application/json").content(fixture.path("body").asText());
            // MockMvc URI extraction does not apply form-query '+' semantics; supply
            // parameters as a real servlet container decodes them for Go-generated URLs.
            if(uri.getRawQuery()!=null)for(String pair:uri.getRawQuery().split("&")) {
                String[] kv=pair.split("=",2);
                request.param(java.net.URLDecoder.decode(kv[0],java.nio.charset.StandardCharsets.UTF_8),kv.length==2?java.net.URLDecoder.decode(kv[1],java.nio.charset.StandardCharsets.UTF_8):"");
            }
            var response=mvc.perform(request.header("Authorization","Bearer "+tokens.get(role))).andExpect(status().is(fixture.path("status").asInt())).andReturn().getResponse();
            org.assertj.core.api.Assertions.assertThat(json.readTree(response.getContentAsString())).as("%s %s role=%s",fixture.path("method").asText(),path,role).isEqualTo(fixture.get("response"));
        }
    }
    @Test void consoleOverlayChangesReloadAtomicallyAndKeepLastValidGeneration() throws Exception {
        jdbc.update("INSERT INTO models(id,tenant_id,name,type,source,parameters) VALUES('configured-chat',1,'gpt-5','KnowledgeQA','remote','{\"provider\":\"openai\",\"base_url\":\"https://relay.invalid/v1\"}')");
        try {
            jdbc.update("UPDATE model_catalog_configs SET version=1,overlay=? WHERE id=1","{\"providers\":{\"openai\":{\"models\":[{\"id\":\"gpt-5\",\"context_window\":12345}]}}}");catalog.syncNow();
            mvc.perform(get("/api/v1/models/configured-chat").header("Authorization","Bearer "+tokens.get("viewer"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.capabilities.context_window").value(12345));
            jdbc.update("UPDATE model_catalog_configs SET version=2,overlay=? WHERE id=1","{\"providers\":{\"openai\":{\"api_key\":\"must-not-be-interpolated\"}}}");catalog.syncNow();
            mvc.perform(get("/api/v1/models/configured-chat").header("Authorization","Bearer "+tokens.get("viewer"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.capabilities.context_window").value(12345));
            org.assertj.core.api.Assertions.assertThat(catalog.appliedVersion()).isEqualTo(1);
        }finally {jdbc.update("DELETE FROM models WHERE id='configured-chat'");jdbc.update("UPDATE model_catalog_configs SET version=3,overlay='{\"providers\":{}}' WHERE id=1");catalog.syncNow();}
    }
}
