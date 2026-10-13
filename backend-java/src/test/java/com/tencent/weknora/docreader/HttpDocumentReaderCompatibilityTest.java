package com.tencent.weknora.docreader;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import com.tencent.weknora.model.ModelEgressPolicy;
import com.tencent.weknora.model.ModelURLPolicy;
import com.tencent.weknora.model.client.*;
import io.grpc.Context;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class HttpDocumentReaderCompatibilityTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final SafeModelHttp.AddressResolver LOOPBACK=(uri,call)->{call.check();try{return new InetAddress[]{InetAddress.getByName("127.0.0.1")};}catch(UnknownHostException ex){throw new AssertionError(ex);}};
    @Test void actualGoRequestsResponsesErrorsAndTypedWireDecoding()throws Exception{
        JsonNode fixtures=JSON.readTree(getClass().getResourceAsStream("/go-docreader-http.json"));
        AtomicReference<JsonNode> fixture=new AtomicReference<>(),captured=new AtomicReference<>();AtomicInteger requests=new AtomicInteger();
        HttpServer server=server();server.createContext("/prefix/",e->{JsonNode f=fixture.get();assertThat(e.getRequestMethod()).isEqualTo("POST");assertThat(e.getRequestURI().getPath()).isEqualTo("/prefix/"+f.path("operation").asText());assertThat(e.getRequestHeaders().getFirst("Content-Type")).isEqualTo("application/json");byte[] input=e.getRequestBody().readAllBytes();assertThat(e.getRequestHeaders().getFirst("Content-Length")).isEqualTo(Integer.toString(input.length));captured.set(JSON.readTree(input));requests.incrementAndGet();byte[] body=Base64.getDecoder().decode(f.path("body_base64").asText());e.sendResponseHeaders(f.path("status").asInt(),f.path("status").asInt()==204?-1:body.length);if(f.path("status").asInt()!=204&&body.length>0)e.getResponseBody().write(body);e.close();});server.start();
        try(var client=new HttpDocumentReader("\u0085"+url(server)+"/prefix/\u3000",LOOPBACK)){
            for(JsonNode f:fixtures){fixture.set(f);captured.set(null);java.util.function.Supplier<JsonNode> action=()->f.path("operation").asText().equals("read")?JSON.valueToTree(client.read(request(f.path("request")))):JSON.valueToTree(client.listEngines(map(f.path("overrides"))));
                if(f.has("error"))assertThatThrownBy(action::get).as(f.path("name").asText()).hasMessage(f.path("error").asText());
                else{
                    boolean read=f.path("operation").asText().equals("read");
                    JsonNode expected=read?expectedRead(f.path("result")):expectedEngines(f.path("result"));
                    if(read)assertThat(numbers(action.get())).as(f.path("name").asText()).isEqualTo(numbers(expected));
                    else {
                        var decoded=client.listEngines(map(f.path("overrides")));
                        assertThat(numbers(JSON.valueToTree(decoded))).as(f.path("name").asText()).isEqualTo(numbers(expected));
                        for(int i=0;i<decoded.size();i++)assertThat(decoded.get(i).fileTypesNil()).as("Go nil FileTypes: %s item %d",f.path("name").asText(),i).isEqualTo(f.path("result").get(i).path("FileTypes").isNull());
                    }
                }
                assertThat(captured.get()).as("request %s",f.path("name").asText()).isEqualTo(f.path("captured"));
            }assertThat(requests).hasValue(136);
        }finally{server.stop(0);}
    }
    @Test void containerTransportChoiceMatchesGoAndDefaultRemainsGrpc()throws Exception{
        var config=new DocReaderConfiguration();
        for(String transport:List.of("http","HTTPS","\u0085 HTTP \u3000")){var env=new MockEnvironment().withProperty("DOCREADER_TRANSPORT",transport).withProperty("GRPC_TLS_ENABLED","true").withProperty("GRPC_TLS_CERT","/nonexistent/ignored-http-cert");try(var reader=config.docReaderClient(env,new ModelEgressPolicy(null,JSON,env),new ModelURLPolicy(null,JSON,env))){assertThat(reader).isInstanceOf(HttpDocumentReader.class);assertThat(reader.isConnected()).isFalse();}env.withProperty("DOCREADER_ADDR","127.0.0.1:8888/").withProperty("SSRF_WHITELIST_EXTRA","127.0.0.1");try(var reader=config.docReaderClient(env,new ModelEgressPolicy(null,JSON,env),new ModelURLPolicy(null,JSON,env))){assertThat(reader).isInstanceOf(HttpDocumentReader.class);assertThat(reader.isConnected()).isTrue();}}
        for(String transport:List.of("","grpc","unexpected")){var env=new MockEnvironment().withProperty("DOCREADER_TRANSPORT",transport);try(var reader=config.docReaderClient(env,new ModelEgressPolicy(null,JSON,env),new ModelURLPolicy(null,JSON,env))){assertThat(reader).isInstanceOf(DocReaderClient.class);assertThat(reader.isConnected()).isFalse();}}
    }
    @Test void whitelistedAddressConfigurationDoesNotRequireLiveDnsButDialDoes()throws Exception{
        var env=new MockEnvironment().withProperty("DOCREADER_TRANSPORT","http").withProperty("DOCREADER_ADDR","http://not-yet-created.invalid/").withProperty("SSRF_WHITELIST_EXTRA","not-yet-created.invalid");
        AtomicInteger lookups=new AtomicInteger();var egress=new ModelEgressPolicy(null,JSON,env,host->{lookups.incrementAndGet();throw new UnknownHostException("not created");});
        try(var reader=new DocReaderConfiguration().docReaderClient(env,egress,new ModelURLPolicy(null,JSON,env))){assertThat(reader.isConnected()).isTrue();assertThat(lookups).hasValue(0);assertThatThrownBy(()->reader.read(request(JSON.createObjectNode()))).hasMessageContaining("DNS resolution failed");assertThat(lookups).hasValue(1);}
    }
    @Test void ssrfIsCheckedBeforeDialAndFailedReconnectPreservesConnection()throws Exception{
        var guard=new ModelEgressPolicy(null,JSON,new MockEnvironment());assertThatThrownBy(()->new HttpDocumentReader("http://127.0.0.1:9999",guard)).hasMessageStartingWith("docreader address failed SSRF validation:");assertThatThrownBy(()->new HttpDocumentReader("file:///etc/passwd",guard)).hasMessageContaining("invalid http/https URL");
        HttpServer server=server();AtomicInteger requests=new AtomicInteger();server.createContext("/read",e->{requests.incrementAndGet();byte[] b="{\"markdown_content\":\"safe\"}".getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,b.length);e.getResponseBody().write(b);e.close();});server.start();AtomicBoolean permit=new AtomicBoolean(true);AtomicInteger validations=new AtomicInteger();
        SafeModelHttp.AddressResolver policy=(uri,call)->{validations.incrementAndGet();if(!permit.get()||uri.getHost().equals("blocked.example"))throw new ModelClientException("blocked by test DNS policy");return LOOPBACK.resolve(uri,call);};
        try(var client=new HttpDocumentReader(url(server),policy)){assertThatThrownBy(()->client.reconnect("http://blocked.example")).hasMessageContaining("SSRF validation");assertThat(client.read(request(JSON.createObjectNode())).markdownContent()).isEqualTo("safe");permit.set(false);assertThatThrownBy(()->client.read(request(JSON.createObjectNode()))).hasMessageContaining("SSRF validation");assertThat(requests).hasValue(1);assertThat(validations.get()).isGreaterThanOrEqualTo(5);client.reconnect("");assertThat(client.isConnected()).isFalse();assertThatThrownBy(()->client.listEngines(Map.of())).hasMessage("docreader service not connected");}finally{server.stop(0);}
    }
    @Test void parentDeadlineAndCancellationInterruptAnInFlightResponse()throws Exception{
        HttpServer server=server();CountDownLatch started=new CountDownLatch(2),release=new CountDownLatch(1);ExecutorService handlers=Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(handlers);
        server.createContext("/read",e->{started.countDown();try{release.await(3,TimeUnit.SECONDS);e.sendResponseHeaders(200,2);e.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8));}catch(Exception ignored){}finally{e.close();}});server.start();ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor();
        try(var client=new HttpDocumentReader(url(server),LOOPBACK);var executor=Executors.newVirtualThreadPerTaskExecutor();var timed=Context.current().withDeadlineAfter(500,TimeUnit.MILLISECONDS,deadlines);var cancelled=Context.current().withCancellation()){
            long start=System.nanoTime();Future<?> a=executor.submit(()->timed.run(()->client.read(request(JSON.createObjectNode()))));Future<?> b=executor.submit(()->cancelled.run(()->client.read(request(JSON.createObjectNode()))));assertThat(started.await(2,TimeUnit.SECONDS)).isTrue();cancelled.cancel(null);assertThatThrownBy(()->a.get(2,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);assertThatThrownBy(()->b.get(2,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(2));
        }finally{release.countDown();deadlines.shutdownNow();server.stop(0);handlers.shutdownNow();}
    }
    @Test void redirectsStayWithinCheckedOriginAndRepeatDnsValidation()throws Exception{
        HttpServer server=server();AtomicInteger reads=new AtomicInteger(),validated=new AtomicInteger();server.createContext("/read",e->{reads.incrementAndGet();e.getRequestBody().readAllBytes();e.getResponseHeaders().set("Location","/result");e.sendResponseHeaders(307,-1);e.close();});server.createContext("/result",e->{assertThat(e.getRequestMethod()).isEqualTo("POST");e.getRequestBody().readAllBytes();byte[] b="{\"markdown_content\":\"redirected\"}".getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,b.length);e.getResponseBody().write(b);e.close();});server.start();try(var client=new HttpDocumentReader(url(server),(uri,call)->{validated.incrementAndGet();return LOOPBACK.resolve(uri,call);})){assertThat(client.read(request(JSON.createObjectNode())).markdownContent()).isEqualTo("redirected");assertThat(reads).hasValue(1);assertThat(validated.get()).isGreaterThanOrEqualTo(4);}finally{server.stop(0);}
    }
    static HttpServer server()throws Exception{return HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);}
    static String url(HttpServer s){return "http://127.0.0.1:"+s.getAddress().getPort();}
    static Map<String,String> map(JsonNode n){Map<String,String> out=new LinkedHashMap<>();if(n.isObject())n.fields().forEachRemaining(e->out.put(e.getKey(),e.getValue().asText()));return out;}
    static byte[] bytes(JsonNode n){return n.isTextual()?Base64.getDecoder().decode(n.asText()):new byte[0];}
    static DocumentReadRequest request(JsonNode n){return new DocumentReadRequest(bytes(n.path("FileContent")),n.path("FileName").asText(""),n.path("FileType").asText(""),n.path("URL").asText(""),n.path("Title").asText(""),n.path("ParserEngine").asText(""),n.path("RequestID").asText(""),map(n.path("ParserEngineOverrides")));}
    static JsonNode expectedRead(JsonNode n){List<DocumentReadResult.ImageRef> images=new ArrayList<>();if(n.path("ImageRefs").isArray())for(JsonNode i:n.path("ImageRefs"))images.add(new DocumentReadResult.ImageRef(i.path("Filename").asText(),i.path("OriginalRef").asText(),i.path("MimeType").asText(),i.path("StorageKey").asText(),bytes(i.path("ImageData")),i.path("IsOriginal").asBoolean()));List<DocumentReadResult.SourceBlock> blocks=new ArrayList<>();if(n.path("SourceBlocks").isArray())for(JsonNode b:n.path("SourceBlocks"))blocks.add(new DocumentReadResult.SourceBlock(b.path("Start").asLong(),b.path("End").asLong(),JSON.convertValue(b.path("Locator"),Map.class)));return JSON.valueToTree(new DocumentReadResult(n.path("MarkdownContent").asText(),images,n.path("ImageDirPath").asText(),map(n.path("Metadata")),n.path("Error").asText(),blocks,n.path("IsAudio").asBoolean(),bytes(n.path("AudioData"))));}
    static JsonNode expectedEngines(JsonNode n){List<DocumentReadResult.ParserEngineInfo> out=new ArrayList<>();for(JsonNode e:n){List<String> types=new ArrayList<>();if(e.path("FileTypes").isArray())e.path("FileTypes").forEach(t->types.add(t.asText()));out.add(new DocumentReadResult.ParserEngineInfo(e.path("Name").asText(""),e.path("Description").asText(""),types,e.path("Available").asBoolean(),e.path("UnavailableReason").asText("")));}return JSON.valueToTree(out);}
    static JsonNode numbers(JsonNode n){if(n.isNumber())return DecimalNode.valueOf(n.decimalValue().stripTrailingZeros());if(n.isObject()){ObjectNode out=JSON.createObjectNode();n.fields().forEachRemaining(e->out.set(e.getKey(),numbers(e.getValue())));return out;}if(n.isArray()){ArrayNode out=JSON.createArrayNode();n.forEach(v->out.add(numbers(v)));return out;}return n;}
}
