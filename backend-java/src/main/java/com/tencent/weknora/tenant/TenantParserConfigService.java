package com.tencent.weknora.tenant;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.NullNode;
import com.tencent.weknora.docreader.ParserEngineSettings;
import com.tencent.weknora.model.ModelURLPolicy;
import com.tencent.weknora.security.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Tenant KV parser settings; probes and shared reader reconnect are separate operations. */
@Service
public class TenantParserConfigService {
    private final JdbcTemplate jdbc;
    private final AuthRepository authRepository;
    private final ParserEngineSettings settings;
    private final ModelURLPolicy policy;
    private final TransactionTemplate writes;

    public TenantParserConfigService(JdbcTemplate jdbc, AuthRepository authRepository,
                                    ParserEngineSettings settings, ModelURLPolicy policy,
                                    PlatformTransactionManager transactions) {
        this.jdbc = jdbc; this.authRepository = authRepository; this.settings = settings; this.policy = policy;
        writes = new TransactionTemplate(transactions);
        writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Map<String,Object> get(AuthContext auth) {
        authorize(auth, "viewer");
        try { return Map.of("success", true, "data", settings.response(load(auth.tenantId()))); }
        catch (IOException ex) { throw failure(ex); }
    }

    public Map<String,Object> update(AuthContext auth, byte[] body) {
        authorize(auth, "admin");
        JsonNode incoming;
        try { incoming = settings.request(body); }
        catch (IOException ex) { throw new ApiException(400, "VALIDATION", "Invalid request data").withAppCode(1010).withDetails(ex.getMessage()); }
        try {
            return writes.execute(status -> {
                if (jdbc.update("UPDATE tenants SET updated_at=updated_at WHERE id=? AND deleted_at IS NULL", auth.tenantId()) != 1)
                    throw missing();
                if (!auth.isApiKey() && !auth.crossTenantSuperuser() && auth.rbacEnforced()
                        && AuthContext.level(authRepository.role(auth.userId(), auth.tenantId())) < AuthContext.level("admin"))
                    throw new ApiException(403, "FORBIDDEN", "Forbidden: insufficient workspace role");
                JsonNode merged = settings.merge(incoming, load(auth.tenantId()));
                for (String field : List.of("mineru_endpoint", "mineru_vlm_server_url", "odl_hybrid_url", "paddleocr_vl_endpoint")) {
                    String endpoint = com.tencent.weknora.storage.StoragePaths.trim(merged.path(field).asText(""));
                    if (endpoint.isEmpty()) continue;
                    try { policy.validate(endpoint); }
                    catch (ApiException ex) {
                        String reason = ex.getMessage(), prefix = "Base URL 未通过安全校验：";
                        if (reason.startsWith(prefix)) {
                            reason = reason.substring(prefix.length());
                            int hint = reason.indexOf("。如该地址确实可信");
                            if (hint >= 0) reason = reason.substring(0, hint);
                        }
                        throw new ApiException(400, "VALIDATION", field + " failed SSRF validation: " + reason).withAppCode(1010);
                    }
                }
                try {
                    byte[] stored = settings.stored(merged);
                    boolean sqlite = Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)
                            connection -> connection.getMetaData().getDatabaseProductName().equalsIgnoreCase("SQLite")));
                    jdbc.update("UPDATE tenants SET parser_engine_config=" + authRepository.jsonParameter()
                                    + ",updated_at=? WHERE id=? AND deleted_at IS NULL", sqlite ? stored : new String(stored, java.nio.charset.StandardCharsets.UTF_8),
                            authRepository.timestampParameter(Instant.now()), auth.tenantId());
                    return Map.of("success", true, "data", settings.response(merged), "message", "解析引擎配置已更新");
                } catch (IOException ex) { throw failure(ex); }
            });
        } catch (DataAccessException ex) { throw failure(ex); }
    }

    private static void authorize(AuthContext auth, String floor) {
        auth.requireRole(floor);
        if (auth.isApiKey()) {
            if (!auth.fullAccess() && !auth.capabilities().contains("manage_tenant_settings"))
                throw new ApiException(403, "FORBIDDEN", "Forbidden: API key scope does not allow this operation")
                        .withResponse(Map.of("error", "Forbidden: API key scope does not allow this operation"));
        } else if (AuthContext.level(auth.role()) < AuthContext.level("admin")) {
            throw new ApiException(403, "FORBIDDEN", "integration configuration requires admin access").withAppCode(1002);
        }
    }

    private JsonNode load(long tenant) {
        var rows = jdbc.queryForList("SELECT parser_engine_config FROM tenants WHERE id=? AND deleted_at IS NULL", tenant);
        if (rows.isEmpty()) throw missing();
        Object raw = rows.getFirst().get("parser_engine_config");
        if (raw == null) return NullNode.instance;
        if (raw instanceof byte[] bytes) return settings.config(bytes);
        if (raw.getClass().getName().equals("org.postgresql.util.PGobject"))
            return settings.config(raw.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        // Go's Scan ignores a string source and leaves a non-nil zero struct.
        return new ObjectMapper().createObjectNode();
    }

    private static ApiException missing() { return new ApiException(400, "BAD_REQUEST", "Workspace is empty").withAppCode(1000); }
    private static ApiException failure(Exception ex) {
        return new ApiException(500, "INTERNAL", "Failed to update workspace parser engine config").withAppCode(1007)
                .withDetails(ex.getMessage());
    }
}
