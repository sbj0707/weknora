package com.tencent.weknora.security;

import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Mirrors Go's independent provisioning/cleanup/token stages; intentionally no global transaction.
 */
public final class OidcIdentityGateway {
  private final JdbcTemplate jdbc;
  private final AuthRepository repository;
  private final JwtCodec jwt;
  private final SecuritySettings settings;
  private final Clock clock;
  private final boolean sqlite;
  private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();

  public OidcIdentityGateway(
      JdbcTemplate jdbc,
      AuthRepository repository,
      JwtCodec jwt,
      SecuritySettings settings,
      Clock clock) {
    this.jdbc = jdbc;
    this.repository = repository;
    this.jwt = jwt;
    this.settings = settings;
    this.clock = clock;
    this.sqlite =
        Boolean.TRUE.equals(
            jdbc.execute(
                (org.springframework.jdbc.core.ConnectionCallback<Boolean>)
                    c ->
                        c.getMetaData()
                            .getDatabaseProductName()
                            .toLowerCase(Locale.ROOT)
                            .contains("sqlite")));
  }

  public Map<String, Object> login(OidcProviderClient.UserInfo info, String provisioning) {
    if (OidcProviderClient.trim(info.email()).isEmpty())
      throw bad("OIDC provider did not return email");
    Map<String, Object> user;
    try {
      user = repository.anyUserByEmail(info.email());
    } catch (DataAccessException ex) {
      throw bad("failed to query user by email: " + databaseError(ex));
    }
    if (user != null)
      try {
        user.put("preferences", new OidcUserPreferences().scan(user.get("preferences")));
      } catch (IllegalArgumentException ex) {
        int index = new ArrayList<>(user.keySet()).indexOf("preferences");
        throw bad(
            "failed to query user by email: sql: Scan error on column index "
                + index
                + ", name \"preferences\": "
                + ex.getMessage());
      }
    boolean fresh = user == null;
    if (fresh) user = provision(info, provisioning);
    if (!AuthRepository.bool(user, "is_active"))
      return Map.of("success", false, "message", "Account is disabled");
    List<Map<String, Object>> memberships;
    try {
      memberships =
          jdbc.queryForList(
              "SELECT * FROM tenant_members WHERE user_id=? AND deleted_at IS NULL ORDER BY"
                  + " joined_at,id",
              AuthRepository.string(user, "id"));
    } catch (DataAccessException ex) {
      memberships = List.of();
    }
    long target = resolve(user, memberships);
    String id = AuthRepository.string(user, "id");
    Instant now = clock.instant();
    String access =
        jwt.sign(
            Map.of(
                "user_id",
                id,
                "email",
                AuthRepository.string(user, "email"),
                "tenant_id",
                target,
                "type",
                "access",
                "iat",
                now.getEpochSecond(),
                "exp",
                now.plusSeconds(86400).getEpochSecond(),
                "jti",
                UUID.randomUUID().toString()));
    String refresh =
        jwt.sign(
            Map.of(
                "user_id",
                id,
                "type",
                "refresh",
                "iat",
                now.getEpochSecond(),
                "exp",
                now.plusSeconds(604800).getEpochSecond(),
                "jti",
                UUID.randomUUID().toString()));
    try {
      repository.storeToken(id, access, "access_token", now.plusSeconds(86400));
    } catch (DataAccessException ex) {
      throw bad("failed to generate local tokens: persist access token: " + databaseError(ex));
    }
    try {
      repository.storeToken(id, refresh, "refresh_token", now.plusSeconds(604800));
    } catch (DataAccessException ex) {
      throw bad("failed to generate local tokens: persist refresh token: " + databaseError(ex));
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("success", true);
    result.put("message", "登录成功");
    result.put("user", repository.publicUser(user));
    ((Map<String, Object>) result.get("user")).put("deleted_at", null);
    result.put("memberships", projectMemberships(memberships));
    result.put("token", access);
    result.put("refresh_token", refresh);
    if (fresh) result.put("is_new_user", true);
    if (target != 0)
      try {
        var tenant = tenant(target);
        if (tenant != null) {
          String role = "";
          for (var m : memberships)
            if (AuthRepository.number(m, "tenant_id") == target
                && AuthRepository.string(m, "status").equals("active"))
              role = AuthRepository.string(m, "role");
          Map<String, Object> dto = repository.publicTenant(tenant, role);
          if (sqlite && !(tenant.get("retriever_engines") instanceof byte[]))
            dto.put("retriever_engines", Collections.singletonMap("engines", null));
          result.put("tenant", dto);
        }
      } catch (DataAccessException ignored) {
      }
    return result;
  }

  private Map<String, Object> provision(OidcProviderClient.UserInfo info, String mode) {
    String base = sanitize(info.username());
    if (base.isEmpty()) base = sanitize(info.email().split("@", -1)[0]);
    if (base.isEmpty()) base = "oidc-user";
    String username = base;
    boolean available = false;
    for (int i = 0; i < 20; i++) {
      try {
        if (repository.anyUserByUsername(username) == null) {
          available = true;
          break;
        }
      } catch (DataAccessException ignored) {
      }
      username = base + "-" + (i + 1);
    }
    if (!available) username = base + "-" + clock.instant().getEpochSecond();
    byte[] raw = new byte[32];
    new SecureRandom().nextBytes(raw);
    String password = Base64.getUrlEncoder().withoutPadding().encodeToString(raw),
        hash = passwords.encode(password);
    if (mode == null || mode.isEmpty()) mode = "create_personal";
    if (!Set.of("tenantless", "create_personal").contains(mode))
      throw bad(
          "failed to auto-provision OIDC user: invalid tenant provisioning mode \"" + mode + "\"");
    long tenant = 0;
    String id = UUID.randomUUID().toString();
    if (mode.equals("create_personal")) {
      try {
        var context =
            Map.of(
                "compression_strategy",
                "",
                "max_tokens",
                0,
                "recent_message_count",
                0,
                "summarize_threshold",
                0);
        Long created =
            jdbc.queryForObject(
                "INSERT INTO"
                    + " tenants(name,description,status,business,retriever_engines,context_config,storage_quota,storage_used)"
                    + " VALUES(?,?,'active','',"
                    + repository.jsonParameter()
                    + ","
                    + repository.jsonParameter()
                    + ",10737418240,0) RETURNING id",
                Long.class,
                username + "'s Workspace",
                "Default workspace",
                sqlJson(Map.of("engines", List.of())),
                sqlJson(context));
        tenant = created;
        try {
          var storage =
              RegistrationStorageSnapshot.from(
                  settings.environment(), new GoSecretCodec(settings.environment()));
          String backend = UUID.randomUUID().toString();
          jdbc.update(
              "INSERT INTO"
                  + " storage_backends(id,tenant_id,name,provider,config,source,status,legacy_alias)"
                  + " VALUES(?,?,?,?,"
                  + repository.jsonParameter()
                  + ",'env','active',true)",
              backend,
              tenant,
              "System " + storage.provider().toUpperCase(Locale.ROOT),
              storage.provider(),
              repository.jsonString(storage.config()));
          try {
            jdbc.update(
                "UPDATE tenants SET default_storage_backend_id=? WHERE id=?", backend, tenant);
          } catch (RuntimeException ex) {
            cleanup("storage_backends", backend);
            throw ex;
          }
        } catch (RuntimeException ex) {
          cleanup("tenants", tenant);
          throw ex;
        }
      } catch (RuntimeException ex) {
        throw bad("failed to auto-provision OIDC user: failed to create workspace");
      }
    }
    try {
      jdbc.update(
          "INSERT INTO"
              + " users(id,username,email,password_hash,avatar,tenant_id,is_active,can_access_all_tenants,is_system_admin,preferences)"
              + " VALUES(?,?,?,?,'',?,true,false,false,"
              + repository.jsonParameter()
              + ")",
          id,
          username,
          info.email(),
          hash,
          tenant == 0 ? null : tenant,
          repository.jsonString(Map.of()));
    } catch (DataAccessException ex) {
      if (tenant != 0) cleanup("tenants", tenant);
      throw bad("failed to auto-provision OIDC user: failed to create user");
    }
    if (tenant != 0)
      try {
        jdbc.update(
            "INSERT INTO tenant_members(user_id,tenant_id,role,status)"
                + " VALUES(?,?,'owner','active')",
            id,
            tenant);
      } catch (DataAccessException ex) {
        cleanup("users", id);
        cleanup("tenants", tenant);
        throw bad("failed to auto-provision OIDC user: failed to finalise workspace ownership");
      }
    Map<String, Object> user = repository.user(id);
    Map<String, Object> preferences = repository.preferences(user);
    preferences.put("oidc_only_login", true);
    try {
      repository.savePreferences(id, preferences);
    } catch (DataAccessException ex) {
      throw bad("failed to mark OIDC-only login preference: " + databaseError(ex));
    }
    user.put("preferences", repository.jsonString(preferences));
    return user;
  }

  private Object sqlJson(Object value) {
    String raw = repository.jsonString(value);
    return sqlite ? raw.getBytes(java.nio.charset.StandardCharsets.UTF_8) : raw;
  }

  private void cleanup(String table, Object id) {
    try {
      jdbc.update(
          "UPDATE " + table + " SET deleted_at=CURRENT_TIMESTAMP WHERE id=? AND deleted_at IS NULL",
          id);
    } catch (DataAccessException ignored) {
    }
  }

  private long resolve(Map<String, Object> user, List<Map<String, Object>> memberships) {
    String id = AuthRepository.string(user, "id");
    Map<String, Object> preferences = repository.preferences(user);
    long home = AuthRepository.number(user, "tenant_id"),
        preferred = AuthRepository.number(preferences, "last_active_tenant_id");
    boolean all = AuthRepository.bool(user, "can_access_all_tenants");
    if (preferred != 0 && preferred != home) {
      boolean exists = false;
      try {
        exists = tenant(preferred) != null;
      } catch (DataAccessException ignored) {
      }
      if (exists && (all || member(memberships, preferred))) return preferred;
      preferences.remove("last_active_tenant_id");
      user.put("preferences", repository.jsonString(preferences));
      try {
        saveUser(user);
      } catch (DataAccessException ignored) {
      }
    }
    if (home != 0 && (all || member(memberships, home))) return home;
    if (home != 0) {
      user.put("tenant_id", 0L);
      if (preferred == home) {
        preferences.remove("last_active_tenant_id");
        user.put("preferences", repository.jsonString(preferences));
      }
      try {
        repository.saveHomeTenant(id, 0);
        saveUser(user);
      } catch (DataAccessException ignored) {
      }
    }
    for (var row : memberships) {
      long first = AuthRepository.number(row, "tenant_id");
      if (first == 0 || !AuthRepository.string(row, "status").equals("active")) continue;
      try {
        if (tenant(first) == null) continue;
      } catch (DataAccessException ignored) {
        continue;
      }
      user.put("tenant_id", first);
      try {
        saveUser(user);
      } catch (DataAccessException ignored) {
        user.put("tenant_id", 0L);
      }
      return first;
    }
    return 0;
  }

  private Map<String, Object> tenant(long id) {
    var rows = jdbc.queryForList("SELECT * FROM tenants WHERE id=? AND deleted_at IS NULL", id);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private void saveUser(Map<String, Object> user) {
    String id = AuthRepository.string(user, "id");
    long home = AuthRepository.number(user, "tenant_id");
    String preferences = repository.jsonString(repository.preferences(user));
    if (home == 0) {
      var manager =
          new org.springframework.jdbc.datasource.DataSourceTransactionManager(
              jdbc.getDataSource());
      new org.springframework.transaction.support.TransactionTemplate(manager)
          .executeWithoutResult(
              status -> {
                jdbc.update(
                    "UPDATE users SET preferences="
                        + repository.jsonParameter()
                        + ",updated_at=CURRENT_TIMESTAMP WHERE id=? AND deleted_at IS NULL",
                    preferences,
                    id);
                jdbc.update(
                    "UPDATE users SET tenant_id=NULL WHERE id=? AND deleted_at IS NULL", id);
              });
    } else
      jdbc.update(
          "UPDATE users SET tenant_id=?,preferences="
              + repository.jsonParameter()
              + ",updated_at=CURRENT_TIMESTAMP WHERE id=? AND deleted_at IS NULL",
          home,
          preferences,
          id);
  }

  private List<Map<String, Object>> projectMemberships(List<Map<String, Object>> members) {
    List<Map<String, Object>> projected = new ArrayList<>();
    for (var m : members) {
      if (!AuthRepository.string(m, "status").equals("active")) continue;
      Map<String, Object> tenant;
      try {
        tenant = tenant(AuthRepository.number(m, "tenant_id"));
      } catch (DataAccessException ignored) {
        continue;
      }
      if (tenant == null
          || OidcProviderClient.trim(AuthRepository.string(tenant, "name")).isEmpty()) continue;
      projected.add(
          Map.of(
              "tenant_id",
              AuthRepository.number(m, "tenant_id"),
              "tenant_name",
              AuthRepository.string(tenant, "name"),
              "role",
              AuthRepository.string(m, "role")));
    }
    return projected;
  }

  private static boolean member(List<Map<String, Object>> memberships, long tenant) {
    return memberships.stream()
        .anyMatch(
            m ->
                AuthRepository.number(m, "tenant_id") == tenant
                    && AuthRepository.string(m, "status").equals("active"));
  }

  private static String sanitize(String value) {
    String input = OidcProviderClient.trim(value);
    StringBuilder out = new StringBuilder();
    boolean dash = false;
    for (int raw : input.codePoints().toArray()) {
      int cp = Character.toLowerCase(raw);
      if (cp >= 'a' && cp <= 'z' || cp >= '0' && cp <= '9' || cp == '_' || cp == '.') {
        out.appendCodePoint(cp);
        dash = false;
      } else if (!dash) {
        out.append('-');
        dash = true;
      }
    }
    String result = strip(out.toString());
    if (result.length() > 50) result = strip(result.substring(0, 50));
    return result;
  }

  private static String strip(String value) {
    int start = 0, end = value.length();
    while (start < end && "-._".indexOf(value.charAt(start)) >= 0) start++;
    while (end > start && "-._".indexOf(value.charAt(end - 1)) >= 0) end--;
    return value.substring(start, end);
  }

  static String databaseError(DataAccessException error) {
    String message = error.getMostSpecificCause().getMessage();
    if (message != null && message.startsWith("[SQLITE_") && message.endsWith(")")) {
      int start = message.lastIndexOf('(');
      if (start >= 0) return message.substring(start + 1, message.length() - 1);
    }
    return Objects.toString(message, "database error");
  }

  private static IllegalArgumentException bad(String message) {
    return new IllegalArgumentException(message);
  }
}
