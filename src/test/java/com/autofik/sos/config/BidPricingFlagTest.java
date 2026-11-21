package com.autofik.sos.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;

/**
 * The flag as an operator sets it: SOS_FEATURE_BID_PRICE_ENABLED, resolved through the real
 * application.yaml into the real BidPricing bean.
 */
class BidPricingFlagTest {

  private static final BigDecimal PRICE = new BigDecimal("25.00");

  /** What Spring Boot registers in the real app: an unresolved ${...} is an error, not text. */
  @Configuration
  static class Placeholders {
    @Bean
    static PropertySourcesPlaceholderConfigurer placeholders() {
      return new PropertySourcesPlaceholderConfigurer();
    }
  }

  @Nested
  @SpringBootTest(
      classes = {BidPricing.class, BidPricingFlagTest.Placeholders.class},
      properties = "SOS_FEATURE_BID_PRICE_ENABLED=false")
  class EnvFalse {
    @Autowired BidPricing pricing;

    @Test
    void hidesThePrice() {
      assertThat(pricing.enabled()).isFalse();
      assertThat(pricing.visible(PRICE)).isNull();
    }
  }

  @Nested
  @SpringBootTest(
      classes = {BidPricing.class, BidPricingFlagTest.Placeholders.class},
      properties = "SOS_FEATURE_BID_PRICE_ENABLED=true")
  class EnvTrue {
    @Autowired BidPricing pricing;

    @Test
    void showsThePrice() {
      assertThat(pricing.enabled()).isTrue();
      assertThat(pricing.visible(PRICE)).isEqualByComparingTo(PRICE);
    }
  }

  // Nobody set the variable: the app must still start, with prices off.
  @Nested
  // A developer's .env may set the variable; point the import elsewhere so it is really unset.
  @SpringBootTest(
      classes = {BidPricing.class, BidPricingFlagTest.Placeholders.class},
      properties = "spring.config.import=optional:file:./no-such.env[.properties]")
  class EnvUnset {
    @Autowired BidPricing pricing;

    @Test
    void defaultsToOff() {
      assertThat(pricing.enabled()).isFalse();
    }
  }
}
