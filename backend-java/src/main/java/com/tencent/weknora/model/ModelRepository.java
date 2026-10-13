package com.tencent.weknora.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.weknora.security.ApiException;
import com.tencent.weknora.security.AuthContext;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Mirrors modelRepository visibility and dto.NewModelResponse using one catalog generation. */
@Repository
public class ModelRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final byte[] aesKey;
    private final ModelCatalogRuntime catalog;
    @Autowired
    public ModelRepository(JdbcTemplate jdbc, ObjectMapper json, @Value("${SYSTEM_AES_KEY:}") String aesKey, ModelCatalogRuntime catalog) {
        this.jdbc = jdbc; this.json = json; this.aesKey = aesKey.getBytes(StandardCharsets.UTF_8);
        this.catalog=catalog;
    }
    ModelRepository(JdbcTemplate jdbc,ObjectMapper json,String aesKey) {this(jdbc,json,aesKey,new ModelCatalogRuntime(null,"","./config"));}

    public List<ObjectNode> list(AuthContext auth) {
        var rows = jdbc.queryForList("SELECT * FROM models WHERE (tenant_id=? OR is_builtin=true) AND deleted_at IS NULL", auth.tenantId());
        CatalogRuntime snapshot=catalog.snapshot();
        return rows.stream().map(row -> project(row, auth,snapshot)).toList();
    }

    public ObjectNode get(AuthContext auth, String id) {
        var rows = jdbc.queryForList("SELECT * FROM models WHERE id=? AND (tenant_id=? OR is_builtin=true) AND deleted_at IS NULL", id, auth.tenantId());
        if (rows.isEmpty()) throw new ApiException(404, "NOT_FOUND", "Model not found");
        var row = rows.getFirst();
        String status = string(row.get("status"));
        if (!status.equals("active")) {
            String message = switch (status) {
                case "downloading" -> "model is currently downloading";
                case "download_failed" -> "model download failed";
                default -> "abnormal model status";
            };
            throw new ApiException(500, "INTERNAL_SERVER_ERROR", message);
        }
        return project(row, auth);
    }

    ObjectNode project(Map<String, Object> row, AuthContext auth) {
        return project(row,auth,catalog.snapshot());
    }
    private ObjectNode project(Map<String,Object> row,AuthContext auth,CatalogRuntime runtime) {
        Set<String> secretExtras=new HashSet<>();
        for(CatalogRuntime.Provider provider:runtime.providers.values())secretExtras.addAll(secretKeys(provider));
        ObjectNode stored = parameters(row.get("parameters"));
        // Validate even when builtin redaction will omit credential metadata.
        text(stored, "api_key"); text(stored, "app_secret");
        ObjectNode params = json.createObjectNode();
        for (String key : List.of("base_url", "interface_type", "parameter_size", "provider")) params.put(key, text(stored, key));
        ObjectNode embedding = json.createObjectNode();
        JsonNode input = stored.get("embedding_parameters");
        if (input != null && !input.isNull() && !input.isObject()) throw invalid();
        for (String key : List.of("dimension", "truncate_prompt_tokens")) embedding.put(key, integer(input, key));
        embedding.put("supports_dimension_override", bool(input, "supports_dimension_override"));
        params.set("embedding_parameters", embedding);
        params.put("supports_vision", bool(stored, "supports_vision"));
        for (String key : List.of("context_window", "max_output_tokens", "max_concurrency")) {
            long value = integer(stored, key); if (value != 0) params.put(key, value);
        }
        if (!text(stored, "app_id").isEmpty()) params.put("app_id", text(stored, "app_id"));
        ObjectNode extra = stringMap(stored.get("extra_config"));
        ObjectNode headers = stringMap(stored.get("custom_headers"));
        JsonNode spec = spec(stored.get("spec"));
        boolean builtin = boolValue(row.get("is_builtin"));
        boolean canManageBuiltin = builtin && auth.systemAdmin();
        boolean details = auth.isAtLeast("admin") || canManageBuiltin;
        if (details && (!builtin || canManageBuiltin)) {
            ObjectNode sanitized = extra.deepCopy();
            secretExtras.forEach(sanitized::remove);
            if (!sanitized.isEmpty()) params.set("extra_config", sanitized);
            if (!headers.isEmpty()) params.set("custom_headers", headers);
            if (spec != null) params.set("spec", spec);
        } else {
            params.put("base_url", "");
        }
        if (builtin && !canManageBuiltin) params.remove("app_id");
        ObjectNode out = json.createObjectNode();
        for (String key : List.of("id", "name", "display_name", "type", "source", "description", "status")) out.put(key, string(row.get(key)));
        out.put("tenant_id", ((Number) row.get("tenant_id")).longValue());
        out.put("is_default", boolValue(row.get("is_default")));
        out.put("is_builtin", builtin);
        out.set("parameters", params);
        for (String key : List.of("created_at", "updated_at")) out.put(key, timestamp(row.get(key)));
        if (!builtin || canManageBuiltin) {
            ObjectNode credentials = json.createObjectNode();
            configured(credentials, "api_key", secretConfigured(text(stored, "api_key")));
            configured(credentials, "app_secret", secretConfigured(text(stored, "app_secret")));
            String provider = CatalogRuntime.trim(text(stored,"provider")).toLowerCase(Locale.ROOT);
            if(provider.isEmpty())provider=runtime.detect(text(stored,"base_url"));
            Set<String> declared=secretKeys(runtime.providers.get(provider));
            for (String key : secretExtras) {
                boolean present = !CatalogRuntime.trim(extra.path(key).asText()).isEmpty();
                if (declared.contains(key) || present) configured(credentials, key, present);
            }
            out.set("credentials", credentials);
        }
        if(string(row.get("source")).equals("remote")&&Set.of("KnowledgeQA","VLLM").contains(string(row.get("type")))) {
            try {
                var ref=new CatalogRuntime.Ref(text(stored,"provider"),string(row.get("name")),text(stored,"base_url"),string(row.get("type")),extra,spec instanceof ObjectNode node?node:null,0);
                out.set("capabilities",runtime.resolve(ref).capabilities());
            }catch(IllegalArgumentException ex) {
                // Actual Go DTO omits capabilities when row resolution fails; the row still reads.
            }
        }
        return out;
    }
    private static Set<String> secretKeys(CatalogRuntime.Provider provider) {
        Set<String> result=new HashSet<>();
        if(provider!=null)for(JsonNode field:provider.definition().path("ExtraFields"))if(field.path("secret").asBoolean()||field.path("type").asText().equals("password"))result.add(field.path("key").asText());
        return result;
    }

    private ObjectNode parameters(Object value) {
        if (value == null) return json.createObjectNode();
        try {
            JsonNode node = json.readTree(value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : value.toString());
            if (node == null || node.isNull()) return json.createObjectNode();
            if (!node.isObject()) throw invalid();
            return (ObjectNode) node;
        } catch (java.io.IOException ex) { throw invalid(); }
    }
    private ObjectNode stringMap(JsonNode node) {
        ObjectNode result = json.createObjectNode();
        if (node == null || node.isNull()) return result;
        if (!node.isObject()) throw invalid();
        node.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (!value.isTextual() && !value.isNull()) throw invalid();
            result.put(entry.getKey(), value.isNull() ? "" : value.textValue());
        });
        return result;
    }
    private JsonNode spec(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isObject()) throw invalid();
        ObjectNode out = json.createObjectNode();
        String api = text(node, "api"); if (!api.isEmpty()) out.put("api", api);
        if (node.hasNonNull("reasoning")) out.put("reasoning", bool(node, "reasoning"));
        for (String key : List.of("context_window", "max_output_tokens")) { long v = integer(node, key); if (v != 0) out.put(key, v); }
        JsonNode input = node.get("input");
        if (input != null && !input.isNull()) {
            if (!input.isArray()) throw invalid();
            var values = json.createArrayNode();
            input.forEach(v -> { if (!v.isTextual() && !v.isNull()) throw invalid(); values.add(v.isNull() ? "" : v.textValue()); });
            if (!values.isEmpty()) out.set("input", values);
        }
        JsonNode levels = node.get("thinking_levels");
        if (levels != null && !levels.isNull()) {
            if (!levels.isObject()) throw invalid();
            levels.forEach(v -> { if (!v.isTextual() && !v.isNull()) throw invalid(); });
            if (!levels.isEmpty()) out.set("thinking_levels", levels.deepCopy());
        }
        JsonNode compat = node.get("compat");
        if (compat != null && !compat.isNull()) { if (!compat.isObject()) throw invalid(); if (!compat.isEmpty()) out.set("compat", compat.deepCopy()); }
        return out;
    }
    private void configured(ObjectNode credentials, String key, boolean value) { credentials.set(key, json.createObjectNode().put("configured", value)); }
    private boolean secretConfigured(String value) {
        if (!value.startsWith("enc:v1:")) return !value.isEmpty();
        if (aesKey.length != 32) return false;
        try {
            byte[] data = Base64.getUrlDecoder().decode(value.substring(7));
            if (data.length < 28) return false;
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(128, data, 0, 12));
            return cipher.doFinal(data, 12, data.length - 12).length > 0;
        } catch (Exception ex) { return false; } // Match Go ModelParameters.Scan lenient decrypt.
    }
    private static String text(JsonNode node, String key) {
        JsonNode value = node == null ? null : node.get(key);
        if (value == null || value.isNull()) return "";
        if (!value.isTextual()) throw invalid();
        return value.textValue();
    }
    private static long integer(JsonNode node, String key) {
        JsonNode value = node == null ? null : node.get(key);
        if (value == null || value.isNull()) return 0;
        if (!value.isIntegralNumber() || !value.canConvertToLong()) throw invalid();
        return value.longValue();
    }
    private static boolean bool(JsonNode node, String key) {
        JsonNode value = node == null ? null : node.get(key);
        if (value == null || value.isNull()) return false;
        if (!value.isBoolean()) throw invalid();
        return value.booleanValue();
    }
    private static String string(Object value) { return value == null ? "" : value.toString(); }
    private static boolean boolValue(Object value) { return Boolean.TRUE.equals(value) || value instanceof Number n && n.intValue() != 0 || "true".equalsIgnoreCase(string(value)); }
    private static String timestamp(Object value) {
        if (value == null) return "0001-01-01T00:00:00Z";
        if (value instanceof Number n) return DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(n.longValue()));
        if (value instanceof Timestamp ts) return DateTimeFormatter.ISO_INSTANT.format(ts.toInstant());
        if (value instanceof OffsetDateTime dt) return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(dt);
        if (value instanceof LocalDateTime dt) return DateTimeFormatter.ISO_INSTANT.format(dt.toInstant(ZoneOffset.UTC));
        String s = value.toString().replace(' ', 'T');
        try { return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(OffsetDateTime.parse(s)); }
        catch (java.time.format.DateTimeParseException ex) { return DateTimeFormatter.ISO_INSTANT.format(LocalDateTime.parse(s).toInstant(ZoneOffset.UTC)); }
    }
    private static ApiException invalid() { return new ApiException(500, "INTERNAL_SERVER_ERROR", "Invalid persisted model parameters"); }
}
