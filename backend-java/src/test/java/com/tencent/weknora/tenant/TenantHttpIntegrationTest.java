package com.tencent.weknora.tenant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"weknora.jwt-secret=isolated-tenant-http-secret","weknora.bridge.enabled=false","logging.level.root=WARN","WEKNORA_TENANT_ENABLE_CROSS_TENANT_ACCESS=true"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantHttpIntegrationTest {
    private static final Path DATABASE;
    static{try{DATABASE=Files.createTempFile("weknora-tenant-",".db");DATABASE.toFile().deleteOnExit();}catch(Exception ex){throw new ExceptionInInitializerError(ex);}}
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties){properties.add("spring.datasource.url",()->"jdbc:sqlite:"+DATABASE+"?busy_timeout=10000");properties.add("spring.datasource.driver-class-name",()->"org.sqlite.JDBC");properties.add("spring.datasource.hikari.maximum-pool-size",()->2);}
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired DataSource datasource;
    @Autowired JwtCodec jwt;
    @Autowired AuthRepository repository;
    @Autowired TenantConfigurationCodec codec;
    @Autowired TenantService service;
    private String owner,viewer,admin;
    @BeforeAll void schema()throws Exception{try(var connection=datasource.getConnection()){for(String script:List.of("000000_init.up.sql","000004_memory.up.sql","000007_system_admin_and_settings.up.sql"))ScriptUtils.executeSqlScript(connection,new FileSystemResource("../migrations/sqlite/"+script));}}
    @BeforeEach void fixtures(){for(String table:List.of("tenant_api_keys","auth_tokens","audit_logs","tenant_members","users","tenants","system_settings"))jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO tenants(id,name,description,status,business,created_at,web_search_config,storage_engine_config) VALUES(1,'home','Persisted','active','business','2026-01-01 00:00:00','{\"api_key\":\"private\",\"proxy_url\":\"private\"}','{\"default_provider\":\"minio\",\"minio\":{\"bucket_name\":\"original\",\"access_key_id\":\"private\",\"secret_access_key\":\"private\"}}'),(2,'peer%_','Peer','inactive','business','2026-02-01 00:00:00',NULL,NULL),(3,'deleted','Deleted','active','business','2026-03-01 00:00:00',NULL,NULL)");jdbc.update("UPDATE tenants SET deleted_at=CURRENT_TIMESTAMP WHERE id=3");
        jdbc.update("INSERT INTO users(id,username,email,password_hash,tenant_id,preferences) VALUES('u','Owner','u@example.com','unused',1,'{}'),('v','Viewer','v@example.com','unused',1,'{}'),('a','Admin','a@example.com','unused',1,'{}')");jdbc.update("INSERT INTO tenant_members(user_id,tenant_id,role,status) VALUES('u',1,'owner','active'),('v',1,'viewer','active'),('a',1,'admin','active')");owner=token("u");viewer=token("v");admin=token("a");}
    @Test void currentIsOnlyActiveWorkspaceAndDetailsRespectRoleSecretProjection()throws Exception{
        mvc.perform(auth(get("/api/v1/tenants"),owner)).andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1)).andExpect(jsonPath("$.data.items[0].id").value(1)).andExpect(jsonPath("$.data.items[0].storage_engine_config.minio.secret_access_key").value("***")).andExpect(jsonPath("$.data.items[0].web_search_config.api_key").doesNotExist());
        mvc.perform(auth(get("/api/v1/tenants/1"),viewer)).andExpect(status().isOk()).andExpect(jsonPath("$.data.storage_engine_config").doesNotExist()).andExpect(jsonPath("$.data.api_key").doesNotExist()).andExpect(jsonPath("$.data.default_storage_backend_id").doesNotExist());
        mvc.perform(auth(get("/api/v1/tenants/2"),owner)).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value(1002));
    }
    @Test void crossTenantCatalogAlwaysRedactsAndIncludesInactiveButNotDeleted()throws Exception{
        mvc.perform(auth(get("/api/v1/tenants/all"),owner)).andExpect(status().isForbidden());superuser();
        mvc.perform(auth(get("/api/v1/tenants/all"),owner)).andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(2)).andExpect(jsonPath("$.data.items[0].id").value(2)).andExpect(jsonPath("$.data.items[1].web_search_config").doesNotExist()).andExpect(jsonPath("$.data.items[1].storage_engine_config").doesNotExist());
        mvc.perform(auth(get("/api/v1/tenants/2"),owner)).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("inactive"));
        mvc.perform(auth(get("/api/v1/tenants/999"),owner)).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.error.code").value(1007));
    }
    @Test void searchUsesLiteralWildcardOrTenantIdAndGoPaginationDefaults()throws Exception{superuser();
        mvc.perform(auth(get("/api/v1/tenants/search").param("keyword","%_").param("tenant_id","1").param("page","bad").param("page_size","999"),owner)).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2)).andExpect(jsonPath("$.data.page").value(1)).andExpect(jsonPath("$.data.page_size").value(100));
        mvc.perform(auth(get("/api/v1/tenants/search").param("keyword","%_").param("tenant_id","invalid").param("page","0").param("page_size","0"),owner)).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.items[0].id").value(2)).andExpect(jsonPath("$.data.page_size").value(20));
        mvc.perform(auth(get("/api/v1/tenants/search").param("keyword"," "),owner)).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
    }
    @Test void metadataWhitelistNullPatchUnicodeTrimAndGormEmptyDescriptionBehavior()throws Exception{
        String storage=jdbc.queryForObject("SELECT storage_engine_config FROM tenants WHERE id=1",String.class);
        mvc.perform(auth(put("/api/v1/tenants/1").contentType("application/json").content("{\"name\":\" 改名😀 \",\"description\":null,\"status\":\"inactive\",\"storage_quota\":999,\"default_storage_backend_id\":\"foreign\",\"storage_engine_config\":{}}"),owner)).andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("改名😀")).andExpect(jsonPath("$.data.description").value("Persisted")).andExpect(jsonPath("$.data.status").value("active"));
        assertEquals(storage,jdbc.queryForObject("SELECT storage_engine_config FROM tenants WHERE id=1",String.class));assertNull(jdbc.queryForMap("SELECT default_storage_backend_id FROM tenants WHERE id=1").get("default_storage_backend_id"));
        var golden=golden().path("empty_description");mvc.perform(auth(put("/api/v1/tenants/1").contentType("application/json").content("{\"description\":\"  \"}"),owner)).andExpect(status().isOk()).andExpect(jsonPath("$.data.description").value(golden.path("returned").asText()));assertEquals(golden.path("persisted").asText(),jdbc.queryForObject("SELECT description FROM tenants WHERE id=1",String.class));
        mvc.perform(auth(put("/api/v1/tenants/1").contentType("application/json").content("null"),owner)).andExpect(status().isOk());
    }
    @Test void metadataBindingLimitsAndRoleDenialAuditPreserveDatabase()throws Exception{
        for(String body:List.of("{\"name\":\"\"}","{\"name\":\"  \"}","{\"name\":1}",json.writeValueAsString(Map.of("name","😀".repeat(129))),json.writeValueAsString(Map.of("description","😀".repeat(513))))){mvc.perform(auth(put("/api/v1/tenants/1").contentType("application/json").content(body),owner)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value(1010));}assertEquals("home",jdbc.queryForObject("SELECT name FROM tenants WHERE id=1",String.class));
        mvc.perform(auth(put("/api/v1/tenants/1").contentType("application/json").content("{}"),admin)).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("Forbidden: insufficient workspace role"));assertEquals("/api/v1/tenants/:id",jdbc.queryForObject("SELECT request_path FROM audit_logs",String.class));
    }
    @Test void memoryNormalizationMatchesActualGoGoldensIncludingPointerFlagsAndUnicode()throws Exception{
        for(JsonNode entry:golden().path("memory")){JsonNode root=entry.get("request");assertEquivalent(entry.get("typed"),json.valueToTree(codec.memory(root,false,false)));assertEquivalent(entry.get("normalized"),json.valueToTree(codec.memory(root,false,true)));}
        JsonNode defaults=data(auth(get("/api/v1/tenants/kv/memory-config"),viewer));assertEquivalent(golden().path("memory").get(0).get("normalized"),defaults);
        JsonNode body=golden().path("memory").get(2).get("request");JsonNode result=data(auth(put("/api/v1/tenants/kv/memory-config").contentType("application/json").content(body.toString()),admin));assertEquivalent(golden().path("memory").get(2).get("normalized"),result);assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_bases",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM models",Integer.class));
        mvc.perform(auth(put("/api/v1/tenants/kv/memory-config").contentType("application/json").content("null"),admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(false)).andExpect(jsonPath("$.data.max_items").value(200));
    }
    @Test void memoryValidationBeforeNormalizationUsesBytesAndRunesAndStrictTypedReplacement()throws Exception{
        for(Map<String,Object> body:List.<Map<String,Object>>of(Map.of("write_mode","old"),Map.of("max_items",2001),Map.of("extract_delay_seconds",-1),Map.of("extract_min_interval_seconds",86401),Map.of("interest_threshold",21),Map.of("embedding_model_id","中".repeat(22)),Map.of("extract_instructions","😀".repeat(1001))))mvc.perform(auth(put("/api/v1/tenants/kv/memory-config").contentType("application/json").content(json.writeValueAsString(body)),admin)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value(1000));
        for(String body:List.of("{\"max_items\":1.0}","{\"enabled\":1}","{\"vector_recall\":\"false\"}"))mvc.perform(auth(put("/api/v1/tenants/kv/memory-config").contentType("application/json").content(body),admin)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value(1010));
        mvc.perform(auth(put("/api/v1/tenants/kv/memory-config").contentType("application/json").content(json.writeValueAsString(Map.of("extract_instructions","😀".repeat(1000),"embedding_model_id","中".repeat(21)))),admin)).andExpect(status().isOk());
        mvc.perform(auth(put("/api/v1/tenants/kv/memory-config").contentType("application/json").content("{\"unknown\":true,\"MAX_ITEMS\":null}"),admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.unknown").doesNotExist()).andExpect(jsonPath("$.data.extract_instructions").value(""));
    }
    @Test void retrievalTypedZeroOptionalOmissionReplacementAndRangesMatchGo()throws Exception{
        assertEquivalent(golden().path("retrieval_zero"),data(auth(get("/api/v1/tenants/kv/retrieval-config"),viewer)));assertEquivalent(golden().path("retrieval"),json.valueToTree(codec.retrieval(golden().path("retrieval"),false)));
        mvc.perform(auth(put("/api/v1/tenants/kv/retrieval-config").contentType("application/json").content("{\"embedding_top_k\":200,\"rerank_threshold\":-10,\"rrf_k\":9223372036854775807,\"unknown\":true}"),admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.rrf_k").value(Long.MAX_VALUE)).andExpect(jsonPath("$.data.unknown").doesNotExist());
        mvc.perform(auth(put("/api/v1/tenants/kv/retrieval-config").contentType("application/json").content("{}"),admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.embedding_top_k").value(0)).andExpect(jsonPath("$.data.rrf_k").doesNotExist());
        for(Map<String,Object> body:List.<Map<String,Object>>of(Map.of("vector_threshold",1.01),Map.of("keyword_threshold",-0.01),Map.of("rerank_threshold",11),Map.of("embedding_top_k",201),Map.of("rerank_top_k",-1)))mvc.perform(auth(put("/api/v1/tenants/kv/retrieval-config").contentType("application/json").content(json.writeValueAsString(body)),admin)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value(1000));
        mvc.perform(auth(put("/api/v1/tenants/kv/retrieval-config").contentType("application/json").content("{\"embedding_top_k\":1.0}"),admin)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value(1010));
    }
    @Test void scopedMachineSettingsCanOperateCurrentConfigsButCannotEnterPlatformCatalogOrDetail()throws Exception{
        String raw="sk-settings";jdbc.update("INSERT INTO tenant_api_keys(tenant_id,name,key_hash,api_key,capabilities,knowledge_base_ids) VALUES(1,'Settings',?,?,'[\"manage_tenant_settings\"]','[\"irrelevant-kb\"]')",AuthFilter.hashApiKey(raw),raw);
        mvc.perform(get("/api/v1/tenants").header("X-API-Key",raw)).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].storage_engine_config").doesNotExist());mvc.perform(put("/api/v1/tenants/kv/memory-config").header("X-API-Key",raw).contentType("application/json").content("{\"enabled\":true}")).andExpect(status().isOk());
        for(String path:List.of("/api/v1/tenants/all","/api/v1/tenants/search","/api/v1/tenants/1","/api/v1/tenants/kv/storage-engine-config"))mvc.perform(get(path).header("X-API-Key",raw)).andExpect(status().isForbidden());jdbc.update("UPDATE tenant_api_keys SET full_access=true");mvc.perform(get("/api/v1/tenants/1").header("X-API-Key",raw)).andExpect(status().isForbidden());
        jdbc.update("UPDATE tenant_api_keys SET full_access=false,capabilities='[\"retrieve\"]'");mvc.perform(get("/api/v1/tenants/kv/memory-config").header("X-API-Key",raw)).andExpect(status().isForbidden());mvc.perform(auth(put("/api/v1/tenants/kv/memory-config").contentType("application/json").content("{}"),viewer)).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("Forbidden: insufficient workspace role"));
    }
    @Test void failedWritesRollBackAndStaleRoleCannotWriteAfterAuthentication()throws Exception{
        jdbc.execute("CREATE TRIGGER fail_tenant_write BEFORE UPDATE OF retrieval_config ON tenants BEGIN SELECT RAISE(ABORT,'isolated failure'); END");try{mvc.perform(auth(put("/api/v1/tenants/kv/retrieval-config").contentType("application/json").content("{\"embedding_top_k\":2}"),admin)).andExpect(status().isInternalServerError());assertNull(jdbc.queryForMap("SELECT retrieval_config FROM tenants WHERE id=1").get("retrieval_config"));}finally{jdbc.execute("DROP TRIGGER fail_tenant_write");}
        AuthContext stale=new AuthContext("u",1,"owner",false,null,false,Set.of(),Set.of());jdbc.update("UPDATE tenant_members SET role='viewer' WHERE user_id='u'");assertEquals(403,assertThrows(ApiException.class,()->service.update(stale,"1","{\"name\":\"forbidden\"}")).status());assertEquals("home",jdbc.queryForObject("SELECT name FROM tenants WHERE id=1",String.class));
    }
    private void superuser(){jdbc.update("UPDATE users SET can_access_all_tenants=true WHERE id='u'");}
    private String token(String user){String value=jwt.sign(Map.of("user_id",user,"email",user+"@example.com","tenant_id",1,"type","access","exp",Instant.now().plusSeconds(3600).getEpochSecond()));jdbc.update("INSERT INTO auth_tokens(id,user_id,token,token_type,expires_at) VALUES(?,?,?,'access_token',?)",UUID.randomUUID().toString(),user,value,repository.timestampParameter(Instant.now().plusSeconds(3600)));return value;}
    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request,String token){return request.header("Authorization","Bearer "+token);}
    private JsonNode data(MockHttpServletRequestBuilder request)throws Exception{return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");}
    private JsonNode golden()throws Exception{return json.readTree(Path.of("src/test/java/com/tencent/weknora/tenant/tenant-golden.json").toFile());}
    private static void assertEquivalent(JsonNode expected,JsonNode actual){if(expected.isNumber()){assertTrue(actual.isNumber());assertEquals(0,expected.decimalValue().compareTo(actual.decimalValue()));return;}if(expected.isObject()){assertEquals(expected.size(),actual.size());expected.fields().forEachRemaining(entry->assertEquivalent(entry.getValue(),actual.path(entry.getKey())));return;}assertEquals(expected,actual);}
}
