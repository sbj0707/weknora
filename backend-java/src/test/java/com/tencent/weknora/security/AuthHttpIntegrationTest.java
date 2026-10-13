package com.tencent.weknora.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"weknora.jwt-secret=isolated-auth-http-test-secret","weknora.bridge.enabled=false","logging.level.root=WARN","STORAGE_TYPE=local","LOCAL_STORAGE_PATH_PREFIX=/isolated/storage","DISABLE_REGISTRATION=false"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthHttpIntegrationTest {
    private static final Path DATABASE;
    static { try { DATABASE=Files.createTempFile("weknora-auth-http-",".db");DATABASE.toFile().deleteOnExit(); } catch(Exception ex){throw new ExceptionInInitializerError(ex);} }
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",()->"jdbc:sqlite:"+DATABASE);
        properties.add("spring.datasource.driver-class-name",()->"org.sqlite.JDBC");
        properties.add("spring.datasource.hikari.maximum-pool-size",()->1);
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired DataSource datasource;
    @Autowired JwtCodec jwt;
    private String access,refresh;
    @BeforeAll void schema() throws Exception {
        try(var connection=datasource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,new FileSystemResource("../migrations/sqlite/000000_init.up.sql"));
            ScriptUtils.executeSqlScript(connection,new FileSystemResource("../migrations/sqlite/000007_system_admin_and_settings.up.sql"));
        }
    }
    @BeforeEach void fixture() throws Exception {
        jdbc.update("DELETE FROM auth_tokens");jdbc.update("DELETE FROM tenant_members");jdbc.update("DELETE FROM users");jdbc.update("DELETE FROM storage_backends");jdbc.update("DELETE FROM tenants");jdbc.update("DELETE FROM system_settings");
        jdbc.update("INSERT INTO tenants(id,name,business) VALUES(1,'home','test'),(2,'peer','test')");
        jdbc.update("INSERT INTO users(id,username,email,password_hash,tenant_id) VALUES('u','Go User','u@example.com',?,1)","$2a$10$7o5s4iXnpIDlSj8XEYredu3mYRpKeW/DUAceshdlbVlUv27eO7bvy");
        jdbc.update("INSERT INTO tenant_members(user_id,tenant_id,role,status) VALUES('u',1,'contributor','active'),('u',2,'viewer','active')");
        var response=mvc.perform(post("/api/v1/auth/login").contentType("application/json").content("{\"email\":\"u@example.com\",\"password\":\"password\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.avatar").value(""))
                .andExpect(jsonPath("$.user.is_active").value(true)).andReturn();
        var tokens=json.readTree(response.getResponse().getContentAsString());access=tokens.path("token").asText();refresh=tokens.path("refresh_token").asText();
    }
    @Test void nativePreferencesValidateAndWorkspaceSwitchPreserveFrontendContracts() throws Exception {
        mvc.perform(get("/api/v1/auth/validate").header("Authorization","Bearer "+access)).andExpect(status().isOk()).andExpect(jsonPath("$.message").value("Token is valid"));
        mvc.perform(put("/api/v1/auth/me/preferences").header("Authorization","Bearer "+access).contentType("application/json")
                .content("{\"browser_search_instructions\":\" 你好 🌏 \",\"last_active_tenant_id\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.browser_search_instructions").value("你好 🌏")).andExpect(jsonPath("$.data.last_active_tenant_id").doesNotExist());
        var switched=mvc.perform(post("/api/v1/auth/switch-tenant").header("Authorization","Bearer "+access).contentType("application/json")
                .content(json.writeValueAsString(Map.of("tenant_id",2,"refresh_token",refresh))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("Workspace switched"))
                .andExpect(jsonPath("$.active_tenant.id").value(2)).andReturn();
        var tokens=json.readTree(switched.getResponse().getContentAsString());String current=tokens.path("token").asText();
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+current)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenant.id").value(2)).andExpect(jsonPath("$.data.user.preferences.last_active_tenant_id").value(2))
                .andExpect(jsonPath("$.data.preference_defaults.browser_search_instructions").value(AuthPreferences.DEFAULT_BROWSER_SEARCH));
        mvc.perform(post("/api/v1/auth/refresh").contentType("application/json").content(json.writeValueAsString(Map.of("refreshToken",refresh)))).andExpect(status().isUnauthorized());
        var rotated=mvc.perform(post("/api/v1/auth/refresh").contentType("application/json").content(json.writeValueAsString(Map.of("refreshToken",tokens.path("refresh_token").asText()))))
                .andExpect(status().isOk()).andReturn();
        String fresh=json.readTree(rotated.getResponse().getContentAsString()).path("access_token").asText();
        assertEquals(2L,((Number)jwt.verifyAccess(fresh).get("tenant_id")).longValue());
    }
    @Test void passwordDetailsRuntimePolicyAndSessionRevocationWorkOnGoSchema() throws Exception {
        jdbc.update("INSERT INTO system_settings(key,value,value_type,category,last_modified_by) VALUES('auth.complex_password_enabled','true','bool','auth','admin')");
        jdbc.update("UPDATE users SET preferences='{\"oidc_only_login\":true}' WHERE id='u'");
        mvc.perform(post("/api/v1/auth/change-password").header("Authorization","Bearer "+access).contentType("application/json")
                .content("{\"old_password\":\"wrong\",\"new_password\":\"tiny\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value(1000)).andExpect(jsonPath("$.error.details").value("invalid_old_password"));
        mvc.perform(post("/api/v1/auth/change-password").header("Authorization","Bearer "+access).contentType("application/json")
                .content("{\"old_password\":\"password\",\"new_password\":\"password\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value(1010)).andExpect(jsonPath("$.error.details").value("same_password"));
        mvc.perform(post("/api/v1/auth/change-password").header("Authorization","Bearer "+access).contentType("application/json")
                .content("{\"old_password\":\"password\",\"new_password\":\"password1\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value(1010)).andExpect(jsonPath("$.error.details").value("password_policy"));
        mvc.perform(post("/api/v1/auth/change-password").header("Authorization","Bearer "+access).contentType("application/json")
                .content("{\"old_password\":\"password\",\"new_password\":\"NewSecure9!\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("Password changed successfully"));
        assertEquals(false,json.readTree(jdbc.queryForObject("SELECT preferences FROM users WHERE id='u'",String.class)).path("oidc_only_login").asBoolean());
        mvc.perform(get("/api/v1/auth/validate").header("Authorization","Bearer "+access)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/refresh").contentType("application/json").content(json.writeValueAsString(Map.of("refreshToken",refresh)))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/login").contentType("application/json").content("{\"email\":\"u@example.com\",\"password\":\"password\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.success").value(false)).andExpect(jsonPath("$.message").value("Invalid email or password"));
        mvc.perform(post("/api/v1/auth/login").contentType("application/json").content("{\"email\":\"u@example.com\",\"password\":\"NewSecure9!\"}"))
                .andExpect(status().isOk());
    }
    @Test void meCapabilitiesAndNullPreferencesFollowRuntimeDatabaseSettings() throws Exception {
        jdbc.update("INSERT INTO system_settings(key,value,value_type,category,last_modified_by) VALUES('tenant.self_service_creation_enabled','false','bool','tenant','admin'),('tenant.auto_accept_invitation','true','bool','tenant','admin')");
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+access)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.capabilities.can_create_tenant").value(false)).andExpect(jsonPath("$.data.capabilities.auto_accept_invitation").value(true));
        mvc.perform(put("/api/v1/auth/me/preferences").header("Authorization","Bearer "+access).contentType("application/json").content("{\"browser_search_instructions\":null,\"last_active_tenant_id\":null}"))
                .andExpect(status().isOk()).andExpect(content().json("{\"success\":true,\"data\":{}}"));
        mvc.perform(put("/api/v1/auth/me/preferences").header("Authorization","Bearer "+access).contentType("application/json").content("{\"last_active_tenant_id\":-1}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value(1010));
    }
    @Test void publicRegistrationProvisionsCompletePersonalWorkspaceAndIgnoresClientMode() throws Exception {
        mvc.perform(get("/api/v1/auth/config")).andExpect(status().isOk()).andExpect(jsonPath("$.registration_mode").value("self_serve"));
        var result=mvc.perform(post("/api/v1/auth/register").contentType("application/json")
                .content("{\"username\":\"New User\",\"email\":\"new@example.com\",\"password\":\"NewSecret9\\n\",\"tenant_provisioning\":\"tenantless\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.message").value("Registration successful"))
                .andExpect(jsonPath("$.user.is_active").value(true)).andExpect(jsonPath("$.user.preferences").isMap())
                .andExpect(jsonPath("$.token").doesNotExist()).andExpect(jsonPath("$.tenant").doesNotExist()).andReturn();
        var user=json.readTree(result.getResponse().getContentAsString()).path("user");long tenant=user.path("tenant_id").asLong();assertTrue(tenant>2);
        var backend=jdbc.queryForMap("SELECT * FROM storage_backends WHERE tenant_id=?",tenant);
        assertEquals("local",backend.get("provider"));assertEquals("env",backend.get("source"));assertEquals(1,((Number)backend.get("legacy_alias")).intValue());
        assertEquals("/isolated/storage",json.readTree(backend.get("config").toString()).path("path_prefix").asText());
        assertEquals(backend.get("id"),jdbc.queryForObject("SELECT default_storage_backend_id FROM tenants WHERE id=?",String.class,tenant));
        assertEquals("owner",jdbc.queryForObject("SELECT role FROM tenant_members WHERE user_id=?",String.class,user.path("id").asText()));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM auth_tokens WHERE user_id=?",Integer.class,user.path("id").asText()));
        mvc.perform(post("/api/v1/auth/login").contentType("application/json").content("{\"email\":\"new@example.com\",\"password\":\"NewSecret9\\n\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active_tenant.id").value(tenant));
        mvc.perform(post("/api/v1/auth/register").contentType("application/json").content("{\"username\":\"Duplicate\",\"email\":\"new@example.com\",\"password\":\"NewSecret9\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value(1000)).andExpect(jsonPath("$.error.message").value("user with this email already exists"));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM tenants",Integer.class));
    }
    @Test void runtimeGateTenantlessModeAndComplexPolicyApplyToPublicRoutes() throws Exception {
        setting("auth.registration_mode","\"invite_only\"","string");
        mvc.perform(get("/api/v1/auth/config")).andExpect(status().isOk()).andExpect(jsonPath("$.registration_mode").value("invite_only"));
        String body="{\"username\":\"Tenantless\",\"email\":\"free@example.com\",\"password\":\"NewSecret9\"}";
        mvc.perform(post("/api/v1/auth/register").contentType("application/json").content(body)).andExpect(status().isForbidden());
        setting("auth.registration_mode","\"self_serve\"","string");setting("auth.default_tenant_mode","\"tenantless\"","string");
        setting("auth.complex_password_enabled","true","bool");
        mvc.perform(get("/api/v1/auth/config")).andExpect(status().isOk()).andExpect(jsonPath("$.complex_password_enabled").value(true));
        mvc.perform(post("/api/v1/auth/register").contentType("application/json").content(body)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(1010)).andExpect(jsonPath("$.error.message").value("password must be 8-32 characters and must contain uppercase and lowercase letters, numbers, and special characters"));
        mvc.perform(post("/api/v1/auth/register").contentType("application/json").content(body.replace("NewSecret9","NewSecret9!")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.user.tenant_id").value(0));
        assertNull(jdbc.queryForMap("SELECT tenant_id FROM users WHERE email='free@example.com'").get("tenant_id"));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM tenants",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM storage_backends",Integer.class));
        var login=mvc.perform(post("/api/v1/auth/login").contentType("application/json").content("{\"email\":\"free@example.com\",\"password\":\"NewSecret9!\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active_tenant").doesNotExist()).andReturn();
        String token=json.readTree(login.getResponse().getContentAsString()).path("token").asText();
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.tenant_required").value(true));
    }
    @Test void ownerCreationFailureRollsBackUserTenantAndStorageTogether() throws Exception {
        jdbc.execute("CREATE TRIGGER reject_new_owner BEFORE INSERT ON tenant_members WHEN NEW.role='owner' BEGIN SELECT RAISE(ABORT,'isolated owner insertion failure'); END");
        try {
            mvc.perform(post("/api/v1/auth/register").contentType("application/json").content("{\"username\":\"Rollback\",\"email\":\"rollback@example.com\",\"password\":\"NewSecret9\"}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value(1000));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM users",Integer.class));assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM tenants",Integer.class));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM storage_backends",Integer.class));
        }finally{jdbc.execute("DROP TRIGGER reject_new_owner");}
    }
    private void setting(String key,String value,String type){jdbc.update("INSERT INTO system_settings(key,value,value_type,category,last_modified_by) VALUES(?,?,?,'auth','admin') ON CONFLICT(key) DO UPDATE SET value=excluded.value",key,value,type);}
}
