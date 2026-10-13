package com.tencent.weknora.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class AuthFilter extends OncePerRequestFilter {
    private final JwtCodec jwt;
    private final AuthRepository repository;
    private final ObjectMapper mapper;
    private final SecuritySettings settings;
    private final ApiKeyUsageTracker usage;
    private final TenantMembershipRecovery recovery;
    public AuthFilter(JwtCodec jwt, AuthRepository repository, ObjectMapper mapper) { this(jwt, repository, mapper, new SecuritySettings(repository, new StandardEnvironment())); }
    public AuthFilter(JwtCodec jwt, AuthRepository repository, ObjectMapper mapper, SecuritySettings settings) {this(jwt,repository,mapper,settings,null);}
    public AuthFilter(JwtCodec jwt, AuthRepository repository, ObjectMapper mapper, SecuritySettings settings,ApiKeyUsageTracker usage) { this(jwt,repository,mapper,settings,usage,null); }
    @Autowired
    public AuthFilter(JwtCodec jwt, AuthRepository repository, ObjectMapper mapper, SecuritySettings settings,ApiKeyUsageTracker usage,TenantMembershipRecovery recovery) { this.jwt = jwt; this.repository = repository; this.mapper = mapper; this.settings = settings;this.usage=usage;this.recovery=recovery; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "OPTIONS".equals(request.getMethod())
                || ("GET".equals(request.getMethod()) && Set.of("/health","/api/v1/auth/config","/api/v1/auth/oidc/config","/api/v1/auth/oidc/url","/api/v1/auth/oidc/start","/api/v1/auth/oidc/callback").contains(path))
                || ("POST".equals(request.getMethod()) && Set.of("/api/v1/auth/login", "/api/v1/auth/refresh","/api/v1/auth/register","/api/v1/auth/register-by-invite","/api/v1/auth/invitations/lookup").contains(path));
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        try {
            request.setAttribute(CurrentAuth.ATTRIBUTE, authenticate(request));
        } catch (ApiException ex) {
            if(ex.response()!=null){response.setStatus(ex.status());response.setContentType("application/json");response.setCharacterEncoding(StandardCharsets.UTF_8.name());mapper.writeValue(response.getWriter(),ex.response());}
            else writeError(response, ex.status(), ex.code(), ex.getMessage());
            return;
        } catch (DataAccessException ex) {
            writeError(response, 503, "AUTH_SERVICE_UNAVAILABLE", "Authentication service unavailable");
            return;
        }
        chain.doFilter(request, response);
    }
    private AuthContext authenticate(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        boolean bearer = authorization != null && authorization.startsWith("Bearer ");
        Map<String, Object> claims = null;
        Map<String, Object> user = null;
        if (bearer) {
            String token = authorization.substring(7);
            try {
                claims = jwt.verifyAccess(token);
                String userId = (String) claims.get("user_id");
                if (!repository.activeAccessToken(token, userId)) throw unauthorized();
                user = repository.user(userId);
                if (user == null) throw unauthorized();
            } catch (ApiException ex) {
                if (ex.status() != 401) throw ex;
                claims = null;
                user = null;
            }
        }
        // Go permits a valid API key to recover an expired/missing access token;
        // a valid JWT with forbidden tenant selection never falls back to a key.
        if (user != null) return jwtContext(request, claims, user);
        String key = request.getHeader("X-API-Key");
        if (key != null && !key.isBlank()) return keyContext(request, key.trim());
        throw new ApiException(401, "UNAUTHORIZED", bearer ? "Unauthorized: invalid or expired token" : "Unauthorized: missing authentication");
    }
    private AuthContext jwtContext(HttpServletRequest request, Map<String, Object> claims, Map<String, Object> user) {
        String userId = AuthRepository.string(user, "id");
        long homeTenant = AuthRepository.number(user, "tenant_id");
        boolean crossSuper = AuthRepository.bool(user, "can_access_all_tenants") && settings.crossTenantEnabled();
        long target = tenantClaim(claims.get("tenant_id"), homeTenant);
        if (target == 0) target = homeTenant;
        String header = request.getHeader("X-Tenant-ID");
        if (header != null && !header.isEmpty()) target = parseTenant(header);
        if (target == 0) target = repository.firstTenant(userId);
        if (target == 0) {
            if (!tenantOptional(request)) throw new ApiException(409, "TENANT_REQUIRED", "Workspace required");
            return new AuthContext(userId, 0, "viewer", AuthRepository.bool(user, "is_system_admin"), null, false, Set.of(), Set.of(), crossSuper, settings.rbacEnabled());
        }
        String role = repository.role(userId, target);
        if (header != null && !header.isEmpty() && target != homeTenant && role == null && !crossSuper) throw new ApiException(403, "FORBIDDEN", "Forbidden: insufficient permissions to access target workspace");
        // Go checks explicit-header access before lookup, so missing foreign
        // workspaces do not reveal existence to an unauthorized caller.
        // Lookup still precedes every orphan-recovery write.
        if (repository.tenant(target) == null) {
            if (header != null && !header.isEmpty()) throw new ApiException(400, "INVALID_TENANT_ID", "Invalid target workspace ID");
            throw unauthorized();
        }
        if (role == null && target != homeTenant && crossSuper) role = "admin";
        if (role == null && target == homeTenant && recovery != null) role = recovery.repairHomeMembership(userId,target);
        if (role == null && !settings.rbacEnabled()) role = "admin";
        if (AuthContext.level(role) == 0) throw new ApiException(403, "FORBIDDEN", "Forbidden: not an active member of the target workspace");
        return new AuthContext(userId, target, role, AuthRepository.bool(user, "is_system_admin"), null, false, Set.of(), Set.of(), crossSuper, settings.rbacEnabled());
    }
    private AuthContext keyContext(HttpServletRequest request, String token) {
        if (token.length() > 4096) throw unauthorized();
        Map<String, Object> key = repository.key(hashApiKey(token));
        if (key == null) throw unauthorized();
        if(usage!=null)usage.touch(AuthRepository.number(key,"id"));
        if (!"tenant".equals(AuthRepository.string(key, "scope_type"))) {
            throw new ApiException(403, "AUTH_MODE_NOT_MIGRATED", "Platform API keys are not migrated");
        }
        long target = AuthRepository.number(key, "tenant_id");
        if (target <= 0) throw unauthorized();
        String header = request.getHeader("X-Tenant-ID");
        if (header != null && !header.isEmpty() && parseTenant(header) != target) {
            throw new ApiException(403, "FORBIDDEN", "Forbidden: workspace API key cannot switch workspaces");
        }
        Map<String, Object> tenant = repository.tenant(target);
        if (tenant == null) throw unauthorized();
        Map<String, Object> principal = repository.object(tenant.get("api_principal_config"));
        Object mode = principal.get("mode");
        if (mode != null && !"".equals(mode) && !"tenant".equals(mode)) {
            throw new ApiException(403, "AUTH_MODE_NOT_MIGRATED", "External API principal authentication is not migrated");
        }
        AuthContext auth = new AuthContext(repository.tenantApiKeyUserId(target), target, AuthRepository.bool(key, "full_access") ? "owner" : "viewer", false,
                AuthRepository.number(key, "id"), AuthRepository.bool(key, "full_access"),
                Set.copyOf(TenantApiKeyService.normalizeCapabilities(new java.util.ArrayList<>(repository.strings(key.get("capabilities"))))), repository.strings(key.get("knowledge_base_ids")));
        authorizeKeyRoute(request, auth);
        return auth;
    }
    private void authorizeKeyRoute(HttpServletRequest request, AuthContext auth) {
        String systemPath=request.getRequestURI(),systemMethod=request.getMethod();
        if(systemPath.equals("/api/v1/system/parser-engines")&&systemMethod.equals("GET") || Set.of("/api/v1/system/parser-engines/check","/api/v1/system/docreader/reconnect").contains(systemPath)&&systemMethod.equals("POST")) {
            if(!auth.fullAccess()&&!auth.capabilities().contains("manage_vector_stores"))throw new ApiException(403,"FORBIDDEN","Forbidden: API key scope does not allow this operation").withResponse(Map.of("error","Forbidden: API key scope does not allow this operation"));
            return;
        }
        if (request.getRequestURI().equals("/api/v1/chunker/preview") && request.getMethod().equals("POST")) {
            if (!auth.fullAccess() && !auth.capabilities().contains("retrieve") && !auth.capabilities().contains("ingest")) throw new ApiException(403,"FORBIDDEN","Forbidden: API key capability required");
            return;
        }
        String tenantPath=request.getRequestURI();
        if(tenantPath.equals("/api/v1/tenants/kv/parser-engine-config")&&Set.of("GET","PUT").contains(request.getMethod())){
            if(!auth.fullAccess()&&!auth.capabilities().contains("manage_tenant_settings"))throw new ApiException(403,"FORBIDDEN","Forbidden: API key scope does not allow this operation").withResponse(Map.of("error","Forbidden: API key scope does not allow this operation"));
            return;
        }
        if ((tenantPath.equals("/api/v1/tenants") && request.getMethod().equals("GET")) || (Set.of("/api/v1/tenants/kv/retrieval-config","/api/v1/tenants/kv/memory-config").contains(tenantPath) && Set.of("GET","PUT").contains(request.getMethod()))) { auth.requireCapability("manage_tenant_settings"); return; }
        if(request.getRequestURI().matches("/api/v1/tenants/[0-9]+/members")&&Set.of("GET","POST").contains(request.getMethod())||request.getRequestURI().matches("/api/v1/tenants/[0-9]+/members/[^/]+")&&Set.of("PUT","DELETE").contains(request.getMethod())){auth.requireCapability("manage_members");return;}
        String invitationPath=request.getRequestURI();
        if(invitationPath.matches("/api/v1/tenants/[0-9]+/invitations")&&Set.of("GET","POST").contains(request.getMethod())||invitationPath.matches("/api/v1/tenants/[0-9]+/invitations/[^/]+")&&request.getMethod().equals("DELETE")||invitationPath.matches("/api/v1/tenants/[0-9]+/invite-links")&&request.getMethod().equals("POST")){auth.requireCapability("manage_members");return;}
        String base = "/api/v1/knowledge-bases";
        String path = request.getRequestURI();
        String method = request.getMethod();
        if(method.equals("GET")&&(path.matches("/api/v1/knowledge-bases/[^/]+/knowledge")||path.matches("/api/v1/knowledge/[^/]+")||path.matches("/api/v1/chunks/[^/]+")||path.matches("/api/v1/chunks/by-id/[^/]+"))){
            if(!auth.fullAccess()&&!auth.capabilities().contains("retrieve"))throw new ApiException(403,"FORBIDDEN","Forbidden: API key scope does not allow this operation").withResponse(Map.of("error","Forbidden: API key scope does not allow this operation"));
            return; // Native content controllers resolve authoritative parent KB scope before reading data.
        }
        if (path.equals(base) && method.equals("GET")) { auth.requireCapability("retrieve"); return; }
        if (path.equals(base) && method.equals("POST")) {
            auth.requireCapability("manage_kbs");
            return;
        }
        if (path.startsWith(base + "/")) {
            String id = path.substring(base.length() + 1);
            if (id.endsWith("/pin") && method.equals("PUT")) {
                String kbId = id.substring(0, id.length() - 4);
                if (!kbId.isBlank() && !kbId.contains("/")) {
                    auth.requireCapability("retrieve");
                    auth.requireKnowledgeBase(kbId);
                    return;
                }
            }
            if (!id.isBlank() && !id.contains("/") && Set.of("GET", "PUT", "DELETE").contains(method)) {
                if(method.equals("GET")) {
                    // Preserve the API-key gate before native routing and its Go response envelopes.
                    if(!auth.fullAccess()&&!auth.capabilities().contains("retrieve"))throw new ApiException(403,"FORBIDDEN","Forbidden: API key scope does not allow this operation").withResponse(Map.of("error","Forbidden: API key scope does not allow this operation"));
                    if(!auth.allowsKnowledgeBase(id)){var error=new java.util.LinkedHashMap<String,Object>();error.put("code",1002);error.put("message","API key scope does not allow one or more knowledge bases");error.put("details",null);throw new ApiException(403,"FORBIDDEN","API key scope does not allow one or more knowledge bases").withResponse(Map.of("success",false,"error",error));}
                    return;
                }
                auth.requireCapability("manage_kbs");
                auth.requireKnowledgeBase(id);
                return;
            }
        }
        throw new ApiException(403, "FORBIDDEN", "Forbidden: API key route is not migrated");
    }
    public static String hashApiKey(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.trim().getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    private static long tenantClaim(Object claim, long fallback) {
        try {
            if (claim instanceof Number n && n.doubleValue() >= 0 && n.doubleValue() == n.longValue()) return n.longValue();
            if (claim instanceof String s) { long parsed = Long.parseLong(s); if (parsed >= 0) return parsed; }
        } catch (NumberFormatException ignored) { }
        return fallback;
    }
    private static long parseTenant(String header) {
        try { if (!header.matches("[0-9]+")) throw new NumberFormatException(); long id = Long.parseLong(header); if (id <= 0) throw new NumberFormatException(); return id; }
        catch (NumberFormatException ex) { throw new ApiException(400, "INVALID_TENANT_ID", "Invalid X-Tenant-ID header"); }
    }
    private static boolean tenantOptional(HttpServletRequest request) {
        String path = request.getRequestURI();
        if(path.startsWith("/api/v1/me/invitations"))return true;
        return (path.equals("/api/v1/auth/me") && request.getMethod().equals("GET"))
                || (path.equals("/api/v1/auth/me/preferences") && request.getMethod().equals("PUT"))
                || (path.equals("/api/v1/auth/logout") && request.getMethod().equals("POST"))
                || (Set.of("/api/v1/auth/switch-tenant", "/api/v1/auth/change-password").contains(path) && request.getMethod().equals("POST"))
                || (path.equals("/api/v1/auth/validate") && request.getMethod().equals("GET"));
    }
    private static ApiException unauthorized() { return new ApiException(401, "UNAUTHORIZED", "Unauthorized: invalid or expired credential"); }
    private void writeError(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mapper.writeValue(response.getWriter(), Map.of("error", message, "code", code));
    }
}
