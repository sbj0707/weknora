package com.tencent.weknora.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.weknora.model.client.CallContext;
import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Native public OIDC routes; provider and identity stages preserve Go behavior. */
@RestController
@RequestMapping("/api/v1/auth/oidc")
public final class OidcController {
  private final Supplier<OidcProviderClient.Configuration> configuration;
  private final OidcProviderClient provider;
  private final OidcState states;
  private final OidcIdentityGateway identity;
  private final Supplier<String> defaultTenant;
  private final ObjectMapper json = new ObjectMapper();

  @org.springframework.beans.factory.annotation.Autowired
  public OidcController(
      OidcSettings configuration,
      OidcProviderClient provider,
      OidcState states,
      OidcIdentityGateway identity,
      SecuritySettings settings) {
    this(configuration::configuration, provider, states, identity, settings::defaultTenantMode);
  }

  public OidcController(
      Supplier<OidcProviderClient.Configuration> configuration,
      OidcProviderClient provider,
      OidcState states,
      OidcIdentityGateway identity,
      Supplier<String> defaultTenant) {
    this.configuration = configuration;
    this.provider = provider;
    this.states = states;
    this.identity = identity;
    this.defaultTenant = defaultTenant;
  }

  @GetMapping("/config")
  public Map<String, Object> config() {
    var cfg = configuration.get();
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("success", true);
    out.put("enabled", cfg != null && cfg.enabled());
    String name = cfg == null ? "" : OidcProviderClient.trim(cfg.displayName());
    if (!name.isEmpty()) out.put("provider_display_name", name);
    return out;
  }

  @GetMapping("/url")
  public ResponseEntity<?> url(HttpServletRequest request, HttpServletResponse response) {
    String redirect = OidcProviderClient.trim(request.getParameter("redirect_uri"));
    if (redirect.isEmpty()) return error(400, 1010, "redirect_uri is required", null);
    try (var call = new CallContext()) {
      var auth = provider.authorization(call, configuration.get(), redirect);
      nonce(request, response, auth.nonce());
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("success", true);
      if (!auth.displayName().isEmpty()) body.put("provider_display_name", auth.displayName());
      if (!auth.authorizationUrl().isEmpty())
        body.put("authorization_url", auth.authorizationUrl());
      if (!auth.state().isEmpty()) body.put("state", auth.state());
      return ResponseEntity.ok(body);
    } catch (IllegalArgumentException ex) {
      return error(403, 1002, "OIDC authorization unavailable", ex.getMessage());
    }
  }

  @GetMapping("/start")
  public ResponseEntity<?> start(HttpServletRequest request, HttpServletResponse response) {
    String redirect =
        (secure(request) ? "https" : "http")
            + "://"
            + request.getHeader("Host")
            + "/api/v1/auth/oidc/callback";
    try (var call = new CallContext()) {
      var auth = provider.authorization(call, configuration.get(), redirect);
      nonce(request, response, auth.nonce());
      return redirect(auth.authorizationUrl());
    } catch (IllegalArgumentException ex) {
      return error(403, 1002, "OIDC authorization unavailable", ex.getMessage());
    }
  }

  @GetMapping("/callback")
  public ResponseEntity<?> callback(HttpServletRequest request, HttpServletResponse response) {
    String error = OidcProviderClient.trim(request.getParameter("error"));
    if (!error.isEmpty()) {
      String target = "/#oidc_error=" + escape(error);
      String description = OidcProviderClient.trim(request.getParameter("error_description"));
      if (!description.isEmpty()) target += "&oidc_error_description=" + escape(description);
      return redirect(target);
    }
    OidcState.Payload state;
    try {
      state = states.verify(OidcProviderClient.trim(request.getParameter("state")));
      String cookie = null;
      if (request.getCookies() != null)
        for (var c : request.getCookies())
          if (c.getName().equals("weknora_oidc_nonce")) {
            cookie = c.getValue();
            break;
          }
      if (OidcProviderClient.trim(cookie).isEmpty() || !state.nonce().equals(cookie))
        throw new IllegalArgumentException();
    } catch (IllegalArgumentException ex) {
      return redirect("/#oidc_error=invalid_state");
    }
    response.addHeader("Set-Cookie", "weknora_oidc_nonce=; Path=/; Max-Age=0; HttpOnly");
    String code = OidcProviderClient.trim(request.getParameter("code"));
    if (code.isEmpty()) return redirect("/#oidc_error=missing_code");
    Map<String, Object> result;
    try (var call = new CallContext()) {
      var info =
          provider.userInfo(
              call, configuration.get(), code, OidcProviderClient.trim(state.redirectUri()));
      result = identity.login(info, defaultTenant.get());
    } catch (IllegalArgumentException ex) {
      return redirect(
          "/#oidc_error=login_failed&oidc_error_description=" + escape(ex.getMessage()));
    }
    if (!Boolean.TRUE.equals(result.get("success")))
      return redirect(
          "/#oidc_error=login_failed&oidc_error_description="
              + escape(Objects.toString(result.get("message"), "")));
    try {
      String payload =
          Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(result));
      return redirect("/#oidc_result=" + escape(payload));
    } catch (Exception ex) {
      return redirect("/#oidc_error=payload_encode_failed");
    }
  }

  private static ResponseEntity<?> error(int status, int code, String message, String details) {
    Map<String, Object> error = new LinkedHashMap<>();
    error.put("code", code);
    error.put("message", message);
    error.put("details", details);
    return ResponseEntity.status(status).body(Map.of("success", false, "error", error));
  }

  private static void nonce(
      HttpServletRequest request, HttpServletResponse response, String nonce) {
    if (nonce.isEmpty()) return;
    response.addHeader(
        "Set-Cookie",
        "weknora_oidc_nonce="
            + nonce
            + "; Path=/; Max-Age=600; HttpOnly"
            + (secure(request) ? "; Secure" : "")
            + "; SameSite=Lax");
  }

  private static boolean secure(HttpServletRequest request) {
    return request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"));
  }

  private static ResponseEntity<?> redirect(String target) {
    StringBuilder ascii = new StringBuilder();
    for (byte b : target.getBytes(StandardCharsets.UTF_8)) {
      int c = b & 255;
      if (c >= 128) ascii.append(String.format("%%%02x", c));
      else ascii.append((char) c);
    }
    String body =
        "<a href=\""
            + target
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&#34;")
                .replace("'", "&#39;")
            + "\">Found</a>.\n\n";
    return ResponseEntity.status(302)
        .header("Location", ascii.toString())
        .header("Content-Type", "text/html; charset=utf-8")
        .body(body);
  }

  private static String escape(String value) {
    return value
        .replace("%", "%25")
        .replace(" ", "%20")
        .replace("#", "%23")
        .replace("&", "%26")
        .replace("+", "%2B")
        .replace("=", "%3D")
        .replace("?", "%3F");
  }
}
