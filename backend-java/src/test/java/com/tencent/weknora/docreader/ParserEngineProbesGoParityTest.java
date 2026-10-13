package com.tencent.weknora.docreader;
import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.docreader.*;import com.tencent.weknora.model.client.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;import java.nio.file.*;import java.util.*;import java.util.concurrent.atomic.AtomicReference;import java.util.Base64;
class ParserEngineProbesGoParityTest {
record Request(String method,String path,String key,String body){}
@org.junit.jupiter.api.Test void actualGoProbeRequestsAndAvailabilityMatchControlledHttp()throws Exception{
var json=new ObjectMapper();var fixtures=json.readTree(getClass().getResourceAsStream("/go-parser-probes.json"));var active=new AtomicReference<JsonNode>();var calls=new ArrayList<Request>();
var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
server.createContext("/",exchange->{var scene=active.get();calls.add(new Request(exchange.getRequestMethod(),exchange.getRequestURI().toString(),Optional.ofNullable(exchange.getRequestHeaders().getFirst("Authorization")).orElse(""),new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)));boolean health=exchange.getRequestURI().getPath().endsWith("/v1/health");int status=scene.path(health?"health_status":"status").asInt();byte[] body=health?Base64.getDecoder().decode(scene.path("health_body_base64").asText()):"{}".getBytes();exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,status==204?-1:body.length);if(status!=204)exchange.getResponseBody().write(body);exchange.close();});server.start();
String base="http://127.0.0.1:"+server.getAddress().getPort();int count=0;
var loopback=InetAddress.getByName("127.0.0.1");try(var http=new SafeModelHttp((uri,context)->new InetAddress[]{loopback},5,true)){
var probes=new ParserEngineProbes(http,url->{if(!url.startsWith(base))throw new AssertionError("external network not allowed");},base+"/api/v4");
for(var c:fixtures){active.set(c);calls.clear();String engine=c.path("engine").asText();var config=new HashMap<String,String>();config.put("mineru_endpoint",c.path("endpoint").asText().replace("{base}",base));config.put("paddleocr_vl_endpoint",config.get("mineru_endpoint"));config.put("mineru_api_key",c.path("key").asText());config.put("mineru_server_api_key",c.path("key").asText());var actual=probes.check(engine,config);
if(actual.available()!=c.path("available").asBoolean()||!actual.reason().equals(c.path("reason").asText()))throw new AssertionError(c.path("name")+" expected "+c.get("reason")+" actual "+actual);
if(!json.valueToTree(calls).equals(c.get("requests")))throw new AssertionError(c.path("name")+" requests expected "+c.get("requests")+" actual "+json.valueToTree(calls));count++;
}
}finally{server.stop(0);}
System.out.println(count+" actual Go probe scenarios passed against real loopback HTTP; full controller and Go network-error strings pending");}}
