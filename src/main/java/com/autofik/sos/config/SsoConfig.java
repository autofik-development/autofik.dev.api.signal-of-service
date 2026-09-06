package com.autofik.sos.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "sso")
@Getter
@Setter
public class SsoConfig {

  /**
   * Everything up to and including the version segment, because the mount differs by environment:
   * {@code http://localhost:4000/api} locally against {@code https://sso.<env>.autofik.com/api/v1}
   * deployed. Paths below it — {@code /user/profile}, {@code /mechanic/profile}, {@code
   * /user/vehicles/{id}} — are the same everywhere, so the difference stays in configuration
   * instead of in code.
   */
  private String baseUrl;

  /**
   * Service credential for {@code verify-token}, the only route it opens. The profile and vehicle
   * reads are scoped to the calling user and need that user's own bearer instead.
   */
  private String publishableApiKey;
}
