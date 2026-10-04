package com.autofik.sos.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * The insecure WebSocket handshake lets a client connect as any user. It must be impossible to
 * leave it on outside local development, even through an environment variable.
 */
class SecurityConfigDevHandshakeTest {

  private static MockEnvironment profiles(String... active) {
    MockEnvironment env = new MockEnvironment();
    env.setActiveProfiles(active);
    return env;
  }

  @Test
  void flagOn_withTheDevProfile_isAllowed() {
    assertThatCode(() -> SecurityConfig.requireDevProfile(true, profiles("dev")))
        .doesNotThrowAnyException();
  }

  @Test
  void flagOn_withProdTestOrNoProfile_refusesToStart() {
    for (String[] active : new String[][] {{"prod"}, {"test"}, {"stg"}, {}}) {
      assertThatThrownBy(() -> SecurityConfig.requireDevProfile(true, profiles(active)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("dev-insecure-handshake");
    }
  }

  @Test
  void flagOff_neverBlocksStartup() {
    for (String[] active : new String[][] {{"prod"}, {"dev"}, {}}) {
      assertThatCode(() -> SecurityConfig.requireDevProfile(false, profiles(active)))
          .doesNotThrowAnyException();
    }
  }
}
