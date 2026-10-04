package com.autofik.sos.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.autofik.sos.dto.event.MechanicLocationEvent;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MechanicLocationEventTest {

  private static MechanicLocationEvent at(OffsetDateTime timestamp) {
    return new MechanicLocationEvent(
        UUID.randomUUID(),
        UUID.randomUUID(),
        11.55,
        104.92,
        timestamp,
        1234L,
        321L,
        "1.2 km",
        "5 mins");
  }

  @Test
  void withCambodianTimestamp_movesUtcToPlus7_andKeepsTheInstant() {
    MechanicLocationEvent utc =
        at(OffsetDateTime.of(2026, 10, 3, 7, 5, 6, 789_000_000, ZoneOffset.UTC));

    MechanicLocationEvent local = utc.withCambodianTimestamp();

    assertThat(local.timestamp().getOffset()).isEqualTo(ZoneOffset.ofHours(7));
    assertThat(local.timestamp().toString()).isEqualTo("2026-10-03T14:05:06.789+07:00");
    assertThat(local.timestamp().toInstant()).isEqualTo(utc.timestamp().toInstant());
  }

  @Test
  void withCambodianTimestamp_changesNothingElse() {
    MechanicLocationEvent utc = at(OffsetDateTime.now(ZoneOffset.UTC));

    assertThat(utc.withCambodianTimestamp())
        .usingRecursiveComparison()
        .ignoringFields("timestamp")
        .isEqualTo(utc);
  }

  @Test
  void withCambodianTimestamp_nullTimestamp_isLeftAlone() {
    MechanicLocationEvent noTime = at(null);

    assertThat(noTime.withCambodianTimestamp()).isSameAs(noTime);
  }
}
