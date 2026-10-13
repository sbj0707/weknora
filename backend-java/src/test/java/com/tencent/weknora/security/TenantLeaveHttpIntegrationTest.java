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

@SpringBootTest(properties={"weknora.jwt-secret=isolated-leave-http-secret","weknora.bridge.enabled=false","logging.level.root=WARN","debug=false","WEKNORA_TENANT_ENABLE_CROSS_TENANT_ACCESS=true"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantLeaveHttpIntegrationTest {
    private static final Path DATABASE;
    static{try{DATABASE=Files.createTempFile("weknora-leave-",".db");DATABASE.toFile().deleteOnExit();}catch(Exception ex){throw new ExceptionInInitializerError(ex);}}
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
    @Test void viewerLeaveMatchesActualGoAndClearsHomePreferenceRevokesAllTokens()throws Exception{
        jdbc.update("UPDATE users SET preferences='{\"last_active_tenant_id\":1,\"browser_search_instructions\":\"keep\"}' WHERE id='v'");
        String response=mvc.perform(post("/api/v1/tenants/1/leave").header("Authorization","Bearer "+viewer)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(golden().path("viewer").path("body"),json.readTree(response));
        assertEquals(golden().path("viewer").path("audit"),json.valueToTree(jdbc.queryForMap("SELECT actor_user_id,actor_role,action,target_type,target_user_id,outcome FROM audit_logs")));
        assertNull(jdbc.queryForObject("SELECT tenant_id FROM users WHERE id='v'",Integer.class));
        var prefs=json.readTree(jdbc.queryForObject("SELECT preferences FROM users WHERE id='v'",String.class));assertFalse(prefs.has("last_active_tenant_id"));assertEquals("keep",prefs.path("browser_search_instructions").asText());
        assertTrue(jdbc.queryForObject("SELECT is_revoked FROM auth_tokens WHERE user_id='v'",Boolean.class));
        assertNotNull(jdbc.queryForObject("SELECT deleted_at FROM tenant_members WHERE user_id='v'",String.class));
        mvc.perform(get("/api/v1/tenants").header("Authorization","Bearer "+viewer)).andExpect(status().isUnauthorized());
    }
    @Test void lastOwnerCannotLeaveAndOtherOwnerEnablesLeave()throws Exception{
        String denied=mvc.perform(post("/api/v1/tenants/1/leave").header("Authorization","Bearer "+access)).andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();assertEquals(golden().path("last_owner").path("body"),json.readTree(denied));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs",Integer.class));assertFalse(jdbc.queryForObject("SELECT is_revoked FROM auth_tokens WHERE user_id='u'",Boolean.class));
        jdbc.update("INSERT INTO tenant_members(user_id,tenant_id,role,status) VALUES('b',1,'owner','active')");
        var reply=mvc.perform(post("/api/v1/tenants/1/leave").header("Authorization","Bearer "+access)).andExpect(status().isOk()).andReturn().getResponse();assertEquals(golden().path("other_owner").path("body"),json.readTree(reply.getContentAsString()));assertEquals(golden().path("other_owner").path("audit"),json.valueToTree(jdbc.queryForMap("SELECT actor_user_id,actor_role,action,target_type,target_user_id,outcome FROM audit_logs")));assertEquals("owner",authRepository.role("b",1));
    }
    @Test void foreignPathMachineKeysAnonymousAndWrongMethodsCannotLeave()throws Exception{
        mvc.perform(post("/api/v1/tenants/2/leave").header("Authorization","Bearer "+viewer)).andExpect(status().isForbidden());
        String raw="sk-leave-isolated";jdbc.update("INSERT INTO tenant_api_keys(tenant_id,name,key_hash,api_key,full_access) VALUES(1,'Machine',?,?,true)",AuthFilter.hashApiKey(raw),raw);mvc.perform(post("/api/v1/tenants/1/leave").header("X-API-Key",raw)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/tenants/1/leave")).andExpect(status().isUnauthorized());mvc.perform(get("/api/v1/tenants/1/leave").header("Authorization","Bearer "+viewer)).andExpect(status().isMethodNotAllowed());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM tenant_members WHERE deleted_at IS NOT NULL",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs",Integer.class));
    }
    @Test void membershipFailureRollsBackAndAuditCleanupFailuresPreserveCommittedLeave()throws Exception{
        jdbc.execute("CREATE TRIGGER leave_fail BEFORE UPDATE OF deleted_at ON tenant_members BEGIN SELECT RAISE(ABORT,'isolated leave failure'); END");try{mvc.perform(post("/api/v1/tenants/1/leave").header("Authorization","Bearer "+viewer)).andExpect(status().isInternalServerError());assertNull(jdbc.queryForObject("SELECT deleted_at FROM tenant_members WHERE user_id='v'",String.class));assertFalse(jdbc.queryForObject("SELECT is_revoked FROM auth_tokens WHERE user_id='v'",Boolean.class));}finally{jdbc.execute("DROP TRIGGER leave_fail");}
        jdbc.execute("CREATE TRIGGER leave_audit_fail BEFORE INSERT ON audit_logs BEGIN SELECT RAISE(ABORT,'isolated audit failure'); END");jdbc.execute("CREATE TRIGGER leave_cleanup_fail BEFORE UPDATE OF tenant_id ON users BEGIN SELECT RAISE(ABORT,'isolated cleanup failure'); END");try{mvc.perform(post("/api/v1/tenants/1/leave").header("Authorization","Bearer "+viewer)).andExpect(status().isOk());assertNotNull(jdbc.queryForObject("SELECT deleted_at FROM tenant_members WHERE user_id='v'",String.class));assertEquals(1,jdbc.queryForObject("SELECT tenant_id FROM users WHERE id='v'",Integer.class));assertTrue(jdbc.queryForObject("SELECT is_revoked FROM auth_tokens WHERE user_id='v'",Boolean.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs",Integer.class));}finally{jdbc.execute("DROP TRIGGER leave_cleanup_fail");jdbc.execute("DROP TRIGGER leave_audit_fail");}
    }
    @Test void concurrentOwnersCannotBothLeave()throws Exception{
        jdbc.update("INSERT INTO tenant_members(user_id,tenant_id,role,status) VALUES('b',1,'owner','active')");String second=token("b");ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch gate=new CountDownLatch(1);try{var a=pool.submit(()->{gate.await();return mvc.perform(post("/api/v1/tenants/1/leave").header("Authorization","Bearer "+access)).andReturn().getResponse().getStatus();});var b=pool.submit(()->{gate.await();return mvc.perform(post("/api/v1/tenants/1/leave").header("Authorization","Bearer "+second)).andReturn().getResponse().getStatus();});gate.countDown();assertEquals(List.of(200,409),List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS)).stream().sorted().toList());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM tenant_members WHERE role='owner' AND deleted_at IS NULL",Integer.class));}finally{pool.shutdownNow();}
    }
    @Test void tenantlessMissingCallerAndAbsentMembershipPreserveActualGoServiceErrors()throws Exception{
        for(String mode:List.of("missing","no_caller")){
            AuthContext context=new AuthContext(mode.equals("missing")?"not-a-member":"",1,"admin",false,null,false,Set.of(),Set.of(),false,false);
            ApiException error=assertThrows(ApiException.class,()->service.leave(context,"1"));
            assertEquals(golden().path(mode).path("status").asInt(),error.status());assertEquals(golden().path(mode).path("body").path("error").path("message").asText(),error.getMessage());assertEquals(golden().path(mode).path("body").path("error").path("code").asInt(),error.appCode());
        }
    }
    private JsonNode golden()throws Exception{return json.readTree(Path.of("src/test/java/com/tencent/weknora/security/leave-golden.json").toFile());}
    private String token(String user){String value=jwt.sign(Map.of("user_id",user,"email",user+"@example.com","tenant_id",1,"type","access","exp",Instant.now().plusSeconds(3600).getEpochSecond()));authRepository.storeToken(user,value,"access_token",Instant.now().plusSeconds(3600));return value;}
}
