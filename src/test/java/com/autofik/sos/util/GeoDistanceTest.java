package com.autofik.sos.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class GeoDistanceTest {

  private static final double R = 6_371_000;

  @Test
  void samePoint_isZero() {
    assertThat(GeoDistance.haversineMeters(11.55, 104.92, 11.55, 104.92, R)).isZero();
  }

  @Test
  void oneDegreeOfLatitude_isAbout111Km() {
    assertThat(GeoDistance.haversineMeters(11, 104, 12, 104, R)).isCloseTo(111_195, within(100.0));
  }

  @Test
  void phnomPenhToSiemReap_isRoughly220Km() {
    double m = GeoDistance.haversineMeters(11.5564, 104.9282, 13.3671, 103.8448, R);
    assertThat(m).isBetween(215_000.0, 235_000.0);
  }

  @Test
  void isSymmetric() {
    double ab = GeoDistance.haversineMeters(11.5, 104.9, 13.3, 103.8, R);
    double ba = GeoDistance.haversineMeters(13.3, 103.8, 11.5, 104.9, R);
    assertThat(ab).isCloseTo(ba, within(1e-6));
  }

  @Test
  void scalesWithTheRadiusParameter() {
    double earth = GeoDistance.haversineMeters(11, 104, 12, 105, R);
    assertThat(GeoDistance.haversineMeters(11, 104, 12, 105, R * 2))
        .isCloseTo(earth * 2, within(1e-6));
  }

  @Test
  void antipodes_areHalfTheCircumference_withoutNaN() {
    double m = GeoDistance.haversineMeters(0, 0, 0, 180, R);
    assertThat(m).isCloseTo(Math.PI * R, within(1.0));
  }

  @Test
  void crossingTheAntimeridian_takesTheShortWay() {
    assertThat(GeoDistance.haversineMeters(0, 179.5, 0, -179.5, R))
        .isCloseTo(111_195, within(100.0));
  }
}
