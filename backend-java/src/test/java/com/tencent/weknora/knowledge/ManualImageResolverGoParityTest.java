package com.tencent.weknora.knowledge;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import com.tencent.weknora.model.ModelEgressPolicy;
import com.tencent.weknora.model.client.SafeModelHttp;
import com.tencent.weknora.storage.FileStore;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.*;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;

/** Actual Go image resolver HTTP/parser/storage-call oracle, with no nested-string normalization. */
class ManualImageResolverGoParityTest {
  private final ObjectMapper json=new ObjectMapper();
  @TestFactory Stream<DynamicTest> actualGoImages()throws Exception {
    JsonNode cases;try(var in=new GZIPInputStream(getClass().getResourceAsStream("/go-manual-images.json.gz"))){cases=json.readTree(in);}
    assertThat(cases.size()).isEqualTo(57);
    return StreamSupport.stream(cases.spliterator(),false).map(c->DynamicTest.dynamicTest(c.path("name").asText(),()->compare(c)));
  }
  void compare(JsonNode c)throws Exception {
    var requests=json.createArrayNode();var saves=json.createArrayNode();byte[] bytes=Base64.getDecoder().decode(c.path("body").asText());
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/",e->{requests.add(json.createObjectNode().put("path",e.getRequestURI().toString()).put("user_agent",e.getRequestHeaders().getFirst("User-Agent")));String ct=c.path("content_type").asText();if(!ct.isEmpty())e.getResponseHeaders().set("Content-Type",ct);else e.getResponseHeaders().set("Content-Type","image/png");e.sendResponseHeaders(c.path("status").asInt()==0?200:c.path("status").asInt(),bytes.length);try(var out=e.getResponseBody()){out.write(bytes);}});server.start();
    String origin="http://127.0.0.1:"+server.getAddress().getPort();String authority=origin.substring(7);
    java.util.function.UnaryOperator<String> expand=s->s.replace("{server}",origin).replace("{authority}",authority);
    java.util.function.UnaryOperator<String> normalize=s->s.replace(origin,"{server}").replace(authority,"{authority}");
    FileStore store=new FileStore(){
      public String saveBytes(byte[] b,long owner,String name,boolean temporary)throws IOException{try{saves.add(json.createObjectNode().put("sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b))).put("length",b.length).put("owner",Math.toIntExact(owner)).put("extension",name.substring(name.lastIndexOf('.'))).put("temporary",temporary));}catch(Exception x){throw new IOException(x);}if(c.path("save_error").asBoolean())throw new IOException("controlled physical save failure");return "resource://"+String.format("%022d",saves.size());}
      public void checkConnectivity(){}public String saveFile(InputStream i,String f,long t,String k){throw new UnsupportedOperationException();}public InputStream open(String p){throw new UnsupportedOperationException();}public String url(String p){return p;}public void delete(String p){}public String copy(String p,long t,String k){throw new UnsupportedOperationException();}
    };
    var policy=new ModelEgressPolicy(null,json,new MockEnvironment().withProperty("SSRF_WHITELIST","127.0.0.1"));
    try(var resolver=new ManualImageResolver(new SafeModelHttp(policy,5,true),expand.apply(c.path("keep").asText()))){
      var resolved=c.path("name").asText().startsWith("manual-")?resolver.resolveForManual(expand.apply(c.path("markdown").asText()),store,8,()->{}):resolver.resolve(expand.apply(c.path("markdown").asText()),store,8,()->{});var images=json.createArrayNode();for(var image:resolved.images())images.add(json.createObjectNode().put("original",normalize.apply(image.original())).put("serving",normalize.apply(image.serving())).put("mime",image.mime()));
      ObjectNode result=json.createObjectNode().put("markdown",normalize.apply(resolved.markdown()));result.set("images",images);result.set("saves",saves);result.set("requests",requests);
      assertThat(result).as("actual Go parser, real HTTP, exact replacement/storage effects").isEqualTo(c.path("result"));
    }finally{server.stop(0);}
  }
}
