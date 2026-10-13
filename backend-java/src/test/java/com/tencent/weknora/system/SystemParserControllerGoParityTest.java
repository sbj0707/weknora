package com.tencent.weknora.system;
import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.docreader.*;import com.tencent.weknora.system.*;import com.tencent.weknora.security.*;
import org.springframework.mock.env.MockEnvironment;import org.springframework.test.web.servlet.setup.MockMvcBuilders;import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;import org.springframework.http.*;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.jdbc.datasource.DriverManagerDataSource;import org.springframework.jdbc.datasource.init.ScriptUtils;import org.springframework.core.io.FileSystemResource;
import java.nio.file.*;import java.util.*;
class SystemParserControllerGoParityTest {
static class Reader implements RemoteDocumentReader {
JsonNode fixture;Map<String,String> seen;String addr="";boolean connected;
Reader(JsonNode fixture){this.fixture=fixture;connected=fixture.path("connected").asBoolean();}
public DocumentReadResult read(DocumentReadRequest request){throw new UnsupportedOperationException();}
public boolean isConnected(){return connected;}
public void reconnect(String address)throws java.io.IOException{addr=address;if(fixture.path("fail_reconnect").asBoolean())throw new java.io.IOException("fixture reconnect failed");connected=true;}
public java.util.List<DocumentReadResult.ParserEngineInfo> listEngines(Map<String,String> overrides){seen=overrides;if(fixture.path("fail_list").asBoolean())throw new IllegalStateException("fixture list failed");List<DocumentReadResult.ParserEngineInfo> out=new ArrayList<>();for(var e:fixture.path("remote")){List<String> types=new ArrayList<>();if(e.path("FileTypes").isArray())e.path("FileTypes").forEach(t->types.add(t.asText()));out.add(new DocumentReadResult.ParserEngineInfo(e.path("Name").asText(""),e.path("Description").asText(""),types,e.path("Available").asBoolean(),e.path("UnavailableReason").asText(""),e.path("FileTypes").isNull()));}return out;}
public void close(){}
}
@org.junit.jupiter.api.Test void actualGoHandlersMatchJavaMvcUsingProductionGoSchema()throws Exception{
var json=new ObjectMapper();var cases=json.readTree(getClass().getResourceAsStream("/go-system-parser.json"));var settings=new ParserEngineSettings(json,json.readTree(getClass().getResourceAsStream("/parser-settings-schema.json")));
var file=Files.createTempFile("system-parser-oracle-",".db");var datasource=new DriverManagerDataSource("jdbc:sqlite:"+file);var jdbc=new JdbcTemplate(datasource);try(var connection=datasource.getConnection()){ScriptUtils.executeSqlScript(connection,new FileSystemResource("../migrations/sqlite/000000_init.up.sql"));}
var repository=new SystemParserRepository(jdbc,settings,new GoSecretCodec(""));var registry=new ParserEngineRegistry((engine,config)->{throw new AssertionError("unconfigured probe: "+engine);},new ParserEngineRegistry.Availability(false,"anydoc engine not built into this binary (rebuild with `make build-anydoc` / `-tags anydoc`; Docker images need `--build-arg WITH_ANYDOC=1`)"));int count=0;
for(var c:cases){jdbc.update("DELETE FROM tenants");jdbc.update("INSERT INTO tenants(id,name,business,parser_engine_config,credentials) VALUES(1,'fixture','test',?,?)",c.path("existing").isNull()?null:json.writeValueAsBytes(c.get("existing")),c.path("credentials").isNull()?null:json.writeValueAsBytes(c.get("credentials")));var reader=new Reader(c);var environment=new MockEnvironment().withProperty("DOCREADER_ADDR",c.path("addr").asText()).withProperty("DOCREADER_TRANSPORT",c.path("transport").asText());var controller=new SystemParserController(repository,settings,registry,c.path("nil_reader").asBoolean()?null:reader,environment,(java.util.function.Consumer<String>)addr->{if(!addr.contains("127.0.0.1")&&!addr.contains("docreader"))throw new AssertionError("unexpected URL "+addr);});var mvc=MockMvcBuilders.standaloneSetup(controller).build();var result=mvc.perform(MockMvcRequestBuilders.request(HttpMethod.valueOf(c.path("method").asText()),c.path("path").asText()).content(c.path("body").asText()).contentType(MediaType.APPLICATION_JSON).requestAttr(CurrentAuth.ATTRIBUTE,new AuthContext("owner",1,"owner",false,null,false,Set.of(),Set.of()))).andReturn().getResponse();var actual=json.readTree(result.getContentAsByteArray());
if(result.getStatus()!=c.path("status").asInt()||!actual.equals(c.get("response")))throw new AssertionError(c.get("name")+" expected "+c.get("response")+" actual "+actual);
if(!json.valueToTree(reader.seen).equals(c.get("seen")))throw new AssertionError(c.get("name")+" overrides expected "+c.get("seen")+" actual "+reader.seen);
if(!reader.addr.equals(c.path("reconnect_addr").asText()))throw new AssertionError(c.get("name")+" reconnect mismatch");count++;
}
Files.deleteIfExists(file);System.out.println(count+" actual Go handler HTTP responses matched Java MVC with production Go SQLite schema; auth filter/role guard process verification pending");}}
