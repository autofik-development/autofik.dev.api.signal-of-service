package com.autofik.sos.service.impl;

import com.autofik.sos.dto.external.DistanceInfo;
import com.autofik.sos.service.GoogleMapsService;
import com.google.maps.DistanceMatrixApi;
import com.google.maps.GeoApiContext;
import com.google.maps.GeocodingApi;
import com.google.maps.model.AddressType;
import com.google.maps.model.DistanceMatrix;
import com.google.maps.model.DistanceMatrixElement;
import com.google.maps.model.DistanceMatrixElementStatus;
import com.google.maps.model.DistanceMatrixRow;
import com.google.maps.model.GeocodingResult;
import com.google.maps.model.LatLng;
import com.google.maps.model.TravelMode;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Point;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j // Lombok: Automatically adds an slf4j 'log' field to record system warnings/errors
@Service // Registers this implementation as a Spring-managed service bean
@RequiredArgsConstructor
public class GoogleMapsServiceImpl implements GoogleMapsService {

  // The foundational configuration bean holding your secure Google Maps API Key credentials
  private final GeoApiContext geoApiContext;

  // Injected by Spring; stays null when the class is built by hand (tests), which counts as
  // enabled.
  @Value("${google.maps.api-key:}")
  private String apiKey;

  // Set by Spring; stays NOOP when the class is built by hand (tests), which records nothing.
  @Autowired(required = false)
  ObservationRegistry observationRegistry = ObservationRegistry.NOOP;

  static final String GEOCODE = "google.maps.geocode";
  static final String DISTANCE_MATRIX = "google.maps.distance-matrix";

  // With no key every call is rejected by Google ("Invalid API key") after a real network round
  // trip, so skip the call: same null result, no outbound traffic, no latency on SOS create.
  private boolean disabled() {
    return apiKey != null && apiKey.isBlank();
  }

  /**
   * Each Google call as a span of its own, under whatever request, socket ping or sweep made it: it
   * is billed, and slow whenever Google is. The span carries its name and, for a batch, how many
   * origins were priced; never the coordinates or the address. A failure is recorded on the span,
   * then handled below as before (logged, null).
   */
  private <T> T observed(String name, int origins, Observation.CheckedCallable<T, Exception> call)
      throws Exception {
    Observation observation = Observation.createNotStarted(name, observationRegistry);
    if (origins > 1) {
      observation.highCardinalityKeyValue("google.maps.origins", String.valueOf(origins));
    }
    return observation.observeChecked(call);
  }

  /** WORKFLOW: Translates raw numeric GPS coordinates back into a text address. */
  @Override
  public String reverseGeocode(double latitude, double longitude) {
    if (disabled()) return null;
    try {
      // 1. API HIT: Request address details from the Google Geocoding API endpoint
      GeocodingResult[] results =
          observed(
              GEOCODE,
              1,
              () ->
                  GeocodingApi.reverseGeocode(geoApiContext, new LatLng(latitude, longitude))
                      .await()); // Synchronously waits for Google's servers to respond

      if (results.length == 0) return null;

      // 2. EXTRACTION: Prefer a human-readable address over a bare Plus Code entry.
      // For points without a street number (e.g. inside a campus), Google's first
      // result is often type plus_code ("HV9R+86Q, Phnom Penh"); skip those.
      for (GeocodingResult result : results) {
        boolean isPlusCodeOnly =
            result.types != null
                && result.types.length == 1
                && result.types[0] == AddressType.PLUS_CODE;
        if (!isPlusCodeOnly) return result.formattedAddress;
      }
      return results[0].formattedAddress; // every result was a plus code — use it as-is
    } catch (Exception e) {
      // Safe Failure: Log a clear warning message but return null so the application layer doesn't
      // crash
      log.warn("Reverse geocode failed for ({}, {}): {}", latitude, longitude, e.getMessage());
      return null;
    }
  }

  /** WORKFLOW: Hits the Distance Matrix API to calculate driving mileage and time frames. */
  @Override
  public DistanceInfo getDistanceInfo(Point origin, Point destination) {
    if (disabled()) return null;
    try {
      // 1. API REQUEST: Set up a new driving route lookup payload matrix
      // Note: In JTS Topology geometry, point coordinates map as: Point.getX() = Longitude,
      // Point.getY() = Latitude
      DistanceMatrix matrix =
          observed(
              DISTANCE_MATRIX,
              1,
              () ->
                  DistanceMatrixApi.newRequest(geoApiContext)
                      .origins(
                          new LatLng(origin.getY(), origin.getX())) // Mechanic starting coordinates
                      .destinations(
                          new LatLng(
                              destination.getY(),
                              destination.getX())) // Stranded Driver target coordinates
                      .mode(TravelMode.DRIVING) // Forces calculation along car-navigable roads
                      .await()); // Executes the network call blocks until completion

      // 2. PARSING: Pull the first coordinate element block from the data matrix table grid
      DistanceMatrixElement element = matrix.rows[0].elements[0];

      // 3. STATUS VALIDATION: Ensure a drivable path actually exists (e.g., fails if checking
      // across separate islands)
      if (element.status != DistanceMatrixElementStatus.OK) return null;

      // 4. MAP TO DTO: Construct and return our structured immutable record data layout
      // representation
      return new DistanceInfo(
          element.distance.inMeters, // Raw numeric metrics (e.g., 5230)
          element.duration.inSeconds, // Raw timing metrics (e.g., 720)
          element.distance.humanReadable, // Formatted text layout (e.g., "5.2 km")
          element.duration.humanReadable // Formatted timing layout (e.g., "12 mins")
          );
    } catch (Exception e) {
      // Safe Failure: Log the problem footprint securely and return null to shield the active
      // business flow
      log.warn(
          "Distance matrix failed (origin={}, destination={}): {}",
          origin,
          destination,
          e.getMessage());
      return null;
    }
  }

  /** WORKFLOW: One Distance Matrix request for many origins and a single destination. */
  @Override
  public List<DistanceInfo> getDistanceInfos(List<Point> origins, Point destination) {
    if (origins.isEmpty()) return List.of();
    if (disabled()) return null;
    try {
      LatLng[] from =
          origins.stream().map(p -> new LatLng(p.getY(), p.getX())).toArray(LatLng[]::new);
      DistanceMatrix matrix =
          observed(
              DISTANCE_MATRIX,
              from.length,
              () ->
                  DistanceMatrixApi.newRequest(geoApiContext)
                      .origins(from)
                      .destinations(new LatLng(destination.getY(), destination.getX()))
                      .mode(TravelMode.DRIVING)
                      .await());

      // One row per origin, each with a single element (the one destination).
      List<DistanceInfo> result = new ArrayList<>(origins.size());
      for (DistanceMatrixRow row : matrix.rows) {
        DistanceMatrixElement element = row.elements[0];
        result.add(
            element.status != DistanceMatrixElementStatus.OK
                ? null
                : new DistanceInfo(
                    element.distance.inMeters,
                    element.duration.inSeconds,
                    element.distance.humanReadable,
                    element.duration.humanReadable));
      }
      return result;
    } catch (Exception e) {
      log.warn(
          "Distance matrix failed ({} origins, destination={}): {}",
          origins.size(),
          destination,
          e.getMessage());
      return null;
    }
  }
}
