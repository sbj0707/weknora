package com.tencent.weknora.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"weknora.jwt-secret=isolated-orphan-http-secret","weknora.bridge.enabled=false","logging.level.root=WARN","debug=false","WEKNORA_TENANT_ENABLE_CROSS_TENANT_ACCESS=true"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrphanMembershipHttpIntegrationTest {
    private static final Path DATABASE;
    static{try{DATABASE=Files.createTempFile("weknora-orphan-",".db");DATABASE.toFile().deleteOnExit();}catch(Exception ex){throw new ExceptionInInitializerError(ex);}}
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties){properties.add("spring.datasource.url",()->"jdbc:sqlite:"+DATABASE+"?busy_timeout=10000");properties.add("spring.datasource.driver-class-name",()->"org.sqlite.JDBC");properties.add("spring.datasource.hikari.maximum-pool-size",()->4);}
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired DataSource datasource;
    @Autowired JwtCodec jwt;
    @Autowired AuthRepository repository;
    @Autowired TenantMembershipRecovery recovery;
    private String access;
    @BeforeAll void schema()throws Exception{try(var connection=datasource.getConnection()){for(String script:List.of("000000_init.up.sql","000007_system_admin_and_settings.up.sql","000011_principal_model.up.sql"))ScriptUtils.executeSqlScript(connection,new FileSystemResource("../migrations/sqlite/"+script));}}
    @BeforeEach void fixtures(){for(String table:List.of("tenant_api_keys","auth_tokens","audit_logs","tenant_members","users","tenants","system_settings"))jdbc.update("DELETE FROM "+table);jdbc.update("INSERT INTO tenants(id,name,business) VALUES(1,'home','test'),(2,'foreign','test')");jdbc.update("INSERT INTO users(id,username,email,password_hash,tenant_id,preferences) VALUES('registrant','Registrant','u@example.com','unused',1,'{}'),('other','Other','other@example.com','unused',1,'{}')");access=token("registrant",1);}
    @Test void homeOrphanHealsOwnerAndActualGoAuditShapeBeforeSessionAttachment()throws Exception{
        JsonNode expected=golden();mvc.perform(get("/api/v1/tenants").header("Authorization","Bearer "+access)).andExpect(status().isOk());Map<String,Object> row=jdbc.queryForMap("SELECT role,status,invited_by FROM tenant_members");assertEquals(expected.path("recovered"),json.valueToTree(row));assertEquals(expected.path("audit"),json.valueToTree(jdbc.queryForMap("SELECT actor_user_id,actor_role,action,target_type,target_user_id,outcome FROM audit_logs")));
        mvc.perform(get("/api/v1/tenants/1/members").header("Authorization","Bearer "+access)).andExpect(status().isOk()).andExpect(jsonPath("$.data.members[0].role").value("owner"));assertEquals(1,count("audit_logs"));assertEquals(1,count("tenant_members"));
    }
    @Test void activeMembershipWinsAndAnyActiveMemberClosesRecoveryIncludingDanglingUsers()throws Exception{
        jdbc.update("INSERT INTO tenant_members(user_id,tenant_id,role,status) VALUES('registrant',1,'viewer','active')");mvc.perform(get("/api/v1/tenants").header("Authorization","Bearer "+access)).andExpect(status().isOk());assertEquals("viewer",repository.role("registrant",1));assertEquals(0,count("audit_logs"));
        jdbc.update("DELETE FROM tenant_members");jdbc.update("INSERT INTO tenant_members(user_id,tenant_id,role,status) VALUES('dangling',1,'owner','active')");mvc.perform(get("/api/v1/tenants").header("Authorization","Bearer "+access)).andExpect(status().isForbidden());assertNull(repository.role("registrant",1));assertEquals(1,count("tenant_members"));
    }
    @Test void suspendedAndInvitedOwnRowsNeverReactivateDespiteNoActiveMembers()throws Exception{
        assertFalse(golden().path("suspended_counts_as_active").asBoolean());assertEquals(golden().path("duplicate_error").asText(),golden().path("suspended_blocks_add").asText());
        for(String state:List.of("suspended","invited")){jdbc.update("DELETE FROM tenant_members");jdbc.update("INSERT INTO tenant_members(user_id,tenant_id,role,status) VALUES('registrant',1,'owner',?)",state);mvc.perform(get("/api/v1/tenants").header("Authorization","Bearer "+access)).andExpect(status().isForbidden());assertEquals(state,jdbc.queryForObject("SELECT status FROM tenant_members",String.class));assertEquals(0,count("audit_logs"));}
    }
    @Test void suspendedOthersDoNotPreventHomeRepairAndSoftDeletedUniqueConflictsFailClosed()throws Exception{
        jdbc.update("INSERT INTO tenant_members(user_id,tenant_id,role,status) VALUES('other',1,'viewer','suspended')");mvc.perform(get("/api/v1/tenants").header("Authorization","Bearer "+access)).andExpect(status().isOk());assertEquals("owner",repository.role("registrant",1));
        jdbc.update("DELETE FROM tenant_members");jdbc.update("DELETE FROM audit_logs");jdbc.update("INSERT INTO tenant_members(user_id,tenant_id,role,status,deleted_at) VALUES('registrant',1,'owner','active',CURRENT_TIMESTAMP)");mvc.perform(get("/api/v1/tenants").header("Authorization","Bearer "+access)).andExpect(status().isForbidden());assertNull(repository.role("registrant",1));assertEquals(0,count("audit_logs"));
    }
    @Test void foreignClaimsAndHeadersNeverClaimOwnershipButExplicitHomeHeaderCanHeal()throws Exception{
        String foreign=token("registrant",2);mvc.perform(get("/api/v1/tenants").header("Authorization","Bearer "+foreign)).andExpect(status().isForbidden());mvc.perform(get("/api/v1/tenants").header("Authorization","Bearer "+access).header("X-Tenant-ID","2")).andExpect(status().isForbidden());assertEquals(0,count("tenant_members"));
        mvc.perform(get("/api/v1/tenants").header("Authorization","Bearer "+foreign).header("X-Tenant-ID","1")).andExpect(status().isOk());assertEquals("owner",repository.role("registrant",1));assertNull(repository.role("registrant",2));
    }
    @Test void crossTenantSuperuserGetsTemporaryAdminAndDoesNotClaimForeignOrphan()throws Exception{
        jdbc.update("UPDATE users SET can_access_all_tenants=true WHERE id='registrant'");mvc.perform(get("/api/v1/tenants").header("Authorization","Bearer "+token("registrant",2))).andExpect(status().isOk());assertEquals(0,count("tenant_members"));assertEquals(0,count("audit_logs"));
        mvc.perform(get("/api/v1/tenants").header("Authorization","Bearer "+access)).andExpect(status().isOk());assertEquals("owner",repository.role("registrant",1));
    }
    @Test void inactiveDeletedUsersOrWorkspacesAndRevokedTokensCannotTriggerRepair()throws Exception{
        jdbc.update("UPDATE tenants SET status='inactive' WHERE id=1");assertUnauthorized();jdbc.update("UPDATE tenants SET status='active',deleted_at=CURRENT_TIMESTAMP WHERE id=1");assertUnauthorized();jdbc.update("UPDATE tenants SET deleted_at=NULL WHERE id=1");jdbc.update("UPDATE users SET is_active=false WHERE id='registrant'");assertUnauthorized();jdbc.update("UPDATE users SET is_active=true,deleted_at=CURRENT_TIMESTAMP WHERE id='registrant'");assertUnauthorized();jdbc.update("UPDATE users SET deleted_at=NULL WHERE id='registrant'");repository.revokeUserTokens("registrant");assertUnauthorized();assertEquals(0,count("tenant_members"));assertEquals(0,count("audit_logs"));
    }
    @Test void machineKeysAndExternalPrincipalsNeverEnterJwtRecovery()throws Exception{
        String raw="sk-orphan";jdbc.update("INSERT INTO tenant_api_keys(tenant_id,name,key_hash,api_key,full_access) VALUES(1,'Machine',?,?,true)",AuthFilter.hashApiKey(raw),raw);mvc.perform(get("/api/v1/tenants").header("X-API-Key",raw)).andExpect(status().isOk());assertEquals(0,count("tenant_members"));jdbc.update("UPDATE tenants SET api_principal_config='{\"mode\":\"direct_header\",\"direct_header_name\":\"X-User-ID\"}'");mvc.perform(get("/api/v1/tenants").header("X-API-Key",raw).header("X-User-ID","registrant")).andExpect(status().isForbidden());assertEquals(0,count("tenant_members"));
    }
    @Test void concurrentSameUserRecoveryIsIdempotentAndDifferentRegistrantsCannotBothClaim()throws Exception{
        List<Integer> same=parallel(access,access);assertEquals(List.of(200,200),same.stream().sorted().toList());assertEquals(1,count("tenant_members"));assertEquals(1,count("audit_logs"));jdbc.update("DELETE FROM tenant_members");jdbc.update("DELETE FROM audit_logs");List<Integer> different=parallel(access,token("other",1));assertEquals(List.of(200,403),different.stream().sorted().toList());assertEquals(1,count("tenant_members"));assertEquals(1,count("audit_logs"));
    }
    @Test void insertFailureRollsBackAndAuditFailureDoesNotUndoSuccessfulRepair()throws Exception{
        jdbc.execute("CREATE TRIGGER fail_recovery BEFORE INSERT ON tenant_members BEGIN SELECT RAISE(ABORT,'isolated repair failure'); END");try{mvc.perform(get("/api/v1/tenants").header("Authorization","Bearer "+access)).andExpect(status().isForbidden());assertEquals(0,count("tenant_members"));assertEquals(0,count("audit_logs"));}finally{jdbc.execute("DROP TRIGGER fail_recovery");}
        jdbc.execute("CREATE TRIGGER fail_recovery_audit BEFORE INSERT ON audit_logs BEGIN SELECT RAISE(ABORT,'isolated audit failure'); END");try{mvc.perform(get("/api/v1/tenants").header("Authorization","Bearer "+access)).andExpect(status().isOk());assertEquals("owner",repository.role("registrant",1));assertEquals(0,count("audit_logs"));}finally{jdbc.execute("DROP TRIGGER fail_recovery_audit");}
    }
    @Test void runtimeRbacOffFallbackDoesNotReactivateSuspendedMembersOrInactiveWorkspaces()throws Exception{
        StandardEnvironment environment=new StandardEnvironment();environment.getPropertySources().addFirst(new MapPropertySource("isolated-rbac",Map.of("WEKNORA_TENANT_ENABLE_RBAC","false","WEKNORA_TENANT_ENABLE_CROSS_TENANT_ACCESS","false")));AuthFilter filter=new AuthFilter(jwt,repository,json,new SecuritySettings(repository,environment),null,recovery);
        jdbc.update("INSERT INTO tenant_members(user_id,tenant_id,role,status) VALUES('registrant',1,'owner','suspended')");MockHttpServletRequest request=new MockHttpServletRequest("GET","/api/v1/tenants");request.addHeader("Authorization","Bearer "+access);MockHttpServletResponse response=new MockHttpServletResponse();AtomicReference<AuthContext> context=new AtomicReference<>();filter.doFilter(request,response,(req,res)->context.set(CurrentAuth.get((jakarta.servlet.http.HttpServletRequest)req)));assertEquals(200,response.getStatus());assertEquals("admin",context.get().role());assertFalse(context.get().rbacEnforced());assertNull(repository.role("registrant",1));assertEquals("suspended",jdbc.queryForObject("SELECT status FROM tenant_members",String.class));assertEquals(0,count("audit_logs"));
        jdbc.update("UPDATE tenants SET status='inactive' WHERE id=1");request=new MockHttpServletRequest("GET","/api/v1/tenants");request.addHeader("Authorization","Bearer "+access);response=new MockHttpServletResponse();filter.doFilter(request,response,(req,res)->fail("inactive workspace must not proceed"));assertEquals(401,response.getStatus());
    }
    @Test void explicitHeaderPermissionPrecedesExistenceButLookupAlwaysPrecedesRecovery()throws Exception{
        mvc.perform(get("/api/v1/tenants").header("Authorization","Bearer "+access).header("X-Tenant-ID","999")).andExpect(status().isForbidden());
        jdbc.update("INSERT INTO tenant_members(user_id,tenant_id,role,status) VALUES('registrant',999,'viewer','active')");
        mvc.perform(get("/api/v1/tenants").header("Authorization","Bearer "+access).header("X-Tenant-ID","999")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("Invalid target workspace ID"));
        jdbc.update("DELETE FROM tenant_members");jdbc.update("DELETE FROM tenants WHERE id=1");
        mvc.perform(get("/api/v1/tenants").header("Authorization","Bearer "+access).header("X-Tenant-ID","1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/tenants").header("Authorization","Bearer "+access)).andExpect(status().isUnauthorized());
        assertEquals(0,count("tenant_members"));assertEquals(0,count("audit_logs"));
    }
    private List<Integer> parallel(String first,String second)throws Exception{ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch gate=new CountDownLatch(1);try{var tasks=List.of(first,second).stream().map(token->pool.submit(()->{gate.await();return mvc.perform(get("/api/v1/tenants").header("Authorization","Bearer "+token)).andReturn().getResponse().getStatus();})).toList();gate.countDown();return List.of(tasks.get(0).get(15,TimeUnit.SECONDS),tasks.get(1).get(15,TimeUnit.SECONDS));}finally{pool.shutdownNow();}}
    private void assertUnauthorized()throws Exception{mvc.perform(get("/api/v1/tenants").header("Authorization","Bearer "+access)).andExpect(status().isUnauthorized());}
    private int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    private String token(String user,long tenant){String value=jwt.sign(Map.of("user_id",user,"email",user+"@example.com","tenant_id",tenant,"type","access","exp",Instant.now().plusSeconds(3600).getEpochSecond()));repository.storeToken(user,value,"access_token",Instant.now().plusSeconds(3600));return value;}
    private JsonNode golden()throws Exception{return json.readTree(Path.of("src/test/java/com/tencent/weknora/security/orphan-golden.json").toFile());}
}
