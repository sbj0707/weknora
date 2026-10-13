package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.security.JwtCodec;
import java.nio.file.*;
import java.security.MessageDigest;
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
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"weknora.jwt-secret=isolated-kb-lifecycle-secret","weknora.bridge.enabled=false","STORAGE_ALLOW_LIST=local,s3","debug=false","logging.level.root=WARN"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KnowledgeBaseLifecycleIntegrationTest {
    static final Path DATABASE;
    static {try{DATABASE=Files.createTempFile("weknora-java-kb-write-",".db");DATABASE.toFile().deleteOnExit();}catch(Exception ex){throw new ExceptionInInitializerError(ex);}}
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties){properties.add("spring.datasource.url",()->"jdbc:sqlite:"+DATABASE);properties.add("spring.datasource.driver-class-name",()->"org.sqlite.JDBC");properties.add("spring.datasource.hikari.maximum-pool-size",()->1);}
    @Autowired MockMvc mvc;@Autowired JdbcTemplate jdbc;@Autowired DataSource datasource;@Autowired ObjectMapper json;@Autowired JwtCodec jwt;
    final Map<String,String> tokens=new HashMap<>();
    @BeforeAll void initialize() throws Exception {
        try(var connection=datasource.getConnection()){for(String script:List.of("000000_init","000003_knowledge_base_auto_tag_config","000007_system_admin_and_settings","000021_knowledge_profiles"))ScriptUtils.executeSqlScript(connection,new FileSystemResource("../migrations/sqlite/"+script+".up.sql"));}
        jdbc.update("INSERT INTO tenants(id,name,business) VALUES(1,'one','test'),(2,'two','test')");
        for(var entry:Map.of("alice","owner","bob","contributor","viewer","viewer").entrySet()){
            String user=entry.getKey();jdbc.update("INSERT INTO users(id,username,email,password_hash,tenant_id) VALUES(?,?,?,'unused',1)",user,user,user+"@test.invalid");jdbc.update("INSERT INTO tenant_members(user_id,tenant_id,role,status) VALUES(?,1,?,'active')",user,entry.getValue());
            String token=jwt.sign(Map.of("user_id",user,"tenant_id",1,"exp",Instant.now().plusSeconds(3600).getEpochSecond()));tokens.put(user,token);jdbc.update("INSERT INTO auth_tokens(id,user_id,token,token_type,expires_at,is_revoked) VALUES(?,?,?,'access_token','2099-01-01 00:00:00',false)",UUID.randomUUID().toString(),user,token);
        }
    }
    @BeforeEach void clean(){for(String table:List.of("knowledge_bases","storage_backends","vector_stores","audit_logs","tenant_api_keys"))jdbc.update("DELETE FROM "+table);jdbc.update("UPDATE tenants SET default_storage_backend_id=NULL,storage_engine_config=NULL");}
    ResultActions create(String user,String body) throws Exception{return mvc.perform(post("/api/v1/knowledge-bases").header("Authorization","Bearer "+tokens.get(user)).contentType("application/json").content(body));}
    ResultActions update(String user,String id,String body) throws Exception{return mvc.perform(put("/api/v1/knowledge-bases/"+id).header("Authorization","Bearer "+tokens.get(user)).contentType("application/json").content(body));}
    long count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);}
    @Test void createOverridesOwnershipPersistsSecretsAndWritesAuditAfterCommit() throws Exception {
        create("bob","""
            {"id":"created","name":"Native","tenant_id":2,"creator_id":"alice","storage_config":{"secret_id":"secret-id","secret_key":"secret-key"},"vlm_config":{"api_key":"vision-key"},"chunking_config":{"table_metadata_instructions":"  中文  "},"auto_tag_config":{"enabled":true,"max_tags":100,"skip_if_tagged":false}}
            """).andExpect(status().isCreated()).andExpect(jsonPath("$.data.tenant_id").value(1)).andExpect(jsonPath("$.data.creator_id").value("bob")).andExpect(jsonPath("$.data.storage_config.secret_key").value("")).andExpect(jsonPath("$.data.vlm_config.api_key").value("")).andExpect(jsonPath("$.data.auto_tag_config.max_tags").value(10)).andExpect(jsonPath("$.data.auto_tag_config.skip_if_tagged").value(false)).andExpect(jsonPath("$.data.chunking_config.table_metadata_instructions").value("中文"));
        assertThat(jdbc.queryForObject("SELECT cos_config FROM knowledge_bases WHERE id='created'",String.class)).contains("secret-key");assertThat(jdbc.queryForObject("SELECT vlm_config FROM knowledge_bases WHERE id='created'",String.class)).contains("vision-key");
        var audit=jdbc.queryForMap("SELECT * FROM audit_logs");assertThat(audit.get("action")).isEqualTo("kb.created");assertThat(audit.get("scope_id")).isEqualTo("created");assertThat(audit.get("actor_user_id")).isEqualTo("bob");
    }
    @Test void defaultAndLegacyAliasStorageBindingsUseTenantOwnershipAndActualGoPrecedence() throws Exception {
        jdbc.update("INSERT INTO storage_backends(id,tenant_id,name,provider,status,legacy_alias) VALUES('default',1,'Default','s3','active',false),('alias',1,'Alias','local','disabled',true)");
        jdbc.update("UPDATE tenants SET default_storage_backend_id='default' WHERE id=1");
        create("alice","{\"id\":\"default-kb\",\"name\":\"Default\",\"storage_provider_config\":{\"provider\":\"local\"}}").andExpect(status().isCreated()).andExpect(jsonPath("$.data.storage_backend_id").value("default")).andExpect(jsonPath("$.data.storage_provider_config.provider").value("s3"));
        jdbc.update("UPDATE tenants SET default_storage_backend_id=NULL WHERE id=1");
        create("alice","{\"name\":\"Alias\"}").andExpect(status().isCreated()).andExpect(jsonPath("$.data.storage_backend_id").value("alias"));
    }
    @Test void rejectsForeignDisabledAndDisallowedStorageWithoutMutation() throws Exception {
        jdbc.update("INSERT INTO storage_backends(id,tenant_id,name,provider,status) VALUES('foreign',2,'Foreign','s3','active'),('disabled',1,'Disabled','local','disabled')");
        for(String id:List.of("foreign","disabled"))create("alice","{\"name\":\"bad\",\"storage_backend_id\":\""+id+"\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.message").value("storage backend is unavailable"));
        create("alice","{\"name\":\"bad\",\"storage_provider_config\":{\"provider\":\"minio\"}}").andExpect(status().isBadRequest());assertThat(count("knowledge_bases")).isZero();assertThat(count("audit_logs")).isZero();
    }
    @Test void defaultPostgresBindingPreflightAndDisplayMatchRealDatabaseMetadata() throws Exception {
        String id="aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";jdbc.update("INSERT INTO vector_stores(id,name,engine_type,tenant_id,connection_config) VALUES(?,'PostgreSQL','postgres',1,'{\"use_default_connection\":true}')",id);
        create("alice","{\"name\":\"Vector\",\"vector_store_id\":\""+id+"\"}").andExpect(status().isCreated()).andExpect(jsonPath("$.data.vector_store_id").value(id)).andExpect(jsonPath("$.data.vector_store_source").value("user")).andExpect(jsonPath("$.data.vector_store_status").value("available")).andExpect(jsonPath("$.data.vector_store_name").value("PostgreSQL"));
    }
    @Test void vectorValidationSeparatesNotFoundFromUnportedEngineAndRollsBack() throws Exception {
        String foreign="bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",external="cccccccc-cccc-cccc-cccc-cccccccccccc";jdbc.update("INSERT INTO vector_stores(id,name,engine_type,tenant_id) VALUES(?,'Foreign','sqlite',2),(?,'External','qdrant',1)",foreign,external);
        for(String id:List.of("bad-uuid",foreign))create("alice","{\"name\":\"Bad\",\"vector_store_id\":\""+id+"\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value(2200));
        create("alice","{\"name\":\"Bad\",\"vector_store_id\":\""+external+"\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value(2201));assertThat(count("knowledge_bases")).isZero();
        jdbc.update("UPDATE vector_stores SET engine_type='sqlite' WHERE id=?",external);
        create("alice","{\"name\":\"Not initialized\",\"vector_store_id\":\""+external+"\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value(2201));assertThat(count("knowledge_bases")).isZero();
    }
    @Test void viewerAndNonCreatorCannotWriteWhileOwnerCanUpdate() throws Exception {
        create("viewer","{\"name\":\"Denied\"}").andExpect(status().isForbidden());create("alice","{\"id\":\"owned\",\"name\":\"Owned\"}").andExpect(status().isCreated());
        update("bob","owned","{\"name\":\"Denied\"}").andExpect(status().isForbidden());update("alice","owned","{\"name\":\"Changed\"}").andExpect(status().isOk());update("alice","unknown","{\"name\":\"No\"}").andExpect(status().isNotFound());
    }
    @Test void updatePartialConfigUsesGoZeroChunkingAndPreservesImmutableBindingsAndSecrets() throws Exception {
        create("bob","{\"id\":\"partial\",\"name\":\"Original\",\"description\":\"clear\",\"storage_config\":{\"secret_key\":\"keep\"},\"chunking_config\":{\"chunk_size\":512}}").andExpect(status().isCreated());
        update("bob","partial","{\"name\":\"Changed\",\"vector_store_id\":\"ignored\",\"storage_backend_id\":\"ignored\",\"config\":{\"auto_tag_config\":{\"enabled\":true}}}").andExpect(status().isOk()).andExpect(jsonPath("$.data.description").value("")).andExpect(jsonPath("$.data.chunking_config.chunk_size").value(0)).andExpect(jsonPath("$.data.auto_tag_config.max_tags").value(3));
        var row=jdbc.queryForMap("SELECT * FROM knowledge_bases WHERE id='partial'");assertThat(row.get("vector_store_id")).isNull();assertThat(row.get("storage_backend_id")).isNull();assertThat(row.get("cos_config").toString()).contains("keep");assertThat(count("audit_logs")).isEqualTo(2);
    }
    @Test void invalidIndexingAndProfileAsynqTransitionFailBeforeAnyWrite() throws Exception {
        create("alice","{\"id\":\"profile\",\"name\":\"Before\",\"profile_config\":{\"enabled\":false}}").andExpect(status().isCreated());
        update("alice","profile","{\"name\":\"Lost\",\"config\":{\"indexing_strategy\":{}}}").andExpect(status().isInternalServerError());
        update("alice","profile","{\"name\":\"Lost\",\"config\":{\"profile_config\":{\"enabled\":true}}}").andExpect(status().isNotImplemented());assertThat(jdbc.queryForObject("SELECT name FROM knowledge_bases WHERE id='profile'",String.class)).isEqualTo("Before");assertThat(count("audit_logs")).isEqualTo(1);
    }
    @Test void temporaryProfileTransitionHasNoAsynqSideEffectAndFaqDefaultsRetainGoEarlyReturn() throws Exception {
        create("alice","{\"id\":\"temp\",\"name\":\"Temp\",\"is_temporary\":true}").andExpect(status().isCreated());update("alice","temp","{\"name\":\"Temp\",\"config\":{\"profile_config\":{\"enabled\":true}}}").andExpect(status().isOk());
        create("alice","{\"name\":\"FAQ\",\"type\":\"faq\"}").andExpect(status().isCreated()).andExpect(jsonPath("$.data.faq_config.index_mode").value("question_answer")).andExpect(jsonPath("$.data.indexing_strategy.vector_enabled").value(false));
    }
    @Test void unicodePromptLimitsAndGraphExtractValidationAreEnforced() throws Exception {
        create("alice","{\"name\":\"Graph\",\"extract_config\":{\"enabled\":true}}").andExpect(status().isBadRequest());
        String valid=json.writeValueAsString(Map.of("name","Prompt","chunking_config",Map.of("table_metadata_instructions","😀".repeat(4000))));create("alice",valid).andExpect(status().isCreated());
        String invalid=json.writeValueAsString(Map.of("name","Prompt","profile_config",Map.of("custom_instructions","😀".repeat(4001))));create("alice",invalid).andExpect(status().isBadRequest());
    }
    @Test void auditFailureCannotRollBackOrHideCommittedBusinessWrite() throws Exception {
        jdbc.execute("CREATE TRIGGER reject_audit BEFORE INSERT ON audit_logs BEGIN SELECT RAISE(ABORT,'test audit unavailable'); END");
        try{create("alice","{\"id\":\"audit-failure\",\"name\":\"Durable\"}").andExpect(status().isCreated());assertThat(count("knowledge_bases")).isEqualTo(1);assertThat(count("audit_logs")).isZero();}finally{jdbc.execute("DROP TRIGGER reject_audit");}
    }
    @Test void vectorDisplayUsesFirstSupportedEnvDriverAndSuppressesStaleUserSuppliedMetadata() throws Exception {
        var repository=new KnowledgeBaseRepository(jdbc,json,"unknown, elasticsearch_v8,sqlite");
        var defaultView=repository.decorateStore(json.createObjectNode().put("tenant_id",1).put("vector_store_name","forged"));assertThat(defaultView.path("vector_store_name").asText()).isEqualTo("System default");assertThat(defaultView.path("vector_store_engine_type").asText()).isEqualTo("elasticsearch");
        var bound=repository.decorateStore(json.createObjectNode().put("tenant_id",1).put("vector_store_id","__env_sqlite__").put("vector_store_name","forged"));assertThat(bound.path("vector_store_source").asText()).isEqualTo("env");assertThat(bound.path("vector_store_name").asText()).isEqualTo("SQLite");
        var missing=repository.decorateStore(json.createObjectNode().put("tenant_id",1).put("vector_store_id","gone").put("vector_store_name","forged").put("vector_store_engine_type","forged"));assertThat(missing.has("vector_store_name")).isFalse();assertThat(missing.has("vector_store_engine_type")).isFalse();assertThat(missing.path("vector_store_status").asText()).isEqualTo("unavailable");
    }
    @Test void restrictedApiKeyCanCreateButCannotReadOutsideItsExistingScope() throws Exception {
        String key="isolated-create-key";String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        jdbc.update("INSERT INTO tenant_api_keys(tenant_id,name,key_hash,capabilities,knowledge_base_ids) VALUES(1,'Writer',?,'[\"manage_kbs\",\"retrieve\"]','[\"allowed\"]')",hash);
        mvc.perform(post("/api/v1/knowledge-bases").header("X-API-Key",key).contentType("application/json").content("{\"id\":\"outside\",\"name\":\"Created\"}")).andExpect(status().isCreated());
        mvc.perform(get("/api/v1/knowledge-bases/outside").header("X-API-Key",key)).andExpect(status().isForbidden());assertThat(count("knowledge_bases")).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT details FROM audit_logs",String.class)).contains("Writer");
    }
}
