package com.tencent.weknora.tenant;

import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.security.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"weknora.jwt-secret=isolated-parser-kv-secret","weknora.bridge.enabled=false",
    "logging.level.root=WARN","debug=false","WEKNORA_TENANT_ENABLE_RBAC=true",
    "SSRF_WHITELIST=127.0.0.1,docreader","SSRF_WHITELIST_EXTRA=","SSRF_DNS_WHITELIST_ONLY=false"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantParserConfigHttpTest {
    static final String PATH = "/api/v1/tenants/kv/parser-engine-config";
    private static final Path DATABASE;
    static { try { DATABASE=Files.createTempFile("parser-kv-java-", ".db"); DATABASE.toFile().deleteOnExit(); }
        catch(Exception ex) { throw new ExceptionInInitializerError(ex); } }
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE + "?busy_timeout=10000");
        p.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
        p.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired DataSource datasource;
    @Autowired JwtCodec jwt;
    @Autowired AuthRepository authRepository;
    @Autowired TenantParserConfigService service;
    private String token;

    @BeforeAll void schema() throws Exception {
        try(var c=datasource.getConnection()) {
            for(String name:List.of("000000_init", "000007_system_admin_and_settings"))
                ScriptUtils.executeSqlScript(c,new FileSystemResource("../migrations/sqlite/"+name+".up.sql"));
        }
    }
    @BeforeEach void identity() {
        for(String table:List.of("tenant_api_keys","audit_logs","auth_tokens","tenant_members","users","tenants","system_settings"))jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO tenants(id,name,business) VALUES(1,'Fixture','test'),(2,'Foreign','test')");
        jdbc.update("INSERT INTO users(id,username,email,password_hash,tenant_id) VALUES('actor','Actor','actor@test.invalid','unused',1)");
        jdbc.update("INSERT INTO tenant_members(user_id,tenant_id,role,status) VALUES('actor',1,'owner','active')");
        token=jwt.sign(Map.of("user_id","actor","tenant_id",1,"type","access","exp",Instant.now().plusSeconds(3600).getEpochSecond()));
        jdbc.update("INSERT INTO auth_tokens(id,user_id,token,token_type,expires_at) VALUES(?,?,?,'access_token',?)",UUID.randomUUID().toString(),"actor",token,authRepository.timestampParameter(Instant.now().plusSeconds(3600)));
    }

    @Test void actualGoHandlerServiceAndSqlitePersistedRowsMatchThroughRealAuthFilter() throws Exception {
        JsonNode cases=json.readTree(getClass().getResourceAsStream("/go-parser-config-kv.json"));
        int count=0;
        for(JsonNode scene:cases) {
            Object old=switch(scene.path("source").asText()) {
                case "blob" -> scene.path("existing").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                case "text" -> scene.path("existing").asText();
                default -> null;
            };
            jdbc.update("UPDATE tenants SET parser_engine_config=?,updated_at='2001-01-01 00:00:00' WHERE id=1",old);
            var request=scene.path("method").asText().equals("GET")?get(PATH):put(PATH).content(scene.path("body").asText()).contentType("application/json");
            var response=mvc.perform(request.header("Authorization","Bearer "+token)).andReturn().getResponse();
            assertEquals(scene.path("status").asInt(),response.getStatus(),scene.path("name").asText());
            assertEquals(scene.path("response"),json.readTree(response.getContentAsByteArray()),scene.path("name").asText()+" body="+scene.path("body"));
            String saved=jdbc.queryForObject("SELECT CAST(parser_engine_config AS TEXT) FROM tenants WHERE id=1",String.class);
            assertEquals(scene.path("persisted"),saved==null?json.nullNode():json.readTree(saved),scene.path("name").asText()+" persisted");
            assertEquals(scene.path("updated").asBoolean(),!"2001-01-01 00:00:00".equals(jdbc.queryForObject("SELECT CAST(updated_at AS TEXT) FROM tenants WHERE id=1",String.class)),scene.path("name").asText()+" timestamp");
            count++;
        }
        assertEquals(1092,count);
    }

    @Test void integrationReadIsAdminOnlyAndPutRoleDenialPrecedesBindingAndDeduplicatesAudit() throws Exception {
        jdbc.update("UPDATE tenant_members SET role='viewer'");
        mvc.perform(auth(get(PATH))).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value(1002))
                .andExpect(jsonPath("$.error.message").value("integration configuration requires admin access"));
        for(int i=0;i<2;i++)mvc.perform(auth(put(PATH).contentType("application/json").content("{")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("Forbidden: insufficient workspace role"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action='rbac.access_denied' AND request_path='/api/v1/tenants/kv/:key'",Integer.class));
        jdbc.update("UPDATE users SET is_system_admin=true");
        mvc.perform(auth(get(PATH))).andExpect(status().isForbidden());
        mvc.perform(auth(put(PATH).contentType("application/json").content("{"))).andExpect(status().isForbidden());
    }

    @Test void machineSettingsCapabilityCanSaveAndReadMaskedSecretsWithForeignTenantIsolation() throws Exception {
        String raw="sk-parser-settings";
        jdbc.update("INSERT INTO tenant_api_keys(tenant_id,name,key_hash,api_key,full_access,capabilities,knowledge_base_ids) VALUES(1,'Settings',?,?,false,'[\"manage_tenant_settings\"]','[\"unrelated\"]')",AuthFilter.hashApiKey(raw),raw);
        mvc.perform(put(PATH).header("X-API-Key",raw).contentType("application/json").content("{\"mineru_api_key\":\"private\",\"mineru_enable_table\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.mineru_api_key").value("***")).andExpect(jsonPath("$.data.mineru_enable_table").value(false));
        mvc.perform(get(PATH).header("X-API-Key",raw)).andExpect(status().isOk()).andExpect(jsonPath("$.data.mineru_api_key").value("***"));
        mvc.perform(put(PATH).header("X-API-Key",raw).contentType("application/json").content("{\"mineru_api_key\":\"***\"}")).andExpect(status().isOk());
        assertEquals("private",json.readTree(jdbc.queryForObject("SELECT CAST(parser_engine_config AS TEXT) FROM tenants WHERE id=1",String.class)).path("mineru_api_key").asText());
        assertNull(jdbc.queryForMap("SELECT parser_engine_config FROM tenants WHERE id=2").get("parser_engine_config"));
        jdbc.update("UPDATE tenant_api_keys SET capabilities='[\"manage_vector_stores\"]'");
        for(var request:List.of(get(PATH),put(PATH).contentType("application/json").content("{")))
            mvc.perform(request.header("X-API-Key",raw)).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("Forbidden: API key scope does not allow this operation"));
    }

    @Test void writeFailureRollsBackAndStaleAuthenticatedRoleCannotUpdate() throws Exception {
        jdbc.execute("CREATE TRIGGER fail_parser_save BEFORE UPDATE OF parser_engine_config ON tenants BEGIN SELECT RAISE(ABORT,'isolated parser save failure'); END");
        try {
            mvc.perform(auth(put(PATH).contentType("application/json").content("{\"mineru_api_key\":\"private\"}")))
                    .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.error.code").value(1007));
            assertNull(jdbc.queryForMap("SELECT parser_engine_config FROM tenants WHERE id=1").get("parser_engine_config"));
        } finally { jdbc.execute("DROP TRIGGER fail_parser_save"); }
        AuthContext stale=new AuthContext("actor",1,"owner",false,null,false,Set.of(),Set.of());
        jdbc.update("UPDATE tenant_members SET role='viewer'");
        assertEquals(403,assertThrows(ApiException.class,()->service.update(stale,"{}".getBytes())).status());
        assertNull(jdbc.queryForMap("SELECT parser_engine_config FROM tenants WHERE id=1").get("parser_engine_config"));
    }
    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request) { return request.header("Authorization","Bearer "+token); }
}
