package com.tencent.weknora.security;

import java.util.Set;

/** Request-local identity. A system administrator is not implicitly a workspace owner. */
public record AuthContext(String userId, long tenantId, String role, boolean systemAdmin,
                          Long apiKeyId, boolean fullAccess,
                          Set<String> capabilities, Set<String> knowledgeBaseIds,
                          boolean crossTenantSuperuser, boolean rbacEnforced) {
    public AuthContext(String userId, long tenantId, String role, boolean systemAdmin,
                       Long apiKeyId, boolean fullAccess, Set<String> capabilities, Set<String> knowledgeBaseIds) {
        this(userId, tenantId, role, systemAdmin, apiKeyId, fullAccess, capabilities, knowledgeBaseIds, false, true);
    }
    public AuthContext {
        capabilities = Set.copyOf(capabilities);
        knowledgeBaseIds = Set.copyOf(knowledgeBaseIds);
    }
    public boolean isApiKey() { return apiKeyId != null; }
    public boolean allowsKnowledgeBase(String id) {
        return !isApiKey() || knowledgeBaseIds.isEmpty() || knowledgeBaseIds.contains(AuthPreferences.trimGoSpace(id));
    }
    public void requireKnowledgeBase(String id) {
        if (!allowsKnowledgeBase(id)) throw new ApiException(403, "FORBIDDEN", "Forbidden: API key knowledge base scope");
    }
    public void requireCapability(String capability) {
        if (isApiKey() && !fullAccess && !capabilities.contains(capability)) {
            throw new ApiException(403, "FORBIDDEN", "Forbidden: API key capability required");
        }
    }
    public void requireRole(String required) {
        // Machine credentials have already passed the default-deny route policy in AuthFilter.
        if (isApiKey() || crossTenantSuperuser || !rbacEnforced) return;
        if (level(required) == 0 || level(role) < level(required)) {
            throw new ApiException(403, "FORBIDDEN", "Forbidden: insufficient workspace role");
        }
    }
    public boolean isAtLeast(String required) { return level(required) > 0 && (crossTenantSuperuser || !rbacEnforced || level(role) >= level(required)); }
    public static int level(String role) {
        if (role == null) return 0;
        return switch (role) { case "viewer" -> 10; case "contributor" -> 20; case "admin" -> 30; case "owner" -> 40; default -> 0; };
    }
}
