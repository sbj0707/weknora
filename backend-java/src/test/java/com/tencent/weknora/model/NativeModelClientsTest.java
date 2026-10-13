package com.tencent.weknora.model;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.*;
import com.tencent.weknora.model.client.CallContext;
import com.tencent.weknora.model.client.ModelEndpoint;
import com.tencent.weknora.model.client.OpenAIChat;
import com.tencent.weknora.security.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

/** Real native network calls against loopback, compared with actual Go factory output. */
class NativeModelClientsTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static JsonNode fixtures()throws Exception{return JSON.readTree(Files.readString(Path.of("src/test/java/com/tencent/weknora/model/client/go-client-fixtures.json")));}
    static List<ObjectNode> messages(JsonNode node){List<ObjectNode> list=new ArrayList<>();node.forEach(m->list.add((ObjectNode)m));return list;}
    static JsonNode normalized(JsonNode node){if(node.isNumber())return DecimalNode.valueOf(node.decimalValue().stripTrailingZeros());if(node.isObject()){ObjectNode copy=JSON.createObjectNode();node.fields().forEachRemaining(e->copy.set(e.getKey(),normalized(e.getValue())));return copy;}if(node.isArray()){ArrayNode copy=JSON.createArrayNode();node.forEach(v->copy.add(normalized(v)));return copy;}return node;}
    static void equalJSON(Object actual,JsonNode expected,String label)throws Exception{assertThat(normalized(JSON.readTree(JSON.writeValueAsString(actual)))).as(label).isEqualTo(normalized(expected));}
    @Test void actualGoRequestBuildersAcrossEveryProviderAndThinkingLevel()throws Exception{
        CatalogRuntime catalog=CatalogRuntime.builtins();int count=0;
        for(JsonNode f:fixtures().path("chat_bodies")){
            var r=catalog.resolve(new CatalogRuntime.Ref(f.path("provider").asText(),"unknown","https://relay.invalid/v1","KnowledgeQA",JSON.createObjectNode().put("api","openai-completions"),null,0));
            ModelEndpoint e=new ModelEndpoint("fixture","unknown",r.remoteModel(),"https://relay.invalid/v1/chat/completions",(ObjectNode)r.settings().path("openai_completions"),r.thinkingLevels(),r.model().path("reasoning").asBoolean(),Map.of(),List.of());
            OpenAIChat client=new OpenAIChat(null,e,Duration.ofSeconds(1),Duration.ofSeconds(1));
            equalJSON(client.request(messages(f.path("messages")),(ObjectNode)f.path("options"),true),f.path("body"),f.path("provider")+" "+f.path("level"));count++;
        }assertThat(count).isEqualTo(189);
    }
    @Test void actualGoEmbeddingRerankChatAndSSEOverRealHTTP()throws Exception{
        try(Harness h=new Harness()){
            int count=0;
            for(JsonNode f:fixtures().path("wire")){
                h.fixture=f;h.requests.clear();String id="wire-"+(count++);ObjectNode p=(ObjectNode)f.path("parameters").deepCopy();p.put("base_url",h.url()+p.remove("base_suffix").asText());p.put("api_key",h.secrets.encryptLegacyWhenConfigured("synthetic-api-key"));
                h.seed(id,1,false,"active",f.path("name").asText(),f.path("type").asText(),p);
                Object result;try(var call=CallContext.timeout(Duration.ofSeconds(5))){switch(f.path("kind").asText()){
                    case "embedding":result=h.factory.embedding(h.viewer,id).embed(call,List.of("a","three","中😀"),true);break;
                    case "rerank":ArrayNode ranks=JSON.createArrayNode();for(var rank:h.factory.rerank(h.viewer,id).rerank(call,"query",List.of("a","three","cc"))){ObjectNode item=JSON.createObjectNode().put("index",rank.index()).put("relevance_score",rank.relevanceScore());item.set("document",JSON.createObjectNode().put("text",rank.text()));ranks.add(item);}result=ranks;break;
                    case "chat":result=h.factory.chat(h.viewer,id).chat(call,messages(f.path("messages")),(ObjectNode)f.path("options"));break;
                    default:List<ObjectNode> chunks=new ArrayList<>();h.factory.chat(h.viewer,id).stream(call,messages(f.path("messages")),(ObjectNode)f.path("options"),chunks::add);result=chunks;
                }}
                String label=f.path("kind")+" "+f.path("provider")+" "+f.path("name");equalJSON(result,f.path("result"),"result "+label);equalJSON(h.requests,f.path("requests"),"wire "+label);
            }assertThat(count).isEqualTo(27);
        }
    }
    @Test void tenantCredentialAndProtocolFailuresNeverSendRequests()throws Exception{
        try(Harness h=new Harness()){
            ObjectNode p=JSON.createObjectNode().put("provider","generic").put("base_url",h.url()+"/v1").put("api_key",h.secrets.encryptLegacyWhenConfigured("synthetic-api-key"));
            h.seed("foreign",2,false,"active","unknown","Embedding",p);h.seed("builtin",2,true,"active","unknown","Embedding",p);h.seed("inactive",1,false,"downloading","unknown","Embedding",p);
            assertThatThrownBy(()->h.factory.embedding(h.viewer,"foreign")).isInstanceOf(ApiException.class).hasMessage("Model not found");
            assertThat(h.factory.embedding(h.viewer,"builtin").modelId()).isEqualTo("builtin");
            assertThatThrownBy(()->h.factory.embedding(h.viewer,"inactive")).hasMessage("model is currently downloading");
            assertThatThrownBy(()->h.factory.embedding(new AuthContext("x",1,"guest",false,null,false,Set.of(),Set.of()),"builtin")).isInstanceOf(ApiException.class);
            p.put("api_key","enc:v1:invalid");h.seed("broken-secret",1,false,"active","unknown","Embedding",p);assertThatThrownBy(()->h.factory.embedding(h.viewer,"broken-secret")).isInstanceOf(ApiException.class);
            p.put("api_key","");p.put("provider","openai");p.set("extra_config",JSON.createObjectNode().put("api","openai-responses"));h.seed("responses",1,false,"active","gpt-5","KnowledgeQA",p);assertThat(h.factory.chat(h.viewer,"responses")).isInstanceOf(com.tencent.weknora.model.client.OpenAIResponses.class);p.set("extra_config",JSON.createObjectNode().put("api","google-generative-ai"));h.seed("unsupported-chat",1,false,"active","unknown","KnowledgeQA",p);assertThatThrownBy(()->h.factory.chat(h.viewer,"unsupported-chat")).hasMessageContaining("google-generative-ai");assertThat(h.requests).isEmpty();
        }
    }
    static final class Harness implements AutoCloseable {
        final Path db;final org.springframework.mock.env.MockEnvironment env;final HttpServer server;final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();final JdbcTemplate jdbc;final GoSecretCodec secrets=new GoSecretCodec("0123456789abcdef0123456789abcdef");final ModelClientFactory factory;final AuthContext viewer=new AuthContext("viewer",1,"viewer",false,null,false,Set.of(),Set.of());final List<ObjectNode> requests=new CopyOnWriteArrayList<>();volatile JsonNode fixture;
        Harness()throws Exception{
            db=Files.createTempFile("weknora-native-model-client-",".db");DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:sqlite:"+db);ds.setDriverClassName("org.sqlite.JDBC");jdbc=new JdbcTemplate(ds);
            try(var c=ds.getConnection()){for(String migration:List.of("000000_init","000007_system_admin_and_settings","000011_principal_model","000031_model_catalog_config"))ScriptUtils.executeSqlScript(c,new FileSystemResource("../migrations/sqlite/"+migration+".up.sql"));}
            jdbc.update("INSERT INTO tenants(id,name,business) VALUES(1,'one','test'),(2,'two','test')");
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(executor);server.createContext("/",this::handle);server.start();
            env=new MockEnvironment().withProperty("SSRF_WHITELIST","127.0.0.1").withProperty("SSRF_DNS_WHITELIST_ONLY","true");factory=new ModelClientFactory(jdbc,JSON,secrets,new ModelCatalogRuntime(jdbc,"","/nonexistent"),new ModelEgressPolicy(jdbc,JSON,env),env);
        }
        String url(){return "http://127.0.0.1:"+server.getAddress().getPort();}
        void seed(String id,long tenant,boolean builtin,String status,String name,String type,JsonNode params){jdbc.update("INSERT INTO models(id,tenant_id,name,type,source,parameters,is_builtin,status,created_at,updated_at) VALUES(?,?,?,?,'remote',?,?,?,'2026-10-01 00:00:00','2026-10-01 00:00:00')",id,tenant,name,type,params.toString(),builtin,status);}
        void handle(HttpExchange x)throws java.io.IOException{
            JsonNode f=fixture;JsonNode body=JSON.readTree(x.getRequestBody());ObjectNode request=JSON.createObjectNode().put("path",x.getRequestURI().getPath()).put("query",Objects.toString(x.getRequestURI().getRawQuery(),""));request.set("body",body);ObjectNode headers=JSON.createObjectNode();for(String key:List.of("Authorization","api-key","x-api-key","anthropic-version","anthropic-beta","X-Test","Accept","session_id","x-client-request-id","x-session-affinity")){String value=x.getRequestHeaders().getFirst(key);if(value!=null&&!value.isEmpty())headers.put(key.toLowerCase(Locale.ROOT),value);}request.set("headers",headers);requests.add(request);
            ObjectNode response=JSON.createObjectNode();String text;
            switch(f.path("kind").asText()){
                case "embedding":ArrayNode data=JSON.createArrayNode();JsonNode input=body.path("input");for(int i=input.size()-1;i>=0;i--){ObjectNode row=JSON.createObjectNode().put("index",i);row.set("embedding",JSON.createArrayNode().add(input.get(i).asText().getBytes(StandardCharsets.UTF_8).length).add(0.12345678f));data.add(row);}response.set("data",data);text=response.toString();break;
                case "rerank":ArrayNode results=JSON.createArrayNode();JsonNode docs=body.path("documents");for(int i=docs.size()-1;i>=0;i--){ObjectNode row=JSON.createObjectNode().put("index",i).put("relevance_score",docs.get(i).asText().getBytes(StandardCharsets.UTF_8).length/10.0);row.set("document",JSON.createObjectNode().put("text",docs.get(i).asText()));results.add(row);}response.set("results",results);text=response.toString();break;
                default:text=f.path("payload").asText();
            }
            byte[] bytes=text.getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type",f.path("content_type").asText(f.path("kind").asText().equals("stream")?"text/event-stream":"application/json"));x.sendResponseHeaders(200,bytes.length);try(var out=x.getResponseBody()){out.write(bytes);}x.close();
        }
        @Override public void close()throws Exception{factory.close();server.stop(0);executor.shutdownNow();Files.deleteIfExists(db);}
    }
}
