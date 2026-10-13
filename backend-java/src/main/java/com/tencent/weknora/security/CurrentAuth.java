package com.tencent.weknora.security;

import jakarta.servlet.http.HttpServletRequest;

public final class CurrentAuth {
    public static final String ATTRIBUTE = AuthContext.class.getName();
    private CurrentAuth() { }
    public static AuthContext get(HttpServletRequest request) {
        if (request.getAttribute(ATTRIBUTE) instanceof AuthContext auth) return auth;
        throw new ApiException(401, "UNAUTHORIZED", "Unauthorized: missing authentication");
    }
}
