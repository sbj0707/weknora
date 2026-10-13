package com.tencent.weknora.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** HS256 tokens use the same claims and signing bytes as Go's golang-jwt. */
@Component
public class JwtCodec {
    private final ObjectMapper mapper;
    private final byte[] secret;
    private final Clock clock;
    @Autowired
    public JwtCodec(ObjectMapper mapper, @Value("${weknora.jwt-secret:${JWT_SECRET:}}") String secret) {
        this(mapper, secret, Clock.systemUTC());
    }
    JwtCodec(ObjectMapper mapper, String secret, Clock clock) {
        if (secret == null || secret.isBlank() || secret.equals("weknora-jwt-secret") || secret.equals("CHANGE-ME-jwt-secret")) {
            throw new IllegalStateException("Configure the same JWT_SECRET as the Go backend before starting Java");
        }
        this.mapper = mapper;
        this.secret = secret.trim().getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }
    public Map<String, Object> verifyAccess(String token) {
        Map<String, Object> claims = verify(token);
        Object type = claims.get("type");
        if ("refresh".equals(type) || "sandbox_terminal".equals(type)) throw unauthorized();
        return claims;
    }
    public Map<String, Object> verify(String token) {
        try {
            if (token == null || token.length() > 16384) throw unauthorized();
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3) throw unauthorized();
            Map<String, Object> header = decode(parts[0]);
            String algorithm = String.valueOf(header.get("alg"));
            if (!java.util.Set.of("HS256", "HS384", "HS512").contains(algorithm)) throw unauthorized();
            byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
            if (!MessageDigest.isEqual(hmac(parts[0] + "." + parts[1], algorithm), signature)) throw unauthorized();
            Map<String, Object> claims = decode(parts[1]);
            long now = clock.instant().getEpochSecond();
            if (claims.get("exp") != null && (!(claims.get("exp") instanceof Number exp) || !Double.isFinite(exp.doubleValue()) || (exp.doubleValue() != 0 && Math.floor(exp.doubleValue()) <= now))) throw unauthorized();
            if (claims.get("nbf") != null && (!(claims.get("nbf") instanceof Number nbf) || !Double.isFinite(nbf.doubleValue()) || Math.floor(nbf.doubleValue()) > now)) throw unauthorized();
            if (!(claims.get("user_id") instanceof String userId) || userId.isBlank()) throw unauthorized();
            return claims;
        } catch (ApiException ex) { throw ex; }
        catch (Exception ex) { throw unauthorized(); }
    }
    public String sign(Map<String, Object> claims) {
        try {
            String content = encode(mapper.writeValueAsBytes(Map.of("alg", "HS256", "typ", "JWT"))) + "." + encode(mapper.writeValueAsBytes(claims));
            return content + "." + encode(hmac(content, "HS256"));
        } catch (Exception ex) { throw new IllegalStateException("JWT signing failed", ex); }
    }
    private Map<String, Object> decode(String part) throws Exception {
        return mapper.readValue(Base64.getUrlDecoder().decode(part), new TypeReference<Map<String, Object>>() { });
    }
    private static String encode(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private byte[] hmac(String content, String algorithm) throws Exception {
        String jca = "HmacSHA" + algorithm.substring(2);
        Mac mac = Mac.getInstance(jca);
        mac.init(new SecretKeySpec(secret, jca));
        return mac.doFinal(content.getBytes(StandardCharsets.US_ASCII));
    }
    private static ApiException unauthorized() { return new ApiException(401, "UNAUTHORIZED", "Unauthorized: invalid or expired token"); }
}
