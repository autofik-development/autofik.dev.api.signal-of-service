package com.autofik.sos.config;

import jakarta.annotation.PostConstruct;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * The billing service SOS asks, on the internal network, whether a customer or mechanic may use
 * SOS. Every value comes from the environment ({@code BILLING_*}, see .env.example) through
 * application.yaml, which is the only place fallbacks live; this class holds none.
 */
@Configuration
@ConfigurationProperties(prefix = "billing")
@Getter
@Setter
public class BillingConfig {

  /** {@code BILLING_ENABLED}. False: nothing in SOS calls billing. */
  private boolean enabled;

  /** {@code BILLING_SERVICE_URL}: internal base URL of the billing service. */
  private String baseUrl;

  /** {@code BILLING_SERVICE_KEY}: sent as {@code X-Service-Key}. Never logged. */
  private String serviceKey;

  /** {@code BILLING_VERIFY_PATH}. ponytail: method and path assumed until billing confirms. */
  private String verifyPath;

  /** {@code BILLING_CONNECT_TIMEOUT_MS}. SOS create and bid wait on this call. */
  private int connectTimeoutMs;

  /** {@code BILLING_READ_TIMEOUT_MS}. */
  private int readTimeoutMs;

  private AllowedTiers allowedTiers = new AllowedTiers();

  /**
   * {@code BILLING_ALLOWED_TIERS_CUSTOMER} / {@code _MECHANIC}: tiers (or plan ids) that may use
   * SOS, comma-separated. Empty or unset means every tier is allowed.
   */
  @Getter
  @Setter
  public static class AllowedTiers {
    private List<String> customer;
    private List<String> mechanic;
  }

  @PostConstruct
  void requireSettingsWhenEnabled() {
    if (!enabled) {
      return;
    }
    if (isBlank(baseUrl) || isBlank(serviceKey) || isBlank(verifyPath)) {
      throw new IllegalStateException(
          "BILLING_ENABLED is true but BILLING_SERVICE_URL, BILLING_SERVICE_KEY or"
              + " BILLING_VERIFY_PATH is empty");
    }
    if (connectTimeoutMs <= 0 || readTimeoutMs <= 0) {
      throw new IllegalStateException(
          "BILLING_CONNECT_TIMEOUT_MS and BILLING_READ_TIMEOUT_MS must be above 0");
    }
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
