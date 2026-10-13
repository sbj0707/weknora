package com.tencent.weknora.security;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"weknora.jwt-secret=isolated-invitation-service-secret","weknora.bridge.enabled=false","logging.level.root=WARN","debug=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantInvitationServiceCompatibilityTest {
    private static final Path DATABASE;
    static{try{DATABASE=Files.createTempFile("weknora-invitations-",".db");DATABASE.toFile().deleteOnExit();}catch(Exception ex){throw new ExceptionInInitializerError(ex);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p){p.add("spring.datasource.url",()->"jdbc:sqlite:"+DATABASE+"?busy_timeout=10000");p.add("spring.datasource.driver-class-name",()->"org.sqlite.JDBC");}
    @Autowired TenantInvitationService service;
    @Autowired TenantMemberService memberService;
    @Autowired AuthRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired DataSource datasource;
    private final AuthContext owner=new AuthContext("owner",7,"owner",false,null,false,Set.of(),Set.of());
    private final AuthContext invitee=new AuthContext("invitee",0,"viewer",false,null,false,Set.of(),Set.of());
    @BeforeAll void schema()throws Exception{try(var c=datasource.getConnection()){for(String script:List.of("000000_init.up.sql","000005_messages_attachments_and_invitation_fields.up.sql","000007_system_admin_and_settings.up.sql"))ScriptUtils.executeSqlScript(c,new FileSystemResource("../migrations/sqlite/"+script));}}
    private void reset(){for(String table:List.of("tenant_invitations","audit_logs","tenant_members","users","tenants","system_settings"))jdbc.update("DELETE FROM "+table);jdbc.update("DELETE FROM sqlite_sequence WHERE name IN ('tenant_invitations','tenant_members','audit_logs')");jdbc.update("INSERT INTO tenants(id,name,business) VALUES(7,'Workspace','test')");jdbc.update("INSERT INTO users(id,username,email,password_hash,tenant_id) VALUES('owner','Owner','owner@example.com','unused',7),('invitee','Invitee','invitee@example.com','unused',NULL),('other','Other','other@example.com','unused',NULL)");jdbc.update("INSERT INTO tenant_members(user_id,tenant_id,role,status) VALUES('owner',7,'owner','active')");}
    @TestFactory Stream<DynamicTest> actualGoServiceRepositoryStateAndAuditGoldens()throws Exception{
        JsonNode fixtures=json.readTree(Path.of("src/test/java/com/tencent/weknora/security/invitation-service-golden.json").toFile());List<DynamicTest> tests=new ArrayList<>();fixtures.fields().forEachRemaining(entry->tests.add(DynamicTest.dynamicTest(entry.getKey(),()->run(entry.getKey(),entry.getValue()))));return tests.stream();
    }
    private void run(String mode,JsonNode expected)throws Exception{
        reset();List<String> errors=new ArrayList<>();boolean share=mode.startsWith("share");
        if(mode.equals("active-member"))memberService.add(owner,"7",Map.of("email","invitee@example.com","role","admin"));
        Map<String,Object> inv=null;try{inv=service.create(owner,7,"invitee","contributor","note",share);}catch(ApiException ex){errors.add(ex.getMessage());}
        if(inv!=null){long id=AuthRepository.number(inv,"id");String token=AuthRepository.string(inv,"token");switch(mode){
            case "duplicate"->call(errors,()->service.create(owner,7,"invitee","contributor","note",false));
            case "accept","accept-twice"->{call(errors,()->service.accept(invitee,id));if(mode.equals("accept-twice"))call(errors,()->service.accept(invitee,id));}
            case "wrong-invitee"->call(errors,()->service.accept(new AuthContext("other",0,"viewer",false,null,false,Set.of(),Set.of()),id));
            case "expired"->{jdbc.update("UPDATE tenant_invitations SET expires_at=? WHERE id=?",repository.timestampParameter(Instant.now().minusSeconds(3600)),id);call(errors,()->service.accept(invitee,id));}
            case "decline"->call(errors,()->service.decline(invitee,id));
            case "revoke","revoke-twice"->{call(errors,()->service.revoke(owner,7,id));if(mode.equals("revoke-twice"))call(errors,()->service.revoke(owner,7,id));}
            case "existing-suspended"->{jdbc.update("INSERT INTO tenant_members(user_id,tenant_id,role,status) VALUES('invitee',7,'admin','suspended')");call(errors,()->service.accept(invitee,id));}
            case "share-multi"->{call(errors,()->service.acceptToken(invitee,token));call(errors,()->service.acceptToken(new AuthContext("other",0,"viewer",false,null,false,Set.of(),Set.of()),token));call(errors,()->service.acceptToken(invitee,token));}
            case "share-existing"->{memberService.add(owner,"7",Map.of("email","invitee@example.com","role","owner"));call(errors,()->service.acceptToken(invitee,token));}
            case "share-reconcile"->{service.create(owner,7,"invitee","viewer","direct",false);call(errors,()->service.acceptToken(invitee,token));}
            case "share-revoked"->{call(errors,()->service.revoke(owner,7,id));call(errors,()->service.acceptToken(invitee,token));}
        }}
        var invites=jdbc.queryForList("SELECT id,invitee_user_id,role,status,accepted_count FROM tenant_invitations ORDER BY id");var members=jdbc.queryForList("SELECT user_id,role,status,invited_by FROM tenant_members WHERE user_id<>'owner' ORDER BY user_id");var audits=jdbc.queryForList("SELECT actor_user_id,actor_role,action,target_type,target_id,target_user_id,details FROM audit_logs ORDER BY id");for(var row:audits){Object detail=row.get("details");if(detail!=null)row.put("details",json.readTree(detail.toString()));}
        // Native service reports the public Go handler's collapsed 410 wording for revoked tokens.
        JsonNode publicExpected=json.readTree(expected.toString().replace("invitation token is invalid or has been revoked","invitation link is invalid or has been revoked"));assertEquals(publicExpected,json.readTree(json.writeValueAsBytes(Map.of("errors",errors,"invitations",invites,"memberships",members,"audits",audits))));
    }
    private void call(List<String> errors,Runnable action){try{action.run();}catch(ApiException ex){errors.add(ex.getMessage());}}
}
