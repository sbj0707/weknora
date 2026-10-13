package com.tencent.weknora.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.weknora.model.ModelEgressPolicy;
import com.tencent.weknora.model.ModelURLPolicy;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class OidcConfiguration {
  @Bean
  public OidcSettings oidcSettings(Environment environment) {
    return new OidcSettings(environment);
  }

  @Bean
  public OidcState oidcState(
      ObjectMapper json, @Value("${weknora.jwt-secret:${JWT_SECRET:}}") String secret) {
    return new OidcState(json, secret, Clock.systemUTC());
  }

  @Bean(destroyMethod = "close")
  public OidcProviderClient oidcProviderClient(
      ModelEgressPolicy egress, ModelURLPolicy policy, OidcState states) {
    return new OidcProviderClient(egress, policy::validate, Clock.systemUTC(), states);
  }

  @Bean
  public OidcIdentityGateway oidcIdentityGateway(
      JdbcTemplate jdbc, AuthRepository repository, JwtCodec jwt, SecuritySettings settings) {
    return new OidcIdentityGateway(jdbc, repository, jwt, settings, Clock.systemUTC());
  }
}
