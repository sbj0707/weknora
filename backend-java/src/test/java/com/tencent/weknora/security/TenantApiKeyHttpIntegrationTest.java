package com.tencent.weknora.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"weknora.jwt-secret=isolated-api-key-http-test-secret","weknora.bridge.enabled=false","logging.level.root=WARN","SYSTEM_AES_KEY=0123456789abcdef0123456789abcdef","WEKNORA_TENANT_ENABLE_CROSS_TENANT_ACCESS=true"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantApiKeyHttpIntegrationTest {
    private static final Path DATABASE;
    static{try{DATABASE=Files.createTempFile("weknora-tenant-api-keys-",".db");DATABASE.toFile().deleteOnExit();}catch(Exception ex){throw new ExceptionInInitializerError(ex);}}
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties){properties.add("spring.datasource.url",()->"jdbc:sqlite:"+DATABASE);properties.add("spring.datasource.driver-class-name",()->"org.sqlite.JDBC");properties.add("spring.datasource.hikari.maximum-pool-size",()->1);}
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired DataSource datasource;
    @Autowired JwtCodec jwt;
    @Autowired AuthRepository authRepository;
    @Autowired TenantApiKeyService service;
    private String access;
    @BeforeAll void schema()throws Exception{try(var connection=datasource.getConnection()){ScriptUtils.executeSqlScript(connection,new FileSystemResource("../migrations/sqlite/000000_init.up.sql"));ScriptUtils.executeSqlScript(connection,new FileSystemResource("../migrations/sqlite/000007_system_admin_and_settings.up.sql"));}}
    @BeforeEach void fixture(){jdbc.update("DELETE FROM tenant_api_keys");jdbc.update("DELETE FROM auth_tokens");jdbc.update("DELETE FROM tenant_members");jdbc.update("DELETE FROM users");jdbc.update("DELETE FROM knowledge_bases");jdbc.update("DELETE FROM tenants");jdbc.update("DELETE FROM system_settings");
        jdbc.update("INSERT INTO tenants(id,name,business) VALUES(1,'home','test'),(2,'peer','test')");
        jdbc.update("INSERT INTO users(id,username,email,password_hash,tenant_id) VALUES('u','Owner','u@example.com','unused',1)");
        jdbc.update("INSERT INTO tenant_members(user_id,tenant_id,role,status) VALUES('u',1,'owner','active'),('u',2,'viewer','active')");
        jdbc.update("INSERT INTO knowledge_bases(id,name,tenant_id,type,embedding_model_id,summary_model_id) VALUES('kb-home','Home KB',1,'document','',''),('kb-other','Other KB',2,'document','','')");
        access=jwt.sign(Map.of("user_id","u","tenant_id",1,"type","access","exp",Instant.now().plusSeconds(3600).getEpochSecond(),"jti",UUID.randomUUID().toString()));authRepository.storeToken("u",access,"access_token",Instant.now().plusSeconds(3600));
    }
    @Test void scopedCreationHashEncryptionNormalizationRetrievalAndLastUsedMatchGo()throws Exception{
        JsonNode created=create(Map.of("name"," \u0085Integration\u3000 ","capabilities",List.of(" Retrieve ","retrieve","CHAT",""),"knowledge_base_ids",List.of(" kb-home ","kb-home",""),"expires_at_unix",Instant.now().plusSeconds(3600).getEpochSecond(),"scope_type","platform"));
        String token=created.path("token").asText();long id=created.path("id").asLong();assertTrue(token.matches("sk-[A-Za-z0-9_-]{43}"));assertEquals("Integration",created.path("name").asText());assertEquals(json.readTree("[\"retrieve\",\"chat\"]"),created.path("capabilities"));assertEquals(json.readTree("[\"kb-home\"]"),created.path("knowledge_base_ids"));
        var stored=jdbc.queryForMap("SELECT * FROM tenant_api_keys WHERE id=?",id);assertEquals("tenant",stored.get("scope_type"));assertEquals(AuthFilter.hashApiKey(token),stored.get("key_hash"));assertTrue(stored.get("api_key").toString().startsWith("enc:v1:"));assertEquals(stored.get("api_key"),created.path("api_key").asText());
        mvc.perform(get("/api/v1/tenants/1/api-keys").header("Authorization","Bearer "+access)).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].api_key").value(token)).andExpect(jsonPath("$.data[0].token").doesNotExist()).andExpect(jsonPath("$.data[0].key_hash").doesNotExist());
        mvc.perform(get("/api/v1/knowledge-bases").header("X-API-Key",token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
        Instant deadline=Instant.now().plusSeconds(2);while(jdbc.queryForObject("SELECT last_used_at FROM tenant_api_keys WHERE id=?",String.class,id)==null&&Instant.now().isBefore(deadline))Thread.sleep(10);
        String used=jdbc.queryForObject("SELECT last_used_at FROM tenant_api_keys WHERE id=?",String.class,id);assertNotNull(used);
        mvc.perform(get("/api/v1/knowledge-bases").header("X-API-Key",token)).andExpect(status().isOk());assertEquals(used,jdbc.queryForObject("SELECT last_used_at FROM tenant_api_keys WHERE id=?",String.class,id));
    }
    @Test void updateIsFullReplacementClearsScopeDoesNotRotateAndCanImmediatelyExpire()throws Exception{
        var created=create(Map.of("name","Scoped","capabilities",List.of("retrieve"),"knowledge_base_ids",List.of("kb-home"),"expires_at_unix",Instant.now().plusSeconds(3600).getEpochSecond()));String token=created.path("token").asText();long id=created.path("id").asLong();String hash=jdbc.queryForObject("SELECT key_hash FROM tenant_api_keys WHERE id=?",String.class,id);
        mvc.perform(put("/api/v1/tenants/1/api-keys/"+id).header("Authorization","Bearer "+access).contentType("application/json").content("{\"name\":\"Full\",\"full_access\":true,\"capabilities\":[\"bogus\"],\"knowledge_base_ids\":[\"kb-other\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.api_key").value(token)).andExpect(jsonPath("$.data.token").doesNotExist()).andExpect(jsonPath("$.data.knowledge_base_ids").value(org.hamcrest.Matchers.nullValue())).andExpect(jsonPath("$.data.capabilities").isEmpty()).andExpect(jsonPath("$.data.expires_at").doesNotExist());
        assertEquals(hash,jdbc.queryForObject("SELECT key_hash FROM tenant_api_keys WHERE id=?",String.class,id));
        mvc.perform(get("/api/v1/knowledge-bases").header("X-API-Key",token)).andExpect(status().isOk());
        mvc.perform(put("/api/v1/tenants/1/api-keys/"+id).header("Authorization","Bearer "+access).contentType("application/json").content("{\"name\":\"Expired\",\"capabilities\":[\"retrieve\"],\"expires_at_unix\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.full_access").value(false)).andExpect(jsonPath("$.data.expires_at").value("1970-01-01T00:00:01Z"));
        mvc.perform(get("/api/v1/knowledge-bases").header("X-API-Key",token)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/tenants/1/api-keys").header("Authorization","Bearer "+access)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(delete("/api/v1/tenants/1/api-keys/"+id).header("Authorization","Bearer "+access)).andExpect(status().isOk()).andExpect(content().json("{\"success\":true}"));
        assertNotNull(jdbc.queryForObject("SELECT revoked_at FROM tenant_api_keys WHERE id=?",String.class,id));
        mvc.perform(get("/api/v1/tenants/1/api-keys").header("Authorization","Bearer "+access)).andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(delete("/api/v1/tenants/1/api-keys/"+id).header("Authorization","Bearer "+access)).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value(1003));
    }
    @Test void scopedRequestsValidateCapabilitiesKbOwnershipExpiryAndJsonTypesBeforeWriting()throws Exception{
        for(String body:List.of("{}","{\"name\":\"Key\"}","{\"name\":\"Key\",\"capabilities\":[\"retrieve\",\"bogus\"]}","{\"name\":\"Key\",\"capabilities\":\"retrieve\"}","{\"name\":\"Key\",\"capabilities\":[\"retrieve\"],\"expires_at_unix\":1}","{\"name\":\"Key\",\"capabilities\":[\"retrieve\"],\"expires_at_unix\":1.5}","{\"name\":\"Key\",\"capabilities\":[\"retrieve\"],\"knowledge_base_ids\":[\"unknown\"]}"))mvc.perform(post("/api/v1/tenants/1/api-keys").header("Authorization","Bearer "+access).contentType("application/json").content(body)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value(1010));
        mvc.perform(post("/api/v1/tenants/1/api-keys").header("Authorization","Bearer "+access).contentType("application/json").content("{\"name\":\"Key\",\"capabilities\":[\"retrieve\"],\"knowledge_base_ids\":[\"kb-other\"]}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.message").value("knowledge_base_ids contains a knowledge base outside this workspace"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM tenant_api_keys",Integer.class));
    }
    @Test void ownerAndPathTenantChecksPreventCrossTenantLeaksAndMachineKeyAdministration()throws Exception{
        var key=create(Map.of("name","Machine","full_access",true));String token=key.path("token").asText();
        mvc.perform(get("/api/v1/tenants/1/api-keys").header("X-API-Key",token)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/tenants/2/api-keys").header("Authorization","Bearer "+access)).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.message").value("Access denied: URL workspace does not match the active workspace"));
        mvc.perform(get("/api/v1/tenants/2/api-keys").header("Authorization","Bearer "+access).header("X-Tenant-ID","2")).andExpect(status().isForbidden());
        for(String role:List.of("admin","contributor","viewer")){jdbc.update("UPDATE tenant_members SET role=? WHERE user_id='u' AND tenant_id=1",role);mvc.perform(get("/api/v1/tenants/1/api-keys").header("Authorization","Bearer "+access)).andExpect(status().isForbidden());}
        jdbc.update("UPDATE users SET is_system_admin=true WHERE id='u'");mvc.perform(get("/api/v1/tenants/1/api-keys").header("Authorization","Bearer "+access)).andExpect(status().isForbidden());
        jdbc.update("UPDATE users SET can_access_all_tenants=true WHERE id='u'");mvc.perform(get("/api/v1/tenants/2/api-keys").header("Authorization","Bearer "+access)).andExpect(status().isOk());
    }
    @Test void otherTenantAndPlatformIdsCannotBeUpdatedOrRevokedAndMissingIDsKeepGoErrorCodes()throws Exception{
        jdbc.update("INSERT INTO tenant_api_keys(tenant_id,scope_type,name,key_hash,api_key) VALUES(2,'tenant','foreign','foreign','sk-foreign'),(NULL,'platform','platform','platform','sk-platform')");
        var ids=jdbc.queryForList("SELECT id FROM tenant_api_keys");for(var row:ids){String id=row.get("id").toString();
            mvc.perform(put("/api/v1/tenants/1/api-keys/"+id).header("Authorization","Bearer "+access).contentType("application/json").content("{\"name\":\"Changed\",\"full_access\":true}")).andExpect(status().isNotFound());
            mvc.perform(delete("/api/v1/tenants/1/api-keys/"+id).header("Authorization","Bearer "+access)).andExpect(status().isNotFound());}
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM tenant_api_keys WHERE revoked_at IS NOT NULL",Integer.class));
        mvc.perform(get("/api/v1/tenants/0/api-keys").header("Authorization","Bearer "+access)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value(1010));
        mvc.perform(delete("/api/v1/tenants/1/api-keys/0").header("Authorization","Bearer "+access)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value(1000));
    }
    @Test void encryptedReadFailsClosedAfterKeyRotationWhileHashAuthenticationStillWorks()throws Exception{
        var created=create(Map.of("name","Encrypted","capabilities",List.of("retrieve")));String token=created.path("token").asText();
        TenantApiKeyService rotated=new TenantApiKeyService(jdbc,authRepository,json,new GoSecretCodec("abcdef0123456789abcdef0123456789"));
        ApiException error=assertThrows(ApiException.class,()->rotated.list(new AuthContext("u",1,"owner",false,null,false,Set.of(),Set.of()),"1"));assertEquals(500,error.status());assertEquals(1007,error.appCode());
        jdbc.update("UPDATE tenant_api_keys SET capabilities='[\" RETRIEVE \",\"bogus\"]'");
        mvc.perform(get("/api/v1/knowledge-bases").header("X-API-Key",token)).andExpect(status().isOk());
    }
    @Test void goGormGoldenConfirmsEncryptedCreateNilFullScopeAndCapabilityNormalization()throws Exception{
        var fixture=json.readTree(Path.of("src/test/java/com/tencent/weknora/security/apikey-golden.json").toFile());
        assertEquals(fixture.path("capabilities"),json.valueToTree(TenantApiKeyService.normalizeCapabilities(List.of(" Chat ","retrieve","chat","bogus","SYSTEM_TENANTS_READ","","\u0085MANAGE_KBS\u3000"))));
        var created=create(Map.of("name","Go fixture","full_access",true));assertEquals(fixture.path("create").path("knowledge_base_ids"),created.path("knowledge_base_ids"));assertEquals(fixture.path("create").path("capabilities"),created.path("capabilities"));assertEquals(fixture.path("create").path("api_key_encrypted").asBoolean(),created.path("api_key").asText().startsWith("enc:v1:"));
    }
    private JsonNode create(Map<String,Object> body)throws Exception{return json.readTree(mvc.perform(post("/api/v1/tenants/1/api-keys").header("Authorization","Bearer "+access).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data");}
}
