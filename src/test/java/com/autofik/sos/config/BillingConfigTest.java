package com.autofik.sos.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Startup check: billing switched on needs both a URL and a key. */
class BillingConfigTest {

  private static BillingConfig config(boolean enabled, String url, String key) {
    BillingConfig c = new BillingConfig();
    c.setEnabled(enabled);
    c.setBaseUrl(url);
    c.setServiceKey(key);
    return c;
  }

  @Test
  void disabled_needsNothing() {
    assertThatCode(() -> config(false, null, null).requireUrlAndKeyWhenEnabled())
        .doesNotThrowAnyException();
  }

  @Test
  void enabledWithUrlAndKey_starts() {
    assertThatCode(() -> config(true, "http://billing", "k").requireUrlAndKeyWhenEnabled())
        .doesNotThrowAnyException();
  }

  @Test
  void enabledWithoutAUrl_stops() {
    assertThatThrownBy(() -> config(true, null, "k").requireUrlAndKeyWhenEnabled())
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> config(true, "  ", "k").requireUrlAndKeyWhenEnabled())
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void enabledWithoutAKey_stops() {
    assertThatThrownBy(() -> config(true, "http://billing", null).requireUrlAndKeyWhenEnabled())
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> config(true, "http://billing", " ").requireUrlAndKeyWhenEnabled())
        .isInstanceOf(IllegalStateException.class);
  }
}
