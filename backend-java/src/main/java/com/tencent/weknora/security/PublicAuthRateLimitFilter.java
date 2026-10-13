package com.tencent.weknora.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;import jakarta.servlet.http.*;
import java.io.IOException;import java.util.*;
import org.springframework.core.Ordered;import org.springframework.core.annotation.Order;import org.springframework.stereotype.Component;import org.springframework.web.filter.OncePerRequestFilter;

@Component @Order(Ordered.HIGHEST_PRECEDENCE+90)
final class PublicAuthRateLimitFilter extends OncePerRequestFilter {
    private final PublicAuthRateLimiter limiter;private final ObjectMapper mapper;
    PublicAuthRateLimitFilter(PublicAuthRateLimiter limiter,ObjectMapper mapper){this.limiter=limiter;this.mapper=mapper;}
    @Override protected boolean shouldNotFilter(HttpServletRequest request){return !request.getMethod().equals("POST")||!Set.of("/api/v1/auth/invitations/lookup","/api/v1/auth/register-by-invite").contains(request.getRequestURI());}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{if(limiter.allow(limiter.clientIP(request))){chain.doFilter(request,response);return;}response.setStatus(429);response.setContentType("application/json");Map<String,Object> error=new LinkedHashMap<>();error.put("code",1006);error.put("message","too many requests; please retry shortly");error.put("details",null);mapper.writeValue(response.getOutputStream(),Map.of("success",false,"error",error));}
}
