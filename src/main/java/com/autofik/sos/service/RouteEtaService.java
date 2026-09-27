package com.autofik.sos.service;

import com.autofik.sos.dto.external.EtaInfo;
import java.util.UUID;

/**
 * Live "how far / how long until the mechanic arrives" for an SOS being driven to.
 *
 * <p>Distance Matrix is billed per call and the mechanic app pings every few seconds, so the route
 * is refreshed from Google at most once per throttle window and interpolated in between.
 */
public interface RouteEtaService {

  /**
   * Use case: attach remaining distance/duration to a mechanic location ping.
   *
   * @param sosId the SOS being driven to
   * @param mechanicLat the mechanic's current latitude
   * @param mechanicLng the mechanic's current longitude
   * @return current progress, or null when the SOS is not en route (ARRIVED, cancelled, completed,
   *     or not found) or Google Maps returned no route — callers should treat null as "no ETA to
   *     show".
   */
  EtaInfo currentEta(UUID sosId, double mechanicLat, double mechanicLng);

  /**
   * Use case: drop the cached route once the SOS stops being en route, so no stale countdown is
   * served for the remainder of the refresh window.
   */
  void clear(UUID sosId);
}
