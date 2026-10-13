package com.tencent.weknora.security;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.chunker.GoJsonFirstValue;
import com.tencent.weknora.docreader.GoUtf8;
import com.tencent.weknora.model.client.*;
import java.io.*;
import java.math.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.RSAPublicKeySpec;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;

/** OIDC provider protocol; identity writes and public routing are separate adapters. */
public final class OidcProviderClient implements AutoCloseable {
  public record Configuration(
      boolean enabled,
      String displayName,
      String issuer,
      String discovery,
      String clientId,
      String clientSecret,
      String authorization,
      String token,
      String userinfo,
      String jwks,
      List<String> scopes,
      String usernameClaim,
      String emailClaim) {
    public Configuration {
      scopes = scopes == null ? List.of() : List.copyOf(scopes);
    }
  }

  public record Authorization(
      String authorizationUrl, String state, String nonce, String displayName) {}

  public record UserInfo(String username, String email, Map<String, Object> claims) {}

  private final SafeModelHttp http;
  private final Consumer<String> validate;
  private final ObjectMapper json = new ObjectMapper();
  private final Clock clock;
  private final OidcState states;

  public OidcProviderClient(
      SafeModelHttp.AddressResolver resolver,
      Consumer<String> validate,
      Clock clock,
      OidcState states) {
    http = new SafeModelHttp(resolver, 10, true);
    this.validate = validate;
    this.clock = clock;
    this.states = states;
  }

  public void close() {
    http.close();
  }

  public Configuration configure(CallContext call, Configuration supplied) {
    if (supplied == null || !supplied.enabled()) throw bad("OIDC login is disabled");
    Configuration cfg = supplied;
    if (trim(cfg.authorization()).isEmpty()
        || trim(cfg.token()).isEmpty()
        || trim(cfg.jwks()).isEmpty()
        || trim(cfg.issuer()).isEmpty()) {
      if (trim(cfg.discovery()).isEmpty()) {
        if (trim(cfg.authorization()).isEmpty() || trim(cfg.token()).isEmpty())
          throw bad("OIDC discovery_url or explicit endpoints are required");
      } else {
        endpoint("discovery", cfg.discovery(), true);
        Map<String, String> doc;
        try (var response = http.get(call, cfg.discovery(), Map.of(), Duration.ofSeconds(30))) {
          if (response.status() < 200 || response.status() >= 300)
            throw bad(
                "OIDC discovery request failed: status="
                    + response.status()
                    + " body="
                    + trim(GoUtf8.decode(response.body().readNBytes(2048))));
          try {
            doc =
                typed(
                    response.body().readNBytes(1 << 20),
                    "oidcDiscoveryDocument",
                    Set.of(
                        "authorization_endpoint",
                        "token_endpoint",
                        "userinfo_endpoint",
                        "jwks_uri",
                        "issuer"));
          } catch (IOException ex) {
            throw bad("failed to decode OIDC discovery document: " + ex.getMessage());
          }
        } catch (IOException
            | ModelClientException
            | java.util.concurrent.CancellationException ex) {
          throw bad("failed to load OIDC discovery document: " + ex.getMessage());
        }
        cfg =
            new Configuration(
                cfg.enabled(),
                cfg.displayName(),
                missing(cfg.issuer(), doc.get("issuer")),
                cfg.discovery(),
                cfg.clientId(),
                cfg.clientSecret(),
                missing(cfg.authorization(), doc.get("authorization_endpoint")),
                missing(cfg.token(), doc.get("token_endpoint")),
                missing(cfg.userinfo(), doc.get("userinfo_endpoint")),
                missing(cfg.jwks(), doc.get("jwks_uri")),
                cfg.scopes(),
                cfg.usernameClaim(),
                cfg.emailClaim());
      }
    }
    if (trim(cfg.authorization()).isEmpty() || trim(cfg.token()).isEmpty())
      throw bad("OIDC discovery document missing required endpoints");
    endpoint("authorization", cfg.authorization(), true);
    endpoint("token", cfg.token(), true);
    endpoint("userinfo", cfg.userinfo(), false);
    endpoint("jwks", cfg.jwks(), false);
    return cfg;
  }

  public Authorization authorization(CallContext call, Configuration supplied, String redirect) {
    var cfg = configure(call, supplied);
    if (trim(redirect).isEmpty()) throw bad("redirect_uri is required");
    byte[] nonceBytes = new byte[24];
    new SecureRandom().nextBytes(nonceBytes);
    String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);
    String state = states.sign(new OidcState.Payload(nonce, trim(redirect), 0));
    Map<String, String> params = new TreeMap<>();
    params.put("response_type", "code");
    params.put("client_id", cfg.clientId());
    params.put("redirect_uri", redirect);
    params.put("scope", String.join(" ", cfg.scopes()));
    params.put("state", state);
    return new Authorization(
        cfg.authorization() + (cfg.authorization().contains("?") ? "&" : "?") + form(params),
        state,
        nonce,
        cfg.displayName());
  }

  public UserInfo userInfo(CallContext call, Configuration supplied, String code, String redirect) {
    if (trim(code).isEmpty()) throw bad("code is required");
    if (trim(redirect).isEmpty()) throw bad("redirect_uri is required");
    var cfg = configure(call, supplied);
    endpoint("token", cfg.token(), true);
    Map<String, String> token;
    Map<String, String> params = new TreeMap<>();
    params.put("grant_type", "authorization_code");
    params.put("code", code);
    params.put("redirect_uri", redirect);
    params.put("client_id", cfg.clientId());
    params.put("client_secret", cfg.clientSecret());
    try (var response =
        http.post(
            call,
            cfg.token(),
            Map.of(
                "Content-Type", "application/x-www-form-urlencoded", "Accept", "application/json"),
            form(params).getBytes(StandardCharsets.UTF_8),
            Duration.ofSeconds(30))) {
      if (response.status() < 200 || response.status() >= 300) {
        response.body().readNBytes(2048);
        throw bad("OIDC token exchange failed: status=" + response.status());
      }
      try {
        token =
            typed(
                response.body().readAllBytes(),
                "oidcTokenResponse",
                Set.of("access_token", "id_token", "token_type"));
      } catch (IOException ex) {
        throw bad("failed to decode OIDC token response: " + ex.getMessage());
      }
    } catch (IOException | ModelClientException | java.util.concurrent.CancellationException ex) {
      throw bad("failed to exchange OIDC code: " + ex.getMessage());
    }
    String access = token.get("access_token"), id = token.get("id_token");
    if (trim(access).isEmpty() && trim(id).isEmpty())
      throw bad("OIDC token response missing access_token and id_token");
    Map<String, Object> claims = new LinkedHashMap<>();
    boolean verified = false;
    if (!trim(id).isEmpty()) {
      if (trim(cfg.jwks()).isEmpty()) {
        if (trim(cfg.userinfo()).isEmpty() || trim(access).isEmpty())
          throw bad("cannot verify OIDC id_token: no jwks_uri configured");
      } else {
        try {
          claims.putAll(verify(call, cfg, trim(id)));
          verified = true;
        } catch (IllegalArgumentException ex) {
          throw bad("OIDC id_token verification failed: " + ex.getMessage());
        }
      }
    }
    if (!trim(cfg.userinfo()).isEmpty() && !trim(access).isEmpty()) {
      try {
        endpoint("userinfo", cfg.userinfo(), true);
        try (var response =
            http.get(
                call,
                cfg.userinfo(),
                Map.of("Authorization", "Bearer " + access, "Accept", "application/json"),
                Duration.ofSeconds(30))) {
          if (response.status() < 200 || response.status() >= 300) {
            response.body().readNBytes(2048);
            throw bad("userinfo request failed: status=" + response.status());
          }
          Map<String, Object> info = claims(response.body().readAllBytes());
          if (info != null) claims.putAll(info);
        }
      } catch (IOException
          | IllegalArgumentException
          | ModelClientException
          | java.util.concurrent.CancellationException ex) {
        if (!verified) throw bad("failed to fetch OIDC userinfo: " + ex.getMessage());
      }
    }
    if (claims.isEmpty()) throw bad("OIDC provider returned no user claims");
    String username = extract(claims, cfg.usernameClaim() == null ? "name" : cfg.usernameClaim()),
        email = extract(claims, cfg.emailClaim() == null ? "email" : cfg.emailClaim());
    if (username.isEmpty()) username = extract(claims, "preferred_username");
    if (username.isEmpty()) username = extract(claims, "name");
    if (username.isEmpty() && !email.isEmpty()) username = email.split("@", -1)[0];
    return new UserInfo(username, email, Collections.unmodifiableMap(claims));
  }

  private Map<String, Object> verify(CallContext call, Configuration cfg, String id) {
    if (trim(cfg.issuer()).isEmpty())
      throw bad("cannot verify OIDC id_token: issuer is not configured");
    if (trim(cfg.clientId()).isEmpty())
      throw bad("cannot verify OIDC id_token: client_id is not configured");
    endpoint("jwks", cfg.jwks(), true);
    List<Map<String, String>> keys;
    try (var response =
        http.get(call, cfg.jwks(), Map.of("Accept", "application/json"), Duration.ofSeconds(30))) {
      if (response.status() < 200 || response.status() >= 300) {
        response.body().readNBytes(2048);
        throw bad("JWKS request failed: status=" + response.status());
      }
      try {
        keys = jwks(response.body().readNBytes(1 << 20));
      } catch (IOException ex) {
        throw bad("failed to decode JWKS document: " + ex.getMessage());
      }
    } catch (IOException | ModelClientException | java.util.concurrent.CancellationException ex) {
      throw bad(ex.getMessage());
    }
    if (keys.isEmpty()) throw bad("JWKS document contains no keys");
    String[] parts = id.split("\\.", -1);
    if (parts.length != 3)
      throw bad(
          "id_token verification failed: token is malformed: token contains an invalid number of"
              + " segments");
    Map<String, Object> header, claims;
    try {
      header = claims(rawUrl(parts[0]));
      claims = claims(rawUrl(parts[1]));
    } catch (IOException | IllegalArgumentException ex) {
      throw bad("id_token verification failed: token is malformed: " + ex.getMessage());
    }
    String alg = header.get("alg") instanceof String value ? value : "";
    if (!Set.of("RS256", "RS384", "RS512").contains(alg))
      throw bad(
          "id_token verification failed: token signature is invalid: signing method "
              + alg
              + " is invalid");
    String kid = header.get("kid") instanceof String value ? value : "";
    List<PublicKey> usable = new ArrayList<>();
    for (var jwk : keys) {
      if (!jwk.get("use").isEmpty() && !jwk.get("use").equalsIgnoreCase("sig")) continue;
      if (!jwk.get("kty").isEmpty() && !jwk.get("kty").equalsIgnoreCase("RSA")) continue;
      if (!kid.isEmpty() && !jwk.get("kid").equals(kid)) continue;
      try {
        if (!jwk.get("kty").equalsIgnoreCase("RSA")) continue;
        byte[] n = jwkBytes(jwk.get("n")), e = jwkBytes(jwk.get("e"));
        BigInteger exponent = new BigInteger(1, e);
        if (n.length == 0 || e.length == 0 || exponent.signum() <= 0 || exponent.bitLength() > 63)
          continue;
        usable.add(
            KeyFactory.getInstance("RSA")
                .generatePublic(new RSAPublicKeySpec(new BigInteger(1, n), exponent)));
      } catch (Exception ignored) {
      }
    }
    String keyError =
        usable.isEmpty()
            ? (kid.isEmpty()
                ? "no matching JWKS key for id_token"
                : "no matching JWKS RSA key for kid \"" + kid + "\"")
            : kid.isEmpty() && usable.size() > 1
                ? "id_token missing kid and JWKS contains multiple RSA signing keys"
                : "";
    if (!keyError.isEmpty())
      throw bad(
          "id_token verification failed: token is unverifiable: error while executing keyfunc: "
              + keyError);
    try {
      Signature signature =
          Signature.getInstance(
              switch (alg) {
                case "RS256" -> "SHA256withRSA";
                case "RS384" -> "SHA384withRSA";
                default -> "SHA512withRSA";
              });
      signature.initVerify(usable.getFirst());
      signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
      if (!signature.verify(rawUrl(parts[2]))) throw new GeneralSecurityException();
    } catch (Exception ex) {
      throw bad(
          "id_token verification failed: token signature is invalid: crypto/rsa: verification"
              + " error");
    }
    List<String> errors = new ArrayList<>();
    double now = clock.instant().getEpochSecond();
    Object exp = claims.get("exp");
    if (claims.containsKey("exp") && !(exp instanceof Number))
      errors.add("invalid type for claim: exp is invalid");
    else if (exp == null || ((Number) exp).doubleValue() == 0)
      errors.add("token is missing required claim: exp claim is required");
    else if (now >= Math.floor(((Number) exp).doubleValue()) + 120) errors.add("token is expired");
    Object nbf = claims.get("nbf");
    if (claims.containsKey("nbf") && !(nbf instanceof Number))
      errors.add("invalid type for claim: nbf is invalid");
    else if (nbf instanceof Number
        && ((Number) nbf).doubleValue() != 0
        && now + 120 < Math.floor(((Number) nbf).doubleValue()))
      errors.add("token is not valid yet");
    Object aud = claims.get("aud");
    if (aud instanceof List<?> audienceList
        && audienceList.stream().anyMatch(v -> !(v instanceof String)))
      errors.add("invalid type for claim: aud is invalid");
    else if (aud == null
        || aud instanceof List<?> list && list.isEmpty()
        || aud instanceof String text && text.isEmpty()
        || !(aud instanceof String || aud instanceof List<?>))
      errors.add("token is missing required claim: aud claim is required");
    else if (!(aud instanceof String a && a.equals(trim(cfg.clientId()))
        || aud instanceof List<?> audienceList && audienceList.contains(trim(cfg.clientId()))))
      errors.add("token has invalid audience");
    Object issuer = claims.get("iss");
    if (claims.containsKey("iss") && !(issuer instanceof String))
      errors.add("invalid type for claim: iss is invalid");
    else if (issuer == null || issuer.equals(""))
      errors.add("token is missing required claim: iss claim is required");
    else if (!issuer.equals(trim(cfg.issuer()))) errors.add("token has invalid issuer");
    if (!errors.isEmpty())
      throw bad(
          "id_token verification failed: token has invalid claims: " + String.join(", ", errors));
    if (extract(claims, "sub").isEmpty()) throw bad("id_token missing sub claim");
    return claims;
  }

  private static byte[] jwkBytes(String value) {
    try {
      byte[] raw = rawUrl(value);
      if (raw.length > 0) return raw;
    } catch (IllegalArgumentException ignored) {
    }
    return Base64.getUrlDecoder().decode(value);
  }

  private static byte[] rawUrl(String value) {
    if (value.indexOf('=') >= 0) throw bad("invalid raw base64url");
    return Base64.getUrlDecoder().decode(value.replace("\r", "").replace("\n", ""));
  }

  private Map<String, Object> claims(byte[] bytes) throws IOException {
    int length = GoJsonFirstValue.length(bytes);
    try (var parser = json.createParser(GoUtf8.decode(Arrays.copyOf(bytes, length)))) {
      JsonToken token = parser.nextToken();
      if (token == JsonToken.VALUE_NULL) return null;
      if (token != JsonToken.START_OBJECT)
        throw new IOException(
            "json: cannot unmarshal "
                + kind(token)
                + " into Go value of type map[string]interface {}");
      return (Map<String, Object>) claimValue(parser);
    }
  }

  private Object claimValue(JsonParser parser) throws IOException {
    JsonToken token = parser.currentToken();
    if (token == JsonToken.START_OBJECT) {
      Map<String, Object> map = new LinkedHashMap<>();
      String name;
      while ((name = parser.nextFieldName()) != null) {
        parser.nextToken();
        map.put(name, claimValue(parser));
      }
      return map;
    }
    if (token == JsonToken.START_ARRAY) {
      List<Object> list = new ArrayList<>();
      while (parser.nextToken() != JsonToken.END_ARRAY) list.add(claimValue(parser));
      return list;
    }
    if (token == JsonToken.VALUE_STRING) return parser.getText();
    if (token == JsonToken.VALUE_NULL) return null;
    if (token == JsonToken.VALUE_TRUE) return true;
    if (token == JsonToken.VALUE_FALSE) return false;
    double value = Double.parseDouble(parser.getText());
    if (!Double.isFinite(value))
      throw new IOException(
          "json: cannot unmarshal number " + parser.getText() + " into Go value of type float64");
    return value;
  }

  private Map<String, String> typed(byte[] bytes, String type, Set<String> fields)
      throws IOException {
    int length = GoJsonFirstValue.length(bytes);
    try (var parser = json.createParser(GoUtf8.decode(Arrays.copyOf(bytes, length)))) {
      return typed(parser, type, fields);
    }
  }

  private Map<String, String> typed(JsonParser parser, String type, Set<String> fields)
      throws IOException {
    return typed(parser, type, fields, null);
  }

  private Map<String, String> typed(
      JsonParser parser, String type, Set<String> fields, Map<String, String> existing)
      throws IOException {
    if (parser.currentToken() == null) parser.nextToken();
    Map<String, String> out = new LinkedHashMap<>();
    fields.forEach(k -> out.put(k, existing == null ? "" : existing.getOrDefault(k, "")));
    if (parser.currentToken() == JsonToken.VALUE_NULL) return out;
    if (parser.currentToken() != JsonToken.START_OBJECT)
      throw new IOException(
          "json: cannot unmarshal "
              + kind(parser.currentToken())
              + " into Go value of type service."
              + type);
    String key;
    while ((key = parser.nextFieldName()) != null) {
      parser.nextToken();
      String field = null;
      for (String candidate : fields)
        if (candidate.equalsIgnoreCase(key)) {
          field = candidate;
          break;
        }
      if (field == null) parser.skipChildren();
      else if (parser.currentToken() == JsonToken.VALUE_STRING) out.put(field, parser.getText());
      else if (parser.currentToken() != JsonToken.VALUE_NULL)
        throw new IOException(
            "json: cannot unmarshal "
                + kind(parser.currentToken())
                + " into Go struct field "
                + type
                + "."
                + field
                + " of type string");
    }
    return out;
  }

  private List<Map<String, String>> jwks(byte[] bytes) throws IOException {
    int length = GoJsonFirstValue.length(bytes);
    try (var parser = json.createParser(GoUtf8.decode(Arrays.copyOf(bytes, length)))) {
      if (parser.nextToken() == JsonToken.VALUE_NULL) return List.of();
      if (parser.currentToken() != JsonToken.START_OBJECT)
        throw new IOException(
            "json: cannot unmarshal "
                + kind(parser.currentToken())
                + " into Go value of type service.oidcJWKS");
      List<Map<String, String>> result = new ArrayList<>();
      String key;
      while ((key = parser.nextFieldName()) != null) {
        parser.nextToken();
        if (!key.equalsIgnoreCase("keys")) {
          parser.skipChildren();
          continue;
        }
        if (parser.currentToken() == JsonToken.VALUE_NULL) {
          result.clear();
          continue;
        }
        if (parser.currentToken() != JsonToken.START_ARRAY)
          throw new IOException(
              "json: cannot unmarshal "
                  + kind(parser.currentToken())
                  + " into Go struct field oidcJWKS.keys of type []service.oidcJWK");
        var old = new ArrayList<>(result);
        result.clear();
        while (parser.nextToken() != JsonToken.END_ARRAY)
          result.add(
              typed(
                  parser,
                  "oidcJWK",
                  Set.of("kty", "kid", "alg", "use", "n", "e"),
                  result.size() < old.size() ? old.get(result.size()) : null));
      }
      return result;
    }
  }

  private static String kind(JsonToken token) {
    return token == JsonToken.START_ARRAY
        ? "array"
        : token == JsonToken.START_OBJECT
            ? "object"
            : token == JsonToken.VALUE_STRING
                ? "string"
                : token == JsonToken.VALUE_TRUE || token == JsonToken.VALUE_FALSE
                    ? "bool"
                    : "number";
  }

  private void endpoint(String label, String value, boolean required) {
    if (trim(value).isEmpty()) {
      if (required) throw bad("OIDC " + label + " endpoint is required");
      return;
    }
    try {
      validate.accept(trim(value));
    } catch (ApiException ex) {
      String reason = ex.getMessage(), prefix = "Base URL 未通过安全校验：";
      if (reason.startsWith(prefix)) {
        reason = reason.substring(prefix.length());
        int end = reason.indexOf("。如该地址确实可信");
        if (end >= 0) reason = reason.substring(0, end);
      }
      if (reason.startsWith("服务端开启了「仅允许白名单出站」")) {
        String host =
            URI.create(trim(value).contains("://") ? trim(value) : "https://" + trim(value))
                .getHost();
        reason =
            "host is not in the SSRF whitelist: "
                + host
                + " (SSRF_DNS_WHITELIST_ONLY is on; add it to SSRF_WHITELIST to allow it)";
      }
      throw bad("OIDC " + label + " endpoint failed SSRF validation: " + reason);
    }
  }

  public static String extract(Map<String, Object> claims, String key) {
    if (trim(key).isEmpty()) return "";
    Object value = claims.get(trim(key));
    if (value == null) return "";
    return trim(value instanceof String s ? s : fmt(value));
  }

  private static String fmt(Object value) {
    if (value instanceof Number n) {
      double d = n.doubleValue();
      if (d == 0) return Double.doubleToRawLongBits(d) < 0 ? "-0" : "0";
      BigDecimal exact = new BigDecimal(d), decimal = BigDecimal.valueOf(d).stripTrailingZeros();
      long bits = Double.doubleToRawLongBits(d);
      for (int digits = 1; digits <= 17; digits++) {
        BigDecimal candidate = exact.round(new MathContext(digits, RoundingMode.HALF_EVEN));
        if (Double.doubleToRawLongBits(Double.parseDouble(candidate.toString())) == bits) {
          decimal = candidate.stripTrailingZeros();
          break;
        }
      }
      int exponent = decimal.precision() - decimal.scale() - 1;
      if (exponent >= -4 && exponent < 6) return decimal.toPlainString();
      String coefficient = decimal.movePointLeft(exponent).stripTrailingZeros().toPlainString();
      return coefficient
          + "e"
          + (exponent < 0 ? "-" : "+")
          + String.format("%02d", Math.abs(exponent));
    }
    if (value instanceof List<?> list)
      return "[" + String.join(" ", list.stream().map(OidcProviderClient::fmt).toList()) + "]";
    if (value instanceof Map<?, ?> map) {
      var keys = map.keySet().stream().map(Object::toString).sorted().toList();
      return "map["
          + String.join(" ", keys.stream().map(k -> k + ":" + fmt(map.get(k))).toList())
          + "]";
    }
    return Objects.toString(value, "<nil>");
  }

  private static String missing(String value, String fallback) {
    return trim(value).isEmpty() ? Objects.toString(fallback, "") : value;
  }

  public static String trim(String text) {
    return AuthPreferences.trimGoSpace(text == null ? "" : text);
  }

  private static String form(Map<String, String> params) {
    return String.join(
        "&",
        params.entrySet().stream()
            .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
            .toList());
  }

  private static String encode(String raw) {
    return URLEncoder.encode(raw, StandardCharsets.UTF_8).replace("*", "%2A").replace("%7E", "~");
  }

  private static IllegalArgumentException bad(String message) {
    return new IllegalArgumentException(message);
  }
}
