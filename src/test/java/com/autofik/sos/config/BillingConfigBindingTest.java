package com.autofik.sos.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * How Spring binds the BILLING_* values. Every value comes from the environment; an empty tier list
 * must never bind as one blank entry, which would refuse every caller once billing is on.
 */
class BillingConfigBindingTest {

  private static BillingConfig bind(Map<String, String> values) {
    return new Binder(new MapConfigurationPropertySource(values))
        .bind("billing", Bindable.ofInstance(new BillingConfig()))
        .orElseGet(BillingConfig::new);
  }

  @Test
  void everyValue_comesFromTheEnvironment() {
    BillingConfig c =
        bind(
            Map.of(
                "billing.enabled", "true",
                "billing.base-url", "http://billing.internal",
                "billing.service-key", "k",
                "billing.verify-path", "/internal/billings/verify",
                "billing.connect-timeout-ms", "1500",
                "billing.read-timeout-ms", "2500"));

    assertThat(c.isEnabled()).isTrue();
    assertThat(c.getBaseUrl()).isEqualTo("http://billing.internal");
    assertThat(c.getServiceKey()).isEqualTo("k");
    assertThat(c.getVerifyPath()).isEqualTo("/internal/billings/verify");
    assertThat(c.getConnectTimeoutMs()).isEqualTo(1500);
    assertThat(c.getReadTimeoutMs()).isEqualTo(2500);
  }

  @Test
  void nothingSet_leavesBillingOff_andHoldsNoValues() {
    BillingConfig c = bind(Map.of());

    assertThat(c.isEnabled()).isFalse();
    assertThat(c.getBaseUrl()).isNull();
    assertThat(c.getVerifyPath()).isNull();
    assertThat(c.getConnectTimeoutMs()).isZero();
  }

  @Test
  void emptyTierLists_neverBindAsABlankEntry() {
    BillingConfig c =
        bind(
            Map.of(
                "billing.allowed-tiers.customer", "",
                "billing.allowed-tiers.mechanic", ""));

    assertThat(c.getAllowedTiers().getCustomer()).isNullOrEmpty();
    assertThat(c.getAllowedTiers().getMechanic()).isNullOrEmpty();
  }

  @Test
  void commaSeparatedTiers_bindAsAList() {
    BillingConfig c =
        bind(
            Map.of(
                "billing.allowed-tiers.customer", "Pro,Gold",
                "billing.allowed-tiers.mechanic", "Active"));

    assertThat(c.getAllowedTiers().getCustomer()).containsExactly("Pro", "Gold");
    assertThat(c.getAllowedTiers().getMechanic()).containsExactly("Active");
  }
}
