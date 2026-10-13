package com.tencent.weknora.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Repository;

@Repository
public class AuthRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final GoSecretCodec secrets;
    private volatile Boolean sqlite;
    private volatile String databaseProduct;
    public AuthRepository(JdbcTemplate jdbc, ObjectMapper mapper) { this(jdbc,mapper,new GoSecretCodec(new org.springframework.core.env.StandardEnvironment())); }
    @org.springframework.beans.factory.annotation.Autowired
    public AuthRepository(JdbcTemplate jdbc, ObjectMapper mapper,GoSecretCodec secrets) { this.jdbc = jdbc; this.mapper = mapper;this.secrets=secrets; }
    Map<String, Object> user(String id) { return one("SELECT * FROM users WHERE id=? AND deleted_at IS NULL AND is_active=true", id); }
    Map<String, Object> userByEmail(String email) { return one("SELECT * FROM users WHERE email=? AND deleted_at IS NULL AND is_active=true", email); }
    Map<String, Object> anyUserByEmail(String email) { return one("SELECT * FROM users WHERE email=? AND deleted_at IS NULL", email); }
    Map<String, Object> anyUserByUsername(String username) { return one("SELECT * FROM users WHERE username=? AND deleted_at IS NULL", username); }
    long createPersonalTenant(String username, RegistrationStorageSnapshot storage) {
        String sql="INSERT INTO tenants(name,description,status,business,retriever_engines,storage_quota,storage_used) VALUES(?,?,'active','',"+jsonParameter()+",10737418240,0) RETURNING id";
        Long id=jdbc.queryForObject(sql,Long.class,username+"'s Workspace","Default workspace",encode(Map.of("engines",List.of())));
        if(id==null)throw new ApiException(400,"INVALID_REQUEST","failed to create workspace").withAppCode(1000);
        String backend=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO storage_backends(id,tenant_id,name,provider,config,source,status,legacy_alias) VALUES(?,?,?,?,"+jsonParameter()+",'env','active',true)",backend,id,"System "+storage.provider().toUpperCase(java.util.Locale.ROOT),storage.provider(),encode(storage.config()));
        jdbc.update("UPDATE tenants SET default_storage_backend_id=? WHERE id=?",backend,id);
        return id;
    }
    Map<String,Object> createRegisteredUser(String username,String email,String hash,long tenantId) {
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO users(id,username,email,password_hash,avatar,tenant_id,is_active,can_access_all_tenants,is_system_admin,preferences) VALUES(?,?,?,?,'',?,true,false,false,"+jsonParameter()+")",id,username,email,hash,tenantId==0?null:tenantId,encode(Map.of()));
        if(tenantId!=0)jdbc.update("INSERT INTO tenant_members(user_id,tenant_id,role,status) VALUES(?,?,'owner','active')",id,tenantId);
        return user(id);
    }
    Map<String, Object> setting(String key) { return one("SELECT value,value_type,last_modified_by FROM system_settings WHERE key=?", key); }
    public Object jsonValue(Object value) {
        if (value == null) return null;
        if (value instanceof Map<?, ?> || value instanceof List<?> || value instanceof Boolean || value instanceof Number) return value;
        try { return mapper.readValue(value instanceof byte[] bytes ? new String(bytes, java.nio.charset.StandardCharsets.UTF_8) : value.toString(), Object.class); }
        catch (Exception ex) { throw new ApiException(503, "INVALID_AUTH_CONFIGURATION", "Invalid stored authentication configuration"); }
    }
    Map<String, Object> preferences(Map<String, Object> user) { return AuthPreferences.typed(object(user.get("preferences"))); }
    void savePreferences(String userId, Map<String, Object> preferences) {
        String sql = "UPDATE users SET preferences=" + jsonParameter() + ",updated_at=CURRENT_TIMESTAMP WHERE id=? AND deleted_at IS NULL";
        if (jdbc.update(sql, encode(preferences), userId) != 1) throw new ApiException(401, "UNAUTHORIZED", "User not found");
    }
    void saveHomeTenant(String userId, long tenantId) {
        jdbc.update("UPDATE users SET tenant_id=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND deleted_at IS NULL", tenantId == 0 ? null : tenantId, userId);
    }
    void replacePassword(String userId, String oldHash, String newHash, Map<String, Object> preferences) {
        String json = jsonParameter();
        if (jdbc.update("UPDATE users SET password_hash=?,preferences=" + json + ",updated_at=CURRENT_TIMESTAMP WHERE id=? AND password_hash=? AND deleted_at IS NULL AND is_active=true", newHash, encode(preferences), userId, oldHash) != 1) {
            throw new ApiException(400, "INVALID_REQUEST", "Current password is incorrect").withAppCode(1000).withDetails("invalid_old_password");
        }
    }
    void revokeRefreshToken(String userId, String token) {
        jdbc.update("UPDATE auth_tokens SET is_revoked=true,updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND token=? AND token_type='refresh_token'", userId, token);
    }
    private String encode(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalStateException("Cannot encode user preferences", ex); }
    }
    public String jsonString(Object value) { return encode(value); }
    String tenantApiKeyUserId(long tenantId) {
        // Go's GetUserByTenantID chooses the earliest non-deleted user for
        // legacy user-scoped operations such as pins, independently of key authority.
        Map<String, Object> row = one("SELECT id FROM users WHERE tenant_id=? AND deleted_at IS NULL ORDER BY created_at,id LIMIT 1", tenantId);
        return row == null ? "system-" + tenantId : string(row, "id");
    }
    Map<String, Object> tenant(long id) { return one("SELECT * FROM tenants WHERE id=? AND deleted_at IS NULL AND status='active'", id); }
    Map<String, Object> key(String hash) {
        Map<String, Object> row = one("SELECT * FROM tenant_api_keys WHERE key_hash=? AND revoked_at IS NULL", hash);
        return row == null || (row.get("expires_at") != null && !future(row.get("expires_at"))) ? null : row;
    }
    boolean activeAccessToken(String token, String userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT expires_at,is_revoked,token_type FROM auth_tokens WHERE token=? AND user_id=?", token, userId);
        // Go can mint byte-identical JWTs twice within a second. They are one
        // credential, so duplicate live rows are valid and any revoked row wins.
        return !rows.isEmpty() && rows.stream().allMatch(row -> !bool(row,"is_revoked") && string(row,"token_type").equals("access_token") && future(row.get("expires_at")));
    }
    boolean consumeRefreshToken(String token, String userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT expires_at,is_revoked,token_type FROM auth_tokens WHERE token=? AND user_id=?", token, userId);
        boolean valid = !rows.isEmpty() && rows.stream().allMatch(row -> !bool(row,"is_revoked") && string(row,"token_type").equals("refresh_token") && future(row.get("expires_at")));
        return valid && jdbc.update("UPDATE auth_tokens SET is_revoked=true,updated_at=CURRENT_TIMESTAMP WHERE token=? AND user_id=? AND token_type='refresh_token' AND is_revoked=false", token, userId) > 0;
    }
    public String role(String userId, long tenantId) {
        Map<String, Object> row = one("SELECT role FROM tenant_members WHERE user_id=? AND tenant_id=? AND status='active' AND deleted_at IS NULL", userId, tenantId);
        return row == null ? null : string(row, "role");
    }
    List<Map<String, Object>> memberships(String userId) {
        return jdbc.queryForList("SELECT m.tenant_id, t.name AS tenant_name, m.role FROM tenant_members m JOIN tenants t ON t.id=m.tenant_id WHERE m.user_id=? AND m.status='active' AND m.deleted_at IS NULL AND t.deleted_at IS NULL AND t.status='active' ORDER BY m.joined_at, m.id", userId);
    }
    long firstTenant(String userId) {
        List<Map<String, Object>> rows = memberships(userId);
        return rows.isEmpty() ? 0 : number(rows.get(0), "tenant_id");
    }
    void storeToken(String userId, String token, String type, Instant expiry) {
        jdbc.update("INSERT INTO auth_tokens(id,user_id,token,token_type,expires_at,is_revoked,created_at,updated_at) VALUES(?,?,?,?,?,false,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(), userId, token, type, timestampParameter(expiry));
    }
    public void revokeUserTokens(String userId) {
        jdbc.update("UPDATE auth_tokens SET is_revoked=true,updated_at=CURRENT_TIMESTAMP WHERE user_id=?", userId);
    }
    Map<String, Object> object(Object value) {
        if (value == null) return Map.of();
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((key, entry) -> out.put(key.toString(), entry));
            return out;
        }
        try { return mapper.readValue(value instanceof byte[] bytes ? new String(bytes, java.nio.charset.StandardCharsets.UTF_8) : value.toString(), new TypeReference<Map<String, Object>>() { }); }
        catch (Exception ex) { throw new ApiException(503, "INVALID_AUTH_CONFIGURATION", "Invalid stored authentication configuration"); }
    }
    Set<String> strings(Object value) {
        if (value == null) return Set.of();
        try {
            List<String> list = mapper.readValue(value instanceof byte[] bytes?new String(bytes,java.nio.charset.StandardCharsets.UTF_8):value.toString(), new TypeReference<List<String>>() { });
            if(list==null)return Set.of();
            return list.stream().map(s->s==null?"":AuthPreferences.trimGoSpace(s)).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
        } catch (Exception ex) { throw new ApiException(503, "INVALID_AUTH_CONFIGURATION", "Invalid stored API key scope"); }
    }
    public Map<String, Object> publicUser(Map<String, Object> user) {
        Map<String, Object> result = project(user, "id", "username", "email", "avatar", "tenant_id", "is_active", "is_system_admin", "created_at", "updated_at");
        result.put("can_access_all_tenants", bool(user, "can_access_all_tenants"));
        for (String field : List.of("id", "username", "email", "avatar")) result.put(field, string(user, field));
        result.put("tenant_id", number(user, "tenant_id"));
        result.put("is_active", bool(user, "is_active"));
        result.put("is_system_admin", bool(user, "is_system_admin"));
        result.put("preferences", preferences(user));
        return result;
    }
    public Map<String, Object> publicTenant(Map<String, Object> tenant) {
        return publicTenant(tenant, "viewer");
    }
    public Map<String, Object> publicTenant(Map<String, Object> tenant, String role) {
        if (tenant == null) return null;
        // Never serialize credentials or api_principal_config.hmac_secret.
        Map<String, Object> out = project(tenant, "id", "name", "description", "status", "business", "storage_quota", "storage_used", "created_at", "updated_at");
        for (String field : List.of("name", "description", "status", "business")) out.put(field, string(tenant, field));
        for (String field : List.of("id", "storage_quota", "storage_used")) out.put(field, number(tenant, field));
        Object engines = json(tenant.get("retriever_engines"), null);
        Map<String, Object> wrapped = new LinkedHashMap<>();
        if (engines instanceof List<?> list) wrapped.put("engines", list);
        else if (engines instanceof Map<?, ?> map) wrapped.put("engines", map.get("engines"));
        else wrapped.put("engines", null);
        out.put("retriever_engines", wrapped);
        for (String field : List.of("context_config", "chat_history_config", "retrieval_config", "memory_config")) {
            if (tenant.get(field) != null) out.put(field, json(tenant.get(field), null));
        }
        out.put("deleted_at", null);
        if (AuthContext.level(role) >= AuthContext.level("admin")) {
            for (String field : List.of("web_search_config", "parser_engine_config", "storage_engine_config", "credentials")) {
                if (tenant.get(field) == null) continue;
                Map<String, Object> config = new LinkedHashMap<>(object(tenant.get(field)));
                switch (field) {
                    case "web_search_config" -> {
                        config.remove("api_key"); mask(config, "proxy_url");
                        if (!(config.get("max_results") instanceof Number n) || n.intValue() <= 0) config.put("max_results", 10);
                        config.putIfAbsent("include_date", false);
                        if (config.get("compression_method") == null || config.get("compression_method").equals("")) config.put("compression_method", "none");
                        if (config.get("blacklist") == null) config.put("blacklist", List.of());
                    }
                    case "parser_engine_config" -> { mask(config, "mineru_api_key", "mineru_server_api_key", "paddleocr_vl_cloud_token"); config.putIfAbsent("mineru_endpoint", ""); config.putIfAbsent("mineru_api_key", ""); }
                    case "storage_engine_config" -> {
                        config.putIfAbsent("default_provider", "");
                        for (String provider : List.of("minio", "cos", "tos", "s3", "oss", "ks3", "obs")) {
                            if (config.get(provider) instanceof Map<?, ?> nested) {
                                Map<String, Object> copy = new LinkedHashMap<>();
                                nested.forEach((key, value) -> copy.put(key.toString(), value));
                                mask(copy, "access_key_id", "secret_access_key", "secret_id", "secret_key", "access_key");
                                config.put(provider, copy);
                            }
                        }
                    }
                    case "credentials" -> {
                        if (config.get("weknoracloud") instanceof Map<?, ?> cloud) {
                            Map<String, Object> copy = new LinkedHashMap<>();
                            cloud.forEach((key, value) -> copy.put(key.toString(), value));
                            if(copy.get("app_secret") instanceof String secret)copy.put("app_secret",secrets.decryptLenient(secret));
                            mask(copy, "app_secret"); config.put("weknoracloud", copy);
                        }
                    }
                    default -> { }
                }
                out.put(field, config);
            }
        }
        return out;
    }
    private static void mask(Map<String, Object> config, String... names) {
        for (String name : names) if (config.get(name) instanceof String value && !value.isEmpty()) config.put(name, "***");
    }
    private Map<String, Object> project(Map<String, Object> source, String... names) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String name : names) {
            Object value = source.get(name);
            out.put(name, name.endsWith("_at") && value != null ? instant(value).toString() : value);
        }
        return out;
    }
    private Object json(Object value, Object fallback) {
        if (value == null) return fallback;
        return jsonValue(value);
    }
    public Object timestampParameter(Instant instant) {
        // sqlite-jdbc binds Timestamp as epoch millis, whereas Go's driver expects DATETIME text.
        return isSqlite() ? DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSSSSxxx").withZone(ZoneOffset.UTC).format(instant) : Timestamp.from(instant);
    }
    private boolean isSqlite() {
        if (sqlite == null) sqlite = product().equalsIgnoreCase("SQLite");
        return Boolean.TRUE.equals(sqlite);
    }
    private String product() {
        if (databaseProduct == null) databaseProduct = jdbc.execute((ConnectionCallback<String>) c -> c.getMetaData().getDatabaseProductName());
        return databaseProduct;
    }
    public String jsonParameter() {
        return switch (product()) { case "PostgreSQL" -> "CAST(? AS JSONB)"; case "H2" -> "? FORMAT JSON"; default -> "?"; };
    }
    private static boolean future(Object value) { return value != null && instant(value).isAfter(Instant.now()); }
    static Instant instant(Object value) {
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof OffsetDateTime date) return date.toInstant();
        if (value instanceof java.util.Date date) return date.toInstant();
        if (value instanceof Number number) return Instant.ofEpochMilli(number.longValue());
        String text = value.toString().trim().replaceFirst(" ", "T").replaceFirst("\\s+([+-]\\d{2}:\\d{2})$", "$1");
        try { return OffsetDateTime.parse(text).toInstant(); }
        catch (java.time.format.DateTimeParseException ignored) {
            try { return LocalDateTime.parse(text).toInstant(ZoneOffset.UTC); }
            catch (java.time.format.DateTimeParseException ex) { throw new ApiException(503, "INVALID_AUTH_CONFIGURATION", "Invalid stored authentication timestamp"); }
        }
    }
    private Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        if (rows.size() > 1) throw new ApiException(503, "INVALID_AUTH_CONFIGURATION", "Ambiguous authentication record");
        return rows.isEmpty() ? null : rows.get(0);
    }
    static long number(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number number) {
            try { return new java.math.BigDecimal(number.toString()).longValueExact(); }
            catch (ArithmeticException ex) { return 0; }
        }
        try { return value == null ? 0 : Long.parseLong(value.toString()); }
        catch (NumberFormatException ex) { return 0; }
    }
    static String string(Map<String, Object> map, String key) { Object value=map.get(key);return value==null?"":value instanceof byte[] bytes?com.tencent.weknora.docreader.GoUtf8.decode(bytes):value.toString(); }
    static boolean bool(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return Boolean.TRUE.equals(value) || (value instanceof Number n && n.intValue() != 0) || "true".equalsIgnoreCase(String.valueOf(value));
    }
}
