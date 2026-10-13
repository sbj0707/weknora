package com.tencent.weknora.security;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Interoperable Go OIDC HMAC state signing and validation. */
public final class OidcState {
  public record Payload(String nonce, String redirectUri, long issuedAt) {}

  private final ObjectMapper json;
  private final byte[] key;
  private final Clock clock;
  private final GoStoredStructDecoder decoder;

  public OidcState(ObjectMapper json, String key, Clock clock) {
    this.json = json;
    this.key = AuthPreferences.trimGoSpace(key).getBytes(StandardCharsets.UTF_8);
    this.clock = clock;
    ObjectNode schema = json.createObjectNode();
    schema.put("kind", "struct");
    ObjectNode fields = schema.putObject("fields");
    fields.putObject("nonce").put("kind", "string");
    fields.putObject("redirect_uri").put("kind", "string");
    fields.putObject("iat").put("kind", "int64").put("bits", 64);
    decoder = new GoStoredStructDecoder(json, schema);
  }

  public String sign(Payload payload) {
    if (payload == null) throw bad("oidc state payload is required");
    if (AuthPreferences.trimGoSpace(payload.nonce()).isEmpty())
      throw bad("oidc state nonce is required");
    if (AuthPreferences.trimGoSpace(payload.redirectUri()).isEmpty())
      throw bad("oidc state redirect_uri is required");
    ObjectNode body =
        json.createObjectNode()
            .put("nonce", payload.nonce())
            .put("redirect_uri", payload.redirectUri())
            .put(
                "iat",
                payload.issuedAt() == 0 ? clock.instant().getEpochSecond() : payload.issuedAt());
    try {
      String raw =
          json.writeValueAsString(body)
              .replace("&", "\\u0026")
              .replace("<", "\\u003c")
              .replace(">", "\\u003e")
              .replace("\u2028", "\\u2028")
              .replace("\u2029", "\\u2029");
      byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
      return encode(bytes) + "." + encode(mac(bytes));
    } catch (Exception error) {
      throw bad("marshal oidc state: " + error.getMessage());
    }
  }

  public Payload verify(String state) {
    String[] parts = AuthPreferences.trimGoSpace(state).split("\\.", -1);
    if (parts.length != 2) throw bad("invalid oidc state format");
    byte[] raw = decode(parts[0], "payload"), signature = decode(parts[1], "signature");
    if (!MessageDigest.isEqual(mac(raw), signature)) throw bad("oidc state signature mismatch");
    JsonNode body;
    try {
      body = decoder.decode(new String(raw, StandardCharsets.UTF_8));
    } catch (IllegalArgumentException failure) {
      throw bad("unmarshal oidc state: Invalid typed JSON payload");
    }
    Payload payload =
        new Payload(
            body.path("nonce").asText(""),
            body.path("redirect_uri").asText(""),
            body.path("iat").asLong());
    if (AuthPreferences.trimGoSpace(payload.redirectUri()).isEmpty())
      throw bad("state.redirect_uri is required");
    if (payload.issuedAt() == 0) throw bad("state.iat is required");
    // Compare exact nanos without int64 subtraction overflow, including extreme signed timestamps.
    Instant now = clock.instant();
    BigInteger elapsed =
        BigInteger.valueOf(now.getEpochSecond())
            .subtract(BigInteger.valueOf(payload.issuedAt()))
            .multiply(BigInteger.valueOf(1_000_000_000L))
            .add(BigInteger.valueOf(now.getNano()));
    if (elapsed.compareTo(BigInteger.valueOf(600_000_000_000L)) > 0
        || elapsed.compareTo(BigInteger.valueOf(-60_000_000_000L)) < 0)
      throw bad("oidc state expired or invalid timestamp");
    return payload;
  }

  private byte[] mac(byte[] raw) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(raw);
    } catch (Exception failure) {
      throw new IllegalStateException("OIDC state signing failed", failure);
    }
  }

  private static String encode(byte[] raw) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
  }

  private static byte[] decode(String raw, String part) {
    byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
    int count = 0, last = 0;
    for (int i = 0; i < bytes.length; i++) {
      int b = bytes[i] & 255;
      if (b == '\n' || b == '\r') continue;
      if (b >= 'A' && b <= 'Z'
          || b >= 'a' && b <= 'z'
          || b >= '0' && b <= '9'
          || b == '-'
          || b == '_') {
        if (count % 4 == 0) last = i;
        count++;
      } else throw bad("decode oidc state " + part + ": illegal base64 data at input byte " + i);
    }
    if (count % 4 == 1)
      throw bad("decode oidc state " + part + ": illegal base64 data at input byte " + last);
    return Base64.getUrlDecoder().decode(raw.replace("\r", "").replace("\n", ""));
  }

  private static IllegalArgumentException bad(String message) {
    return new IllegalArgumentException(message);
  }
}
