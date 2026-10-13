package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.weknora.security.JwtCodec;
import java.nio.file.*;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
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
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties={"weknora.jwt-secret=isolated-migration-test-secret", "weknora.bridge.enabled=false", "debug=false", "logging.level.root=WARN"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KnowledgeBaseIntegrationTest {
    static final Path DATABASE;
    static {try {DATABASE=Files.createTempFile("weknora-java-test-",".db");DATABASE.toFile().deleteOnExit();}catch(Exception ex){throw new ExceptionInInitializerError(ex);}}
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",() -> "jdbc:sqlite:"+DATABASE);
        properties.add("spring.datasource.driver-class-name",() -> "org.sqlite.JDBC");
        properties.add("spring.datasource.hikari.maximum-pool-size",() -> 1);
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource datasource;
    @Autowired ObjectMapper json;
    @Autowired JwtCodec jwt;
    String token;
    @BeforeAll void initialize() throws Exception {
        try(var connection=datasource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,new FileSystemResource("../migrations/sqlite/000000_init.up.sql"));
            ScriptUtils.executeSqlScript(connection,new FileSystemResource("../migrations/sqlite/000007_system_admin_and_settings.up.sql"));
        }
        jdbc.update("INSERT INTO tenants(id,name,business) VALUES (1,'first','test'),(2,'second','test')");
        jdbc.update("INSERT INTO users(id,username,email,password_hash,tenant_id) VALUES ('alice','Alice','alice@test.invalid','unused',1),('bob','Bob','bob@test.invalid','unused',1)");
        jdbc.update("INSERT INTO tenant_members(user_id,tenant_id,role,status) VALUES ('alice',1,'viewer','active'),('bob',1,'viewer','active')");
        token=jwt.sign(Map.of("user_id","alice","tenant_id",1,"exp",Instant.now().plusSeconds(3600).getEpochSecond()));
        jdbc.update("INSERT INTO auth_tokens(id,user_id,token,token_type,expires_at,is_revoked) VALUES (?,?,?,'access_token','2099-01-01 00:00:00',false)",UUID.randomUUID().toString(),"alice",token);
        String columns="id,name,tenant_id,creator_id,embedding_model_id,summary_model_id,cos_config,vlm_config,created_at";
        jdbc.update("INSERT INTO knowledge_bases("+columns+") VALUES ('mine','Mine',1,'alice','','','{\"secret_key\":\"do-not-leak\"}','{\"api_key\":\"do-not-leak\"}','2026-10-01 00:00:00'),('other','Other',1,'bob','','','{}','{}','2026-10-02 00:00:00'),('foreign','Foreign',2,'bob','','','{}','{}','2026-10-03 00:00:00')");
        jdbc.update("INSERT INTO knowledges(id,tenant_id,knowledge_base_id,type,title,source,parse_status) VALUES ('a',1,'mine','file','A','test','pending'),('b',1,'mine','file','B','test','deleting'),('c',2,'mine','file','C','test','pending')");
    }
    @Test void bcryptLoginRefreshAndLogoutWorkOnExistingSqliteSchema() throws Exception {
        String goHash="$2a$10$7o5s4iXnpIDlSj8XEYredu3mYRpKeW/DUAceshdlbVlUv27eO7bvy";
        jdbc.update("INSERT INTO users(id,username,email,password_hash,tenant_id) VALUES ('login-user','Login','login@test.invalid',?,1)",goHash);
        jdbc.update("INSERT INTO tenant_members(user_id,tenant_id,role,status) VALUES ('login-user',1,'viewer','active')");
        var login=mvc.perform(post("/api/v1/auth/login").contentType("application/json").content("{\"email\":\"login@test.invalid\",\"password\":\"password\"}"))
            .andExpect(status().isOk()).andReturn();
        var credentials=json.readTree(login.getResponse().getContentAsString());
        String access=credentials.path("token").asText(),refresh=credentials.path("refresh_token").asText();
        mvc.perform(get("/api/v1/knowledge-bases").header("Authorization","Bearer "+access)).andExpect(status().isOk());
        var rotation=mvc.perform(post("/api/v1/auth/refresh").contentType("application/json").content(json.writeValueAsString(Map.of("refreshToken",refresh))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.access_token").isString()).andReturn();
        String rotated=json.readTree(rotation.getResponse().getContentAsString()).path("access_token").asText();
        mvc.perform(post("/api/v1/auth/refresh").contentType("application/json").content(json.writeValueAsString(Map.of("refreshToken",refresh)))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/logout").header("Authorization","Bearer "+rotated)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/knowledge-bases").header("Authorization","Bearer "+access)).andExpect(status().isUnauthorized());
    }
    @Test void publicHealthAndUnauthenticatedRejection() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk()).andExpect(content().json("{\"status\":\"ok\"}"));
        mvc.perform(get("/api/v1/knowledge-bases")).andExpect(status().isUnauthorized());
    }
    @Test void tenantIsolationAndRedactedOldSchema() throws Exception {
        mvc.perform(get("/api/v1/knowledge-bases/mine").header("Authorization","Bearer "+token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.knowledge_count").value(1))
            .andExpect(jsonPath("$.data.processing_count").value(1)).andExpect(jsonPath("$.data.is_processing").value(true))
            .andExpect(jsonPath("$.data.storage_config.secret_key").value(""))
            .andExpect(jsonPath("$.data.vlm_config.api_key").value(""))
            .andExpect(jsonPath("$.data.deleted_at").isEmpty());
        // Go's KBAccessRead resolves an existing foreign KB, then reports missing share permission.
        mvc.perform(get("/api/v1/knowledge-bases/foreign").header("Authorization","Bearer "+token))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value(1002))
            .andExpect(jsonPath("$.error.message").value("Permission denied to access this knowledge base"));
        mvc.perform(get("/api/v1/knowledge-bases").header("Authorization","Bearer "+token).header("X-Tenant-ID","2")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/knowledge-bases").header("Authorization","Bearer "+token).param("creator","mine"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].id").value("mine"));
    }
    @Test void viewerPinsOnlyTheirOwnPreferenceAndRetainsOrdering() throws Exception {
        mvc.perform(put("/api/v1/knowledge-bases/mine/pin").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.is_pinned").value(true));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_kb_pins WHERE user_id='alice' AND kb_id='mine'",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_kb_pins WHERE user_id='bob'",Long.class)).isZero();
        mvc.perform(get("/api/v1/knowledge-bases").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value("mine"));
        mvc.perform(put("/api/v1/knowledge-bases/mine/pin").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.is_pinned").value(false));
        mvc.perform(put("/api/v1/knowledge-bases/foreign/pin").header("Authorization","Bearer "+token)).andExpect(status().isNotFound());
    }
    @Test void rejectsTamperingAndRevokedMembership() throws Exception {
        String revoked=jwt.sign(Map.of("user_id","bob","tenant_id",1,"exp",Instant.now().plusSeconds(3600).getEpochSecond()));
        jdbc.update("INSERT INTO auth_tokens(id,user_id,token,token_type,expires_at,is_revoked) VALUES (?,?,?,'access_token','2099-01-01 00:00:00',false)",UUID.randomUUID().toString(),"bob",revoked);
        jdbc.update("UPDATE tenant_members SET status='inactive' WHERE user_id='bob'");
        mvc.perform(get("/api/v1/knowledge-bases").header("Authorization","Bearer "+revoked)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/knowledge-bases").header("Authorization","Bearer "+token.substring(0,token.length()-1)+"x")).andExpect(status().isUnauthorized());
    }
}
