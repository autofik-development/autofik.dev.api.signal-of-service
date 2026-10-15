package com.autofik.sos.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GeoTest {

  @Test
  void ringRadiusFor_roundsUp_andAddsOneRingOfBuffer() {
    assertThat(Geo.ringRadiusFor(10, 2.0)).isEqualTo(6); // exact multiple: 5 + 1
    assertThat(Geo.ringRadiusFor(11, 2.0)).isEqualTo(7); // 5.5 rounds up to 6, + 1
    assertThat(Geo.ringRadiusFor(0, 2.0)).isEqualTo(1); // never less than the centre's own ring
  }

  @Test
  void wgs84_isSrid4326() {
    assertThat(Geo.WGS84.getSRID()).isEqualTo(4326);
  }
}
