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

  /**
   * Snaps a coordinate to a 0.01° grid — at most ~0.8 km from the real fix at Cambodian latitudes.
   * Deterministic on purpose: random jitter would average back to the real position for anyone who
   * polls the same mechanic enough times.
   */
  // ponytail: square grid; a mechanic on a grid line flips between two cells. H3 centres have the
  // same edge property, so not worth the extra H3Core here.
  public static double coarsen(double degrees) {
    return Math.round(degrees * 100) / 100.0;
  }
}
