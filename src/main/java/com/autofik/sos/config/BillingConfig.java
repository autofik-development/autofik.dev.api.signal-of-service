package com.autofik.sos.config;

import jakarta.annotation.PostConstruct;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * The billing service SOS asks, on the internal network, whether a customer or mechanic may use
 * SOS. Off by default: with {@code billing.enabled=false} nothing in SOS calls billing.
 */
@Configuration
@ConfigurationProperties(prefix = "billing")
@Getter
@Setter
public class BillingConfig {

  private boolean enabled = false;

  /** Internal base URL of the billing service. */
  private String baseUrl;

  /** Sent as {@code X-Service-Key}. Never logged. */
  private String serviceKey;

  // ponytail: method and path are assumed until billing confirms them.
  private String verifyPath = "/internal/billings/verify";

  /** SOS create and bid wait on this call, so it must stay short. */
  private int connectTimeoutMs = 2000;

  private int readTimeoutMs = 3000;

  private AllowedTiers allowedTiers = new AllowedTiers();

  /** Tiers (or plan ids) that may use SOS, per role. Empty means every tier is allowed. */
  @Getter
  @Setter
  public static class AllowedTiers {
    private List<String> customer = List.of();
    private List<String> mechanic = List.of();
  }

  @PostConstruct
  void requireUrlAndKeyWhenEnabled() {
    if (enabled && (isBlank(baseUrl) || isBlank(serviceKey))) {
      throw new IllegalStateException(
          "billing.enabled is true but billing.base-url or billing.service-key is empty");
    }
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
