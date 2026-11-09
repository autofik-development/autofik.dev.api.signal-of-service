package com.autofik.sos.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Startup check: billing switched on needs its URL, key, path and timeouts from the env. */
class BillingConfigTest {

  /** What a complete BILLING_* environment gives. */
  private static BillingConfig complete() {
    BillingConfig c = new BillingConfig();
    c.setEnabled(true);
    c.setBaseUrl("http://billing");
    c.setServiceKey("k");
    c.setVerifyPath("/internal/billings/verify");
    c.setConnectTimeoutMs(2000);
    c.setReadTimeoutMs(3000);
    return c;
  }

  @Test
  void disabled_needsNothing() {
    assertThatCode(() -> new BillingConfig().requireSettingsWhenEnabled())
        .doesNotThrowAnyException();
  }

  @Test
  void enabledWithEverything_starts() {
    assertThatCode(() -> complete().requireSettingsWhenEnabled()).doesNotThrowAnyException();
  }

  @Test
  void enabledWithoutAUrl_stops() {
    BillingConfig none = complete();
    none.setBaseUrl(null);
    BillingConfig blank = complete();
    blank.setBaseUrl("  ");

    assertThatThrownBy(none::requireSettingsWhenEnabled).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(blank::requireSettingsWhenEnabled).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void enabledWithoutAKey_stops() {
    BillingConfig none = complete();
    none.setServiceKey(null);
    BillingConfig blank = complete();
    blank.setServiceKey(" ");

    assertThatThrownBy(none::requireSettingsWhenEnabled).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(blank::requireSettingsWhenEnabled).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void enabledWithoutAVerifyPath_stops() {
    BillingConfig c = complete();
    c.setVerifyPath("");

    assertThatThrownBy(c::requireSettingsWhenEnabled).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void enabledWithAZeroTimeout_stops() {
    BillingConfig connect = complete();
    connect.setConnectTimeoutMs(0);
    BillingConfig read = complete();
    read.setReadTimeoutMs(0);

    assertThatThrownBy(connect::requireSettingsWhenEnabled)
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(read::requireSettingsWhenEnabled).isInstanceOf(IllegalStateException.class);
  }
}
