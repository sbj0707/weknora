package com.tencent.weknora.security;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Runtime DB overrides use the same fallback and bootstrap-row rules as Go. */
@Component
public class SecuritySettings {
    private static final Logger LOG = LoggerFactory.getLogger(SecuritySettings.class);
    private final AuthRepository repository;
    private final Environment environment;
    private final Map<?, ?> goConfig;
    public SecuritySettings(AuthRepository repository, Environment environment) {
        this.repository = repository;
        this.environment = environment;
        this.goConfig = readGoConfig(environment);
    }
    boolean complexPasswordEnabled() {
        return bool("auth.complex_password_enabled", "WEKNORA_AUTH_COMPLEX_PASSWORD_ENABLED", false,
                configBool("auth", "complex_password_enabled", false));
    }
    boolean selfServiceCreationEnabled() {
        return bool("tenant.self_service_creation_enabled", "WEKNORA_TENANT_SELF_SERVICE_CREATION_ENABLED", true,
                configBool("tenant", "self_service_creation_enabled", true));
    }
    boolean autoAcceptInvitation() { return bool("tenant.auto_accept_invitation", "WEKNORA_TENANT_AUTO_ACCEPT_INVITATION", false, false); }
    public boolean crossTenantEnabled() {
        String env = environment.getProperty("WEKNORA_TENANT_ENABLE_CROSS_TENANT_ACCESS");
        if (env != null && !env.trim().isEmpty()) return env.trim().equalsIgnoreCase("true");
        return configBool("tenant", "enable_cross_tenant_access", false);
    }
    boolean rbacEnabled() {
        String env = environment.getProperty("WEKNORA_TENANT_ENABLE_RBAC");
        if (env != null && !env.trim().isEmpty()) return env.trim().equalsIgnoreCase("true");
        return configBool("tenant", "enable_rbac", true);
    }
    String registrationMode() {
        String configured = configString("auth","registration_mode","self_serve");
        if("true".equalsIgnoreCase(environment.getProperty("DISABLE_REGISTRATION","").trim())) configured="invite_only";
        return string("auth.registration_mode","","self_serve",configured);
    }
    String defaultTenantMode() {
        String configured=configString("auth","default_tenant_mode","create_personal");
        String resolved=string("auth.default_tenant_mode","WEKNORA_AUTH_DEFAULT_TENANT_MODE","create_personal",configured);
        return resolved.equals("tenantless")?"tenantless":"create_personal";
    }
    String frontendBaseUrl() {
        String override=environment.getProperty("weknora.frontend-base-url");
        if(override!=null)return override;
        Object configured=goConfig.get("frontend_base_url");
        return configured instanceof String value?value:"";
    }
    Environment environment() { return environment; }
    private String string(String key,String envName,String registryDefault,String configured) {
        try{
            Map<String,Object> row=repository.setting(key);
            if(row!=null) {
                Object value=repository.jsonValue(row.get("value"));
                boolean bootstrap=AuthPreferences.trimGoSpace(AuthRepository.string(row,"last_modified_by")).isEmpty()&&registryDefault.equals(value);
                if(!bootstrap&&value instanceof String text)return text;
            }
        }catch(org.springframework.dao.DataAccessException|ApiException ex){LOG.warn("Authentication setting {} unavailable; using environment/config fallback",key);}
        String env=envName.isEmpty()?null:environment.getProperty(envName);
        return env==null||env.isEmpty()?configured:env;
    }
    private String configString(String group,String key,String fallback) {
        String property=environment.getProperty("weknora."+group+"."+key.replace('_','-'));
        if(property!=null&&!property.trim().isEmpty())return property.trim();
        Object section=goConfig.get(group);
        if(section instanceof Map<?,?> map&&map.get(key) instanceof String value&&!value.trim().isEmpty())return value.trim();
        return fallback;
    }
    private boolean bool(String key, String envName, boolean registryDefault, boolean configured) {
        try {
            Map<String, Object> row = repository.setting(key);
            if (row != null) {
                Object value = repository.jsonValue(row.get("value"));
                boolean bootstrap = AuthPreferences.trimGoSpace(AuthRepository.string(row, "last_modified_by")).isEmpty()
                        && Boolean.valueOf(registryDefault).equals(value);
                if (!bootstrap && value instanceof Boolean b) return b;
            }
        } catch (org.springframework.dao.DataAccessException | ApiException ex) {
            LOG.warn("Authentication setting {} unavailable; using environment/config fallback", key);
        }
        Boolean env = parseBool(environment.getProperty(envName));
        return env == null ? configured : env;
    }
    private boolean configBool(String group, String key, boolean fallback) {
        // Spring-specific configuration can override the legacy YAML fallback;
        // WEKNORA env and DB tiers still retain their normal precedence.
        Boolean property = parseBool(environment.getProperty("weknora." + group + "." + key.replace('_', '-')));
        if (property != null) return property;
        Object section = goConfig.get(group);
        if (section instanceof Map<?, ?> map && map.get(key) instanceof Boolean value) return value;
        return fallback;
    }
    private static Boolean parseBool(String raw) {
        if (raw == null) return null;
        return switch (raw) {
            case "1", "t", "T", "TRUE", "true", "True" -> true;
            case "0", "f", "F", "FALSE", "false", "False" -> false;
            default -> null;
        };
    }
    private static Map<?, ?> readGoConfig(Environment environment) {
        String configuredPath = environment.getProperty("weknora.go-config-path", environment.getProperty("JAVA_GO_CONFIG"));
        List<Path> paths = configuredPath == null ? List.of(Path.of("config.yaml"), Path.of("config/config.yaml"), Path.of("../config/config.yaml")) : List.of(Path.of(configuredPath));
        for (Path path : paths) {
            if (!Files.isRegularFile(path)) continue;
            try (var stream = Files.newInputStream(path)) {
                Object document = new Yaml(new SafeConstructor(new LoaderOptions())).load(stream);
                if (document instanceof Map<?, ?> map) return map;
                throw new IllegalStateException("Go configuration must contain a YAML object");
            } catch (Exception ex) { throw new IllegalStateException("Cannot load Go authentication configuration from " + path, ex); }
        }
        if (configuredPath != null) throw new IllegalStateException("Go authentication configuration file does not exist: " + configuredPath);
        return Map.of();
    }
}
