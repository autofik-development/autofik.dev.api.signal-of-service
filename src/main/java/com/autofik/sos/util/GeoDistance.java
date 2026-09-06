package com.autofik.sos.util;

/**
 * Great-circle distance between two WGS-84 points.
 *
 * <p>Deliberately not PostGIS: ST_Distance on SRID 4326 geometry returns degrees, not metres, and
 * getting metres requires a ::geography cast that is easy to forget. Both call sites already hold
 * the coordinates in memory, so there is nothing to gain from a round trip either.
 */
public final class GeoDistance {

  private static final double EARTH_RADIUS_M = 6_371_000;

  private GeoDistance() {}

  public static double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
    double dLat = Math.toRadians(lat2 - lat1);
    double dLng = Math.toRadians(lng2 - lng1);
    double a =
        Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2)
                * Math.sin(dLng / 2);
    return EARTH_RADIUS_M * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  }
}
