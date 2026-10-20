package com.autofik.sos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SosApplicationTimeZoneTest {

  private TimeZone original;

  @BeforeEach
  void remember() {
    original = TimeZone.getDefault();
  }

  @AfterEach
  void restore() {
    TimeZone.setDefault(original);
  }

  @Test
  void withNoSetting_pinsPhnomPenh_whateverTheHostZoneIs() {
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

    SosApplication.pinTimeZone(null);

    assertThat(TimeZone.getDefault().toZoneId()).isEqualTo(ZoneId.of("Asia/Phnom_Penh"));
    assertThat(ZoneId.systemDefault()).isEqualTo(ZoneId.of("Asia/Phnom_Penh"));
  }

  @Test
  void aBlankSetting_fallsBackToPhnomPenh() {
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

    SosApplication.pinTimeZone("  ");

    assertThat(ZoneId.systemDefault()).isEqualTo(ZoneId.of("Asia/Phnom_Penh"));
  }

  @Test
  void anExplicitSetting_wins() {
    SosApplication.pinTimeZone("UTC");

    assertThat(ZoneId.systemDefault()).isEqualTo(ZoneId.of("UTC"));
  }

  @Test
  void anUnknownZone_stopsStartupInsteadOfFallingBackToGmt() {
    assertThatThrownBy(() -> SosApplication.pinTimeZone("Mars/Olympus"))
        .isInstanceOf(DateTimeException.class);
  }

  @Test
  void localDateTimeNow_followsThePinnedZone() {
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

    SosApplication.pinTimeZone("Asia/Phnom_Penh");

    LocalDateTime local = LocalDateTime.now();
    LocalDateTime phnomPenh = LocalDateTime.now(ZoneId.of("Asia/Phnom_Penh"));
    assertThat(Duration.between(local, phnomPenh).abs().toSeconds()).isLessThan(5);
  }
}
