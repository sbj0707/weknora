package com.tencent.weknora.bridge;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
class GoCompatibilityBridgeTest {
    @Test void oidcSelectorOwnsOnlyFourPublicGetOperations() {
        for(String route:java.util.List.of("config","url","start","callback")) {
            String path="/api/v1/auth/oidc/"+route;
            assertThat(GoCompatibilityBridge.ownsNative(new MockHttpServletRequest("GET",path))).as(path).isTrue();
            for(String method:java.util.List.of("POST","PUT","DELETE","HEAD","PATCH"))
                assertThat(GoCompatibilityBridge.ownsNative(new MockHttpServletRequest(method,path))).as(method+" "+path).isFalse();
            assertThat(GoCompatibilityBridge.ownsNative(new MockHttpServletRequest("GET",path+"/extra"))).isFalse();
        }
        assertThat(GoCompatibilityBridge.ownsNative(new MockHttpServletRequest("GET","/api/v1/auth/oidc/unknown"))).isFalse();
    }
    @Test void parserEngineSelectorOwnsOnlyImplementedSystemAndKvOperations() {
        for(String route:java.util.List.of("GET /api/v1/system/parser-engines","POST /api/v1/system/parser-engines/check","POST /api/v1/system/docreader/reconnect","GET /api/v1/tenants/kv/parser-engine-config","PUT /api/v1/tenants/kv/parser-engine-config")) {
            String[] parts=route.split(" ");assertThat(GoCompatibilityBridge.ownsNative(new MockHttpServletRequest(parts[0],parts[1]))).as(route).isTrue();
        }
        for(String route:java.util.List.of("POST /api/v1/tenants/kv/parser-engine-config","DELETE /api/v1/tenants/kv/parser-engine-config","GET /api/v1/tenants/kv/parser-engine-config/extra","GET /api/v1/tenants/kv/unknown-config","POST /api/v1/system/parser-engines","GET /api/v1/system/parser-engines/check","GET /api/v1/system/docreader/reconnect","PUT /api/v1/system/docreader/reconnect","GET /api/v1/system/info","GET /api/v1/system/parser-engines/extra","POST /api/v1/system/parser-engines/check/extra")) {
            String[] parts=route.split(" ");assertThat(GoCompatibilityBridge.ownsNative(new MockHttpServletRequest(parts[0],parts[1]))).as(route).isFalse();
        }
    }
    @Test void contentReadSelectorOwnsOnlyPortedGetRoutes() {
        for(String path:java.util.List.of("/api/v1/knowledge-bases/kb/knowledge", "/api/v1/knowledge/doc", "/api/v1/chunks/doc", "/api/v1/chunks/by-id/chunk")) {
            assertThat(GoCompatibilityBridge.ownsNative(new MockHttpServletRequest("GET",path))).as(path).isTrue();
            for(String method:java.util.List.of("POST","PUT","DELETE","PATCH","HEAD"))
                assertThat(GoCompatibilityBridge.ownsNative(new MockHttpServletRequest(method,path))).as(method+" "+path).isFalse();
        }
        for(String path:java.util.List.of("/api/v1/knowledge/batch", "/api/v1/knowledge/search", "/api/v1/knowledge/doc/download", "/api/v1/chunks/by-id/chunk/detail", "/api/v1/chunks", "/api/v1/knowledge-bases/kb/knowledge/extra"))
            assertThat(GoCompatibilityBridge.ownsNative(new MockHttpServletRequest("GET",path))).as(path).isFalse();
    }
    @Test void forwardsOneRequestBodyCredentialsTenantAndStatusWithoutRetryOrRedirect() throws Exception {
        var calls=new AtomicReference<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v1/knowledge",exchange -> {
            calls.set(exchange.getRequestMethod()+" "+exchange.getRequestURI()+" "+exchange.getRequestHeaders().getFirst("Authorization")+" "+exchange.getRequestHeaders().getFirst("X-Tenant-ID")+" "+exchange.getRequestHeaders().getFirst("X-Forwarded-For")+" "+new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.getResponseHeaders().set("X-Request-ID","test-request-id");
            byte[] body="{\"success\":false,\"error\":{\"code\":1002}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(403,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try {
            var bridge=new GoCompatibilityBridge(true,false,"http://127.0.0.1:"+server.getAddress().getPort());
            var request=new MockHttpServletRequest("POST","/api/v1/knowledge");request.setQueryString("id=abc");request.setContent("{\"title\":\"中文\"}".getBytes(StandardCharsets.UTF_8));
            request.addHeader("X-Forwarded-For","spoofed-client-ip");request.setRemoteAddr("192.0.2.17");
            request.addHeader("Authorization","Bearer original-token");request.addHeader("X-Tenant-ID","42");request.addHeader("Content-Type","application/json");
            var response=new MockHttpServletResponse();bridge.doFilter(request,response,new MockFilterChain());
            assertThat(calls.get()).isEqualTo("POST /api/v1/knowledge?id=abc Bearer original-token 42 192.0.2.17 {\"title\":\"中文\"}");
            assertThat(response.getStatus()).isEqualTo(403);assertThat(response.getHeader("X-Request-ID")).isEqualTo("test-request-id");
            assertThat(response.getHeader("X-WeKnora-Implementation")).isEqualTo("go-compatibility-bridge");assertThat(response.getContentAsString()).contains("1002");
        }finally{server.stop(0);}
    }
    @Test void flushesFirstSseEventBeforeUpstreamFinishesAndPreservesEventBytes() throws Exception {
        var flushed=new CountDownLatch(1);var serverFinished=new CountDownLatch(1);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v1/chat",exchange -> {
            exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);
            try(var output=exchange.getResponseBody()) {
                output.write("event: token\ndata: first\n\n".getBytes(StandardCharsets.UTF_8));output.flush();
                if(!flushed.await(5,TimeUnit.SECONDS)) throw new AssertionError("First event was buffered until EOF");
                output.write("event: done\ndata: {}\n\n".getBytes(StandardCharsets.UTF_8));output.flush();
            }catch(InterruptedException ex){Thread.currentThread().interrupt();}finally{exchange.close();serverFinished.countDown();}
        });server.start();
        try {
            var bridge=new GoCompatibilityBridge(true,false,"http://127.0.0.1:"+server.getAddress().getPort());
            var response=new MockHttpServletResponse(){@Override public void flushBuffer(){super.flushBuffer();flushed.countDown();}};
            bridge.doFilter(new MockHttpServletRequest("GET","/api/v1/chat"),response,new MockFilterChain());
            assertThat(serverFinished.await(1,TimeUnit.SECONDS)).isTrue();
            assertThat(response.getContentAsString()).isEqualTo("event: token\ndata: first\n\nevent: done\ndata: {}\n\n");
        }finally{server.stop(0);}
    }
    @Test void preservesRawMultipartAndBinaryTransfers() throws Exception {
        byte[] payload={0,1,2,-1,13,10,0};var received=new AtomicReference<byte[]>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v1/files",exchange -> {received.set(exchange.getRequestBody().readAllBytes());exchange.sendResponseHeaders(200,payload.length);exchange.getResponseBody().write(payload);exchange.close();});server.start();
        try {
            var bridge=new GoCompatibilityBridge(true,false,"http://127.0.0.1:"+server.getAddress().getPort());
            var request=new MockHttpServletRequest("POST","/api/v1/files");request.setContent(payload);request.addHeader("Content-Type","multipart/form-data; boundary=test");
            var response=new MockHttpServletResponse();bridge.doFilter(request,response,new MockFilterChain());
            assertThat(received.get()).isEqualTo(payload);assertThat(response.getContentAsByteArray()).isEqualTo(payload);
        }finally{server.stop(0);}
    }
    @Test void restrictsOriginAndDoesNotClaimWebsocketMigration() throws Exception {
        assertThrows(IllegalArgumentException.class,() -> new GoCompatibilityBridge(true,false,"http://localhost:8080/api"));
        var bridge=new GoCompatibilityBridge(true,false,"http://127.0.0.1:8080");var request=new MockHttpServletRequest("GET","/api/v1/session/terminal");request.addHeader("Upgrade","websocket");
        var response=new MockHttpServletResponse();bridge.doFilter(request,response,new MockFilterChain());assertThat(response.getStatus()).isEqualTo(501);
        assertThat(GoCompatibilityBridge.ownsNative(new MockHttpServletRequest("GET","/api/v1/knowledge-bases"))).isTrue();
    }
    @Test void nativeSelectorKeepsUnportedAuthAndKnowledgeBaseSideEffectsOnGo() {
        assertThat(GoCompatibilityBridge.ownsNative(new MockHttpServletRequest("POST","/api/v1/auth/register"))).isTrue();
        assertThat(GoCompatibilityBridge.ownsNative(new MockHttpServletRequest("GET","/api/v1/auth/config"))).isTrue();
        assertThat(GoCompatibilityBridge.ownsNative(new MockHttpServletRequest("PUT","/api/v1/auth/me/preferences"))).isTrue();
        assertThat(GoCompatibilityBridge.ownsNative(new MockHttpServletRequest("GET","/api/v1/auth/validate"))).isTrue();
        assertThat(GoCompatibilityBridge.ownsNative(new MockHttpServletRequest("POST","/api/v1/auth/switch-tenant"))).isTrue();
        assertThat(GoCompatibilityBridge.ownsNative(new MockHttpServletRequest("POST","/api/v1/auth/change-password"))).isTrue();
        assertThat(GoCompatibilityBridge.ownsNative(new MockHttpServletRequest("POST","/api/v1/tenants/1/api-keys"))).isTrue();
        assertThat(GoCompatibilityBridge.ownsNative(new MockHttpServletRequest("DELETE","/api/v1/tenants/1/api-keys/2"))).isTrue();
        assertThat(GoCompatibilityBridge.ownsNative(new MockHttpServletRequest("POST","/api/v1/admin/api-keys"))).isFalse();
        assertThat(GoCompatibilityBridge.ownsNative(new MockHttpServletRequest("POST","/api/v1/chunker/preview"))).isTrue();
        assertThat(GoCompatibilityBridge.ownsNative(new MockHttpServletRequest("GET","/api/v1/chunker/preview"))).isFalse();
        assertThat(GoCompatibilityBridge.ownsNative(new MockHttpServletRequest("PUT","/api/v1/tenants/1/members/user"))).isTrue();
        assertThat(GoCompatibilityBridge.ownsNative(new MockHttpServletRequest("POST","/api/v1/tenants/1/leave"))).isTrue();
        assertThat(GoCompatibilityBridge.ownsNative(new MockHttpServletRequest("GET","/api/v1/tenants/1/leave"))).isFalse();
        assertThat(GoCompatibilityBridge.ownsNative(new MockHttpServletRequest("POST","/api/v1/tenants/1/leave/extra"))).isFalse();
        for(String route:java.util.List.of("GET /api/v1/tenants/1/invitations", "POST /api/v1/tenants/1/invitations", "DELETE /api/v1/tenants/1/invitations/invite", "POST /api/v1/tenants/1/invite-links", "GET /api/v1/me/invitations", "GET /api/v1/me/invitations/pending-count", "POST /api/v1/me/invitations/invite/accept", "POST /api/v1/me/invitations/invite/decline", "POST /api/v1/me/invitations/accept-by-token", "POST /api/v1/auth/invitations/lookup", "POST /api/v1/auth/register-by-invite")) {
            String[] parts=route.split(" ");assertThat(GoCompatibilityBridge.ownsNative(new MockHttpServletRequest(parts[0],parts[1]))).as(route).isTrue();
        }
        for(String route:java.util.List.of("GET /api/v1/auth/invitations/lookup", "GET /api/v1/auth/register-by-invite", "POST /api/v1/me/invitations/pending-count", "PUT /api/v1/tenants/1/invitations/invite", "POST /api/v1/me/invitations/invite/accept/extra", "POST /api/v1/tenants/0/invite-links")) {
            String[] parts=route.split(" ");assertThat(GoCompatibilityBridge.ownsNative(new MockHttpServletRequest(parts[0],parts[1]))).as(route).isFalse();
        }
        assertThat(GoCompatibilityBridge.ownsNative(new MockHttpServletRequest("GET","/api/v1/tenants/search"))).isTrue();
        assertThat(GoCompatibilityBridge.ownsNative(new MockHttpServletRequest("PUT","/api/v1/tenants/kv/memory-config"))).isTrue();
        assertThat(GoCompatibilityBridge.ownsNative(new MockHttpServletRequest("PUT","/api/v1/tenants/kv/storage-config"))).isFalse();
        assertThat(GoCompatibilityBridge.ownsNative(new MockHttpServletRequest("DELETE","/api/v1/tenants/1"))).isFalse();
        for(String path:java.util.List.of("/api/v1/auth/register/invite","/api/v1/auth/oidc/callback","/api/v1/knowledge-bases"))assertThat(GoCompatibilityBridge.ownsNative(new MockHttpServletRequest("POST",path))).isFalse();
        assertThat(GoCompatibilityBridge.ownsNative(new MockHttpServletRequest("PUT","/api/v1/knowledge-bases/kb"))).isFalse();
    }
}
