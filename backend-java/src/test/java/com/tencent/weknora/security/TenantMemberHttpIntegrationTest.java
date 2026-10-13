package com.tencent.weknora.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
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

@SpringBootTest(properties={"weknora.jwt-secret=isolated-member-http-test-secret","weknora.bridge.enabled=false","logging.level.root=WARN","WEKNORA_TENANT_ENABLE_CROSS_TENANT_ACCESS=true"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantMemberHttpIntegrationTest {
    private static final Path DATABASE;
    static{try{DATABASE=Files.createTempFile("weknora-members-",".db");DATABASE.toFile().deleteOnExit();}catch(Exception ex){throw new ExceptionInInitializerError(ex);}}
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties){properties.add("spring.datasource.url",()->"jdbc:sqlite:"+DATABASE+"?busy_timeout=10000");properties.add("spring.datasource.driver-class-name",()->"org.sqlite.JDBC");properties.add("spring.datasource.hikari.maximum-pool-size",()->4);}
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired DataSource datasource;
    @Autowired JwtCodec jwt;
    @Autowired AuthRepository authRepository;
    @Autowired TenantMemberService service;
    private String access,viewer;
    @BeforeAll void schema()throws Exception{try(var connection=datasource.getConnection()){ScriptUtils.executeSqlScript(connection,new FileSystemResource("../migrations/sqlite/000000_init.up.sql"));ScriptUtils.executeSqlScript(connection,new FileSystemResource("../migrations/sqlite/000007_system_admin_and_settings.up.sql"));}}
    @BeforeEach void fixture(){jdbc.update("DELETE FROM tenant_api_keys");jdbc.update("DELETE FROM auth_tokens");jdbc.update("DELETE FROM audit_logs");jdbc.update("DELETE FROM tenant_members");jdbc.update("DELETE FROM users");jdbc.update("DELETE FROM tenants");jdbc.update("DELETE FROM system_settings");
        jdbc.update("INSERT INTO tenants(id,name,business) VALUES(1,'home','test'),(2,'peer','test')");
        jdbc.update("INSERT INTO users(id,username,email,password_hash,tenant_id,preferences) VALUES('u','Owner','u@example.com','unused',1,'{}'),('v','Viewer','v@example.com','unused',1,'{}'),('t','Target','t@example.com','unused',1,'{\"last_active_tenant_id\":1,\"browser_search_instructions\":\"keep\"}'),('b','Other Owner','b@example.com','unused',1,'{}')");
        jdbc.update("INSERT INTO tenant_members(user_id,tenant_id,role,status) VALUES('u',1,'owner','active'),('v',1,'viewer','active'),('u',2,'viewer','active')");
        access=token("u");viewer=token("v");
    }
    @Test void viewerListPaginationEscapedSearchStatusesAndSafeProjectionMatchGo()throws Exception{
        jdbc.update("INSERT INTO tenant_members(user_id,tenant_id,role,status,joined_at) VALUES('t',1,'viewer','suspended','2026-01-02 03:04:05'),('missing-user',1,'contributor','suspended','2026-01-02 03:04:05')");
        jdbc.update("UPDATE users SET username='100%_Target' WHERE id='t'");
        mvc.perform(get("/api/v1/tenants/1/members").header("Authorization","Bearer "+viewer)).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(4)).andExpect(jsonPath("$.data.page").value(1)).andExpect(jsonPath("$.data.page_size").value(20));
        mvc.perform(get("/api/v1/tenants/1/members").param("q","%_").header("Authorization","Bearer "+viewer)).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.members[0].status").value("suspended")).andExpect(jsonPath("$.data.members[0].avatar").doesNotExist());
        mvc.perform(get("/api/v1/tenants/1/members").param("q","T@EXAMPLE.COM").param("page_size","1").header("Authorization","Bearer "+viewer)).andExpect(status().isOk()).andExpect(jsonPath("$.data.members[0].username").value("100%_Target"));
        for(String value:List.of("0","-1","bad","101"))mvc.perform(get("/api/v1/tenants/1/members").param("page_size",value).header("Authorization","Bearer "+viewer)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value(1010));
        var golden=json.readTree(Path.of("src/test/java/com/tencent/weknora/security/member-golden.json").toFile());
        var dangling=jdbc.queryForMap("SELECT * FROM tenant_members WHERE user_id='missing-user'");assertEquals(golden.path("dangling"),json.valueToTree(service.project(dangling,null)));
    }
    @Test void addExistingUserProducesDtoAuditAndDuplicateConflictWithoutChangingHomeTenant()throws Exception{
        jdbc.update("UPDATE users SET tenant_id=2,is_active=false WHERE id='t'");
        var added=add("t@example.com","admin");assertEquals("t",added.path("user_id").asText());assertEquals("admin",added.path("role").asText());assertEquals("active",added.path("status").asText());assertEquals("u",added.path("invited_by").asText());assertFalse(added.has("password_hash"));assertFalse(added.has("created_at"));assertFalse(added.has("avatar"));Instant.parse(added.path("joined_at").asText());
        assertEquals(2,jdbc.queryForObject("SELECT tenant_id FROM users WHERE id='t'",Integer.class));
        var audit=jdbc.queryForMap("SELECT * FROM audit_logs");assertEquals("rbac.member_added",audit.get("action"));assertEquals("u",audit.get("actor_user_id"));assertEquals("owner",audit.get("actor_role"));assertEquals("t",audit.get("target_user_id"));assertEquals("tenant_member",audit.get("target_type"));
        mvc.perform(post("/api/v1/tenants/1/members").header("Authorization","Bearer "+access).contentType("application/json").content("{\"email\":\"t@example.com\",\"role\":\"viewer\"}")).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value(1005));
        mvc.perform(post("/api/v1/tenants/1/members").header("Authorization","Bearer "+access).contentType("application/json").content("{\"email\":\"new@example.com\",\"role\":\"viewer\"}")).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.message").value("user with this email is not registered; ask them to sign up first"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs",Integer.class));
    }
    @Test void roleChangesAuditOnlyActualChangesAndNeverDemoteLastOwner()throws Exception{
        mvc.perform(put("/api/v1/tenants/1/members/v").header("Authorization","Bearer "+access).contentType("application/json").content("{\"role\":\"viewer\"}")).andExpect(status().isOk());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs",Integer.class));
        mvc.perform(put("/api/v1/tenants/1/members/v").header("Authorization","Bearer "+access).contentType("application/json").content("{\"role\":\"admin\"}")).andExpect(status().isOk());
        var golden=json.readTree(Path.of("src/test/java/com/tencent/weknora/security/member-golden.json").toFile());assertEquals(golden.path("role_details"),json.readTree(jdbc.queryForObject("SELECT details FROM audit_logs",String.class)));
        mvc.perform(put("/api/v1/tenants/1/members/u").header("Authorization","Bearer "+access).contentType("application/json").content("{\"role\":\"viewer\"}")).andExpect(status().isConflict()).andExpect(jsonPath("$.error.message").value(golden.path("last_owner_error").asText()));
        mvc.perform(delete("/api/v1/tenants/1/members/u").header("Authorization","Bearer "+access)).andExpect(status().isConflict());
        assertEquals("owner",jdbc.queryForObject("SELECT role FROM tenant_members WHERE user_id='u' AND tenant_id=1",String.class));
    }
    @Test void removalClearsHomePreferenceRevokesAllSessionsAndSoftDeleteCannotReAddOnSQLite()throws Exception{
        add("t@example.com","viewer");String targetToken=token("t");authRepository.storeToken("t","isolated-refresh","refresh_token",Instant.now().plusSeconds(3600));
        mvc.perform(delete("/api/v1/tenants/1/members/t").header("Authorization","Bearer "+access)).andExpect(status().isOk());
        assertNotNull(jdbc.queryForObject("SELECT deleted_at FROM tenant_members WHERE user_id='t' AND tenant_id=1",String.class));assertNull(jdbc.queryForMap("SELECT tenant_id FROM users WHERE id='t'").get("tenant_id"));
        var prefs=json.readTree(jdbc.queryForObject("SELECT preferences FROM users WHERE id='t'",String.class));assertFalse(prefs.has("last_active_tenant_id"));assertEquals("keep",prefs.path("browser_search_instructions").asText());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM auth_tokens WHERE user_id='t' AND is_revoked=false",Integer.class));
        mvc.perform(get("/api/v1/auth/validate").header("Authorization","Bearer "+targetToken)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/tenants/1/members").header("Authorization","Bearer "+access).contentType("application/json").content("{\"email\":\"t@example.com\",\"role\":\"viewer\"}")).andExpect(status().isConflict());
        assertEquals("rbac.member_removed",jdbc.queryForObject("SELECT action FROM audit_logs ORDER BY id DESC LIMIT 1",String.class));
    }
    @Test void selfRemovalNeedsAnotherOwnerAndRecordsVoluntaryLeave()throws Exception{
        add("b@example.com","owner");mvc.perform(delete("/api/v1/tenants/1/members/u").header("Authorization","Bearer "+access)).andExpect(status().isOk());assertEquals("rbac.member_left",jdbc.queryForObject("SELECT action FROM audit_logs ORDER BY id DESC LIMIT 1",String.class));
        mvc.perform(get("/api/v1/tenants/1/members").header("Authorization","Bearer "+access)).andExpect(status().isUnauthorized());
    }
    @Test void memberPrivilegesPathMatchingAndMachineOwnershipBoundaryAreEnforced()throws Exception{
        mvc.perform(post("/api/v1/tenants/1/members").header("Authorization","Bearer "+viewer).contentType("application/json").content("{\"email\":\"t@example.com\",\"role\":\"viewer\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/tenants/2/members").header("Authorization","Bearer "+access)).andExpect(status().isForbidden());
        String machine="sk-members";jdbc.update("INSERT INTO tenant_api_keys(tenant_id,name,key_hash,api_key,capabilities,knowledge_base_ids) VALUES(1,'Members',?,?, '[\"manage_members\"]','[\"irrelevant-kb\"]')",AuthFilter.hashApiKey(machine),machine);
        mvc.perform(get("/api/v1/tenants/1/members").header("X-API-Key",machine)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/tenants/1/members").header("X-API-Key",machine).contentType("application/json").content("{\"email\":\"t@example.com\",\"role\":\"admin\"}")).andExpect(status().isCreated());
        mvc.perform(put("/api/v1/tenants/1/members/t").header("X-API-Key",machine).contentType("application/json").content("{\"role\":\"owner\"}")).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.message").value("API keys cannot assign the owner role"));
        mvc.perform(post("/api/v1/tenants/1/members").header("X-API-Key",machine).contentType("application/json").content("{\"email\":\"b@example.com\",\"role\":\"owner\"}")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/tenants/1/members/u").header("X-API-Key",machine)).andExpect(status().isConflict());
        mvc.perform(get("/api/v1/tenants/2/members").header("X-API-Key",machine)).andExpect(status().isForbidden());
        jdbc.update("UPDATE tenant_api_keys SET capabilities='[\"retrieve\"]'");mvc.perform(get("/api/v1/tenants/1/members").header("X-API-Key",machine)).andExpect(status().isForbidden());
    }
    @Test void auditAndCleanupFailuresRemainBestEffortAfterCommittedRemoval()throws Exception{
        add("t@example.com","viewer");token("t");
        jdbc.execute("CREATE TRIGGER fail_member_audit BEFORE INSERT ON audit_logs BEGIN SELECT RAISE(ABORT,'isolated audit failure'); END");
        jdbc.execute("CREATE TRIGGER fail_member_pointer BEFORE UPDATE OF tenant_id ON users WHEN OLD.id='t' BEGIN SELECT RAISE(ABORT,'isolated cleanup failure'); END");
        try{mvc.perform(delete("/api/v1/tenants/1/members/t").header("Authorization","Bearer "+access)).andExpect(status().isOk());assertNotNull(jdbc.queryForObject("SELECT deleted_at FROM tenant_members WHERE user_id='t'",String.class));assertEquals(1,jdbc.queryForObject("SELECT tenant_id FROM users WHERE id='t'",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM auth_tokens WHERE user_id='t' AND is_revoked=false",Integer.class));}
        finally{jdbc.execute("DROP TRIGGER fail_member_audit");jdbc.execute("DROP TRIGGER fail_member_pointer");}
    }
    @Test void concurrentSelfDemotionsCannotLeaveTenantWithoutOwner()throws Exception{
        add("b@example.com","owner");CountDownLatch start=new CountDownLatch(1);ExecutorService executor=Executors.newFixedThreadPool(2);
        try{List<Future<Integer>> outcomes=new ArrayList<>();for(String owner:List.of("u","b"))outcomes.add(executor.submit(()->{start.await();try{service.update(new AuthContext(owner,1,"owner",false,null,false,Set.of(),Set.of()),"1",owner,Map.of("role","viewer"));return 200;}catch(ApiException ex){return ex.status();}}));start.countDown();List<Integer> codes=new ArrayList<>();for(Future<Integer> result:outcomes)codes.add(result.get(10,TimeUnit.SECONDS));Collections.sort(codes);assertEquals(List.of(200,409),codes);assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM tenant_members WHERE tenant_id=1 AND role='owner' AND status='active' AND deleted_at IS NULL",Integer.class));}
        finally{executor.shutdownNow();}
    }
    @Test void invalidRolesUnknownMembershipAndSuspendedDuplicatesKeepGoErrors()throws Exception{
        mvc.perform(put("/api/v1/tenants/1/members/missing").header("Authorization","Bearer "+access).contentType("application/json").content("{\"role\":\"viewer\"}")).andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/tenants/1/members/v").header("Authorization","Bearer "+access).contentType("application/json").content("{\"role\":\"Owner\"}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value(1010));
        jdbc.update("INSERT INTO tenant_members(user_id,tenant_id,role,status) VALUES('t',1,'viewer','invited')");mvc.perform(post("/api/v1/tenants/1/members").header("Authorization","Bearer "+access).contentType("application/json").content("{\"email\":\"t@example.com\",\"role\":\"viewer\"}")).andExpect(status().isConflict());
    }
    @Test void deniedRoleAuditsUseRouteTemplatesAndDeduplicateResourceProbing()throws Exception{
        for(String target:List.of("u","t","many-probed-users"))mvc.perform(put("/api/v1/tenants/1/members/"+target).header("Authorization","Bearer "+viewer).contentType("application/json").content("{\"role\":\"admin\"}"))
                .andExpect(status().isForbidden()).andExpect(content().json("{\"error\":\"Forbidden: insufficient workspace role\"}"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action='rbac.access_denied'",Integer.class));
        var entry=jdbc.queryForMap("SELECT * FROM audit_logs");assertEquals("/api/v1/tenants/:id/members/:user_id",entry.get("request_path"));assertEquals("PUT",entry.get("request_method"));assertEquals("denied",entry.get("outcome"));
        var details=json.readTree(entry.get("details").toString());assertEquals("owner",details.path("required_role").asText());assertEquals("/api/v1/tenants/1/members/u",details.path("raw_path").asText());
    }
    private String token(String user){String token=jwt.sign(Map.of("user_id",user,"tenant_id",1,"type","access","exp",Instant.now().plusSeconds(3600).getEpochSecond(),"jti",UUID.randomUUID().toString()));authRepository.storeToken(user,token,"access_token",Instant.now().plusSeconds(3600));return token;}
    private JsonNode add(String email,String role)throws Exception{return json.readTree(mvc.perform(post("/api/v1/tenants/1/members").header("Authorization","Bearer "+access).contentType("application/json").content(json.writeValueAsString(Map.of("email",email,"role",role)))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data");}
}
