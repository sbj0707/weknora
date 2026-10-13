package com.tencent.weknora.security;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import java.util.regex.*;
import org.springframework.core.env.Environment;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Startup OIDC configuration, preserving Go's Viper binding before env/default overrides. */
public final class OidcSettings {
  private final OidcProviderClient.Configuration configuration;

  public OidcSettings(Environment environment) {
    configuration = bind(load(environment), environment::getProperty);
  }

  public OidcProviderClient.Configuration configuration() {
    return configuration;
  }

  static Map<?, ?> load(Environment environment) {
    String configured =
        environment.getProperty(
            "weknora.go-config-path", environment.getProperty("JAVA_GO_CONFIG"));
    List<Path> paths =
        configured == null
            ? List.of(
                Path.of("config.yaml"),
                Path.of("config/config.yaml"),
                Path.of("../config/config.yaml"))
            : List.of(Path.of(configured));
    for (Path path : paths) {
      if (!Files.isRegularFile(path)) continue;
      try {
        String raw = Files.readString(path);
        Matcher substitution = Pattern.compile("\\$\\{([^}]+)}").matcher(raw);
        StringBuffer resolved = new StringBuffer();
        while (substitution.find()) {
          String value = environment.getProperty(substitution.group(1));
          substitution.appendReplacement(
              resolved,
              Matcher.quoteReplacement(
                  value == null || value.isEmpty() ? substitution.group() : value));
        }
        substitution.appendTail(resolved);
        Object document =
            new Yaml(new SafeConstructor(new LoaderOptions())).load(resolved.toString());
        if (document instanceof Map<?, ?> map) return map;
        if (document == null) return Map.of();
        throw new IllegalStateException("Go configuration must contain a YAML object");
      } catch (IOException ex) {
        throw new IllegalStateException("Cannot load Go OIDC configuration from " + path, ex);
      }
    }
    if (configured != null)
      throw new IllegalStateException("Go OIDC configuration file does not exist: " + configured);
    return Map.of();
  }

  public static OidcProviderClient.Configuration bind(
      Map<?, ?> document, Function<String, String> environment) {
    Object raw = document.get("oidc_auth");
    Map<?, ?> source = section(raw, "oidc_auth");
    Map<String, Object> values = new LinkedHashMap<>();
    String[] fields = {
      "enable",
      "issuer_url",
      "discovery_url",
      "provider_display_name",
      "client_id",
      "client_secret",
      "authorization_endpoint",
      "token_endpoint",
      "user_info_endpoint",
      "jwks_uri",
      "scopes"
    };
    for (String field : fields) {
      Object value = source.get(field);
      String env = environment.apply("OIDC_AUTH_" + field.toUpperCase(Locale.ROOT));
      values.put(field, source.containsKey(field) && env != null && !env.isEmpty() ? env : value);
    }
    boolean enabled = weakBoolean(values.get("enable"));
    Map<?, ?> mapping = section(source.get("user_info_mapping"), "oidc_auth.user_info_mapping");
    List<String> scopes = weakList(values.get("scopes"));
    String name = weakString(mapping.get("username")), email = weakString(mapping.get("email"));
    Map<String, String> text = new LinkedHashMap<>();
    for (String field : fields) {
      if (!field.equals("enable") && !field.equals("scopes"))
        text.put(field, weakString(values.get(field)));
    }
    for (String field : fields) {
      String value = trim(environment.apply("OIDC_AUTH_" + field.toUpperCase(Locale.ROOT)));
      if (value.isEmpty()) continue;
      if (field.equals("enable")) enabled = value.equalsIgnoreCase("true");
      else if (field.equals("scopes"))
        scopes =
            Arrays.stream(value.replace(',', ' ').split("(?U)\\s+"))
                .filter(s -> !s.isEmpty())
                .toList();
      else text.put(field, value);
    }
    String envName = trim(environment.apply("OIDC_USER_INFO_MAPPING_USER_NAME"));
    if (!envName.isEmpty()) name = envName;
    String envEmail = trim(environment.apply("OIDC_USER_INFO_MAPPING_EMAIL"));
    if (!envEmail.isEmpty()) email = envEmail;
    if (text.get("provider_display_name").isEmpty()) text.put("provider_display_name", "OIDC");
    if (scopes.isEmpty()) scopes = List.of("openid", "profile", "email");
    if (name.isEmpty()) name = "name";
    if (email.isEmpty()) email = "email";
    if (text.get("discovery_url").isEmpty() && !text.get("issuer_url").isEmpty())
      text.put(
          "discovery_url",
          text.get("issuer_url").replaceAll("/+$", "") + "/.well-known/openid-configuration");
    List<String> errors = new ArrayList<>();
    if (enabled) {
      if (trim(text.get("client_id")).isEmpty())
        errors.add("oidc_auth.client_id is required when OIDC is enabled");
      if (trim(text.get("client_secret")).isEmpty())
        errors.add("oidc_auth.client_secret is required when OIDC is enabled");
      if (trim(text.get("discovery_url")).isEmpty()
          && (trim(text.get("authorization_endpoint")).isEmpty()
              || trim(text.get("token_endpoint")).isEmpty()))
        errors.add(
            "oidc_auth.discovery_url or both oidc_auth.authorization_endpoint and"
                + " oidc_auth.token_endpoint are required when OIDC is enabled");
    }
    if (!errors.isEmpty())
      throw new IllegalArgumentException("config validation errors: " + String.join("; ", errors));
    return new OidcProviderClient.Configuration(
        enabled,
        text.get("provider_display_name"),
        text.get("issuer_url"),
        text.get("discovery_url"),
        text.get("client_id"),
        text.get("client_secret"),
        text.get("authorization_endpoint"),
        text.get("token_endpoint"),
        text.get("user_info_endpoint"),
        text.get("jwks_uri"),
        scopes,
        name,
        email);
  }

  private static Map<?, ?> section(Object value, String field) {
    if (value == null) return Map.of();
    if (value instanceof Map<?, ?> map) return map;
    throw decode(
        "'"
            + field
            + "' expected a map or struct, got \""
            + (value instanceof String ? "string" : value.getClass().getSimpleName())
            + "\"");
  }

  private static List<String> weakList(Object value) {
    if (value == null) return List.of();
    if (value instanceof String text) return Arrays.stream(text.split(",", -1)).toList();
    if (value instanceof List<?> list) return list.stream().map(OidcSettings::weakString).toList();
    return List.of(weakString(value));
  }

  private static String weakString(Object value) {
    if (value == null) return "";
    if (value instanceof Boolean b) return b ? "1" : "0";
    return value.toString();
  }

  private static boolean weakBoolean(Object value) {
    if (value == null) return false;
    if (value instanceof Boolean b) return b;
    if (value instanceof Number n) return n.doubleValue() != 0;
    return switch (value.toString()) {
      case "1", "t", "T", "TRUE", "true", "True" -> true;
      case "0", "f", "F", "FALSE", "false", "False" -> false;
      default ->
          throw decode(
              "'oidc_auth.enable' cannot parse value as 'bool': strconv.ParseBool: invalid syntax");
    };
  }

  private static IllegalArgumentException decode(String error) {
    return new IllegalArgumentException(
        "unable to decode config into struct: decoding failed due to the following error(s):\n\n"
            + error);
  }

  private static String trim(String value) {
    return AuthPreferences.trimGoSpace(value == null ? "" : value);
  }
}
