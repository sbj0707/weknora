package com.tencent.weknora.chunker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.weknora.security.JwtCodec;
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
import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"weknora.jwt-secret=chunker-preview-isolated-secret","weknora.bridge.enabled=false","debug=false","logging.level.root=WARN"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChunkerPreviewIntegrationTest {
 static final Path DATABASE;
 static {try {DATABASE=Files.createTempFile("weknora-preview-",".db");DATABASE.toFile().deleteOnExit();}catch(Exception ex){throw new ExceptionInInitializerError(ex);}}
 @DynamicPropertySource static void database(DynamicPropertyRegistry props) {
  props.add("spring.datasource.url",() -> "jdbc:sqlite:"+DATABASE);props.add("spring.datasource.driver-class-name",() -> "org.sqlite.JDBC");props.add("spring.datasource.hikari.maximum-pool-size",() -> 1);
 }
 @Autowired MockMvc mvc;
 @Autowired JdbcTemplate jdbc;
 @Autowired DataSource datasource;
 @Autowired JwtCodec jwt;
 @Autowired ObjectMapper mapper;
 String token;
 @BeforeAll void initialize() throws Exception {
  try(var connection=datasource.getConnection()) {ScriptUtils.executeSqlScript(connection,new FileSystemResource("../migrations/sqlite/000000_init.up.sql"));ScriptUtils.executeSqlScript(connection,new FileSystemResource("../migrations/sqlite/000007_system_admin_and_settings.up.sql"));}
  jdbc.update("INSERT INTO tenants(id,name,business) VALUES (1,'preview','test')");
  jdbc.update("INSERT INTO users(id,username,email,password_hash,tenant_id) VALUES ('preview','Preview','preview@test.invalid','unused',1)");
  jdbc.update("INSERT INTO tenant_members(user_id,tenant_id,role,status) VALUES ('preview',1,'viewer','active')");
  token=jwt.sign(Map.of("user_id","preview","tenant_id",1,"exp",Instant.now().plusSeconds(3600).getEpochSecond()));
  jdbc.update("INSERT INTO auth_tokens(id,user_id,token,token_type,expires_at,is_revoked) VALUES (?,?,?,'access_token','2099-01-01 00:00:00',false)",UUID.randomUUID().toString(),"preview",token);
 }
 @TestFactory Stream<DynamicTest> actualGoHttpResponses() throws Exception {
  JsonNode fixtures;
  try(var input=new GZIPInputStream(getClass().getResourceAsStream("/go-chunker-preview.json.gz"))) {fixtures=mapper.readTree(input);}
  assertEquals(90,fixtures.size());
  return StreamSupport.stream(fixtures.spliterator(),false).map(test -> DynamicTest.dynamicTest(test.path("name").asText(),() -> {
   var response=mvc.perform(post("/api/v1/chunker/preview").header("Authorization","Bearer "+token).contentType("application/json").content(java.util.Base64.getDecoder().decode(test.path("body_base64").asText()))).andExpect(status().is(test.path("status").asInt())).andReturn().getResponse();
   compare(test.path("response"),mapper.readTree(response.getContentAsByteArray()));
   assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM chunks",Integer.class));
   assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM knowledges",Integer.class));
  }));
 }
 @Test void authenticationAndTenantBoundary() throws Exception {
  mvc.perform(post("/api/v1/chunker/preview").contentType("application/json").content("{\"text\":\"hi\"}")).andExpect(status().isUnauthorized());
  mvc.perform(post("/api/v1/chunker/preview").header("Authorization","Bearer "+token).header("X-Tenant-ID","2").contentType("application/json").content("{\"text\":\"hi\"}")).andExpect(status().isForbidden());
  mvc.perform(get("/api/v1/chunker/preview").header("Authorization","Bearer "+token)).andExpect(status().isMethodNotAllowed());
 }
 private static void compare(JsonNode expected,JsonNode actual) {
  if(expected.isNumber()&&actual.isNumber()){assertEquals(expected.asDouble(),actual.asDouble(),1e-10);return;}
  if(expected.isObject()&&actual.isObject()){assertEquals(expected.size(),actual.size());expected.fields().forEachRemaining(field -> compare(field.getValue(),actual.path(field.getKey())));return;}
  if(expected.isArray()&&actual.isArray()){assertEquals(expected.size(),actual.size());for(int i=0;i<expected.size();i++)compare(expected.get(i),actual.get(i));return;}
  assertEquals(expected,actual);
 }
}
