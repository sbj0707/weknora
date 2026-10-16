package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.docreader.GoUtf8;
import com.tencent.weknora.security.*;
import java.io.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Runtime system policy and original-user preferences over the production Go attribute registry.
 */
@Service
public final class GalleryConfigService {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final JsonNode contract;
  private final GoStoredStructDecoder policy, preferences, filters, rules;

  public GalleryConfigService(JdbcTemplate jdbc, ObjectMapper json) throws IOException {
    this.jdbc = jdbc;
    this.json = json;
    try (var in = getClass().getResourceAsStream("/gallery-contract.json")) {
      contract = json.readTree(in);
    }
    policy = new GoStoredStructDecoder(json, contract.path("policy_schema"));
    preferences = new GoStoredStructDecoder(json, contract.path("preferences_schema"));
    filters = new GoStoredStructDecoder(json, contract.path("filters_schema"));
    rules = new GoStoredStructDecoder(json, contract.path("rules_schema"));
  }

  public ObjectNode resolve(AuthContext caller) {
    JsonNode system = json.createObjectNode(), user = json.createObjectNode();
    try {
      var rows =
          jdbc.queryForList(
              "SELECT value,value_type FROM system_settings WHERE key=?", "gallery.policy");
      if (!rows.isEmpty() && "string".equals(rows.getFirst().get("value_type"))) {
        JsonNode value =
            json.reader()
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readTree(raw(rows.getFirst().get("value")));
        if (value != null
            && value.isTextual()
            && !AuthPreferences.trimGoSpace(value.asText()).isEmpty())
          system = policy.decode(value.asText());
      }
    } catch (Exception ignored) {
      // A missing, unavailable or malformed tier is absent in Go; never suppress the gallery.
    }
    if (caller.userId() != null && !caller.userId().isEmpty())
      try {
        var rows = jdbc.queryForList("SELECT preferences FROM users WHERE id=?", caller.userId());
        if (!rows.isEmpty()) {
          String stored = raw(rows.getFirst().get("preferences"));
          if (!stored.isEmpty()) user = preferences.decode(stored).path("gallery");
        }
      } catch (Exception ignored) {
      }
    var result = json.createObjectNode();
    var sources = result.putArray("attribute_sources");
    var attributes = result.putArray("attributes");
    for (var source : contract.path("sources")) {
      String id = source.path("id").asText();
      JsonNode enabled = system.path("sources").get(id);
      if (enabled != null && !enabled.asBoolean()) continue;
      sources.add(id);
      for (var definition : source.path("attrs")) {
        ObjectNode attr = ((ObjectNode) definition).deepCopy();
        String attrId = id + ":" + attr.path("name").asText();
        attr.put("id", attrId).put("source", id).put("usage_from", "source");
        var override = system.path("overrides").path(attrId);
        for (String key : List.of("in_filter", "in_searchfield", "in_sortfield")) {
          var value = override.get(key);
          if (value != null && !value.isNull()) {
            ((ObjectNode) attr.get("usage")).set(key, value);
            attr.put("usage_from", "system");
          }
        }
        attributes.add(attr);
      }
    }
    String mode = user.path("mode").asText();
    result.put("mode", mode.equals("custom") ? "custom" : "all");
    var status = result.putObject("status");
    user.path("status")
        .fields()
        .forEachRemaining(
            e -> {
              if (Set.of("on", "off").contains(e.getValue().asText()))
                status.set(e.getKey(), e.getValue());
            });
    return result;
  }

  JsonNode filters(String raw) {
    return decodeOptional(filters, raw);
  }

  JsonNode rules(String raw) {
    return decodeOptional(rules, raw);
  }

  private JsonNode decodeOptional(GoStoredStructDecoder decoder, String raw) {
    try {
      return decoder.decode(raw);
    } catch (Exception ignored) {
      return json.createObjectNode();
    }
  }

  private static String raw(Object value) {
    return value instanceof byte[] bytes ? GoUtf8.decode(bytes) : Objects.toString(value, "");
  }
}
