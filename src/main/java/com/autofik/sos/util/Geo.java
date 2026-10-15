package com.autofik.sos.util;

import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;

/** Geo helpers shared by the services that build PostGIS points and search H3 cells. */
public final class Geo {

  /** Builds WGS-84 (SRID 4326) geometries; thread-safe, so one instance serves everyone. */
  public static final GeometryFactory WGS84 = new GeometryFactory(new PrecisionModel(), 4326);

  private Geo() {}

  /**
   * Converts a km radius into the minimum kRing depth that fully covers it at resolution 7. One
   * extra ring of buffer accounts for partial hex overlap at the boundary.
   */
  public static int ringRadiusFor(int radiusKm, double h3CenterToCenterKm) {
    return (int) Math.ceil(radiusKm / h3CenterToCenterKm) + 1;
  }
}
