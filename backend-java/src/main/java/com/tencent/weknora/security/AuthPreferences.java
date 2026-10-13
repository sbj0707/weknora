package com.tencent.weknora.security;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

/** Go UserPreferences uses pointer fields: null/omitted fields do not patch a value. */
public final class AuthPreferences {
    static final String DEFAULT_BROWSER_SEARCH = "Default search engine: Bing.\nSearch URL: https://www.bing.com/search?q={query}";
    private static final BigInteger MAX_UINT64 = new BigInteger("18446744073709551615");
    private AuthPreferences() { }
    static Map<String, Object> typed(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw == null) return out;
        copy(raw, out, "browser_search_instructions", String.class);
        if (raw.get("last_active_tenant_id") != null) out.put("last_active_tenant_id", unsigned(raw.get("last_active_tenant_id"), false));
        copy(raw, out, "oidc_only_login", Boolean.class);
        Object gallery = raw.get("gallery");
        if (gallery != null) {
            if (!(gallery instanceof Map<?, ?> map)) throw configuration();
            Map<String, Object> block = new LinkedHashMap<>();
            Object mode = map.get("mode");
            if (mode != null && !(mode instanceof String)) throw configuration();
            if (mode instanceof String text && !text.isEmpty()) block.put("mode", text);
            Object status = map.get("status");
            if (status != null) {
                if (!(status instanceof Map<?, ?> fields)) throw configuration();
                Map<String, Object> values = new LinkedHashMap<>();
                for (var entry : fields.entrySet()) {
                    if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof String)) throw configuration();
                    values.put((String)entry.getKey(), entry.getValue());
                }
                if (!values.isEmpty()) block.put("status", values);
            }
            out.put("gallery", block);
        }
        return out;
    }
    static Map<String, Object> patch(Map<String, Object> stored, Map<String, Object> patch) {
        Map<String, Object> out = typed(stored);
        Object instructions = patch.get("browser_search_instructions");
        if (instructions != null) {
            if (!(instructions instanceof String text) || ((String)instructions).codePointCount(0, ((String)instructions).length()) > 4000) {
                throw new ApiException(400, "INVALID_REQUEST", "Invalid preferences request").withAppCode(1010);
            }
            String value = trimGoSpace(text);
            out.remove("browser_search_instructions");
            if (!value.isEmpty() && !value.equals(DEFAULT_BROWSER_SEARCH)) out.put("browser_search_instructions", value);
        }
        Object lastTenant = patch.get("last_active_tenant_id");
        if (lastTenant != null) {
            BigInteger value = unsigned(lastTenant, true);
            out.remove("last_active_tenant_id");
            if (value.signum() != 0) out.put("last_active_tenant_id", value);
        }
        // Handler accepts only these two keys. Gallery and OIDC flags are server-controlled here.
        return out;
    }
    private static BigInteger unsigned(Object value, boolean request) {
        try {
            if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long || value instanceof BigInteger)) throw new NumberFormatException();
            BigInteger result = new BigInteger(value.toString());
            if (result.signum() < 0 || result.compareTo(MAX_UINT64) > 0) throw new NumberFormatException();
            return result;
        } catch (NumberFormatException ex) {
            if (request) throw new ApiException(400, "INVALID_REQUEST", "Invalid preferences request").withAppCode(1010);
            throw configuration();
        }
    }
    private static void copy(Map<String, Object> raw, Map<String, Object> out, String key, Class<?> type) {
        Object value = raw.get(key);
        if (value == null) return;
        if (!type.isInstance(value)) throw configuration();
        out.put(key, value);
    }
    public static String trimGoSpace(String text) {
        int start = 0, end = text.length();
        while (start < end && space(text.codePointAt(start))) start += Character.charCount(text.codePointAt(start));
        while (start < end && space(text.codePointBefore(end))) end -= Character.charCount(text.codePointBefore(end));
        return text.substring(start, end);
    }
    private static boolean space(int rune) {
        return (rune >= 9 && rune <= 13) || (rune >= 0x2000 && rune <= 0x200a)
                || switch (rune) { case 0x20, 0x85, 0xa0, 0x1680, 0x2028, 0x2029, 0x202f, 0x205f, 0x3000 -> true; default -> false; };
    }
    private static ApiException configuration() { return new ApiException(503, "INVALID_AUTH_CONFIGURATION", "Invalid stored user preferences"); }
}
