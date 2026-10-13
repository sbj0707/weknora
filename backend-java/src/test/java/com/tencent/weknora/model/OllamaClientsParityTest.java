package com.tencent.weknora.model;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.model.client.CallContext;
import com.tencent.weknora.model.client.ChatModel;
import com.tencent.weknora.model.client.EmbeddingModel;
import com.tencent.weknora.model.client.OllamaChat;
import com.tencent.weknora.model.client.OllamaEmbedding;
import com.tencent.weknora.model.client.ModelClientException;
import com.sun.net.httpserver.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OllamaClientsParityTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static JsonNode fixtures()throws Exception{return JSON.readTree(Files.readString(Path.of(System.getProperty("weknora.ollama.fixture","src/test/java/com/tencent/weknora/model/client/go-ollama.json"))));}
    static final class Server implements AutoCloseable {
        final HttpServer server=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);final ExecutorService executor=Executors.newCachedThreadPool();
        final JsonNode fixture;final List<ObjectNode> requests=Collections.synchronizedList(new ArrayList<>());int tags;boolean pulled;
        Server(JsonNode f)throws Exception{fixture=f;server.setExecutor(executor);server.createContext("/",this::handle);server.start();}
        String url(){return "http://127.0.0.1:"+server.getAddress().getPort();}
        void handle(HttpExchange x)throws java.io.IOException{
            byte[] input=x.getRequestBody().readAllBytes();ObjectNode request=JSON.createObjectNode().put("method",x.getRequestMethod()).put("path",x.getRequestURI().getPath()).put("query",Objects.toString(x.getRequestURI().getRawQuery(),""));request.set("body",input.length==0?NullNode.instance:JSON.readTree(input));ObjectNode headers=JSON.createObjectNode();for(String key:List.of("Accept","Content-Type","Authorization")){String value=x.getRequestHeaders().getFirst(key);if(value!=null&&!value.isEmpty())headers.put(key.toLowerCase(Locale.ROOT),value);}request.set("headers",headers);requests.add(request);
            if(x.getRequestURI().getPath().startsWith("/image")){String path=x.getRequestURI().getPath();if(path.equals("/image-redirect")||path.equals("/image-blocked-redirect")){x.getResponseHeaders().set("Location",path.equals("/image-redirect")?"/image":"http://169.254.169.254/image");x.sendResponseHeaders(302,-1);}else{byte[] output=(path.equals("/image-error")?"image error bytes":"remote image bytes").getBytes(StandardCharsets.UTF_8);x.sendResponseHeaders(path.equals("/image-error")?400:200,output.length);try(var out=x.getResponseBody()){out.write(output);}}x.close();return;}
            if(x.getRequestMethod().equals("HEAD")){x.sendResponseHeaders(fixture.path("heartbeat_status").asInt()>0?fixture.path("heartbeat_status").asInt():200,-1);x.close();return;}
            String payload=fixture.path("payload").asText();int status=fixture.path("status").asInt();
            if(x.getRequestURI().getPath().endsWith("/api/tags")){tags++;String name=fixture.path("model").asText();if(name.isEmpty())name="nomic-embed-text";if(!name.contains(":"))name+=":latest";payload=fixture.path("missing").asBoolean()&&!pulled||fixture.path("missing_once").asBoolean()&&tags==1?"{\"models\":[]}":"{\"models\":[{\"name\":\""+name+"\"}]}";status=200;}
            else if(x.getRequestURI().getPath().endsWith("/api/pull")){pulled=true;payload="{\"status\":\"success\",\"total\":2,\"completed\":2}\n";status=200;}
            else if(fixture.path("kind").asText().equals("embed")&&(fixture.path("name").asText().contains("availability")||fixture.path("name").asText().matches("embed-[0-2]-(false|true)"))){ArrayNode vectors=JSON.createArrayNode();for(int i=0;i<fixture.path("texts").size();i++)vectors.add(JSON.createArrayNode().add(i).add(.123456789f));payload=JSON.createObjectNode().set("embeddings",vectors).toString();}
            byte[] output=payload.getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","application/x-ndjson");x.sendResponseHeaders(status,output.length);try(var out=x.getResponseBody()){out.write(output);}x.close();
        }
        @Override public void close(){server.stop(0);executor.shutdownNow();}
    }
    @Test void actualGoOllamaHTTPAvailabilityRequestsChatToolsAndNDJSON()throws Exception {
        int cases=0;for(JsonNode f:fixtures().path("cases")){
            try(Server server=new Server(f);var h=new NativeModelClientsTest.Harness()){
                h.env.setProperty("OLLAMA_BASE_URL",server.url()+f.path("base_suffix").asText());h.env.setProperty("OLLAMA_OPTIONAL",Boolean.toString(f.path("optional").asBoolean()));ObjectNode params=JSON.createObjectNode().put("base_url","https://ignored.invalid");params.set("embedding_parameters",JSON.createObjectNode().put("dimension",f.path("dimensions").asInt()).put("truncate_prompt_tokens",f.path("truncate").asInt()).put("supports_dimension_override",f.path("supports_dimension").asBoolean()));String kind=f.path("kind").asText();h.seed("ollama",1,false,"active",f.path("model").asText(),kind.equals("embed")?"Embedding":"KnowledgeQA",params);h.jdbc.update("UPDATE models SET source='local' WHERE id='ollama'");ObjectNode options=f.path("options").isNull()?null:(ObjectNode)f.path("options");List<Object> results=new ArrayList<>();
                for(int i=0;i<f.path("calls").asInt();i++)try(var call=CallContext.timeout(Duration.ofSeconds(5))){
                    if(kind.equals("embed")){EmbeddingModel client=h.factory.embedding(h.viewer,"ollama");assertThat(client).isInstanceOf(OllamaEmbedding.class);List<String> texts=new ArrayList<>();f.path("texts").forEach(n->texts.add(n.asText()));
                        if(f.has("error")){assertThatThrownBy(()->client.embed(call,texts,false)).as(f.path("name").asText()).hasMessage(f.path("error").asText());break;}
                        if(Set.of("embed-shape-1","embed-shape-2","embed-shape-3").contains(f.path("name").asText())){assertThatThrownBy(()->client.embed(call,texts,false)).isInstanceOf(ModelClientException.class).hasMessageContaining("Ollama returned");break;}
                        float[][] vectors=client.embed(call,texts,false);JsonNode expected=f.path("calls").asInt()==1?f.path("result"):f.path("result").get(i);for(int row=0;row<vectors.length;row++)for(int column=0;column<vectors[row].length;column++)assertThat(Float.floatToRawIntBits(vectors[row][column])).as(f.path("name").asText()+" float32 "+row+":"+column).isEqualTo(f.has("float_bits")?(int)f.path("float_bits").get(row).get(column).asLong():Float.floatToRawIntBits(Float.parseFloat(expected.get(row).get(column).asText())));results.add(vectors);
                    }else{ChatModel client=h.factory.chat(h.viewer,"ollama");assertThat(client).isInstanceOf(OllamaChat.class);List<ObjectNode> messages=NativeModelClientsTest.messages(f.path("messages"));for(ObjectNode m:messages)if(m.path("images").isArray())for(int n=0;n<m.path("images").size();n++)((ArrayNode)m.get("images")).set(n,TextNode.valueOf(m.path("images").get(n).asText().replace("http://OLLAMA-IMAGE",server.url())));
                        if(f.has("error")){assertThatThrownBy(()->{if(kind.equals("stream"))client.stream(call,messages,options,e->{});else client.chat(call,messages,options);}).as(f.path("name").asText()).hasMessage(f.path("error").asText());break;}
                        if(kind.equals("stream")){List<ObjectNode> events=new ArrayList<>();client.stream(call,messages,options,events::add);results.add(events);}else results.add(client.chat(call,messages,options));
                    }
                }
                if(!f.has("error")&&!Set.of("embed-shape-1","embed-shape-2","embed-shape-3").contains(f.path("name").asText()))NativeModelClientsTest.equalJSON(f.path("calls").asInt()==1?results.getFirst():results,f.path("result"),"Ollama result "+f.path("name"));
                NativeModelClientsTest.equalJSON(server.requests,f.path("requests"),"Ollama requests "+f.path("name"));
            }cases++;
        }assertThat(cases).isEqualTo(78);
    }

    static void local(NativeModelClientsTest.Harness h,String id,long tenant,String status,String type){h.seed(id,tenant,false,status,"qwen3",type,JSON.createObjectNode());h.jdbc.update("UPDATE models SET source='local' WHERE id=?",id);}
    @Test void localModelsPreserveTenantStatusAndEgressGuardsAndRejectSignedAuth()throws Exception {
        JsonNode f=fixtures().path("cases").get(0);try(Server server=new Server(f);var h=new NativeModelClientsTest.Harness()){
            h.env.setProperty("OLLAMA_BASE_URL",server.url());local(h,"other",2,"active","KnowledgeQA");local(h,"inactive",1,"downloading","Embedding");
            assertThatThrownBy(()->h.factory.chat(h.viewer,"other")).hasMessage("Model not found");
            assertThatThrownBy(()->h.factory.embedding(h.viewer,"inactive")).hasMessage("model is currently downloading");
            local(h,"denied",1,"active","KnowledgeQA");h.env.setProperty("SSRF_WHITELIST","");
            try(var call=CallContext.timeout(Duration.ofSeconds(1))){assertThatThrownBy(()->h.factory.chat(h.viewer,"denied").chat(call,List.of(),null)).hasMessageContaining("SSRF whitelist");}
            assertThat(server.requests).isEmpty();
        }
        for(String auth:List.of("true","1","invalid","\"true\""))try(Server server=new Server(f);var h=new NativeModelClientsTest.Harness()){
            h.env.setProperty("OLLAMA_BASE_URL",server.url());h.env.setProperty("OLLAMA_AUTH",auth);local(h,"signed",1,"active","KnowledgeQA");
            try(var call=CallContext.timeout(Duration.ofSeconds(1))){assertThatThrownBy(()->h.factory.chat(h.viewer,"signed").chat(call,List.of(),null)).hasMessageContaining("signed authentication is not migrated");}
            assertThat(server.requests).isEmpty();
        }
    }
    @Test void localCallsShareCancellationAndDeadlineAcrossAvailabilityAndNDJSON()throws Exception {
        JsonNode f=fixtures().path("cases").get(12);try(Server server=new Server(f);var h=new NativeModelClientsTest.Harness()){
            h.env.setProperty("OLLAMA_BASE_URL",server.url());local(h,"cancel",1,"active","KnowledgeQA");ChatModel client=h.factory.chat(h.viewer,"cancel");
            try(var cancelled=new CallContext()){cancelled.cancel();assertThatThrownBy(()->client.chat(cancelled,List.of(),null)).isInstanceOf(CancellationException.class);assertThat(server.requests).isEmpty();}
            try(var expired=new CallContext(Instant.now().minusMillis(1))){assertThatThrownBy(()->client.chat(expired,List.of(),null)).hasMessageContaining("deadline exceeded");assertThat(server.requests).isEmpty();}
            try(var call=CallContext.timeout(Duration.ofSeconds(3))){List<ObjectNode> events=new ArrayList<>();assertThatThrownBy(()->client.stream(call,NativeModelClientsTest.messages(f.path("messages")),null,event->{events.add(event);call.cancel();})).isInstanceOf(CancellationException.class);assertThat(events).hasSize(1);}
        }
    }
}
