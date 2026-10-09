package com.autofik.sos.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Skill tags are dispatch keys compared against the upper-cased Medusa service group key, so they
 * are normalised on write: otherwise "flat_tire" silently never matches problem type "FLAT_TIRE".
 * The SSO profile sync is the only writer now.
 */
class MechanicServiceImplTagsTest {

  @Test
  void normalizeTags_upperCasesTrimsAndDeDuplicates() {
    assertThat(MechanicServiceImpl.normalizeTags(List.of("flat_tire", "  Battery  ", "FLAT_TIRE")))
        .containsExactly("FLAT_TIRE", "BATTERY");
  }

  @Test
  void normalizeTags_dropsNullsAndBlanks() {
    assertThat(MechanicServiceImpl.normalizeTags(Arrays.asList("tow", null, "   ", "")))
        .containsExactly("TOW");
  }

  @Test
  void normalizeTags_isLocaleIndependent() {
    java.util.Locale previous = java.util.Locale.getDefault();
    try {
      java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
      assertThat(MechanicServiceImpl.normalizeTags(List.of("tire"))).containsExactly("TIRE");
    } finally {
      java.util.Locale.setDefault(previous);
    }
  }
}
