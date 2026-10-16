package com.tencent.weknora.bridge;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Temporary Go transport delegation; this class is not a Java business implementation. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public class GoCompatibilityBridge extends OncePerRequestFilter {
    private final boolean enabled,nativeRoutes;
    private final URI upstream;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private static final Set<String> HOP=Set.of("connection","keep-alive","proxy-authenticate","proxy-authorization","te","trailer","transfer-encoding","upgrade","host","content-length","expect");
    public GoCompatibilityBridge(@Value("${weknora.bridge.enabled:false}") boolean enabled, @Value("${weknora.bridge.native-routes:false}") boolean nativeRoutes,@Value("${weknora.bridge.url:http://127.0.0.1:8080}") String url) {
        this.enabled=enabled;this.nativeRoutes=nativeRoutes;this.upstream=URI.create(url);
        if(!Set.of("http","https").contains(upstream.getScheme())||upstream.getHost()==null||upstream.getUserInfo()!=null||upstream.getQuery()!=null||upstream.getFragment()!=null||!Set.of("","/").contains(upstream.getPath())) throw new IllegalArgumentException("Go bridge URL must be a fixed HTTP(S) origin");
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest req) {
        if(!enabled) return true;
        if(req.getRequestURI().equals("/health")&&req.getMethod().equals("GET")) return true;
        return nativeRoutes&&ownsNative(req);
    }
    public static boolean ownsNative(HttpServletRequest req) {
        String path=req.getRequestURI(),method=req.getMethod();
        if(path.equals("/api/v1/knowledge/move")&&method.equals("POST")||path.matches("/api/v1/knowledge/move/progress/[^/]+")&&method.equals("GET"))return true;
        if(method.equals("DELETE")&&path.matches("/api/v1/knowledge-bases/[^/]+"))return true;
        if(method.equals("POST")&&path.matches("/api/v1/knowledge-bases/[^/]+/duplicate"))return true;
        if(method.equals("PUT")&&path.matches("/api/v1/knowledge/image/[^/]+/[^/]+"))return true;
        if(path.equals("/api/v1/system/parser-engines")&&method.equals("GET"))return true;
        if(Set.of("/api/v1/system/parser-engines/check","/api/v1/system/docreader/reconnect").contains(path)&&method.equals("POST"))return true;
        if(path.equals("/api/v1/chunker/preview")&&method.equals("POST"))return true;
        if(Set.of("/api/v1/auth/oidc/config","/api/v1/auth/oidc/url","/api/v1/auth/oidc/start","/api/v1/auth/oidc/callback").contains(path)&&method.equals("GET"))return true;
        if(path.equals("/api/v1/auth/register-by-invite")&&method.equals("POST"))return true;
        if(path.equals("/api/v1/auth/login")&&method.equals("POST")) return true;
        if(path.equals("/api/v1/auth/me")&&method.equals("GET")) return true;
        if(path.equals("/api/v1/auth/logout")&&method.equals("POST")) return true;
        if(path.equals("/api/v1/auth/refresh")&&method.equals("POST")) return true;
        if(Set.of("/api/v1/auth/config","/api/v1/auth/validate").contains(path)&&method.equals("GET"))return true;
        if(path.equals("/api/v1/auth/me/preferences")&&method.equals("PUT"))return true;
        if(Set.of("/api/v1/auth/register","/api/v1/auth/switch-tenant","/api/v1/auth/change-password").contains(path)&&method.equals("POST"))return true;
        if(Set.of("/api/v1/tenants","/api/v1/tenants/all","/api/v1/tenants/search").contains(path)&&method.equals("GET"))return true;
        if(path.matches("/api/v1/tenants/[1-9][0-9]*")&&Set.of("GET","PUT").contains(method))return true;
        if(Set.of("/api/v1/tenants/kv/retrieval-config","/api/v1/tenants/kv/memory-config","/api/v1/tenants/kv/parser-engine-config").contains(path)&&Set.of("GET","PUT").contains(method))return true;
        if(path.matches("/api/v1/tenants/[1-9][0-9]*/api-keys")&&Set.of("GET","POST").contains(method))return true;
        if(path.matches("/api/v1/tenants/[1-9][0-9]*/api-keys/[1-9][0-9]*")&&Set.of("PUT","DELETE").contains(method))return true;
        if(path.matches("/api/v1/tenants/[1-9][0-9]*/members")&&Set.of("GET","POST").contains(method))return true;
        if(path.matches("/api/v1/tenants/[1-9][0-9]*/leave")&&method.equals("POST"))return true;
        if(path.matches("/api/v1/tenants/[1-9][0-9]*/invitations")&&Set.of("GET","POST").contains(method))return true;
        if(path.matches("/api/v1/tenants/[1-9][0-9]*/invitations/[^/]+")&&method.equals("DELETE"))return true;
        if(path.matches("/api/v1/tenants/[1-9][0-9]*/invite-links")&&method.equals("POST"))return true;
        if(Set.of("/api/v1/me/invitations","/api/v1/me/invitations/pending-count").contains(path)&&method.equals("GET"))return true;
        if(path.matches("/api/v1/me/invitations/[^/]+/(accept|decline)")&&method.equals("POST"))return true;
        if(Set.of("/api/v1/me/invitations/accept-by-token","/api/v1/auth/invitations/lookup").contains(path)&&method.equals("POST"))return true;
        if(path.matches("/api/v1/tenants/[1-9][0-9]*/members/[^/]+")&&Set.of("PUT","DELETE").contains(method))return true;
        if(path.equals("/api/v1/knowledge-bases")&&method.equals("GET")&&req.getParameter("agent_id")==null) return true;
        if(method.equals("GET")) {
            if(path.matches("/api/v1/knowledge/[^/]+/index-status")) return true;
            if(path.matches("/api/v1/knowledge/[^/]+/(spans|stages)")) return true;
            if(path.matches("/api/v1/knowledge/[^/]+/chunks/[^/]+/details")) return true;
            if(path.matches("/api/v1/knowledge-bases/[^/]+/knowledge")) return true;
            if(path.matches("/api/v1/knowledge-bases/[^/]+/(images|gallery-config|move-targets)")) return true;
            if(path.matches("/api/v1/knowledge/(?!batch$|search$)[^/]+")) return true;
            if(path.matches("/api/v1/chunks/[^/]+")) return true;
            if(path.matches("/api/v1/chunks/by-id/[^/]+")) return true;
        }
        return path.matches("/api/v1/knowledge-bases/[^/]+")&&method.equals("GET") || path.matches("/api/v1/knowledge-bases/[^/]+/pin")&&method.equals("PUT");
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws IOException,ServletException {
        if(request.getHeader("Upgrade")!=null) {response.sendError(501,"WebSocket migration bridge is not implemented");return;}
        Set<String> excluded=excluded(request.getHeader("Connection"));
        excluded.addAll(Set.of("forwarded","x-forwarded-for","x-forwarded-host","x-forwarded-proto","x-real-ip"));
        URI uri=URI.create(upstream.toString().replaceAll("/$", "")+request.getRequestURI()+(request.getQueryString()==null?"":"?"+request.getQueryString()));
        var builder=HttpRequest.newBuilder(uri);
        for(String name:Collections.list(request.getHeaderNames())) if(!excluded.contains(name.toLowerCase(Locale.ROOT))) for(String value:Collections.list(request.getHeaders(name))) builder.header(name,value);
        builder.header("X-Forwarded-For",request.getRemoteAddr());
        builder.header("X-Forwarded-Proto",request.getScheme());
        builder.header("X-Forwarded-Host",request.getServerName());
        var body=request.getContentLengthLong()==0?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofInputStream(() -> {try{return request.getInputStream();}catch(IOException ex){throw new UncheckedIOException(ex);}});
        builder.method(request.getMethod(),body);
        HttpResponse<InputStream> result;
        try {result=client.send(builder.build(),HttpResponse.BodyHandlers.ofInputStream());}
        catch(InterruptedException ex) {Thread.currentThread().interrupt();response.sendError(502,"Go compatibility backend unavailable");return;}
        catch(IOException ex) {response.sendError(502,"Go compatibility backend unavailable");return;}
        response.setStatus(result.statusCode());
        Set<String> responseExcluded=excluded(result.headers().firstValue("Connection").orElse(null));
        result.headers().map().forEach((name,values) -> {if(!responseExcluded.contains(name.toLowerCase(Locale.ROOT))) values.forEach(value -> response.addHeader(name,value));});
        response.setHeader("X-WeKnora-Implementation","go-compatibility-bridge");
        // Preserve SSE boundaries and promptly close the Go stream on downstream disconnect.
        try(var in=result.body()) {byte[] bytes=new byte[8192];int read;while((read=in.read(bytes))!=-1){response.getOutputStream().write(bytes,0,read);response.flushBuffer();}}
    }
    private static Set<String> excluded(String connection) {var result=new HashSet<>(HOP);if(connection!=null)for(String field:connection.split(","))result.add(field.trim().toLowerCase(Locale.ROOT));return result;}
}
