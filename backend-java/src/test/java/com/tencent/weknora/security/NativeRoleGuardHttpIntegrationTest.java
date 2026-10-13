package com.tencent.weknora.security;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;import java.time.*;import java.util.*;import javax.sql.DataSource;
import org.junit.jupiter.api.*;import org.springframework.beans.factory.annotation.Autowired;import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;import org.springframework.boot.test.context.SpringBootTest;import org.springframework.core.io.FileSystemResource;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.jdbc.datasource.init.ScriptUtils;import org.springframework.test.context.DynamicPropertyRegistry;import org.springframework.test.context.DynamicPropertySource;import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"weknora.jwt-secret=isolated-native-guard-http-secret","weknora.bridge.enabled=false","logging.level.root=WARN","debug=false","FRONTEND_BASE_URL=https://frontend.example///"})
@AutoConfigureMockMvc @TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NativeRoleGuardHttpIntegrationTest {
    private static final Path DATABASE;static{try{DATABASE=Files.createTempFile("weknora-native-guard-http-",".db");DATABASE.toFile().deleteOnExit();}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p){p.add("spring.datasource.url",()->"jdbc:sqlite:"+DATABASE+"?busy_timeout=10000");p.add("spring.datasource.driver-class-name",()->"org.sqlite.JDBC");}
    @Autowired MockMvc mvc;@Autowired JdbcTemplate jdbc;@Autowired DataSource datasource;@Autowired JwtCodec jwt;@Autowired AuthRepository repository;@Autowired ObjectMapper json;
    private String owner,invitee,other;
    @BeforeAll void schema()throws Exception{try(var c=datasource.getConnection()){for(String script:List.of("000000_init.up.sql","000005_messages_attachments_and_invitation_fields.up.sql","000007_system_admin_and_settings.up.sql"))ScriptUtils.executeSqlScript(c,new FileSystemResource("../migrations/sqlite/"+script));}}
    @BeforeEach void reset(){for(String table:List.of("tenant_invitations","audit_logs","auth_tokens","tenant_api_keys","tenant_members","users","tenants","system_settings"))jdbc.update("DELETE FROM "+table);jdbc.update("INSERT INTO tenants(id,name,business) VALUES(7,'Workspace','test'),(8,'Other','test')");jdbc.update("INSERT INTO users(id,username,email,password_hash,tenant_id) VALUES('owner','Owner','owner@example.com','unused',7),('invitee','Invitee','invitee@example.com','unused',NULL),('other','Other','other@example.com','unused',NULL)");jdbc.update("INSERT INTO tenant_members(user_id,tenant_id,role,status) VALUES('owner',7,'owner','active')");owner=token("owner",7);invitee=token("invitee",0);other=token("other",0);}

    @Test void roleDenialPrecedesInvalidJsonAndPersistsGoTemplateDedup()throws Exception{
        jdbc.update("UPDATE tenant_members SET role='viewer' WHERE user_id='owner'");
        mvc.perform(post("/api/v1/tenants/7/invitations").header("Authorization","Bearer "+owner).contentType("application/json").content("{"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("Forbidden: insufficient workspace role")).andExpect(jsonPath("$.success").doesNotExist());
        for(int id:List.of(99,100))mvc.perform(delete("/api/v1/tenants/7/invitations/"+id).header("Authorization","Bearer "+owner)).andExpect(status().isForbidden());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action='rbac.access_denied'",Integer.class));
        var row=jdbc.queryForMap("SELECT * FROM audit_logs WHERE request_path='/api/v1/tenants/:id/invitations/:inv_id'");assertEquals("DELETE",row.get("request_method"));assertEquals("/api/v1/tenants/7/invitations/99",json.readTree(row.get("details").toString()).path("raw_path").asText());
    }
    @Test void parserEngineAdminFloorsPrecedeBindingAndAuditBothExactGoTemplates()throws Exception{
        jdbc.update("UPDATE tenant_members SET role='viewer' WHERE user_id='owner'");
        mvc.perform(get("/api/v1/system/parser-engines").header("Authorization","Bearer "+owner)).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].Name").value("builtin"));
        for(String path:List.of("/api/v1/system/parser-engines/check","/api/v1/system/docreader/reconnect")) {
            mvc.perform(post(path).header("Authorization","Bearer "+owner).contentType("application/json").content("{"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("Forbidden: insufficient workspace role"));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action='rbac.access_denied' AND request_path=?",Integer.class,path));
        }
        jdbc.update("UPDATE users SET is_system_admin=true WHERE id='owner'");
        mvc.perform(post("/api/v1/system/parser-engines/check").header("Authorization","Bearer "+owner).contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
    }
    @Test void parserMachineCapabilityAllowsUnsavedCheckAndBypassesHumanAdminFloor()throws Exception{
        String raw="sk-parser-machine-fixture";jdbc.update("INSERT INTO tenant_api_keys(tenant_id,name,key_hash,api_key,full_access,capabilities) VALUES(7,'Parser',?,?,false,'[\"manage_vector_stores\"]')",AuthFilter.hashApiKey(raw),raw);
        mvc.perform(get("/api/v1/system/parser-engines").header("X-API-Key",raw)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/system/parser-engines/check").header("X-API-Key",raw).contentType("application/json").content("{\"paddleocr_vl_cloud_token\":\"fixture-token\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data[7].Available").value(true));
        assertNull(jdbc.queryForObject("SELECT parser_engine_config FROM tenants WHERE id=7",String.class));
        mvc.perform(post("/api/v1/system/docreader/reconnect").header("X-API-Key",raw).contentType("application/json").content("{}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.msg").value("请提供 addr 参数"));
        jdbc.update("UPDATE tenant_api_keys SET capabilities='[\"retrieve\"]' WHERE api_key=?",raw);
        mvc.perform(get("/api/v1/system/parser-engines").header("X-API-Key",raw)).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("Forbidden: API key scope does not allow this operation"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action='rbac.access_denied'",Integer.class));
    }
    @Test void foreignTenantDenialRemainsBeforeRoleAndWritesNoRoleAudit()throws Exception{
        jdbc.update("UPDATE tenant_members SET role='viewer' WHERE user_id='owner'");
        mvc.perform(post("/api/v1/tenants/8/invitations").header("Authorization","Bearer "+owner).contentType("application/json").content("{"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.message").value("Access denied: URL workspace does not match the active workspace"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs",Integer.class));
    }
    @Test void scopedMachineBypassesRoleLadderAndItsOwnerAssignmentDenialIsNotRoleAudit()throws Exception{
        String raw="sk-native-denial-test";jdbc.update("INSERT INTO tenant_api_keys(tenant_id,name,key_hash,api_key,full_access,capabilities) VALUES(7,'Machine',?,?,false,'[\"manage_members\"]')",AuthFilter.hashApiKey(raw),raw);
        mvc.perform(post("/api/v1/tenants/7/invite-links").header("X-API-Key",raw).contentType("application/json").content("{\"role\":\"owner\"}"))
            .andExpect(status().isForbidden());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action='rbac.access_denied'",Integer.class));
    }
    @Test void deniedAuditInsertFailureKeepsForbiddenResponse()throws Exception{
        jdbc.update("UPDATE tenant_members SET role='viewer' WHERE user_id='owner'");jdbc.execute("CREATE TRIGGER deny_audit_failure BEFORE INSERT ON audit_logs BEGIN SELECT RAISE(ABORT,'isolated denied audit failure'); END");
        try{mvc.perform(post("/api/v1/tenants/7/invite-links").header("Authorization","Bearer "+owner).contentType("application/json").content("{\"role\":\"viewer\"}"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("Forbidden: insufficient workspace role"));}
        finally{jdbc.execute("DROP TRIGGER deny_audit_failure");}
    }
    @Test void modelAndKnowledgeBaseNativeRoleFloorsRunBeforeBodyBinding()throws Exception{
        jdbc.update("UPDATE tenant_members SET role='viewer' WHERE user_id='owner'");
        for(String path:List.of("/api/v1/models","/api/v1/knowledge-bases"))mvc.perform(post(path).header("Authorization","Bearer "+owner).contentType("application/json").content("{"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("Forbidden: insufficient workspace role"));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action='rbac.access_denied'",Integer.class));
    }
    @Test void systemAdministratorBypassesOnlyGoAdminOrSystemAdminRoutes()throws Exception{
        jdbc.update("UPDATE tenant_members SET role='viewer' WHERE user_id='owner'");jdbc.update("UPDATE users SET is_system_admin=true WHERE id='owner'");
        mvc.perform(post("/api/v1/models").header("Authorization","Bearer "+owner).contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/models/missing").header("Authorization","Bearer "+owner).contentType("application/json").content("{\"name\":\"Renamed\"}"))
            .andExpect(status().isNotFound());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action='rbac.access_denied'",Integer.class));
    }
    private String token(String user,long tenant){String value=jwt.sign(Map.of("user_id",user,"email",user+"@example.com","tenant_id",tenant,"type","access","exp",Instant.now().plusSeconds(3600).getEpochSecond()));repository.storeToken(user,value,"access_token",Instant.now().plusSeconds(3600));return value;}
}
