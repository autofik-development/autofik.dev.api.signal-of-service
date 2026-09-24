package com.autofik.sos.dto.external;

/**
 * Cached result of one Distance Matrix call, plus the coordinates it was measured between. Storing
 * both endpoints lets later pings interpolate progress without another API call: the drop in
 * straight-line distance to the destination is how much of the route was consumed.
 */
public record EtaSnapshot(
    long distanceMeters,
    long durationSeconds,
    double originLat,
    double originLng,
    double destLat,
    double destLng) {}
