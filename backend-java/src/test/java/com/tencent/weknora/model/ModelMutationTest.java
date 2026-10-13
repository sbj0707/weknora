package com.tencent.weknora.model;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.security.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"weknora.jwt-secret=model-write-test","weknora.bridge.enabled=false","SYSTEM_AES_KEY=0123456789abcdef0123456789abcdef","debug=false","logging.level.root=WARN","SSRF_DNS_WHITELIST_ONLY=true","SSRF_WHITELIST=*.allowed.invalid"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModelMutationTest {
    static final Path DB;static{try{DB=Files.createTempFile("weknora-model-writes-",".db");DB.toFile().deleteOnExit();}catch(Exception ex){throw new ExceptionInInitializerError(ex);}}
    @DynamicPropertySource static void db(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->"jdbc:sqlite:"+DB);r.add("spring.datasource.driver-class-name",()->"org.sqlite.JDBC");r.add("spring.datasource.hikari.maximum-pool-size",()->1);}
    @Autowired ModelMutations mutations;@Autowired JdbcTemplate jdbc;@Autowired DataSource datasource;@Autowired ObjectMapper json;@Autowired GoSecretCodec secrets;@Autowired MockMvc mvc;@Autowired JwtCodec jwt;
    final AuthContext admin=new AuthContext("admin",1,"admin",false,null,false,Set.of(),Set.of());final Map<String,String> tokens=new HashMap<>();
    @BeforeAll void initialize()throws Exception{
        try(var c=datasource.getConnection()){for(String migration:List.of("000000_init","000003_knowledge_base_auto_tag_config","000004_memory","000007_system_admin_and_settings","000011_principal_model","000031_model_catalog_config"))ScriptUtils.executeSqlScript(c,new FileSystemResource("../migrations/sqlite/"+migration+".up.sql"));}
        jdbc.update("INSERT INTO tenants(id,name,business) VALUES(1,'one','test'),(2,'two','test')");
        for(String user:List.of("viewer","admin","system")){String role=user.equals("system")?"viewer":user;jdbc.update("INSERT INTO users(id,username,email,password_hash,tenant_id,is_system_admin) VALUES(?,?,?,?,1,?)",user,user,user+"@test.invalid","unused",user.equals("system"));jdbc.update("INSERT INTO tenant_members(user_id,tenant_id,role,status) VALUES(?,1,?,'active')",user,role);String token=jwt.sign(Map.of("user_id",user,"tenant_id",1,"exp",Instant.now().plusSeconds(3600).getEpochSecond()));tokens.put(user,token);jdbc.update("INSERT INTO auth_tokens(id,user_id,token,token_type,expires_at,is_revoked) VALUES(?,?,?,'access_token','2099-01-01 00:00:00',false)",UUID.randomUUID().toString(),user,token);}
        jdbc.update("INSERT INTO tenant_api_keys(tenant_id,key_hash,name,full_access) VALUES(1,?,'test',true)",AuthFilter.hashApiKey("write-key"));
    }
    private void seed(String id,long tenant,boolean builtin,String status,String parameters){jdbc.update("INSERT INTO models(id,tenant_id,name,display_name,type,source,description,parameters,is_builtin,managed_by,status,created_at,updated_at) VALUES(?,?,'old-name','old display','Rerank','remote','',?,?,'yaml',?,'2026-10-01 00:00:00','2026-10-01 00:00:00')",id,tenant,parameters,builtin,status);}
    @Test void actualGoHandlersAndTypedStorageGoldenFixtures()throws Exception{
        ModelParametersCodec codec=new ModelParametersCodec(json,secrets);
        for(JsonNode fixture:json.readTree(Files.readString(Path.of("src/test/java/com/tencent/weknora/model/go-mutation-fixtures.json")))){
            jdbc.update("DELETE FROM models WHERE id='fixture-model'");seed("fixture-model",1,false,"active",fixture.path("old_parameters").toString());
            JsonNode request=json.readTree(fixture.path("body").asText());
            if(fixture.has("error")){String expected=fixture.path("error").asText().replaceFirst("^error code: [0-9]+, error message: ","");assertThatThrownBy(()->{if(fixture.path("operation").asText().equals("create"))mutations.create(admin,request);else mutations.update(admin,"fixture-model",request);}).isInstanceOf(ApiException.class).hasMessage(expected);continue;}
            ObjectNode actual=fixture.path("operation").asText().equals("create")?mutations.create(admin,request):mutations.update(admin,"fixture-model",request);
            ObjectNode expected=(ObjectNode)fixture.path("response").path("data").deepCopy();for(String key:List.of("id","created_at","updated_at")){actual.remove(key);expected.remove(key);}assertThat(json.readTree(actual.toString())).as("response %s",fixture.path("body")).isEqualTo(expected);
            String id=fixture.path("operation").asText().equals("create")?jdbc.queryForObject("SELECT id FROM models WHERE id<>'fixture-model' ORDER BY created_at DESC LIMIT 1",String.class):"fixture-model";
            JsonNode stored=codec.stored(jdbc.queryForObject("SELECT parameters FROM models WHERE id=?",String.class,id));assertThat(json.readTree(stored.toString())).as("storage %s",fixture.path("body")).isEqualTo(fixture.get("storage"));if(!id.equals("fixture-model"))jdbc.update("DELETE FROM models WHERE id=?",id);
        }jdbc.update("DELETE FROM models WHERE id='fixture-model'");
    }
    @Test void remoteCreateEncryptsCredentialsAndUnsupportedDownloadHasNoSideEffect()throws Exception{
        String body="{\"name\":\"gpt-5\\nline\",\"description\":\"text\\tend\",\"type\":\"KnowledgeQA\",\"source\":\"remote\",\"parameters\":{\"provider\":\"openai\",\"base_url\":\"https://allowed.invalid/v1\",\"api_key\":\"private-api\",\"app_secret\":\"private-app\"}}";
        var result=mvc.perform(post("/api/v1/models").header("Authorization","Bearer "+tokens.get("admin")).contentType("application/json").content(body)).andExpect(status().isCreated()).andExpect(jsonPath("$.data.name").value("gpt-5 line")).andExpect(jsonPath("$.data.status").value("active")).andExpect(jsonPath("$.data.parameters.api_key").doesNotExist()).andReturn();
        String id=json.readTree(result.getResponse().getContentAsString()).path("data").path("id").asText();JsonNode stored=json.readTree(jdbc.queryForObject("SELECT parameters FROM models WHERE id=?",String.class,id));assertThat(stored.path("api_key").asText()).startsWith("enc:v1:");assertThat(secrets.decryptStrict(stored.path("api_key").asText())).isEqualTo("private-api");assertThat(secrets.decryptStrict(stored.path("app_secret").asText())).isEqualTo("private-app");
        long count=jdbc.queryForObject("SELECT count(*) FROM models",Long.class);mvc.perform(post("/api/v1/models").header("Authorization","Bearer "+tokens.get("admin")).contentType("application/json").content(body.replace("\"remote\"","\"local\""))).andExpect(status().isServiceUnavailable());assertThat(jdbc.queryForObject("SELECT count(*) FROM models",Long.class)).isEqualTo(count);jdbc.update("DELETE FROM models WHERE id=?",id);
    }
    @Test void credentialSubresourceIsEncryptedIdempotentAndBuiltinProtected()throws Exception{
        for(String id:List.of("cred","foreign-cred","builtin-cred","inactive-cred"))seed(id,id.equals("foreign-cred")||id.equals("builtin-cred")?2:1,id.equals("builtin-cred"),id.equals("inactive-cred")?"downloading":"active","{}");
        try{
            mvc.perform(put("/api/v1/models/cred/credentials").header("Authorization","Bearer "+tokens.get("viewer")).contentType("application/json").content("{\"api_key\":\"secret\"}")).andExpect(status().isForbidden());
            mvc.perform(put("/api/v1/models/foreign-cred/credentials").header("Authorization","Bearer "+tokens.get("admin")).contentType("application/json").content("{\"api_key\":\"secret\"}")).andExpect(status().isNotFound());
            mvc.perform(put("/api/v1/models/builtin-cred/credentials").header("Authorization","Bearer "+tokens.get("admin")).contentType("application/json").content("{\"api_key\":\"secret\"}")).andExpect(status().isForbidden());
            for(String id:List.of("cred","inactive-cred","builtin-cred")){
                String token=tokens.get(id.equals("builtin-cred")?"system":"admin");mvc.perform(put("/api/v1/models/"+id+"/credentials").header("Authorization","Bearer "+token).contentType("application/json").content("{\"api_key\":\"secret\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.fields.api_key.configured").value(true));
                String stored=jdbc.queryForObject("SELECT parameters FROM models WHERE id=?",String.class,id);assertThat(stored).doesNotContain("\"secret\"");mvc.perform(put("/api/v1/models/"+id+"/credentials").header("Authorization","Bearer "+token).contentType("application/json").content("{\"api_key\":\"\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.fields.api_key.configured").value(true));assertThat(jdbc.queryForObject("SELECT parameters FROM models WHERE id=?",String.class,id)).isEqualTo(stored);
                if(id.equals("inactive-cred"))mvc.perform(put("/api/v1/models/"+id+"/credentials").header("Authorization","Bearer "+token).contentType("application/json").content("{}")).andExpect(status().isNotFound());
                mvc.perform(delete("/api/v1/models/"+id+"/credentials/api_key").header("Authorization","Bearer "+token)).andExpect(status().isNoContent());mvc.perform(delete("/api/v1/models/"+id+"/credentials/api_key").header("Authorization","Bearer "+token)).andExpect(status().isNoContent());
            }assertThat(jdbc.queryForObject("SELECT managed_by FROM models WHERE id='builtin-cred'",String.class)).isEmpty();
            mvc.perform(delete("/api/v1/models/cred/credentials/secret_key").header("Authorization","Bearer "+tokens.get("admin"))).andExpect(status().isBadRequest());
        }finally{jdbc.update("DELETE FROM models WHERE id LIKE '%cred'");}
    }
    @Test void allReferenceBindingsCapCountsIsolationAndSoftDelete()throws Exception{
        seed("used-model",1,false,"download_failed","{}");
        for(int i=0;i<55;i++)jdbc.update("INSERT INTO knowledge_bases(id,name,tenant_id,embedding_model_id,summary_model_id,image_processing_config,vlm_config,asr_config,wiki_config,auto_tag_config) VALUES(?,?,1,'used-model','used-model',?,?,?,?,?)","use-kb-"+i,String.format("KB %03d",i),"{\"model_id\":\"used-model\"}","{\"model_id\":\"used-model\"}","{\"model_id\":\"used-model\"}","{\"synthesis_model_id\":\"used-model\"}","{\"model_id\":\"used-model\"}");
        String config="{\"model_id\":\"used-model\",\"rerank_model_id\":\"used-model\",\"vlm_model_id\":\"used-model\",\"asr_model_id\":\"used-model\",\"query_understand_model_id\":\"used-model\",\"secret\":\"must-not-return\",\"question_suggestions\":{\"follow_ups\":{\"model_id\":\"used-model\"}}}";
        jdbc.update("INSERT INTO custom_agents(id,name,tenant_id,config) VALUES('use-agent','Agent',1,?)",config);jdbc.update("UPDATE tenants SET memory_config=? WHERE id=1","{\"enabled\":false,\"embedding_model_id\":\" used-model \",\"extract_model_id\":\"used-model\"}");
        try{
            var result=mvc.perform(delete("/api/v1/models/used-model").header("Authorization","Bearer "+tokens.get("admin"))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value(2300)).andExpect(jsonPath("$.error.details.knowledge_base_total").value(55)).andExpect(jsonPath("$.error.details.knowledge_bases.length()").value(50)).andExpect(jsonPath("$.error.details.knowledge_bases[0].bindings.length()").value(7)).andExpect(jsonPath("$.error.details.agents[0].bindings.length()").value(6)).andExpect(jsonPath("$.error.details.long_term_memory.bindings.length()").value(2)).andReturn();assertThat(result.getResponse().getContentAsString()).doesNotContain("must-not-return");
            jdbc.update("UPDATE knowledge_bases SET deleted_at=CURRENT_TIMESTAMP WHERE id LIKE 'use-kb-%'");jdbc.update("UPDATE custom_agents SET tenant_id=2 WHERE id='use-agent'");jdbc.update("UPDATE tenants SET memory_config='{}' WHERE id=1");
            mvc.perform(delete("/api/v1/models/used-model").header("Authorization","Bearer "+tokens.get("admin"))).andExpect(status().isOk()).andExpect(jsonPath("$.message").value("Model deleted"));assertThat(jdbc.queryForObject("SELECT deleted_at FROM models WHERE id='used-model'",String.class)).isNotEmpty();mvc.perform(delete("/api/v1/models/used-model").header("Authorization","Bearer "+tokens.get("admin"))).andExpect(status().isNotFound());
        }finally{jdbc.update("DELETE FROM knowledge_bases WHERE id LIKE 'use-kb-%'");jdbc.update("DELETE FROM custom_agents WHERE id='use-agent'");jdbc.update("DELETE FROM models WHERE id='used-model'");jdbc.update("UPDATE tenants SET memory_config='{}' WHERE id=1");}
    }
    @Test void writeAuthorizationAndCatalogUrlErrorsLeaveStorageUntouched()throws Exception{
        seed("protected",2,true,"active","{\"api_key\":\"stored\"}");String body="{\"type\":\"KnowledgeQA\",\"source\":\"remote\",\"parameters\":{\"provider\":\"openai\"}}";
        try{
            mvc.perform(put("/api/v1/models/protected").header("Authorization","Bearer "+tokens.get("admin")).contentType("application/json").content(body)).andExpect(status().isForbidden());mvc.perform(put("/api/v1/models/protected").header("Authorization","Bearer "+tokens.get("system")).contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.data.tenant_id").value(2));assertThat(jdbc.queryForObject("SELECT managed_by FROM models WHERE id='protected'",String.class)).isEmpty();
            mvc.perform(delete("/api/v1/models/protected").header("Authorization","Bearer "+tokens.get("admin"))).andExpect(status().isBadRequest());
            String before=jdbc.queryForObject("SELECT parameters FROM models WHERE id='protected'",String.class);
            mvc.perform(put("/api/v1/models/protected").header("Authorization","Bearer "+tokens.get("system")).contentType("application/json").content(body.replace("\"provider\":\"openai\"","\"provider\":\"openai\",\"base_url\":\"http://metadata.google.internal\""))).andExpect(status().isBadRequest());assertThat(jdbc.queryForObject("SELECT parameters FROM models WHERE id='protected'",String.class)).isEqualTo(before);
            mvc.perform(post("/api/v1/models").header("X-API-Key","write-key").contentType("application/json").content(body)).andExpect(status().isForbidden());mvc.perform(post("/api/v1/models").header("Authorization","Bearer "+tokens.get("viewer")).contentType("application/json").content(body)).andExpect(status().isForbidden());
        }finally{jdbc.update("DELETE FROM models WHERE id='protected'");}
    }
}
