package com.tencent.weknora.model;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import com.tencent.weknora.model.client.CallContext;
import com.tencent.weknora.model.client.SafeModelHttp;
import com.tencent.weknora.model.client.ModelClientException;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.zip.GZIPOutputStream;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.cert.CertificateFactory;
import javax.net.ssl.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class ModelTransportSecurityTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static InetAddress[] ips(String... values)throws Exception{InetAddress[] out=new InetAddress[values.length];for(int i=0;i<values.length;i++)out[i]=InetAddress.getByName(values[i]);return out;}
    static ModelEgressPolicy policy(MockEnvironment env,ModelEgressPolicy.DNS dns){return new ModelEgressPolicy(null,JSON,env,dns);}
    @Test void forbiddenNameStopsBeforeDNSAndRebindingOrMixedAnswersStopBeforeDial()throws Exception{
        AtomicInteger calls=new AtomicInteger();MockEnvironment only=new MockEnvironment().withProperty("SSRF_DNS_WHITELIST_ONLY","true").withProperty("SSRF_WHITELIST","*.allowed.invalid");
        ModelEgressPolicy p=policy(only,h->{calls.incrementAndGet();return InetAddress.getAllByName(h);});ModelEgressPolicy initial=p;
        assertThatThrownBy(()->initial.resolve(URI.create("https://foreign.invalid"),new CallContext())).hasMessageContaining("not in the SSRF whitelist");assertThat(calls.get()).isZero();
        InetAddress[] publicIP=ips("8.8.8.8"),privateIP=ips("127.0.0.1"),mixed=ips("8.8.8.8","10.0.0.1");
        p=policy(new MockEnvironment(),h->calls.getAndIncrement()==0?publicIP:privateIP);calls.set(0);ModelEgressPolicy rebind=p;
        assertThatThrownBy(()->rebind.resolve(URI.create("https://unlisted.example"),new CallContext())).hasMessageContaining("DNS answer blocked");assertThat(calls.get()).isEqualTo(2);
        ModelEgressPolicy mix=policy(new MockEnvironment(),h->mixed);assertThatThrownBy(()->mix.resolve(URI.create("https://unlisted.example"),new CallContext())).hasMessageContaining("SSRF policy");
    }
    @Test void validatedIPIsPinnedAndHostnameKeptForHTTPRequest()throws Exception{
        AtomicInteger dns=new AtomicInteger();InetAddress[] local=ips("127.0.0.1");
        try(Server server=new Server(x->{assertThat(x.getRequestHeaders().getFirst("Host")).startsWith("pin.allowed.invalid:");reply(x,200,"{\"ok\":true}");});SafeModelHttp http=new SafeModelHttp(policy(new MockEnvironment().withProperty("SSRF_WHITELIST","pin.allowed.invalid"),h->{dns.incrementAndGet();return local;}));var ctx=CallContext.timeout(Duration.ofSeconds(2))){
            try(var result=http.post(ctx,"http://pin.allowed.invalid:"+server.port()+"/",Map.of(),new byte[0],Duration.ZERO)){assertThat(result.text(1024)).isEqualTo("{\"ok\":true}");}assertThat(dns.get()).isEqualTo(1);
        }
    }
    @Test void crossOriginRedirectNeverReplaysBodyOrAPIKeyAndSameOriginChecksAgain()throws Exception{
        AtomicInteger leaked=new AtomicInteger(),dns=new AtomicInteger();InetAddress[] local=ips("127.0.0.1");
        try(Server sink=new Server(x->{leaked.incrementAndGet();reply(x,200,"{}");});Server origin=new Server(x->{if(x.getRequestURI().getPath().equals("/same")){x.getResponseHeaders().set("Location","/done");x.sendResponseHeaders(307,-1);x.close();}else if(x.getRequestURI().getPath().equals("/done")){assertThat(x.getRequestMethod()).isEqualTo("POST");assertThat(new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)).isEqualTo("private-prompt");reply(x,200,"{}");}else{x.getResponseHeaders().set("Location",sink.url());x.sendResponseHeaders(307,-1);x.close();}});SafeModelHttp http=new SafeModelHttp((u,c)->{dns.incrementAndGet();return local;});var ctx=CallContext.timeout(Duration.ofSeconds(3))){
            assertThatThrownBy(()->http.post(ctx,origin.url(),Map.of("api-key","synthetic-secret"),"private-prompt".getBytes(StandardCharsets.UTF_8),Duration.ZERO)).hasMessageContaining("cross-origin");assertThat(leaked.get()).isZero();
            try(var result=http.post(ctx,origin.url()+"same",Map.of("api-key","synthetic-secret"),"private-prompt".getBytes(StandardCharsets.UTF_8),Duration.ZERO)){assertThat(result.status()).isEqualTo(200);assertThat(result.text(1024)).isEqualTo("{}");}assertThat(dns.get()).isEqualTo(3);
        }
    }
    @Test void gzipAndChunkedLargeBodiesAreReadWithLimits()throws Exception{
        byte[] payload=("abcdefgh".repeat(400000)).getBytes(StandardCharsets.UTF_8);InetAddress[] local=ips("127.0.0.1");
        try(Server server=new Server(x->{x.getResponseHeaders().set("Content-Encoding","gzip");x.sendResponseHeaders(200,0);try(var out=new GZIPOutputStream(x.getResponseBody())){out.write(payload);}x.close();});SafeModelHttp http=new SafeModelHttp((u,c)->local);var ctx=CallContext.timeout(Duration.ofSeconds(5))){
            try(var r=http.post(ctx,server.url(),Map.of(),new byte[0],Duration.ZERO)){assertThat(r.bytes(payload.length)).isEqualTo(payload);}
            try(var r=http.post(ctx,server.url(),Map.of(),new byte[0],Duration.ZERO)){assertThatThrownBy(()->r.bytes(1024)).hasMessageContaining("maximum size");}
        }
    }
    @Test void deadlineAndExplicitCancellationCloseStalledBodies()throws Exception{
        InetAddress[] local=ips("127.0.0.1");CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        try(Server server=new Server(x->{x.sendResponseHeaders(200,0);x.getResponseBody().write('a');x.getResponseBody().flush();entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException ex){Thread.currentThread().interrupt();}x.close();});SafeModelHttp http=new SafeModelHttp((u,c)->local);var executor=Executors.newVirtualThreadPerTaskExecutor()){
            try(var ctx=new CallContext();var r=http.post(ctx,server.url(),Map.of(),new byte[0],Duration.ofMillis(200))){assertThatThrownBy(()->r.bytes(100)).hasMessageContaining("deadline exceeded");}
            try(var ctx=new CallContext();var r=http.post(ctx,server.url(),Map.of(),new byte[0],Duration.ZERO)){Future<byte[]> read=executor.submit(()->r.bytes(100));assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();ctx.cancel();assertThatThrownBy(()->read.get(2,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(CancellationException.class);}
        }finally{release.countDown();}
    }
    @Test void proxyRequiringCallsFailClosedAndGoNoProxyExemptionsRemainDirect()throws Exception{
        AtomicInteger lookups=new AtomicInteger();InetAddress[] local=ips("127.0.0.1");MockEnvironment env=new MockEnvironment().withProperty("HTTPS_PROXY","http://proxy.invalid:8080").withProperty("HTTP_PROXY","http://proxy.invalid:8080").withProperty("SSRF_WHITELIST","*.example,127.0.0.1,localhost").withProperty("NO_PROXY",".exempt.example,port.example:8443");ModelEgressPolicy p=policy(env,h->{lookups.incrementAndGet();return local;});
        assertThatThrownBy(()->p.resolve(URI.create("https://remote.example"),new CallContext())).hasMessage("Native model proxy transport is not migrated");assertThat(lookups.get()).isZero();
        for(String url:List.of("https://a.exempt.example","https://port.example:8443","http://127.0.0.1","http://localhost"))assertThat(p.resolve(URI.create(url),new CallContext())).isEqualTo(local);
        assertThatThrownBy(()->p.resolve(URI.create("https://exempt.example"),new CallContext())).hasMessageContaining("proxy transport");assertThatThrownBy(()->p.resolve(URI.create("https://port.example:443"),new CallContext())).hasMessageContaining("proxy transport");
    }
    @Test void defaultTrustRejectsActualGoHTTPTestServerCertificate()throws Exception{
        JsonNode material=NativeModelClientsTest.fixtures().path("tls_material");var certificate=CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(material.path("certificate_der").asText())));PrivateKey key=KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(material.path("private_key_pkcs8").asText())));
        KeyStore keys=KeyStore.getInstance("PKCS12");keys.load(null,null);char[] password="synthetic-test-only".toCharArray();keys.setKeyEntry("test",key,password,new java.security.cert.Certificate[]{certificate});KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(keys,password);SSLContext ssl=SSLContext.getInstance("TLS");ssl.init(km.getKeyManagers(),null,null);
        HttpsServer server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setHttpsConfigurator(new HttpsConfigurator(ssl));AtomicInteger requests=new AtomicInteger();server.createContext("/",x->{requests.incrementAndGet();reply(x,200,"{}");});server.start();InetAddress[] local=ips("127.0.0.1");
        try(SafeModelHttp http=new SafeModelHttp((u,c)->local);var ctx=CallContext.timeout(Duration.ofSeconds(2))){assertThatThrownBy(()->http.post(ctx,"https://127.0.0.1:"+server.getAddress().getPort()+"/",Map.of("Authorization","Bearer synthetic-key"),new byte[0],Duration.ZERO)).isInstanceOf(ModelClientException.class).hasMessageNotContaining("synthetic-key");assertThat(requests.get()).isZero();}finally{server.stop(0);}
    }
    static void reply(HttpExchange x,int status,String text)throws IOException{byte[] b=text.getBytes(StandardCharsets.UTF_8);x.sendResponseHeaders(status,b.length);try(var out=x.getResponseBody()){out.write(b);}x.close();}
    static final class Server implements AutoCloseable{final HttpServer server;final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();Server(HttpHandler handler)throws IOException{server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(executor);server.createContext("/",handler);server.start();}int port(){return server.getAddress().getPort();}String url(){return "http://127.0.0.1:"+port()+"/";}@Override public void close(){server.stop(0);executor.shutdownNow();}}
}
