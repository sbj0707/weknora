package com.tencent.weknora.security;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.StandardEnvironment;

@Service
public class SecurityAuthService {
    private final AuthRepository repository;
    private final JwtCodec jwt;
    private final SecuritySettings settings;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
    // Equal-cost comparison for nonexistent users prevents the cheap missing-user path.
    private final String dummyPassword = passwords.encode(UUID.randomUUID().toString());
    public SecurityAuthService(AuthRepository repository, JwtCodec jwt) { this(repository, jwt, new SecuritySettings(repository, new StandardEnvironment())); }
    @Autowired
    public SecurityAuthService(AuthRepository repository, JwtCodec jwt, SecuritySettings settings) { this.repository = repository; this.jwt = jwt; this.settings = settings; }
    public Map<String,Object> authConfig() {
        return Map.of("success",true,"registration_mode",settings.registrationMode(),"complex_password_enabled",settings.complexPasswordEnabled());
    }
    @Transactional
    public Map<String,Object> register(Map<String,Object> body) {
        if(!settings.registrationMode().equals("self_serve"))throw new ApiException(403,"FORBIDDEN","Public registration is disabled").withAppCode(1002);
        String username=requiredString(body,"username","Invalid registration parameters");
        String email=requiredString(body,"email","Invalid registration parameters");
        String password=requiredString(body,"password","Invalid registration parameters");
        int length=username.codePointCount(0,username.length());
        if(length<2||length>50||!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")||password.codePointCount(0,password.length())<6)throw new ApiException(400,"INVALID_REQUEST","Invalid registration parameters").withAppCode(1010);
        username=sanitizeForLog(username);email=sanitizeForLog(email);
        if(username.isEmpty()||email.isEmpty())throw new ApiException(400,"INVALID_REQUEST","Username, email and password are required").withAppCode(1010);
        try {validatePasswordPolicy(password,settings.complexPasswordEnabled());}
        catch(ApiException ex){
            boolean size=password.codePointCount(0,password.length())<8||password.codePointCount(0,password.length())>32;
            String message=!size&&settings.complexPasswordEnabled()?"password must be 8-32 characters and must contain uppercase and lowercase letters, numbers, and special characters":"password must be 8-32 characters and contain at least one letter and one number";
            throw new ApiException(400,"INVALID_REQUEST",message).withAppCode(1010);
        }
        if(repository.anyUserByEmail(email)!=null)throw new ApiException(400,"INVALID_REQUEST","user with this email already exists").withAppCode(1000);
        if(repository.anyUserByUsername(username)!=null)throw new ApiException(400,"INVALID_REQUEST","user with this username already exists").withAppCode(1000);
        if(password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)throw new ApiException(400,"INVALID_REQUEST","failed to process password").withAppCode(1000);
        String hash=passwords.encode(password);
        long tenant=0;
        if(settings.defaultTenantMode().equals("create_personal")) {
            RegistrationStorageSnapshot storage=RegistrationStorageSnapshot.from(settings.environment(),new GoSecretCodec(settings.environment()));
            try{tenant=repository.createPersonalTenant(username,storage);}
            catch(org.springframework.dao.DataAccessException ex){throw new ApiException(400,"INVALID_REQUEST","failed to create workspace").withAppCode(1000);}
        }
        Map<String,Object> user;
        try{user=repository.createRegisteredUser(username,email,hash,tenant);}
        catch(org.springframework.dao.DataAccessException ex){throw new ApiException(400,"INVALID_REQUEST","failed to create user").withAppCode(1000);}
        Map<String,Object> profile=repository.publicUser(user);profile.put("deleted_at",null);
        return Map.of("success",true,"message","Registration successful","user",profile);
    }
    private static String sanitizeForLog(String value) {
        StringBuilder out=new StringBuilder();value.codePoints().forEach(cp->{if(cp=='\n'||cp=='\r'||cp=='\t')out.append(' ');else if(cp>=32)out.appendCodePoint(cp);});return out.toString();
    }
    @Transactional
    public Map<String, Object> login(String email, String password) {
        if (email == null || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+") || password == null || password.codePointCount(0, password.length()) < 6) {
            throw new ApiException(400, "INVALID_REQUEST", "Invalid login parameters").withAppCode(1010);
        }
        Map<String, Object> user = repository.anyUserByEmail(email);
        if (user != null && !AuthRepository.bool(user, "is_active")) throw loginFailure("Account is disabled");
        String hash = user == null ? dummyPassword : AuthRepository.string(user, "password_hash");
        if (!passwords.matches(password, hash) || user == null) {
            throw loginFailure("Invalid email or password");
        }
        return issueTokens(user);
    }
    @Transactional
    public Map<String, Object> refresh(String token) {
        try {
        Map<String, Object> claims = jwt.verify(token);
        String userId = (String) claims.get("user_id");
        if (!"refresh".equals(claims.get("type")) || !repository.consumeRefreshToken(token, userId)) {
            throw new ApiException(401, "UNAUTHORIZED", "Invalid or revoked refresh token");
        }
        Map<String, Object> user = repository.user(userId);
        if (user == null) throw new ApiException(401, "UNAUTHORIZED", "User is inactive");
        return issueTokens(user);
        }catch(ApiException ex){throw new ApiException(401,"UNAUTHORIZED","Token refresh failed").withAppCode(1001).withDetails(ex.getMessage());}
    }
    Map<String, Object> issueTokens(Map<String, Object> user) {
        String userId = AuthRepository.string(user, "id");
        List<Map<String, Object>> memberships = repository.memberships(userId);
        long target = resolveLoginTenant(user, memberships);
        return issueTokens(user, target, memberships);
    }
    private Map<String, Object> issueTokens(Map<String, Object> user, long target, List<Map<String, Object>> memberships) {
        String userId = AuthRepository.string(user, "id");
        Instant now = Instant.now();
        String access = jwt.sign(Map.of("user_id", userId, "email", AuthRepository.string(user, "email"), "tenant_id", target,
                "type", "access", "iat", now.getEpochSecond(), "exp", now.plusSeconds(86400).getEpochSecond(), "jti", UUID.randomUUID().toString()));
        String refresh = jwt.sign(Map.of("user_id", userId, "type", "refresh", "iat", now.getEpochSecond(),
                "exp", now.plusSeconds(604800).getEpochSecond(), "jti", UUID.randomUUID().toString()));
        repository.storeToken(userId, access, "access_token", now.plusSeconds(86400));
        repository.storeToken(userId, refresh, "refresh_token", now.plusSeconds(604800));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "Login successful");
        Map<String, Object> profile = repository.publicUser(user);
        profile.put("deleted_at", null);
        result.put("user", profile);
        if (target != 0) result.put("active_tenant", repository.publicTenant(repository.tenant(target), roleFor(userId, target)));
        result.put("memberships", memberships);
        result.put("token", access);
        result.put("refresh_token", refresh);
        return result;
    }
    public Map<String, Object> me(AuthContext auth) {
        if (auth.isApiKey()) throw new ApiException(403, "FORBIDDEN", "API keys cannot access user identity");
        Map<String, Object> user = repository.user(auth.userId());
        if (user == null) throw new ApiException(401, "UNAUTHORIZED", "User is inactive");
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Object> profile = repository.publicUser(user);
        profile.put("can_access_all_tenants", AuthRepository.bool(user, "can_access_all_tenants") && settings.crossTenantEnabled());
        data.put("user", profile);
        data.put("preference_defaults", Map.of("browser_search_instructions", AuthPreferences.DEFAULT_BROWSER_SEARCH));
        data.put("tenant", repository.publicTenant(auth.tenantId() == 0 ? null : repository.tenant(auth.tenantId()), auth.role()));
        data.put("memberships", repository.memberships(auth.userId()));
        data.put("tenant_required", auth.tenantId() == 0);
        data.put("capabilities", Map.of("can_create_tenant", AuthRepository.bool(user, "can_access_all_tenants") || settings.selfServiceCreationEnabled(), "auto_accept_invitation", settings.autoAcceptInvitation()));
        return Map.of("success", true, "data", data);
    }
    @Transactional
    public Map<String, Object> logout(AuthContext auth) {
        if (auth.isApiKey()) throw new ApiException(403, "FORBIDDEN", "API keys cannot logout users");
        repository.revokeUserTokens(auth.userId());
        return Map.of("success", true, "message", "Logout successful");
    }
    public Map<String, Object> validate(AuthContext auth, String authorizationHeader) {
        requireHuman(auth);
        if (authorizationHeader == null || authorizationHeader.isEmpty()) throw new ApiException(400, "INVALID_REQUEST", "Authorization header is required").withAppCode(1010);
        String[] parts = authorizationHeader.split(" ", -1);
        if (parts.length != 2 || !parts[0].equals("Bearer")) throw new ApiException(400, "INVALID_REQUEST", "Invalid Authorization header format").withAppCode(1010);
        try {
            Map<String, Object> claims = jwt.verifyAccess(parts[1]);
            String userId = (String)claims.get("user_id");
            Map<String, Object> user = repository.user(userId);
            if (user == null || !userId.equals(auth.userId()) || !repository.activeAccessToken(parts[1], userId)) throw new ApiException(401, "UNAUTHORIZED", "invalid token");
            return Map.of("success", true, "message", "Token is valid", "user", repository.publicUser(user));
        } catch (ApiException ex) { throw new ApiException(401, "UNAUTHORIZED", "Token validation failed").withAppCode(1001).withDetails("invalid token"); }
    }
    @Transactional
    public Map<String, Object> updatePreferences(AuthContext auth, Map<String, Object> patch) {
        Map<String, Object> user = currentUser(auth);
        Map<String, Object> prefs = AuthPreferences.patch(repository.preferences(user), patch);
        repository.savePreferences(auth.userId(), prefs);
        return Map.of("success", true, "data", prefs);
    }
    @Transactional
    public Map<String, Object> switchTenant(AuthContext auth, Map<String, Object> body) {
        Map<String, Object> user = currentUser(auth);
        Object value = body.get("tenant_id");
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long || value instanceof java.math.BigInteger)) {
            throw new ApiException(400, "INVALID_REQUEST", "Invalid workspace switch request").withAppCode(1010);
        }
        long target = AuthRepository.number(body, "tenant_id");
        if (target <= 0) throw new ApiException(400, "INVALID_REQUEST", "Invalid workspace switch request").withAppCode(1010);
        Object refreshValue = body.get("refresh_token");
        if (refreshValue != null && !(refreshValue instanceof String)) throw new ApiException(400, "INVALID_REQUEST", "Invalid workspace switch request").withAppCode(1010);
        String role = repository.role(auth.userId(), target);
        boolean crossSuper = AuthRepository.bool(user, "can_access_all_tenants") && settings.crossTenantEnabled() && target != AuthRepository.number(user, "tenant_id");
        if (role == null && !crossSuper) throw new ApiException(403, "FORBIDDEN", "workspace switch failed").withAppCode(1002).withDetails("tenant membership not found");
        if (repository.tenant(target) == null) throw new ApiException(403, "FORBIDDEN", "workspace switch failed").withAppCode(1002).withDetails("workspace not found");
        Map<String, Object> prefs = AuthPreferences.patch(repository.preferences(user), Map.of("last_active_tenant_id", target));
        repository.savePreferences(auth.userId(), prefs);
        user.put("preferences", repository.jsonString(prefs));
        Map<String, Object> response = issueTokens(user, target, repository.memberships(auth.userId()));
        response.put("message", "Workspace switched");
        if (refreshValue instanceof String token && !token.isBlank()) repository.revokeRefreshToken(auth.userId(), token);
        return response;
    }
    @Transactional
    public Map<String, Object> changePassword(AuthContext auth, Map<String, Object> body) {
        String oldPassword = requiredString(body, "old_password", "Invalid password change request");
        String newPassword = requiredString(body, "new_password", "Invalid password change request");
        Map<String, Object> user = currentUser(auth);
        String hash = AuthRepository.string(user, "password_hash");
        if (!passwords.matches(oldPassword, hash)) throw new ApiException(400, "INVALID_REQUEST", "Current password is incorrect").withAppCode(1000).withDetails("invalid_old_password");
        if (passwords.matches(newPassword, hash)) throw new ApiException(400, "INVALID_REQUEST", "New password must differ from current password").withAppCode(1010).withDetails("same_password");
        validatePasswordPolicy(newPassword, settings.complexPasswordEnabled());
        if (newPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) throw new ApiException(400, "INVALID_REQUEST", "Password change failed").withAppCode(1000).withDetails("bcrypt: password length exceeds 72 bytes");
        Map<String, Object> prefs = repository.preferences(user);
        if (Boolean.TRUE.equals(prefs.get("oidc_only_login"))) prefs.put("oidc_only_login", false);
        repository.replacePassword(auth.userId(), hash, passwords.encode(newPassword), prefs);
        repository.revokeUserTokens(auth.userId());
        return Map.of("success", true, "message", "Password changed successfully");
    }
    static void validatePasswordPolicy(String password, boolean complex) {
        int length = password.codePointCount(0, password.length());
        boolean upper = false, lower = false, digit = false, special = false;
        for (int rune : password.codePoints().toArray()) {
            if (rune >= 'A' && rune <= 'Z') upper = true;
            else if (rune >= 'a' && rune <= 'z') lower = true;
            else if (rune >= '0' && rune <= '9') digit = true;
            else if ("!@#$%^&*()_+-=[]{}|;:,.<>?".indexOf(rune) >= 0) special = true;
        }
        if (length < 8 || length > 32 || !digit || (!upper && !lower) || (complex && (!upper || !lower || !special))) {
            throw new ApiException(400, "INVALID_REQUEST", "Password policy violation").withAppCode(1010).withDetails("password_policy");
        }
    }
    private static String requiredString(Map<String, Object> body, String key, String message) {
        if (body.get(key) instanceof String value && !value.isEmpty()) return value;
        throw new ApiException(400, "INVALID_REQUEST", message).withAppCode(1010);
    }
    private Map<String, Object> currentUser(AuthContext auth) {
        requireHuman(auth);
        Map<String, Object> user = repository.user(auth.userId());
        if (user == null) throw new ApiException(401, "UNAUTHORIZED", "Failed to get user information").withAppCode(1001);
        return user;
    }
    private static void requireHuman(AuthContext auth) {
        if (auth.isApiKey()) throw new ApiException(403, "FORBIDDEN", "API keys cannot access user identity");
    }
    private static ApiException loginFailure(String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", false); response.put("message", message); response.put("memberships", null);
        return new ApiException(401, "UNAUTHORIZED", message).withResponse(response);
    }
    private String roleFor(String userId, long target) { return repository.role(userId, target); }
    private long resolveLoginTenant(Map<String, Object> user, List<Map<String, Object>> memberships) {
        Map<String, Object> prefs = repository.preferences(user);
        long preferred = AuthRepository.number(prefs, "last_active_tenant_id");
        long home = AuthRepository.number(user, "tenant_id");
        boolean superuser = AuthRepository.bool(user, "can_access_all_tenants") && settings.crossTenantEnabled();
        if (preferred > 0 && preferred != home && validLoginTenant(preferred, memberships, superuser)) return preferred;
        if (prefs.containsKey("last_active_tenant_id") && preferred != 0 && preferred != home) {
            prefs.remove("last_active_tenant_id");
            repository.savePreferences(AuthRepository.string(user, "id"), prefs);
            user.put("preferences", repository.jsonString(prefs));
        }
        if (home > 0 && validLoginTenant(home, memberships, superuser)) return home;
        if (home > 0) {
            repository.saveHomeTenant(AuthRepository.string(user, "id"), 0);
            user.put("tenant_id", 0L);
            if (preferred == home) {
                prefs.remove("last_active_tenant_id");
                repository.savePreferences(AuthRepository.string(user, "id"), prefs);
                user.put("preferences", repository.jsonString(prefs));
            }
        }
        for (Map<String, Object> membership : memberships) {
            if (AuthContext.level(AuthRepository.string(membership, "role")) > 0) {
                long first = AuthRepository.number(membership, "tenant_id");
                repository.saveHomeTenant(AuthRepository.string(user, "id"), first);
                user.put("tenant_id", first);
                return first;
            }
        }
        return 0;
    }
    private boolean validLoginTenant(long desired, List<Map<String, Object>> memberships, boolean superuser) {
        if (superuser && repository.tenant(desired) != null) return true;
        for (Map<String, Object> membership : memberships) {
            if (AuthRepository.number(membership, "tenant_id") == desired && AuthContext.level(AuthRepository.string(membership, "role")) > 0) return true;
        }
        return false;
    }
}
