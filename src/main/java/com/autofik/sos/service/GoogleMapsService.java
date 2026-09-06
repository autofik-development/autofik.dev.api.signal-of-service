package com.autofik.sos.service;

import com.autofik.sos.dto.external.DistanceInfo;
import org.locationtech.jts.geom.Point;

/**
 * Service Interface managing external integrations with Google Maps API. Handles spatial
 * coordinates, address translations, and real road travel calculations.
 */
public interface GoogleMapsService {

  /**
   * use case: convert raw coordinate into human-readable string Example: Inputs (11.5683, 104.8917)
   * -> Returns "Tuol Kouk, Phnom Penh, Cambodia".
   *
   * @param latitude latitude The GPS latitude coordinate.
   * @param longitude longitude The GPS longitude coordinate.
   * @return A readable address string representing the location.
   */
  String reverseGeocode(double latitude, double longitude);

  /**
   * Use case: calcuate real-world driving distance and travel duration
   *
   * @param origin the physical starting point
   * @param destination the physical ending point
   * @return A DistanceInfo DTO containing raw metrics (meters/seconds) and formatted display
   *     strings ("5.2 km", "12 mins").
   */
  DistanceInfo getDistanceInfo(Point origin, Point destination);
}
