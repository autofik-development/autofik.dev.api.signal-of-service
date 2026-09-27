package com.autofik.sos.dto.external;

/**
 * Live route progress for an SOS in flight: how far the mechanic still has to drive and how long
 * that should take. Always derived from a live Google route; when Google returns nothing there is
 * no EtaInfo.
 */
public record EtaInfo(
    long remainingDistanceM,
    long remainingDurationS,
    String remainingDistanceText,
    String remainingDurationText) {}
