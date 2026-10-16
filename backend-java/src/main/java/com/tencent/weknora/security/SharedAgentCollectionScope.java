package com.tencent.weknora.security;

import com.fasterxml.jackson.databind.*;
import java.math.BigInteger;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Resolves an explicit shared-agent identity before applying its KB selection. */
@Service
public final class SharedAgentCollectionScope {
  public record Scope(long tenant, boolean all, Set<String> ids, JsonNode config) {
    public boolean empty() {
      return !all && ids.isEmpty();
    }

    public boolean allows(long owner, String id) {
      return owner == tenant && !id.isEmpty() && (all || ids.contains(id));
    }
  }

  private final JdbcTemplate jdbc;
  private final GoAgentConfigDecoder configs;
  private final Set<String> builtin;

  @org.springframework.beans.factory.annotation.Autowired
  public SharedAgentCollectionScope(
      JdbcTemplate jdbc, ObjectMapper json, BuiltinAgentRegistry registry) {
    this(jdbc, json, registry.ids());
  }

  public SharedAgentCollectionScope(JdbcTemplate jdbc, ObjectMapper json, Set<String> builtin) {
    this.jdbc = jdbc;
    configs = new GoAgentConfigDecoder(json);
    this.builtin = Set.copyOf(builtin);
  }

  public Scope resolve(AuthContext caller, String id, String selector) {
    if (caller.tenantId() == 0 || caller.userId() == null || caller.userId().isEmpty())
      throw new ApiException(401, "UNAUTHORIZED", "Unauthorized").withAppCode(1001);
    BigInteger source = parseSource(selector);
    if (id.isEmpty()
        || builtin.contains(id)
        || source.equals(BigInteger.valueOf(caller.tenantId()))) throw denied();
    String sql =
        "SELECT s.source_tenant_id FROM agent_shares s JOIN organizations o ON"
            + " o.id=s.organization_id AND o.deleted_at IS NULL JOIN organization_tenant_members"
            + " src ON src.organization_id=s.organization_id AND src.tenant_id=s.source_tenant_id"
            + " JOIN organization_tenant_members dst ON dst.organization_id=s.organization_id AND"
            + " dst.tenant_id=?";
    if (source.signum() != 0)
      sql +=
          " JOIN custom_agents a ON a.id=s.agent_id AND a.tenant_id=s.source_tenant_id AND"
              + " a.deleted_at IS NULL";
    sql +=
        " WHERE s.agent_id=? AND s.deleted_at IS NULL AND s.source_tenant_id"
            + (source.signum() == 0 ? "<>?" : "=?")
            + " ORDER BY s.id LIMIT 1";
    try {
      Object value =
          source.signum() == 0
              ? caller.tenantId()
              : source.bitLength() > 63 ? source.toString() : source.longValue();
      var shares = jdbc.queryForList(sql, caller.tenantId(), id, value);
      if (shares.isEmpty()) throw denied();
      long owner = AuthRepository.number(shares.getFirst(), "source_tenant_id");
      List<Map<String, Object>> agents;
      try {
        agents =
            jdbc.queryForList(
                "SELECT * FROM custom_agents WHERE id=? AND tenant_id=? AND deleted_at IS NULL",
                id,
                owner);
      } catch (RuntimeException ex) {
        if (source.signum() != 0) throw denied();
        throw ex;
      }
      if (agents.isEmpty() || AuthRepository.bool(agents.getFirst(), "is_builtin")) throw denied();
      JsonNode config;
      try {
        config = configs.decode(AuthRepository.string(agents.getFirst(), "config"));
      } catch (RuntimeException ex) {
        if (source.signum() != 0) throw denied();
        throw ex;
      }
      Set<String> ids = new LinkedHashSet<>();
      if (config.path("kb_selection_mode").asText().equals("selected"))
        for (var v : config.path("knowledge_bases")) if (!v.asText().isEmpty()) ids.add(v.asText());
      return new Scope(
          owner, config.path("kb_selection_mode").asText().equals("all"), Set.copyOf(ids), config);
    } catch (ApiException ex) {
      throw ex;
    } catch (RuntimeException ex) {
      throw new ApiException(500, "INTERNAL_SERVER_ERROR", "Failed to verify shared agent access")
          .withAppCode(1007);
    }
  }

  public static void bindUnsigned(String raw) {
    raw = com.tencent.weknora.storage.StoragePaths.trim(raw);
    if (raw.isEmpty()) return;
    String error = unsignedError(raw);
    if (error != null)
      throw new ApiException(400, "BAD_REQUEST", "Invalid request parameters")
          .withAppCode(1000)
          .withDetails(error);
  }

  public static BigInteger parseSource(String raw) {
    raw = com.tencent.weknora.storage.StoragePaths.trim(raw);
    if (raw.isEmpty()) return BigInteger.ZERO;
    String error = unsignedError(raw);
    if (error != null)
      throw new ApiException(400, "BAD_REQUEST", "invalid agent_source_tenant_id: " + error)
          .withAppCode(1000);
    return new BigInteger(raw);
  }

  private static String unsignedError(String raw) {
    String kind =
        !raw.matches("[0-9]+")
            ? "invalid syntax"
            : new BigInteger(raw).bitLength() > 64 ? "value out of range" : null;
    return kind == null ? null : "strconv.ParseUint: parsing " + quote(raw) + ": " + kind;
  }

  private static String quote(String text) {
    try {
      return new ObjectMapper().writeValueAsString(text);
    } catch (Exception ex) {
      throw new IllegalArgumentException(ex);
    }
  }

  private static ApiException denied() {
    return new ApiException(403, "FORBIDDEN", "no permission for this shared agent")
        .withAppCode(1002);
  }
}
